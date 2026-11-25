package com.example.smarthomedashboard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.smarthomedashboard.data.TileEntity
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Запись с камер, этап 2 (docs/recording.md): события в HD. Основной поток камеры держится открытым постоянно,
 * последние секунды лежат в памяти ([PreRollBuffer]); событие из HA ([RecordingEvents]) открывает ролик с
 * предзаписью, после того как все датчики погасли, ролик дописывается ещё [RecordingConfig.postSeconds]
 * ([EventPlanner]). Новое событие во время записи продлевает тот же ролик; длиннее
 * [RecordingConfig.maxClipMinutes] — следующий файл того же события. Метки событий — в [RecIndex].
 */

/**
 * Какие события сейчас идут — по каждой плитке домофона. Два источника, срабатывает любой:
 * - датчики HA (пишет панель, главный поток, из [DoorbellHub.onState]);
 * - события напрямую с камеры (вебхук, docs/direct-events-tz.md, этап 2) — [direct]: камера сообщает только начало
 *   события, поэтому оно считается идущим [DIRECT_HOLD_MS], дальше — обычная дозапись.
 * Читают потоки записи. Нет ни HA, ни свежего события с камеры — «неизвестно»: новое событие не начинается,
 * идущее дописывается и закрывается по дозаписи.
 */
internal object RecordingEvents {
    /** [labels] — ключи сработавших датчиков ([RecordingConfig.TRIGGERS]); null — состояние HA неизвестно. */
    private val active = ConcurrentHashMap<String, Set<String>>()
    private val known = ConcurrentHashMap.newKeySet<String>()
    /** Плитка → ключ события → до какого времени (стена, мс) оно считается идущим. */
    private val directUntil = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    const val DIRECT_HOLD_MS = 10_000L
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * Сеанс у калитки: окно звонка открыто или идёт разговор. Пока он держится, ролик события пишется дальше, после —
     * обычная дозапись. Раньше ролик кончался через дозапись после последнего срабатывания датчика: звонок 23.11 в 13:20
     * — ролик до 13:20:47, а разговор шёл до 13:21:05, и его конец в архив не попал.
     * Плитка → кто держит (окно, разговор) → (метка, с какого времени). Держится не дольше [SESSION_MAX_MS] —
     * на случай, если окно закрылось, не сообщив.
     */
    private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<Any, Pair<String, Long>>>()
    const val SESSION_MAX_MS = 11 * 60_000L

    /**
     * [on] — сеанс начался или кончился у [owner]. Метка ролика — «Звонок», если звонок пишется в HD, иначе первое
     * событие, которое пишется; никакое не пишется — ролик не продлевается.
     */
    fun session(tileId: String?, owner: Any, on: Boolean) {
        if (tileId.isNullOrEmpty()) return
        if (!on) { sessions[tileId]?.remove(owner); return }
        val triggers = DoorbellHub.storedTile(tileId)?.let { RecordingConfig.from(it.config).eventTriggers }
            ?: RecordingConfig.DEFAULT_TRIGGERS
        val label = if ("visitor" in triggers) "visitor" else RecordingConfig.TRIGGERS.map { it.first }.firstOrNull { it in triggers } ?: return
        sessions.getOrPut(tileId) { ConcurrentHashMap() }[owner] = label to clock()
    }

    fun of(tileId: String): Set<String>? {
        val now = clock()
        val direct = directUntil[tileId]?.filterValues { it > now }?.keys.orEmpty() +
            sessions[tileId]?.values?.filter { now - it.second < SESSION_MAX_MS }?.map { it.first }.orEmpty()
        if (tileId !in known) return direct.takeIf { it.isNotEmpty() }
        val ha = active[tileId].orEmpty()
        return if (direct.isEmpty()) ha else ha + direct
    }

    /**
     * Событие напрямую с камеры. [triggers] — какие события этой плитки запускают HD-запись (настройка записи);
     * остальные не учитываются.
     */
    fun direct(tileId: String, keys: Collection<String>, triggers: Set<String>) {
        val until = clock() + DIRECT_HOLD_MS
        val map = directUntil.getOrPut(tileId) { ConcurrentHashMap() }
        keys.filter { it in triggers }.forEach { map[it] = until }
    }

