package com.example.smarthomedashboard

import android.content.Context
import android.media.MediaCrypto
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Живое видео H.264 без задержки декодера (docs/doorbell.md, «Задержка живого видео»).
 *
 * Камеры (Reolink и др.) не пишут в SPS ограничения потока (VUI `bitstream_restriction`). Без них декодер обязан
 * считать, что кадры могут идти не по порядку показа, и держит у себя до `MaxDpbFrames` кадров (у субпотока
 * Reolink 640×480 с уровнем 5.1 — 16 кадров, при 10 к/с это 1,2–1,6 с задержки; у основного 2560×1920 — 9 кадров).
 * У потока с одним опорным кадром (`max_num_ref_frames ≤ 1`) перестановки быть не может — это дописывается в SPS:
 * `max_num_reorder_frames = 0`, и декодер отдаёт кадр сразу. Так же поступает WebRTC (SpsVuiRewriter).
 *
 * Поток с двумя и более опорными кадрами не трогаем: там перестановка возможна (B-кадры).
 */
internal object H264LowDelay {
    private const val NAL_SPS = 7
    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /**
     * SPS ([nal] с заголовком NAL, без стартового кода) с дописанным «перестановки нет».
     * null — менять не нужно (уже так), нельзя (опорных кадров больше одного) или SPS не разобран.
     */
    fun rewriteSps(nal: ByteArray, offset: Int = 0, length: Int = nal.size - offset): ByteArray? {
        if (length < 5 || (nal[offset].toInt() and 0x1F) != NAL_SPS) return null
        return try {
            val rbsp = unescape(nal, offset + 1, length - 1)
            val r = BitReader(rbsp)
            val profile = r.u(8)
            r.skip(16) // флаги ограничений и уровень
            r.ue() // seq_parameter_set_id
            if (profile in HIGH_PROFILES) {
                val chroma = r.ue()
                if (chroma == 3) r.skip(1)
                r.ue(); r.ue(); r.skip(1)
                if (r.u(1) == 1) for (i in 0 until if (chroma != 3) 8 else 12) if (r.u(1) == 1) scalingList(r, if (i < 6) 16 else 64)
            }
            r.ue() // log2_max_frame_num_minus4
            when (r.ue()) {
                0 -> r.ue()
                1 -> {
                    r.skip(1); r.se(); r.se()
                    val cycle = r.ue()
                    require(cycle <= 255)
                    repeat(cycle) { r.se() }
                }
            }
            val refs = r.ue()
            r.skip(1); r.ue(); r.ue()
            if (r.u(1) == 0) r.skip(1) // frame_mbs_only_flag → mb_adaptive_frame_field_flag
            r.skip(1)
            if (r.u(1) == 1) repeat(4) { r.ue() }
            val vuiFlagAt = r.position
            val vui = r.u(1)
            if (refs > 1) return null
            val out = BitWriter()
            if (vui == 0) {
                out.copy(rbsp, 0, vuiFlagAt)
                out.bit(1) // vui_parameters_present_flag
                repeat(8) { out.bit(0) } // соотношение сторон, overscan, тип сигнала, цветность, время, два HRD, pic_struct
                restriction(out, refs)
            } else {
                if (r.u(1) == 1 && r.u(8) == 255) r.skip(32)
                if (r.u(1) == 1) r.skip(1)
                if (r.u(1) == 1) { r.skip(4); if (r.u(1) == 1) r.skip(24) }
                if (r.u(1) == 1) { r.ue(); r.ue() }
                if (r.u(1) == 1) r.skip(65)
                val nalHrd = r.u(1); if (nalHrd == 1) hrd(r)
                val vclHrd = r.u(1); if (vclHrd == 1) hrd(r)
                if (nalHrd == 1 || vclHrd == 1) r.skip(1)
                r.skip(1) // pic_struct_present_flag
                val restrictionFlagAt = r.position
                if (r.u(1) == 1) {
                    r.skip(1); r.ue(); r.ue(); r.ue(); r.ue()
                    val reorderAt = r.position
                    if (r.ue() == 0) return null // уже «перестановки нет»
                    out.copy(rbsp, 0, reorderAt)
                    out.ue(0)
                    out.ue(maxOf(refs, 1))
                } else {
                    out.copy(rbsp, 0, restrictionFlagAt)
                    restriction(out, refs)
                }
            }
            out.trailing()
            byteArrayOf(nal[offset]) + escape(out.bytes())
        } catch (_: Exception) {
            null
        }
    }

