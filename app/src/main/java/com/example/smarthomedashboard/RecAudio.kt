package com.example.smarthomedashboard

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

/**
 * Запись с камер: звук G.711 (PCMA/PCMU, RFC 3551) → AAC для MP4 (решение владельца 18.11.2026: видео не
 * трогаем, перекодируется только звук 8 кГц моно — это дёшево). Разбор G.711 — чистая логика, кодирование —
 * аппаратный/системный AAC-кодер Android ([AacEncoder]).
 */
internal class G711Depacketizer(private val aLaw: Boolean) {
    private val unwrapper = RtpTimestampUnwrapper()

    /** Отсчёты PCM 16 бит и метка RTP (8 кГц) первого отсчёта. */
    class Pcm(val timestamp: Long, val samples: ShortArray)

    fun push(packet: RtpPacket): Pcm {
        val out = ShortArray(packet.length)
        val table = if (aLaw) ALAW else ULAW
        for (i in 0 until packet.length) out[i] = table[packet.payload[packet.offset + i].toInt() and 0xFF]
        return Pcm(unwrapper.unwrap(packet.timestamp), out)
    }

    companion object {
        fun create(track: SdpTrack): G711Depacketizer? = when (track.encoding) {
            "PCMA" -> G711Depacketizer(true)
            "PCMU" -> G711Depacketizer(false)
            else -> null
        }?.takeIf { (track.clockRate == 0 || track.clockRate == 8000) && track.channels <= 1 }

        private val ALAW = ShortArray(256) { decodeALaw(it).toShort() }
        private val ULAW = ShortArray(256) { decodeULaw(it).toShort() }

        fun decodeALaw(code: Int): Int {
            val a = code xor 0x55
            val exponent = (a shr 4) and 0x07
            val mantissa = a and 0x0F
            var magnitude = if (exponent == 0) (mantissa shl 4) + 8 else ((mantissa shl 4) + 0x108) shl (exponent - 1)
            if (a and 0x80 == 0) magnitude = -magnitude
            return magnitude
        }

        fun decodeULaw(code: Int): Int {
            val u = code.inv() and 0xFF
            val exponent = (u shr 4) and 0x07
            val mantissa = u and 0x0F
            val magnitude = (((mantissa shl 3) + 0x84) shl exponent) - 0x84
            return if (u and 0x80 != 0) -magnitude else magnitude
        }
    }
}

internal object AacConfig {
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** AudioSpecificConfig для AAC-LC (ISO 14496-3): тип 2, индекс частоты, число каналов. */
    fun audioSpecificConfig(sampleRate: Int, channels: Int): ByteArray {
        val index = RATES.indexOf(sampleRate).takeIf { it >= 0 } ?: throw IllegalArgumentException("Частота $sampleRate")
        val value = (2 shl 11) or (index shl 7) or ((channels and 0x0F) shl 3)
        return byteArrayOf((value shr 8).toByte(), value.toByte())
    }
}

/**
 * Кодер AAC-LC через MediaCodec (синхронный режим, без ожидания: вход и выход опрашиваются на потоке записи).
 * Метки: вход — в отсчётах [sampleRate] RTP, выход — те же единицы. Сбой кодера — исключение, запись идёт без звука.
 */
internal class AacEncoder(val sampleRate: Int = 8000, val channels: Int = 1, bitrate: Int = 24_000) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var anchor = Long.MIN_VALUE
    private var released = false

    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    val config: ByteArray = AacConfig.audioSpecificConfig(sampleRate, channels)

    /** Подать отсчёты; вернуть готовые кадры AAC. Вход, не поместившийся сейчас, отбрасывается (звук, не видео). */
    fun encode(pcm: G711Depacketizer.Pcm): List<AudioFrame> {
        if (anchor == Long.MIN_VALUE) anchor = pcm.timestamp
        var offset = 0
        val out = ArrayList<AudioFrame>(2)
        while (offset < pcm.samples.size) {
            var index = codec.dequeueInputBuffer(0)
            if (index < 0) {
                // Входные буферы заняты: забрать готовое и попробовать ещё раз, иначе остаток пропустить.
                drain(out)
                index = codec.dequeueInputBuffer(2_000)
                if (index < 0) break
            }
            val buffer = codec.getInputBuffer(index) ?: break
            buffer.clear()
            buffer.order(java.nio.ByteOrder.nativeOrder())
            val count = minOf(pcm.samples.size - offset, buffer.remaining() / 2)
            for (i in 0 until count) buffer.putShort(pcm.samples[offset + i])
            val ptsUs = (pcm.timestamp + offset - anchor) * 1_000_000L / sampleRate
            codec.queueInputBuffer(index, 0, count * 2, ptsUs, 0)
            offset += count
        }
        drain(out)
        return out
    }

    private fun drain(out: MutableList<AudioFrame>) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data)
                out += AudioFrame(anchor + info.presentationTimeUs * sampleRate / 1_000_000L, data)
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    fun release() {
        if (released) return
        released = true
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (e: Exception) { Log.w("Recording", "AAC-кодер: ${e.javaClass.simpleName}") }
    }
}