    fun update(tiles: List<TileEntity>, readings: BatteryReadings) {
        val ids = HashSet<String>()
        for (tile in tiles) {
            val source = CameraSource.of(tile) ?: continue
            ids += tile.id
            val labels = labels(source, RecordingConfig.from(tile.config), readings)
            if (labels == null) { known -= tile.id; active.remove(tile.id) } else { active[tile.id] = labels; known += tile.id }
        }
        known.retainAll(ids)
        active.keys.retainAll(ids)
    }

    fun clear() { known.clear(); active.clear(); directUntil.clear(); sessions.clear() }

    /** Сработавшие датчики, запускающие HD-запись; null — связи с HA нет или идёт синхронизация. */
    fun labels(source: CameraSource, rec: RecordingConfig, readings: BatteryReadings): Set<String>? {
        if (readings.status != null) return null
        val out = LinkedHashSet<String>()
        for ((key, entity) in source.events) {
            if (key !in rec.eventTriggers || entity.isBlank()) continue
            if ((readings.of(entity) as? BatteryReading.Value)?.raw == "on") out += key
        }
        return out
    }
}

/**
 * Когда писать ролик события. Чистая логика: время — стена (мс), [update] вызывается на каждом кадре видео.
 * Событие начинается, когда сработал хоть один датчик; заканчивается через [postMs] после того, как погасли все
 * (или стало неизвестно). Новое срабатывание во время дозаписи продлевает тот же ролик.
 */
internal class EventPlanner(private val postMs: Long) {
    enum class Decision { IDLE, START, CONTINUE, STOP }

    var recording = false
        private set
    private var lastActiveMs = 0L
    /** Все метки, сработавшие с начала ролика (или с последнего [takeLabels]). */
    private val labels = LinkedHashSet<String>()

    fun update(nowMs: Long, current: Set<String>?): Decision {
        val on = !current.isNullOrEmpty()
        if (!current.isNullOrEmpty()) { labels += current; lastActiveMs = nowMs }
        if (!recording) {
            if (!on) return Decision.IDLE
            recording = true
            return Decision.START
        }
        if (on || nowMs - lastActiveMs < postMs) return Decision.CONTINUE
        recording = false
        return Decision.STOP
    }

    /** Метки для закрываемого файла; если событие идёт дальше (новый файл), в нём остаются сработавшие сейчас. */
    fun takeLabels(current: Set<String>?): Set<String> {
        val out = labels.toSet()
        labels.clear()
        if (recording && current != null) labels += current
        return out
    }
}

/**
 * Последние секунды потока в памяти — целыми группами кадров (от ключевого кадра), чтобы ролик начинался
 * с ключевого. Хранится от последнего ключевого кадра не позже [preUs] до текущего момента; память ограничена
 * [maxBytes] (старшие группы отбрасываются, текущая остаётся). Жёсткий предел [hardBytes] — и для текущей группы
 * (камера долго не шлёт ключевых кадров, видео встало, а звук идёт): буфер очищается и начинается заново со
 * следующего ключевого кадра — предзаписи у такого события меньше, зато память не растёт (аудит 20.11.2026, A1).
 */
internal class PreRollBuffer(private val preUs: Long, private val maxBytes: Long = MAX_BYTES, private val hardBytes: Long = HARD_BYTES) {
    sealed class Item(val us: Long) {
        class Video(val frame: VideoFrame, us: Long, val params: VideoParams?) : Item(us)
        class Audio(val frame: AudioFrame, us: Long) : Item(us)
    }

    private val items = ArrayDeque<Item>()
    var bytes = 0L
        private set

    val size get() = items.size
    fun snapshot(): List<Item> = items.toList()

    /** Ключевой кадр несёт параметры потока ([params]); без ключевого кадра буфер не начинается. */
    fun addVideo(frame: VideoFrame, us: Long, params: VideoParams?) {
        if (items.none { it is Item.Video } && !frame.key) return
        items.addLast(Item.Video(frame, us, params))
        bytes += frame.nals.sumOf { it.size + 4 }
        if (frame.key) trim(us)
        if (bytes > hardBytes) overflow()
    }

    fun addAudio(frame: AudioFrame, us: Long) {
        if (items.none { it is Item.Video }) return
        items.addLast(Item.Audio(frame, us))
        bytes += frame.data.size
        if (bytes > hardBytes) overflow()
    }

    fun clear() { items.clear(); bytes = 0 }

