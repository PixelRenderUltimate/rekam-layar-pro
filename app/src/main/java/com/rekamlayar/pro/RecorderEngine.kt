package com.rekamlayar.pro

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Inti perekaman.
 *
 * VirtualDisplay menggambar langsung ke input Surface milik MediaCodec, jadi
 * piksel tidak pernah lewat CPU aplikasi. Audio ditangkap terpisah, di-encode
 * jadi AAC, lalu digabung dengan video oleh MediaMuxer.
 *
 * MediaMuxer baru bisa menerima sampel setelah semua track terdaftar, dan track
 * audio baru terdaftar setelah paket AAC pertama keluar. Sampel video yang
 * datang sebelum itu TIDAK dibuang, melainkan ditahan dulu di antrean, lalu
 * ditulis begitu muxer siap. Kalau dibuang, keyframe pertama ikut hilang dan
 * file jadi tidak punya detik nol serta sulit dipotong di aplikasi editor.
 */
class RecorderEngine(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val profil: Profil
) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val KANAL = 2
        private const val TIMEOUT_US = 10_000L
        private const val BATAS_ANTREAN_BYTE = 16 * 1024 * 1024
        private const val BATAS_TUNGGU_MUXER_MS = 3_000L
    }

    private class Tertunda(
        val track: Int,
        val data: ByteArray,
        val pts: Long,
        val flags: Int
    )

    private var videoEnc: MediaCodec? = null
    private var audioEnc: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var vd: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var recSistem: AudioRecord? = null
    private var recMic: AudioRecord? = null
    private var pfd: ParcelFileDescriptor? = null

    private var trackVideo = -1
    private var trackAudio = -1
    private var muxerJalan = false
    private var tanpaAudio = false
    private val kunciMuxer = Any()

    private val antrean = ArrayList<Tertunda>()
    private var antreanByte = 0

    @Volatile private var jalan = false
    @Volatile var dijeda = false
        private set
    var jumlahJeda = 0
        private set

    /** Titik nol bersama untuk video dan audio, dalam mikrodetik. */
    private var t0Us = 0L
    private var totalJedaUs = 0L
    private var mulaiJedaUs = 0L
    private var frameAudio = 0L
    private var geserAudioUs = -1L
    private var tMulaiMs = 0L

    private var akumMs = 0L
    private var segmenMulaiMs = 0L

    var uriKeluaran: Uri? = null
        private set
    var namaFile: String = ""
        private set
    var ukuranRekam: Point = Point(0, 0)
        private set

    private var utasVideo: Thread? = null
    private var utasAudio: Thread? = null

    fun durasiMs(): Long =
        akumMs + if (jalan && !dijeda) SystemClock.elapsedRealtime() - segmenMulaiMs else 0L

    private fun sekarangUs(): Long = System.nanoTime() / 1000

    // ---------------------------------------------------------------- mulai

    @SuppressLint("MissingPermission")
    fun mulai() {
        ukuranRekam = Caps.ukuranRekam(ctx, profil.tinggi)
        siapkanFileKeluaran()

        muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // --- encoder video ---
        val enc = MediaCodec.createEncoderByType(Caps.MIME_VIDEO)
        try {
            enc.configure(formatVideo(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Exception) {
            try { enc.reset() } catch (_: Exception) {}
            enc.configure(formatVideo(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = enc.createInputSurface()
        enc.start()
        videoEnc = enc

        // --- audio disiapkan lebih dulu supaya sudah mengalir saat video mulai ---
        if (profil.audio != "bisu") {
            try {
                siapkanAudio()
            } catch (_: Exception) {
                tanpaAudio = true
            }
        } else {
            tanpaAudio = true
        }

        jalan = true
        dijeda = false
        akumMs = 0L
        jumlahJeda = 0
        segmenMulaiMs = SystemClock.elapsedRealtime()
        t0Us = sekarangUs()
        tMulaiMs = System.currentTimeMillis()

        vd = projection.createVirtualDisplay(
            "RekamLayarPro",
            ukuranRekam.x, ukuranRekam.y, Caps.kerapatan(ctx),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        utasVideo = Thread({ putarVideo() }, "rl-video").also { it.start() }
        if (audioEnc != null) {
            utasAudio = Thread({ putarAudio() }, "rl-audio").also { it.start() }
        }
    }

    private fun formatVideo(pakaiProfilTinggi: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(Caps.MIME_VIDEO, ukuranRekam.x, ukuranRekam.y).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, profil.bitrateVideo(ukuranRekam.x, ukuranRekam.y))
            setInteger(MediaFormat.KEY_FRAME_RATE, profil.fps)
            // Keyframe tiap 2 detik: cukup rapat untuk dipotong di aplikasi
            // editor, tanpa memboroskan bitrate seperti tiap 1 detik.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // Layar yang diam tidak menghasilkan frame baru. Tanpa ini, video
            // punya lubang panjang dan terlihat macet saat diputar.
            setLong(
                MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                (1_000_000L / profil.fps).coerceAtLeast(8_000L)
            )
            if (pakaiProfilTinggi) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
            }
        }

    private fun siapkanFileKeluaran() {
        val cap = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        namaFile = "RekamLayar-$cap.mp4"
        val nilai = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, namaFile)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RekamLayarPro")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), nilai
        ) ?: throw IllegalStateException("Tidak bisa membuat file di penyimpanan.")
        uriKeluaran = uri
        pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Tidak bisa membuka file keluaran.")
    }

    @SuppressLint("MissingPermission")
    private fun siapkanAudio() {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, KANAL).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, profil.bitrateAudio)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        audioEnc = enc

        if (profil.butuhInternal) {
            val konfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val ukuran = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192) * 2
            recSistem = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(ukuran)
                .setAudioPlaybackCaptureConfig(konfig)
                .build()
                .also { it.startRecording() }
        }

        if (profil.butuhMikrofon) {
            val ukuran = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096) * 2
            recMic = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, ukuran
            ).also { it.startRecording() }
        }
    }

    // ----------------------------------------------------------- jeda/stop

    fun jeda() {
        if (!jalan || dijeda) return
        akumMs += SystemClock.elapsedRealtime() - segmenMulaiMs
        mulaiJedaUs = sekarangUs()
        jumlahJeda++
        dijeda = true
    }

    fun lanjut() {
        if (!jalan || !dijeda) return
        totalJedaUs += sekarangUs() - mulaiJedaUs
        segmenMulaiMs = SystemClock.elapsedRealtime()
        dijeda = false
        try {
            videoEnc?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {
        }
    }

    fun hentikan(): Long {
        if (!jalan) return akumMs
        if (!dijeda) akumMs += SystemClock.elapsedRealtime() - segmenMulaiMs
        val durasi = akumMs

        jalan = false
        try { videoEnc?.signalEndOfInputStream() } catch (_: Exception) {}

        try { utasVideo?.join(3000) } catch (_: Exception) {}
        try { utasAudio?.join(3000) } catch (_: Exception) {}

        // Kalau muxer belum sempat jalan, mulai sekarang dan tulis apa yang
        // tertahan, supaya rekaman pendek tetap menghasilkan file utuh.
        synchronized(kunciMuxer) {
            if (!muxerJalan && trackVideo >= 0) {
                tanpaAudio = trackAudio < 0
                mungkinMulaiMuxer()
            }
        }

        try { vd?.release() } catch (_: Exception) {}
        try { videoEnc?.stop(); videoEnc?.release() } catch (_: Exception) {}
        try { audioEnc?.stop(); audioEnc?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        try { recSistem?.stop(); recSistem?.release() } catch (_: Exception) {}
        try { recMic?.stop(); recMic?.release() } catch (_: Exception) {}

        synchronized(kunciMuxer) {
            try { if (muxerJalan) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            muxerJalan = false
            antrean.clear()
            antreanByte = 0
        }

        try { pfd?.close() } catch (_: Exception) {}
        selesaikanFile(durasi)
        return durasi
    }

    private fun selesaikanFile(durasiMs: Long) {
        val uri = uriKeluaran ?: return
        try {
            ctx.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                    put(MediaStore.Video.Media.DURATION, durasiMs)
                },
                null, null
            )
        } catch (_: Exception) {
        }
    }

    fun ukuranFile(): Long {
        val uri = uriKeluaran ?: return 0L
        return try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // -------------------------------------------------------------- video

    private fun putarVideo() {
        val enc = videoEnc ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try {
                enc.dequeueOutputBuffer(info, TIMEOUT_US)
            } catch (_: Exception) {
                break
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(kunciMuxer) {
                    if (trackVideo < 0) {
                        trackVideo = muxer!!.addTrack(enc.outputFormat)
                        mungkinMulaiMuxer()
                    }
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && !dijeda) {
                        val pts = info.presentationTimeUs - t0Us - totalJedaUs
                        if (pts >= 0) {
                            info.presentationTimeUs = pts
                            tulisSample(trackVideo, buf, info)
                        }
                    }
                    try { enc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> {
                    // Jaring pengaman: kalau track audio tidak pernah muncul
                    // (mikrofon ditolak, tangkapan internal diblokir aplikasi
                    // lain), jangan menahan video selamanya.
                    synchronized(kunciMuxer) {
                        if (!muxerJalan && trackVideo >= 0 &&
                            System.currentTimeMillis() - tMulaiMs > BATAS_TUNGGU_MUXER_MS
                        ) {
                            tanpaAudio = true
                            mungkinMulaiMuxer()
                        }
                    }
                    if (!jalan) return
                }
            }
        }
    }

    // -------------------------------------------------------------- audio

    private fun putarAudio() {
        val enc = audioEnc ?: return
        val frameChunk = 1024
        val bufSistem = ShortArray(frameChunk * KANAL)
        val bufMic = ShortArray(frameChunk)
        val keluar = ShortArray(frameChunk * KANAL)

        while (jalan) {
            var jumlahShort = 0
            try {
                when (profil.audio) {
                    "perangkat" -> {
                        val n = recSistem?.read(bufSistem, 0, bufSistem.size) ?: -1
                        if (n > 0) {
                            System.arraycopy(bufSistem, 0, keluar, 0, n)
                            jumlahShort = n
                        }
                    }
                    "mic" -> {
                        val n = recMic?.read(bufMic, 0, bufMic.size) ?: -1
                        if (n > 0) {
                            for (i in 0 until n) {
                                keluar[i * 2] = bufMic[i]
                                keluar[i * 2 + 1] = bufMic[i]
                            }
                            jumlahShort = n * 2
                        }
                    }
                    "dua" -> {
                        val ns = recSistem?.read(bufSistem, 0, bufSistem.size) ?: -1
                        if (ns > 0) {
                            val butuhMono = ns / 2
                            val nm = recMic?.read(bufMic, 0, butuhMono) ?: 0
                            for (i in 0 until butuhMono) {
                                val m = if (i < nm) bufMic[i].toInt() else 0
                                keluar[i * 2] = campur(bufSistem[i * 2].toInt(), m)
                                keluar[i * 2 + 1] = campur(bufSistem[i * 2 + 1].toInt(), m)
                            }
                            jumlahShort = butuhMono * 2
                        }
                    }
                }
            } catch (_: Exception) {
                break
            }

            if (jumlahShort > 0 && !dijeda) {
                if (geserAudioUs < 0) {
                    // Audio selalu tertinggal sedikit dari video karena buffer
                    // perekam harus terisi dulu. Selisih itu diukur sekali di
                    // sini, lalu dipakai sebagai titik awal audio, supaya suara
                    // tidak maju beberapa ratus milidetik dari gambarnya.
                    val durasiPetak = (jumlahShort / KANAL).toLong() * 1_000_000L / SAMPLE_RATE
                    geserAudioUs = (sekarangUs() - t0Us - durasiPetak).coerceAtLeast(0L)
                }
                suapAudio(enc, keluar, jumlahShort)
            }
            tirisAudio(enc)
        }

        try {
            val idx = enc.dequeueInputBuffer(TIMEOUT_US)
            if (idx >= 0) {
                enc.queueInputBuffer(idx, 0, 0, ptsAudio(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (_: Exception) {
        }
        tirisAudio(enc)
    }

    private fun ptsAudio(): Long =
        (if (geserAudioUs > 0) geserAudioUs else 0L) +
            frameAudio * 1_000_000L / SAMPLE_RATE

    private fun campur(a: Int, b: Int): Short {
        val j = a + b
        return when {
            j > Short.MAX_VALUE -> Short.MAX_VALUE
            j < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> j.toShort()
        }
    }

    private fun suapAudio(enc: MediaCodec, data: ShortArray, jumlahShort: Int) {
        try {
            val idx = enc.dequeueInputBuffer(TIMEOUT_US)
            if (idx < 0) return
            val buf = enc.getInputBuffer(idx) ?: return
            buf.clear()
            buf.order(ByteOrder.nativeOrder())
            buf.asShortBuffer().put(data, 0, jumlahShort)
            enc.queueInputBuffer(idx, 0, jumlahShort * 2, ptsAudio(), 0)
            frameAudio += (jumlahShort / KANAL).toLong()
        } catch (_: Exception) {
        }
    }

    private fun tirisAudio(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try {
                enc.dequeueOutputBuffer(info, 0)
            } catch (_: Exception) {
                return
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(kunciMuxer) {
                    if (trackAudio < 0) {
                        trackAudio = muxer!!.addTrack(enc.outputFormat)
                        mungkinMulaiMuxer()
                    }
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0) tulisSample(trackAudio, buf, info)
                    try { enc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    // -------------------------------------------------------------- muxer

    /** Harus dipanggil dari dalam synchronized(kunciMuxer). */
    private fun mungkinMulaiMuxer() {
        if (muxerJalan) return
        if (trackVideo < 0) return
        if (!tanpaAudio && trackAudio < 0) return
        try {
            muxer?.start()
            muxerJalan = true
        } catch (_: Exception) {
            return
        }
        // tulis semua yang tertahan, urut seperti saat datang
        val info = MediaCodec.BufferInfo()
        for (t in antrean) {
            if (tanpaAudio && t.track == trackAudio) continue
            try {
                val bb = ByteBuffer.wrap(t.data)
                info.set(0, t.data.size, t.pts, t.flags)
                muxer?.writeSampleData(t.track, bb, info)
            } catch (_: Exception) {
            }
        }
        antrean.clear()
        antreanByte = 0
    }

    private fun tulisSample(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (track < 0) return
        synchronized(kunciMuxer) {
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            if (muxerJalan) {
                try { muxer?.writeSampleData(track, buf, info) } catch (_: Exception) {}
                return
            }
            // Muxer belum siap: tahan dulu, jangan dibuang.
            if (antreanByte + info.size > BATAS_ANTREAN_BYTE) {
                tanpaAudio = true
                mungkinMulaiMuxer()
                if (muxerJalan) {
                    try { muxer?.writeSampleData(track, buf, info) } catch (_: Exception) {}
                }
                return
            }
            val salinan = ByteArray(info.size)
            buf.get(salinan)
            antrean.add(Tertunda(track, salinan, info.presentationTimeUs, info.flags))
            antreanByte += info.size
        }
    }
}
