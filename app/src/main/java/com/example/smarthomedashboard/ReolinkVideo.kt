package com.example.smarthomedashboard

import android.os.SystemClock
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** Источник кадров общего потока камеры ([SharedStream]): RTSP ([RtspSession]) или протокол Reolink ([ReolinkVideoSession]). */
internal interface FeedSession : java.io.Closeable {
    val sdp: String
    fun open(wanted: (SdpTrack) -> Boolean): List<RtspSession.Setup>
    fun read(sink: RtspSession.Sink)
}

/**
 * Живое видео камер Reolink по их протоколу (docs/doorbell.md, «Видео по протоколу Reolink»): те же кадры приходят на 60–70 мс раньше,
 * чем по RTSP, и ровнее, а первый кадр — сразу (камера начинает с ключевого). Только показ основного потока (раскрытая карточка, окно
 * звонка): запись, звук и события — по RTSP, как были; не вышло — показ тем же путём, что раньше, сразу.
 */
internal object ReolinkVideo {
    private const val SCHEME = "baichuan://"
    /** На сколько картинка этим путём приходит раньше, чем по RTSP: звук идёт по RTSP и догоняет её ([SoundLate]). Замер 25.11.2026. */
    const val LEAD_MS = 60L

    private class Hold(val failures: Int, val until: Long)
    private val holds = ConcurrentHashMap<String, Hold>()
    internal var clock: () -> Long = SystemClock::elapsedRealtime
    // Первая пауза короткая: камера изредка не успевает отдать картинку за отведённое время (на планшете — 2 раза за сутки 25.11.2026,
    // оба — вместе с открытием канала голоса), а минута показа запасным путём — это первый кадр через ≈ 1 с вместо 0,2 с при
    // каждом раскрытии. Подводит снова — паузы растут, как раньше.
    private val PAUSES = longArrayOf(15_000L, 60_000L, 10 * 60_000L, 30 * 60_000L)

