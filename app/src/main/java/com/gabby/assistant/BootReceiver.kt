package com.gabby.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** Setelah HP menyala: jalankan lagi layanan Gabby bila diaktifkan atau ada tugas terjadwal yang aktif. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val active = Settings(AndroidKv(ctx)).active
        val hasTasks = TaskStore(File(ctx.filesDir, "tasks.json")).enabledCount() > 0
        if (active || hasTasks) ctx.startForegroundService(Intent(ctx, GabbyService::class.java).setAction(GabbyService.ACTION_REFRESH))
    }
}
