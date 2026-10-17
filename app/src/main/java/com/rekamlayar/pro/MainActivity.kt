package com.rekamlayar.pro

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainActivity : AppCompatActivity() {

    private lateinit var profil: Profil
    private lateinit var adapter: RiwayatAdapter

    private lateinit var dot: View
    private lateinit var statusTeks: TextView
    private lateinit var timer: TextView
    private lateinit var bidikKet: TextView
    private lateinit var tombolRekam: TextView
    private lateinit var meter: View
    private lateinit var meterIsi: View
    private lateinit var meterKiri: TextView
    private lateinit var meterKanan: TextView
    private lateinit var riwayatKet: TextView
    private lateinit var riwayatKosong: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val detak = object : Runnable {
        override fun run() {
            gambarStatus()
            handler.postDelayed(this, 500)
        }
    }

    private val mintaTangkapLayar =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { hasil ->
            if (hasil.resultCode == Activity.RESULT_OK && hasil.data != null) {
                RecorderService.mulai(this, hasil.resultCode, hasil.data!!)
            } else {
                Toast.makeText(this, "Rekaman dibatalkan.", Toast.LENGTH_SHORT).show()
            }
        }

    private val mintaIzin =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasil ->
            val butuhMic = profil.butuhMikrofon
            val micOk = !butuhMic || hasil[Manifest.permission.RECORD_AUDIO] != false
            if (!micOk) {
                Toast.makeText(
                    this,
                    "Tanpa izin mikrofon, rekaman akan dibuat tanpa suara mikrofon.",
                    Toast.LENGTH_LONG
                ).show()
            }
            lanjutKeTangkapLayar()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        profil = Prefs.muat(this)

        dot = findViewById(R.id.dot)
        statusTeks = findViewById(R.id.statusTeks)
        timer = findViewById(R.id.timer)
        bidikKet = findViewById(R.id.bidikKet)
        tombolRekam = findViewById(R.id.tombolRekam)
        meter = findViewById(R.id.meter)
        meterIsi = findViewById(R.id.meterIsi)
        meterKiri = findViewById(R.id.meterKiri)
        meterKanan = findViewById(R.id.meterKanan)
        riwayatKet = findViewById(R.id.riwayatKet)
        riwayatKosong = findViewById(R.id.riwayatKosong)

        val daftar = findViewById<RecyclerView>(R.id.daftar)
        daftar.layoutManager = LinearLayoutManager(this)
        adapter = RiwayatAdapter(Riwayat.daftar(this), ::bukaRekaman, ::konfirmasiHapus)
        daftar.adapter = adapter

        findViewById<View>(R.id.chips).setOnClickListener { bukaLembar() }
        findViewById<View>(R.id.hapusSemua).setOnClickListener { konfirmasiKosongkan() }
        tombolRekam.setOnClickListener { tekanRekam() }

        gambarProfil()
        gambarRiwayat()
    }

    override fun onResume() {
        super.onResume()
        profil = Prefs.muat(this)
        gambarProfil()
        gambarRiwayat()
        handler.post(detak)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(detak)
    }

    // ------------------------------------------------------------- tampilan

    private fun gambarProfil() {
        val ukuran = Caps.ukuranRekam(this, profil.tinggi)
        val bitVideo = profil.bitrateVideo(ukuran.x, ukuran.y)

        findViewById<TextView>(R.id.chipRes).text = profil.labelRes
        findViewById<TextView>(R.id.chipFps).text = "${profil.fps} fps"
        findViewById<TextView>(R.id.chipQ).text = profil.labelKualitas
        findViewById<TextView>(R.id.chipA).text = profil.labelAudio

        meterKiri.text = Format.mbps(bitVideo)
        meterKanan.text = "${Format.mbPerMenit(bitVideo, profil.bitrateAudio)} / menit"

        meter.post {
            val rasio = (bitVideo / 55_000_000.0).coerceIn(0.02, 1.0)
            meterIsi.layoutParams = meterIsi.layoutParams.apply {
                width = (meter.width * rasio).toInt()
            }
            meterIsi.requestLayout()
        }
    }

    private fun gambarRiwayat() {
        val d = Riwayat.daftar(this)
        adapter.perbarui(d)
        riwayatKet.text = "${d.size} file"
        riwayatKosong.visibility = if (d.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.hapusSemua).visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun gambarStatus() {
        val jalan = RecorderService.berjalan
        val jeda = RecorderService.dijeda
        val durasi = RecorderService.durasiMs

        timer.text = Format.durasi(if (jalan) durasi else 0L)
        timer.setTextColor(
            ContextCompat.getColor(this, if (jalan) R.color.fg else R.color.muted)
        )
        statusTeks.text = when {
            !jalan -> getString(R.string.siap)
            jeda -> getString(R.string.dijeda)
            else -> getString(R.string.merekam)
        }
        dot.setBackgroundResource(
            when {
                !jalan -> R.drawable.bg_bulat_muted
                jeda -> R.drawable.bg_bulat_kuning
                else -> R.drawable.bg_bulat_merah
            }
        )
        tombolRekam.visibility = if (jalan) View.GONE else View.VISIBLE
        bidikKet.text = when {
            jalan && jeda -> "Dijeda — lanjutkan dari kontrol mengambang."
            jalan -> "Kontrol mengambang aktif. Geser ke mana saja."
            else -> "Pengaturan tersimpan. Tinggal pencet rekam."
        }
    }

    // ------------------------------------------------------------- rekaman

    private fun tekanRekam() {
        if (RecorderService.berjalan) return

        val perlu = mutableListOf<String>()
        if (profil.butuhMikrofon &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) perlu.add(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) perlu.add(Manifest.permission.POST_NOTIFICATIONS)

        if (perlu.isNotEmpty()) {
            mintaIzin.launch(perlu.toTypedArray())
            return
        }
        lanjutKeTangkapLayar()
    }

    private fun lanjutKeTangkapLayar() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Izin kontrol mengambang")
                .setMessage(
                    "Supaya tombol jeda dan stop tetap terlihat saat kamu pindah ke aplikasi lain, " +
                        "Rekam Layar Pro perlu izin \"Tampilkan di atas aplikasi lain\".\n\n" +
                        "Tanpa izin ini rekaman tetap jalan, tapi jeda dan stop hanya bisa dari notifikasi."
                )
                .setPositiveButton("Buka pengaturan") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("Lanjut tanpa izin") { _, _ -> mintaProyeksi() }
                .show()
            return
        }
        mintaProyeksi()
    }

    private fun mintaProyeksi() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mintaTangkapLayar.launch(mpm.createScreenCaptureIntent())
    }

    private fun bukaRekaman(r: Rekaman) {
        if (r.uri.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(r.uri), "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            Toast.makeText(this, "Tidak ada aplikasi pemutar video.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun konfirmasiHapus(r: Rekaman) {
        AlertDialog.Builder(this)
            .setTitle("Hapus rekaman")
            .setMessage("${r.nama} akan dihapus dari HP. Tindakan ini tidak bisa dibatalkan.")
            .setPositiveButton("Hapus") { _, _ ->
                try {
                    if (r.uri.isNotEmpty()) contentResolver.delete(Uri.parse(r.uri), null, null)
                } catch (_: Exception) {
                }
                Riwayat.hapus(this, r.id)
                gambarRiwayat()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun konfirmasiKosongkan() {
        AlertDialog.Builder(this)
            .setTitle("Hapus semua riwayat")
            .setMessage("Semua file rekaman akan dihapus dari HP. Tindakan ini tidak bisa dibatalkan.")
            .setPositiveButton("Hapus semua") { _, _ ->
                Riwayat.daftar(this).forEach { r ->
                    try {
                        if (r.uri.isNotEmpty()) contentResolver.delete(Uri.parse(r.uri), null, null)
                    } catch (_: Exception) {
                    }
                }
                Riwayat.kosongkan(this)
                gambarRiwayat()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // --------------------------------------------------- lembar pengaturan

    private fun bukaLembar() {
        if (RecorderService.berjalan) {
            Toast.makeText(this, "Hentikan rekaman dulu sebelum mengubah pengaturan.", Toast.LENGTH_SHORT).show()
            return
        }

        val v = LayoutInflater.from(this).inflate(R.layout.sheet_settings, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(v)

        val grupFps = v.findViewById<LinearLayout>(R.id.grupFps)
        val grupRes = v.findViewById<LinearLayout>(R.id.grupRes)
        val grupQ = v.findViewById<LinearLayout>(R.id.grupQ)
        val grupAudio = v.findViewById<LinearLayout>(R.id.grupAudio)
        val estBit = v.findViewById<TextView>(R.id.estBit)
        val estMenit = v.findViewById<TextView>(R.id.estMenit)
        val estCodec = v.findViewById<TextView>(R.id.estCodec)

        fun segarkan() {
            val mati = Caps.alasanMati(this, profil)
            pasangGrup(grupFps, profil.fps.toString(), mati, "fps") { nilai ->
                profil = profil.copy(fps = nilai.toInt()); true
            }
            pasangGrup(grupRes, profil.tinggi.toString(), mati, "res") { nilai ->
                profil = profil.copy(tinggi = nilai.toInt()); true
            }
            pasangGrup(grupQ, profil.kualitas, mati, "q") { nilai ->
                profil = profil.copy(kualitas = nilai); true
            }
            pasangGrup(grupAudio, profil.audio, mati, "audio") { nilai ->
                profil = profil.copy(audio = nilai); true
            }

            val ukuran = Caps.ukuranRekam(this, profil.tinggi)
            val bit = profil.bitrateVideo(ukuran.x, ukuran.y)
            estBit.text = Format.mbps(bit)
            estMenit.text = Format.mbPerMenit(bit, profil.bitrateAudio)
            estCodec.text = "H.264"
        }

        // pasangGrup memakai callback ini untuk menggambar ulang setelah pilihan berubah
        gambarUlangLembar = { segarkan(); Prefs.simpan(this, profil); gambarProfil() }
        segarkan()

        v.findViewById<View>(R.id.tutupLembar).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            gambarUlangLembar = null
            Prefs.simpan(this, profil)
            gambarProfil()
        }
        dialog.show()
    }

    private var gambarUlangLembar: (() -> Unit)? = null

    private fun pasangGrup(
        grup: LinearLayout,
        terpilih: String,
        mati: Map<String, String>,
        awalan: String,
        pilih: (String) -> Boolean
    ) {
        for (i in 0 until grup.childCount) {
            val anak = grup.getChildAt(i) as? TextView ?: continue
            val nilai = anak.tag?.toString() ?: continue
            val alasan = mati["$awalan:$nilai"]

            if (alasan != null) {
                anak.isSelected = false
                anak.setBackgroundResource(R.drawable.bg_opsi_mati)
                anak.alpha = 0.4f
                anak.setTextColor(ContextCompat.getColor(this, R.color.fg))
                anak.setOnClickListener { Toast.makeText(this, alasan, Toast.LENGTH_LONG).show() }
            } else {
                anak.alpha = 1f
                anak.setBackgroundResource(R.drawable.bg_opsi)
                anak.isSelected = nilai == terpilih
                anak.setTextColor(
                    if (anak.isSelected) ContextCompat.getColor(this, R.color.on_accent)
                    else ContextCompat.getColor(this, R.color.fg)
                )
                anak.setOnClickListener {
                    if (pilih(nilai)) gambarUlangLembar?.invoke()
                }
            }
        }
    }
}
