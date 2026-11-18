package com.example.smarthomedashboard

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Запись с камер: разбор RTP в кадры без декодирования (docs/recording.md).
 * H.264 — RFC 6184 (одиночные NAL, STAP-A, FU-A), H.265 — RFC 7798 (одиночные NAL, AP, FU),
 * AAC — RFC 3640 (mpeg4-generic, AAC-hbr). Потеря пакета видео — кадр отбрасывается до следующего ключевого.
 */

internal class RtpPacket(
    val payloadType: Int,
    val marker: Boolean,
    val sequence: Int,
    val timestamp: Long,
    val payload: ByteArray,
    val offset: Int,
    val length: Int
) {
    companion object {
        /** Разобрать RTP-пакет; некорректный — null. [data] не копируется. */
        fun parse(data: ByteArray, length: Int): RtpPacket? {
            if (length < 12) return null
            val b0 = data[0].toInt() and 0xFF
            if (b0 shr 6 != 2) return null
            val padding = b0 and 0x20 != 0
            val extension = b0 and 0x10 != 0
            val csrc = b0 and 0x0F
            val b1 = data[1].toInt() and 0xFF
            val seq = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val ts = ((data[4].toLong() and 0xFF) shl 24) or ((data[5].toLong() and 0xFF) shl 16) or
                ((data[6].toLong() and 0xFF) shl 8) or (data[7].toLong() and 0xFF)
            var offset = 12 + csrc * 4
            if (offset > length) return null
            if (extension) {
                if (offset + 4 > length) return null
                val words = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
                offset += 4 + words * 4
                if (offset > length) return null
            }
            var end = length
            if (padding) {
                val pad = data[length - 1].toInt() and 0xFF
                end -= pad
                if (end < offset) return null
            }
            return RtpPacket(b1 and 0x7F, b1 and 0x80 != 0, seq, ts, data, offset, end - offset)
        }
    }
}

/** Развернуть 32-битную метку времени RTP в непрерывную (переполнение примерно раз в 13 ч при 90 кГц). */
internal class RtpTimestampUnwrapper {
    private var last = -1L
    private var base = 0L
    fun unwrap(ts: Long): Long {
        if (last >= 0) {
            val diff = ts - last
            if (diff < -(1L shl 31)) base += 1L shl 32
            else if (diff > (1L shl 31)) base -= 1L shl 32
        }
        last = ts
        return base + ts
    }
}

/** Кадр видео: NAL-блоки без стартовых кодов, метка в единицах [clockRate] RTP. */
internal class VideoFrame(val timestamp: Long, val nals: List<ByteArray>, val key: Boolean) {
    /** Annex-B (00 00 00 01 перед каждым NAL) — так кадр принимает муксер. */
    fun annexB(): ByteArray {
        val out = ByteArrayOutputStream(nals.sumOf { it.size + 4 })
        for (n in nals) { out.write(START_CODE); out.write(n) }
        return out.toByteArray()
    }

    companion object { val START_CODE = byteArrayOf(0, 0, 0, 1) }
}

internal class AudioFrame(val timestamp: Long, val data: ByteArray)

/** Общий разборщик видео: сборка кадра по маркеру/смене метки, параметры потока, реакция на потери. */
internal abstract class VideoDepacketizer(val codec: Codec) {
    enum class Codec(val mime: String) { H264("video/avc"), H265("video/hevc") }

    /** Последние параметры потока (без стартовых кодов): VPS (только H.265), SPS, PPS. */
    var vps: ByteArray? = null; protected set
    var sps: ByteArray? = null; protected set
    var pps: ByteArray? = null; protected set
    /** Кадров отброшено из-за потерь пакетов (для диагностики). */
    var droppedFrames = 0; private set

    private val unwrapper = RtpTimestampUnwrapper()
    private var expectedSeq = -1
    private var current = mutableListOf<ByteArray>()
    private var currentTs = Long.MIN_VALUE
    private var corrupt = false
    private var waitKey = true
    private var fragment: ByteArrayOutputStream? = null

    val hasParameters get() = sps != null && pps != null && (codec == Codec.H264 || vps != null)

