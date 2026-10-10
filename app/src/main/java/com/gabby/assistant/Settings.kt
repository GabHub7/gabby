package com.gabby.assistant

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/** Penyimpanan kunci-nilai. Nilai rahasia (API key, token) dienkripsi oleh implementasi Android. */
interface Kv {
    fun get(k: String): String?
    fun put(k: String, v: String?)
    fun getSecret(k: String): String?
    fun putSecret(k: String, v: String?)
}

class Settings(private val kv: Kv) {
    companion object {
        val PROVIDERS = listOf("gemini", "openai", "anthropic")
        val MODES = listOf("normal", "cepat", "akurat")
        val MODEL_RE = Regex("^[A-Za-z0-9._:/-]{3,80}$")
        // Nama model bisa berubah. Gemini: dari dokumentasi Google; Claude: dari daftar model Anthropic; OpenAI: isi sendiri.
        val DEFAULT_MODEL = mapOf("gemini" to "gemini-3.5-flash", "openai" to "", "anthropic" to "claude-sonnet-5-5")
        const val DEFAULT_PERSONA = "Kamu Gabby, asisten AI pribadi yang ramah, ringkas, dan membantu. Jawab dalam bahasa pengguna."
    }

    var active: Boolean
        get() = kv.get("active") == "1"
        set(v) = kv.put("active", if (v) "1" else "0")
    var provider: String
        get() = kv.get("provider")?.takeIf { it in PROVIDERS } ?: "gemini"
        set(v) = kv.put("provider", v)
    var mode: String
        get() = kv.get("mode")?.takeIf { it in MODES } ?: "normal"
        set(v) = kv.put("mode", v)
    var persona: String
        get() = kv.get("persona") ?: DEFAULT_PERSONA
        set(v) = kv.put("persona", v)
    var screenConsent: Boolean
        get() = kv.get("screen_consent") == "1"
        set(v) = kv.put("screen_consent", if (v) "1" else "0")
    /** Membaca daftar dan isi file di folder yang diizinkan tanpa bertanya tiap kali. Menulis file tetap selalu bertanya. */
    var autoRead: Boolean
        get() = kv.get("auto_read") == "1"
        set(v) = kv.put("auto_read", if (v) "1" else "0")
    var saveHistory: Boolean
        get() = kv.get("save_history") != "0"
        set(v) = kv.put("save_history", if (v) "1" else "0")
    /** Folder yang diizinkan lewat Storage Access Framework (URI) dan namanya. Hanya diatur dari perangkat, bisa dicabut dari web. */
    var filesTree: String?
        get() = kv.get("files_tree")
        set(v) = kv.put("files_tree", v)
    var filesName: String
        get() = kv.get("files_name") ?: ""
        set(v) = kv.put("files_name", v.ifEmpty { null })
    /** Batas langkah agen per pesan (3-30). */
    var maxSteps: Int
        get() = kv.get("max_steps")?.toIntOrNull()?.coerceIn(3, 30) ?: 12
        set(v) = kv.put("max_steps", v.coerceIn(3, 30).toString())
    /** Agen boleh menyimpan ingatan tanpa bertanya (tetap diberitahukan di chat). */
    var memoryAuto: Boolean
        get() = kv.get("memory_auto") == "1"
        set(v) = kv.put("memory_auto", if (v) "1" else "0")
    var port: Int
        get() = kv.get("port")?.toIntOrNull() ?: 0
        set(v) = kv.put("port", v.toString())

    fun model(p: String): String = kv.get("model_$p") ?: DEFAULT_MODEL[p].orEmpty()
    fun key(p: String): String? = kv.getSecret("key_$p")

    /** Token akses panel web lokal. Dibuat acak sekali, disimpan terenkripsi. */
    fun token(): String {
        kv.getSecret("local_token")?.let { return it }
        val b = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val t = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        kv.putSecret("local_token", t)
        return t
    }

