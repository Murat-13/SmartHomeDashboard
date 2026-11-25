package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Журнал звонков со снимками посетителя (docs/doorbell.md, «Звонки»): время каждого звонка и кадр с камеры в момент
 * звонка. Снимок берётся с уже идущего видео плитки, затем заменяется кадром получше из окна звонка (основной
 * поток). Последние [KEEP] звонков на камеру; файлы — `files/doorbell_rings/<камера>/`, в облако Google не уходят
 * (лица посетителей). Работа с диском — фоновый поток.
 */
internal object RingLog {
    /** [answered] — в окне звонка ответили (касание, разговор, калитка); иначе звонок пропущен. */
    data class Ring(val atMs: Long, val photo: File?, val answered: Boolean = false)

    const val KEEP = 50
    private const val DIR = "doorbell_rings"
    private const val LIST = "rings.json"
    private const val MAX_WIDTH = 1280
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "ring-log").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun dir(context: Context, tileId: String) = File(File(context.filesDir, DIR), RecLayout.cameraKey(tileId))

    /** Звонки, новые первыми. Блокирующее. */
    fun list(context: Context, tileId: String): List<Ring> {
        val dir = dir(context, tileId)
        return read(dir).sortedByDescending { it.first }.map { (t, a) -> Ring(t, File(dir, "$t.jpg").takeIf { it.isFile }, a) }
    }

    /** Новый звонок (без снимка). Блокирующее. */
    fun add(context: Context, tileId: String, atMs: Long) = synchronized(this) {
        val dir = dir(context, tileId).apply { mkdirs() }
        val all = (read(dir).filter { it.first != atMs } + (atMs to false)).sortedBy { it.first }
        val keep = all.takeLast(KEEP)
        (all - keep.toSet()).forEach { File(dir, "${it.first}.jpg").delete() }
        write(dir, keep)
    }

    /** На звонок ответили (окно звонка). Фоновый поток. */
    fun markAnswered(context: Context, tileId: String, atMs: Long) {
        val app = context.applicationContext
        io.execute {
            synchronized(this) {
                val dir = dir(app, tileId)
                val all = read(dir)
                if (all.none { it.first == atMs && !it.second }) return@execute
                write(dir, all.map { if (it.first == atMs) atMs to true else it })
            }
            DoorbellHub.notifyRings()
        }
    }

    /**
     * Снимок звонка [atMs]: [better] — кадр лучше прежнего (основной поток в окне звонка) заменяет снимок; иначе
     * сохраняется, только если снимка ещё нет. Блокирующее.
     */
    fun savePhoto(context: Context, tileId: String, atMs: Long, frame: Bitmap, better: Boolean): Boolean = synchronized(this) {
        val dir = dir(context, tileId)
        if (read(dir).none { it.first == atMs }) return false
        val file = File(dir, "$atMs.jpg")
        if (file.isFile && !better) return false
        val scaled = if (frame.width > MAX_WIDTH) Bitmap.createScaledBitmap(frame, MAX_WIDTH, frame.height * MAX_WIDTH / frame.width, true) else frame
        val tmp = File(dir, "$atMs.jpg.tmp")
        return try {
            tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(file)
        } catch (e: Exception) {
            tmp.delete(); Log.w(TAG, "Снимок звонка не сохранён: ${e.javaClass.simpleName}"); false
        }
    }

    fun photo(file: File?, width: Int): Bitmap? {
        if (file == null || !file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Звонок пришёл: записать его и снять кадр с [video] (если видео идёт) — сразу; ещё раз через [RETRY_MS], если
     * сразу кадра не было. Главный поток.
     */
    fun onRing(context: Context, tileId: String, atMs: Long, video: () -> DoorbellVideoView?) {
        val app = context.applicationContext
        io.execute { add(app, tileId, atMs) }
        fun attempt(left: Int) {
            val frame = video()?.currentFrame()
            if (frame != null) io.execute { savePhoto(app, tileId, atMs, frame, better = false); DoorbellHub.notifyRings() }
            else if (left > 0) main.postDelayed({ attempt(left - 1) }, RETRY_MS)
        }
        attempt(4)
    }

    /** Кадр из окна звонка (основной поток в HD) — лучше снимка с плитки. Главный поток. */
    fun improve(context: Context, tileId: String, atMs: Long, frame: Bitmap) {
        val app = context.applicationContext
        io.execute { savePhoto(app, tileId, atMs, frame, better = true); DoorbellHub.notifyRings() }
    }

    private fun read(dir: File): List<Pair<Long, Boolean>> = try {
        val array = JSONArray(File(dir, LIST).readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            o.optLong("t", 0L).takeIf { it > 0 }?.let { it to o.optBoolean("a", false) }
        }
    } catch (_: Exception) { emptyList() }

    private fun write(dir: File, rings: List<Pair<Long, Boolean>>) {
        dir.mkdirs()
        val array = JSONArray()
        rings.forEach { (t, a) -> array.put(JSONObject().put("t", t).put("a", a)) }
        val tmp = File(dir, "$LIST.tmp")
        // Нет места или сбой памяти планшета: запись журнала теряется, но панель из-за неё не закрывается (аудит 25.11.2026, STAB-03).
        try {
            tmp.writeText(array.toString())
            tmp.renameTo(File(dir, LIST))
        } catch (e: java.io.IOException) {
            tmp.delete(); Log.w(TAG, "Журнал звонков не записан: ${e.javaClass.simpleName}")
        }
    }

    private const val RETRY_MS = 1_500L
    private const val TAG = "Doorbell"
}

/**
 * Быстрые фразы своим голосом (docs/doorbell.md): владелец записывает в редакторе «Оставьте у калитки», «Сейчас
 * выйду»…; в раскрытом домофоне и окне звонка фраза уходит в динамик домофона через обратный канал. Хранятся только
 * на этом планшете, рядом с голосом: `files/doorbell_phrases/<камера>/phrases.json` и `<id>.pcm` (8 кГц, 16 бит,
 * моно). Настройки плитки не меняются; в облако Google и в файл настроек фразы не уходят.
 */
internal object DoorbellPhrases {
    data class Phrase(val id: String, val title: String)

    const val MAX = 6
    const val MAX_SECONDS = 10
    private const val DIR = "doorbell_phrases"
    private const val LIST = "phrases.json"

    private fun dir(context: Context, tileId: String) = File(File(context.filesDir, DIR), RecLayout.cameraKey(tileId))

    /** Все фразы плитки (и без записанного голоса). Блокирующее, но файл крошечный. */
    fun list(context: Context, tileId: String): List<Phrase> = parse(try { File(dir(context, tileId), LIST).readText() } catch (_: Exception) { "" })

    fun parse(text: String): List<Phrase> = try {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.matches(ID) } ?: return@mapNotNull null
            Phrase(id, o.optString("title").trim().take(40).ifBlank { "Фраза" })
        }.distinctBy { it.id }.take(MAX)
    } catch (_: Exception) { emptyList() }

    fun setList(context: Context, tileId: String, phrases: List<Phrase>): Boolean {
        val dir = dir(context, tileId).apply { mkdirs() }
        val array = JSONArray()
        phrases.take(MAX).forEach { array.put(JSONObject().put("id", it.id).put("title", it.title.trim().take(40))) }
        val tmp = File(dir, "$LIST.tmp")
        return try { tmp.writeText(array.toString()); tmp.renameTo(File(dir, LIST)) } catch (_: Exception) { tmp.delete(); false }
    }

    fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    fun file(context: Context, tileId: String, id: String) = File(dir(context, tileId), "$id.pcm")

    fun has(context: Context, tileId: String, id: String) = file(context, tileId, id).let { it.isFile && it.length() >= 1_600 }

    /** Фразы с записанным голосом. */
    fun ready(context: Context, tileId: String?): List<Phrase> =
        if (tileId == null) emptyList() else list(context, tileId).filter { has(context, tileId, it.id) }

    fun save(context: Context, tileId: String, id: String, pcm: ShortArray, count: Int): Boolean {
        val file = file(context, tileId, id)
        file.parentFile?.mkdirs()
        val bytes = java.nio.ByteBuffer.allocate(count * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(pcm[i])
        val tmp = File(file.path + ".tmp")
        return try { tmp.writeBytes(bytes.array()); tmp.renameTo(file) } catch (_: Exception) { tmp.delete(); false }
    }

    fun load(context: Context, tileId: String, id: String): ShortArray? = try {
        val bytes = file(context, tileId, id).readBytes()
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        ShortArray(bytes.size / 2) { buffer.getShort() }
    } catch (_: Exception) { null }

    fun delete(context: Context, tileId: String, id: String) { file(context, tileId, id).delete() }

    /** Тихие края записи (нажатие и отпускание кнопки) убираются; громкость выравнивается до ~70 % без перегруза. */
    fun trimAndNormalize(pcm: ShortArray, count: Int): ShortArray {
        if (count <= 0) return ShortArray(0)
        val frame = 160
        fun loud(from: Int) = (from until minOf(count, from + frame)).maxOfOrNull { kotlin.math.abs(pcm[it].toInt()) } ?: 0
        val threshold = 600
        var start = 0
        while (start < count && loud(start) < threshold) start += frame
        var end = count
        while (end - frame > start && loud(end - frame) < threshold) end -= frame
        if (start >= end) return ShortArray(0)
        start = (start - frame * 5).coerceAtLeast(0) // 0,1 с запаса до и после голоса
        end = (end + frame * 5).coerceAtMost(count)
        val peak = (start until end).maxOf { kotlin.math.abs(pcm[it].toInt()) }.coerceAtLeast(1)
        val gain = minOf(4f, 23_000f / peak)
        return ShortArray(end - start) { (pcm[start + it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private val ID = Regex("^[a-z0-9]{4,32}$")
}