    /**
     * Адрес основного потока по протоколу Reolink; null — не Reolink или субпоток: у плитки запас держит сам аппаратный декодер, и
     * выигрыша нет (замер 25.11.2026).
     */
    fun urlFor(rtsp: String): String? {
        if (!TalkChannels.looksReolink(rtsp)) return null
        val host = runCatching { java.net.URI(rtsp.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return if (rtsp.contains("_main", true)) "$SCHEME$host/main" else null
    }

    fun isFast(url: String) = url.startsWith(SCHEME)

    /** Источник для общего потока [url]. */
    fun feed(url: String, user: String, password: String?): FeedSession =
        if (isFast(url)) ReolinkVideoSession(url.removePrefix(SCHEME).substringBefore('/'), user, password.orEmpty())
        else RtspSession(url, user, password)

    /** Путь недавно подвёл — пока показываем как раньше. */
    fun held(url: String) = holds[url]?.let { clock() < it.until } == true

    fun refused(url: String, why: String = "") {
        val failures = (holds[url]?.failures ?: 0) + 1
        val pause = PAUSES[minOf(failures, PAUSES.size) - 1]
        holds[url] = Hold(failures, clock() + pause)
        Log.i("ReolinkVideo", "Видео по протоколу Reolink не вышло ($failures${if (why.isEmpty()) "" else ", $why"}) — следующая попытка через " +
            if (pause < 60_000L) "${pause / 1000} с" else "${pause / 60_000} мин")
    }

    fun worked(url: String) { holds.remove(url) }
    internal fun forget() { holds.clear(); clock = SystemClock::elapsedRealtime }
}

/** Разбор потока Reolink (BcMedia) в кадры: куски приходят как попало, кадр отдаётся целиком. */
internal class BcMediaParser {
    class Frame(val h265: Boolean, val key: Boolean, val stampUs: Long, val data: ByteArray)

    private var buffer = ByteArray(1 shl 20)
    private var start = 0
    private var end = 0
    private var lastUs = -1L
    private var wraps = 0L

    /** Добавить кусок; готовые кадры видео (звук и сведения о потоке пропускаются). Мусор — [IOException]. */
    fun push(chunk: ByteArray, onFrame: (Frame) -> Unit) {
        if (end + chunk.size > buffer.size) compact(chunk.size)
        System.arraycopy(chunk, 0, buffer, end, chunk.size)
        end += chunk.size
        while (end - start >= 8) {
            val b = ByteBuffer.wrap(buffer, start, end - start).slice().order(ByteOrder.LITTLE_ENDIAN)
            val magic = b.getInt(0)
            val total: Int
            var frame: (() -> Frame)? = null
            when (magic) {
                INFO_V1, INFO_V2 -> total = INFO_SIZE
                in IFRAME..IFRAME + CAMERAS, in PFRAME..PFRAME + CAMERAS -> {
                    if (end - start < VIDEO_HEAD) return
                    val size = b.getInt(8); val extra = b.getInt(12)
                    if (size <= 0 || size > MAX_FRAME || extra < 0 || extra > MAX_EXTRA) throw IOException("Непонятный кадр Reolink")
                    total = VIDEO_HEAD + extra + size + pad(size)
                    val at = start + VIDEO_HEAD + extra
                    val codec = b.getInt(4); val us = b.getInt(16).toLong() and 0xFFFFFFFFL
                    frame = { Frame(codec == H265, magic in IFRAME..IFRAME + CAMERAS, unwrap(us), buffer.copyOfRange(at, at + size)) }
                }
                AAC, ADPCM -> { val size = b.getShort(4).toInt() and 0xFFFF; total = SOUND_HEAD + size + pad(size) }
                else -> throw IOException("Поток Reolink сбился")
            }
            if (end - start < total) return
            val ready = frame?.invoke()
            start += total
            ready?.let(onFrame)
        }
    }

    private fun compact(more: Int) {
        val left = end - start
        if (left + more > MAX_FRAME * 2) throw IOException("Слишком большой кадр Reolink")
        val target = if (left + more > buffer.size) ByteArray(maxOf(buffer.size * 2, left + more)) else buffer
        System.arraycopy(buffer, start, target, 0, left)
        buffer = target; start = 0; end = left
    }

    private fun pad(size: Int) = (8 - size % 8) % 8

    /** Метка камеры — 32 бита микросекунд (переполнение раз в 71 мин). */
    private fun unwrap(us: Long): Long {
        if (lastUs >= 0 && us < lastUs - (1L shl 31)) wraps += 1L shl 32
        lastUs = us
        return wraps + us
    }

    private companion object {
        const val INFO_V1 = 0x31303031
        const val INFO_V2 = 0x32303031
        const val IFRAME = 0x63643030
        const val PFRAME = 0x63643130
        const val CAMERAS = 9
        const val AAC = 0x62773530
        const val ADPCM = 0x62773130
        const val H265 = 0x35363248
        const val INFO_SIZE = 32
        const val VIDEO_HEAD = 24
        const val SOUND_HEAD = 8
        const val MAX_EXTRA = 1024
        const val MAX_FRAME = 8 shl 20
    }
}

/**
 * Сеанс видео по протоколу Reolink (порт 9000; вход — как у разговора, [BaichuanTalkSession]): кадры превращаются в те же пакеты RTP,
 * что приходят по RTSP, поэтому дальше — общий поток, ретранслятор и плеер без изменений. Раз в [REPORT_NS] — отчёт RTCP с метками
 * камеры: по нему общий поток меряет неровность прихода.
 */
internal class ReolinkVideoSession(private val host: String, private val user: String, private val password: String,
                                   private val port: Int = BaichuanTalkSession.PORT, private val readTimeoutMs: Int = 5_000) : FeedSession {
    override var sdp = ""
        private set
    private var socket: Socket? = null
    private lateinit var link: BcLink
    private val parser = BcMediaParser()
    private val waiting = ArrayDeque<BcMediaParser.Frame>()
    private lateinit var setup: RtspSession.Setup
    private var h265 = false
    @Volatile private var closed = false
    private var sequence = 0
    private var packets = 0
    private var bytes = 0L
    private var firstUs = -1L
    private var firstWallMs = 0L
    private var lastReport = 0L
    private var lastPing = 0L

    override fun open(wanted: (SdpTrack) -> Boolean): List<RtspSession.Setup> {
        val address = InetSocketAddress(host, port)
        val s = Socket()
        socket = s
        s.connect(address, CONNECT_MS)
        s.tcpNoDelay = true
        s.soTimeout = readTimeoutMs
        link = BcLink(s)
        link.login(user, password)
        link.startVideo()
        val until = System.nanoTime() + FIRST_KEY_NS
        while (true) {
            val frame = next()
            if (System.nanoTime() > until) throw RtspException("Камера не прислала ключевой кадр (Reolink)")
            if (!frame.key) continue
            h265 = frame.h265
            val nals = AnnexB.split(frame.data)
            sdp = describe(nals) ?: continue
            setup = RtspSession.Setup(SdpParser.parse(sdp).single().takeIf(wanted) ?: throw RtspException("Кодек не поддерживается"), 0)
            waiting.addFirst(frame)
            return listOf(setup)
        }
    }

    private fun describe(nals: List<ByteArray>): String? {
        fun b64(n: ByteArray) = RecBase64.encode(n)
        val head = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=reolink\r\nt=0 0\r\na=control:*\r\nm=video 0 RTP/AVP $PAYLOAD\r\na=control:track0\r\n"
        if (h265) {
            fun of(type: Int) = nals.firstOrNull { ((it[0].toInt() shr 1) and 0x3F) == type }
            val vps = of(32) ?: return null; val sps = of(33) ?: return null; val pps = of(34) ?: return null
            return head + "a=rtpmap:$PAYLOAD H265/90000\r\na=fmtp:$PAYLOAD sprop-vps=${b64(vps)};sprop-sps=${b64(sps)};sprop-pps=${b64(pps)}\r\n"
        }
        val sps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 7 && it.size >= 4 } ?: return null
        val pps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 8 } ?: return null
        return head + "a=rtpmap:$PAYLOAD H264/90000\r\na=fmtp:$PAYLOAD packetization-mode=1;profile-level-id=%02X%02X%02X;sprop-parameter-sets=${b64(sps)},${b64(pps)}\r\n"
            .format(sps[1], sps[2], sps[3])
    }