    /** bitstream_restriction_flag = 1 и его поля: значения по умолчанию из стандарта, перестановки нет. */
    private fun restriction(out: BitWriter, refs: Int) {
        out.bit(1) // bitstream_restriction_flag
        out.bit(1) // motion_vectors_over_pic_boundaries_flag
        out.ue(2); out.ue(1) // max_bytes_per_pic_denom, max_bits_per_mb_denom
        out.ue(16); out.ue(16) // log2_max_mv_length_horizontal / vertical
        out.ue(0) // max_num_reorder_frames
        out.ue(maxOf(refs, 1)) // max_dec_frame_buffering
    }

    internal fun scalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        repeat(size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            if (next != 0) last = next
        }
    }

    private fun hrd(r: BitReader) {
        val count = r.ue()
        require(count <= 31)
        r.skip(8)
        repeat(count + 1) { r.ue(); r.ue(); r.skip(1) }
        r.skip(20)
    }

    /** Начинается ли блок Annex-B с набора параметров (SPS до первого среза) — быстрый ответ для обычных кадров. */
    fun spsAt(data: ByteBuffer, limit: Int): Boolean {
        var i = 0
        val end = minOf(limit, 512)
        while (i + 3 < end) {
            if (data.get(i).toInt() == 0 && data.get(i + 1).toInt() == 0 && data.get(i + 2).toInt() == 1) {
                val type = data.get(i + 3).toInt() and 0x1F
                if (type == NAL_SPS) return true
                if (type in 1..5) return false // срез: набора параметров в этом кадре нет
                i += 3
            } else i++
        }
        return false
    }

    /**
     * Блок доступа Annex-B (стартовые коды 00 00 01 / 00 00 00 01): SPS внутри заменяется на «без перестановки».
     * null — менять нечего.
     */
    fun rewriteAccessUnit(data: ByteArray, length: Int = data.size): ByteArray? {
        var i = 0
        while (i + 3 < length) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                val start = i + 3
                val type = data[start].toInt() and 0x1F
                if (type in 1..5) return null
                if (type == NAL_SPS) {
                    var end = length
                    var j = start
                    while (j + 2 < length) {
                        if (data[j].toInt() == 0 && data[j + 1].toInt() == 0 && (data[j + 2].toInt() == 1 ||
                                (data[j + 2].toInt() == 0 && j + 3 < length && data[j + 3].toInt() == 1))) { end = j; break }
                        j++
                    }
                    val sps = rewriteSps(data, start, end - start) ?: return null
                    val out = ByteArray(start + sps.size + (length - end))
                    System.arraycopy(data, 0, out, 0, start)
                    System.arraycopy(sps, 0, out, start, sps.size)
                    System.arraycopy(data, end, out, start + sps.size, length - end)
                    return out
                }
                i = start
            } else i++
        }
        return null
    }

    /** Убрать байты защиты от стартового кода (00 00 03 → 00 00). */
    internal fun unescape(data: ByteArray, offset: Int, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length)
        var zeros = 0
        for (k in offset until offset + length) {
            val b = data[k].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Вставить байты защиты: после двух нулей байт 0–3 предваряется 03. */
    internal fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 4)
        var zeros = 0
        for (x in rbsp) {
            val b = x.toInt() and 0xFF
            if (zeros >= 2 && b <= 3) { out.write(3); zeros = 0 }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    internal class BitReader(private val data: ByteArray) {
        var position = 0
            private set

        fun bit(): Int {
            if (position >= data.size * 8) throw IndexOutOfBoundsException("SPS короче, чем нужно")
            val b = (data[position ushr 3].toInt() ushr (7 - (position and 7))) and 1
            position++
            return b
        }

        fun u(bits: Int): Int { var v = 0; repeat(bits) { v = (v shl 1) or bit() }; return v }
        fun skip(bits: Int) { if (position + bits > data.size * 8) throw IndexOutOfBoundsException("SPS короче, чем нужно"); position += bits }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) { zeros++; require(zeros <= 30) }
            return (1 shl zeros) - 1 + if (zeros > 0) u(zeros) else 0
        }

        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }

    internal class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var count = 0

        fun bit(b: Int) {
            current = (current shl 1) or (b and 1)
            if (++count == 8) { out.write(current); current = 0; count = 0 }
        }

        fun u(value: Int, bits: Int) { for (i in bits - 1 downTo 0) bit(value ushr i) }

        fun ue(value: Int) {
            val x = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(x)
            repeat(length - 1) { bit(0) }
            u(x, length)
        }

        fun se(value: Int) = ue(if (value > 0) value * 2 - 1 else -value * 2)

        fun copy(from: ByteArray, startBit: Int, endBit: Int) {
            for (p in startBit until endBit) bit((from[p ushr 3].toInt() ushr (7 - (p and 7))) and 1)
        }

        /** rbsp_trailing_bits: единица и нули до границы байта. */
        fun trailing() { bit(1); while (count != 0) bit(0) }

        fun bytes(): ByteArray = out.toByteArray()
    }
}