    /** Принять пакет; возвращает готовые кадры (обычно ноль или один). */
    fun push(packet: RtpPacket): List<VideoFrame> {
        var out: MutableList<VideoFrame>? = null
        val ts = unwrapper.unwrap(packet.timestamp)
        if (expectedSeq >= 0 && packet.sequence != expectedSeq) {
            // Потеря: текущий кадр испорчен, следующий без ключевого тоже — ждём ключевой.
            corrupt = true
            fragment = null
        }
        expectedSeq = (packet.sequence + 1) and 0xFFFF
        if (currentTs != Long.MIN_VALUE && ts != currentTs) {
            val gap = corrupt
            finish()?.let { (out ?: mutableListOf<VideoFrame>().also { l -> out = l }).add(it) }
            if (gap) waitKey = true
        }
        currentTs = ts
        payload(packet.payload, packet.offset, packet.length)
        if (packet.marker) finish()?.let { (out ?: mutableListOf<VideoFrame>().also { l -> out = l }).add(it) }
        return out ?: emptyList()
    }

    private fun finish(): VideoFrame? {
        val nals = current
        val ts = currentTs
        current = mutableListOf()
        currentTs = Long.MIN_VALUE
        fragment = null
        val wasCorrupt = corrupt
        corrupt = false
        if (wasCorrupt) {
            // Кадр испорчен (даже если от него ничего не осталось): дальше — только с ключевого.
            droppedFrames++
            waitKey = true
            return null
        }
        if (nals.isEmpty()) return null
        val key = nals.any { isKey(it) }
        if (waitKey) {
            if (!key || !hasParameters) { droppedFrames++; return null }
            waitKey = false
        }
        return VideoFrame(ts, nals, key)
    }

    protected fun nal(data: ByteArray) {
        if (data.isEmpty()) return
        when (parameterKind(data)) {
            0 -> vps = data
            1 -> sps = data
            2 -> pps = data
        }
        current.add(data)
    }

    protected fun fragmentStart(header: ByteArray) { fragment = ByteArrayOutputStream().apply { write(header) } }
    protected fun fragmentAppend(src: ByteArray, off: Int, len: Int) {
        val f = fragment ?: run { corrupt = true; return } // середина без начала — потеряно начало
        f.write(src, off, len)
    }
    protected fun fragmentEnd() { fragment?.let { nal(it.toByteArray()) }; fragment = null }

    protected abstract fun payload(data: ByteArray, off: Int, len: Int)
    protected abstract fun isKey(nal: ByteArray): Boolean
    /** 0 — VPS, 1 — SPS, 2 — PPS, -1 — прочее. */
    protected abstract fun parameterKind(nal: ByteArray): Int

    /** Параметры из SDP (sprop-…), пока их нет в потоке. */
    fun seed(vps: ByteArray?, sps: ByteArray?, pps: ByteArray?) {
        if (vps != null) this.vps = vps
        if (sps != null) this.sps = sps
        if (pps != null) this.pps = pps
    }

    companion object {
        fun create(track: SdpTrack): VideoDepacketizer? = when (track.encoding) {
            "H264" -> H264Depacketizer().also { d ->
                track.fmtp["sprop-parameter-sets"]?.split(',')?.mapNotNull { decode(it) }?.let { sets ->
                    d.seed(null, sets.firstOrNull { (it[0].toInt() and 0x1F) == 7 }, sets.firstOrNull { (it[0].toInt() and 0x1F) == 8 })
                }
            }
            "H265", "HEVC" -> H265Depacketizer().also { d ->
                d.seed(track.fmtp["sprop-vps"]?.let { decode(it) }, track.fmtp["sprop-sps"]?.let { decode(it) },
                    track.fmtp["sprop-pps"]?.let { decode(it) })
            }
            else -> null
        }

        private fun decode(b64: String): ByteArray? = try {
            Base64.getDecoder().decode(b64.trim()).takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }
}

internal class H264Depacketizer : VideoDepacketizer(Codec.H264) {
    override fun payload(data: ByteArray, off: Int, len: Int) {
        if (len < 1) return
        val type = data[off].toInt() and 0x1F
        when (type) {
            in 1..23 -> nal(data.copyOfRange(off, off + len))
            24 -> { // STAP-A
                var p = off + 1
                while (p + 2 <= off + len) {
                    val size = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                    p += 2
                    if (size == 0 || p + size > off + len) break
                    nal(data.copyOfRange(p, p + size))
                    p += size
                }
            }
            28 -> { // FU-A
                if (len < 2) return
                val fu = data[off + 1].toInt() and 0xFF
                val start = fu and 0x80 != 0
                val end = fu and 0x40 != 0
                if (start) fragmentStart(byteArrayOf(((data[off].toInt() and 0xE0) or (fu and 0x1F)).toByte()))
                fragmentAppend(data, off + 2, len - 2)
                if (end) fragmentEnd()
            }
        }
    }

