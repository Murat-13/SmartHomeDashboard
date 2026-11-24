package com.example.smarthomedashboard

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * Голос в динамик камеры по HTTP — запасной путь, когда обратного канала ONVIF у камеры нет (решение владельца 25.11.2026,
 * docs/talk-tz.md): Hikvision и её марки — ISAPI TwoWayAudio, Dahua и её марки — CGI `audio.cgi`. Сделано по открытым описаниям
 * протоколов, проверено на поддельных камерах; на настоящих — нет (docs/cameras.md). Только G.711 8 кГц, как и ONVIF.
 */
internal object HttpTalk {
    private const val TAG = "Talk"
    /** Для тестов: порт HTTP камеры. */
    internal var port = 80

    /** Отказ ONVIF, после которого есть смысл спросить камеру по HTTP: пароль верный, а обратного канала нет. */
    fun worthTrying(e: Exception) = e is TalkException || e is RtspException && e.status in 400..599 && e.status !in setOf(401, 453, 503)

    /**
     * Открыть канал голоса камеры [host] (блокирует; фоновый поток). null — это не Hikvision и не Dahua; [TalkException] — они, но
     * голоса не дают (и почему). По адресу потока [rtsp] — с кого начинать.
     */
    fun open(rtsp: String, host: String, user: String, password: String): TalkChannel? {
        val http = CameraHttp(host, port, user, password)
        val both = listOf(HikvisionTalk(http), DahuaTalk(http))
        for (talk in if (DAHUA_PATH in rtsp) both.reversed() else both) {
            try {
                val codec = talk.open()
                Log.i(TAG, "Голос — ${talk.path} ($codec)")
                return talk
            } catch (e: NotThisCamera) {
                talk.close()
            } catch (e: IOException) {
                talk.close()
                throw e
            }
        }
        return null
    }

    internal const val DAHUA_PATH = "/cam/realmonitor"

    /** Камера таких запросов не знает — это не её протокол. */
    internal class NotThisCamera : IOException("другая камера")
}

/**
 * Короткие запросы и открытый поток HTTP к камере по обычному сокету: ни одна библиотека не умеет «ответ 200, а потом звук в
 * тот же сокет». Авторизация — [DigestAuth]; камеры меняют nonce на каждый запрос, поэтому повтор один и только после 401.
 */
