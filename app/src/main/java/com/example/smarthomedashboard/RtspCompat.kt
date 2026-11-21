package com.example.smarthomedashboard

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Совместимость описания потока (SDP) камеры с RTSP-плеером Media3.
 *
 * Media3 1.11.1 отвергает всё описание целиком (`missing attribute fmtp` в `RtspMediaTrack`), если у дорожки
 * нет `a=fmtp` с нужными параметрами:
 * - **видео H.264 без `sprop-parameter-sets`** — так отдаёт субпоток Reolink (19.11.2026, московский планшет:
 *   у основного потока SPS/PPS в описании есть, у субпотока строки fmtp нет вовсе). SPS/PPS камера шлёт в самом
 *   потоке перед ключевыми кадрами: их один раз берёт [H264Params.probe] и они дописываются в описание;
 * - звук AAC без `config` — такая дорожка убирается (плитка звук не играет), видео идёт.
 * Запись разбирает описание камеры сама (SPS/PPS — из потока, AAC без `config` — без звука) и этой правки не видит.
 */
internal object Media3Sdp {
    private const val TAG = "RtspCompat"

    /**
     * Описание, которое примет Media3. [h264] — параметры видео H.264 (`a=fmtp` без номера), если в описании их нет;
     * null — оставить как есть. Последнюю дорожку не убираем — отказ плеера честнее пустого видео.
     */
    fun compatible(sdp: String, h264: (() -> String?)? = null): String {
        val lines = sdp.lines().map { it.trimEnd('\r') }
        val firstMedia = lines.indexOfFirst { it.startsWith("m=") }
        if (firstMedia < 0) return sdp
        val blocks = ArrayList<List<String>>()
        var i = firstMedia
        while (i < lines.size) {
            var j = i + 1
            while (j < lines.size && !lines[j].startsWith("m=")) j++
            blocks += lines.subList(i, j)
            i = j
        }
        var changed = false
        val fixed = blocks.map { block ->
            val track = SdpParser.parse(block.joinToString("\r\n")).firstOrNull()
            if (track == null || !lacksH264Params(track)) return@map block
            val params = h264?.invoke() ?: return@map block
            changed = true
            Log.i(TAG, "Видео H.264 без параметров в описании — дописаны SPS/PPS из потока камеры")
            withFmtp(block, track.payloadType, params)
        }
        val keep = fixed.filter { block ->
            val track = SdpParser.parse(block.joinToString("\r\n")).firstOrNull()
            track == null || !rejected(track)
        }.takeIf { it.isNotEmpty() } ?: fixed
        if (keep.size != fixed.size) {
            changed = true
            Log.i(TAG, "Убрана дорожка, которую плеер не примет (звук AAC без fmtp/config)")
        }
        if (!changed) return sdp
        val out = lines.take(firstMedia) + keep.flatten()
        return out.filter { it.isNotEmpty() }.joinToString("\r\n", postfix = "\r\n")
    }

    /** Ветка AAC в `RtspMediaTrack.generatePayloadFormat`: без fmtp — отказ; mpeg4-generic ещё и без config. */
    internal fun rejected(track: SdpTrack) = when (track.encoding) {
        "MPEG4-GENERIC" -> track.fmtp.isEmpty() || track.fmtp["config"].isNullOrEmpty()
        "MP4A-LATM" -> track.fmtp.isEmpty()
        else -> false
    }

    /** Ветка H.264: Media3 требует `sprop-parameter-sets` (из SPS берёт размер кадра для декодера). */
    internal fun lacksH264Params(track: SdpTrack) =
        track.isVideo && track.encoding == "H264" && track.fmtp["sprop-parameter-sets"].isNullOrEmpty()