    /**
     * С какого кадра начать ролик события в момент [nowUs]: последний ключевой не позже now − pre (буфер обрезается
     * только на ключевых кадрах, поэтому в нём бывает на группу больше); если такого нет — самый ранний.
     */
    fun startFor(nowUs: Long): Item.Video? {
        val keys = items.filterIsInstance<Item.Video>().filter { it.frame.key && it.params != null }
        return keys.lastOrNull { it.us <= nowUs - preUs } ?: keys.firstOrNull()
    }

    /** Оставить группы, начиная с последнего ключевого кадра не позже now − pre; затем — предел памяти. */
    private fun trim(nowUs: Long) {
        val keys = items.withIndex().filter { (it.value as? Item.Video)?.frame?.key == true }
        if (keys.isEmpty()) return
        val limit = nowUs - preUs
        var start = keys.last { it.value.us <= limit || it == keys.first() }.index
        // Предел памяти: отбрасываем старшие группы, пока не уложимся (последняя группа остаётся всегда).
        var i = keys.indexOfFirst { it.index == start }
        while (bytesFrom(start) > maxBytes && i < keys.lastIndex) start = keys[++i].index
        repeat(start) { removeFirst() }
    }

    /** Сверх жёсткого предела: сначала старшие группы, не помогло — всё (ждём следующий ключевой кадр). */
    private fun overflow() {
        var rest = bytes
        var start = -1
        for ((i, item) in items.withIndex()) {
            if (i > 0 && (item as? Item.Video)?.frame?.key == true && rest <= hardBytes) { start = i; break }
            rest -= size(item)
        }
        if (start > 0) repeat(start) { removeFirst() } else clear()
    }

    private fun bytesFrom(index: Int): Long {
        var total = 0L
        items.forEachIndexed { i, item -> if (i >= index) total += size(item) }
        return total
    }

    private fun removeFirst() { bytes -= size(items.removeFirst()) }

    private fun size(item: Item) = when (item) {
        is Item.Video -> item.frame.nals.sumOf { it.size + 4 }.toLong()
        is Item.Audio -> item.frame.data.size.toLong()
    }

    companion object {
        /** ~10 с основного потока 2K (2–4 Мбит/с) — 3–5 МБ; запас на высокий битрейт. */
        const val MAX_BYTES = 24L * 1024 * 1024
        /** Сверх него не держится и текущая группа кадров. */
        const val HARD_BYTES = MAX_BYTES + 8L * 1024 * 1024
    }
}

/**
 * Запись событий в HD: основной поток камеры открыт постоянно, ролики — по событиям [events] этой плитки.
 * [onClip] — закрытый файл ролика и метки сработавших за него событий.
 */