internal class CameraHttp(private val host: String, private val port: Int, private val user: String, private val password: String,
                          private val connectTimeoutMs: Int = 4_000, private val readTimeoutMs: Int = 6_000) {
    class Reply(val status: Int, val challenges: List<String>, val body: String)
    class Stream(val socket: Socket, val input: InputStream)

    private var auth: DigestAuth? = null

    /** Запрос без тела. Неверный пароль — ответ 401. */
    fun request(method: String, path: String): Reply = authorized { once(method, path) }

    /**
     * Открыть поток для отправки звука. [answerWithinMs] > 0 — камера на успех не отвечает вовсе (Dahua): ответ за это время — отказ;
     * 0 — ждём «200» и пишем в тот же сокет (Hikvision).
     */
    fun stream(method: String, path: String, headers: List<Pair<String, String>>, answerWithinMs: Int): Stream {
        var opened: Stream? = null
        val reply = authorized {
            val s = connect()
            try {
                send(s, method, path, headers)
                val input = s.getInputStream()
                if (answerWithinMs > 0) s.soTimeout = answerWithinMs
                val head = try { head(input) } catch (e: SocketTimeoutException) { if (answerWithinMs > 0) null else throw e }
                if (head == null || answerWithinMs == 0 && head.status in 200..299) {
                    s.soTimeout = 0
                    opened = Stream(s, input)
                    head ?: Reply(OK, emptyList(), "")
                } else { s.close(); if (answerWithinMs > 0 && head.status in 200..299) Reply(REFUSED, emptyList(), "") else head }
            } catch (e: IOException) { runCatching { s.close() }; throw e }
        }
        return opened ?: throw if (reply.status == UNAUTHORIZED) TalkException(WRONG, auth = true)
            else TalkException("Камера не приняла голос (HTTP ${reply.status})")
    }

    private fun authorized(call: () -> Reply): Reply {
        var reply = call()
        if (reply.status != UNAUTHORIZED || user.isBlank()) return reply
        var challenge = DigestAuth.choose(reply.challenges)
        if (challenge == null && auth != null) {
            // Просроченный nonce без нового предложения (Hikvision KV): спросить без авторизации.
            auth = null
            challenge = DigestAuth.choose(call().challenges)
        }
        auth = challenge ?: return reply
        reply = call()
        if (reply.status == UNAUTHORIZED) DigestAuth.choose(reply.challenges)?.takeIf { it.stale }?.let { auth = it; reply = call() }
        return reply
    }

    private fun connect(): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = readTimeoutMs
        return s
    }

    private fun send(s: Socket, method: String, path: String, headers: List<Pair<String, String>>) {
        val text = StringBuilder("$method $path HTTP/1.1\r\nHost: ").append(if (port == HTTP_PORT) host else "$host:$port").append("\r\n")
            .append("User-Agent: SmartHomeDashboard\r\n")
        auth?.let { text.append("Authorization: ").append(it.header(user, password, method, path)).append("\r\n") }
        for ((k, v) in headers) text.append(k).append(": ").append(v).append("\r\n")
        s.getOutputStream().apply { write(text.append("\r\n").toString().toByteArray(Charsets.UTF_8)); flush() }
    }

    private fun once(method: String, path: String): Reply = connect().use { s ->
        send(s, method, path, listOf("Accept" to "*/*", "Connection" to "close") + (if (method == "GET") emptyList() else listOf("Content-Length" to "0")))
        val input = s.getInputStream().buffered()
        val (reply, headers) = headAndHeaders(input)
        Reply(reply.status, reply.challenges, body(input, headers))
    }

    private fun head(input: InputStream) = headAndHeaders(input).first

    private fun headAndHeaders(input: InputStream): Pair<Reply, Map<String, String>> {
        val status = line(input).split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Непонятный ответ камеры")
        val headers = HashMap<String, String>()
        val challenges = ArrayList<String>()
        while (true) {
            val l = line(input)
            if (l.isEmpty()) break
            val key = l.substringBefore(':').trim().lowercase(Locale.ROOT)
            val value = l.substringAfter(':', "").trim()
            if (key == "www-authenticate") challenges += value
            headers[key] = value
        }
        return Reply(status, challenges, "") to headers
    }

    private fun line(input: InputStream): String {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Камера закрыла соединение")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > MAX_LINE) throw IOException("Слишком длинный ответ камеры")
        }
        return out.toString("UTF-8")
    }

    /** Тело по длине, кусками (chunked) или до закрытия; не больше [MAX_BODY]. Обрыв посреди тела — что пришло. */
    private fun body(input: InputStream, headers: Map<String, String>): String {
        val out = ByteArrayOutputStream()
        try {
            if (headers["transfer-encoding"].orEmpty().contains("chunked", true)) {
                while (true) {
                    val size = line(input).substringBefore(';').trim().toIntOrNull(16) ?: break
                    if (size <= 0) break
                    copy(input, out, size)
                    line(input)
                }
            } else copy(input, out, headers["content-length"]?.toIntOrNull() ?: MAX_BODY)
        } catch (_: IOException) { /* что успело прийти */ }
        return out.toString("UTF-8")
    }

    private fun copy(input: InputStream, out: ByteArrayOutputStream, count: Int) {
        val buffer = ByteArray(4096)
        var left = minOf(count, MAX_BODY - out.size())
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, left))
            if (n < 0) return
            out.write(buffer, 0, n)
            left -= n
        }
    }

    private companion object {
        const val HTTP_PORT = 80
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val REFUSED = 409
        const val MAX_LINE = 8192
        const val MAX_BODY = 256 * 1024
    }
}

private const val WRONG = "Камера не приняла логин или пароль"