    /** Строка fmtp дорожки заменяется (прежние параметры, кроме вычисленных, сохраняются) или добавляется после rtpmap. */
    private fun withFmtp(block: List<String>, payloadType: Int, params: String): List<String> {
        val prefix = "a=fmtp:$payloadType "
        val existing = block.firstOrNull { it.startsWith(prefix) }
        val ours = params.split(';').map { it.substringBefore('=').trim().lowercase(Locale.ROOT) }.toSet()
        val kept = existing?.removePrefix(prefix)?.split(';')?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.substringBefore('=').trim().lowercase(Locale.ROOT) !in ours }.orEmpty()
        val line = prefix + (kept + params).joinToString(";")
        if (existing != null) return block.map { if (it === existing) line else it }
        val rtpmap = block.indexOfFirst { it.startsWith("a=rtpmap:$payloadType ") }
        val at = if (rtpmap >= 0) rtpmap + 1 else block.size
        return block.take(at) + line + block.drop(at)
    }
}

/**
 * SPS/PPS видео H.264 из самого потока камеры — для описаний без `sprop-parameter-sets` (субпоток Reolink).
 * Запоминаются по адресу камеры до перезапуска приложения или неудачи видео ([forget]).
 */
internal object H264Params {
    private const val TAG = "RtspCompat"
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Параметры для [url]: из памяти или коротким отдельным подключением к камере (на потоке плеера). */
    fun forStream(url: String, user: String, password: String?): String? {
        if (url.isBlank()) return null
        cache[url]?.let { return it }
        return probe(url, user, password)?.also { cache[url] = it }
    }

    /** Уже известные параметры (например, увиденные ретранслятором в потоке). */
    fun remember(url: String, params: String) { if (url.isNotBlank()) cache[url] = params }

    fun cached(url: String): String? = cache[url]

    fun forget(url: String) { cache.remove(url) }

    /**
     * Своя сессия только с видео: ждёт SPS и PPS (камера шлёт их перед каждым ключевым кадром) не дольше
     * [timeoutMs] и закрывается. Неудача — null: плеер получит описание как есть и отработает обычную ошибку.
     */
    fun probe(url: String, user: String, password: String?, timeoutMs: Long = 6_000): String? {
        val session = RtspSession(url, user, password, connectTimeoutMs = 4_000, readTimeoutMs = timeoutMs.toInt())
        val collector = Collector()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        return try {
            session.open { it.isVideo && it.encoding == "H264" }
            session.read(object : RtspSession.Sink {
                override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {
                    RtpPacket.parse(packet, length)?.let { collector.push(it.payload, it.offset, it.length) }
                    if (collector.params != null || System.nanoTime() > deadline) session.close()
                }
            })
            collector.params
        } catch (e: Exception) {
            collector.params ?: run { Log.w(TAG, "SPS/PPS не получены: ${e.javaClass.simpleName}"); null }
        } finally {
            session.close()
        }
    }

    /** Собирает первые SPS (7) и PPS (8) из полезной нагрузки RTP (RFC 6184: одиночный NAL, STAP-A). */
    class Collector {
        private var sps: ByteArray? = null
        private var pps: ByteArray? = null

        /** Готовая строка параметров fmtp, когда есть оба. */
        val params: String?
            get() {
                val s = sps ?: return null
                val p = pps ?: return null
                if (s.size < 4) return null
                val profile = "%02X%02X%02X".format(s[1].toInt() and 0xFF, s[2].toInt() and 0xFF, s[3].toInt() and 0xFF)
                return "packetization-mode=1;profile-level-id=$profile;" +
                    "sprop-parameter-sets=${RecBase64.encode(s)},${RecBase64.encode(p)}"
            }

        fun push(data: ByteArray, offset: Int, length: Int) {
            if (length < 1) return
            when (data[offset].toInt() and 0x1F) {
                in 1..23 -> nal(data, offset, length)
                24 -> {
                    var at = offset + 1
                    val end = offset + length
                    while (at + 2 <= end) {
                        val size = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
                        at += 2
                        if (size <= 0 || at + size > end) break
                        nal(data, at, size)
                        at += size
                    }
                }
            }
        }

        private fun nal(data: ByteArray, offset: Int, length: Int) {
            when (data[offset].toInt() and 0x1F) {
                7 -> if (sps == null) sps = data.copyOfRange(offset, offset + length)
                8 -> if (pps == null) pps = data.copyOfRange(offset, offset + length)
            }
        }
    }
}

