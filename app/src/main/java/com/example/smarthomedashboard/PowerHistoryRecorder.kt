package com.example.smarthomedashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Регистратор истории мощности (docs/power-graph.md). Публичные методы — на главном потоке,
 * база — только на [io]. Пишет показания текущих источников графика, разрывы (нет связи,
 * unavailable, остановка приложения, смена источника) и подгружает пропуски из истории HA
 * (только чтение). Команд HA не отправляет.
 */
internal class PowerHistoryRecorder(
    private val store: PowerHistoryStore,
    private val io: Executor = Executors.newSingleThreadExecutor { Thread(it, "power-history").apply { priority = Thread.MIN_PRIORITY } },
    private val post: (() -> Unit) -> Unit = { block -> MAIN.post(block) },
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Живые обновления для открытого графика: новое значение или «данные перечитать» (sample = null). */
    fun interface Listener { fun changed(entityId: String, sample: PowerSample?) }

    /** Чтение HA: тип команды, параметры, ответ (null — отказ). Задаётся владельцем соединения. */
    var reader: ((String, JSONObject, (Any?) -> Unit) -> Unit)? = null

    var instance: String? = null
        private set
    private var sources: Set<String> = emptySet()
    private var connected = false
    private var snapshotDone = false
    private val pending = ArrayList<Pair<String, PowerSample>>()
    private val last = HashMap<String, Double?>()
    private val units = HashMap<String, String>()
    private val listeners = LinkedHashSet<Listener>()
    private var flushScheduled = false
    private var importRequested = false

    fun addListener(listener: Listener) { listeners += listener }
    fun removeListener(listener: Listener) { listeners -= listener }

    /** Последнее записанное значение источника в этой сессии («Сейчас» графика). */
    fun latest(entityId: String): Double? = last[entityId]

    fun isSource(entityId: String) = entityId in sources

    /** Сервер HA определён: закрываем прошлые сессии (остановка приложения) и начинаем запись. */
    fun setInstance(key: String) {
        if (instance != null && instance != key) breakAll()
        val changed = instance != key
        instance = key
        connected = true
        if (changed) closeStale(sources)
        flush()
        maybeImport()
        startHeartbeat()
    }

    private var lastCompact = 0L
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!connected) return
            // Значение может не меняться часами: отметка «запись идёт» нужна, чтобы после
            // остановки приложения разрыв начался с неё, а не с последнего изменения.
            flush()
            val now = clock()
            if (now - lastCompact >= PowerHistory.HOUR_MS) { lastCompact = now; compact() }
            MAIN.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun startHeartbeat() {
        MAIN.removeCallbacks(heartbeat)
        MAIN.post(heartbeat)
    }

    fun setSources(entityIds: Set<String>) {
        val next = entityIds.filter { it.isNotBlank() }.toSet()
        val removed = sources - next
        val added = next - sources
        val now = clock()
        removed.forEach { id -> if (last[id] != null && connected) queue(id, PowerSample(now, null)) }
        removed.forEach { last.remove(it) }
        sources = next
        if (instance != null && connected) closeStale(added)
        flush()
        if (added.isNotEmpty()) { importRequested = false; maybeImport() }
    }

    fun onState(entityId: String, state: String, attributes: JSONObject?) {
        if (entityId !in sources) return
        attributes?.optString("unit_of_measurement")?.takeIf { it.isNotBlank() }?.let { units[entityId] = it }
        val watts = PowerHistory.toWatts(state, units[entityId])
        if (last.containsKey(entityId) && last[entityId] == watts) return
        last[entityId] = watts
        val sample = PowerSample(clock(), watts)
        queue(entityId, sample)
        listeners.toList().forEach { it.changed(entityId, sample) }
        scheduleFlush()
    }

    /** Получен полный снимок состояний: единицы известны, можно подгружать пропуски из HA. */
    fun onSnapshotDone() {
        snapshotDone = true
        maybeImport()
    }

    fun onDisconnected() {
        if (connected) breakAll()
        connected = false
        MAIN.removeCallbacks(heartbeat)
        snapshotDone = false
        importRequested = false
        flush()
    }

    private fun breakAll() {
        val now = clock()
        sources.forEach { id -> if (last[id] != null) queue(id, PowerSample(now, null)) }
        last.clear()
    }

    private val lastTs = HashMap<String, Long>()

    /** Время записей источника строго возрастает: разрыв и новое значение в одну мс не вытесняют друг друга. */
    private fun queue(entityId: String, raw: PowerSample) {
        val sample = lastTs[entityId]?.takeIf { raw.ts <= it }?.let { raw.copy(ts = it + 1) } ?: raw
        lastTs[entityId] = sample.ts
        pending += entityId to sample
        if (pending.size > MAX_PENDING) pending.subList(0, pending.size - MAX_PENDING).clear()
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        MAIN.postDelayed({ flushScheduled = false; flush() }, FLUSH_MS)
    }

    /** Пишет накопленное; до определения сервера HA данные ждут в памяти. */
    fun flush() {
        val key = instance ?: return
        val batch = ArrayList(pending)
        pending.clear()
        val alive = if (connected) sources.toList() else emptyList()
        if (batch.isEmpty() && alive.isEmpty()) return
        val now = clock()
        io.execute {
            batch.groupBy({ it.first }, { it.second }).forEach { (id, samples) ->
                store.sourceId(key, id)?.let { store.insert(it, samples) }
            }
            alive.forEach { id -> store.sourceId(key, id)?.let { store.setAlive(it, now) } }
        }
    }

    /** Прошлая запись источника оборвалась без метки (приложение остановлено) — ставим разрыв. */
    private fun closeStale(ids: Collection<String>) {
        val key = instance ?: return
        val list = ids.toList()
        if (list.isEmpty()) return
        io.execute {
            list.forEach { id ->
                val source = store.sourceId(key, id) ?: return@forEach
                val previous = store.last(source) ?: return@forEach
                if (previous.watts == null) return@forEach
                val until = maxOf(store.aliveUntil(source) ?: previous.ts, previous.ts)
                store.insert(source, listOf(PowerSample(until + 1, null)))
            }
        }
    }

    fun compact() {
        val now = clock()
        io.execute { store.compact(now) }
    }

    // ---- Подгрузка пропусков из истории HA (только чтение) ----

    private fun maybeImport() {
        val key = instance ?: return
        val read = reader ?: return
        if (!snapshotDone || importRequested || !connected) return
        importRequested = true
        val ids = sources.filter { units[it] != null }
        val now = clock()
        val units = ids.associateWith { this.units.getValue(it) }
        io.execute {
            val plan = ids.flatMap { id ->
                val source = store.sourceId(key, id) ?: return@flatMap emptyList()
                store.gaps(source, now - PowerHistory.KEEP_MS, now - IMPORT_RECENT_MS)
                    .filter { it.last - it.first >= MIN_GAP_MS }
                    .sortedByDescending { it.first }
                    .take(MAX_IMPORTS_PER_SOURCE)
                    .map { Triple(id, source, it) }
            }
            post { plan.forEach { (id, source, gap) -> request(read, key, id, source, gap, units.getValue(id)) } }
        }
    }

    private fun request(read: (String, JSONObject, (Any?) -> Unit) -> Unit, key: String, entityId: String, source: Long,
                        gap: LongRange, unit: String) {
        val params = JSONObject()
            .put("start_time", iso(gap.first))
            .put("end_time", iso(gap.last + 1))
            .put("entity_ids", JSONArray().put(entityId))
            .put("minimal_response", true)
            .put("no_attributes", true)
            .put("significant_changes_only", false)
            .put("include_start_time_state", true)
        read(HISTORY, params) { result ->
            if (instance != key) return@read
            val samples = parseHistory(result, entityId, unit).filter { it.ts in gap }
            if (samples.isEmpty()) return@read
            io.execute {
                if (store.insert(source, samples, fillBreaks = true) > 0) post { listeners.toList().forEach { it.changed(entityId, null) } }
            }
        }
    }

    // ---- Чтение для графика (результат на главном потоке) ----

    fun series(entityId: String, from: Long, to: Long, maxSamples: Int, done: (PowerSeries) -> Unit) {
        val key = instance
        if (key == null) { done(PowerSeries(emptyList(), emptyList())); return }
        io.execute {
            val source = store.sourceId(key, entityId, create = false)
            val result = if (source == null) PowerSeries(emptyList(), emptyList()) else store.series(source, from, to, maxSamples)
            post { done(result) }
        }
    }

    fun stats(entityId: String, from: Long, to: Long, done: (PowerStats) -> Unit) {
        val key = instance
        val now = clock()
        if (key == null) { done(PowerStats(null, null, null)); return }
        io.execute {
            val source = store.sourceId(key, entityId, create = false)
            val result = if (source == null) PowerStats(null, null, null) else store.stats(source, from, to, now)
            post { done(result) }
        }
    }

    companion object {
        private val MAIN by lazy { Handler(Looper.getMainLooper()) }
        const val HISTORY = "history/history_during_period"
        const val FLUSH_MS = 2_000L
        const val HEARTBEAT_MS = 60_000L
        const val MAX_PENDING = 20_000
        const val MIN_GAP_MS = 30_000L
        /** Последние секунды не запрашиваем: данные о них уже идут живым потоком. */
        const val IMPORT_RECENT_MS = 5_000L
        const val MAX_IMPORTS_PER_SOURCE = 20

        @Volatile private var shared: Pair<Context, PowerHistoryRecorder>? = null

        /** Один регистратор на приложение: переживает пересоздание экрана вместе с соединением HA. */
        fun get(context: Context): PowerHistoryRecorder = synchronized(this) {
            val app = context.applicationContext
            shared?.takeIf { it.first === app }?.second
                ?: PowerHistoryRecorder(PowerHistoryStore(app)).also { shared = app to it }
        }

        /**
         * Ключ сервера HA из ответа `get_config`: необратимый хэш названия, часового пояса и координат.
         * Адреса подключения не участвуют — локальный и внешний адрес одного дома дают один ключ.
         */
        fun instanceKey(config: Any?): String? {
            val json = config as? JSONObject ?: return null
            fun number(key: String) = json.optDouble(key).takeIf { it.isFinite() }
                ?.let { java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()
            val text = listOf(json.optString("location_name"), json.optString("time_zone"),
                number("latitude"), number("longitude"), number("elevation")).joinToString("|")
            if (text.replace("|", "").isBlank()) return null
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
        }

        private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        /** Ответ history_during_period (сжатый формат: s — состояние, lc/lu — секунды). */
        internal fun parseHistory(result: Any?, entityId: String, unit: String): List<PowerSample> {
            val array = (result as? JSONObject)?.optJSONArray(entityId) ?: return emptyList()
            val out = ArrayList<PowerSample>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val seconds = when {
                    item.has("lc") -> item.optDouble("lc")
                    item.has("lu") -> item.optDouble("lu")
                    else -> Double.NaN
                }
                if (!seconds.isFinite()) continue
                out += PowerSample((seconds * 1000).toLong(), PowerHistory.toWatts(item.optString("s"), unit))
            }
            return out.sortedBy { it.ts }.distinctBy { it.ts }
        }
    }
}