    /** Следующий кадр видео; попутно — ответы на запросы камеры и «пинг». */
    private fun next(): BcMediaParser.Frame {
        while (waiting.isEmpty()) {
            if (closed) throw RtspException("Сессия закрыта")
            val now = System.nanoTime()
            if (now - lastPing > PING_NS) { lastPing = now; link.ping() }
            link.readVideo()?.let { parser.push(it, waiting::addLast) }
        }
        return waiting.removeFirst()
    }

    override fun read(sink: RtspSession.Sink) {
        while (!closed) emit(next(), sink)
    }

    private fun emit(frame: BcMediaParser.Frame, sink: RtspSession.Sink) {
        if (frame.h265 != h265) throw RtspException("Камера сменила кодек")
        if (firstUs < 0) { firstUs = frame.stampUs; firstWallMs = System.currentTimeMillis() }
        val ts = frame.stampUs * 9 / 100
        val now = System.nanoTime()
        if (lastReport == 0L || now - lastReport > REPORT_NS) { lastReport = now; report(frame.stampUs, ts, sink) }
        val nals = AnnexB.split(frame.data).filter { it.size > (if (h265) 2 else 1) }
        for ((i, nal) in nals.withIndex()) send(nal, ts, i == nals.lastIndex, sink)
    }

    private fun send(nal: ByteArray, ts: Long, last: Boolean, sink: RtspSession.Sink) {
        if (nal.size <= MTU) { packet(ts, last, sink, nal.size) { System.arraycopy(nal, 0, it, RTP, nal.size) }; return }
        val head = if (h265) 2 else 1
        var at = head
        while (at < nal.size) {
            val n = minOf(MTU, nal.size - at)
            val first = at == head
            val final = at + n == nal.size
            packet(ts, last && final, sink, n + head + 1) { p ->
                val flags = (if (first) 0x80 else 0) or (if (final) 0x40 else 0)
                if (h265) {
                    p[RTP] = ((nal[0].toInt() and 0x81) or (FU_H265 shl 1)).toByte(); p[RTP + 1] = nal[1]
                    p[RTP + 2] = (flags or ((nal[0].toInt() shr 1) and 0x3F)).toByte()
                } else {
                    p[RTP] = ((nal[0].toInt() and 0xE0) or FU_H264).toByte()
                    p[RTP + 1] = (flags or (nal[0].toInt() and 0x1F)).toByte()
                }
                System.arraycopy(nal, at, p, RTP + head + 1, n)
            }
            at += n
        }
    }

    private val buffer = ByteArray(RTP + MTU + 8)

