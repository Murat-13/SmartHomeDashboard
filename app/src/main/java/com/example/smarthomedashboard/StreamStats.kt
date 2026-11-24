package com.example.smarthomedashboard

import android.util.Log

/**
 * Сводка потока камеры для диагностики (docs/diagnostics-tz.md, часть 1): несколько чисел в памяти, без файлов.
 * Пишет поток чтения камеры (общий поток [SharedStream] или проверка [StreamProbe]), читает диагностика.
 *
 * - Ключевые кадры: размер, как часто и за сколько доходят от первого пакета до последнего. Большой ключевой кадр,
 *   идущий ≈ 1 МБ/с, — признак линии 10 Мбит/с (случай 24.11.2026, docs/doorbell.md).
 * - Потери пакетов — по номерам RTP.
 * - Шаг меток времени между кадрами: камера, отдающая 20 к/с с датчика 25 к/с, даёт шаги 40 и 80 мс.
 * - Неровность прихода: на сколько самый поздний кадр минуты пришёл позже обычного (приход минус метка кадра).
 */
internal class StreamStats(private val clockRate: Int = 90_000) {
    private val unwrapper = RtpTimestampUnwrapper()

    // Текущий кадр (между первым пакетом с новой меткой и пакетом с маркером).
    private var frameTs = Long.MIN_VALUE
    private var firstNs = 0L
    private var bytes = 0
    private var packets = 0
    /** В кадре был пакет ключевого кадра ([RtpKey]): так ключевой виден и тогда, когда часть его пакетов потеряна. */
    private var frameKey = false

    // Потери.
    private var expectedSeq = -1
    private var received = 0L
    private var lost = 0L

    // Кадры — кольцом: приход (мс, монотонные часы) и метка (мс), размер.
    private val arrival = LongArray(FRAMES)
    private val pts = LongArray(FRAMES)
    private val size = IntArray(FRAMES)
    private var frames = 0L
    private var startedMs = -1L
    private var lastMs = 0L

    // Ключевые кадры — кольцом.
    private val keyPts = LongArray(KEYS)
    private val keyBytes = IntArray(KEYS)
    private val keySpanUs = LongArray(KEYS)
    private val keyPackets = IntArray(KEYS)
    private var keys = 0

    // Шаги меток между соседними кадрами, мс.
    private val steps = IntArray(STEPS)
    private var stepCount = 0
    private var lastPts = Long.MIN_VALUE

    // Неровность по минутам.
    private var minuteStartMs = -1L
    private var minuteFirstFrame = 0L
    private val minuteLate = LongArray(MINUTES)
    private var minuteCount = 0

    private var codec: VideoDepacketizer.Codec? = null
    private var width = 0
    private var height = 0

    val hasCodec @Synchronized get() = codec != null

    /**
     * Пакет видео [length] байт с номером [seq] и меткой [rtpTs] прочитан в [nowNs] ([System.nanoTime]); [key] — пакет
     * ключевого кадра ([RtpKey]).
     */
    @Synchronized
    fun packet(seq: Int, rtpTs: Long, length: Int, nowNs: Long, key: Boolean = false) {
        if (expectedSeq >= 0 && seq != expectedSeq) {
            val gap = (seq - expectedSeq) and 0xFFFF
            // Большой скачок — новая нумерация (переподключение камеры), а не потеря.
            if (gap in 1 until MAX_GAP) lost += gap
        }
        expectedSeq = (seq + 1) and 0xFFFF
        received++
        if (rtpTs != frameTs) { frameTs = rtpTs; firstNs = nowNs; bytes = 0; packets = 0; frameKey = false }
        bytes += length
        packets++
        if (key) frameKey = true
    }