/** Общее у каналов голоса по HTTP: G.711 кадрами по 20 мс без перерыва (тишина между фразами), слежение за обрывом. */
internal abstract class HttpTalkChannel : TalkChannel {
    private val pending = ByteArray(FRAME)
    private var filled = 0
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val watch = SendWatch()
    private var aLaw = false
    private var socket: Socket? = null
    @Volatile private var open = false
    @Volatile final override var sentPackets = 0
        private set
    @Volatile final override var sentVoice = 0
        private set

    /** Поток к камере и закон сжатия (true — A-law). */
    protected abstract fun connect(): Pair<CameraHttp.Stream, Boolean>
    /** Попрощаться с камерой после обрыва потока. */
    protected open fun bye() {}

    final override fun open(): String {
        val (stream, a) = connect()
        aLaw = a
        socket = stream.socket
        open = true
        Thread({ listen(stream.input) }, "talk-http-listen").apply { isDaemon = true; start() }
        Thread({ stream(stream.socket) }, "talk-http-send").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        return if (a) "PCMA" else "PCMU"
    }

    /** Камера молчит, пока всё хорошо: любой ответ или закрытие — конец канала (разговор откроет его заново). */
    private fun listen(input: InputStream) {
        val buffer = ByteArray(512)
        try { while (open) if (input.read(buffer) < 0 || String(buffer, Charsets.ISO_8859_1).contains("HTTP/1.")) break } catch (_: IOException) {}
        if (open) Log.w(TAG, "Камера закрыла канал голоса")
        open = false
        runCatching { socket?.close() }
    }

    override fun beginTalkspurt() { synchronized(this) { filled = 0 } }

    @Synchronized override fun send(pcm: ShortArray, count: Int) {
        for (i in 0 until count) {
            pending[filled++] = if (aLaw) G711.alaw(pcm[i].toInt()) else G711.ulaw(pcm[i].toInt())
            if (filled == FRAME) {
                queue.add(pending.copyOf())
                while (queue.size > MAX_QUEUE) queue.poll()
                filled = 0
            }
        }
    }

    override val queued get() = queue.size
    override val alive get() = open && !watch.stuck

    private fun stream(s: Socket) {
        val silence = ByteArray(FRAME) { if (aLaw) 0xD5.toByte() else 0xFF.toByte() }
        var next = System.nanoTime()
        watch.sent()
        try {
            val out = s.getOutputStream()
            while (open) {
                val voice = queue.poll()
                out.write(voice ?: silence)
                out.flush()
                watch.sent()
                sentPackets++
                if (voice != null) sentVoice++
                if (limitBytes > 0 && sentPackets * FRAME.toLong() >= limitBytes) break
                next += FRAME_NS
                val wait = next - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                else if (wait < -200_000_000) next = System.nanoTime() // после долгой паузы потока не догоняем рывком
            }
        } catch (e: IOException) {
            if (open) Log.w(TAG, "Отправка голоса прервалась: ${e.javaClass.simpleName}")
        }
        open = false
    }

    /** Сколько всего байтов объявлено камере; больше слать нельзя — канал закрывается (и открывается разговором заново). */
    protected open val limitBytes: Long get() = 0

    override fun close() {
        val was = socket
        open = false
        socket = null
        if (was == null) return
        SendWatch.closeWithin(CLOSE_MS, abort = { runCatching { was.close() } }) { runCatching { was.close() }; bye() }
    }

    protected companion object {
        const val FRAME = 160
        const val FRAME_NS = 20_000_000L
        const val MAX_QUEUE = 25
        const val CLOSE_MS = 1_500L
        const val TAG = "Talk"
    }
}

/**
 * Hikvision: список каналов → `open` → `audioData` (ответ 200, дальше сырой G.711 в тот же сокет) → `close`. Незакрытая прошлая
 * сессия не даёт открыть новую: отказ на `open` — закрыть и один раз повторить.
 */
internal class HikvisionTalk(private val http: CameraHttp) : HttpTalkChannel() {
    override val path get() = "Hikvision (ISAPI)"
    private var channel = ""

