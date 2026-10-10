package com.gabby.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Widget melayang Gabby, panel chat, dan server pengaturan lokal.
 * Perintah terminal dan akses web SELALU lewat modal persetujuan.
 */
class GabbyService : Service() {
    companion object {
        const val ACTION_SERVER = "com.gabby.assistant.SERVER"
        const val ACTION_REFRESH = "com.gabby.assistant.REFRESH"
        @Volatile var serverUntil = 0L
        private const val CH = "gabby"
        private const val INK = 0xFF1B1640.toInt()
        private const val MUTE = 0xFF6B6890.toInt()
    }

    private enum class K { USER, BOT, TOOL, NOTE }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()          // percakapan
    private val taskExec = Executors.newSingleThreadExecutor()     // tugas terjadwal (terpisah dari percakapan)
    private lateinit var st: Settings
    private lateinit var kv: AndroidKv
    private lateinit var agent: Agent
    private var wm: WindowManager? = null
    private var server: LocalServer? = null

    private var bubble: FrameLayout? = null
    private var mascot: MascotView? = null
    private lateinit var blp: WindowManager.LayoutParams
    private var panel: LinearLayout? = null
    private var headMascot: MascotView? = null
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sub: TextView
    private var approval: View? = null
    private var overlayOn = false
    private var panelOpen = false
    private var busy = false
    private var greeted = false
    private lateinit var memory: MemoryStore
    private lateinit var sessions: SessionStore
    private lateinit var tasks: TaskStore
    private lateinit var tools: Tools
    private val grants = Grants()
    private var curSession = ""
    private var sendBtn: Button? = null
    @Volatile private var approvalQueue: ArrayBlockingQueue<Approval>? = null
    @Volatile private var taskRunning = false
    private val items = ArrayList<ChatItem>()   // riwayat chat yang tampil (disimpan ke perangkat bila diaktifkan)
    private var live: TextView? = null           // gelembung sementara saat jawaban mengalir

    private val sleepRun = Runnable { if (!busy) setState(MascotView.S.SLEEP) }
    private val tick = object : Runnable {
        override fun run() {
            if (!st.active && tasks.enabledCount() == 0 && System.currentTimeMillis() > serverUntil) { stopSelf(); return }
            refreshOverlay(); checkTasks(); ui.postDelayed(this, 30_000)
        }
    }

    private val env = object : AgentEnv {
        override fun persona() = st.persona
        override fun autoRead() = st.autoRead
        override fun memoryAuto() = st.memoryAuto
        override fun maxSteps() = st.maxSteps
        override fun memoryContext(query: String) = memory.context(query)
        override fun nowMs() = System.currentTimeMillis()
        override fun nowText(): String = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy HH:mm", Locale("id", "ID"))) + " (" + ZoneId.systemDefault() + ")"
    }

    private fun llm() = Llm { sys, msgs, onDelta ->
        val p = st.provider; val key = st.key(p)
        if (key == null) Result.failure(IllegalStateException("API key belum diisi."))
        else Providers.stream(p, st.model(p), key, sys, msgs, st.mode, onDelta)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun grad(r: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF7B3FE4.toInt(), 0xFF2F6BFF.toInt())).apply { cornerRadius = dp(r).toFloat() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        kv = AndroidKv(this); st = Settings(kv)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        memory = MemoryStore(File(filesDir, "memory.json")); sessions = SessionStore(File(filesDir, "sessions")); tasks = TaskStore(File(filesDir, "tasks.json"))
        val now = System.currentTimeMillis()
        sessions.importLegacy(File(filesDir, "chat.json"), now)
        curSession = sessions.currentId() ?: sessions.create("", now).id
        tools = RealTools(mapOf(
            "shell" to { a: Action -> ShellTool.run(a.s("command"), filesDir) },
            "termux" to { a: Action -> TermuxTool.run(this, a.s("command")) },
            "fetch" to { a: Action -> FetchTool.get(a.s("url")) },
            "search" to { a: Action -> WebSearch.run(a.s("query")) },
            "now" to { _: Action -> env.nowText() },
            "files" to { a: Action -> filesTool(a.s("op"), a.s("path"), a.content) },
            "open" to { a: Action -> openTool(a) },
            "clipboard" to { a: Action -> clipTool(a.s("text")) },
            "memory" to { a: Action -> memoryTool(a) },
            "task" to { a: Action -> taskTool(a) }))
        agent = Agent(llm(), tools, env, agentUi, grants)
        loadSession(curSession)
    }

