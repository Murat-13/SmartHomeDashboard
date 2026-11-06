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
    /** Общая база процесса: её же читает и пополняет отдельный бэкап истории. */
    internal val store: PowerHistoryStore,
    worker: Executor = Executors.newSingleThreadExecutor { Thread(it, "power-history").apply { priority = Thread.MIN_PRIORITY } },
    /** Свой планировщик у каждого регистратора (а не общий статический): остановка снимает всё запланированное. */
    private val scheduler: Handler = Handler(Looper.getMainLooper()),
    post: ((() -> Unit) -> Unit)? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val post: (() -> Unit) -> Unit = post ?: { block -> scheduler.post(block) }
    /** Фоновая работа с базой никогда не роняет приложение: ошибка записи — лишь пропущенные точки. */
    private val io = Executor { task -> runCatching { worker.execute { runCatching(task::run) } } }
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
    /**
     * После перехода соединения на другой адрес сервер ещё не подтверждён (`get_config`): если адреса ведут к
     * разным HA, показания нового нельзя записывать под ключом прежнего. До [setInstance] записи ждут в памяти.
     */
    private var identityPending = false

    fun addListener(listener: Listener) { listeners += listener }
    fun removeListener(listener: Listener) { listeners -= listener }

    /** Последнее записанное значение источника в этой сессии («Сейчас» графика). */
    fun latest(entityId: String): Double? = last[entityId]

    fun isSource(entityId: String) = entityId in sources

    /** Сервер HA определён: закрываем прошлые сессии (остановка приложения) и начинаем запись. */
    fun setInstance(key: String) {
        identityPending = false
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
            scheduler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun startHeartbeat() {
        scheduler.removeCallbacks(heartbeat)
        scheduler.post(heartbeat)
    }

    /** Остановить всё запланированное (тесты, замена регистратора). Данные не теряются: накопленное записывается. */
    fun stop() {
        importSession++
        importQueue.clear()
        flush()
        connected = false
        scheduler.removeCallbacksAndMessages(null)
        flushScheduled = false
        listeners.clear()
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

    /**
     * Соединение без разрыва перешло на другой адрес того же HA: запись продолжается без разрыва графика,
     * а прерванная подгрузка истории начнётся заново после нового снимка состояний.
     */
    fun onEndpointSwitched() {
        importSession++
        importQueue.clear()
        importRequested = false
        snapshotDone = false
        identityPending = true
    }

    fun onDisconnected() {
        // identityPending сохраняется: неподтверждённые записи ждут ответа get_config и после переподключения.
        importSession++
        importQueue.clear()
        if (connected) breakAll()
        connected = false
        scheduler.removeCallbacks(heartbeat)
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
        scheduler.postDelayed({ flushScheduled = false; flush() }, FLUSH_MS)
    }

    /** Пишет накопленное; до определения сервера HA данные ждут в памяти. */
    fun flush() {
        if (identityPending) return
        val key = instance ?: return
        val batch = ArrayList(pending)
        pending.clear()
        val alive = if (connected) sources.toList() else emptyList()
        if (batch.isEmpty() && alive.isEmpty()) return
        val now = clock()
        io.execute {
            // Вставка идемпотентна (INSERT OR IGNORE по времени) и транзакционна: при сбое SQLite пачка
            // возвращается в очередь и уходит со следующей записью — без отдельного цикла повторов.
            val written = runCatching {
                batch.groupBy({ it.first }, { it.second }).forEach { (id, samples) ->
                    store.sourceId(key, id)?.let { store.insert(it, samples) }
                }
            }
            if (written.isFailure && batch.isNotEmpty()) post { requeue(batch, key) }
            runCatching { alive.forEach { id -> store.sourceId(key, id)?.let { store.setAlive(it, now) } } }
        }
    }

    /** Неудачная пачка — в начало очереди (старше новых записей), с тем же пределом памяти [MAX_PENDING]. */
    private fun requeue(batch: List<Pair<String, PowerSample>>, key: String) {
        // Сервер сменился — старые записи под новым ключом недопустимы.
        if (instance != key) return
        pending.addAll(0, batch)
        if (pending.size > MAX_PENDING) pending.subList(0, pending.size - MAX_PENDING).clear()
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

    /** Данные обновились извне (восстановление из копии): открытые графики перечитывают базу. */
    fun reloadAll() {
        sources.forEach { id -> listeners.toList().forEach { it.changed(id, null) } }
    }

    fun compact() {
        val now = clock()
        io.execute { store.compact(now) }
    }

    // ---- Подгрузка пропусков из истории HA (только чтение) ----

    /** Кусок пропуска для одного запроса истории; [range] — полуинтервал first..last. */
    private class ImportChunk(val entityId: String, val source: Long, val range: LongRange, val unit: String)
    private val importQueue = ArrayDeque<ImportChunk>()
    /** Сессия подгрузки: разрыв, смена сервера и остановка отменяют ещё не отправленные куски. */
    private var importSession = 0

    /**
     * Пропуски за 35 дней запрашиваются по одному куску не длиннее [IMPORT_CHUNK_MS], новые раньше старых.
     * Разбор ответа и запись — в фоне. Запрошенный кусок отмечается «проверено» (кроме последних
     * [IMPORT_SETTLE_MS], которые HA мог ещё не записать) и больше не запрашивается, даже если в HA данных за него нет.
     */
    private fun maybeImport() {
        if (identityPending) return
        val key = instance ?: return
        val read = reader ?: return
        if (!snapshotDone || importRequested || !connected) return
        importRequested = true
        val session = ++importSession
        importQueue.clear()
        val ids = sources.filter { units[it] != null }
        val now = clock()
        val units = ids.associateWith { this.units.getValue(it) }
        io.execute {
            val plan = ids.flatMap { id ->
                val source = store.sourceId(key, id) ?: return@flatMap emptyList()
                store.gaps(source, now - PowerHistory.KEEP_MS, now - IMPORT_RECENT_MS)
                    .filter { it.last - it.first >= MIN_GAP_MS }
                    .flatMap { chunks(it, now) }
                    .map { ImportChunk(id, source, it, units.getValue(id)) }
            }.sortedByDescending { it.range.first }
            post {
                if (session != importSession || instance != key) return@post
                importQueue.addAll(plan)
                nextImport(read, key, session)
            }
        }
    }

    /** Режет пропуск по границе уплотнения (старше — сразу в минутные агрегаты) и на куски [IMPORT_CHUNK_MS]. */
    private fun chunks(gap: LongRange, now: Long): List<LongRange> {
        val cutoff = rawCutoff(now)
        val end = gap.last + 1
        val result = ArrayList<LongRange>()
        var start = gap.first
        while (start < end) {
            var stop = minOf(end, start + IMPORT_CHUNK_MS)
            if (start < cutoff && stop > cutoff) stop = cutoff
            result += start until stop
            start = stop
        }
        return result
    }

    private fun nextImport(read: (String, JSONObject, (Any?) -> Unit) -> Unit, key: String, session: Int) {
        if (session != importSession || instance != key || !connected) return
        val chunk = importQueue.removeFirstOrNull() ?: return
        val params = JSONObject()
            .put("start_time", iso(chunk.range.first))
            .put("end_time", iso(chunk.range.last + 1))
            .put("entity_ids", JSONArray().put(chunk.entityId))
            .put("minimal_response", true)
            .put("no_attributes", true)
            .put("significant_changes_only", false)
            .put("include_start_time_state", true)
        read(HISTORY, params) { result ->
            if (session != importSession || instance != key) return@read
            // Отказ, тайм-аут или разрыв: остальное — при следующем подключении (кусок не отмечен).
            if (result == null) { importQueue.clear(); return@read }
            val settled = clock() - IMPORT_SETTLE_MS
            io.execute {
                val samples = parseHistory(result, chunk.entityId, chunk.unit).filter { it.ts in chunk.range }
                val end = chunk.range.last + 1
                // Старше границы уплотнения — сразу минутные агрегаты без перезаписи имеющихся,
                // а не точные замеры, которые уплотнение затем слило бы поверх готовых минут.
                val added = if (end <= rawCutoff(clock())) store.insertMinutes(chunk.source, PowerHistoryStore.aggregate(samples, end))
                    else store.insert(chunk.source, samples, fillBreaks = true)
                // Отмечается всё, кроме последних минут: их HA мог ещё не записать — спросим при следующем подключении.
                store.markChecked(chunk.source, chunk.range.first, minOf(end, settled))
                post {
                    if (added > 0) listeners.toList().forEach { it.changed(chunk.entityId, null) }
                    nextImport(read, key, session)
                }
            }
        }
    }

    private fun rawCutoff(now: Long) = Math.floorDiv(now - PowerHistory.RAW_KEEP_MS, PowerHistory.MINUTE_MS) * PowerHistory.MINUTE_MS

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
        const val HISTORY = "history/history_during_period"
        const val FLUSH_MS = 2_000L
        const val HEARTBEAT_MS = 60_000L
        const val MAX_PENDING = 20_000
        const val MIN_GAP_MS = 30_000L
        /** Последние секунды не запрашиваем: данные о них уже идут живым потоком. */
        const val IMPORT_RECENT_MS = 5_000L
        /** Один запрос истории HA — не длиннее 6 часов: ответ умеренного размера, без всплесков памяти. */
        const val IMPORT_CHUNK_MS = 6 * PowerHistory.HOUR_MS
        /** Последние минуты HA мог ещё не записать: такие куски не отмечаются «проверено». */
        const val IMPORT_SETTLE_MS = 10 * PowerHistory.MINUTE_MS

        @Volatile private var shared: Pair<Context, PowerHistoryRecorder>? = null

        /** Один регистратор на приложение: переживает пересоздание экрана вместе с соединением HA. */
        fun get(context: Context): PowerHistoryRecorder = synchronized(this) {
            val app = context.applicationContext
            shared?.takeIf { it.first === app }?.second
                ?: PowerHistoryRecorder(PowerHistoryStore(app)).also { next ->
                    // Другое приложение (только в тестах): прежний регистратор останавливается.
                    shared?.second?.stop()
                    shared = app to next
                }
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
