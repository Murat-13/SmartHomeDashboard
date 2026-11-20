package com.example.smarthomedashboard

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.Mp4Muxer
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Запись с камер: отрезок фрагментированного MP4 (docs/recording.md). Видео и звук пишутся как есть, без
 * перекодирования. Фрагмент — каждая группа кадров (закрывается на ключевом кадре, если набралось [FRAGMENT_MS]), поэтому при падении
 * процесса теряется не больше одной группы кадров (обычно 1–2 с), а файл остаётся читаемым до последнего фрагмента.
 */
internal data class VideoParams(val codec: VideoDepacketizer.Codec, val vps: ByteArray?, val sps: ByteArray, val pps: ByteArray) {
    val width: Int
    val height: Int

    init {
        var w = 0
        var h = 0
        try {
            if (codec == VideoDepacketizer.Codec.H264) {
                val parsed = parseH264(sps)
                w = parsed.first; h = parsed.second
            } else {
                val parsed = parseH265(sps)
                w = parsed.first; h = parsed.second
            }
        } catch (_: Exception) {
        }
        width = w; height = h
    }

    /** Смена разрешения или параметров (ночной режим, перенастройка камеры) — нужен новый отрезок. */
    fun sameAs(other: VideoParams) = codec == other.codec && sps.contentEquals(other.sps) && pps.contentEquals(other.pps) &&
        (vps == null && other.vps == null || vps != null && other.vps != null && vps.contentEquals(other.vps))

    override fun equals(other: Any?) = other is VideoParams && sameAs(other)
    override fun hashCode() = sps.contentHashCode() * 31 + pps.contentHashCode()

    private companion object {
        @OptIn(UnstableApi::class)
        fun parseH264(sps: ByteArray): Pair<Int, Int> = NalUnitUtil.parseSpsNalUnit(sps, 0, sps.size).let { it.width to it.height }

        @OptIn(UnstableApi::class)
        fun parseH265(sps: ByteArray): Pair<Int, Int> =
            NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.size, null).let { it.width to it.height }
    }
}

internal data class AudioParams(val config: ByteArray, val sampleRate: Int, val channels: Int) {
    override fun equals(other: Any?) = other is AudioParams && config.contentEquals(other.config) &&
        sampleRate == other.sampleRate && channels == other.channels
    override fun hashCode() = config.contentHashCode()
}

@OptIn(UnstableApi::class)
internal class Mp4SegmentWriter(file: File, val video: VideoParams, audio: AudioParams?) : Closeable {
    private val stream = FileOutputStream(file)
    private val muxer = FragmentedMp4Muxer.Builder(stream.channel).setFragmentDurationMs(FRAGMENT_MS).build()
    private val videoTrack: Int
    private val audioTrack: Int?
    private var lastVideoUs = Long.MIN_VALUE
    private var lastAudioUs = Long.MIN_VALUE
    var bytes = 0L
        private set
    private var closed = false

    init {
        try {
            val start = VideoFrame.START_CODE
            val init = if (video.codec == VideoDepacketizer.Codec.H264) {
                listOf(start + video.sps, start + video.pps)
            } else {
                listOf(start + (video.vps ?: ByteArray(0)) + start + video.sps + start + video.pps)
            }
            videoTrack = muxer.addTrack(Format.Builder()
                .setSampleMimeType(if (video.codec == VideoDepacketizer.Codec.H264) MimeTypes.VIDEO_H264 else MimeTypes.VIDEO_H265)
                .setWidth(video.width).setHeight(video.height)
                .setInitializationData(init)
                .build())
            audioTrack = audio?.let {
                muxer.addTrack(Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_AAC)
                    .setSampleRate(it.sampleRate).setChannelCount(it.channels)
                    .setInitializationData(listOf(it.config))
                    .build())
            }
        } catch (e: Exception) {
            try { stream.close() } catch (_: Exception) {}
            throw e
        }
    }

    val hasAudio get() = audioTrack != null

    /** Кадр видео; метки должны возрастать (повтор/откат метки — кадр пропускается). */
    fun writeVideo(frame: VideoFrame, presentationUs: Long) {
        if (presentationUs <= lastVideoUs) return
        lastVideoUs = presentationUs
        val data = frame.annexB()
        bytes += data.size
        muxer.writeSampleData(videoTrack, ByteBuffer.wrap(data),
            BufferInfo(presentationUs, data.size, if (frame.key) C.BUFFER_FLAG_KEY_FRAME else 0))
    }

    fun writeAudio(frame: AudioFrame, presentationUs: Long) {
        val track = audioTrack ?: return
        if (presentationUs < 0 || presentationUs <= lastAudioUs) return
        lastAudioUs = presentationUs
        bytes += frame.data.size
        muxer.writeSampleData(track, ByteBuffer.wrap(frame.data), BufferInfo(presentationUs, frame.data.size, C.BUFFER_FLAG_KEY_FRAME))
    }

    override fun close() {
        if (closed) return
        closed = true
        try { muxer.close() } finally { try { stream.close() } catch (_: Exception) {} }
    }

    companion object {
        const val FRAGMENT_MS = 500L
    }
}

