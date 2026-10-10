package com.gabby.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import android.view.View

/**
 * Maskot Gabby beranimasi. Memakai gambar `gabby_<state>.png` di res/drawable-nodpi (idle, hover, open, thinking, scanning,
 * working, success, error, notification, sleep). Gerak tiap state dihitung oleh [Motion]. Kalau gambar tidak ada,
 * dipakai gambar bawaan sederhana yang digambar lewat kode.
 */
class MascotView(ctx: Context) : View(ctx) {
    enum class S(val sprite: String) {
        IDLE("idle"), HOVER("hover"), OPEN("open"), THINKING("thinking"), SCANNING("scanning"),
        WORKING("working"), SUCCESS("success"), ERROR("error"), NOTIFY("notification"), SLEEP("sleep")
    }

    companion object {
        private val cache = HashMap<String, Bitmap?>()
        @Synchronized fun sprite(ctx: Context, name: String): Bitmap? = cache.getOrPut(name) {
            val id = ctx.resources.getIdentifier("gabby_$name", "drawable", ctx.packageName)
            if (id == 0) null else BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false })
        }
    }

    private var t0 = SystemClock.uptimeMillis()
    private var prevState: S? = null
    private var prevStart = 0L
    private var running = false
    private val reduce = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val d = resources.displayMetrics.density

    var state: S = S.IDLE
        set(v) {
            if (v == field) return
            prevState = field; prevStart = t0
            field = v; t0 = SystemClock.uptimeMillis(); invalidate()
        }
    /** Kemiringan tambahan (derajat), mis. saat maskot digeser. */
    var tilt = 0f
    /** Menekan maskot: sedikit mengecil. */
    var squished = false

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; invalidate() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = if (reduce) 0f else (now - t0) / 1000f
        val size = minOf(width, height).toFloat()
        val bmp = sprite(context, state.sprite)
        if (bmp == null) drawFallback(c) else {
            val fade = if (reduce) 1f else minOf(1f, t / 0.18f)   // crossfade antar state
            val ps = prevState
            if (ps != null && fade < 1f) sprite(context, ps.sprite)?.let { drawSprite(c, it, Motion.pose(ps.sprite, (now - prevStart) / 1000f), size, 1f - fade) }
            drawSprite(c, bmp, Motion.pose(state.sprite, t), size, fade)
            overlays(c, size, t)
        }
        dot(c, size)
        if (running && !reduce) postInvalidateDelayed(33)   // ~30 fps, hemat baterai
    }

    private fun drawSprite(c: Canvas, b: Bitmap, p: Pose, size: Float, alpha: Float) {
        c.save()
        c.translate(width / 2f + p.dx * size, height / 2f + p.dy * size)
        c.rotate(p.rot + tilt)
        val press = if (squished) 0.94f else 1f
        c.scale(p.sx * press, p.sy * press)
        val half = size / 2f * 0.98f
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.drawBitmap(b, null, RectF(-half, -half, half, half), paint)
        paint.alpha = 255
        c.restore()
    }

    private fun overlays(c: Canvas, size: Float, t: Float) {
        val cx = width / 2f; val cy = height / 2f
        if (state == S.SCANNING) {   // garis pindai menyapu avatar
            val r = size * 0.385f; val y = cy + (Motion.scanLine(t) * 2f - 1f) * r
            c.save(); c.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
            paint.shader = LinearGradient(0f, y - 7 * d, 0f, y + 7 * d, intArrayOf(0x0019B5D6, 0xCC19B5D6.toInt(), 0x0019B5D6), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, y - 7 * d, width.toFloat(), y + 7 * d, paint); paint.shader = null
            c.restore()
        }
        if (state == S.SUCCESS) Motion.sparkles(t).forEach { star(c, cx + it.x * size, cy + it.y * size, it.size * size, it.alpha) }
    }

    private fun star(c: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
        val path = Path()
        for (i in 0 until 8) {
            val a = Math.PI * 2 * i / 8 - Math.PI / 2
            val rr = if (i % 2 == 0) r else r * 0.28f
            val x = cx + (Math.cos(a) * rr).toFloat(); val y = cy + (Math.sin(a) * rr).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); paint.style = Paint.Style.FILL; paint.color = 0xFFFFC107.toInt(); paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.drawPath(path, paint); paint.alpha = 255
    }

    private fun dot(c: Canvas, size: Float) {
        val x = width / 2f - size * 0.29f; val y = height / 2f + size * 0.29f   // kiri bawah (kanan bawah dipakai ikon bel/chat)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; c.drawCircle(x, y, 6.5f * d, paint)
        paint.color = if (state == S.ERROR) 0xFFD9480F.toInt() else if (state == S.SLEEP) 0xFF9AA0B4.toInt() else 0xFF22C55E.toInt()
        c.drawCircle(x, y, 4.5f * d, paint)
    }

    // ---- gambar bawaan sederhana bila sprite tidak ada ----
    private fun drawFallback(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h / 2
        val r = minOf(w, h) / 2 - 2 * d
        paint.style = Paint.Style.FILL; paint.shader = LinearGradient(0f, 0f, w, h, 0xFFF3F0FF.toInt(), 0xFFDCE6FF.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, paint); paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * d; paint.shader = LinearGradient(0f, 0f, w, h, 0xFF7B3FE4.toInt(), 0xFF2F6BFF.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, paint); paint.shader = null
        val fr = r * 0.60f; val fy = cy + r * 0.10f
        paint.style = Paint.Style.FILL; paint.color = 0xFFFFDFC8.toInt(); c.drawCircle(cx, fy, fr, paint)
        paint.color = 0xFF1F1B2E.toInt(); c.drawArc(RectF(cx - fr * 1.06f, fy - fr * 1.12f, cx + fr * 1.06f, fy + fr * 0.95f), 180f, 180f, true, paint)
        paint.color = 0xFFFFDFC8.toInt(); c.drawRect(cx - fr * 0.9f, fy - fr * 0.18f, cx + fr * 0.9f, fy + fr * 0.55f, paint)
        val gy = fy + fr * 0.12f; val gx = fr * 0.38f; val gr = fr * 0.27f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f * d; paint.color = 0xFF2A2540.toInt()
        c.drawCircle(cx - gx, gy, gr, paint); c.drawCircle(cx + gx, gy, gr, paint); c.drawLine(cx - gx + gr, gy, cx + gx - gr, gy, paint)
        paint.style = Paint.Style.FILL; c.drawCircle(cx - gx, gy, gr * .32f, paint); c.drawCircle(cx + gx, gy, gr * .32f, paint)
    }
}
