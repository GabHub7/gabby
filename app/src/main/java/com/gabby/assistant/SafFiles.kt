package com.gabby.assistant

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap

/** Akses file lewat Storage Access Framework, hanya di dalam folder yang dipilih pengguna. Jalur selalu relatif ke folder itu. */
class SafFiles(ctx: Context, private val tree: Uri) {
    private val cr = ctx.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val dirMime = DocumentsContract.Document.MIME_TYPE_DIR

    private class Doc(val id: String, val name: String, val mime: String, val size: Long)

    private fun children(parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Doc>()
        cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", if (c.isNull(3)) -1L else c.getLong(3)))
        }
        return out
    }

    private fun resolve(segs: List<String>): Doc? {
        var cur = Doc(rootId, "", dirMime, -1)
        for (s in segs) cur = children(cur.id).firstOrNull { it.name == s } ?: return null
        return cur
    }

    private fun docUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    fun run(op: String, path: String, content: String?): String {
        val segs = PathGuard.segments(path) ?: return "Jalur tidak valid."
        return when (op) {
            "list" -> list(segs)
            "read" -> read(segs)
            "write" -> write(segs, content ?: "")
            else -> "Operasi tidak dikenal."
        }
    }

    private fun list(segs: List<String>): String {
        val d = resolve(segs) ?: return "Folder tidak ditemukan: /${segs.joinToString("/")}"
        if (d.mime != dirMime) return "Ini file, bukan folder."
        val all = children(d.id).sortedWith(compareBy({ it.mime != dirMime }, { it.name.lowercase() }))
        if (all.isEmpty()) return "(folder kosong)"
        val shown = all.take(200).joinToString("\n") { if (it.mime == dirMime) "${it.name}/" else "${it.name}  (${if (it.size >= 0) "${it.size} byte" else "?"})" }
        return shown + if (all.size > 200) "\n(+${all.size - 200} lainnya)" else ""
    }

    private fun read(segs: List<String>): String {
        val d = resolve(segs) ?: return "File tidak ditemukan: /${segs.joinToString("/")}"
        if (d.mime == dirMime) return "Ini folder, bukan file. Gunakan op list."
        val max = 100_000
        val bytes = cr.openInputStream(docUri(d.id))?.use { it.readNBytes(max + 1) } ?: return "File tidak bisa dibuka."
        if (bytes.take(4096).any { it.toInt() == 0 }) return "File ini bukan teks (biner), tidak bisa ditampilkan."
        val txt = String(bytes, 0, minOf(bytes.size, max), Charsets.UTF_8)
        return if (bytes.size > max) txt + "\n[dipotong di 100 KB]" else txt
    }

    private fun write(segs: List<String>, content: String): String {
        if (segs.isEmpty()) return "Jalur file kosong."
        val parent = resolve(segs.dropLast(1)) ?: return "Folder tujuan tidak ditemukan. Buat foldernya dulu."
        if (parent.mime != dirMime) return "Tujuan bukan folder."
        val name = segs.last()
        val existing = children(parent.id).firstOrNull { it.name == name }
        if (existing != null && existing.mime == dirMime) return "Sudah ada folder dengan nama itu."
        val uri = existing?.let { docUri(it.id) } ?: run {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "")) ?: "text/plain"
            DocumentsContract.createDocument(cr, docUri(parent.id), mime, name) ?: return "Gagal membuat file."
        }
        cr.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: return "File tidak bisa ditulis."
        return (if (existing != null) "File ditimpa: " else "File dibuat: ") + "/" + segs.joinToString("/") + " (${content.toByteArray().size} byte)"
    }
}
