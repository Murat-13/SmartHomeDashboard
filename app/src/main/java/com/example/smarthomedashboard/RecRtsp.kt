package com.example.smarthomedashboard

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Запись с камер (docs/recording.md): минимальный RTSP-клиент для записи без перекодирования.
 * Media3 RTSP только проигрывает, поэтому запись получает RTP-пакеты сама: DESCRIBE → SETUP (TCP interleaved) → PLAY,
 * затем чтение кадров `$` на одном потоке. Без Android: проверяется обычными JVM-тестами.
 */

/** Дорожка из SDP. [encoding] — в верхнем регистре (H264, H265, MPEG4-GENERIC, PCMA, PCMU…), fmtp — ключи в нижнем. */
internal data class SdpTrack(
    val media: String,
    val payloadType: Int,
    val encoding: String,
    val clockRate: Int,
    val channels: Int,
    val fmtp: Map<String, String>,
    val control: String,
    /** `sendonly`/`recvonly`/`sendrecv`/`inactive` дорожки; пусто — не указано. У ONVIF-обратного канала — `sendonly`. */
    val direction: String = ""
) {
    val isVideo get() = media == "video"
    val isAudio get() = media == "audio"
}

internal object SdpParser {
    fun parse(text: String): List<SdpTrack> {
        val tracks = mutableListOf<SdpTrack>()
        var media: String? = null
        var pt = -1
        var encoding = ""
        var clock = 0
        var channels = 1
        var fmtp = emptyMap<String, String>()
        var control = ""
        var direction = ""
        fun flush() {
            val m = media ?: return
            if (pt >= 0) tracks += SdpTrack(m, pt, encoding, clock, channels, fmtp, control, direction)
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("m=") -> {
                    flush()
                    val parts = line.substring(2).split(' ').filter { it.isNotEmpty() }
                    media = parts.getOrNull(0)?.lowercase(Locale.ROOT)
                    pt = parts.getOrNull(3)?.toIntOrNull() ?: -1
                    // Статические типы без rtpmap (RFC 3551).
                    encoding = when (pt) { 0 -> "PCMU"; 8 -> "PCMA"; 26 -> "JPEG"; else -> "" }
                    clock = when (pt) { 0, 8 -> 8000; 26 -> 90000; else -> 0 }
                    channels = 1; fmtp = emptyMap(); control = ""; direction = ""
                }
                media == null -> {}
                line.startsWith("a=rtpmap:") -> {
                    val body = line.substringAfter(':')
                    if (body.substringBefore(' ').toIntOrNull() == pt) {
                        val spec = body.substringAfter(' ').trim().split('/')
                        encoding = spec.getOrNull(0)?.uppercase(Locale.ROOT).orEmpty()
                        clock = spec.getOrNull(1)?.toIntOrNull() ?: clock
                        channels = spec.getOrNull(2)?.toIntOrNull() ?: 1
                    }
                }
                line.startsWith("a=fmtp:") -> {
                    val body = line.substringAfter(':')
                    if (body.substringBefore(' ').toIntOrNull() == pt) {
                        fmtp = body.substringAfter(' ').split(';').mapNotNull { item ->
                            val key = item.substringBefore('=').trim().lowercase(Locale.ROOT)
                            if (key.isEmpty()) null else key to item.substringAfter('=', "").trim()
                        }.toMap()
                    }
                }
                line.startsWith("a=control:") -> control = line.substringAfter("a=control:").trim()
                line == "a=sendonly" || line == "a=recvonly" || line == "a=sendrecv" || line == "a=inactive" -> direction = line.substring(2)
            }
        }
        flush()
        return tracks
    }

    /** Адрес дорожки: абсолютный control, «*» — базовый адрес, иначе относительный к Content-Base. */
    fun resolve(base: String, control: String): String = when {
        control.isEmpty() || control == "*" -> base
        control.startsWith("rtsp://", ignoreCase = true) || control.startsWith("rtsps://", ignoreCase = true) -> control
        base.endsWith("/") -> base + control
        else -> "$base/$control"
    }
}

/** Ответ RTSP (заголовки — ключи в нижнем регистре). */
internal class RtspResponse(val status: Int, val reason: String, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String) = headers[name.lowercase(Locale.ROOT)]
    val bodyText get() = String(body, Charsets.UTF_8)
}

