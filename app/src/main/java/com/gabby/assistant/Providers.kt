package com.gabby.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Adaptor untuk Gemini, OpenAI, dan Claude. Semua memakai API key milik pengguna, langsung dari perangkat. */
object Providers {
    class Req(val url: String, val headers: Map<String, String>, val body: JSONObject)

    private val GEMINI_MODEL_RE = Regex("^[A-Za-z0-9._-]{3,64}$")
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    /** Mode cepat menurunkan "berpikir" Gemini (3.x: thinkingLevel, 2.x: thinkingBudget). Mode akurat memakai bawaan model. */
    fun thinkingFor(model: String, mode: String): JSONObject? {
        if (mode != "cepat") return null
        return when {
            Regex("gemini-3", RegexOption.IGNORE_CASE).containsMatchIn(model) -> JSONObject().put("thinkingLevel", "low")
            Regex("gemini-2\\.", RegexOption.IGNORE_CASE).containsMatchIn(model) -> JSONObject().put("thinkingBudget", 0)
            else -> null
        }
    }

    fun build(provider: String, model: String, key: String, system: String, msgs: List<Msg>, mode: String, thinking: Boolean = true): Req =
        when (provider) {
            "gemini" -> {
                require(GEMINI_MODEL_RE.matches(model)) { "Nama model Gemini tidak valid." }
                val contents = JSONArray()
                msgs.forEach { m ->
                    val parts = JSONArray()
                    m.image?.let { parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64(it)))) }
                    parts.put(JSONObject().put("text", m.text))
                    contents.put(JSONObject().put("role", if (m.role == "assistant") "model" else "user").put("parts", parts))
                }
                val gen = JSONObject().put("temperature", if (mode == "akurat") 0.2 else 0.7)
                if (thinking) thinkingFor(model, mode)?.let { gen.put("thinkingConfig", it) }
                Req("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to key),
                    JSONObject().put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                        .put("contents", contents).put("generationConfig", gen))
            }
            "openai" -> {
                val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                msgs.forEach { m ->
                    if (m.image != null) {
                        val c = JSONArray().put(JSONObject().put("type", "text").put("text", m.text))
                            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + b64(m.image))))
                        arr.put(JSONObject().put("role", m.role).put("content", c))
                    } else arr.put(JSONObject().put("role", m.role).put("content", m.text))
                }
                Req("https://api.openai.com/v1/chat/completions", mapOf("authorization" to "Bearer $key"), JSONObject().put("model", model).put("messages", arr))
            }
            "anthropic" -> {
                val arr = JSONArray()
                msgs.forEach { m ->
                    val c = JSONArray()
                    m.image?.let { c.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64(it)))) }
                    c.put(JSONObject().put("type", "text").put("text", m.text))
                    arr.put(JSONObject().put("role", m.role).put("content", c))
                }
                Req("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
                    JSONObject().put("model", model).put("max_tokens", 4096).put("system", system).put("messages", arr))
            }
            else -> throw IllegalArgumentException("Penyedia AI tidak dikenal.")
        }

    fun parse(provider: String, j: JSONObject): String {
        val text = when (provider) {
            "gemini" -> {
                val c = j.optJSONArray("candidates")
                if (c == null || c.length() == 0) {
                    val br = j.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                    error(if (br.isNotEmpty()) "Permintaan diblokir oleh Gemini ($br)." else "Gemini tidak memberi jawaban.")
                }
                val parts = c.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                (0 until (parts?.length() ?: 0)).joinToString("") { parts!!.getJSONObject(it).optString("text", "") }
            }
            "openai" -> j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")
            "anthropic" -> {
                val c = j.getJSONArray("content")
                (0 until c.length()).map { c.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
            }
            else -> throw IllegalArgumentException("Penyedia AI tidak dikenal.")
        }
        return text.trim().ifEmpty { error("Jawaban kosong dari model.") }
    }

    fun describe(code: Int, serverMsg: String): String = when (code) {
        400 -> "Permintaan ditolak (400). Cek nama model dan pengaturan." + hint(serverMsg)
        401, 403 -> "API key ditolak ($code). Periksa key di pengaturan." 
        404 -> "Model tidak ditemukan (404). Periksa nama model di pengaturan."
        429 -> "Kuota atau batas kecepatan tercapai (429). Coba lagi nanti."
        500, 502, 503, 529 -> "Server penyedia AI sedang sibuk ($code). Coba lagi sebentar."
        else -> "Error dari penyedia AI ($code)."
    }
    private fun hint(m: String) = if (m.isBlank()) "" else " Detail: " + m.take(160)

    class HttpError(val code: Int, message: String) : Exception(message)

    private fun open(r: Req): HttpURLConnection {
        val c = (URL(r.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 120000; doOutput = true
            setRequestProperty("content-type", "application/json")
            r.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        c.outputStream.use { it.write(r.body.toString().toByteArray()) }
        return c
    }

    private fun errorOf(code: Int, c: HttpURLConnection): HttpError {
        val text = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        val em = json.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { json.optString("error", "") }
        return HttpError(code, describe(code, em))
    }

    private fun post(r: Req): JSONObject {
        val c = open(r)
        val code = c.responseCode
        if (code !in 200..299) throw errorOf(code, c)
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return try { JSONObject(text) } catch (e: Exception) { JSONObject() }
    }

    /** Memanggil model. Mengulang untuk error sementara; Gemini diulang tanpa pengaturan berpikir bila ditolak (400). */
    fun call(provider: String, model: String, key: String, system: String, msgs: List<Msg>, mode: String): Result<String> = runCatching {
        var thinking = true
        var attempt = 0
        while (true) {
            try {
                return@runCatching parse(provider, post(build(provider, model, key, system, msgs, mode, thinking)))
            } catch (e: HttpError) {
                if (e.code == 400 && provider == "gemini" && thinking && thinkingFor(model, mode) != null) { thinking = false; continue }
                if (e.code in listOf(429, 500, 502, 503, 529) && attempt < 2) { Thread.sleep(1500L * (attempt + 1)); attempt++; continue }
                throw e
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    fun toStream(provider: String, r: Req): Req = when (provider) {
        "gemini" -> Req(r.url.replace(":generateContent", ":streamGenerateContent") + "?alt=sse", r.headers, r.body)
        else -> Req(r.url, r.headers, JSONObject(r.body.toString()).put("stream", true))
    }

    /**
     * Seperti [call], tetapi jawaban mengalir: onDelta dipanggil untuk tiap potongan teks. Mengembalikan teks lengkap.
     * urlOverride hanya untuk tes lokal.
     */
    fun stream(provider: String, model: String, key: String, system: String, msgs: List<Msg>, mode: String,
               onDelta: (String) -> Unit, urlOverride: String? = null): Result<String> = runCatching {
        var thinking = true
        var attempt = 0
        while (true) {
            try {
                var r = toStream(provider, build(provider, model, key, system, msgs, mode, thinking))
                if (urlOverride != null) r = Req(urlOverride, r.headers, r.body)
                val c = open(r)
                val code = c.responseCode
                if (code !in 200..299) throw errorOf(code, c)
                val sb = StringBuilder()
                c.inputStream.use { ins -> Sse.read(ins) { data -> val d = Sse.delta(provider, data); if (d.isNotEmpty()) { sb.append(d); onDelta(d) } } }
                c.disconnect()
                val text = sb.toString().trim()
                if (text.isEmpty()) error("Jawaban kosong dari model.")
                return@runCatching text
            } catch (e: HttpError) {   // hanya terjadi sebelum ada teks yang mengalir
                if (e.code == 400 && provider == "gemini" && thinking && thinkingFor(model, mode) != null) { thinking = false; continue }
                if (e.code in listOf(429, 500, 502, 503, 529) && attempt < 2) { Thread.sleep(1500L * (attempt + 1)); attempt++; continue }
                throw e
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
    }
}

/** Pembaca Server-Sent Events untuk ketiga penyedia. */
object Sse {
    fun read(ins: java.io.InputStream, onData: (String) -> Unit) {
        val br = ins.bufferedReader(Charsets.UTF_8)
        while (true) { val line = br.readLine() ?: break; if (line.startsWith("data:")) onData(line.substring(5).trim()) }
    }

    /** Potongan teks dari satu event. Kosong bila bukan teks. Melempar IllegalStateException untuk event error. */
    fun delta(provider: String, data: String): String {
        if (data.isEmpty() || data == "[DONE]") return ""
        val j = try { JSONObject(data) } catch (e: Exception) { return "" }
        when (provider) {
            "gemini" -> {
                j.optJSONObject("error")?.let { error("Gemini: " + it.optString("message", "error")) }
                val c = j.optJSONArray("candidates")
                if (c == null || c.length() == 0) {
                    val br = j.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                    if (br.isNotEmpty()) error("Permintaan diblokir oleh Gemini ($br).")
                    return ""
                }
                val parts = c.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return ""
                return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text", "") }
            }
            "openai" -> {
                j.optJSONObject("error")?.let { error("OpenAI: " + it.optString("message", "error")) }
                val c = j.optJSONArray("choices") ?: return ""
                if (c.length() == 0) return ""
                return c.getJSONObject(0).optJSONObject("delta")?.optString("content", "").orEmpty()
            }
            "anthropic" -> {
                if (j.optString("type") == "error") error("Claude: " + (j.optJSONObject("error")?.optString("message") ?: "error"))
                if (j.optString("type") != "content_block_delta") return ""
                val d = j.optJSONObject("delta") ?: return ""
                return if (d.optString("type") == "text_delta") d.optString("text", "") else ""
            }
        }
        return ""
    }
}
