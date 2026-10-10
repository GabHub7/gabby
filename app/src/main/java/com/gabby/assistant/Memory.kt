package com.gabby.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class Note(val id: String, val text: String, val source: String, val ts: Long)

/** Tulis atomik: tulis ke berkas sementara lalu pindahkan, agar tidak rusak bila proses terhenti. */
internal fun atomicWrite(f: File, text: String) {
    f.parentFile?.mkdirs()
    val tmp = File(f.path + ".tmp"); tmp.writeText(text)
    try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    catch (e: Exception) { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING) }
}

/** Ingatan jangka panjang, disimpan lokal di perangkat. Aman dipakai dari banyak thread. */
class MemoryStore(private val file: File) {
    companion object { const val MAX_NOTES = 200; const val MAX_TEXT = 500 }
    private val notes = ArrayList<Note>()

    init {
        try {
            if (file.exists()) { val a = JSONObject(file.readText()).getJSONArray("notes")
                for (i in 0 until a.length()) { val o = a.getJSONObject(i); notes.add(Note(o.getString("id"), o.getString("text"), o.optString("source", "user"), o.optLong("ts"))) } }
        } catch (e: Exception) { notes.clear() }
    }

    private fun save() = atomicWrite(file, JSONObject().put("notes", JSONArray().also { a -> notes.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("source", it.source).put("ts", it.ts)) } }).toString())
    private fun words(s: String) = Regex("[\\p{L}\\p{N}]{3,}").findAll(s.lowercase()).map { it.value }.toSet()

    @Synchronized fun all(): List<Note> = notes.toList()

    /** Menambah catatan. Mengembalikan null bila kosong atau sudah ada yang persis sama. */
    @Synchronized fun add(text: String, source: String, now: Long): Note? {
        val t = text.trim().take(MAX_TEXT)
        if (t.length < 3 || notes.any { it.text.equals(t, ignoreCase = true) }) return null
        var id = "m" + java.lang.Long.toString(now, 36); var n = 1
        while (notes.any { it.id == id }) id = "m" + java.lang.Long.toString(now, 36) + (n++)
        val note = Note(id, t, if (source == "agent") "agent" else "user", now)
        notes.add(note)
        while (notes.size > MAX_NOTES) notes.removeAt(0)
        save(); return note
    }

    @Synchronized fun forget(id: String): Boolean { val r = notes.removeAll { it.id == id }; if (r) save(); return r }
    @Synchronized fun clear() { notes.clear(); save() }

    /** Pencarian kata kunci sederhana: skor = jumlah kata yang sama, seri dimenangkan yang lebih baru. */
    @Synchronized fun search(q: String, limit: Int = 8): List<Note> {
        val qw = words(q)
        if (qw.isEmpty()) return notes.takeLast(limit).reversed()
        return notes.map { it to words(it.text).count { w -> w in qw } }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Note, Int>> { it.second }.thenByDescending { it.first.ts }).take(limit).map { it.first }
    }

    /** Ingatan yang relevan untuk disisipkan ke prompt (relevan dulu, lalu yang terbaru). */
    @Synchronized fun context(query: String, maxChars: Int = 1200): String {
        val picked = LinkedHashMap<String, Note>()
        search(query, 6).forEach { picked[it.id] = it }
        notes.takeLast(3).reversed().forEach { picked.putIfAbsent(it.id, it) }
        val sb = StringBuilder()
        for (n in picked.values) { val line = "- [${n.id}] ${n.text}\n"; if (sb.length + line.length > maxChars) break; sb.append(line) }
        return sb.toString().trimEnd()
    }
}
