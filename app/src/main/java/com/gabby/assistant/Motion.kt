package com.gabby.assistant

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Posisi/skala/putaran maskot. dx dan dy dalam fraksi ukuran tampilan, rot dalam derajat. */
class Pose(val dx: Float, val dy: Float, val rot: Float, val sx: Float, val sy: Float)

/** Bintang kecil (x,y relatif ke tengah dalam fraksi ukuran; size dalam fraksi; alpha 0..1). */
class Sparkle(val x: Float, val y: Float, val size: Float, val alpha: Float)

/** Gerak maskot Gabby per state. Fungsi murni agar mudah diuji. t = detik sejak masuk state. */
object Motion {
    private const val TAU = (2 * PI).toFloat()
    private fun s(t: Float, period: Float, phase: Float = 0f) = sin(TAU * t / period + phase)
    private fun env(t: Float, k: Float) = exp(-k * t)

    val STATES = listOf("idle", "hover", "open", "thinking", "scanning", "working", "success", "error", "notification", "sleep")

    fun pose(state: String, t0: Float): Pose {
        val t = if (t0 < 0f) 0f else t0
        var dx = 0f; var dy = 0f; var rot = 0f; var sx = 1f; var sy = 1f
        when (state) {
            "idle" -> { val b = s(t, 2.8f); dy = -0.022f * b; sx = 1f - 0.006f * b; sy = 1f + 0.009f * b }            // melayang dan bernapas
            "hover" -> { sx = 1.05f; sy = 1.05f; rot = 3.5f * s(t, 0.9f); dy = -0.012f * s(t, 1.3f) }                // menyambut sentuhan
            "open" -> { rot = 2f * s(t, 2.2f); dy = -0.012f * s(t, 2.2f, 1.2f); sx = 1.02f; sy = 1.02f }              // mengangguk pelan
            "thinking" -> { rot = 4.5f * s(t, 1.8f); dy = -0.014f * s(t, 0.9f); dx = 0.008f * s(t, 1.8f) }            // goyang miring
            "scanning" -> { val p = 1f + 0.022f * s(t, 0.9f); sx = p; sy = p; dy = -0.008f * s(t, 0.9f, 1f) }          // berdenyut
            "working" -> { dy = -0.018f * abs(s(t, 0.7f)); rot = 1.6f * s(t, 1.4f) }                                  // memantul seperti mengetik
            "success" -> {                                                                                              // melompat lalu mereda
                val e = env(t, 3.2f)
                dy = -0.07f * e * abs(sin(TAU * 1.6f * t)) - 0.01f * s(t, 2.4f) * (1f - e)
                val p = 1f + 0.16f * e * sin(TAU * 2.2f * t); sx = p; sy = p
            }
            "error" -> { val e = env(t, 2.6f); rot = 7f * e * sin(TAU * 6f * t); dx = 0.035f * e * sin(TAU * 7f * t) } // bergetar lalu mereda
            "notification" -> {                                                                                         // bel bergoyang bergelombang
                val c = t % 1.5f; val e = if (c < 0.7f) env(c, 2.2f) else 0f
                rot = 9f * e * sin(TAU * 3.4f * c); dy = -0.012f * s(t, 1.5f)
            }
            "sleep" -> { val b = s(t, 4.2f); sy = 1f + 0.02f * b; sx = 1f - 0.008f * b; dy = 0.006f * b; rot = 1.2f * s(t, 6f) }
        }
        val pop = 1f + 0.10f * env(t, 7f) * sin(TAU * 2.4f * t)   // "pop" kecil setiap kali masuk state
        return Pose(dx, dy, rot, sx * pop, sy * pop)
    }

    /** Posisi garis pindai (0 = atas, 1 = bawah) untuk state scanning. */
    fun scanLine(t: Float): Float = ((if (t < 0f) 0f else t) % 1.3f) / 1.3f

    /** Percikan bintang untuk state success. Kosong setelah sekitar 1,1 detik. */
    fun sparkles(t: Float): List<Sparkle> {
        if (t < 0f || t > 1.1f) return emptyList()
        val r = 0.30f + 0.28f * (1f - exp(-4f * t))
        val a = (1f - t / 1.1f).coerceIn(0f, 1f)
        return (0 until 6).map { i ->
            val ang = TAU * i / 6f + 0.4f
            Sparkle(kotlin.math.cos(ang) * r, kotlin.math.sin(ang) * r, 0.045f * (1f - t / 1.5f), a)
        }
    }
}