/**
 * Рендереры живого RTSP-видео: видео H.264 идёт в аппаратный декодер с SPS «без перестановки кадров»
 * ([H264LowDelay]) — и в начальных данных декодера (csd-0), и в самом потоке (камера повторяет SPS перед каждым
 * ключевым кадром).
 */
@OptIn(UnstableApi::class)
internal class LiveRenderers(
    context: Context, private val softwareLowDelay: Boolean = true, private val retime: Boolean = true
) : DefaultRenderersFactory(context) {
    /** Выравнивание времени показа у созданного видеорендерера (для журнала); null — выключено. */
    @Volatile var retimer: FrameRetimer? = null
        private set
    /** Созданный видеорендерер (состояние декодера — в журнал остановок, [StallProbe]). */
    @Volatile var video: LowDelayVideoRenderer? = null
        private set

    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>
    ) {
        val timing = if (retime) FrameRetimer() else null
        retimer = timing
        out.add(LowDelayVideoRenderer(context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
            enableDecoderFallback, eventHandler, eventListener, softwareLowDelay, timing).also { video = it })
    }
}

@OptIn(UnstableApi::class)
internal class LowDelayVideoRenderer(
    context: Context, adapters: MediaCodecAdapter.Factory, selector: MediaCodecSelector, joiningMs: Long, fallback: Boolean,
    handler: Handler, listener: VideoRendererEventListener, private val softwareLowDelay: Boolean = true,
    private val retimer: FrameRetimer? = null
) : MediaCodecVideoRenderer(context, adapters, selector, joiningMs, fallback, handler, listener, DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) {
    /** SPS этого декодера переписывается: декодер не держит кадры «на перестановку». */
    private var lowDelay = false
    /** Кадр, готовый с опозданием, всё равно показывается ([LiveTuning.Mode.freshest]). */
    private var freshest = false

    override fun getMediaCodecConfiguration(
        codecInfo: MediaCodecInfo, format: Format, crypto: MediaCrypto?, codecOperatingRate: Float
    ): MediaCodecAdapter.Configuration {
        val configuration = super.getMediaCodecConfiguration(codecInfo, format, crypto, codecOperatingRate)
        val mode = LiveTuning.mode(codecInfo.codecMimeType == MimeTypes.VIDEO_H264, codecInfo.softwareOnly, softwareLowDelay)
        lowDelay = mode.lowDelay
        freshest = mode.freshest
        if (lowDelay) {
            val media = configuration.mediaFormat
            val csd = try { media.getByteBuffer("csd-0") } catch (_: Exception) { null }
            if (csd != null) {
                val bytes = ByteArray(csd.remaining())
                csd.duplicate().get(bytes)
                H264LowDelay.rewriteAccessUnit(bytes)?.let {
                    media.setByteBuffer("csd-0", ByteBuffer.wrap(it))
                    Log.i("DoorbellVideo", "Декодеру ${codecInfo.name} сообщено: перестановки кадров нет (SPS камеры этого не говорит)")
                }
            }
        }
        return configuration
    }

    override fun shouldDropOutputBuffer(earlyUs: Long, elapsedRealtimeUs: Long, isLastBuffer: Boolean): Boolean =
        !freshest && super.shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs, isLastBuffer)

    /** Кадр показывается по выровненному времени ([FrameRetimer]): не позже своей метки, без «пачек» после ключевого. */
    override fun processOutputBuffer(
        positionUs: Long, elapsedRealtimeUs: Long, codec: MediaCodecAdapter?, buffer: ByteBuffer?, bufferIndex: Int, bufferFlags: Int,
        sampleCount: Int, bufferPresentationTimeUs: Long, isDecodeOnlyBuffer: Boolean, isLastBuffer: Boolean, format: Format
    ): Boolean {
        val at = retimer?.timeFor(bufferPresentationTimeUs) ?: bufferPresentationTimeUs
        val done = super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex, bufferFlags, sampleCount,
            at, isDecodeOnlyBuffer, isLastBuffer, format)
        if (done) retimer?.shown(bufferPresentationTimeUs)
        return done
    }

    override fun resetCodecStateForFlush() {
        super.resetCodecStateForFlush()
        retimer?.reset()
        inCodec = 0
        wasReady = false
        // Сброс посреди показа оставляет декодер без картинки до следующего ключевого кадра: такое должно быть видно
        // в журнале (редкие остановки HD на ≈ 1,8 с, причина не найдена). При закрытии плеера сброс — обычное дело.
        if (state == STATE_STARTED) {
            Log.i("DoorbellVideo", "Декодер сброшен посреди показа: " +
                Thread.currentThread().stackTrace.drop(FLUSH_OWN_FRAMES).take(FLUSH_CALLERS).joinToString(" < ") { it.methodName })
        }
    }

    private companion object {
        /** В стеке вызовов пропускаются getStackTrace и сам этот метод; в журнал — столько вызвавших. */
        const val FLUSH_OWN_FRAMES = 3
        const val FLUSH_CALLERS = 5
    }

    /** Кадры, отправленные в декодер и ещё не вышедшие из него, и когда отправлен последний (пишет поток плеера). */
    @Volatile private var inCodec = 0
    @Volatile private var lastQueuedAtMs = 0L
    /** Когда из декодера вышел последний кадр (показан или выброшен). */
    @Volatile private var lastOutputAtMs = 0L
    private var wasReady = false

    override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        super.onProcessedOutputBuffer(presentationTimeUs)
        if (inCodec > 0) inCodec--
        lastOutputAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** Состояние декодера для журнала остановки (читается не из потока плеера — значения приблизительные). */
    fun decoderNote(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        fun ago(at: Long) = if (at == 0L) "—" else "${now - at} мс назад"
        return "в декодере кадров ${inCodec}, последний отправлен ${ago(lastQueuedAtMs)}, последний вышел ${ago(lastOutputAtMs)}" +
            if (freshest) ", программный" else ""
    }

    /**
     * Плеер считает рендерер готовым, только если есть декодированный кадр или непрочитанный кадр в очереди. У живого
     * потока очередь пуста (кадр уходит в декодер сразу), а программный декодер большого кадра отдаёт картинку не
     * мгновенно: пока он занят ключевым кадром, плеер останавливался «ждать данные» на 50–300 мс, хотя данные уже
     * в декодере. Для такого декодера ([LiveTuning.Mode.freshest]) рендерер готов, пока в декодере есть свежий кадр:
     * часы идут дальше, кадр покажется сразу, как будет готов.
     */
    override fun isReady(): Boolean {
        if (super.isReady()) { wasReady = true; return true }
        return freshest && wasReady &&
            LiveTuning.decoding(inCodec, android.os.SystemClock.elapsedRealtime() - lastQueuedAtMs)
    }

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        super.onQueueInputBuffer(buffer)
        retimer?.queued(buffer.timeUs)
        inCodec++
        lastQueuedAtMs = android.os.SystemClock.elapsedRealtime()
        if (!lowDelay || buffer.isEncrypted) return
        val data = buffer.data ?: return
        val size = data.limit()
        // Обычный кадр (без SPS) распознаётся по первым байтам и не копируется.
        if (size < 8 || !H264LowDelay.spsAt(data, size)) return
        val bytes = ByteArray(size)
        data.position(0)
        data.get(bytes)
        val fixed = H264LowDelay.rewriteAccessUnit(bytes)
        if (fixed != null && fixed.size <= data.capacity()) {
            data.clear()
            data.put(fixed)
            data.flip()
        } else {
            data.position(0)
        }
    }
}