/**
 * Восстановление отрезка после падения: файл обрезается по концу последнего целого фрагмента (moof+mdat),
 * чтобы не оставлять недописанный хвост. Возвращает новую длину или -1, если файл непригоден (нет moov/фрагментов).
 */
internal object Mp4Repair {
    fun truncateToLastFragment(file: File): Long {
        RandomAccessFile(file, "rw").use { raf ->
            val length = raf.length()
            var pos = 0L
            var goodEnd = -1L
            var sawMoov = false
            var pendingMoof = false
            var fragments = 0
            val header = ByteArray(16)
            while (pos + 8 <= length) {
                raf.seek(pos)
                raf.readFully(header, 0, 8)
                var size = ((header[0].toLong() and 0xFF) shl 24) or ((header[1].toLong() and 0xFF) shl 16) or
                    ((header[2].toLong() and 0xFF) shl 8) or (header[3].toLong() and 0xFF)
                val type = String(header, 4, 4, Charsets.ISO_8859_1)
                if (size == 1L) {
                    if (pos + 16 > length) break
                    raf.readFully(header, 8, 8)
                    size = 0L
                    for (i in 8 until 16) size = (size shl 8) or (header[i].toLong() and 0xFF)
                } else if (size == 0L) {
                    size = length - pos
                }
                if (size < 8 || pos + size > length) break
                when (type) {
                    "moov" -> { sawMoov = true; goodEnd = pos + size }
                    "moof" -> pendingMoof = true
                    "mdat" -> if (pendingMoof) { pendingMoof = false; fragments++; goodEnd = pos + size }
                    else -> if (!pendingMoof) goodEnd = pos + size
                }
                pos += size
            }
            if (!sawMoov || fragments == 0 || goodEnd <= 0) return -1
            if (goodEnd < length) raf.setLength(goodEnd)
            return goodEnd
        }
    }
}

/**
 * Закрытый отрезок — в обычный MP4 с оглавлением (найдено 19.11.2026 на планшете: у фрагментированного MP4 без
 * индекса плеер не знает длительность и не перематывает — «0:00», «+10 с» отскакивало в начало). Пока отрезок
 * пишется, он фрагментированный (переживает падение процесса); после закрытия кадры перекладываются как есть,
 * без перекодирования, во временный файл, который заменяет исходный. Своя очередь — запись не ждёт.
 */