    private inline fun packet(ts: Long, marker: Boolean, sink: RtspSession.Sink, size: Int, fill: (ByteArray) -> Unit) {
        val p = buffer
        p[0] = 0x80.toByte()
        p[1] = ((if (marker) 0x80 else 0) or PAYLOAD).toByte()
        p[2] = (sequence shr 8).toByte(); p[3] = sequence.toByte()
        sequence = (sequence + 1) and 0xFFFF
        put32(p, 4, ts); put32(p, 8, SSRC)
        fill(p)
        packets++; bytes += size
        sink.onRtp(setup, p, RTP + size)
    }

    /** Отчёт отправителя RTCP: метка RTP ↔ время; время — по меткам камеры от первого кадра. */
    private fun report(us: Long, ts: Long, sink: RtspSession.Sink) {
        val wall = firstWallMs + (us - firstUs) / 1000
        val p = ByteArray(28)
        p[0] = 0x80.toByte(); p[1] = 200.toByte(); p[3] = 6
        put32(p, 4, SSRC)
        put32(p, 8, wall / 1000 + NTP_TO_UNIX_S)
        put32(p, 12, (wall % 1000 shl 32) / 1000)
        put32(p, 16, ts); put32(p, 20, packets.toLong()); put32(p, 24, bytes)
        sink.onRtcp(setup, p, p.size)
    }

    private fun put32(p: ByteArray, at: Int, v: Long) { for (i in 0 until 4) p[at + i] = (v shr (24 - 8 * i)).toByte() }

    override fun close() {
        if (closed) return
        closed = true
        val s = socket ?: return
        // Прощание не ждёт: запись в застрявший сокет держала бы того, кто закрывает.
        SendWatch.closeWithin(CLOSE_MS, abort = { runCatching { s.close() } }) { runCatching { if (::link.isInitialized) link.stopVideo() }; runCatching { s.close() } }
    }

    private companion object {
        const val CONNECT_MS = 3_000
        const val FIRST_KEY_NS = 6_000_000_000L
        const val PING_NS = 10_000_000_000L
        const val REPORT_NS = 5_000_000_000L
        const val CLOSE_MS = 400L
        const val PAYLOAD = 96
        const val SSRC = 0x5EC0117AL
        const val RTP = 12
        const val MTU = 1_380
        const val FU_H264 = 28
        const val FU_H265 = 49
        const val NTP_TO_UNIX_S = 2_208_988_800L
    }
}

/** Связь с камерой по протоколу Reolink для видео: вход, команды, сообщения. Разговор — свой сеанс ([BaichuanTalkSession]). */
internal class BcLink(socket: Socket) {
    private class Message(val msgId: Int, val channel: Int, val num: Int, val code: Int, val ext: ByteArray, val payload: ByteArray)

    private val input = DataInputStream(socket.getInputStream().buffered(64 * 1024))
    private val output = socket.getOutputStream()
    private var aes: ByteArray? = null
    private var xor = false
    /** Камера шифрует и начало каждого куска видео. */
    private var sealed = false
    private var binary = false
    private var num = 0

    fun login(user: String, password: String) {
        header(MSG_LOGIN, 0, 0, WANT_AES, CLASS_LEGACY, null); output.flush()
        val first = read()
        val xml = String(if (looksXml(first.payload)) first.payload else BcCrypto.xor(first.payload, first.channel), Charsets.UTF_8)
        val nonce = BaichuanTalkSession.tag(xml, "nonce") ?: throw RtspException("Камера не ответила на вход по протоколу Reolink")
        when (first.code and 0xFF) {
            0x00 -> {}
            0x01 -> xor = true
            0x02, 0x12 -> { xor = true; aes = BcCrypto.aesKey(nonce, password); sealed = first.code and 0xFF == 0x12 }
            else -> throw RtspException("Неизвестное шифрование камеры")
        }
        send(MSG_LOGIN, "<body>\n<LoginUser version=\"1.1\">\n<userName>${BcCrypto.md5(user + nonce)}</userName>\n<password>${BcCrypto.md5(password + nonce)}</password>\n" +
            "<userVer>1</userVer>\n</LoginUser>\n<LoginNet version=\"1.1\">\n<type>LAN</type>\n<udpPort>0</udpPort>\n</LoginNet>\n</body>\n")
        if (read().code != OK) throw RtspException("Камера не приняла логин или пароль", UNAUTHORIZED)
    }

    fun startVideo() = send(MSG_VIDEO, preview("<streamType>mainStream</streamType>\n"))
    fun stopVideo() = send(MSG_VIDEO_STOP, preview(""))
    fun ping() = send(MSG_PING, "")

