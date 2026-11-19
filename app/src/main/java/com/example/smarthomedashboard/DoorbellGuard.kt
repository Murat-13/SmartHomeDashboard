package com.example.smarthomedashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Охрана (docs/guard-tz.md): человек стоит у входа дольше заданного времени — уведомление на телефон и ★ у ролика.
 * Включается кнопкой в раскрытом домофоне и по расписанию; все настройки — только этого планшета
 * (`doorbell_away.xml`, рядом с уведомлением о звонке). Главный поток; работа с файлами — свой поток.
 */
internal object DoorbellGuard {
    const val GAP_MS = 15_000L
    const val REARM_MS = 120_000L
    val SECONDS_RANGE = 10..600
    private const val TICK_MS = 1_000L
    private const val STAR_EVERY_MS = 30_000L
    private const val MARK_TTL_MS = 2 * 3_600_000L
    private const val TAG = "DoorbellGuard"

    data class Settings(
        val enabled: Boolean = false,
        val schedule: Boolean = true,
        /** Начало и конец ночной охраны — минуты от полуночи; конец раньше начала — через полночь. */
        val startMin: Int = 0,
        val endMin: Int = 6 * 60,
        val seconds: Int = 60,
        val star: Boolean = true,
        /** Включена кнопкой (до выключения кнопкой). */
        val manualOn: Boolean = false,
        /** Выключена кнопкой посреди ночи — до этого времени (мс на стене) расписание не включает. */
        val offUntil: Long = 0L
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(DoorbellAway.PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context, tileId: String): Settings {
        val p = prefs(context)
        val d = Settings()
        return Settings(
            enabled = p.getBoolean("$tileId.guard", d.enabled),
            schedule = p.getBoolean("$tileId.guard_schedule", d.schedule),
            startMin = p.getInt("$tileId.guard_start", d.startMin).coerceIn(0, 1439),
            endMin = p.getInt("$tileId.guard_end", d.endMin).coerceIn(0, 1439),
            seconds = p.getInt("$tileId.guard_seconds", d.seconds).coerceIn(SECONDS_RANGE),
            star = p.getBoolean("$tileId.guard_star", d.star),
            manualOn = p.getBoolean("$tileId.guard_manual", d.manualOn),
            offUntil = p.getLong("$tileId.guard_off_until", d.offUntil)
        )
    }

    fun save(context: Context, tileId: String, s: Settings) {
        prefs(context).edit()
            .putBoolean("$tileId.guard", s.enabled)
            .putBoolean("$tileId.guard_schedule", s.schedule)
            .putInt("$tileId.guard_start", s.startMin.coerceIn(0, 1439))
            .putInt("$tileId.guard_end", s.endMin.coerceIn(0, 1439))
            .putInt("$tileId.guard_seconds", s.seconds.coerceIn(SECONDS_RANGE))
            .putBoolean("$tileId.guard_star", s.star)
            .putBoolean("$tileId.guard_manual", s.manualOn)
            .putLong("$tileId.guard_off_until", s.offUntil)
            .apply()
        DoorbellHub.refreshCards()
    }

    // ---- Расписание ----

    fun minuteOf(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** Минута [minute] внутри окна [start, end) (через полночь — если конец раньше начала). Равные — окна нет. */
    fun inWindow(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    /** Конец текущего окна охраны (мс на стене) — до него действует ручное выключение. */
    fun windowEnd(now: Long, end: Int): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, end / 60); set(Calendar.MINUTE, end % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Охрана сейчас включена: кнопкой или по расписанию (если ночью не выключили кнопкой). */
    fun armed(s: Settings, now: Long): Boolean {
        if (!s.enabled) return false
        if (s.manualOn) return true
        return s.schedule && now >= s.offUntil && inWindow(minuteOf(now), s.startMin, s.endMin)
    }

    /** Кнопка: включена — выключить (ночью — до конца ночи), выключена — включить. */
    fun toggle(context: Context, tileId: String, now: Long = System.currentTimeMillis()) {
        val s = settings(context, tileId)
        val next = if (armed(s, now)) {
            s.copy(manualOn = false, offUntil = if (s.schedule && inWindow(minuteOf(now), s.startMin, s.endMin)) windowEnd(now, s.endMin) else 0L)
        } else s.copy(manualOn = true, offUntil = 0L)
        Log.i(TAG, if (armed(next, now)) "Охрана включена кнопкой" else "Охрана выключена кнопкой")
        save(context, tileId, next)
    }

    fun time(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

    /** «00:30» → 30; неверное — null. */
    fun parseTime(text: String): Int? {
        val m = Regex("^\\s*(\\d{1,2})[:.](\\d{2})\\s*$").matchEntire(text) ?: return null
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    // ---- Присутствие ----

    /**
     * Человек у входа без перерыва (перерыв распознавания ≤ [GAP_MS] не в счёт). Тревога — один раз, следующая — после
     * [REARM_MS] без людей. Чистая логика: время — монотонное, мс.
     */
    class Presence {
        private var start = -1L
        private var lastSeen = -1L
        private var alerted = false
        /** С какого момента стоит (или -1). */
        val since get() = start

        /** true — пора поднять тревогу. */
        fun update(now: Long, seen: Boolean, thresholdMs: Long): Boolean {
            if (seen) {
                if (start < 0 || now - lastSeen > GAP_MS) start = now
                lastSeen = now
                if (!alerted && now - start >= thresholdMs) { alerted = true; return true }
            } else if (lastSeen >= 0 && now - lastSeen > REARM_MS) {
                alerted = false; start = -1; lastSeen = -1
            }
            return false
        }

        fun reset() { start = -1; lastSeen = -1; alerted = false }
    }

    // ---- Работа на панели ----

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "doorbell-guard").apply { isDaemon = true } }
    private val presence = mutableMapOf<String, Presence>()
    private val wasArmed = mutableMapOf<String, Boolean>()
    private var app: Context? = null
    private var lastStarPass = 0L

    /** Человек у плитки сейчас: датчик HA или событие камеры напрямую. Подменяется в тестах. */
    internal var seen: (String) -> Boolean = { tileId ->
        val haPerson = DoorbellHub.readings?.let { r -> DoorbellHub.config(tileId)?.let { DoorbellModel.snapshot(it, r).person } } == true
        haPerson || DoorbellConfig.Detection.PERSON in DirectEvents.activeDetections(tileId)
    }
    internal var tiles: () -> List<Pair<String, String>> = { DoorbellHub.doorbellTiles().map { it.id to it.title } }
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }
    internal var wall: () -> Long = { System.currentTimeMillis() }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    fun attach(context: Context) {
        if (app != null) return
        app = context.applicationContext
        main.postDelayed(ticker, TICK_MS)
    }

    fun detach() {
        main.removeCallbacks(ticker)
        presence.clear()
        wasArmed.clear()
        app = null
    }

    internal fun tick() {
        val context = app ?: return
        val now = clock()
        val wallNow = wall()
        for ((tileId, title) in tiles()) {
            val s = settings(context, tileId)
            val armed = armed(s, wallNow)
            if (wasArmed[tileId] != armed) {
                wasArmed[tileId] = armed
                Log.i(TAG, if (armed) "Охрана включена" else "Охрана выключена")
                DoorbellHub.refreshCards()
            }
            val p = presence.getOrPut(tileId) { Presence() }
            if (!armed) { p.reset(); continue }
            if (p.update(now, seen(tileId), s.seconds * 1000L)) alarm(context, tileId, title, s, wallNow - (now - p.since))
        }
        if (now - lastStarPass >= STAR_EVERY_MS) { lastStarPass = now; starPending(context) }
    }

    /** Тревога: уведомление на телефон (тот же, что для звонка) и отметка для ★ ролика. */
    private fun alarm(context: Context, tileId: String, title: String, s: Settings, sinceWall: Long) {
        Log.i(TAG, "Тревога: человек у входа дольше ${s.seconds} с")
        val away = DoorbellAway.settings(context, tileId)
        if (away.notifyService.isNotEmpty()) {
            val config = DoorbellHub.config(tileId) ?: DoorbellConfig()
            val data = DoorbellAway.notification(title, config.camera, wall(), tileId).toMutableMap()
            data["message"] = "У калитки человек больше ${duration(s.seconds)} · " +
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(wall()))
            (data["data"] as? org.json.JSONObject)?.put("tag", "doorbell_guard_$tileId")
            DoorbellAway.sender(away.notifyService.substringBefore('.'), away.notifyService.substringAfter('.'), data) { ok ->
                Log.i(TAG, if (ok) "Уведомление охраны отправлено" else "Уведомление охраны не отправлено")
            }
        }
        if (s.star) addMark(context, tileId, sinceWall)
    }

    fun duration(seconds: Int): String = when {
        seconds % 60 == 0 && seconds >= 60 -> if (seconds == 60) "минуты" else "${seconds / 60} мин"
        else -> "$seconds с"
    }

    // ---- ★ ролика ----

    private fun addMark(context: Context, tileId: String, atWall: Long) {
        val p = prefs(context)
        val marks = readMarks(p.getString("$tileId.guard_marks", null)) + atWall
        p.edit().putString("$tileId.guard_marks", JSONArray(marks).toString()).apply()
    }

    private fun readMarks(text: String?): List<Long> = try {
        val a = JSONArray(text ?: "[]"); (0 until a.length()).map { a.getLong(it) }
    } catch (_: Exception) { emptyList() }

    /** Отметки, чьи ролики событий уже закрыты: ★ и копия в папку копий. Ролик ещё пишется — отметка ждёт. */
    private fun starPending(context: Context) {
        val p = prefs(context)
        val todo = tiles().map { it.first }.mapNotNull { id -> readMarks(p.getString("$id.guard_marks", null)).takeIf { it.isNotEmpty() }?.let { id to it } }
        if (todo.isEmpty()) return
        val nowWall = wall()
        io.execute {
            var starred = 0
            for ((tileId, marks) in todo) {
                val left = starFor(context, tileId, marks, nowWall)
                starred += marks.size - left.size
                p.edit().putString("$tileId.guard_marks", JSONArray(left).toString()).apply()
            }
            if (starred > 0) try { ArchiveCopy.run(context) } catch (_: Exception) {}
        }
    }

    /** ★ у закрытых роликов событий, где есть отметка; возвращает отметки, которые ещё ждут. */
    internal fun starFor(context: Context, tileId: String, marks: List<Long>, nowWall: Long,
                         archive: RecArchive = RecArchive(context)): List<Long> {
        val files = archive.load(RecLayout.cameraKey(tileId)).events.map { it.file }
        val left = mutableListOf<Long>()
        for (mark in marks) {
            val file = files.firstOrNull { mark >= it.startMs - GAP_MS && mark <= it.endMs }
            when {
                file != null -> { if (!file.starred) archive.setStarred(file, true); Log.i(TAG, "Ролик охраны отмечен ★") }
                nowWall - mark < MARK_TTL_MS -> left += mark // ролик ещё пишется или не появился
                else -> Log.i(TAG, "Ролик для отметки охраны не найден")
            }
        }
        return left
    }
}