internal class EventRecorder(
    camera: String,
    url: String,
    user: String,
    password: String?,
    audio: Boolean,
    private val root: () -> File?,
    private val canWrite: () -> Boolean,
    private val events: () -> Set<String>?,
    private val settings: () -> RecordingConfig,
    private val onClip: (RecFile, Set<String>) -> Unit,
    onStatus: (CameraRecorder, Status) -> Unit,
    sessionFactory: (String, String, String?) -> RtspSession = { u, l, p -> RtspSession(u, l, p) },
    encoderFactory: () -> AacEncoder = { AacEncoder() }
) : CameraRecorder(camera, url, user, password, audio, onStatus, sessionFactory, encoderFactory, "rec-hd-$camera") {

    override fun newSink(): Sink = ClipSink()

    /** Ролики одной RTSP-сессии. Отдельно — для тестов без сети. */
    inner class ClipSink : Sink {
        override var audioParams: AudioParams? = null
        private val timeline = Timeline { wallClock() }
        override var videoClock: Int
            get() = timeline.videoClock
            set(v) { timeline.videoClock = v }
        private val config = settings()
        private val buffer = PreRollBuffer(config.preSeconds * 1_000_000L)
        private val planner = EventPlanner(config.postSeconds * 1_000L)
        private var params: VideoParams? = null
        private var writer: Mp4SegmentWriter? = null
        private var file: File? = null
        /** Носитель идущего ролика и когда можно пробовать снова после его ошибки. */
        private var fileRoot: File? = null
        private var retryAtNs = 0L
        private var clipStartUs = 0L
        private var clipStartWall = 0L
        private var lastUs = 0L
        /** Ролик не открылся (нет места) — до конца этого события не пытаемся снова на каждом кадре. */
        private var failed = false

        override fun video(frame: VideoFrame, d: VideoDepacketizer) {
            val us = timeline.videoUs(frame)
            if (frame.key) timeline.sync(us)
            if (frame.key) d.sps?.let { sps -> d.pps?.let { pps -> params = VideoParams(d.codec, d.vps, sps, pps) } }
            buffer.addVideo(frame, us, if (frame.key) params else null)
            // Сессия идёт — «ждёт события»; «нет места» остаётся до следующего удачного ролика.
            if (status.state == State.CONNECTING || status.state == State.RETRYING) report(State.RECORDING, IDLE)
            val current = events()
            // Дозапись — по шкале потока, а не по часам: они могут поправиться посреди события.
            when (planner.update(us / 1_000, current)) {
                EventPlanner.Decision.IDLE -> Unit
                EventPlanner.Decision.START -> { failed = false; stored(current) { openFromBuffer(us, current) } }
                EventPlanner.Decision.CONTINUE -> stored(current) { write(frame, us, current) }
                EventPlanner.Decision.STOP -> { stored(current) { write(frame, us, current) }; closeClip(current); report(State.RECORDING, IDLE) }
            }
        }

        /** Носитель сменился (карту извлекают, она перестала писать) — ролик продолжается на другом. */
        private fun moved() = fileRoot?.let { it.path != root()?.path } == true

        private inline fun stored(current: Set<String>?, block: () -> Unit) {
            try { block() } catch (e: Exception) { if (storageError(e)) storageFailed(e, current) else throw e }
        }

        private fun storageFailed(e: Exception, current: Set<String>?) {
            Log.w(TAG, "Запись события: носитель не пишет (${e.javaClass.simpleName}: ${e.message})")
            val broken = fileRoot
            closeClip(current)
            retryAtNs = System.nanoTime() + storageRetryNs
            report(State.RETRYING, ContinuousRecorder.STORAGE_RETRYING)
            broken?.let(onStorageError)
        }

        override fun audioArrival(ts: Long) = timeline.audioArrival(ts)

        override fun audio(frame: AudioFrame, rate: Int) {
            val us = timeline.audioUs(frame, rate) ?: return
            buffer.addAudio(frame, us)
            val w = writer ?: return
            stored(null) { w.writeAudio(frame, us - clipStartUs) }
        }

        /** Начало события: ролик с первого ключевого кадра буфера (предзапись), затем весь буфер по порядку. */
        private fun openFromBuffer(nowUs: Long, current: Set<String>?) {
            val items = buffer.snapshot()
            val first = buffer.startFor(nowUs) ?: return
            if (!open(first.params!!, first.us)) return
            for (item in items.dropWhile { it !== first }) {
                when (item) {
                    is PreRollBuffer.Item.Video -> write(item.frame, item.us, current, item.params)
                    is PreRollBuffer.Item.Audio -> writer?.writeAudio(item.frame, item.us - clipStartUs)
                }
            }
        }

        private fun write(frame: VideoFrame, us: Long, current: Set<String>?, keyParams: VideoParams? = if (frame.key) params else null) {
            var w = writer
            if (w == null) {
                // Событие идёт, а ролика нет (не было ключевого кадра или места) — пробуем с ближайшего ключевого.
                if (!frame.key || failed || keyParams == null || !open(keyParams, us)) return
                w = writer ?: return
            } else if (frame.key && keyParams != null &&
                (us - clipStartUs >= config.maxClipMinutes * 60_000_000L || !keyParams.sameAs(w.video) || moved())) {
                // Предел ролика, смена параметров потока (ночной режим) или носителя: следующий файл того же события.
                closeClip(current)
                if (!open(keyParams, us)) return
                w = writer ?: return
            }
            w.writeVideo(frame, us - clipStartUs)
            lastUs = us
        }

        private fun open(params: VideoParams, us: Long): Boolean {
            val started = System.nanoTime()
            if (started < retryAtNs && root()?.path == fileRoot?.path) return false
            try { return openFile(params, us) } finally { RecBusy.open(System.nanoTime() - started) }
        }

        private fun openFile(params: VideoParams, us: Long): Boolean {
            val base = root()
            if (base == null) { failed = true; report(State.NO_STORAGE, "Нет места для записи"); return false }
            if (!canWrite()) { failed = true; report(State.NO_SPACE, "Запись остановлена: нет места"); return false }
            val dir = RecLayout.dayDir(base, camera, timeline.wallMs(us))
            fileRoot = base
            if (!dir.isDirectory && !dir.mkdirs()) {
                report(State.NO_STORAGE, "Папка записи недоступна")
                retryAtNs = System.nanoTime() + storageRetryNs
                onStorageError(base)
                return false
            }
            val start = RecLayout.freeStart(dir, RecFile.Kind.EVENT, timeline.wallMs(us))
            val f = File(dir, RecLayout.openName(RecFile.Kind.EVENT, start))
            writer = Mp4SegmentWriter(f, params, audioParams, queued = true)
            file = f
            clipStartUs = us
            clipStartWall = start
            lastUs = us
            report(State.RECORDING, WRITING)
            return true
        }

        private fun closeClip(current: Set<String>?) {
            val w = writer ?: run { planner.takeLabels(current); return }
            val f = file
            writer = null
            file = null
            val labels = planner.takeLabels(current)
            // Дописать, обрезать и переименовать — в потоке записи [RecIo]; метки события — уже сейчас.
            RecLayout.finishLater(w, f, camera, RecFile.Kind.EVENT, clipStartWall, (lastUs - clipStartUs) / 1_000) { onClip(it, labels) }
        }

        /** Конец сессии (обрыв, остановка): идущий ролик закрывается — событие продолжится новым после переподключения. */
        override fun close() {
            closeClip(null)
            buffer.clear()
        }
    }

    companion object {
        /** Подробности состояния HD-сессии для редактора. */
        const val IDLE = "ждёт события"
        const val WRITING = "пишет событие"
    }
}

