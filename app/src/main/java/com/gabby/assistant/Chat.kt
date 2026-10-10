package com.gabby.assistant

import org.json.JSONArray
import org.json.JSONObject

class ChatItem(val kind: String, val text: String)

/** Riwayat chat permanen (disimpan di penyimpanan privat aplikasi). Gambar layar tidak pernah disimpan. */
object ChatStore {
    const val MAX_ITEMS = 60
    private val KINDS = setOf("user", "bot", "tool", "note")

    fun toJson(items: List<ChatItem>, history: List<Msg>): String {
        val a = JSONArray(); items.takeLast(MAX_ITEMS).forEach { a.put(JSONObject().put("k", it.kind).put("t", it.text.take(6000))) }
        val h = JSONArray(); history.forEach { h.put(JSONObject().put("r", it.role).put("t", it.text.take(8000))) }
        return JSONObject().put("v", 1).put("items", a).put("history", h).toString()
    }

    fun fromJson(s: String): Pair<List<ChatItem>, List<Msg>>? = try {
        val j = JSONObject(s)
        val items = j.getJSONArray("items").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("k") in KINDS }.map { ChatItem(it.getString("k"), it.getString("t")) } }
        val hist = j.getJSONArray("history").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("r") == "user" || it.optString("r") == "assistant" }.map { Msg(it.getString("r"), it.getString("t")) } }
        items.takeLast(MAX_ITEMS) to hist
    } catch (e: Exception) { null }
}
