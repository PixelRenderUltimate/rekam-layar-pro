package com.rekamlayar.pro

import android.content.Context
import android.graphics.Point
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Membaca batas asli perangkat: ukuran layar, refresh rate, dan kemampuan
 * encoder video. Dipakai untuk mematikan pilihan yang tidak didukung.
 */
object Caps {

    const val MIME_VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC

    private var cacheCaps: MediaCodecInfo.VideoCapabilities? = null
    private var sudahCariCaps = false

    fun kemampuanVideo(): MediaCodecInfo.VideoCapabilities? {
        if (sudahCariCaps) return cacheCaps
        sudahCariCaps = true
        try {
            val daftar = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in daftar.codecInfos) {
                if (!info.isEncoder) continue
                val cocok = info.supportedTypes.any { it.equals(MIME_VIDEO, true) }
                if (!cocok) continue
                // lewati encoder software supaya yang terbaca adalah batas hardware
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly) continue
                cacheCaps = info.getCapabilitiesForType(MIME_VIDEO).videoCapabilities
                return cacheCaps
            }
        } catch (_: Exception) {
        }
        return cacheCaps
    }

    /** Apakah encoder sanggup ukuran dan fps ini. */
    fun sanggup(lebar: Int, tinggi: Int, fps: Int): Boolean {
        val c = kemampuanVideo() ?: return fps <= 60 && tinggi <= 1080
        return try {
            c.areSizeAndRateSupported(lebar, tinggi, fps.toDouble())
        } catch (_: Exception) {
            false
        }
    }

    fun ukuranLayar(ctx: Context): Point {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Point(dm.widthPixels, dm.heightPixels)
        }
    }

    fun kerapatan(ctx: Context): Int = ctx.resources.displayMetrics.densityDpi

    /** Refresh rate tertinggi yang tersedia pada layar. */
    fun refreshHz(ctx: Context): Float {
        return try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val d = wm.defaultDisplay
            val modes = d.supportedModes
            if (modes.isNullOrEmpty()) d.refreshRate
            else modes.maxOf { it.refreshRate }
        } catch (_: Exception) {
            60f
        }
    }

    private fun bulat16(n: Int): Int = (n / 16) * 16

    /**
     * Ukuran rekaman dari sisi pendek yang diminta, mengikuti rasio layar.
     * Hasilnya dibulatkan ke kelipatan 16 agar aman untuk encoder.
     */
    fun ukuranRekam(ctx: Context, sisiPendekTarget: Int): Point {
        val layar = ukuranLayar(ctx)
        val pendek = minOf(layar.x, layar.y)
        val skala = sisiPendekTarget.toDouble() / pendek.toDouble()
        var w = bulat16((layar.x * skala).toInt())
        var h = bulat16((layar.y * skala).toInt())
        if (w < 16) w = 16
        if (h < 16) h = 16
        return Point(w, h)
    }

    /** Daftar alasan untuk pilihan yang harus dimatikan di lembar pengaturan. */
    fun alasanMati(ctx: Context, profil: Profil): Map<String, String> {
        val mati = HashMap<String, String>()
        val layar = ukuranLayar(ctx)
        val pendek = minOf(layar.x, layar.y)
        val panjang = maxOf(layar.x, layar.y)

        for (target in intArrayOf(1080, 1440)) {
            if (pendek < target) {
                mati["res:$target"] =
                    "HP kamu tidak mendukung ${if (target == 1440) "2K" else "${target}p"}. " +
                    "Layarnya ${panjang}×${pendek} piksel."
                continue
            }
            val u = ukuranRekam(ctx, target)
            if (!sanggup(u.x, u.y, 30)) {
                mati["res:$target"] = "Encoder HP kamu tidak sanggup merekam di ${u.x}×${u.y}."
            }
        }

        val hz = refreshHz(ctx)
        val u = ukuranRekam(ctx, profil.tinggi)
        for (fps in intArrayOf(60, 120)) {
            if (hz + 1f < fps) {
                mati["fps:$fps"] =
                    "HP kamu tidak mendukung $fps fps. Layarnya berjalan di ${hz.toInt()} Hz."
                continue
            }
            if (!sanggup(u.x, u.y, fps)) {
                mati["fps:$fps"] =
                    "Encoder HP kamu tidak sanggup $fps fps di ${u.x}×${u.y}. " +
                    "Banyak HP membatasi tangkap layar di 60 fps."
            }
        }
        return mati
    }
}
