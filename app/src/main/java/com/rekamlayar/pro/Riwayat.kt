package com.rekamlayar.pro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Rekaman(
    val id: String,
    val uri: String,
    val nama: String,
    val durasiMs: Long,
    val ukuranBytes: Long,
    val tinggi: Int,
    val fps: Int,
    val kualitas: String,
    val audio: String,
    val jeda: Int
)

object Riwayat {
    private const val NAMA = "rekam_layar_riwayat"
    private const val KUNCI = "daftar"
    private const val MAKS = 60

    fun daftar(c: Context): MutableList<Rekaman> {
        val teks = c.getSharedPreferences(NAMA, Context.MODE_PRIVATE).getString(KUNCI, null)
            ?: return mutableListOf()
        val hasil = mutableListOf<Rekaman>()
        try {
            val arr = JSONArray(teks)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                hasil.add(
                    Rekaman(
                        id = o.getString("id"),
                        uri = o.getString("uri"),
                        nama = o.getString("nama"),
                        durasiMs = o.getLong("durasi"),
                        ukuranBytes = o.getLong("ukuran"),
                        tinggi = o.getInt("tinggi"),
                        fps = o.getInt("fps"),
                        kualitas = o.getString("kualitas"),
                        audio = o.getString("audio"),
                        jeda = o.optInt("jeda", 0)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return hasil
    }

    private fun tulis(c: Context, daftar: List<Rekaman>) {
        val arr = JSONArray()
        daftar.take(MAKS).forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id).put("uri", r.uri).put("nama", r.nama)
                    .put("durasi", r.durasiMs).put("ukuran", r.ukuranBytes)
                    .put("tinggi", r.tinggi).put("fps", r.fps)
                    .put("kualitas", r.kualitas).put("audio", r.audio).put("jeda", r.jeda)
            )
        }
        c.getSharedPreferences(NAMA, Context.MODE_PRIVATE).edit()
            .putString(KUNCI, arr.toString()).apply()
    }

    fun tambah(c: Context, r: Rekaman) {
        val d = daftar(c)
        d.add(0, r)
        tulis(c, d)
    }

    fun hapus(c: Context, id: String) {
        tulis(c, daftar(c).filterNot { it.id == id })
    }

    fun kosongkan(c: Context) {
        tulis(c, emptyList())
    }
}