    private fun preview(stream: String) = "<body>\n<Preview version=\"1.1\">\n<channelId>0</channelId>\n<handle>0</handle>\n$stream</Preview>\n</body>\n"

    /** Следующий кусок потока видео; null — служебное сообщение. Отказ камеры — [RtspException]. */
    fun readVideo(): ByteArray? {
        val m = read()
        if (m.msgId == MSG_KEEPALIVE) { synchronized(this) { header(MSG_KEEPALIVE, 0, m.num, OK, CLASS_MODERN, 0); output.flush() }; return null }
        if (m.msgId != MSG_VIDEO) return null
        val ext = String(plain(m.ext, m.channel), Charsets.UTF_8)
        if (ext.contains("<binaryData>1")) binary = true
        if (!binary) { if (m.code != OK) throw RtspException("Камера не отдала видео (${m.code})"); return null }
        if (m.payload.isEmpty()) return null
        val key = aes
        val hidden = if (sealed && key != null) BaichuanTalkSession.tag(ext, "encryptLen")?.toIntOrNull() ?: 0 else 0
        if (hidden <= 0 || hidden > m.payload.size || key == null) return m.payload
        System.arraycopy(BcCrypto.aesCfb(key, m.payload.copyOfRange(0, hidden), encrypt = false), 0, m.payload, 0, hidden)
        return m.payload
    }

    private fun send(msgId: Int, xml: String) = synchronized(this) {
        val plain = if (xml.isEmpty()) ByteArray(0) else (XML_HEAD + xml).toByteArray()
        val key = aes
        val body = when {
            plain.isEmpty() -> plain
            key != null && msgId != MSG_LOGIN -> BcCrypto.aesCfb(key, plain, encrypt = true)
            xor -> BcCrypto.xor(plain, 0)
            else -> plain
        }
        num = (num + 1) and 0xFFFF
        header(msgId, body.size, num, 0, CLASS_MODERN, 0)
        output.write(body)
        output.flush()
    }

    private fun plain(data: ByteArray, channel: Int): ByteArray {
        val key = aes
        return when {
            data.isEmpty() || looksXml(data) -> data
            key != null -> BcCrypto.aesCfb(key, data, encrypt = false)
            xor -> BcCrypto.xor(data, channel)
            else -> data
        }
    }

    private fun header(msgId: Int, length: Int, n: Int, code: Int, cls: Int, offset: Int?) {
        val h = ByteBuffer.allocate(if (offset != null) 24 else 20).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(MAGIC).putInt(msgId).putInt(length).putShort(0).putShort(n.toShort()).putShort(code.toShort()).putShort(cls.toShort())
        if (offset != null) h.putInt(offset)
        output.write(h.array())
    }

    private fun read(): Message {
        val h = ByteArray(20)
        input.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val magic = b.int
        if (magic != MAGIC && magic != MAGIC_REV) throw RtspException("Камера ответила не по протоколу Reolink")
        val msgId = b.int
        val length = b.int
        val channel = b.get().toInt() and 0xFF
        b.get()
        val n = b.short.toInt() and 0xFFFF
        val code = b.short.toInt() and 0xFFFF
        val cls = b.short.toInt() and 0xFFFF
        val offset = if (cls == CLASS_MODERN || cls == 0) { val o = ByteArray(4); input.readFully(o); ByteBuffer.wrap(o).order(ByteOrder.LITTLE_ENDIAN).int } else 0
        if (length < 0 || length > MAX_BODY || offset < 0 || offset > length) throw RtspException("Повреждённый ответ камеры")
        val body = ByteArray(length)
        input.readFully(body)
        return Message(msgId, channel, n, code, body.copyOfRange(0, offset), if (offset == 0) body else body.copyOfRange(offset, length))
    }

    private fun looksXml(data: ByteArray) = data.firstOrNull()?.toInt() == '<'.code

    private companion object {
        const val MAGIC = 0x0ABCDEF0
        const val MAGIC_REV = 0x0FEDCBA0
        const val CLASS_LEGACY = 0x6514
        const val CLASS_MODERN = 0x6414
        const val WANT_AES = 0xDC12
        const val MSG_LOGIN = 1
        const val MSG_VIDEO = 3
        const val MSG_VIDEO_STOP = 4
        const val MSG_PING = 93
        const val MSG_KEEPALIVE = 234
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val MAX_BODY = 4 shl 20
        const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
    }
}
