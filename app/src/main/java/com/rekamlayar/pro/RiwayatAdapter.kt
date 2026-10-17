package com.rekamlayar.pro

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RiwayatAdapter(
    private var data: MutableList<Rekaman>,
    private val onBuka: (Rekaman) -> Unit,
    private val onHapus: (Rekaman) -> Unit
) : RecyclerView.Adapter<RiwayatAdapter.Baris>() {

    class Baris(v: View) : RecyclerView.ViewHolder(v) {
        val ikon: ImageView = v.findViewById(R.id.ikon)
        val judul: TextView = v.findViewById(R.id.judul)
        val meta: TextView = v.findViewById(R.id.meta)
        val ukuran: TextView = v.findViewById(R.id.ukuran)
        val hapus: ImageView = v.findViewById(R.id.hapus)
    }

    fun perbarui(baru: MutableList<Rekaman>) {
        data = baru
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Baris =
        Baris(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: Baris, position: Int) {
        val r = data[position]
        holder.judul.text = r.nama
        holder.ukuran.text = Format.ukuran(r.ukuranBytes)

        val labelRes = if (r.tinggi == 1440) "2K" else "${r.tinggi}p"
        val labelQ = when (r.kualitas) {
            "rendah" -> "Rendah"; "tinggi" -> "Tinggi"; else -> "Sedang"
        }
        val jeda = if (r.jeda > 0) " · ${r.jeda}× jeda" else ""
        holder.meta.text = "${Format.durasi(r.durasiMs)} · $labelRes · ${r.fps} fps · $labelQ$jeda"

        holder.ikon.setImageResource(
            when (r.audio) {
                "mic", "dua" -> R.drawable.ic_audio_mic
                "bisu" -> R.drawable.ic_audio_mute
                else -> R.drawable.ic_audio_device
            }
        )

        holder.itemView.setOnClickListener { onBuka(r) }
        holder.hapus.setOnClickListener { onHapus(r) }
    }
}
