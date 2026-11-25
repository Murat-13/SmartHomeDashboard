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
        /** Дни (локальное начало суток), где есть хоть что-то; новые первыми. Считается один раз — календарь только на смене дня. */
        val days: List<Long> by lazy {
            val out = ArrayList<Long>()
            var until = Long.MIN_VALUE
            for (t in (continuous.map { it.startMs } + events.map { it.file.startMs }).sorted()) {
                if (t < until) continue
                val day = dayStart(t)
                if (out.lastOrNull() != day) out += day
                until = ArchiveTimeline.nextDay(day)
            }
            out.reversed()
        }
    }

    /** Записи камеры из памяти ([RecCatalog]), без носителя; null — он ещё не обойдён. */
    fun known(camera: String): Snapshot? = RecCatalog.camera(roots(), camera)?.let { snapshot(camera, it) }

    /** Записи последних дней камеры — по именам файлов: первое, что видно, пока читается остальное. */
    fun recent(camera: String): Snapshot {
        val days = roots().flatMap { RecLayout.dayDirs(it, camera) }
        val last = days.map { it.name }.distinct().sortedDescending().take(RECENT_DAYS)
        return snapshot(camera, days.filter { it.name in last }.flatMap { RecLayout.names(it, camera) })
    }

    /** Все записи камеры с носителей — по именам файлов (размеры архиву не нужны). */
    fun load(camera: String): Snapshot {
        val roots = roots()
        val files = roots.flatMap { root ->
            val since = RecCatalog.mark()
            RecLayout.dayDirs(root, camera).flatMap { RecLayout.names(it, camera) }.also { RecCatalog.publishCamera(root, camera, it, since) }
        }
        // Из памяти — с учётом удалений и ★, сделанных за время чтения.
        return snapshot(camera, RecCatalog.camera(roots, camera) ?: files)
    }

    /** Сверить память с носителем; true — записи камеры изменились. Нечего сверять — false. */
    fun verify(camera: String): Boolean {
        val since = RecCatalog.mark()
        val roots = roots()
        val listed = roots.flatMap { root -> RecLayout.dayDirs(root, camera).flatMap { RecLayout.names(it, camera) } }
        return RecCatalog.reconcile(roots, camera, listed, since)
    }

    private fun snapshot(camera: String, all: List<RecFile>): Snapshot {
        val files = all.filter { !it.open && it.durationMs > 0 }
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
        val ok = synchronized(Mp4Finalize.LOCK) { file.path.renameTo(target) }
        if (!ok) return null
        return file.copy(path = target, starred = starred).also { RecCatalog.replace(file, it) }
    }

    fun delete(file: RecFile): Boolean {
        val ok = synchronized(Mp4Finalize.LOCK) { file.path.delete() || !file.path.exists() }
        if (ok) RecCatalog.remove(listOf(file))
        if (ok) try { index()?.remove(listOf(file)) } catch (_: Exception) {}
        thumbFile(file).delete()
        return ok
    }

    /**
     * Кадр из начала ролика (≈1 с), уменьшенный; кэш в cacheDir. null — файл не читается.
     * Системный `MediaMetadataRetriever` берёт аппаратный декодер, а он у Samsung SM-X216B не принимает HD-ролики
     * Reolink 2560×1920 («Not supported spec», 19.11.2026) — тогда кадр декодирует программный декодер ([SoftFrame]).
     */
    fun thumbnail(file: RecFile, width: Int = 320): Bitmap? {
        val cached = thumbFile(file)
        if (cached.isFile) android.graphics.BitmapFactory.decodeFile(cached.path)?.let { return it }
        val atUs = minOf(1_000_000L, file.durationMs * 500L)
        val frame = systemFrame(file, atUs) ?: SoftFrame.grab(file.path.path, atUs, width) ?: return null
        val scaled = if (frame.width > width) Bitmap.createScaledBitmap(frame, width, frame.height * width / frame.width, true) else frame
        try {
            cached.parentFile?.mkdirs()
            cached.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (_: Exception) {}
        return scaled
    }

    /**
     * Кадр из момента [offsetMs] ролика для превью над риской шкалы (ключевой кадр не позже, уменьшенный до [width]).
     * Без кэша на диске: превью живут, пока ведут ленту.
     */
    fun frameAt(file: RecFile, offsetMs: Long, width: Int = 240): Bitmap? {
        val atUs = offsetMs.coerceIn(0, file.durationMs.coerceAtLeast(1)) * 1000
        val frame = systemFrame(file, atUs) ?: SoftFrame.grab(file.path.path, atUs, width) ?: return null
        return if (frame.width > width) Bitmap.createScaledBitmap(frame, width, frame.height * width / frame.width, true) else frame
    }

    private fun systemFrame(file: RecFile, atUs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path.path)
            retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
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

    /**
     * Строка о месте внизу архива: занято из лимита и оценка ([RecRetention.outlookText] — та же, что в редакторе
     * камеры) по носителю, куда идёт запись. [keepDays] — срок хранения круглосуточной записи этой камеры. Блокирующее.
     */
    fun storageText(device: RecordingDevice, keepDays: Int, now: Long = System.currentTimeMillis()): String {
        val o = RecVolumes.outlook(RecVolumes.snapshot(context, device.useSdCard), device.limitGb, device.reserveGb, now)
        return RecRetention.archiveLine(o, keepDays)
    }

    companion object {
        const val SHARE_DIR = "archive_share"
        /** Дней (UTC) в первой порции: местные сутки лежат в двух папках. */
        private const val RECENT_DAYS = 2

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
    /** Следующая местная полночь: при переводе часов в сутках 23 или 25 ч (аудит 20.11.2026, A8). */
    val dayEndMs = ArchiveTimeline.nextDay(dayStartMs)
    private val spanMs = (dayEndMs - dayStartMs).coerceAtLeast(1)
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
    fun fraction(wallMs: Long) = ((wallMs - dayStartMs).toFloat() / spanMs).coerceIn(0f, 1f)

    fun timeAt(fraction: Float) = dayStartMs + (fraction.coerceIn(0f, 1f) * spanMs).toLong()

    companion object {
        /** Отрезки режутся на ключевом кадре — между ними бывают доли секунды; это не пропуск. */
        const val GAP_MS = 3_000L
    }
}

/**
 * Один кадр видео программным декодером Android (`c2.android.*` / `OMX.google.*`) — для превью роликов, которые
 * аппаратный декодер не принимает. Ключевой кадр не позже [atUs]; выход YUV 4:2:0 уменьшается до [width] прямо
 * при переводе в RGB (без полного кадра 2560×1920 в памяти).
 */
internal object SoftFrame {
    fun grab(path: String, atUs: Long, width: Int): Bitmap? {
        val extractor = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: return null
            val name = softwareDecoder(mime) ?: return null
            format.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            extractor.seekTo(atUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = android.media.MediaCodec.createByCodecName(name).also { codec = it }
            c.configure(format, null, null, 0)
            c.start()
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = c.getInputBuffer(inIndex) ?: return null
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) { c.queueInputBuffer(inIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val outIndex = c.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val image = c.getOutputImage(outIndex)
                        val bitmap = image?.let { toBitmap(it, width) }
                        image?.close()
                        c.releaseOutputBuffer(outIndex, false)
                        if (bitmap != null) return bitmap
                    } else c.releaseOutputBuffer(outIndex, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                }
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun softwareDecoder(mime: String): String? =
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && (info.name.startsWith("c2.android.") || info.name.startsWith("OMX.google.")) &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }?.name

    private fun toBitmap(image: android.media.Image, width: Int): Bitmap? {
        if (image.format != android.graphics.ImageFormat.YUV_420_888 || image.planes.size < 3) return null
        val crop = image.cropRect
        val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        val out = Yuv.toArgb(
            crop.width(), crop.height(), crop.left, crop.top, width,
            { i -> y.buffer.get(i).toInt() and 0xFF }, y.rowStride, y.pixelStride,
            { i -> u.buffer.get(i).toInt() and 0xFF }, { i -> v.buffer.get(i).toInt() and 0xFF }, u.rowStride, u.pixelStride
        ) ?: return null
        return Bitmap.createBitmap(out.pixels, out.width, out.height, Bitmap.Config.ARGB_8888)
    }
}

/** YUV 4:2:0 → ARGB с уменьшением до [targetWidth] (ближайший пиксель; для превью достаточно). Чистая функция. */
internal object Yuv {
    class Result(val pixels: IntArray, val width: Int, val height: Int)

    fun toArgb(
        srcWidth: Int, srcHeight: Int, left: Int, top: Int, targetWidth: Int,
        y: (Int) -> Int, yRow: Int, yPixel: Int,
        u: (Int) -> Int, v: (Int) -> Int, uvRow: Int, uvPixel: Int
    ): Result? {
        if (srcWidth <= 0 || srcHeight <= 0) return null
        val w = minOf(targetWidth, srcWidth)
        val h = (srcHeight.toLong() * w / srcWidth).toInt().coerceAtLeast(1)
        val out = IntArray(w * h)
        for (ty in 0 until h) {
            val sy = top + (ty.toLong() * srcHeight / h).toInt()
            for (tx in 0 until w) {
                val sx = left + (tx.toLong() * srcWidth / w).toInt()
                val luma = y(sy * yRow + sx * yPixel) - 16
                val uvIndex = (sy / 2) * uvRow + (sx / 2) * uvPixel
                val cb = u(uvIndex) - 128
                val cr = v(uvIndex) - 128
                val c = 1.164f * luma
                val r = (c + 1.596f * cr).toInt().coerceIn(0, 255)
                val g = (c - 0.392f * cb - 0.813f * cr).toInt().coerceIn(0, 255)
                val b = (c + 2.017f * cb).toInt().coerceIn(0, 255)
                out[ty * w + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Result(out, w, h)
    }
}