/**
 * Сокет RTSP-плеера: ответ на DESCRIBE проходит через [Media3Sdp], номера RTP-пакетов — через [RtpSequenceFix]
 * ([sequence]; null — номера камеры как есть). Остальное — байт в байт.
 */
internal class SdpFixingSocket(
    private val h264: (() -> String?)? = null,
    private val sequence: RtpSequenceFix? = RtpSequenceFix()
) : java.net.Socket() {
    private var wrapped: InputStream? = null

    @Synchronized
    override fun getInputStream(): InputStream = wrapped ?: SdpFixingInputStream(super.getInputStream(), h264)
        .let { fixed -> sequence?.let { RtpSequenceInputStream(fixed, it) } ?: fixed }
        .also { wrapped = it }
}

/** Читает ответы RTSP целиком, пока не встретит описание потока; дальше — прозрачная труба. */
internal class SdpFixingInputStream(
    private val source: InputStream,
    private val h264: (() -> String?)? = null
) : InputStream() {
    private var pending: ByteArray? = null
    private var position = 0
    private var passThrough = false

    override fun read(): Int {
        if (passThrough && pending == null) return source.read()
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        pending?.let { buffer ->
            val n = minOf(len, buffer.size - position)
            System.arraycopy(buffer, position, b, off, n)
            position += n
            if (position >= buffer.size) { pending = null; position = 0 }
            return n
        }
        if (passThrough) return source.read(b, off, len)
        val message = nextMessage() ?: return -1
        pending = message
        position = 0
        return read(b, off, len)
    }

    override fun available(): Int = pending?.let { it.size - position } ?: if (passThrough) source.available() else 0

    override fun close() = source.close()

    /** Один ответ RTSP (заголовки + тело). null — конец потока. */
    private fun nextMessage(): ByteArray? {
        val first = source.read()
        if (first < 0) return null
        // Пакет RTP поверх RTSP: описание уже было или его не будет.
        if (first == '$'.code) { passThrough = true; return byteArrayOf(first.toByte()) }
        val head = ByteArrayOutputStream()
        head.write(first)
        // Последние четыре байта: конец заголовков — «\r\n\r\n».
        var tail = first
        while (tail != BLANK_LINE) {
            val next = source.read()
            if (next < 0) return head.toByteArray()
            head.write(next)
            tail = (tail shl 8) or next
            if (head.size() > MAX_HEAD) { passThrough = true; return head.toByteArray() }
        }
        val headText = head.toString(Charsets.UTF_8.name())
        val headerLines = headText.split("\r\n").filter { it.isNotEmpty() }
        val length = header(headerLines, "content-length")?.toIntOrNull() ?: 0
        if (length <= 0) return head.toByteArray()
        if (length > MAX_BODY) { passThrough = true; return head.toByteArray() + readExactly(length) }
        val body = readExactly(length)
        if (header(headerLines, "content-type")?.contains("application/sdp", ignoreCase = true) != true) {
            return head.toByteArray() + body
        }
        passThrough = true
        val original = String(body, Charsets.UTF_8)
        val fixed = Media3Sdp.compatible(original, h264)
        if (fixed == original) return head.toByteArray() + body
        val fixedBytes = fixed.toByteArray(Charsets.UTF_8)
        val rebuilt = headerLines.map { line ->
            if (line.substringBefore(':').trim().equals("content-length", ignoreCase = true)) "Content-Length: ${fixedBytes.size}" else line
        }
        return (rebuilt.joinToString("\r\n", postfix = "\r\n\r\n")).toByteArray(Charsets.UTF_8) + fixedBytes
    }

    private fun readExactly(length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = source.read(out, read, length - read)
            if (n < 0) throw IOException("Ответ RTSP оборвался")
            read += n
        }
        return out
    }

    private fun header(lines: List<String>, name: String) = lines.drop(1)
        .firstOrNull { it.substringBefore(':').trim().lowercase(Locale.ROOT) == name }
        ?.substringAfter(':')?.trim()

    private companion object {
        const val BLANK_LINE = 0x0D0A0D0A
        const val MAX_HEAD = 16 * 1024
        const val MAX_BODY = 256 * 1024
    }
}
