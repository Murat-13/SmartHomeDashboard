package com.example.smarthomedashboard

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Обход ошибки Media3 на переходе номера RTP-пакета через ноль (docs/doorbell.md, «Проверка 21.11.2026 днём»).
 *
 * `RtpPacketReorderingQueue` (Media3 1.11.1) считает сдвиг номера как `min − max + 65535` вместо `+ 65536`: пакет № 0
 * сразу после № 65535 выходит «не новее предыдущего» и отбрасывается, а разборщик видео помечает кадр испорченным и не
 * отдаёт его декодеру. Основной поток камеры проходит 65 536 пакетов за ≈ 2,3 минуты, субпоток — за ≈ 27 минут: каждый
 * раз теряется кадр, а следующие до ключевого декодируются по чужой опоре.
 *
 * Поэтому до Media3 номера пакетов идут свои: с [FIRST], и задолго до 65535 — на границе кадров (предыдущий пакет
 * закрыл кадр битом marker) — отсчёт начинается заново. Скачок номера больше чем на 1000 очередь Media3 принимает как
 * новый отсчёт без ожидания и потерь, а разборщик сверяет номера только внутри разрезанного на пакеты кадра. Пропуски
 * в номерах камеры сохраняются: настоящую потерю пакета Media3 по-прежнему видит.
 *
 * Состояние — на один канал interleaved; вызывается потоком чтения плеера.
 */
internal class RtpSequenceFix(private val restarts: AtomicInteger? = null) {
    private val offset = IntArray(CHANNELS)
    private val last = IntArray(CHANNELS) { NONE }
    private val frameEnded = BooleanArray(CHANNELS)

    /** Номер пакета для Media3 вместо номера камеры [sequence]; [marker] — пакет закрывает кадр. */
    fun map(channel: Int, sequence: Int, marker: Boolean): Int {
        val c = channel and (CHANNELS - 1)
        var out = (sequence + offset[c]) and MASK
        val restart = when {
            last[c] == NONE -> true
            out >= RESTART_FROM && frameEnded[c] -> { restarts?.incrementAndGet(); true }
            // Поток без границ кадров: начинаем заново посреди кадра (один кадр пропадёт, как и без обхода).
            out >= FORCE_FROM -> { restarts?.incrementAndGet(); true }
            else -> false
        }
        if (restart) {
            offset[c] = (FIRST - sequence) and MASK
            out = FIRST
        }
        last[c] = out
        frameEnded[c] = marker
        return out
    }

    private companion object {
        const val CHANNELS = 256
        const val MASK = 0xFFFF
        const val NONE = -1
        /**
         * С какого номера идёт свой отсчёт. Не с 1: подключившись посреди кадра, Media3 пишет в журнал «Expected: 0;
         * received: <первый номер>» — с 1 это не отличить от самой ошибки перехода через ноль («received: 1»).
         */
        const val FIRST = 1_000
        /** С этого номера отсчёт начинается заново на первой же границе кадров. */
        const val RESTART_FROM = 32_768
        /** Скачок с номеров выше 64 535 очередь Media3 приняла бы за переход через ноль — до них не доходим. */
        const val FORCE_FROM = 64_000
    }
}

/**
 * Поток RTSP плеера с перенумерованными RTP-пакетами ([RtpSequenceFix]). Разбирает только границы: ответы RTSP
 * (заголовки и тело по Content-Length) и кадры `$ канал длина`; меняет два байта номера в заголовке RTP, остальное —
 * байт в байт. RTCP (тип 200–223) не трогает. Не смог разобрать поток — дальше прозрачная труба.
 */
internal class RtpSequenceInputStream(
    private val source: InputStream,
    private val fix: RtpSequenceFix = RtpSequenceFix()
) : InputStream() {
    private var state = START
    private var channel = 0
    /** Сколько байт пакета или тела ответа ещё впереди. */
    private var left = 0
    /** Номер следующего байта в заголовке кадра или в пакете. */
    private var at = 0
    private var rtp = false
    private var marker = false
    private var tail = 0
    private val head = ByteArrayOutputStream(256)
    /** Байт, прочитанный из источника сверх места в буфере читателя (младший байт номера). */
    private var carry = NONE
    private var transparent = false
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (carry != NONE) {
            b[off] = carry.toByte()
            carry = NONE
            return 1
        }
        val n = source.read(b, off, len)
        if (n <= 0 || transparent) return n
        return walk(b, off, n, len)
    }

    override fun available(): Int = (if (carry != NONE) 1 else 0) + source.available()

    override fun close() = source.close()

    /** Проходит [count] прочитанных байт; возвращает, сколько их стало (на один больше, если дочитан байт номера). */
    private fun walk(b: ByteArray, from: Int, count: Int, room: Int): Int {
        var i = from
        var end = from + count
        while (i < end && !transparent) {
            val v = b[i].toInt() and 0xFF
            when (state) {
                START -> {
                    if (v == DOLLAR) { state = FRAME; at = 0 } else { state = TEXT; head.reset(); head.write(v); tail = v }
                    i++
                }
                FRAME -> {
                    when (at) {
                        0 -> channel = v
                        1 -> left = v shl 8
                        else -> { left = left or v; state = if (left == 0) START else PACKET }
                    }
                    at = if (at == 2) 0 else at + 1
                    i++
                }
                PACKET -> when {
                    at == 0 -> { rtp = left >= RTP_HEADER && (v ushr 6) == RTP_VERSION; i++; at++; left-- }
                    // RFC 5761: типы 64–95 без старшего бита — RTCP (отчёты 200–207), номера пакета там нет.
                    at == 1 -> { if ((v and 0x7F) in RTCP_TYPES) rtp = false else marker = (v and 0x80) != 0; i++; at++; left-- }
                    at == 2 && rtp -> {
                        val inBuffer = i + 1 < end
                        val low = if (inBuffer) b[i + 1].toInt() and 0xFF else source.read()
                        if (low < 0) { transparent = true; break }
                        val out = fix.map(channel, (v shl 8) or low, marker)
                        b[i] = (out ushr 8).toByte()
                        when {
                            inBuffer -> { b[i + 1] = out.toByte(); i += 2 }
                            end < from + room -> { b[end] = out.toByte(); end++; i += 2 }
                            else -> { carry = out and 0xFF; i++ }
                        }
                        at += 2
                        left -= 2
                    }
                    else -> {
                        val skip = minOf(left, end - i)
                        i += skip
                        left -= skip
                        at += skip
                    }
                }
                TEXT -> {
                    head.write(v)
                    tail = (tail shl 8) or v
                    i++
                    if (head.size() > MAX_HEAD) transparent = true
                    else if (tail == BLANK_LINE) { left = contentLength(); state = if (left > 0) BODY else START }
                }
                else -> { // BODY
                    val skip = minOf(left, end - i)
                    i += skip
                    left -= skip
                }
            }
            if ((state == PACKET || state == BODY) && left == 0) state = START
        }
        return end - from
    }

    private fun contentLength(): Int = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        .firstOrNull { it.substringBefore(':').trim().lowercase(Locale.ROOT) == "content-length" }
        ?.substringAfter(':')?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private companion object {
        const val START = 0
        const val FRAME = 1
        const val PACKET = 2
        const val TEXT = 3
        const val BODY = 4
        const val NONE = -1
        const val DOLLAR = '$'.code
        const val RTP_HEADER = 12
        const val RTP_VERSION = 2
        val RTCP_TYPES = 64..95
        const val BLANK_LINE = 0x0D0A0D0A
        const val MAX_HEAD = 16 * 1024
    }
}