/**
 * Индекс роликов событий: метки сработавших датчиков (этап 2) и, дальше, превью и прочее для архива. Файлы —
 * источник правды: строка без файла удаляется при сверке, файл без строки показывается без меток.
 * Только фоновый поток.
 */
internal class RecIndex(context: Context, name: String? = DB_NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {

    data class Event(val camera: String, val file: String, val startMs: Long, val durationMs: Long, val labels: Set<String>)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE event (camera TEXT NOT NULL, file TEXT NOT NULL, start INTEGER NOT NULL, " +
            "duration INTEGER NOT NULL, labels TEXT NOT NULL, PRIMARY KEY(camera, file)) WITHOUT ROWID")
        db.execSQL("CREATE INDEX event_start ON event(camera, start)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(file: RecFile, labels: Set<String>) {
        writableDatabase.insertWithOnConflict("event", null, ContentValues().apply {
            put("camera", file.camera)
            put("file", file.path.name)
            put("start", file.startMs)
            put("duration", file.durationMs)
            put("labels", labels.sorted().joinToString(","))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Метки по имени файла (звёздочка меняет имя — ищем по началу ролика). */
    fun labels(camera: String, startMs: Long): Set<String> =
        readableDatabase.rawQuery("SELECT labels FROM event WHERE camera = ? AND start = ?", arrayOf(camera, startMs.toString())).use {
            if (it.moveToFirst()) it.getString(0).split(',').filter(String::isNotBlank).toSet() else emptySet()
        }

    fun events(camera: String, fromMs: Long = 0, toMs: Long = Long.MAX_VALUE): List<Event> =
        readableDatabase.rawQuery("SELECT camera, file, start, duration, labels FROM event WHERE camera = ? AND start >= ? AND start < ? " +
            "ORDER BY start DESC", arrayOf(camera, fromMs.toString(), toMs.toString())).use {
            val out = ArrayList<Event>()
            while (it.moveToNext()) out += Event(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3),
                it.getString(4).split(',').filter(String::isNotBlank).toSet())
            out
        }

    /** Сверка с диском: строки удалённых роликов убираются. [files] — все события на диске. */
    fun reconcile(files: List<RecFile>) {
        val present = files.filter { it.kind == RecFile.Kind.EVENT }.mapTo(HashSet()) { it.camera to it.startMs }
        val db = writableDatabase
        val gone = ArrayList<Pair<String, Long>>()
        db.rawQuery("SELECT camera, start FROM event", null).use { while (it.moveToNext()) {
            val key = it.getString(0) to it.getLong(1)
            if (key !in present) gone += key
        } }
        if (gone.isEmpty()) return
        db.beginTransaction()
        try {
            for ((camera, start) in gone) db.delete("event", "camera = ? AND start = ?", arrayOf(camera, start.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun remove(files: Collection<RecFile>) {
        val events = files.filter { it.kind == RecFile.Kind.EVENT }
        if (events.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (f in events) db.delete("event", "camera = ? AND start = ?", arrayOf(f.camera, f.startMs.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    companion object {
        const val DB_NAME = "recording_index.db"
        const val VERSION = 1
    }
}