    /** Последний пакет кадра [rtpTs] прочитан в [nowNs]; [key] — ключевой кадр (по разбору; без него — по пакетам). */
    @Synchronized
    fun frameEnd(rtpTs: Long, nowNs: Long, key: Boolean = false) {
        val nowMs = nowNs / 1_000_000
        val ptsMs = unwrapper.unwrap(rtpTs) * 1000 / clockRate
        if (startedMs < 0) { startedMs = nowMs; minuteStartMs = nowMs }
        lastMs = nowMs
        val i = (frames % FRAMES).toInt()
        arrival[i] = nowMs
        pts[i] = ptsMs
        size[i] = if (rtpTs == frameTs) bytes else 0
        frames++
        if (lastPts != Long.MIN_VALUE) {
            val step = ptsMs - lastPts
            if (step in 1..MAX_STEP_MS) { steps[stepCount % STEPS] = step.toInt(); stepCount++ }
        }
        lastPts = ptsMs
        if ((key || frameKey) && rtpTs == frameTs) {
            val k = keys % KEYS
            keyPts[k] = ptsMs
            keyBytes[k] = bytes
            keySpanUs[k] = (nowNs - firstNs) / 1000
            keyPackets[k] = packets
            keys++
        }
        frameTs = Long.MIN_VALUE
        if (nowMs - minuteStartMs >= MINUTE_MS) {
            late(minuteFirstFrame, frames)?.let { minuteLate[minuteCount % MINUTES] = it; minuteCount++ }
            minuteStartMs = nowMs
            minuteFirstFrame = frames
        }
    }

    /** Параметры потока из SPS (без стартового кода, с заголовком NAL). */
    @Synchronized
    fun codec(codec: VideoDepacketizer.Codec, sps: ByteArray?) {
        this.codec = codec
        val dims = sps?.let { VideoSize.parse(codec, it) } ?: return
        width = dims.first
        height = dims.second
    }

    /** Самый поздний кадр отрезка [from, to) — позже обычного (медианы) на столько мс; null — кадров мало. */
    private fun late(from: Long, to: Long): Long? {
        val start = maxOf(from, to - FRAMES)
        val n = (to - start).toInt()
        if (n < MIN_FRAMES) return null
        val d = LongArray(n) { val i = ((start + it) % FRAMES).toInt(); arrival[i] - pts[i] }
        d.sort()
        return d[n - 1] - d[n / 2]
    }

