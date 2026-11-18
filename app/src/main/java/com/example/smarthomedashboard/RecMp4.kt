package com.example.smarthomedashboard

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
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
