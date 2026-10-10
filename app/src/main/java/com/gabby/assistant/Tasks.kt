package com.gabby.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

sealed class Schedule {
    class Once(val at: Long) : Schedule()
    class Every(val minutes: Int) : Schedule()
    class Daily(val hour: Int, val minute: Int) : Schedule()
}

object Schedules {
    const val MIN_EVERY = 15
    val ALLOW = setOf("fetch", "search", "files", "files_write", "memory", "shell", "termux", "open", "clipboard")

    /** Dari argumen aksi: in_minutes (sekali), every_minutes (berulang, minimal 15), atau daily_at "HH:MM". Tepat satu. */
    fun parse(j: JSONObject, now: Long): Schedule? {
        val keys = listOf("in_minutes", "every_minutes", "daily_at").filter { j.has(it) }
        if (keys.size != 1) return null
        return when (keys[0]) {
            "in_minutes" -> j.optInt("in_minutes", -1).takeIf { it in 1..1440 }?.let { Schedule.Once(now + it * 60_000L) }
            "every_minutes" -> j.optInt("every_minutes", -1).takeIf { it in MIN_EVERY..10080 }?.let { Schedule.Every(it) }
            else -> Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(j.optString("daily_at").trim())?.let { Schedule.Daily(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        }
    }

    fun toJson(s: Schedule): JSONObject = when (s) {
        is Schedule.Once -> JSONObject().put("type", "once").put("at", s.at)
        is Schedule.Every -> JSONObject().put("type", "every").put("minutes", s.minutes)
        is Schedule.Daily -> JSONObject().put("type", "daily").put("hour", s.hour).put("minute", s.minute)
    }

    fun fromJson(o: JSONObject): Schedule? = when (o.optString("type")) {
        "once" -> Schedule.Once(o.getLong("at"))
        "every" -> Schedule.Every(o.getInt("minutes"))
        "daily" -> Schedule.Daily(o.getInt("hour"), o.getInt("minute"))
        else -> null
    }

    /** Waktu jalan pertama. */
    fun first(s: Schedule, now: Long, zone: ZoneId): Long = when (s) {
        is Schedule.Once -> s.at
        is Schedule.Every -> now + s.minutes * 60_000L
        is Schedule.Daily -> dailyAfter(s, now, zone)
    }

    /** Waktu jalan berikutnya setelah satu kali jalan. null = selesai (sekali saja). */
    fun after(s: Schedule, now: Long, zone: ZoneId): Long? = when (s) {
        is Schedule.Once -> null
        is Schedule.Every -> now + s.minutes * 60_000L
        is Schedule.Daily -> dailyAfter(s, now, zone)
    }

    private fun dailyAfter(s: Schedule.Daily, now: Long, zone: ZoneId): Long {
        var t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).withHour(s.hour).withMinute(s.minute).withSecond(0).withNano(0)
        if (t.toInstant().toEpochMilli() <= now) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }

    fun describe(s: Schedule): String = when (s) {
        is Schedule.Once -> "sekali saja"
        is Schedule.Every -> if (s.minutes % 60 == 0) "tiap ${s.minutes / 60} jam" else "tiap ${s.minutes} menit"
        is Schedule.Daily -> "tiap hari pukul %02d:%02d".format(s.hour, s.minute)
    }
}

class Task(
    val id: String, var title: String, val goal: String, val schedule: Schedule, val allow: List<String>,
    var enabled: Boolean, val maxSteps: Int, var nextRun: Long, var lastRun: Long = 0, var lastResult: String = ""
)

/** Tugas terjadwal, disimpan lokal. Tugas dijalankan tanpa kehadiran pengguna, jadi izin alatnya ditetapkan saat dibuat (allow). */
class TaskStore(private val file: File, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    companion object { const val MAX_TASKS = 10 }
    private val tasks = ArrayList<Task>()

    init {
        try {
            if (file.exists()) { val a = JSONObject(file.readText()).getJSONArray("tasks")
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    val sch = Schedules.fromJson(o.getJSONObject("schedule")) ?: continue
                    tasks.add(Task(o.getString("id"), o.getString("title"), o.getString("goal"), sch, o.getJSONArray("allow").let { l -> (0 until l.length()).map { l.getString(it) } },
                        o.getBoolean("enabled"), o.getInt("maxSteps"), o.getLong("nextRun"), o.optLong("lastRun"), o.optString("lastResult"))) } }
        } catch (e: Exception) { tasks.clear() }
    }

    private fun save() = atomicWrite(file, JSONObject().put("tasks", JSONArray().also { a -> tasks.forEach { t ->
        a.put(JSONObject().put("id", t.id).put("title", t.title).put("goal", t.goal).put("schedule", Schedules.toJson(t.schedule)).put("allow", JSONArray(t.allow))
            .put("enabled", t.enabled).put("maxSteps", t.maxSteps).put("nextRun", t.nextRun).put("lastRun", t.lastRun).put("lastResult", t.lastResult)) } }).toString())

    @Synchronized fun all(): List<Task> = tasks.toList()
    @Synchronized fun get(id: String): Task? = tasks.firstOrNull { it.id == id }
    @Synchronized fun enabledCount(): Int = tasks.count { it.enabled }

    @Synchronized fun add(title: String, goal: String, schedule: Schedule, allow: List<String>, maxSteps: Int, now: Long): Task? {
        if (tasks.size >= MAX_TASKS) return null
        var id = "t" + java.lang.Long.toString(now, 36); var n = 1
        while (tasks.any { it.id == id }) id = "t" + java.lang.Long.toString(now, 36) + (n++)
        val t = Task(id, title.trim().take(60).ifEmpty { goal.trim().take(40) }, goal.trim().take(1000), schedule, allow.filter { it in Schedules.ALLOW }.distinct(),
            true, maxSteps.coerceIn(1, 20), Schedules.first(schedule, now, zone()))
        tasks.add(t); save(); return t
    }

    @Synchronized fun remove(id: String): Boolean { val r = tasks.removeAll { it.id == id }; if (r) save(); return r }

    @Synchronized fun setEnabled(id: String, on: Boolean, now: Long): Boolean {
        val t = tasks.firstOrNull { it.id == id } ?: return false
        t.enabled = on
        if (on && t.nextRun <= 0) t.nextRun = Schedules.after(t.schedule, now, zone()) ?: now
        save(); return true
    }

    /** Menjadwalkan jalan secepatnya ("jalankan sekarang"). */
    @Synchronized fun runSoon(id: String, now: Long): Boolean { val t = tasks.firstOrNull { it.id == id } ?: return false; t.enabled = true; t.nextRun = now; save(); return true }

    @Synchronized fun due(now: Long): List<Task> = tasks.filter { it.enabled && it.nextRun in 1..now }

    /** Mencatat hasil jalan dan menjadwalkan berikutnya. Tugas yang terlewat hanya dijalankan sekali (tidak menumpuk). */
    @Synchronized fun markRan(id: String, now: Long, result: String) {
        val t = tasks.firstOrNull { it.id == id } ?: return
        t.lastRun = now; t.lastResult = result.take(2000)
        val n = Schedules.after(t.schedule, now, zone())
        if (n == null) { t.enabled = false; t.nextRun = 0 } else t.nextRun = n
        save()
    }
}