    @Synchronized
    fun snapshot(): StreamSnapshot {
        val observed = if (startedMs < 0) 0 else lastMs - startedMs
        // Частота и битрейт — за последнюю минуту (или сколько есть).
        val n = minOf(frames, FRAMES.toLong()).toInt()
        var fps: Double? = null
        var kbps: Long? = null
        if (n >= MIN_FRAMES) {
            val last = ((frames - 1) % FRAMES).toInt()
            var count = 0
            var sum = 0L
            var oldest = arrival[last]
            for (back in 0 until n) {
                val i = ((frames - 1 - back) % FRAMES).toInt()
                if (arrival[last] - arrival[i] > MINUTE_MS) break
                oldest = arrival[i]; count++; sum += size[i]
            }
            val spanMs = arrival[last] - oldest
            if (spanMs >= MIN_SPAN_MS && count > 1) {
                fps = (count - 1) * 1000.0 / spanMs
                kbps = sum * 8 / spanMs
            }
        }
        // Ключевые кадры.
        val k = minOf(keys, KEYS)
        val keyIntervalMs = if (k >= 2) {
            val ordered = (0 until k).map { keyPts[(keys - k + it) % KEYS] }
            median(ordered.zipWithNext { a, b -> b - a }.filter { it > 0 })
        } else null
        val big = (0 until k).map { (keys - k + it) % KEYS }.filter { keyBytes[it] >= BIG_KEY_BYTES && keyPackets[it] >= MIN_KEY_PACKETS }
        val keyKb = median(big.map { keyBytes[it].toLong() / 1024 })
        val keyKbAll = median((0 until k).map { keyBytes[(keys - k + it) % KEYS].toLong() / 1024 })
        val keySpanMs = median(big.map { keySpanUs[it] / 1000 })
        val keyRate = median(big.mapNotNull { j -> keySpanUs[j].takeIf { it > 0 }?.let { keyBytes[j] * 1_000_000L / it / 1024 } })
        // Шаги меток: шаг датчика — самый короткий из частых (от 10 %); пропуски кадров датчика — шаги в 2–3 раза длиннее.
        // При 15 к/с с датчика 25 шаги 40 и 80 мс, и 80 встречается чаще — по самому частому датчик казался бы 12,5 к/с.
        val s = minOf(stepCount, STEPS)
        var baseStep: Int? = null
        var skipShare: Double? = null
        if (s >= MIN_STEPS) {
            val counts = HashMap<Int, Int>()
            for (j in 0 until s) { val v = (steps[j] + 5) / 10 * 10; counts[v] = (counts[v] ?: 0) + 1 }
            val base = counts.filter { it.key > 0 && it.value * 10 >= s }.keys.minOrNull()
            if (base != null) {
                baseStep = base
                val skips = (0 until s).count { j -> listOf(2, 3).any { k -> kotlin.math.abs(steps[j] - k * base) <= base / 4 } }
                skipShare = skips.toDouble() / s
            }
        }
        // Неровность: медиана по полным минутам; минут ещё нет — текущий отрезок (от 10 с).
        val m = minOf(minuteCount, MINUTES)
        val lateMs = if (m > 0) median((0 until m).map { minuteLate[(minuteCount - m + it) % MINUTES] })
            else if (lastMs - minuteStartMs >= MIN_SPAN_MS) late(minuteFirstFrame, frames) else null
        val total = received + lost
        return StreamSnapshot(
            observedMs = observed, codec = codec, width = width, height = height, fps = fps, kbps = kbps,
            keyIntervalMs = keyIntervalMs, keyKbAll = keyKbAll?.toInt(), keySamples = big.size, keyKb = keyKb?.toInt(), keySpanMs = keySpanMs,
            keyRateKbPerSec = keyRate, packets = total, lossPercent = if (total >= MIN_PACKETS) lost * 100.0 / total else null,
            baseStepMs = baseStep, skipStepShare = skipShare, lateMs = lateMs
        )
    }

    private fun median(values: List<Long>): Long? = if (values.isEmpty()) null else values.sorted()[values.size / 2]

    companion object {
        /** Кадров в памяти: минута при 60 к/с. */
        private const val FRAMES = 4096
        private const val KEYS = 32
        private const val STEPS = 600
        private const val MINUTES = 10
        private const val MINUTE_MS = 60_000L
        private const val MIN_FRAMES = 20
        /** Частота, битрейт и неровность — не меньше чем за 10 с (проверка по кнопке длится 30 с). */
        private const val MIN_SPAN_MS = 10_000L
        private const val MIN_STEPS = 100
        private const val MIN_PACKETS = 1_000L
        private const val MAX_GAP = 1_000
        private const val MAX_STEP_MS = 2_000L
        /** Ключевой кадр, по скорости которого судим о линии: от 100 КБ и 12 пакетов (субпоток мельче). */
        const val BIG_KEY_BYTES = 100 * 1024
        private const val MIN_KEY_PACKETS = 12
    }
}

/**
 * Пакет ключевого кадра — по заголовку NAL в самом пакете RTP (RFC 6184, RFC 7798), без сборки кадра: при потерях
 * разборщик отбрасывает испорченный ключевой кадр целиком, и интервал ключевых кадров казался бы вдвое-втрое больше.
 */
internal object RtpKey {
    fun isKey(h265: Boolean, payload: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 2) return false
        val b0 = payload[offset].toInt() and 0xFF
        return if (!h265) {
            when (val type = b0 and 0x1F) {
                5, 7 -> true
                24 -> length > 3 && (payload[offset + 3].toInt() and 0x1F).let { it == 5 || it == 7 }
                28 -> (payload[offset + 1].toInt() and 0x1F) == 5
                else -> type == 5
            }
        } else {
            fun irap(t: Int) = t in 16..21 || t in 32..34
            when (val type = (b0 shr 1) and 0x3F) {
                48 -> length > 4 && irap(((payload[offset + 4].toInt() and 0xFF) shr 1) and 0x3F)
                49 -> length > 2 && ((payload[offset + 2].toInt() and 0x3F) in 16..21)
                else -> irap(type)
            }
        }
    }
}

