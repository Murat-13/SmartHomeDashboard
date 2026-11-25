package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Журнал открытий (docs/gate-tz.md, решение 7): кто, когда и чем открыл или закрыл проход, итог и снимок с камеры
 * прохода. Хранится на этом планшете (`files/gate_log/`, не в облачной копии — там лица), последние [KEEP] записей.
 * В архиве — вкладка «Открытия»; касание — видео этого момента.
 */
internal object GateLog {
    /** Откуда действие. [OUTSIDE] — без нашей команды: пульт, ключ, автоматика Home Assistant, другая панель. */
    enum class Origin(val key: String, val title: String) {
        PANEL("panel", "с этого планшета"), PHONE("phone", "с телефона"), AUTO("auto", "закрыто само"),
        OUTSIDE("outside", "без команды с панели");
        companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: OUTSIDE }
    }

    data class Entry(val atMs: Long, val gateId: String, val gateName: String, val action: Gate.Action, val origin: Origin,
                     val result: String, val photo: File? = null) {
        val key get() = "${atMs}_$gateId"
    }

    const val KEEP = 200
    private const val DIR = "gate_log"
    private const val LIST = "log.json"
    private const val MAX_WIDTH = 1280
    private val io = Executors.newSingleThreadExecutor { Thread(it, "gate-log").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun listen(listener: () -> Unit) { listeners += listener }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
    private fun changed() = main.post { listeners.forEach { it() } }

    fun dir(context: Context) = File(context.filesDir, DIR)

    /** Записи, новые первыми; [gateIds] — только эти проходы (вкладка у камеры). Блокирующее. */
    fun list(context: Context, gateIds: Collection<String>? = null): List<Entry> = synchronized(this) {
        val dir = dir(context)
        // В одну миллисекунду — позже записанная выше.
        read(dir).filter { gateIds == null || it.gateId in gateIds }.reversed().sortedByDescending { it.atMs }
            .map { e -> e.copy(photo = File(dir, "${e.key}.jpg").takeIf { it.isFile }) }
    }

    /**
     * Новая запись (главный поток) и снимок с видео плитки, где проход показан — сразу или через секунду-другую.
     * [video] — видео плитки домофона или камеры этого прохода; null — без снимка.
     */
    fun add(context: Context, entry: Entry, video: () -> DoorbellVideoView? = { null }) {
        val app = context.applicationContext
        io.execute { synchronized(this) { write(dir(app), (read(dir(app)) + entry).sortedBy { it.atMs }.let(::trim)) }; changed() }
        fun attempt(left: Int) {
            val frame = runCatching { video()?.currentFrame() }.getOrNull()
            if (frame != null) io.execute { savePhoto(app, entry, frame); changed() }
            else if (left > 0) main.postDelayed({ attempt(left - 1) }, RETRY_MS)
        }
        attempt(2)
    }

    private fun trim(all: List<Entry>): List<Entry> = all.takeLast(KEEP)

    private fun savePhoto(context: Context, entry: Entry, frame: Bitmap) = synchronized(this) {
        val dir = dir(context)
        val scaled = if (frame.width > MAX_WIDTH) Bitmap.createScaledBitmap(frame, MAX_WIDTH, frame.height * MAX_WIDTH / frame.width, true) else frame
        val tmp = File(dir, "${entry.key}.jpg.tmp")
        try {
            tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(File(dir, "${entry.key}.jpg"))
        } catch (e: Exception) {
            tmp.delete(); Log.w(TAG, "Снимок прохода не сохранён: ${e.javaClass.simpleName}")
        }
    }

    private fun read(dir: File): List<Entry> = try {
        val array = JSONArray(File(dir, LIST).readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optLong("t", 0L).takeIf { it > 0 } ?: return@mapNotNull null
            val action = Gate.Action.entries.firstOrNull { it.name == o.optString("a") } ?: return@mapNotNull null
            Entry(t, o.optString("g"), o.optString("n"), action, Origin.of(o.optString("o")), o.optString("r"))
        }
    } catch (_: Exception) { emptyList() }

    private fun write(dir: File, entries: List<Entry>) {
        dir.mkdirs()
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().put("t", e.atMs).put("g", e.gateId).put("n", e.gateName).put("a", e.action.name)
                .put("o", e.origin.key).put("r", e.result))
        }
        val keep = entries.mapTo(HashSet()) { "${it.key}.jpg" }
        dir.listFiles()?.filter { it.name.endsWith(".jpg") && it.name !in keep }?.forEach { it.delete() }
        val tmp = File(dir, "$LIST.tmp")
        // Нет места или сбой памяти планшета: запись журнала теряется, но панель из-за неё не закрывается (аудит 25.11.2026, STAB-03).
        try {
            tmp.writeText(array.toString())
            tmp.renameTo(File(dir, LIST))
        } catch (e: java.io.IOException) {
            tmp.delete(); Log.w(TAG, "Журнал открытий не записан: ${e.javaClass.simpleName}")
        }
    }

    /** Подпись действия в журнале. */
    fun actionText(gate: Gate.Action) = when (gate) {
        Gate.Action.OPEN -> "открыть"
        Gate.Action.CLOSE -> "закрыть"
        Gate.Action.STOP -> "стоп"
        Gate.Action.PEDESTRIAN -> "пешеходный"
        Gate.Action.STEP -> "шаг"
    }

    /** Для тестов: дождаться фоновой записи. */
    internal fun flush() { io.submit {}.get() }

    private const val RETRY_MS = 1_500L
    private const val TAG = "Gates"
}