@OptIn(UnstableApi::class)
internal object Mp4Finalize {
    /** Переименования/удаления архива и замена файла здесь — под одним замком. */
    val LOCK = Any()
    private val queue = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "rec-finalize").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Поставить в очередь (повторная постановка того же файла игнорируется). */
    fun enqueue(file: File) {
        if (!pending.add(file.path)) return
        queue.execute {
            try { remux(file) } catch (e: Exception) { android.util.Log.w("Recording", "Оглавление MP4 не записано: ${e.javaClass.simpleName}") }
            finally { pending.remove(file.path) }
        }
    }

    /** Файл сейчас в очереди или перекладывается (его временный `.remux` трогать нельзя). */
    fun pending(file: File) = file.path in pending

    /** Фрагментированный MP4: в `moov` есть `mvex` (оглавление фрагментов вместо таблиц кадров). */
    fun isFragmented(file: File): Boolean {
        if (!file.isFile) return false
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            val header = ByteArray(8)
            while (pos + 8 <= raf.length()) {
                raf.seek(pos); raf.readFully(header)
                val size = ByteBuffer.wrap(header, 0, 4).int.toLong() and 0xFFFFFFFFL
                val type = String(header, 4, 4, Charsets.ISO_8859_1)
                if (type == "moov") {
                    if (size < 16 || size > 4_000_000) return false
                    val body = ByteArray((size - 8).toInt()); raf.readFully(body)
                    var i = 0
                    while (i + 8 <= body.size) {
                        val child = ByteBuffer.wrap(body, i, 4).int
                        if (String(body, i + 4, 4, Charsets.ISO_8859_1) == "mvex") return true
                        if (child < 8) return false
                        i += child
                    }
                    return false
                }
                if (size < 8) return false
                pos += size
            }
        }
        return false
    }

    /** true — файл заменён обычным MP4. Кадры, метки и флаги ключевых кадров — без изменений. */
    fun remux(file: File): Boolean {
        if (!isFragmented(file)) return false
        val originalLength = file.length()
        val tmp = File(file.parentFile, file.name + ".remux")
        val extractor = android.media.MediaExtractor()
        var output: FileOutputStream? = null
        try {
            extractor.setDataSource(file.path)
            output = FileOutputStream(tmp)
            val muxer = Mp4Muxer.Builder(output).setLastSampleDurationBehavior(
                Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS).build()
            val tracks = HashMap<Int, Int>()
            var maxInput = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val format = toFormat(f) ?: continue
                tracks[i] = muxer.addTrack(format)
                extractor.selectTrack(i)
                if (f.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = maxOf(maxInput, f.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            if (tracks.isEmpty()) return false
            var buffer = ByteBuffer.allocateDirect(maxInput)
            while (true) {
                buffer.clear()
                var size = try { extractor.readSampleData(buffer, 0) } catch (_: IllegalArgumentException) { -2 }
                // Кадр больше буфера (getSampleSize — только с API 28): буфер вдвое больше и ещё раз.
                while (size == -2 && buffer.capacity() < MAX_SAMPLE) {
                    buffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                    size = try { extractor.readSampleData(buffer, 0) } catch (_: IllegalArgumentException) { -2 }
                }
                if (size < 0) break
                val track = tracks[extractor.sampleTrackIndex]
                if (track != null) {
                    buffer.position(0); buffer.limit(size)
                    val key = extractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    muxer.writeSampleData(track, buffer, BufferInfo(extractor.sampleTime, size, if (key) C.BUFFER_FLAG_KEY_FRAME else 0))
                }
                extractor.advance()
            }
            muxer.close()
            output.close(); output = null
            synchronized(LOCK) {
                // Пока перекладывали, файл переименовали (звёздочка), удалили или дописали — оставляем как есть.
                if (!file.isFile || file.length() != originalLength || tmp.length() == 0L) { tmp.delete(); return false }
                if (!tmp.renameTo(file)) { tmp.delete(); return false }
            }
            return true
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            try { output?.close() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private const val MAX_SAMPLE = 64 shl 20

    internal fun formatOf(f: android.media.MediaFormat): Format? = toFormat(f)

    private fun toFormat(f: android.media.MediaFormat): Format? {
        val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: return null
        fun csd(key: String): ByteArray? = if (f.containsKey(key)) f.getByteBuffer(key)?.let { b ->
            val copy = b.duplicate(); copy.position(0); ByteArray(copy.remaining()).also { copy.get(it) } } else null
        val init = listOfNotNull(csd("csd-0"), csd("csd-1"), csd("csd-2"))
        return when {
            mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265 -> Format.Builder().setSampleMimeType(mime)
                .setWidth(f.getInteger(android.media.MediaFormat.KEY_WIDTH)).setHeight(f.getInteger(android.media.MediaFormat.KEY_HEIGHT))
                .setInitializationData(init).build()
            mime == MimeTypes.AUDIO_AAC -> Format.Builder().setSampleMimeType(mime)
                .setSampleRate(f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE))
                .setChannelCount(f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT))
                .setInitializationData(init).build()
            else -> null
        }
    }
}

/**
 * «Поделиться отрезком» (архив): кусок записи [fromMs]–[toMs] (время на стене) из одного или нескольких подряд
 * идущих файлов — в один обычный MP4, без перекодирования. Начало — с ключевого кадра не позже [fromMs]. Файл с
 * другими параметрами потока (камера сменила разрешение) обрывает отрезок на нём. Возвращает длительность, мс
 * (0 — ничего не записано). Блокирующее.
 */
@OptIn(UnstableApi::class)
internal object Mp4Cut {
    fun cut(sources: List<RecFile>, fromMs: Long, toMs: Long, out: File): Long {
        val files = sources.filter { it.endMs > fromMs && it.startMs < toMs }.sortedBy { it.startMs }
        if (files.isEmpty() || toMs <= fromMs) return 0
        val output = FileOutputStream(out)
        var muxer: Mp4Muxer? = null
        var origin = -1L // мкс на стене первого ключевого кадра
        var lastWall = 0L
        val muxTracks = HashMap<String, Int>()
        val formats = HashMap<String, Format>()
        val last = HashMap<Int, Long>()
        try {
            for ((n, file) in files.withIndex()) {
                val extractor = android.media.MediaExtractor()
                try {
                    synchronized(Mp4Finalize.LOCK) { extractor.setDataSource(file.path.path) }
                    val map = HashMap<Int, Int>()
                    var compatible = true
                    for (i in 0 until extractor.trackCount) {
                        val f = extractor.getTrackFormat(i)
                        val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                        val kind = if (mime.startsWith("video/")) "video" else if (mime.startsWith("audio/")) "audio" else continue
                        val format = Mp4Finalize.formatOf(f) ?: continue
                        val known = formats[kind]
                        if (known == null && n == 0) {
                            if (muxer == null) muxer = Mp4Muxer.Builder(output).setLastSampleDurationBehavior(
                                Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS).build()
                            formats[kind] = format
                            muxTracks[kind] = muxer!!.addTrack(format)
                        } else if (known == null || !same(known, format)) {
                            if (kind == "video") compatible = false
                            continue
                        }
                        map[i] = muxTracks[kind]!!
                        extractor.selectTrack(i)
                    }
                    if (!compatible || map.isEmpty()) break
                    val video = map.entries.firstOrNull { formats["video"] != null && muxTracks["video"] == it.value }?.key
                    val startUs = ((fromMs - file.startMs) * 1000).coerceAtLeast(0)
                    if (n == 0) extractor.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    var buffer = ByteBuffer.allocateDirect(1 shl 20)
                    while (true) {
                        buffer.clear()
                        var size = try { extractor.readSampleData(buffer, 0) } catch (_: IllegalArgumentException) { -2 }
                        while (size == -2 && buffer.capacity() < (64 shl 20)) {
                            buffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                            size = try { extractor.readSampleData(buffer, 0) } catch (_: IllegalArgumentException) { -2 }
                        }
                        if (size < 0) break
                        val track = extractor.sampleTrackIndex
                        val wallUs = file.startMs * 1000 + extractor.sampleTime
                        if (wallUs > toMs * 1000) break
                        val key = extractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        if (origin < 0) {
                            // Отрезок начинается с ключевого кадра видео (иначе первые секунды — серая каша).
                            if (track == video && key) origin = wallUs
                            else if (video == null) origin = wallUs
                        }
                        val mux = map[track]
                        if (origin >= 0 && mux != null && wallUs >= origin) {
                            val pts = wallUs - origin
                            if (pts > (last[mux] ?: -1L)) {
                                buffer.position(0); buffer.limit(size)
                                muxer!!.writeSampleData(mux, buffer, BufferInfo(pts, size, if (key) C.BUFFER_FLAG_KEY_FRAME else 0))
                                last[mux] = pts
                                lastWall = maxOf(lastWall, wallUs)
                            }
                        }
                        extractor.advance()
                    }
                } finally {
                    extractor.release()
                }
            }
            muxer?.close()
            muxer = null
        } finally {
            try { muxer?.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
        }
        return if (origin < 0) 0 else (lastWall - origin) / 1000
    }

    private fun same(a: Format, b: Format) = a.sampleMimeType == b.sampleMimeType && a.width == b.width && a.height == b.height &&
        a.sampleRate == b.sampleRate && a.channelCount == b.channelCount &&
        a.initializationData.size == b.initializationData.size &&
        a.initializationData.indices.all { a.initializationData[it].contentEquals(b.initializationData[it]) }
}
