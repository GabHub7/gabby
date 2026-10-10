package com.gabby.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SessionMeta(val id: String, var title: String, val created: Long, var updated: Long)

/** Banyak sesi chat, tersimpan lokal: satu berkas per sesi plus indeks. Aman dipakai dari banyak thread. */
class SessionStore(private val dir: File) {
    companion object { const val MAX = 30; const val DEFAULT_TITLE = "Percakapan baru" }
    private val metas = ArrayList<SessionMeta>()
    private var current: String? = null
    private val index get() = File(dir, "index.json")
    private fun fileOf(id: String) = File(dir, "s_$id.json")

    init {
        try {
            if (index.exists()) {
                val j = JSONObject(index.readText()); val a = j.getJSONArray("sessions")
                for (i in 0 until a.length()) { val o = a.getJSONObject(i); metas.add(SessionMeta(o.getString("id"), o.getString("title"), o.getLong("created"), o.getLong("updated"))) }
                current = j.optString("current").takeIf { c -> metas.any { it.id == c } }
            }
        } catch (e: Exception) { metas.clear(); current = null }
    }

    private fun saveIndex() = atomicWrite(index, JSONObject().put("current", current ?: "").put("sessions", JSONArray().also { a ->
        metas.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("created", it.created).put("updated", it.updated)) } }).toString())

    @Synchronized fun list(): List<SessionMeta> = metas.sortedByDescending { it.updated }
    @Synchronized fun currentId(): String? = current

    @Synchronized fun create(title: String, now: Long): SessionMeta {
        var id = java.lang.Long.toString(now, 36); var n = 1
        while (metas.any { it.id == id }) id = java.lang.Long.toString(now, 36) + (n++)
        val m = SessionMeta(id, title.trim().take(60).ifEmpty { DEFAULT_TITLE }, now, now)
        metas.add(m); current = id
        while (metas.size > MAX) { val old = metas.filter { it.id != current }.minByOrNull { it.updated } ?: break; metas.remove(old); fileOf(old.id).delete() }
        saveIndex(); return m
    }

    @Synchronized fun select(id: String): Boolean { if (metas.none { it.id == id }) return false; current = id; saveIndex(); return true }

    @Synchronized fun load(id: String): Pair<List<ChatItem>, List<Msg>>? =
        try { if (metas.none { it.id == id }) null else ChatStore.fromJson(fileOf(id).readText()) } catch (e: Exception) { null }

    /** Menyimpan sesi. Judul otomatis diambil dari pesan pengguna pertama bila masih judul bawaan. */
    @Synchronized fun save(id: String, items: List<ChatItem>, history: List<Msg>, now: Long) {
        val m = metas.firstOrNull { it.id == id } ?: return
        m.updated = now
        if (m.title == DEFAULT_TITLE) items.firstOrNull { it.kind == "user" }?.let { m.title = it.text.replace(Regex("\\s+"), " ").trim().take(40).ifEmpty { DEFAULT_TITLE } }
        atomicWrite(fileOf(id), ChatStore.toJson(items, history)); saveIndex()
    }

    @Synchronized fun rename(id: String, title: String) { metas.firstOrNull { it.id == id }?.let { it.title = title.trim().take(60).ifEmpty { DEFAULT_TITLE }; saveIndex() } }

    @Synchronized fun delete(id: String): Boolean {
        val r = metas.removeAll { it.id == id }; fileOf(id).delete()
        if (current == id) current = metas.maxByOrNull { it.updated }?.id
        if (r) saveIndex(); return r
    }

    /** Memindahkan chat.json versi lama menjadi sesi pertama (sekali saja). */
    @Synchronized fun importLegacy(legacy: File, now: Long) {
        if (metas.isNotEmpty() || !legacy.exists()) return
        val data = try { ChatStore.fromJson(legacy.readText()) } catch (e: Exception) { null } ?: return
        val m = create(DEFAULT_TITLE, now); save(m.id, data.first, data.second, now); legacy.delete()
    }
}
