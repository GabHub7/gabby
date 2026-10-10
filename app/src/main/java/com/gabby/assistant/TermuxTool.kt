package com.gabby.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Terminal Linux sungguhan lewat Termux (opsional). Pengguna harus memasang Termux, mengaktifkan
 * `allow-external-apps=true` di ~/.termux/termux.properties, dan memberi izin RUN_COMMAND ke Gabby.
 * Selalu lewat persetujuan per perintah.
 */
object TermuxTool {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private val waiting = ConcurrentHashMap<String, ArrayBlockingQueue<String>>()

    fun installed(ctx: Context) = try { ctx.packageManager.getPackageInfo("com.termux", 0); true } catch (e: Exception) { false }
    fun permitted(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    fun deliver(id: String, text: String) { waiting[id]?.offer(text) }

    fun run(ctx: Context, cmd: String, timeoutSec: Long = 60): String {
        if (!installed(ctx)) return "Termux belum terpasang. Pasang Termux (dari F-Droid atau GitHub) lalu coba lagi."
        if (!permitted(ctx)) return "Izin menjalankan perintah Termux belum diberikan. Buka aplikasi Gabby dan ketuk 'Izinkan Termux'."
        val id = UUID.randomUUID().toString()
        val q = ArrayBlockingQueue<String>(1); waiting[id] = q
        try {
            val back = Intent(ctx, TermuxResultReceiver::class.java).putExtra("id", id)
            val pi = PendingIntent.getBroadcast(ctx, id.hashCode(), back, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
            val i = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", cmd))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            ctx.startForegroundService(i)
            return q.poll(timeoutSec, TimeUnit.SECONDS)
                ?: "Waktu habis menunggu Termux. Pastikan 'allow-external-apps=true' ada di ~/.termux/termux.properties lalu jalankan 'termux-reload-settings'."
        } catch (e: SecurityException) {
            return "Android menolak akses ke Termux. Pastikan allow-external-apps aktif dan izin Termux sudah diberikan."
        } finally { waiting.remove(id) }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val b = intent.getBundleExtra("result")
        val out = if (b == null) "Termux tidak mengembalikan hasil." else {
            val so = b.getString("stdout").orEmpty().trimEnd(); val se = b.getString("stderr").orEmpty().trimEnd()
            val err = b.getString("errmsg").orEmpty()
            val sb = StringBuilder(so)
            if (se.isNotEmpty()) sb.append(if (sb.isNotEmpty()) "\n" else "").append("[stderr]\n").append(se)
            if (err.isNotEmpty()) sb.append("\n[error] ").append(err)
            sb.append("\n[exit ").append(b.getInt("exitCode", -1)).append("]")
            if (sb.length > 8000) sb.substring(0, 8000) + "\n[output dipotong]" else sb.toString()
        }
        TermuxTool.deliver(id, out)
    }
}
