package com.gabby.assistant

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors

class HttpReq(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)
class HttpResp(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/**
 * Panel pengaturan web lokal. Hanya 127.0.0.1, dilindungi token acak (cookie HttpOnly SameSite=Strict),
 * pemeriksaan Host (anti DNS rebinding) dan Origin (anti CSRF).
 */
class LocalServer(
    private val port: Int, private val token: String,
    private val page: () -> String, private val state: () -> JSONObject, private val save: (JSONObject) -> String?,
    /** Rute tambahan (mis. "GET /api/memory"). Penangan boleh melempar IllegalArgumentException(pesan) untuk jawaban 400. */
    private val routes: Map<String, (JSONObject) -> JSONObject> = emptyMap()
) {
    private val csp = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
    private fun tokOk(t: String?) = t != null && MessageDigest.isEqual(t.toByteArray(), token.toByteArray())
    private fun cookie(h: String?, name: String) = h?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    private fun base(extra: Map<String, String> = emptyMap()) = mapOf("cache-control" to "no-store", "x-content-type-options" to "nosniff", "referrer-policy" to "no-referrer") + extra
    private fun json(status: Int, o: JSONObject) = HttpResp(status, base(mapOf("content-type" to "application/json; charset=utf-8")), o.toString().toByteArray())
    private fun err(status: Int, m: String) = json(status, JSONObject().put("error", m))

    fun handle(r: HttpReq): HttpResp {
        val host = r.headers["host"] ?: ""
        if (host != "127.0.0.1:$port" && host != "localhost:$port") return err(403, "Host tidak diizinkan.")
        val ck = cookie(r.headers["cookie"], "gabby_t")
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) {
            if (tokOk(r.query["t"])) return HttpResp(302, base(mapOf("location" to "/", "set-cookie" to "gabby_t=$token; Path=/; HttpOnly; SameSite=Strict")), ByteArray(0))
            if (tokOk(ck)) return HttpResp(200, base(mapOf("content-type" to "text/html; charset=utf-8", "content-security-policy" to csp)), page().toByteArray())
            return HttpResp(401, base(mapOf("content-type" to "text/plain; charset=utf-8")), "Buka halaman ini lewat tombol Pengaturan di aplikasi Gabby.".toByteArray())
        }
        if (!r.path.startsWith("/api/")) return err(404, "Tidak ditemukan.")
        if (!tokOk(r.headers["x-gabby-token"] ?: ck)) return err(401, "Belum diizinkan. Buka lewat aplikasi Gabby.")
        if (r.method == "POST") {
            val o = r.headers["origin"]
            if (o != null && o != "http://127.0.0.1:$port" && o != "http://localhost:$port") return err(403, "Origin tidak diizinkan.")
            if (!(r.headers["content-type"] ?: "").startsWith("application/json")) return err(415, "Harus application/json.")
        }
        return when ("${r.method} ${r.path}") {
            "GET /api/state" -> json(200, state())
            "POST /api/save" -> {
                val j = try { JSONObject(r.body) } catch (e: Exception) { return err(400, "JSON tidak valid.") }
                val e = save(j)
                if (e != null) err(400, e) else json(200, state())
            }
            else -> {
                val h = routes["${r.method} ${r.path}"] ?: return err(404, "Tidak ditemukan.")
                val body = if (r.method == "POST") (try { JSONObject(r.body) } catch (e: Exception) { return err(400, "JSON tidak valid.") }) else JSONObject()
                try { json(200, h(body)) } catch (e: IllegalArgumentException) { err(400, e.message ?: "Permintaan tidak valid.") }
            }
        }
    }

    // ---- soket ----
    private var ss: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile private var running = false

    fun start() {
        val s = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        ss = s; running = true
        Thread { while (running) { try { val c = s.accept(); pool.execute { serve(c) } } catch (e: Exception) { if (!running) break } } }.apply { isDaemon = true }.start()
    }
    fun stop() { running = false; try { ss?.close() } catch (e: Exception) {}; pool.shutdownNow() }

    private fun readReq(ins: InputStream): HttpReq? {
        val head = ByteArrayOutputStream(); var last4 = 0
        while (head.size() < 16384) {
            val b = ins.read(); if (b < 0) return null
            head.write(b); last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        if (last4 != 0x0D0A0D0A) return null
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val parts = lines[0].split(" "); if (parts.size < 3) return null
        val headers = HashMap<String, String>()
        lines.drop(1).filter { it.contains(':') }.forEach { headers[it.substringBefore(':').trim().lowercase()] = it.substringAfter(':').trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > 65536) return null
        val body = ByteArray(len); var off = 0
        while (off < len) { val n = ins.read(body, off, len - off); if (n < 0) return null; off += n }
        val path = parts[1].substringBefore('?')
        val q = HashMap<String, String>()
        parts[1].substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { q[URLDecoder.decode(it.substringBefore('='), "UTF-8")] = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
        return HttpReq(parts[0].uppercase(), path, q, headers, String(body, Charsets.UTF_8))
    }

    private fun serve(c: Socket) {
        try {
            c.soTimeout = 5000
            val req = readReq(c.getInputStream())
            val resp = if (req == null) HttpResp(400, base(mapOf("content-type" to "text/plain")), "Bad request".toByteArray()) else handle(req)
            val out = c.getOutputStream()
            val reason = mapOf(200 to "OK", 302 to "Found", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 415 to "Unsupported Media Type")[resp.status] ?: "OK"
            val sb = StringBuilder("HTTP/1.1 ${resp.status} $reason\r\n")
            resp.headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("content-length: ${resp.body.size}\r\nconnection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); out.write(resp.body); out.flush()
        } catch (e: Exception) { /* koneksi putus */ } finally { try { c.close() } catch (e: Exception) {} }
    }
}
