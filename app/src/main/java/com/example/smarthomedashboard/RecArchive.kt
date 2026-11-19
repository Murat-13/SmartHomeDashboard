package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Архив записей (этап 3, docs/recording.md): что лежит на диске по одной камере, метки событий из индекса,
 * звёздочка, удаление, превью, файл для «Поделиться». Только фоновый поток (обход папок, файлы, декодер).
 * Файлы ищутся во всех хранилищах приложения (память планшета и SD-карта): после пропажи карты часть записей
 * могла остаться в памяти планшета.
 */
internal class RecArchive(private val context: Context, private val roots: () -> List<File> = { defaultRoots(context) },
                          private val index: () -> RecIndex? = { null }) {

    /** Ролик события для ленты: файл и сработавшие датчики. */
    data class Event(val file: RecFile, val labels: Set<String>) {
        val starred get() = file.starred
    }

    data class Snapshot(val continuous: List<RecFile>, val events: List<Event>) {
        /** Дни (локальное начало суток), где есть хоть что-то; новые первыми. */
        val days: List<Long> get() = (continuous.map { it.startMs } + events.map { it.file.startMs })
            .map(::dayStart).distinct().sortedDescending()
    }

    fun load(camera: String): Snapshot {
        val files = roots().flatMap { RecLayout.scan(it) }.filter { it.camera == camera && !it.open && it.durationMs > 0 }
        val labels = try { index()?.events(camera)?.associate { it.startMs to it.labels } } catch (_: Exception) { null }.orEmpty()
        return Snapshot(
            continuous = files.filter { it.kind == RecFile.Kind.CONTINUOUS }.sortedBy { it.startMs },
            events = files.filter { it.kind == RecFile.Kind.EVENT }.sortedByDescending { it.startMs }
                .map { Event(it, labels[it.startMs].orEmpty()) }
        )
    }

    /** Звёздочка «Сохранить» — в имени файла (`_s`): переживает потерю индекса, очистка такие не трогает. */
    fun setStarred(file: RecFile, starred: Boolean): RecFile? {
        if (file.starred == starred) return file
        val target = File(file.path.parentFile, RecLayout.closedName(file.kind, file.startMs, file.durationMs, starred))
        return if (file.path.renameTo(target)) file.copy(path = target, starred = starred) else null
    }

    fun delete(file: RecFile): Boolean {
        val ok = file.path.delete() || !file.path.exists()
        if (ok) try { index()?.remove(listOf(file)) } catch (_: Exception) {}
        thumbFile(file).delete()
        return ok
    }

    /** Кадр из начала ролика (≈1 с), уменьшенный; кэш в cacheDir. null — файл не читается. */
    fun thumbnail(file: RecFile, width: Int = 320): Bitmap? {
        val cached = thumbFile(file)
        if (cached.isFile) android.graphics.BitmapFactory.decodeFile(cached.path)?.let { return it }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path.path)
            val frame = retriever.getFrameAtTime(minOf(1_000_000L, file.durationMs * 500L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val scaled = if (frame.width > width) Bitmap.createScaledBitmap(frame, width, frame.height * width / frame.width, true) else frame
            cached.parentFile?.mkdirs()
            cached.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            scaled
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun thumbFile(file: RecFile) = File(context.cacheDir, "archive_thumbs/${file.camera}/${file.kind.prefix}_${file.startMs}.jpg")

    /**
     * Копия ролика с понятным именем для «Поделиться» («Домофон 2026-11-18 21-30.mp4»): файл как есть, без
     * перекодирования. Прежние копии удаляются.
     */
    fun shareCopy(file: RecFile, title: String): File {
        val dir = File(context.cacheDir, SHARE_DIR).apply { deleteRecursively(); mkdirs() }
        val target = File(dir, shareName(title, file.startMs))
        file.path.copyTo(target, overwrite = true)
        return target
    }

    companion object {
        const val SHARE_DIR = "archive_share"

        fun shareName(title: String, startMs: Long): String {
            val safe = title.ifBlank { DoorbellConfig.DEFAULT_TITLE }.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
            return "$safe ${SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(Date(startMs))}.mp4"
        }

        fun defaultRoots(context: Context): List<File> =
            (try { context.getExternalFilesDirs(null).filterNotNull() } catch (_: Exception) { emptyList() })
                .ifEmpty { listOf(context.filesDir) }

        fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}

/**
 * Шкала суток и непрерывная запись как один плейлист (чистая логика). Отрезки 24/7 идут подряд с пропусками;
 * позиция на шкале (время на стене) переводится в (номер отрезка, смещение) и обратно.
 */
internal class ArchiveDay(val dayStartMs: Long, segments: List<RecFile>, events: List<RecArchive.Event>) {
    val dayEndMs = dayStartMs + 86_400_000L
    val segments = segments.filter { it.endMs > dayStartMs && it.startMs < dayEndMs }.sortedBy { it.startMs }
    val events = events.filter { it.file.endMs > dayStartMs && it.file.startMs < dayEndMs }

    /** Сплошные участки записи (соседние отрезки с разрывом меньше [GAP_MS] сливаются). */
    val coverage: List<LongRange> = buildList {
        var start = -1L
        var end = -1L
        for (s in this@ArchiveDay.segments) {
            if (start >= 0 && s.startMs - end <= GAP_MS) { end = maxOf(end, s.endMs); continue }
            if (start >= 0) add(start until end)
            start = s.startMs; end = s.endMs
        }
        if (start >= 0) add(start until end)
    }

    /** Куда встать в плейлисте 24/7 для момента [wallMs]; в пропуске — начало следующего отрезка. */
    fun locate(wallMs: Long): Pair<Int, Long>? {
        if (segments.isEmpty()) return null
        val inside = segments.indexOfFirst { wallMs >= it.startMs && wallMs < it.endMs }
        if (inside >= 0) return inside to (wallMs - segments[inside].startMs)
        val next = segments.indexOfFirst { it.startMs > wallMs }
        return if (next >= 0) next to 0L else segments.lastIndex to (segments.last().durationMs - 1).coerceAtLeast(0)
    }

    /** Время на стене для позиции плейлиста. */
    fun wallTime(segment: Int, offsetMs: Long): Long? = segments.getOrNull(segment)?.let { it.startMs + offsetMs }

    /** Доля суток (0–1) для времени на стене — для шкалы. */
    fun fraction(wallMs: Long) = ((wallMs - dayStartMs).toFloat() / 86_400_000f).coerceIn(0f, 1f)

    fun timeAt(fraction: Float) = dayStartMs + (fraction.coerceIn(0f, 1f) * 86_400_000L).toLong()

    companion object {
        /** Отрезки режутся на ключевом кадре — между ними бывают доли секунды; это не пропуск. */
        const val GAP_MS = 3_000L
    }
}
