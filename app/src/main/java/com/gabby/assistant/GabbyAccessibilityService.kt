package com.gabby.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.ByteArrayOutputStream

object Jpeg {
    /** Perkecil sisi terpanjang ke 1280 px lalu kompres JPEG, supaya cepat dikirim dan hemat token. */
    fun encode(b: Bitmap): ByteArray {
        val s = minOf(1f, 1280f / maxOf(b.width, b.height).toFloat())
        val bm = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        val o = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 80, o)
        return o.toByteArray()
    }
}

/** Dipakai HANYA untuk mengambil tangkapan layar saat pengguna menekan Scan Layar. Tidak membaca isi layar. */
class GabbyAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: GabbyAccessibilityService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** done(jpeg, pesanError). Dipanggil di thread utama. */
    fun capture(done: (ByteArray?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { done(null, "Scan layar butuh Android 11 ke atas."); return }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                try {
                    val hw = r.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(hw, r.colorSpace)
                    hw.close()
                    if (bmp == null) { done(null, "Gagal membaca tangkapan layar."); return }
                    val soft = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    bmp.recycle()
                    done(Jpeg.encode(soft), null)
                } catch (e: Exception) { done(null, "Gagal memproses tangkapan layar.") }
            }
            override fun onFailure(errorCode: Int) {
                done(null, when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Terlalu cepat. Coba lagi sebentar."
                    else -> "Gagal mengambil tangkapan layar (kode $errorCode). Layar tertentu, misalnya aplikasi perbankan, memang dilarang di-screenshot."
                })
            }
        })
    }
}
