package com.gabby.assistant

import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit

object ShellTool {
    private const val MAX = 8000
    /** Menjalankan perintah lewat sh dengan batas waktu. Hanya dipanggil setelah pengguna menyetujui. */
    fun run(cmd: String, dir: File? = null, timeoutSec: Long = 20): String {
        val pb = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true)
        if (dir != null) pb.directory(dir)
        val p = pb.start()
        val sb = StringBuilder()
        val reader = Thread {
            p.inputStream.bufferedReader().use { r ->
                val buf = CharArray(2048)
                while (true) { val n = r.read(buf); if (n < 0) break; synchronized(sb) { if (sb.length < MAX) sb.append(buf, 0, n) } }
            }
        }
        reader.start()
        val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) { p.destroyForcibly(); reader.join(500); return clip(sb) + "\n[waktu habis setelah ${timeoutSec}s, proses dihentikan]" }
        reader.join(1000)
        return clip(sb) + "\n[exit ${p.exitValue()}]"
    }
    private fun clip(sb: StringBuilder): String = synchronized(sb) { if (sb.length > MAX) sb.substring(0, MAX) + "\n[output dipotong]" else sb.toString().trimEnd() }
}

object FetchTool {
    private const val MAX_BYTES = 1_000_000
    private const val MAX_TEXT = 12000

    fun isPrivate(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (a is Inet4Address) return (b[0].toInt() and 255) == 100 && (b[1].toInt() and 0xC0) == 64 || (b[0].toInt() and 255) >= 240 || (b[0].toInt() and 255) == 0
        if (a is Inet6Address) return (b[0].toInt() and 0xFE) == 0xFC
        return true
    }

    fun blockedHost(host: String): Boolean = try { InetAddress.getAllByName(host).any { isPrivate(it) } } catch (e: Exception) { true }

    fun htmlToText(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style|noscript|svg|template)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>|</(p|div|li|tr|h[1-6]|section|article)>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        s = s.replace(Regex("[ \\t\\r]+"), " ").replace(Regex(" ?\\n ?"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        return if (s.length > MAX_TEXT) s.substring(0, MAX_TEXT) + "\n[dipotong]" else s
    }

    /** Mengambil isi sebagai (tipe, isi). Hanya http/https; alamat internal ditolak, termasuk setelah pengalihan. allowPrivate khusus tes lokal. */
    fun fetch(url: String, allowPrivate: Boolean = false): Pair<String, String> {
        var u = URL(url)
        for (hop in 0..4) {
            require(u.protocol == "http" || u.protocol == "https") { "Hanya http atau https." }
            require(u.userInfo == null) { "Link dengan username/password tidak didukung." }
            if (!allowPrivate && blockedHost(u.host)) throw IllegalArgumentException("Alamat tujuan tidak diizinkan (internal atau tidak ditemukan).")
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 10000; readTimeout = 15000
                setRequestProperty("user-agent", "Mozilla/5.0 (compatible; Gabby/1.0)")
                setRequestProperty("accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9")
                setRequestProperty("accept-encoding", "identity")
            }
            val code = c.responseCode
            if (code in 300..399 && c.getHeaderField("Location") != null) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); continue }
            val type = (c.contentType ?: "").lowercase()
            if (code >= 400) { c.disconnect(); throw IllegalStateException("Situs menjawab dengan kode $code.") }
            if (!(type.startsWith("text/") || type.contains("json") || type.contains("xml"))) { c.disconnect(); throw IllegalStateException("Tipe isi tidak didukung (${type.substringBefore(';').ifEmpty { "tidak diketahui" }}).") }
            val bytes = c.inputStream.use { it.readNBytes(MAX_BYTES) }
            c.disconnect()
            return type to String(bytes, Charsets.UTF_8)
        }
        throw IllegalStateException("Terlalu banyak pengalihan.")
    }

    fun get(url: String, allowPrivate: Boolean = false): String {
        val (type, body) = fetch(url, allowPrivate)
        return if (type.contains("html")) htmlToText(body) else body.take(MAX_TEXT)
    }

    /** Isi mentah (HTML) untuk diurai alat lain, mis. pencarian. */
    fun getRaw(url: String, allowPrivate: Boolean = false): String = fetch(url, allowPrivate).second
}

/** Penyalur aksi ke penangan per alat. Penangan yang bergantung pada Android disediakan oleh layanan. */
class RealTools(private val handlers: Map<String, (Action) -> String>) : Tools {
    override fun run(a: Action): String { val h = handlers[a.tool] ?: return "Alat tidak tersedia: ${a.tool}"; return h(a) }
}