internal class RtspException(message: String, val status: Int = 0) : IOException(message)

/**
 * Одна RTSP-сессия. [open] устанавливает соединение и запускает воспроизведение выбранных дорожек,
 * [read] блокирует поток и отдаёт RTP/RTCP до ошибки или [close] (из любого потока).
 *
 * Таймауты: подключение [connectTimeoutMs] (как у живого видео, 4 с), чтение [readTimeoutMs] (20 с без единого
 * байта — камера молчит, сессия считается оборванной). Keep-alive — GET_PARAMETER (или OPTIONS) раз в половину
 * таймаута сессии, из того же потока чтения.
 */
internal class RtspSession(
    url: String,
    private val user: String,
    private val password: String?,
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 20_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Заголовок `Require` во всех запросах (ONVIF-обратный канал: `www.onvif.org/ver20/backchannel`). */
    private val require: String? = null
) : Closeable {
    /** Дорожка, для которой выполнен SETUP: RTP идёт в канал [rtpChannel], RTCP — в [rtpChannel] + 1. */
    data class Setup(val track: SdpTrack, val rtpChannel: Int)

    interface Sink {
        fun onRtp(setup: Setup, packet: ByteArray, length: Int)
        fun onRtcp(setup: Setup, packet: ByteArray, length: Int) {}
    }

    private val requestUrl = url.trim()
    private val uri = URI(requestUrl)
    private var socket: Socket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var cseq = 0
    private var session: String? = null
    private var sessionTimeoutSec = 60
    private var keepAliveMethod = "GET_PARAMETER"
    private var auth: Challenge? = null
    @Volatile private var closed = false
    var setups: List<Setup> = emptyList()
        private set
    /** Описание потока камеры (ответ DESCRIBE) — для локального ретранслятора живого видео ([RtspRelay]). */
    var sdp: String = ""
        private set

    private data class Challenge(val scheme: String, val realm: String, val nonce: String, val qop: String?, val opaque: String?)
    private var nonceCount = 0

    /** Подключиться и начать воспроизведение дорожек, выбранных [wanted]. Возвращает выполненные SETUP. */
    fun open(wanted: (SdpTrack) -> Boolean): List<Setup> {
        val host = uri.host ?: throw RtspException("Нет адреса камеры")
        val port = if (uri.port > 0) uri.port else 554
        val s = Socket()
        socket = s
        if (closed) throw RtspException("Сессия закрыта")
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = readTimeoutMs
        s.tcpNoDelay = true
        input = BufferedInputStream(s.getInputStream(), 64 * 1024)
        output = s.getOutputStream()

        val options = request("OPTIONS", requestUrl)
        options.header("public")?.let { public ->
            if (!public.contains("GET_PARAMETER", ignoreCase = true)) keepAliveMethod = "OPTIONS"
        }
        val describe = request("DESCRIBE", requestUrl, listOf("Accept" to "application/sdp"))
        val base = (describe.header("content-base") ?: describe.header("content-location") ?: requestUrl).trimEnd()
        sdp = describe.bodyText
        val tracks = SdpParser.parse(describe.bodyText).filter(wanted)
        if (tracks.isEmpty()) throw RtspException("Нет подходящих дорожек в SDP")

        val done = mutableListOf<Setup>()
        for ((index, track) in tracks.withIndex()) {
            val channel = index * 2
            val reply = request("SETUP", SdpParser.resolve(base, track.control),
                listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}"))
            reply.header("session")?.let { value ->
                session = value.substringBefore(';').trim()
                Regex("timeout=(\\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    sessionTimeoutSec = it.coerceIn(10, 600)
                }
            }
            val transport = reply.header("transport").orEmpty()
            val assigned = Regex("interleaved=(\\d+)").find(transport)?.groupValues?.get(1)?.toIntOrNull() ?: channel
            done += Setup(track, assigned)
        }
        request("PLAY", base, listOf("Range" to "npt=0.000-"))
        setups = done
        return done
    }

    /** Читать поток до ошибки, конца или [close]. */
    fun read(sink: Sink) {
        val byChannel = HashMap<Int, Pair<Setup, Boolean>>()
        for (s in setups) { byChannel[s.rtpChannel] = s to true; byChannel[s.rtpChannel + 1] = s to false }
        var buffer = ByteArray(64 * 1024)
        var nextKeepAlive = clock() + sessionTimeoutSec * 500L
        while (!closed) {
            val first = input.read()
            if (first < 0) throw EOFException("Камера закрыла соединение")
            if (first == '$'.code) {
                val channel = readByte()
                val length = (readByte() shl 8) or readByte()
                if (length > buffer.size) buffer = ByteArray(length)
                readFully(buffer, length)
                val target = byChannel[channel]
                if (target != null) {
                    if (target.second) sink.onRtp(target.first, buffer, length) else sink.onRtcp(target.first, buffer, length)
                }
            } else {
                // Ответ на keep-alive или запрос сервера внутри потока: дочитать и пропустить.
                readMessage(first.toChar().toString())
            }
            val now = clock()
            if (now >= nextKeepAlive) {
                nextKeepAlive = now + sessionTimeoutSec * 500L
                send(keepAliveMethod, requestUrl, emptyList())
            }
        }
    }

    /** Отправить RTP-пакет камере по каналу [channel] (обратный канал); из любого потока. */
    fun sendRtp(channel: Int, packet: ByteArray, length: Int) {
        if (closed) throw RtspException("Сессия закрыта")
        val frame = ByteArray(4 + length)
        frame[0] = '$'.code.toByte(); frame[1] = channel.toByte()
        frame[2] = (length shr 8).toByte(); frame[3] = length.toByte()
        System.arraycopy(packet, 0, frame, 4, length)
        synchronized(this) {
            output.write(frame)
            output.flush()
        }
    }

    /**
     * Оборвать без прощания с камерой: запись в сокет застряла (связь молча пропала), и TEARDOWN ждал бы её вместе с
     * тем, кто закрывает сессию. Можно из любого потока.
     */
    fun abort() {
        closed = true
        try { socket?.close() } catch (_: Exception) {}
    }

    override fun close() {
        if (closed) return
        closed = true
        val s = socket ?: return
        try {
            if (session != null && !s.isClosed) {
                s.soTimeout = 500
                send("TEARDOWN", requestUrl, emptyList())
            }
        } catch (_: Exception) {
        }
        try { s.close() } catch (_: Exception) {}
    }

    private fun request(method: String, url: String, headers: List<Pair<String, String>> = emptyList()): RtspResponse {
        var reply = exchange(method, url, headers)
        if (reply.status == 401) {
            val challenge = parseChallenge(reply.headers["www-authenticate"])
                ?: throw RtspException("Камера требует авторизацию неизвестного вида", 401)
            if (user.isEmpty()) throw RtspException("Камера требует логин и пароль", 401)
            auth = challenge
            nonceCount = 0
            reply = exchange(method, url, headers)
            if (reply.status == 401) throw RtspException("Неверный логин или пароль камеры", 401)
        }
        if (reply.status !in 200..299) throw RtspException("$method: ${reply.status} ${reply.reason}", reply.status)
        return reply
    }

    private fun exchange(method: String, url: String, headers: List<Pair<String, String>>): RtspResponse {
        val sent = send(method, url, headers)
        while (true) {
            val first = input.read()
            if (first < 0) throw EOFException("Камера закрыла соединение")
            if (first == '$'.code) {
                // RTP до ответа на PLAY: пропустить кадр.
                readByte()
                val length = (readByte() shl 8) or readByte()
                input.skipNBytesCompat(length)
                continue
            }
            val response = readMessage(first.toChar().toString()) ?: continue
            val seq = response.header("cseq")?.trim()?.toIntOrNull()
            if (seq == null || seq == sent) return response
        }
    }

    private fun send(method: String, url: String, headers: List<Pair<String, String>>): Int {
        val seq = ++cseq
        val text = StringBuilder()
        text.append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
        text.append("CSeq: ").append(seq).append("\r\n")
        text.append("User-Agent: SmartHomeDashboard\r\n")
        session?.let { text.append("Session: ").append(it).append("\r\n") }
        authorization(method, url)?.let { text.append("Authorization: ").append(it).append("\r\n") }
        require?.let { text.append("Require: ").append(it).append("\r\n") }
        for ((k, v) in headers) text.append(k).append(": ").append(v).append("\r\n")
        text.append("\r\n")
        synchronized(this) {
            output.write(text.toString().toByteArray(Charsets.UTF_8))
            output.flush()
        }
        return seq
    }

    /** Прочитать сообщение RTSP (ответ или запрос сервера), первый символ уже прочитан. Запрос → null. */
    private fun readMessage(prefix: String): RtspResponse? {
        val statusLine = prefix + readLine()
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                val value = line.substring(colon + 1).trim()
                // Несколько WWW-Authenticate: Digest предпочтительнее Basic.
                if (key == "www-authenticate" && headers.containsKey(key) && !value.startsWith("Digest", true)) continue
                headers[key] = value
            }
        }
        val length = headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (length < 0 || length > 1_000_000) throw RtspException("Слишком длинный ответ камеры")
        val body = ByteArray(length)
        readFully(body, length)
        if (!statusLine.startsWith("RTSP/")) return null
        val parts = statusLine.split(' ', limit = 3)
        val status = parts.getOrNull(1)?.toIntOrNull() ?: throw RtspException("Непонятный ответ камеры")
        return RtspResponse(status, parts.getOrNull(2).orEmpty(), headers, body)
    }

    private fun readLine(): String {
        val out = java.io.ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Камера закрыла соединение")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 8192) throw RtspException("Слишком длинная строка ответа")
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("Камера закрыла соединение")
        return b
    }

    private fun readFully(target: ByteArray, length: Int) {
        var done = 0
        while (done < length) {
            val n = input.read(target, done, length - done)
            if (n < 0) throw EOFException("Камера закрыла соединение")
            done += n
        }
    }

    private fun InputStream.skipNBytesCompat(n: Int) {
        var left = n.toLong()
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) { if (read() < 0) throw EOFException(); left-- } else left -= skipped
        }
    }

    private fun parseChallenge(value: String?): Challenge? {
        if (value == null) return null
        val scheme = value.substringBefore(' ').trim()
        val params = Regex("(\\w+)=(\"([^\"]*)\"|[^,\\s]+)").findAll(value.substringAfter(' ')).associate { m ->
            m.groupValues[1].lowercase(Locale.ROOT) to (m.groups[3]?.value ?: m.groupValues[2])
        }
        return when {
            scheme.equals("Digest", true) -> Challenge("Digest", params["realm"].orEmpty(), params["nonce"].orEmpty(),
                params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }, params["opaque"])
            scheme.equals("Basic", true) -> Challenge("Basic", params["realm"].orEmpty(), "", null, null)
            else -> null
        }
    }

    private fun authorization(method: String, url: String): String? {
        val c = auth ?: return null
        val pass = password.orEmpty()
        if (c.scheme == "Basic") return "Basic " + RecBase64.encode("$user:$pass".toByteArray(Charsets.UTF_8))
        val ha1 = md5("$user:${c.realm}:$pass")
        val ha2 = md5("$method:$url")
        val parts = mutableListOf("username=\"$user\"", "realm=\"${c.realm}\"", "nonce=\"${c.nonce}\"", "uri=\"$url\"")
        if (c.qop != null) {
            val nc = "%08x".format(++nonceCount)
            val cnonce = md5(System.nanoTime().toString()).take(16)
            parts += "response=\"${md5("$ha1:${c.nonce}:$nc:$cnonce:${c.qop}:$ha2")}\""
            parts += "qop=${c.qop}"; parts += "nc=$nc"; parts += "cnonce=\"$cnonce\""
        } else {
            parts += "response=\"${md5("$ha1:${c.nonce}:$ha2")}\""
        }
        c.opaque?.let { parts += "opaque=\"$it\"" }
        return "Digest " + parts.joinToString(", ")
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Base64 (RFC 4648) без Android и без java.util.Base64 (minSdk 24): SDP sprop-* и Basic-авторизация. */
internal object RecBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            out.append(ALPHABET[b0 shr 2]).append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            out.append(if (i + 1 < data.size) ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            out.append(if (i + 2 < data.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return out.toString()
    }

    /** Некорректная строка — null. */
    fun decode(text: String): ByteArray? {
        val clean = text.trim().trimEnd('=')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) { bits -= 8; out.write((buffer shr bits) and 0xFF) }
        }
        return out.toByteArray()
    }
}