    override fun connect(): Pair<CameraHttp.Stream, Boolean> {
        val list = http.request("GET", ROOT)
        if (list.status == UNAUTHORIZED) throw TalkException(WRONG, auth = true)
        val channels = CHANNEL.findAll(list.body).map { it.value }.toList()
        if (list.status !in 200..299 || channels.isEmpty()) throw HttpTalk.NotThisCamera()
        val good = channels.firstOrNull { codec(it).contains("711") }
            ?: throw TalkException("Камера ждёт голос в формате ${codec(channels.first()).ifBlank { "не G.711" }} — выберите в ней звук G.711")
        val id = tag(good, "id").ifBlank { "1" }.filter { it.isLetterOrDigit() }
        var opened = http.request("PUT", "$ROOT/$id/open")
        if (opened.status !in 200..299) {
            http.request("PUT", "$ROOT/$id/close")
            Thread.sleep(PAUSE_MS)
            opened = http.request("PUT", "$ROOT/$id/open")
        }
        if (opened.status !in 200..299) throw TalkException(
            if (opened.status == BUSY || tag(opened.body, "statusCode") == "2") "Сейчас говорит другое устройство" else "Камера не открыла канал голоса (HTTP ${opened.status})")
        channel = id
        val session = tag(opened.body, "sessionId").filter { it.isLetterOrDigit() || it == '-' }
        val path = "$ROOT/$id/audioData" + if (session.isEmpty()) "" else "?sessionId=$session"
        return try {
            http.stream("PUT", path, listOf("Content-Type" to "application/octet-stream", "Content-Length" to "0", "Connection" to "keep-alive"), 0) to
                !codec(good).contains("ulaw", true)
        } catch (e: IOException) {
            runCatching { bye() }
            throw e
        }
    }

    override fun bye() { if (channel.isNotEmpty()) runCatching { http.request("PUT", "$ROOT/$channel/close") } }

    private fun codec(xml: String) = tag(xml, "audioCompressionType")
    private fun tag(xml: String, name: String) = Regex("<(?:\\w+:)?$name>\\s*([^<]*?)\\s*</").find(xml)?.groupValues?.get(1).orEmpty()

    private companion object {
        const val ROOT = "/ISAPI/System/TwoWayAudio/channels"
        const val UNAUTHORIZED = 401
        const val BUSY = 503
        const val PAUSE_MS = 300L
        val CHANNEL = Regex("<(?:\\w+:)?TwoWayAudioChannel[ >].*?</(?:\\w+:)?TwoWayAudioChannel>", RegexOption.DOT_MATCHES_ALL)
    }
}

/**
 * Dahua, Amcrest, Imou: есть ли динамик (`devAudioOutput.cgi`), затем `audio.cgi?action=postAudio` с объявленной заранее длиной и
 * A-law без перерыва. На успех камера не отвечает; ответ — отказ.
 */
internal class DahuaTalk(private val http: CameraHttp) : HttpTalkChannel() {
    override val path get() = "Dahua (CGI)"
    override val limitBytes get() = LENGTH

    override fun connect(): Pair<CameraHttp.Stream, Boolean> {
        val outputs = http.request("GET", "/cgi-bin/devAudioOutput.cgi?action=getCollect")
        if (outputs.status == UNAUTHORIZED) throw TalkException(WRONG, auth = true)
        val count = Regex("result=(\\d+)").find(outputs.body)?.groupValues?.get(1)?.toIntOrNull()
        if (outputs.status !in 200..299 || count == null) throw HttpTalk.NotThisCamera()
        if (count == 0) throw TalkException("У камеры нет динамика")
        return http.stream("POST", "/cgi-bin/audio.cgi?action=postAudio&httptype=singlepart&channel=1",
            listOf("Content-Type" to "Audio/G.711A", "Content-Length" to "$LENGTH"), ANSWER_MS) to true
    }

    private companion object {
        const val UNAUTHORIZED = 401
        const val LENGTH = 9_999_840L
        /** Отказ (401, 400) камера присылает сразу; дольше ждать нечего. */
        const val ANSWER_MS = 500
    }
}