    // ---------- alat ----------
    private fun filesTool(op: String, path: String, content: String?): String {
        val tree = st.filesTree ?: return "Folder belum diizinkan. Minta pengguna memilih folder lewat aplikasi Gabby (tombol 'Izinkan folder untuk Gabby')."
        return try { SafFiles(this, Uri.parse(tree)).run(op, path, content) }
        catch (e: SecurityException) { "Izin folder sudah dicabut. Minta pengguna memilihnya lagi di aplikasi Gabby." }
    }

    private fun openTool(a: Action): String {
        try {
            if (a.s("kind") == "url") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.s("target"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "Membuka tautan di browser."
            }
            val q = a.s("target").lowercase(); val pm = packageManager
            val cands = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val hit = cands.firstOrNull { it.loadLabel(pm).toString().lowercase() == q } ?: cands.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
                ?: cands.firstOrNull { it.activityInfo.packageName == a.s("target") } ?: return "Aplikasi tidak ditemukan: ${a.s("target")}"
            val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "Aplikasi itu tidak bisa dibuka."
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "Membuka ${hit.loadLabel(pm)}."
        } catch (e: Exception) { return "Android menolak membuka itu dari latar belakang (${e.javaClass.simpleName}). Coba lagi saat Gabby tampil di layar." }
    }

