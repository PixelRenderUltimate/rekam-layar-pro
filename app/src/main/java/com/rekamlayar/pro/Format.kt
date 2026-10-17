package com.rekamlayar.pro

import java.util.Locale

object Format {
    fun durasi(ms: Long): String {
        val total = ms / 1000
        val j = total / 3600
        val m = (total % 3600) / 60
        val d = total % 60
        return if (j > 0) String.format(Locale.US, "%d:%02d:%02d", j, m, d)
        else String.format(Locale.US, "%02d:%02d", m, d)
    }

    fun ukuran(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb < 1000) String.format(Locale.US, "%.0f MB", mb)
        else String.format(Locale.US, "%.2f GB", mb / 1000.0)
    }

    fun mbps(bit: Int): String = String.format(Locale.US, "%.1f Mbps", bit / 1_000_000.0)

    fun mbPerMenit(bitVideo: Int, bitAudio: Int): String {
        val mb = (bitVideo + bitAudio).toDouble() * 60.0 / 8.0 / 1_000_000.0
        return String.format(Locale.US, "%.0f MB", mb)
    }
}
