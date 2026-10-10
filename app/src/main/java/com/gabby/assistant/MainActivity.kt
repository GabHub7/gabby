package com.gabby.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {
    private lateinit var kv: AndroidKv
    private lateinit var st: Settings
    private lateinit var status: TextView
    private lateinit var sw: Switch
    private val h = Handler(Looper.getMainLooper())
    private val INK = 0xFF1B1640.toInt()
    private val REQ_TREE = 41

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun btn(label: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; minHeight = 0; minimumHeight = 0; setPadding(dp(14), dp(12), dp(14), dp(12))
        if (primary) { setTextColor(Color.WHITE); background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF7B3FE4.toInt(), 0xFF2F6BFF.toInt())).apply { cornerRadius = dp(14).toFloat() } }
        else { setTextColor(INK); background = GradientDrawable().apply { setColor(0xFFEEF1FF.toInt()); cornerRadius = dp(14).toFloat() } }
        setOnClickListener { click() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kv = AndroidKv(this); st = Settings(kv)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); setBackgroundColor(0xFFF6F4FD.toInt()) }
        val scroll = ScrollView(this).apply { addView(root) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsetsCompat.CONSUMED
        }
        val mascot = MascotView(this)
        root.addView(mascot, LinearLayout.LayoutParams(dp(120), dp(120)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        mascot.setOnClickListener { mascot.state = MascotView.S.HOVER; h.postDelayed({ mascot.state = MascotView.S.IDLE }, 1400) }
        root.addView(TextView(this).apply { text = "Gabby"; textSize = 30f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply { text = "Asisten AI pribadi yang selalu siap membantu"; setTextColor(0xFF6B6890.toInt()); gravity = Gravity.CENTER })
        status = TextView(this).apply { setTextColor(INK); textSize = 13f; setPadding(dp(14), dp(12), dp(14), dp(12)); background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() } }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        sw = Switch(this).apply { text = "Gabby Aktif (widget melayang)"; setTextColor(INK); textSize = 15f; setPadding(dp(4), dp(14), dp(4), dp(8)) }
        root.addView(sw, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        fun add(b: Button) = root.addView(b, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        add(btn("Buka Pengaturan (API key, model, mode)", true) { openSettings() })
        add(btn("Izinkan tampil di atas aplikasi lain", false) { startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
        add(btn("Aktifkan Aksesibilitas (untuk Scan Layar)", false) { a11yGuide() })
        add(btn("Izinkan folder untuk Gabby (/files)", false) { folderDialog() })
        add(btn("Cabut izin folder", false) { revokeFolder() })
        add(btn("Izinkan Termux (opsional, terminal penuh)", false) { termuxGuide() })
        add(btn("Izinkan berjalan di latar (untuk tugas terjadwal)", false) { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        add(btn("Cara pakai", false) { howTo() })
        root.addView(TextView(this).apply {
            text = "Privasi: pesan (dan gambar layar saat kamu menekan Scan) dikirim ke penyedia AI pilihanmu memakai API key milikmu. " +
                "API key tersimpan terenkripsi di perangkat ini. Perintah terminal dan akses web selalu meminta izinmu dulu."
            textSize = 12f; setTextColor(0xFF6B6890.toInt()); setPadding(0, dp(16), 0, 0)
        })
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        sw.setOnCheckedChangeListener(null); sw.isChecked = st.active
        sw.setOnCheckedChangeListener { _, on -> toggle(on) }
    }

    private fun a11yEnabled(): Boolean =
        (AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").split(':').any { it.startsWith("$packageName/") }

    private fun refresh() {
        val p = st.provider
        val key = if (st.key(p) != null) "tersimpan" else "BELUM diisi"
        status.text = "Penyedia AI: $p, model: ${st.model(p).ifEmpty { "(belum diisi)" }}\nAPI key: $key\nMode: ${st.mode}\n" +
            "Izin tampil di atas aplikasi lain: ${if (AndroidSettings.canDrawOverlays(this)) "aktif" else "belum"}\n" +
            "Scan layar (Aksesibilitas): ${if (a11yEnabled()) "aktif" else "belum"}\n" +
            "Folder untuk /files: ${if (st.filesTree != null) st.filesName.ifEmpty { "(dipilih)" } else "belum dipilih"}\n" +
            "Termux: ${if (!TermuxTool.installed(this)) "belum terpasang" else if (TermuxTool.permitted(this)) "siap" else "terpasang, izin belum diberikan"}\n" +
            "Ingatan: ${MemoryStore(java.io.File(filesDir, "memory.json")).all().size} catatan | Sesi: ${SessionStore(java.io.File(filesDir, "sessions")).list().size} | Tugas aktif: ${TaskStore(java.io.File(filesDir, "tasks.json")).enabledCount()}"
    }

    private fun toggle(on: Boolean) {
        if (!on) { st.active = false; startForegroundService(Intent(this, GabbyService::class.java).setAction(GabbyService.ACTION_REFRESH)); toast("Gabby dinonaktifkan."); return }
        if (kv.get("intro_ok") != "1") {
            AlertDialog.Builder(this).setTitle("Aktifkan Gabby").setCancelable(false)
                .setMessage("Gabby akan melayang di atas aplikasi lain.\n\nPesan dan (saat kamu menekan Scan) gambar layar dikirim ke penyedia AI yang kamu pilih. " +
                    "Perintah terminal dan akses web hanya berjalan setelah kamu menekan Izinkan di setiap aksi.")
                .setPositiveButton("Lanjut") { _, _ -> kv.put("intro_ok", "1"); toggle(true) }
                .setNegativeButton("Batal") { _, _ -> sw.setOnCheckedChangeListener(null); sw.isChecked = false; sw.setOnCheckedChangeListener { _, o -> toggle(o) } }.show()
            return
        }
        if (!AndroidSettings.canDrawOverlays(this)) {
            toast("Izinkan 'Tampil di atas aplikasi lain' untuk Gabby, lalu nyalakan lagi.")
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            sw.setOnCheckedChangeListener(null); sw.isChecked = false; sw.setOnCheckedChangeListener { _, o -> toggle(o) }
            return
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        st.active = true
        startForegroundService(Intent(this, GabbyService::class.java).setAction(GabbyService.ACTION_REFRESH))
        if (st.key(st.provider) == null) toast("Gabby aktif. Isi API key di Pengaturan agar bisa menjawab.") else toast("Gabby aktif. Ketuk maskot di layar.")
        moveTaskToBack(true)
    }

    private fun openSettings() {
        GabbyService.serverUntil = System.currentTimeMillis() + 10 * 60_000
        startForegroundService(Intent(this, GabbyService::class.java).setAction(GabbyService.ACTION_SERVER))
        h.postDelayed({
            if (st.port > 0) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${st.port}/?t=${st.token()}")))
            else toast("Server pengaturan belum siap. Coba lagi sebentar.")
        }, 900)
    }

    private fun a11yGuide() {
        AlertDialog.Builder(this).setTitle("Aktifkan Aksesibilitas")
            .setMessage("Dipakai hanya untuk mengambil tangkapan layar saat kamu menekan Scan Layar (butuh Android 11 ke atas).\n\n" +
                "1. Ketuk 'Buka Aksesibilitas', cari Gabby, lalu nyalakan.\n\n" +
                "2. Kalau Android menolak dengan pesan 'pengaturan terbatas': buka 'Info aplikasi', ketuk menu ⋮ di pojok kanan atas, pilih 'Izinkan pengaturan terbatas', lalu ulangi.")
            .setPositiveButton("Buka Aksesibilitas") { _, _ -> startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNeutralButton("Info aplikasi") { _, _ -> startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            .setNegativeButton("Nanti", null).show()
    }

    // Jendela izin folder (seperti "Allow Gabby to access this folder?" di desain)
    private fun folderDialog() {
        AlertDialog.Builder(this).setTitle("Izinkan Gabby mengakses folder?")
            .setMessage("Pilih satu folder. Gabby hanya bisa melihat dan membaca file di dalam folder itu (tidak ke folder lain). " +
                "Menulis file selalu meminta izinmu setiap kali. Kamu bisa mencabutnya kapan saja.")
            .setPositiveButton("Pilih folder") { _, _ ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                startActivityForResult(i, REQ_TREE)
            }
            .setNegativeButton("Batal", null).show()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try { contentResolver.takePersistableUriPermission(uri, rw) } catch (e: Exception) { toast("Gagal menyimpan izin folder."); return }
        st.filesTree?.takeIf { it != uri.toString() }?.let { try { contentResolver.releasePersistableUriPermission(Uri.parse(it), rw) } catch (e: Exception) {} }
        st.filesTree = uri.toString(); st.filesName = folderName(uri)
        toast("Folder diizinkan: ${st.filesName.ifEmpty { "(dipilih)" }}"); refresh()
    }

    private fun folderName(tree: Uri): String = try {
        contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" } ?: ""
    } catch (e: Exception) { "" }

    private fun revokeFolder() {
        val t = st.filesTree ?: run { toast("Belum ada folder yang diizinkan."); return }
        try { contentResolver.releasePersistableUriPermission(Uri.parse(t), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) {}
        st.filesTree = null; st.filesName = ""; toast("Izin folder dicabut."); refresh()
    }

    private fun termuxGuide() {
        AlertDialog.Builder(this).setTitle("Terminal penuh lewat Termux")
            .setMessage("Termux memberi Gabby shell Linux sungguhan (python, git, dan lainnya). Setiap perintah tetap meminta izinmu.\n\n" +
                "1. Pasang Termux (dari F-Droid atau GitHub).\n" +
                "2. Di Termux jalankan:\necho \"allow-external-apps=true\" >> ~/.termux/termux.properties\ntermux-reload-settings\n\n" +
                "3. Ketuk 'Beri izin' di bawah.\n\nFitur ini belum diuji pada perangkat sungguhan.")
            .setPositiveButton("Beri izin") { _, _ ->
                if (!TermuxTool.installed(this)) toast("Termux belum terpasang.")
                else if (TermuxTool.permitted(this)) toast("Izin Termux sudah diberikan.")
                else requestPermissions(arrayOf(TermuxTool.PERMISSION), 42)
            }
            .setNegativeButton("Tutup", null).show()
    }

    private fun howTo() {
        AlertDialog.Builder(this).setTitle("Cara pakai")
            .setMessage("1. Buka Pengaturan, pilih penyedia AI (Gemini, OpenAI, atau Claude), isi nama model dan API key, lalu Simpan.\n\n" +
                "2. Nyalakan 'Gabby Aktif'. Maskot melayang di layar; ketuk untuk membuka chat.\n\n" +
                "3. Perintah cepat: /tanya (bertanya), /scan (pindai layar lalu tanya 'ini apaan?'), /reset (mulai percakapan baru).\n\n" +
                "4. Kalau Gabby ingin menjalankan perintah terminal atau membuka web, muncul jendela izin. Kamu yang memutuskan tiap kali.\n\n" +
                "6. Agen: Gabby bisa mencari di web, membuka aplikasi, menyalin ke clipboard, mengingat hal penting (/ingatan), dan menyimpan banyak sesi (/sesi). Untuk pekerjaan beberapa langkah, ia mengusulkan rencana: setujui sekali, lalu alat di dalam rencana jalan otomatis ±15 menit. Perintah berbahaya selalu bertanya. Tombol Stop menghentikannya kapan saja.\n\n" +
                "7. Tugas terjadwal (/tugas): Gabby bisa menjalankan tugas sendiri tanpa kamu, misalnya \"tiap pagi jam 8 ringkas berita\". Alat yang boleh dipakai ditetapkan saat tugas dibuat; di luar itu ditolak. Tugas hanya berjalan selama Gabby aktif.\n\n" +
                "5. /files: izinkan satu folder, lalu minta 'tampilkan isi folder' atau 'baca catatan.txt'. Membaca bisa otomatis (Pengaturan); menulis selalu meminta izin.\n\n" +
                "Catatan: terminal berjalan di dalam sandbox aplikasi Android (perintah dasar), bukan akses penuh ke seluruh HP. Untuk shell Linux penuh, pakai Termux.")
            .setPositiveButton("Mengerti", null).show()
    }
}