    override fun isKey(nal: ByteArray) = (nal[0].toInt() and 0x1F) == 5
    override fun parameterKind(nal: ByteArray) = when (nal[0].toInt() and 0x1F) { 7 -> 1; 8 -> 2; else -> -1 }
}

internal class H265Depacketizer : VideoDepacketizer(Codec.H265) {
    override fun payload(data: ByteArray, off: Int, len: Int) {
        if (len < 2) return
        val type = (data[off].toInt() shr 1) and 0x3F
        when (type) {
            48 -> { // AP
                var p = off + 2
                while (p + 2 <= off + len) {
                    val size = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                    p += 2
                    if (size == 0 || p + size > off + len) break
                    nal(data.copyOfRange(p, p + size))
                    p += size
                }
            }
            49 -> { // FU
                if (len < 3) return
                val fu = data[off + 2].toInt() and 0xFF
                val start = fu and 0x80 != 0
                val end = fu and 0x40 != 0
                if (start) {
                    val nalType = fu and 0x3F
                    val h0 = ((data[off].toInt() and 0x81) or (nalType shl 1)).toByte()
                    fragmentStart(byteArrayOf(h0, data[off + 1]))
                }
                fragmentAppend(data, off + 3, len - 3)
                if (end) fragmentEnd()
            }
            50 -> {} // PACI — не используется камерами
            else -> nal(data.copyOfRange(off, off + len))
        }
    }

    override fun isKey(nal: ByteArray) = ((nal[0].toInt() shr 1) and 0x3F) in 16..21
    override fun parameterKind(nal: ByteArray) = when ((nal[0].toInt() shr 1) and 0x3F) { 32 -> 0; 33 -> 1; 34 -> 2; else -> -1 }
}

/** AAC по RFC 3640 (mpeg4-generic, AAC-hbr). Несколько кадров в пакете — метки через [samplesPerFrame]. */
internal class AacDepacketizer(
    val config: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    private val sizeLength: Int = 13,
    private val indexLength: Int = 3
) {
    private val unwrapper = RtpTimestampUnwrapper()
    val samplesPerFrame = 1024

    fun push(packet: RtpPacket): List<AudioFrame> {
        val d = packet.payload
        var p = packet.offset
        val end = packet.offset + packet.length
        if (end - p < 2) return emptyList()
        val headersBits = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
        p += 2
        val headersBytes = (headersBits + 7) / 8
        if (p + headersBytes > end) return emptyList()
        val headerBits = sizeLength + indexLength
        if (headerBits <= 0) return emptyList()
        val count = headersBits / headerBits
        val sizes = IntArray(count)
        var bit = 0
        for (i in 0 until count) {
            sizes[i] = readBits(d, p, bit, sizeLength)
            bit += headerBits
        }
        p += headersBytes
        val ts = unwrapper.unwrap(packet.timestamp)
        val out = ArrayList<AudioFrame>(count)
        for (i in 0 until count) {
            val size = sizes[i]
            if (size <= 0 || p + size > end) break
            out += AudioFrame(ts + i.toLong() * samplesPerFrame, d.copyOfRange(p, p + size))
            p += size
        }
        return out
    }

    private fun readBits(data: ByteArray, byteOffset: Int, bitOffset: Int, count: Int): Int {
        var value = 0
        for (i in 0 until count) {
            val bitIndex = bitOffset + i
            val byte = data[byteOffset + bitIndex / 8].toInt() and 0xFF
            value = (value shl 1) or ((byte shr (7 - bitIndex % 8)) and 1)
        }
        return value
    }

    companion object {
        /** Только AAC-hbr с конфигурацией в SDP; прочие режимы (и G.711) — null, запись идёт без звука. */
        fun create(track: SdpTrack): AacDepacketizer? {
            if (track.encoding != "MPEG4-GENERIC") return null
            val mode = track.fmtp["mode"].orEmpty()
            if (!mode.equals("AAC-hbr", ignoreCase = true)) return null
            val hex = track.fmtp["config"]?.takeIf { it.length >= 4 && it.length % 2 == 0 } ?: return null
            val config = try { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } } catch (_: Exception) { return null }
            val size = track.fmtp["sizelength"]?.toIntOrNull() ?: 13
            val index = track.fmtp["indexlength"]?.toIntOrNull() ?: 3
            if (size !in 1..16 || index !in 0..8) return null
            return AacDepacketizer(config, track.clockRate.takeIf { it > 0 } ?: 8000, track.channels.coerceAtLeast(1), size, index)
        }
    }
}