/** Сводка потока на момент запроса (см. [StreamStats]). null в полях — не измерено. */
internal data class StreamSnapshot(
    /** Сколько мс собиралась сводка. */
    val observedMs: Long,
    val codec: VideoDepacketizer.Codec?,
    val width: Int,
    val height: Int,
    val fps: Double?,
    val kbps: Long?,
    /** Ключевой кадр раз в столько мс (по меткам камеры). */
    val keyIntervalMs: Long?,
    /** Размер ключевого кадра (медиана по всем), КБ. */
    val keyKbAll: Int?,
    /** Больших ключевых кадров (от 100 КБ) в сводке, их размер, за сколько и с какой скоростью доходят. */
    val keySamples: Int,
    val keyKb: Int?,
    val keySpanMs: Long?,
    val keyRateKbPerSec: Long?,
    val packets: Long,
    val lossPercent: Double?,
    /** Шаг датчика (самый короткий из частых шагов меток) и доля шагов в 2–3 раза длиннее (пропуски кадров датчика). */
    val baseStepMs: Int?,
    val skipStepShare: Double?,
    /** Самый поздний кадр минуты позже обычного, мс. */
    val lateMs: Long?
)

/** Ширина и высота картинки из SPS H.264 и H.265 (NAL с заголовком, без стартового кода). */
internal object VideoSize {
    fun parse(codec: VideoDepacketizer.Codec, sps: ByteArray): Pair<Int, Int>? = try {
        when (codec) {
            VideoDepacketizer.Codec.H264 -> h264(sps)
            VideoDepacketizer.Codec.H265 -> h265(sps)
        }
    } catch (e: RuntimeException) {
        Log.w("VideoSize", "SPS не разобран: ${e.javaClass.simpleName}")
        null
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private fun h264(nal: ByteArray): Pair<Int, Int>? {
        if (nal.size < 5 || (nal[0].toInt() and 0x1F) != 7) return null
        val r = H264LowDelay.BitReader(H264LowDelay.unescape(nal, 1, nal.size - 1))
        val profile = r.u(8)
        r.skip(16)
        r.ue()
        var chroma = 1
        if (profile in HIGH_PROFILES) {
            chroma = r.ue()
            if (chroma == 3) r.skip(1)
            r.ue(); r.ue(); r.skip(1)
            if (r.u(1) == 1) for (i in 0 until if (chroma != 3) 8 else 12) if (r.u(1) == 1) H264LowDelay.scalingList(r, if (i < 6) 16 else 64)
        }
        r.ue()
        when (r.ue()) {
            0 -> r.ue()
            1 -> { r.skip(1); r.se(); r.se(); val cycle = r.ue(); require(cycle <= 255); repeat(cycle) { r.se() } }
        }
        r.ue(); r.skip(1)
        val widthMbs = r.ue() + 1
        val heightUnits = r.ue() + 1
        val frameMbsOnly = r.u(1)
        if (frameMbsOnly == 0) r.skip(1)
        r.skip(1)
        var w = widthMbs * 16
        var h = (2 - frameMbsOnly) * heightUnits * 16
        if (r.u(1) == 1) {
            val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
            val unitX = if (chroma == 1 || chroma == 2) 2 else 1
            val unitY = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
            w -= unitX * (left + right)
            h -= unitY * (top + bottom)
        }
        return if (w > 0 && h > 0) w to h else null
    }

    private fun h265(nal: ByteArray): Pair<Int, Int>? {
        if (nal.size < 6 || ((nal[0].toInt() shr 1) and 0x3F) != 33) return null
        val r = H264LowDelay.BitReader(H264LowDelay.unescape(nal, 2, nal.size - 2))
        r.skip(4)
        val maxSubLayersMinus1 = r.u(3)
        r.skip(1)
        // profile_tier_level: общий профиль (88 бит) и уровень (8), затем подуровни.
        r.skip(96)
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) { profilePresent[i] = r.u(1) == 1; levelPresent[i] = r.u(1) == 1 }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) r.skip(2)
        for (i in 0 until maxSubLayersMinus1) { if (profilePresent[i]) r.skip(88); if (levelPresent[i]) r.skip(8) }
        r.ue()
        val chroma = r.ue()
        if (chroma == 3) r.skip(1)
        var w = r.ue()
        var h = r.ue()
        if (r.u(1) == 1) {
            val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
            val subW = if (chroma == 1 || chroma == 2) 2 else 1
            val subH = if (chroma == 1) 2 else 1
            w -= subW * (left + right)
            h -= subH * (top + bottom)
        }
        return if (w > 0 && h > 0) w to h else null
    }
}