/**
 * Настройка живого видео под декодер (docs/doorbell.md, «Задержка живого видео»).
 *
 * Аппаратный декодер: SPS без перестановки кадров — он отдаёт кадр сразу. Программный (основной поток больше
 * предела аппаратного): то же самое, и вдобавок кадр, готовый с опозданием, показывается, а не выбрасывается.
 * Обычное правило плеера «опоздал на 30 мс — не показывать» у декодера, работающего почти на пределе процессора,
 * оставляло на экране застывшую картинку: каждый следующий кадр тоже опаздывал. Свежий кадр с опозданием лучше
 * пропущенного; прыжок на более новый ключевой кадр при большом отставании остаётся за плеером.
 */
internal object LiveTuning {
    class Mode(val lowDelay: Boolean, val freshest: Boolean)

    /**
     * Декодер ещё работает над кадром: в нём есть кадры ([inCodec]), и последний отправлен недавно. Дольше
     * [DECODING_MS] без нового кадра — поток встал, это уже настоящая остановка.
     */
    fun decoding(inCodec: Int, sinceQueuedMs: Long) = inCodec > 0 && sinceQueuedMs in 0..DECODING_MS
    private const val DECODING_MS = 400L

    fun mode(h264: Boolean, softwareOnly: Boolean, softwareLowDelay: Boolean) =
        Mode(lowDelay = h264 && (!softwareOnly || softwareLowDelay), freshest = h264 && softwareOnly && softwareLowDelay)

    /**
     * Проверочные выключатели для сравнения на планшете — файлы в папке `video_debug` приложения на общей памяти,
     * читаются при каждом открытии видео:
     * - `sw_hold` — программному декодеру прежнее поведение (держит кадры, опоздавшие не показываются);
     * - `no_golive` — без рывка к живому ([LiveCatchUp.burstMs]);
     * - `no_retime` — показ строго по меткам камеры (без [FrameRetimer]);
     * - `no_jitter_floor` — запас без поправки на измеренную неровность прихода ([LiveCatchUp.floorFor]);
     * - `player_audio` — звук тем же плеером, что и видео (без отдельного плеера звука).
     */
    fun off(context: Context, name: String): Boolean =
        DebugSwitches.allowed(context) && context.getExternalFilesDir(null)?.let { File(File(it, "video_debug"), name).exists() } == true

    fun softwareHold(context: Context): Boolean = off(context, "sw_hold")
}
