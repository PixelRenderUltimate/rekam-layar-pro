package com.rekamlayar.pro

import android.content.Context

/** Profil rekaman yang dipilih pengguna. */
data class Profil(
    val fps: Int = 60,
    val tinggi: Int = 1080,          // sisi pendek target: 720 / 1080 / 1440
    val kualitas: String = "sedang", // rendah / sedang / tinggi
    val audio: String = "perangkat", // perangkat / mic / dua / bisu
    // "samar" = tampil tapi ditandai secure supaya tidak ikut terekam
    // "biasa" = tampil apa adanya, ikut terekam
    // "mati"  = tidak ditampilkan sama sekali
    val mengambangMode: String = "samar",
    val bitManual: Boolean = false,  // true = pakai angka bitrate sendiri
    val bitMbps: Int = 17            // bitrate pilihan sendiri, dalam Mbps
) {
    /** Bit per piksel per frame. Dasar perhitungan bitrate. */
    private val bpp: Double
        get() = when (kualitas) {
            "rendah" -> 0.060
            "tinggi" -> 0.165
            else -> 0.110
        }

    /** Bitrate yang dihitung dari resolusi, fps, dan tingkat kualitas. */
    fun bitrateOtomatis(lebarPx: Int, tinggiPx: Int): Int =
        (lebarPx.toDouble() * tinggiPx * fps * bpp).toInt().coerceAtLeast(1_000_000)

    fun bitrateVideo(lebarPx: Int, tinggiPx: Int): Int =
        if (bitManual) bitMbps.coerceIn(2, 60) * 1_000_000
        else bitrateOtomatis(lebarPx, tinggiPx)

    val bitrateAudio: Int
        get() = when (audio) {
            "bisu" -> 0
            "mic" -> 128_000
            "dua" -> 256_000
            else -> 192_000
        }

    val labelRes: String get() = if (tinggi == 1440) "2K" else "${tinggi}p"

    val labelKualitas: String
        get() = when (kualitas) {
            "rendah" -> "Rendah"
            "tinggi" -> "Tinggi"
            else -> "Sedang"
        }

    val labelAudio: String
        get() = when (audio) {
            "mic" -> "Mikrofon"
            "dua" -> "Perangkat + mikrofon"
            "bisu" -> "Tanpa audio"
            else -> "Suara perangkat"
        }

    val mengambang: Boolean get() = mengambangMode != "mati"
    val mengambangSamar: Boolean get() = mengambangMode == "samar"

    val butuhMikrofon: Boolean get() = audio == "mic" || audio == "dua"
    val butuhInternal: Boolean get() = audio == "perangkat" || audio == "dua"
}

object Prefs {
    private const val NAMA = "rekam_layar_pro"

    fun muat(c: Context): Profil {
        val p = c.getSharedPreferences(NAMA, Context.MODE_PRIVATE)
        return Profil(
            fps = p.getInt("fps", 60),
            tinggi = p.getInt("tinggi", 1080),
            kualitas = p.getString("kualitas", "sedang") ?: "sedang",
            audio = p.getString("audio", "perangkat") ?: "perangkat",
            mengambangMode = p.getString("mengambangMode", null)
                ?: if (p.getBoolean("mengambang", true)) "samar" else "mati",
            bitManual = p.getBoolean("bitManual", false),
            bitMbps = p.getInt("bitMbps", 17)
        )
    }

    fun simpan(c: Context, profil: Profil) {
        c.getSharedPreferences(NAMA, Context.MODE_PRIVATE).edit()
            .putInt("fps", profil.fps)
            .putInt("tinggi", profil.tinggi)
            .putString("kualitas", profil.kualitas)
            .putString("audio", profil.audio)
            .putString("mengambangMode", profil.mengambangMode)
            .putBoolean("bitManual", profil.bitManual)
            .putInt("bitMbps", profil.bitMbps)
            .apply()
    }

    fun simpanPosisiOverlay(c: Context, x: Int, y: Int) {
        c.getSharedPreferences(NAMA, Context.MODE_PRIVATE).edit()
            .putInt("ovx", x).putInt("ovy", y).apply()
    }

    fun posisiOverlay(c: Context): Pair<Int, Int>? {
        val p = c.getSharedPreferences(NAMA, Context.MODE_PRIVATE)
        if (!p.contains("ovx")) return null
        return Pair(p.getInt("ovx", 0), p.getInt("ovy", 0))
    }
}
