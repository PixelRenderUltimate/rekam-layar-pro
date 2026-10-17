package com.rekamlayar.pro

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs

/**
 * Pil mengambang di atas aplikasi lain: titik status, timer, jeda, dan stop.
 * Bisa digeser, dan posisinya diingat untuk rekaman berikutnya.
 */
class OverlayControl(
    private val ctx: Context,
    private val onJeda: () -> Unit,
    private val onStop: () -> Unit
) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var tampilan: View? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var jam: TextView
    private lateinit var titik: View
    private lateinit var tombolJeda: ImageView

    fun bisaTampil(): Boolean = Settings.canDrawOverlays(ctx)

    fun tampilkan() {
        if (tampilan != null || !bisaTampil()) return

        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_control, null)
        jam = v.findViewById(R.id.gJam)
        titik = v.findViewById(R.id.gDot)
        tombolJeda = v.findViewById(R.id.gJeda)

        tombolJeda.setOnClickListener { onJeda() }
        v.findViewById<ImageView>(R.id.gStop).setOnClickListener { onStop() }

        val tipe = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            tipe,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val simpan = Prefs.posisiOverlay(ctx)
            if (simpan != null) {
                x = simpan.first
                y = simpan.second
            } else {
                val layar = Caps.ukuranLayar(ctx)
                x = layar.x - 420
                y = layar.y - 420
            }
        }

        pasangGeser(v, lp)

        wm.addView(v, lp)
        tampilan = v
        params = lp
    }

    private fun pasangGeser(v: View, lp: WindowManager.LayoutParams) {
        var awalX = 0
        var awalY = 0
        var sentuhX = 0f
        var sentuhY = 0f
        var bergerak = false

        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    awalX = lp.x
                    awalY = lp.y
                    sentuhX = e.rawX
                    sentuhY = e.rawY
                    bergerak = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sentuhX
                    val dy = e.rawY - sentuhY
                    if (abs(dx) > 12 || abs(dy) > 12) bergerak = true
                    if (bergerak) {
                        val layar = Caps.ukuranLayar(ctx)
                        lp.x = (awalX + dx.toInt()).coerceIn(0, maxOf(0, layar.x - v.width))
                        lp.y = (awalY + dy.toInt()).coerceIn(0, maxOf(0, layar.y - v.height))
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (bergerak) Prefs.simpanPosisiOverlay(ctx, lp.x, lp.y)
                    bergerak
                }
                else -> false
            }
        }
    }

    fun perbarui(durasiMs: Long, dijeda: Boolean) {
        val v = tampilan ?: return
        v.post {
            jam.text = Format.durasi(durasiMs)
            titik.setBackgroundResource(
                if (dijeda) R.drawable.bg_bulat_kuning else R.drawable.bg_bulat_merah
            )
            tombolJeda.setImageResource(if (dijeda) R.drawable.ic_play else R.drawable.ic_pause)
            tombolJeda.contentDescription =
                ctx.getString(if (dijeda) R.string.lanjutkan else R.string.jeda)
        }
    }

    fun sembunyikan() {
        val v = tampilan ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        tampilan = null
        params = null
    }
}
