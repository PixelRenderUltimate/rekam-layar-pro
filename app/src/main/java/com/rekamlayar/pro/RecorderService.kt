package com.rekamlayar.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

/**
 * Layanan latar depan yang memegang MediaProjection selama rekaman berjalan.
 * Android mewajibkan layanan ini supaya rekaman tidak dimatikan sistem.
 */
class RecorderService : Service() {

    companion object {
        const val AKSI_MULAI = "mulai"
        const val AKSI_JEDA = "jeda"
        const val AKSI_LANJUT = "lanjut"
        const val AKSI_STOP = "stop"
        const val EXTRA_KODE = "kode"
        const val EXTRA_DATA = "data"

        private const val ID_SALURAN = "rekam_status"
        private const val ID_NOTIF = 4711

        @Volatile var berjalan = false
        @Volatile var dijeda = false
        @Volatile var durasiMs = 0L

        fun mulai(ctx: Context, kode: Int, data: Intent) {
            val i = Intent(ctx, RecorderService::class.java).apply {
                action = AKSI_MULAI
                putExtra(EXTRA_KODE, kode)
                putExtra(EXTRA_DATA, data)
            }
            ctx.startForegroundService(i)
        }

        fun kirim(ctx: Context, aksi: String) {
            ctx.startService(Intent(ctx, RecorderService::class.java).apply { action = aksi })
        }
    }

    private var projection: MediaProjection? = null
    private var engine: RecorderEngine? = null
    private var overlay: OverlayControl? = null
    private var profil: Profil = Profil()

    private val handler = Handler(Looper.getMainLooper())
    private val detak = object : Runnable {
        override fun run() {
            val e = engine ?: return
            durasiMs = e.durasiMs()
            dijeda = e.dijeda
            overlay?.perbarui(durasiMs, dijeda)
            perbaruiNotif()
            handler.postDelayed(this, 1000)
        }
    }

    private val callbackProjection = object : MediaProjection.Callback() {
        override fun onStop() {
            hentikan()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AKSI_MULAI -> {
                val kode = intent.getIntExtra(EXTRA_KODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data != null) mulaiRekam(kode, data) else stopSelf()
            }
            AKSI_JEDA -> {
                engine?.jeda()
                dijeda = true
                overlay?.perbarui(durasiMs, true)
                perbaruiNotif()
            }
            AKSI_LANJUT -> {
                engine?.lanjut()
                dijeda = false
                overlay?.perbarui(durasiMs, false)
                perbaruiNotif()
            }
            AKSI_STOP -> hentikan()
        }
        return START_NOT_STICKY
    }

    private fun mulaiRekam(kode: Int, data: Intent) {
        profil = Prefs.muat(this)
        buatSaluran()
        mulaiLatarDepan()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try {
            mpm.getMediaProjection(kode, data)
        } catch (e: Exception) {
            gagal("Izin tangkap layar ditolak sistem.")
            return
        }
        if (p == null) {
            gagal("Izin tangkap layar tidak diberikan.")
            return
        }
        projection = p
        p.registerCallback(callbackProjection, handler)

        val e = RecorderEngine(this, p, profil)
        try {
            e.mulai()
        } catch (ex: Exception) {
            gagal("Gagal memulai rekaman: ${ex.message}")
            return
        }
        engine = e
        berjalan = true
        dijeda = false
        durasiMs = 0L

        overlay = OverlayControl(this, onJeda = {
            if (engine?.dijeda == true) kirim(this, AKSI_LANJUT) else kirim(this, AKSI_JEDA)
        }, onStop = {
            kirim(this, AKSI_STOP)
        }).also { it.tampilkan() }

        handler.postDelayed(detak, 1000)
    }

    private fun hentikan() {
        if (!berjalan) {
            stopSelf()
            return
        }
        berjalan = false
        handler.removeCallbacks(detak)

        val e = engine
        engine = null
        var durasi = 0L
        if (e != null) {
            durasi = try { e.hentikan() } catch (_: Exception) { 0L }
            if (durasi >= 1000) {
                Riwayat.tambah(
                    this,
                    Rekaman(
                        id = System.currentTimeMillis().toString(),
                        uri = e.uriKeluaran?.toString() ?: "",
                        nama = e.namaFile,
                        durasiMs = durasi,
                        ukuranBytes = e.ukuranFile(),
                        tinggi = profil.tinggi,
                        fps = profil.fps,
                        kualitas = profil.kualitas,
                        audio = profil.audio,
                        jeda = e.jumlahJeda
                    )
                )
            }
        }

        try { projection?.unregisterCallback(callbackProjection) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null

        overlay?.sembunyikan()
        overlay = null
        dijeda = false
        durasiMs = 0L

        if (durasi in 1..999) {
            Toast.makeText(this, "Rekaman terlalu pendek, tidak disimpan.", Toast.LENGTH_SHORT).show()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun gagal(alasan: String) {
        Toast.makeText(this, alasan, Toast.LENGTH_LONG).show()
        berjalan = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ notifikasi

    private fun buatSaluran() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ID_SALURAN) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ID_SALURAN, getString(R.string.saluran_rekam),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun aksiNotif(aksi: String): PendingIntent {
        val i = Intent(this, RecorderService::class.java).apply { action = aksi }
        return PendingIntent.getService(
            this, aksi.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun bangunNotif(): Notification {
        val buka = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val judul = if (dijeda) getString(R.string.dijeda) else getString(R.string.merekam)
        val b = Notification.Builder(this, ID_SALURAN)
            .setContentTitle("$judul · ${Format.durasi(durasiMs)}")
            .setContentText("${profil.labelRes} · ${profil.fps} fps · ${profil.labelKualitas}")
            .setSmallIcon(R.drawable.ic_rec)
            .setOngoing(true)
            .setContentIntent(buka)

        if (dijeda) {
            b.addAction(Notification.Action.Builder(null as Icon?, "Lanjutkan", aksiNotif(AKSI_LANJUT)).build())
        } else {
            b.addAction(Notification.Action.Builder(null as Icon?, "Jeda", aksiNotif(AKSI_JEDA)).build())
        }
        b.addAction(Notification.Action.Builder(null as Icon?, "Berhenti", aksiNotif(AKSI_STOP)).build())
        return b.build()
    }

    private fun mulaiLatarDepan() {
        val notif = bangunNotif()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_NOTIF, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID_NOTIF, notif)
        }
    }

    private fun perbaruiNotif() {
        try {
            getSystemService(NotificationManager::class.java).notify(ID_NOTIF, bangunNotif())
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(detak)
        berjalan = false
        super.onDestroy()
    }
}