/**
 * Проверка потока по кнопке «Проверить сейчас», если общего потока нет (запись на этом планшете выключена): своё
 * подключение к камере на [seconds] с, только видео, сводка [StreamStats]. Блокирующее — рабочий поток.
 */
internal object StreamProbe {
    internal var sessionFactory: (String, String, String?) -> RtspSession = { url, user, password -> RtspSession(url, user, password) }

    fun run(url: String, user: String, password: String?, seconds: Int, cancelled: () -> Boolean): StreamSnapshot {
        val session = sessionFactory(url, user, password)
        val stop = Thread({
            val until = System.nanoTime() + seconds * 1_000_000_000L
            try {
                while (System.nanoTime() < until && !cancelled()) Thread.sleep(200)
            } catch (_: InterruptedException) {}
            session.close()
        }, "diag-probe-stop").apply { isDaemon = true; start() }
        val setups = try {
            session.open { it.isVideo && (it.encoding == "H264" || it.encoding == "H265" || it.encoding == "HEVC") }
        } catch (e: Exception) {
            stop.interrupt(); session.close(); throw e
        }
        val video = setups.firstOrNull { it.track.isVideo } ?: run { stop.interrupt(); session.close(); throw RtspException("Камера не отдаёт H.264/H.265") }
        val depacketizer = VideoDepacketizer.create(video.track)
            ?: run { stop.interrupt(); session.close(); throw RtspException("Кодек не поддерживается") }
        val stats = StreamStats(video.track.clockRate.takeIf { it > 0 } ?: 90_000)
        val h265 = depacketizer.codec == VideoDepacketizer.Codec.H265
        try {
            session.read(object : RtspSession.Sink {
                private var keyTs = -1L
                override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {
                    if (setup !== video) return
                    val now = System.nanoTime()
                    val rtp = RtpPacket.parse(packet, length) ?: return
                    stats.packet(rtp.sequence, rtp.timestamp, rtp.length, now, RtpKey.isKey(h265, rtp.payload, rtp.offset, rtp.length))
                    // Метка кадра у разборщика — развёрнутая (64 бита), у пакета — 32 бита.
                    for (frame in depacketizer.push(rtp)) if (frame.key) keyTs = frame.timestamp and 0xFFFFFFFFL
                    if (!stats.hasCodec && depacketizer.sps != null) stats.codec(depacketizer.codec, depacketizer.sps)
                    if (rtp.marker) stats.frameEnd(rtp.timestamp, now, keyTs == rtp.timestamp)
                }
            })
        } catch (e: Exception) {
            // Закрыли по времени — так и задумано; иначе — ошибка камеры, если ничего не успели собрать.
            if (stats.snapshot().observedMs == 0L && !cancelled()) { stop.interrupt(); session.close(); throw e }
        } finally {
            stop.interrupt()
            session.close()
        }
        return stats.snapshot()
    }
}