    /** Status untuk panel web. API key tidak pernah dikembalikan, hanya 4 karakter terakhir. */
    fun state(): JSONObject {
        val ps = JSONObject()
        PROVIDERS.forEach { p ->
            val k = key(p)
            ps.put(p, JSONObject().put("model", model(p)).put("hasKey", k != null).put("last4", k?.takeLast(4) ?: ""))
        }
        return JSONObject().put("active", active).put("provider", provider).put("mode", mode).put("persona", persona)
            .put("screenConsent", screenConsent).put("autoRead", autoRead).put("maxSteps", maxSteps).put("memoryAuto", memoryAuto).put("saveHistory", saveHistory).put("filesFolder", if (filesTree != null) filesName.ifEmpty { "(folder dipilih)" } else "").put("providers", ps).put("defaultModels", JSONObject(DEFAULT_MODEL))
    }

    /** Validasi semua dulu, baru tulis. Mengembalikan pesan error, atau null jika sukses. */
    fun apply(j: JSONObject): String? {
        j.optString("provider", "").takeIf { it.isNotEmpty() }?.let { if (it !in PROVIDERS) return "Penyedia AI tidak dikenal." }
        j.optString("mode", "").takeIf { it.isNotEmpty() }?.let { if (it !in MODES) return "Mode tidak dikenal." }
        if (j.has("maxSteps")) { val n = (j.opt("maxSteps") as? Number)?.toDouble(); if (n == null || n % 1.0 != 0.0 || n < 3 || n > 30) return "Batas langkah harus bilangan bulat 3-30." }
        if (j.has("memoryAuto") && j.opt("memoryAuto") !is Boolean) return "Nilai memoryAuto tidak valid."
        if (j.has("autoRead") && j.opt("autoRead") !is Boolean) return "Nilai autoRead tidak valid."
        if (j.has("saveHistory") && j.opt("saveHistory") !is Boolean) return "Nilai saveHistory tidak valid."
        if (j.has("persona") && j.optString("persona").length > 2000) return "Persona maksimal 2000 karakter."
        if (j.has("active") && j.opt("active") !is Boolean) return "Nilai aktif tidak valid."
        val ps = j.optJSONObject("providers")
        if (ps != null) for (p in ps.keys()) {
            if (p !in PROVIDERS) return "Penyedia AI tidak dikenal."
            val o = ps.optJSONObject(p) ?: return "Data penyedia tidak valid."
            if (o.has("model")) { val m = o.optString("model").trim(); if (m.isNotEmpty() && !MODEL_RE.matches(m)) return "Nama model tidak valid: $m" }
            val k = o.optString("key", "").trim()
            if (k.isNotEmpty() && (k.length < 20 || k.length > 300 || k.any { it.isWhitespace() })) return "API key $p tidak valid."
        }
        j.optString("provider", "").takeIf { it.isNotEmpty() }?.let { provider = it }
        j.optString("mode", "").takeIf { it.isNotEmpty() }?.let { mode = it }
        if (j.has("persona")) persona = j.optString("persona").trim()
        if (j.has("active")) active = j.getBoolean("active")
        if (j.has("autoRead")) autoRead = j.getBoolean("autoRead")
        if (j.has("maxSteps")) maxSteps = j.getInt("maxSteps")
        if (j.has("memoryAuto")) memoryAuto = j.getBoolean("memoryAuto")
        if (j.has("saveHistory")) saveHistory = j.getBoolean("saveHistory")
        if (j.optBoolean("revokeFiles", false)) { filesTree = null; filesName = "" }   // folder hanya bisa dicabut dari web, diberikan dari perangkat
        if (j.has("screenConsent") && j.opt("screenConsent") == false) screenConsent = false // hanya boleh dicabut dari web
        if (ps != null) for (p in ps.keys()) {
            val o = ps.getJSONObject(p)
            if (o.has("model")) kv.put("model_$p", o.optString("model").trim().ifEmpty { null })
            val k = o.optString("key", "").trim()
            if (k.isNotEmpty()) kv.putSecret("key_$p", k)
            if (o.optBoolean("clearKey", false)) kv.putSecret("key_$p", null)
        }
        return null
    }
}