    private fun clipTool(text: String): String {
        ui.post { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Gabby", text)) }
        return "Disalin ke clipboard (${text.length} karakter)."
    }

    private fun memoryTool(a: Action): String = when (a.op) {
        "add" -> memory.add(a.s("text"), "agent", System.currentTimeMillis())?.let { n -> ui.post { addMsg("Diingat: ${n.text}", K.NOTE, record = false) }; "Tersimpan sebagai ${n.id}." } ?: "Tidak disimpan (sudah ada atau terlalu pendek)."
        "search" -> memory.search(a.s("query")).let { r -> if (r.isEmpty()) "Tidak ada ingatan yang cocok." else r.joinToString("\n") { "${it.id}: ${it.text}" } }
        "list" -> memory.all().takeLast(30).let { r -> if (r.isEmpty()) "Ingatan kosong." else r.joinToString("\n") { "${it.id}: ${it.text}" } }
        "forget" -> if (memory.forget(a.s("id"))) "Dilupakan." else "Catatan tidak ditemukan."
        else -> "Operasi tidak dikenal."
    }

    private fun fmtTask(t: Task): String = "${t.id} | ${t.title} | ${Schedules.describe(t.schedule)} | ${if (t.enabled) "aktif" else "mati"}" +
        if (t.lastRun > 0) " | terakhir: ${t.lastResult.replace('\n', ' ').take(80)}" else ""

    private fun taskTool(a: Action): String = when (a.op) {
        "list" -> tasks.all().let { r -> if (r.isEmpty()) "Belum ada tugas." else r.joinToString("\n") { fmtTask(it) } }
        "add" -> {
            val now = System.currentTimeMillis()
            val sch = Schedules.parse(a.args, now)
            val allow = a.args.optJSONArray("allow")?.let { l -> (0 until l.length()).map { l.getString(it) } }.orEmpty()
            val t = if (sch == null) null else tasks.add(a.s("title"), a.s("goal"), sch, allow, a.args.optInt("max_steps", 10), now)
            if (t == null) "Gagal membuat tugas (jadwal tidak valid atau sudah ${TaskStore.MAX_TASKS} tugas)." else { ui.post { checkTasks() }; "Tugas dibuat: ${fmtTask(t)}" }
        }
        "remove" -> if (tasks.remove(a.s("id"))) "Tugas dihapus." else "Tugas tidak ditemukan."
        "enable", "disable" -> if (tasks.setEnabled(a.s("id"), a.op == "enable", System.currentTimeMillis())) "Tugas ${if (a.op == "enable") "diaktifkan" else "dimatikan"}." else "Tugas tidak ditemukan."
        "run" -> if (tasks.runSoon(a.s("id"), System.currentTimeMillis())) { ui.post { checkTasks() }; "Tugas dijadwalkan jalan sekarang." } else "Tugas tidak ditemukan."
        else -> "Operasi tidak dikenal."
    }

    // ---------- tugas terjadwal: berjalan tanpa pengguna, hanya dengan izin yang ditetapkan saat tugas dibuat ----------
    private fun checkTasks() {
        if (taskRunning) return
        val due = tasks.due(System.currentTimeMillis()); if (due.isEmpty()) return
        taskRunning = true
        taskExec.execute { try { due.forEach { runTask(it) } } finally { taskRunning = false } }
    }

    private fun runTask(t: Task) {
        val wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gabby:task")
        wl.acquire(6 * 60_000L)
        try {
            val sb = StringBuilder()
            val tui = object : AgentUi {
                override fun status(s: String) {}
                override fun partial(text: String) {}
                override fun say(text: String) { sb.append(text).append('\n') }
                override fun tool(text: String) {}
                override fun approve(a: Action, canSession: Boolean) = Approval.NO   // tidak ada pengguna: tidak pernah dipakai (kebijakan tugas tidak pernah bertanya)
            }
            val tenv = object : AgentEnv by env { override fun maxSteps() = t.maxSteps }
            val r = if (st.key(st.provider) == null) Result.failure(IllegalStateException("API key belum diisi.")) else
                Agent(llm(), tools, tenv, tui, Grants(), Unattended(t.allow.toSet())).ask("Tugas terjadwal \"${t.title}\": ${t.goal}")
            val report = if (r.isSuccess) sb.toString().trim().ifEmpty { "(tugas selesai tanpa laporan)" } else "Gagal: " + (r.exceptionOrNull()?.message ?: "terjadi kesalahan")
            tasks.markRan(t.id, System.currentTimeMillis(), report)
            notifyTask(t, report)
            ui.post { addMsg("Tugas selesai: ${t.title}\n" + report.take(600), K.NOTE, record = false); if (!panelOpen) setState(MascotView.S.NOTIFY) }
        } finally { if (wl.isHeld) wl.release() }
    }

    private fun notifyTask(t: Task, report: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("gabby_tasks", "Tugas Gabby", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(t.id.hashCode(), Notification.Builder(this, "gabby_tasks").setSmallIcon(R.drawable.ic_stat_gabby).setContentTitle("Tugas selesai: " + t.title)
                .setContentText(report.replace('\n', ' ').take(120)).setStyle(Notification.BigTextStyle().bigText(report.take(1500))).setContentIntent(pi).setAutoCancel(true).build())
        } catch (e: Exception) { /* izin notifikasi belum diberikan */ }
    }

    // ---------- sesi ----------
    private fun loadSession(id: String) {
        items.clear()
        sessions.load(id)?.let { (its, hist) -> items.addAll(its); agent.restore(hist) }
    }

    private fun saveChat() {
        if (!st.saveHistory) return
        try { sessions.save(curSession, items, agent.snapshot(), System.currentTimeMillis()) } catch (e: Exception) { /* abaikan */ }
    }

    private fun switchTo(id: String) {
        curSession = id; sessions.select(id)
        agent.reset(); live = null
        if (::list.isInitialized) list.removeAllViews()
        loadSession(id)
        greeted = items.isNotEmpty()
        items.forEach { addMsg(it.text, kindOf(it.kind), record = false) }
    }

    private fun note(t: String) { addMsg(t, K.NOTE, record = false) }

    private fun cmdSessions(arg: String) {
        val all = sessions.list(); val parts = arg.split(" ", limit = 2); val sub = parts[0]; val rest = parts.getOrElse(1) { "" }.trim()
        when {
            arg.isEmpty() -> note((if (all.isEmpty()) "Belum ada sesi." else all.mapIndexed { i, x -> "${i + 1}. ${x.title}${if (x.id == curSession) "  (aktif)" else ""}" }.joinToString("\n")) + "\n\n/sesi baru [judul], /sesi <nomor>, /sesi hapus <nomor>")
            sub == "baru" -> { saveChat(); switchTo(sessions.create(rest, System.currentTimeMillis()).id); note("Sesi baru dimulai.") }
            sub == "hapus" -> {
                val x = all.getOrNull((rest.toIntOrNull() ?: 0) - 1)
                if (x == null) note("Nomor sesi tidak ada.") else {
                    sessions.delete(x.id)
                    if (x.id == curSession) switchTo(sessions.currentId() ?: sessions.create("", System.currentTimeMillis()).id)
                    note("Sesi dihapus: ${x.title}")
                }
            }
            sub.toIntOrNull() != null -> { val x = all.getOrNull(sub.toInt() - 1); if (x == null) note("Nomor sesi tidak ada.") else { saveChat(); switchTo(x.id); note("Beralih ke: ${x.title}") } }
            else -> note("Perintah sesi tidak dikenal.")
        }
    }

    private fun cmdMemory(arg: String) {
        when {
            arg.isEmpty() -> note(memory.all().takeLast(40).let { r -> if (r.isEmpty()) "Ingatan kosong." else r.joinToString("\n") { "[${it.id}] ${it.text}" } } + "\n\n/ingatan lupa <id> atau /ingatan lupa semua")
            arg.startsWith("lupa") -> { val id = arg.removePrefix("lupa").trim(); if (id == "semua") { memory.clear(); note("Semua ingatan dihapus.") } else note(if (memory.forget(id)) "Dilupakan." else "Catatan tidak ditemukan.") }
            else -> note("Contoh: /ingatan, /ingatan lupa <id>")
        }
    }

    private fun cmdTasks(arg: String) {
        val p = arg.split(" ", limit = 2); val sub = p[0]; val id = p.getOrElse(1) { "" }.trim(); val now = System.currentTimeMillis()
        when {
            arg.isEmpty() -> note(tasks.all().let { r -> if (r.isEmpty()) "Belum ada tugas. Minta Gabby, mis. 'buat tugas tiap pagi jam 8 untuk meringkas berita'." else r.joinToString("\n") { fmtTask(it) } } + "\n\n/tugas jalan|on|off|hapus <id>")
            sub == "hapus" -> note(if (tasks.remove(id)) "Tugas dihapus." else "Tugas tidak ditemukan.")
            sub == "on" || sub == "off" -> note(if (tasks.setEnabled(id, sub == "on", now)) "OK." else "Tugas tidak ditemukan.")
            sub == "jalan" -> { note(if (tasks.runSoon(id, now)) "Dijadwalkan jalan sekarang." else "Tugas tidak ditemukan."); checkTasks() }
            else -> note("Perintah tugas tidak dikenal.")
        }
    }

    // ---------- rute web (panel pengaturan) ----------
    private fun taskJson(t: Task) = JSONObject().put("id", t.id).put("title", t.title).put("goal", t.goal).put("schedule", Schedules.describe(t.schedule)).put("enabled", t.enabled)
        .put("nextRun", t.nextRun).put("lastRun", t.lastRun).put("lastResult", t.lastResult).put("allow", JSONArray(t.allow))

    private fun webRoutes(): Map<String, (JSONObject) -> JSONObject> = mapOf(
        "GET /api/memory" to { _: JSONObject -> JSONObject().put("notes", JSONArray().also { a -> memory.all().reversed().forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("source", it.source).put("ts", it.ts)) } }) },
        "POST /api/memory/forget" to { b: JSONObject ->
            if (b.optBoolean("all", false)) memory.clear() else if (!memory.forget(b.optString("id"))) throw IllegalArgumentException("Catatan tidak ditemukan.")
            JSONObject().put("ok", true) },
        "GET /api/tasks" to { _: JSONObject -> JSONObject().put("tasks", JSONArray().also { a -> tasks.all().forEach { a.put(taskJson(it)) } }) },
        "POST /api/tasks" to { b: JSONObject ->
            val id = b.optString("id"); val now = System.currentTimeMillis()
            val ok = when (b.optString("op")) {
                "remove" -> tasks.remove(id); "enable" -> tasks.setEnabled(id, true, now); "disable" -> tasks.setEnabled(id, false, now)
                "run" -> tasks.runSoon(id, now).also { ui.post { checkTasks() } }
                else -> throw IllegalArgumentException("Operasi tidak dikenal.")
            }
            if (!ok) throw IllegalArgumentException("Tugas tidak ditemukan.")
            JSONObject().put("ok", true) },
        "GET /api/sessions" to { _: JSONObject -> JSONObject().put("current", curSession).put("sessions", JSONArray().also { a -> sessions.list().forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated)) } }) },
        "POST /api/sessions" to { b: JSONObject ->
            if (b.optString("op") != "delete") throw IllegalArgumentException("Operasi tidak dikenal.")
            val id = b.optString("id")
            if (!sessions.delete(id)) throw IllegalArgumentException("Sesi tidak ditemukan.")
            if (id == curSession) ui.post { switchTo(sessions.currentId() ?: sessions.create("", System.currentTimeMillis()).id) }
            JSONObject().put("ok", true) }
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (intent?.action == ACTION_REFRESH && !st.active && tasks.enabledCount() == 0 && System.currentTimeMillis() > serverUntil) { stopSelf(); return START_NOT_STICKY }
        startServer()
        refreshOverlay()
        ui.removeCallbacks(tick); ui.postDelayed(tick, 5_000)   // cek cepat: tugas yang terlewat saat layanan mati dijalankan sekali
        return START_STICKY
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        saveChat()
        removeOverlay(); hideApproval()
        server?.stop(); io.shutdownNow(); taskExec.shutdownNow()
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Gabby", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_gabby).setContentTitle("Gabby aktif")
            .setContentText("Ketuk maskot di layar untuk bertanya").setContentIntent(pi).setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    // ---------- server pengaturan lokal ----------
    private fun startServer() {
        if (server != null) return
        val candidates = (listOfNotNull(st.port.takeIf { it > 0 }) + (8787..8797)).distinct()
        for (p in candidates) {
            try {
                val s = LocalServer(p, st.token(), { assets.open("settings.html").bufferedReader().use { it.readText() } }, { st.state() },
                    { j -> val e = st.apply(j); if (e == null) ui.post { refreshOverlay() }; e }, webRoutes())
                s.start(); server = s; st.port = p; return
            } catch (e: Exception) { /* port terpakai, coba berikutnya */ }
        }
    }

    // ---------- overlay ----------
    private fun refreshOverlay() {
        val want = st.active && AndroidSettings.canDrawOverlays(this)
        if (want && !overlayOn) addOverlay() else if (!want && overlayOn) removeOverlay()
    }

    private fun addOverlay() {
        val d = resources.displayMetrics; val size = dp(68)
        val m = MascotView(this); mascot = m
        val box = FrameLayout(this).apply { addView(m, FrameLayout.LayoutParams(size, size)); contentDescription = "Gabby" }
        blp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = kv.get("bx")?.toIntOrNull() ?: (d.widthPixels - size - dp(12)); y = kv.get("by")?.toIntOrNull() ?: (d.heightPixels / 2)
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var drag = false
        box.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = blp.x; oy = blp.y; drag = false; mascot?.squished = true; mascot?.state = MascotView.S.HOVER }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!drag && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) drag = true
                    if (drag) {
                        blp.x = (ox + dx).toInt().coerceIn(0, maxOf(0, d.widthPixels - size)); blp.y = (oy + dy).toInt().coerceIn(0, maxOf(0, d.heightPixels - size))
                        wm?.updateViewLayout(box, blp)
                        mascot?.tilt = (dx / dp(40)).coerceIn(-1f, 1f) * 10f   // miring searah geseran
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mascot?.squished = false; mascot?.tilt = 0f
                    if (drag) { kv.put("bx", blp.x.toString()); kv.put("by", blp.y.toString()); setState(MascotView.S.IDLE) }
                    else if (e.actionMasked == MotionEvent.ACTION_UP) openPanel(true) else setState(MascotView.S.IDLE)
                }
            }
            true
        }
        wm?.addView(box, blp); bubble = box

        panel = buildPanel()
        val pw = minOf(d.widthPixels - dp(24), dp(380)); val ph = (d.heightPixels * 0.70).toInt()
        val plp = WindowManager.LayoutParams(pw, ph, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(16); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        panel?.visibility = View.GONE
        wm?.addView(panel, plp)
        if (items.isNotEmpty()) { greeted = true; items.forEach { addMsg(it.text, kindOf(it.kind), record = false) } }
        overlayOn = true; setState(MascotView.S.IDLE)
    }

    private fun removeOverlay() {
        try { bubble?.let { wm?.removeView(it) } } catch (e: Exception) {}
        try { panel?.let { wm?.removeView(it) } } catch (e: Exception) {}
        bubble = null; panel = null; mascot = null; headMascot = null; overlayOn = false; panelOpen = false
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(0xFF2F6BFF.toInt()); background = rounded(0xFFEAF0FF.toInt(), 14)
        setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { onClick() }
    }

    private fun buildPanel(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0xFFD7DBF5.toInt()) }
            elevation = dp(10).toFloat()
        }
        val hm = MascotView(this); headMascot = hm
        sub = TextView(this).apply { text = "AI Assistant"; textSize = 12f; setTextColor(MUTE) }
        val title = TextView(this).apply { text = "Gabby"; textSize = 17f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(sub) }
        fun hbtn(t: String, onClick: () -> Unit) = TextView(this).apply { text = t; textSize = 20f; setTextColor(MUTE); setPadding(dp(12), dp(4), dp(12), dp(4)); setOnClickListener { onClick() } }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(6), dp(8))
            addView(hm, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            addView(hbtn("–") { openPanel(false) }); addView(hbtn("✕") { openPanel(false) })
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
        scroll = ScrollView(this).apply { addView(list) }
        input = EditText(this).apply {
            hint = "Ketik pesan…"; textSize = 14f; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val send = Button(this).apply {
            text = "Kirim"; isAllCaps = false; setTextColor(Color.WHITE); background = grad(12); minHeight = 0; minimumHeight = 0
            setPadding(dp(14), dp(8), dp(14), dp(8)); setOnClickListener { if (busy) stopAgent() else send(input.text.toString()) }
        }
        sendBtn = send
        fun chipRow(vararg specs: Pair<String, () -> Unit>) = LinearLayout(this).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            specs.forEachIndexed { i, (label, fn) -> addView(chip(label, fn), LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) }) }
        }
        val chips = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow(
                "/tanya" to { input.setText("/tanya "); input.setSelection(input.text.length); input.requestFocus(); Unit },
                "/scan" to { send("/scan") }, "/files" to { send("/files") }, "/sesi" to { send("/sesi") },
                "/ingatan" to { send("/ingatan") }, "/tugas" to { send("/tugas") }, "/reset" to { send("/reset") }))
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(12))
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); addView(send, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        root.addView(head); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(chips); root.addView(bar)
        return root
    }

    private fun openPanel(open: Boolean) {
        panelOpen = open
        panel?.visibility = if (open) View.VISIBLE else View.GONE
        bubble?.visibility = if (open) View.GONE else View.VISIBLE
        if (open && !greeted) {
            greeted = true
            addMsg("Halo, aku Gabby. Tanya apa saja, atau ketik /scan untuk memindai layar. Setiap perintah terminal atau akses web akan meminta izinmu dulu.", K.BOT)
            if (!configured()) addMsg("Atur penyedia AI dan API key dulu lewat tombol Pengaturan di aplikasi Gabby.", K.NOTE)
        }
        if (open) setState(if (busy) MascotView.S.THINKING else MascotView.S.IDLE)
    }

    private fun kindOf(k: String) = when (k) { "user" -> K.USER; "tool" -> K.TOOL; "note" -> K.NOTE; else -> K.BOT }
    private fun nameOf(k: K) = when (k) { K.USER -> "user"; K.TOOL -> "tool"; K.NOTE -> "note"; K.BOT -> "bot" }

    private fun addMsg(text: String, k: K, record: Boolean = true): TextView? {
        if (record) items.add(ChatItem(nameOf(k), text))
        if (!overlayOn) return null
        val tv = TextView(this).apply {
            this.text = text; textSize = if (k == K.TOOL) 11f else 14f; setPadding(dp(12), dp(8), dp(12), dp(8)); setTextIsSelectable(k != K.USER)
            when (k) {
                K.USER -> { setTextColor(Color.WHITE); background = grad(14) }
                K.BOT -> { setTextColor(INK); background = rounded(0xFFEEF1FF.toInt(), 14) }
                K.TOOL -> { setTextColor(0xFF444455.toInt()); typeface = Typeface.MONOSPACE; background = rounded(0xFFF2F2F5.toInt(), 10) }
                K.NOTE -> { setTextColor(MUTE); textSize = 12f; gravity = Gravity.CENTER }
            }
        }
        val lp = LinearLayout.LayoutParams(if (k == K.NOTE) -1 else -2, -2).apply {
            topMargin = dp(6)
            if (k == K.USER) { gravity = Gravity.END; marginStart = dp(48) } else if (k != K.NOTE) { gravity = Gravity.START; marginEnd = dp(28) }
        }
        list.addView(tv, lp); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun removeLive() { live?.let { list.removeView(it) }; live = null }

    private fun setState(s: MascotView.S) {
        mascot?.state = s; headMascot?.state = if (s == MascotView.S.IDLE && panelOpen) MascotView.S.OPEN else s
        if (::sub.isInitialized) sub.text = when (s) {
            MascotView.S.THINKING -> "Sedang berpikir…"; MascotView.S.SCANNING -> "Memindai layar…"; MascotView.S.WORKING -> "Sedang bekerja…"
            MascotView.S.SUCCESS -> "Selesai"; MascotView.S.NOTIFY -> "Ada balasan baru"; MascotView.S.ERROR -> "Terjadi kesalahan"; MascotView.S.SLEEP -> "Tidur. Ketuk untuk bangun"; else -> "AI Assistant"
        }
        ui.removeCallbacks(sleepRun)
        if (s == MascotView.S.IDLE) ui.postDelayed(sleepRun, 90_000)
        if (s == MascotView.S.SUCCESS || s == MascotView.S.ERROR) ui.postDelayed({ if (!busy) setState(MascotView.S.IDLE) }, 3000)
        if (s == MascotView.S.NOTIFY) ui.postDelayed({ if (!busy && !panelOpen) setState(MascotView.S.IDLE) }, 6000)
    }

    // ---------- chat ----------
    private fun setBusy(b: Boolean) { busy = b; sendBtn?.text = if (b) "Stop" else "Kirim" }

    /** Menghentikan agen: memutus jawaban yang mengalir dan menutup modal izin yang sedang menunggu. */
    private fun stopAgent() { agent.cancel(); approvalQueue?.offer(Approval.NO); hideApproval() }

    private fun configured() = st.key(st.provider) != null && st.model(st.provider).isNotBlank()

    private fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return
        input.setText("")
        when {
            text == "/reset" -> { agent.reset(); items.clear(); live = null; list.removeAllViews(); saveChat(); note("Percakapan direset. Izin sesi dicabut.") }
            text == "/sesi" || text.startsWith("/sesi ") -> cmdSessions(text.removePrefix("/sesi").trim())
            text == "/ingatan" || text.startsWith("/ingatan ") -> cmdMemory(text.removePrefix("/ingatan").trim())
            text == "/tugas" || text.startsWith("/tugas ") -> cmdTasks(text.removePrefix("/tugas").trim())
            text == "/files" -> addMsg(
                if (st.filesTree == null) "Belum ada folder yang diizinkan. Buka aplikasi Gabby lalu ketuk 'Izinkan folder untuk Gabby'."
                else "Folder aktif: ${st.filesName.ifEmpty { "(dipilih)" }}. Contoh: /files tampilkan isi folder, atau /files baca catatan.txt. Membaca ${if (st.autoRead) "otomatis" else "meminta izin tiap kali"}; menulis selalu meminta izin.", K.NOTE, record = false)
            text.startsWith("/files ") -> ask("Gunakan alat files. " + text.removePrefix("/files").trim(), null)
            text.startsWith("/scan") -> scan(text.removePrefix("/scan").trim())
            text.startsWith("/tanya") -> ask(text.removePrefix("/tanya").trim().ifEmpty { "Halo" }, null)
            else -> ask(text, null)
        }
    }

    private fun ask(text: String, image: ByteArray?) {
        if (!configured()) { addMsg("Atur penyedia AI dan API key dulu lewat tombol Pengaturan di aplikasi Gabby.", K.NOTE); setState(MascotView.S.ERROR); return }
        setBusy(true); addMsg(text, K.USER); setState(MascotView.S.THINKING)
        io.execute {
            val r = agent.ask(text, image)
            ui.post {
                setBusy(false); removeLive(); saveChat()
                r.onSuccess { setState(if (panelOpen) MascotView.S.SUCCESS else MascotView.S.NOTIFY) }.onFailure { addMsg("Gagal: " + (it.message ?: "terjadi kesalahan"), K.NOTE); setState(MascotView.S.ERROR) }
            }
        }
    }

    private fun scan(question: String) {
        if (!configured()) { addMsg("Atur penyedia AI dan API key dulu lewat tombol Pengaturan di aplikasi Gabby.", K.NOTE); setState(MascotView.S.ERROR); return }
        val svc = GabbyAccessibilityService.instance
        if (svc == null) { addMsg("Aktifkan layanan Aksesibilitas Gabby dulu (Pengaturan > Aksesibilitas) agar bisa scan layar.", K.NOTE); setState(MascotView.S.ERROR); return }
        if (!st.screenConsent) {
            showApproval("Izinkan Gabby menangkap layar?", "Gabby akan mengambil screenshot saat kamu menekan Scan. Gambar dikirim ke penyedia AI yang kamu pilih, memakai API key milikmu.", "", false, "Izinkan") { r ->
                if (r != Approval.NO) { st.screenConsent = true; scan(question) }
            }
            return
        }
        setBusy(true); setState(MascotView.S.SCANNING)
        panel?.visibility = View.GONE; bubble?.visibility = View.GONE   // sembunyikan Gabby agar tidak ikut tertangkap
        ui.postDelayed({
            svc.capture { jpeg, err ->
                panel?.visibility = if (panelOpen) View.VISIBLE else View.GONE; bubble?.visibility = if (panelOpen) View.GONE else View.VISIBLE
                setBusy(false)
                if (jpeg == null) { addMsg(err ?: "Gagal memindai layar.", K.NOTE); setState(MascotView.S.ERROR) }
                else { addMsg("Gambar layar telah di-scan.", K.NOTE); ask(question.ifEmpty { "Ini apaan? Jelaskan apa yang terlihat di layar dan apa artinya." }, jpeg) }
            }
        }, 400)
    }

    private val agentUi = object : AgentUi {
        override fun status(s: String) { ui.post { setState(if (s.startsWith("Bekerja")) MascotView.S.WORKING else MascotView.S.THINKING) } }
        override fun partial(text: String) {
            ui.post {
                if (text.isEmpty()) removeLive()
                else { val tv = live ?: addMsg(text, K.BOT, record = false).also { live = it }; tv?.text = text; scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
            }
        }
        override fun say(text: String) {
            ui.post {
                val l = live
                if (l != null) { l.text = text; live = null; items.add(ChatItem("bot", text)) } else addMsg(text, K.BOT)
            }
        }
        override fun tool(text: String) { ui.post { addMsg(text, K.TOOL) } }
        override fun approve(a: Action, canSession: Boolean): Approval {
            val q = ArrayBlockingQueue<Approval>(1); approvalQueue = q
            ui.post { showApproval(a.title(), a.detail(), a.why, canSession, if (canSession) "Sekali" else if (a.tool == "plan") "Setujui" else "Izinkan") { q.offer(it) } }
            val r = q.poll(180, TimeUnit.SECONDS)
            approvalQueue = null
            if (r == null) ui.post { hideApproval() }
            return r ?: Approval.NO
        }
    }

    // ---------- modal persetujuan ----------
    private fun hideApproval() { try { approval?.let { wm?.removeView(it) } } catch (e: Exception) {}; approval = null }

    private fun showApproval(title: String, detail: String, why: String, canSession: Boolean, okLabel: String, cb: (Approval) -> Unit) {
        hideApproval()
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(Color.WHITE, 18); setPadding(dp(18), dp(16), dp(18), dp(14)); elevation = dp(12).toFloat() }
        card.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD })
        if (why.isNotBlank()) card.addView(TextView(this).apply { text = why; textSize = 13f; setTextColor(MUTE); setPadding(0, dp(6), 0, 0) })
        if (detail.isNotBlank()) card.addView(TextView(this).apply {
            text = detail; textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(INK); maxLines = 8; setTextIsSelectable(true)
            background = rounded(0xFFF2F2F5.toInt(), 10); setPadding(dp(10), dp(8), dp(10), dp(8))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        fun b(t: String, primary: Boolean, ok: Approval) = Button(this).apply {
            text = t; isAllCaps = false; minHeight = 0; minimumHeight = 0; setPadding(dp(14), dp(9), dp(14), dp(9))
            if (primary) { setTextColor(Color.WHITE); background = grad(12) } else { setTextColor(INK); background = rounded(0xFFEEF0F8.toInt(), 12) }
            setOnClickListener { hideApproval(); cb(ok) }
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.END
            addView(b("Batal", false, Approval.NO))
            addView(b(okLabel, true, Approval.ONCE), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            if (canSession) addView(b("Sesi 10 mnt", false, Approval.SESSION), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val lp = WindowManager.LayoutParams(minOf(resources.displayMetrics.widthPixels - dp(32), dp(320)), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER; dimAmount = 0.45f }
        wm?.addView(card, lp); approval = card
    }
}
