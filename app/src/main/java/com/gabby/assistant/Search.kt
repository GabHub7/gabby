package com.gabby.assistant

import java.net.URLDecoder
import java.net.URLEncoder

/** Pencarian web tanpa API key lewat halaman HTML DuckDuckGo. Upaya terbaik: bisa gagal bila situsnya memblokir atau mengubah format. */
object WebSearch {
    class Result(val title: String, val url: String, val snippet: String)

    private fun clean(s: String) = s.replace(Regex("(?s)<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    private fun target(href: String): String? {
        val h = href.replace("&amp;", "&")
        val u = Regex("[?&]uddg=([^&]+)").find(h)?.groupValues?.get(1)?.let { try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { null } }
        val r = u ?: if (h.startsWith("//")) "https:$h" else h
        return r.takeIf { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) && !it.contains("duckduckgo.com/y.js") }
    }

    fun parse(html: String, limit: Int = 6): List<Result> {
        val tags = Regex("<a\\s[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*>", RegexOption.IGNORE_CASE).findAll(html).toList()
        val out = ArrayList<Result>()
        for ((i, m) in tags.withIndex()) {
            if (out.size >= limit) break
            val href = Regex("href=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: continue
            val url = target(href) ?: continue
            val end = html.indexOf("</a>", m.range.last)
            if (end < 0) continue
            val title = clean(html.substring(m.range.last + 1, end))
            val blockEnd = if (i + 1 < tags.size) tags[i + 1].range.first else html.length
            val sn = Regex("(?s)class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|div|td)>").find(html.substring(end, blockEnd))?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
            if (title.isNotEmpty()) out.add(Result(title.take(150), url, sn.take(240)))
        }
        return out
    }

    fun format(rs: List<Result>): String =
        if (rs.isEmpty()) "Tidak ada hasil (atau mesin pencari memblokir permintaan). Coba kata kunci lain atau gunakan fetch ke situs tertentu."
        else rs.mapIndexed { i, r -> "${i + 1}. ${r.title}\n   ${r.url}" + if (r.snippet.isNotEmpty()) "\n   ${r.snippet}" else "" }.joinToString("\n")

    fun run(query: String, fetch: (String) -> String = { FetchTool.getRaw(it) }): String =
        format(parse(fetch("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))))
}
