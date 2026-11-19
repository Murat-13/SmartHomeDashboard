package com.example.smarthomedashboard

import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Общий модуль камеры (этап 4, docs/recording.md): одно RTSP-подключение на поток камеры, сколько бы его ни
 * использовали. Круглосуточная запись, запись событий и живое видео плитки получают один и тот же поток:
 * записи — кадрами ([CameraRecorder]), живое видео — через локальный ретранслятор [RtspRelay] (плеер плитки
 * подключается к планшету, а не к камере). С двумя пишущими планшетами домофон держит 4 сессии вместо 6.
 *
 * Поток открыт, пока к нему подключена хоть одна запись. Без записи живое видео идёт к камере напрямую, как раньше.
 */
internal object CameraHub {
    data class Key(val url: String, val user: String, val password: String?)

    private val streams = ConcurrentHashMap<Key, SharedStream>()

    /** Подключить запись к общему потоку камеры (поток создаётся при первой). Поток управления записью. */
    @Synchronized
    fun attach(key: Key, recorder: CameraRecorder,
               sessionFactory: (String, String, String?) -> RtspSession, encoderFactory: () -> AacEncoder): SharedStream {
        val stream = streams.getOrPut(key) { SharedStream(key, sessionFactory, encoderFactory) }
        stream.add(recorder)
        return stream
    }

    /** Отключить запись; последняя — поток закрывается (ждём не дольше [waitMs]). */
    fun detach(stream: SharedStream, recorder: CameraRecorder, waitMs: Long) {
        val last = synchronized(this) {
            val empty = stream.remove(recorder)
            if (empty) streams.remove(stream.key, stream)
            empty
        }
        if (last) stream.stop(waitMs)
    }

    /**
     * Адрес ретранслятора для живого видео: поток [url] с логином [user] уже идёт (его держит запись) — плеер
     * подключается к планшету по одноразовому адресу. null — потока нет, смотреть напрямую.
     */
    fun relayUrl(url: String, user: String): String? {
        val stream = streams.values.firstOrNull { it.key.url == url.trim() && it.key.user == user && it.live } ?: return null
        val port = RtspRelay.ensureStarted() ?: return null
        return "rtsp://127.0.0.1:$port/${stream.token}"
    }

    internal fun byToken(token: String): SharedStream? = streams.values.firstOrNull { it.token == token }

    /** Для тестов: сколько общих потоков открыто. */
    internal val openStreams get() = streams.size
}

/**
 * Один поток камеры: подключение, разбор RTP в кадры, звук (AAC как есть, G.711 → AAC для записи), переподключение
 * 2→30 с. Кадры раздаются подключённым записям, сырые RTP-пакеты — клиентам ретранслятора. Свой поток чтения.
 */
internal class SharedStream(
    val key: CameraHub.Key,
    private val sessionFactory: (String, String, String?) -> RtspSession,
    private val encoderFactory: () -> AacEncoder
) {
    /** Одноразовая часть адреса ретранслятора: без неё другие приложения планшета не увидят видео. */
    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private class Attached(val recorder: CameraRecorder) {
        var sink: CameraRecorder.Sink? = null
    }

    private val consumers = CopyOnWriteArrayList<Attached>()
    @Volatile private var running = false
    @Volatile private var session: RtspSession? = null
    private var thread: Thread? = null
    /** Описание потока для ретранслятора; null — поток сейчас не идёт. */
    @Volatile var relaySdp: String? = null
        private set
    /** Текущая сессия камеры: номер дорожки ретранслятора по каналу RTP. */
    @Volatile private var relayChannels: Map<Int, Int> = emptyMap()
    private val relayClients = CopyOnWriteArrayList<RtspRelay.Client>()
    private var current: SessionInfo? = null

    private class SessionInfo(val videoClock: Int, val audioParams: AudioParams?)

    val live get() = relaySdp != null

    fun add(recorder: CameraRecorder) {
        val a = Attached(recorder)
        synchronized(a) {
            consumers += a
            // Поток уже идёт — запись начинает со следующего ключевого кадра этой же сессии.
            current?.let { begin(a, it) }
        }
        recorder.streamStatus(if (live) null else CameraRecorder.State.CONNECTING, "")
        if (!running) {
            running = true
            thread = Thread({ loop() }, "cam-${RecLayout.cameraKey(recorder.camera)}").apply {
                isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start()
            }
        }
    }

    /** true — подключённых записей больше нет. */
    fun remove(recorder: CameraRecorder): Boolean {
        consumers.firstOrNull { it.recorder === recorder }?.let { a ->
            synchronized(a) { end(a); consumers.remove(a) }
        }
        return consumers.isEmpty()
    }

    fun stop(waitMs: Long) {
        running = false
        session?.close()
        relayClients.forEach { it.close() }
        thread?.interrupt()
        if (thread !== Thread.currentThread()) thread?.join(waitMs)
        thread = null
    }

    private fun begin(a: Attached, info: SessionInfo) {
        a.sink = a.recorder.newSink().also {
            it.videoClock = info.videoClock
            it.audioParams = if (a.recorder.wantsAudio) info.audioParams else null
        }
    }

    private fun end(a: Attached) {
        val sink = a.sink ?: return
        a.sink = null
        try { sink.close() } catch (e: Exception) { Log.w(TAG, "${key.url.hashCode()}: файл не закрыт: ${e.message}") }
    }

    private inline fun each(block: (Attached, CameraRecorder.Sink) -> Unit) {
        for (a in consumers) synchronized(a) { a.sink?.let { block(a, it) } }
    }

    private fun broadcast(state: CameraRecorder.State, detail: String = "") = consumers.forEach { it.recorder.streamStatus(state, detail) }

    private fun loop() {
        var backoffMs = 2_000L
        while (running) {
            val started = System.nanoTime()
            broadcast(CameraRecorder.State.CONNECTING)
            var encoder: AacEncoder? = null
            try {
                val s = sessionFactory(key.url, key.user, key.password)
                session = s
                if (!running) { s.close(); break }
                // Звук берётся всегда (если он есть): записи без звука его просто не пишут, а живому видео он нужен.
                val setups = s.open { t -> (t.isVideo && (t.encoding == "H264" || t.encoding == "H265" || t.encoding == "HEVC")) || t.isAudio }
                val video = setups.firstOrNull { it.track.isVideo } ?: throw RtspException("Камера не отдаёт H.264/H.265")
                val depacketizer = VideoDepacketizer.create(video.track) ?: throw RtspException("Кодек не поддерживается")
                val audioSetup = setups.firstOrNull { it.track.isAudio }
                val aac = audioSetup?.let { AacDepacketizer.create(it.track) }
                // G.711 (PCMA/PCMU) — для записи перекодируется в AAC; не вышло — запись без звука.
                val g711 = if (aac == null) audioSetup?.let { G711Depacketizer.create(it.track) } else null
                encoder = g711?.takeIf { consumers.any { c -> c.recorder.wantsAudio } }?.let {
                    try { encoderFactory() } catch (e: Exception) {
                        Log.w(TAG, "AAC-кодер недоступен (${e.javaClass.simpleName}), запись без звука")
                        null
                    }
                }
                val info = SessionInfo(video.track.clockRate.takeIf { it > 0 } ?: 90_000,
                    aac?.let { AudioParams(it.config, it.sampleRate, it.channels) }
                        ?: encoder?.let { AudioParams(it.config, it.sampleRate, it.channels) })
                synchronized(this) {
                    current = info
                    for (a in consumers) synchronized(a) { end(a); begin(a, info) }
                }
                relayChannels = setups.withIndex().associate { (i, setup) -> setup.rtpChannel to i }
                relaySdp = RtspRelay.sdpFor(s.sdp, setups)
                val enc = encoder
                s.read(object : RtspSession.Sink {
                    override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {
                        relay(setup.rtpChannel, rtcp = false, packet, length)
                        val rtp = RtpPacket.parse(packet, length) ?: return
                        if (setup === video) {
                            for (frame in depacketizer.push(rtp)) each { _, sink -> sink.video(frame, depacketizer) }
                        } else if (setup === audioSetup && aac != null) {
                            for (frame in aac.push(rtp)) each { a, sink -> if (a.recorder.wantsAudio) sink.audio(frame, aac.sampleRate) }
                        } else if (setup === audioSetup && g711 != null && enc != null) {
                            val pcm = g711.push(rtp)
                            // Привязка звука — по приходу отсчётов, а не по выходу кодера (у него своя задержка).
                            each { a, sink -> if (a.recorder.wantsAudio) sink.audioArrival(pcm.timestamp) }
                            for (frame in enc.encode(pcm)) each { a, sink -> if (a.recorder.wantsAudio) sink.audio(frame, enc.sampleRate) }
                        }
                    }

                    override fun onRtcp(setup: RtspSession.Setup, packet: ByteArray, length: Int) =
                        relay(setup.rtpChannel, rtcp = true, packet, length)
                })
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "${e.javaClass.simpleName}: ${e.message}")
                    broadcast(CameraRecorder.State.RETRYING, e.message ?: e.javaClass.simpleName)
                }
            } finally {
                // Новая сессия камеры — новые номера и метки RTP: клиенты ретранслятора переподключаются сами.
                relaySdp = null
                relayClients.forEach { it.close() }
                synchronized(this) {
                    current = null
                    for (a in consumers) synchronized(a) { end(a) }
                }
                session?.close()
                session = null
                encoder?.release()
            }
            if (!running) break
            // Сессия держалась дольше минуты — начинаем отсчёт заново.
            if (System.nanoTime() - started > 60_000_000_000L) backoffMs = 2_000L
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { if (!running) break }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        relaySdp = null
        broadcast(CameraRecorder.State.STOPPED)
    }

    private fun relay(channel: Int, rtcp: Boolean, packet: ByteArray, length: Int) {
        if (relayClients.isEmpty()) return
        val track = relayChannels[channel] ?: return
        for (c in relayClients) c.offer(track, rtcp, packet, length)
    }

    internal fun addClient(client: RtspRelay.Client) { relayClients += client }
    internal fun removeClient(client: RtspRelay.Client) { relayClients -= client }

    private companion object {
        const val TAG = "CameraHub"
    }
}

/**
 * Локальный RTSP-ретранслятор живого видео (127.0.0.1, только TCP interleaved — как просит плеер плитки).
 * Отдаёт описание потока камеры и пересылает её RTP-пакеты как есть: плеер видит ту же камеру, но соединение с
 * камерой одно. Адрес — с одноразовым токеном потока ([SharedStream.token]); чужой адрес — 404.
 */
internal object RtspRelay {
    @Volatile private var server: ServerSocket? = null

    /** Порт ретранслятора; null — не удалось открыть сокет (тогда живое видео идёт напрямую). */
    @Synchronized
    fun ensureStarted(): Int? {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        return try {
            val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            server = s
            Thread({ accept(s) }, "rtsp-relay").apply { isDaemon = true; start() }
            s.localPort
        } catch (e: Exception) {
            Log.w("RtspRelay", "Ретранслятор не запущен: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val socket = try { s.accept() } catch (_: Exception) { break }
            Thread({ Client(socket).serve() }, "rtsp-relay-client").apply { isDaemon = true; start() }
        }
    }

    /**
     * Описание потока для плеера: только дорожки, для которых выполнен SETUP у камеры, со своими короткими
     * адресами (`track0`, `track1`…). Остальное описание камеры — как есть.
     */
    fun sdpFor(raw: String, setups: List<RtspSession.Setup>): String {
        val lines = raw.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        val firstMedia = lines.indexOfFirst { it.startsWith("m=") }.let { if (it < 0) lines.size else it }
        val out = ArrayList<String>()
        lines.take(firstMedia).filterNot { it.startsWith("a=control:") }.forEach { out += it }
        out += "a=control:*"
        var i = firstMedia
        while (i < lines.size) {
            var j = i + 1
            while (j < lines.size && !lines[j].startsWith("m=")) j++
            val block = lines.subList(i, j)
            val track = SdpParser.parse(block.joinToString("\r\n")).firstOrNull()
            val index = setups.indexOfFirst { it.track == track }
            if (track != null && index >= 0) {
                block.filterNot { it.startsWith("a=control:") }.forEach { out += it }
                out += "a=control:track$index"
            }
            i = j
        }
        return out.joinToString("\r\n", postfix = "\r\n")
    }

    /** Один плеер. Запросы — на своём потоке; пакеты — через ограниченную очередь своим потоком записи. */
    internal class Client(private val socket: Socket) {
        private val input: InputStream = BufferedInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()
        private val queue = ArrayBlockingQueue<ByteArray>(QUEUE)
        /** Номер дорожки → канал interleaved, который попросил плеер. */
        private val channels = ConcurrentHashMap<Int, Int>()
        @Volatile private var playing = false
        @Volatile private var closed = false
        private var stream: SharedStream? = null
        private val session = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        fun offer(track: Int, rtcp: Boolean, packet: ByteArray, length: Int) {
            if (!playing || closed) return
            val channel = channels[track]?.let { if (rtcp) it + 1 else it } ?: return
            val frame = ByteArray(length + 4)
            frame[0] = '$'.code.toByte(); frame[1] = channel.toByte(); frame[2] = (length shr 8).toByte(); frame[3] = length.toByte()
            System.arraycopy(packet, 0, frame, 4, length)
            // Плеер не успевает (завис, медленный) — отключаем: он переподключится, а камера и запись не ждут.
            if (!queue.offer(frame)) close()
        }

        fun close() {
            if (closed) return
            closed = true
            stream?.removeClient(this)
            try { socket.close() } catch (_: Exception) {}
        }

        fun serve() {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 120_000
                Thread({ pump() }, "rtsp-relay-out").apply { isDaemon = true; start() }
                while (!closed) {
                    val first = input.read()
                    if (first < 0) break
                    if (first == '$'.code) { skipInterleaved(); continue } // RTCP плеера (отчёты получателя)
                    val request = readRequest(first.toChar()) ?: break
                    handle(request)
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }

        private fun pump() {
            try {
                while (!closed) {
                    val frame = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    synchronized(output) { output.write(frame) }
                    if (queue.isEmpty()) synchronized(output) { output.flush() }
                }
            } catch (_: Exception) {
                close()
            }
        }

        private class Request(val method: String, val uri: String, val headers: Map<String, String>)

        private fun readRequest(first: Char): Request? {
            val line = first + readLine()
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine()
                if (h.isEmpty()) break
                headers[h.substringBefore(':').trim().lowercase(Locale.ROOT)] = h.substringAfter(':', "").trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > 64 * 1024) return null
            repeat(length) { if (input.read() < 0) throw EOFException() }
            val parts = line.split(' ')
            return Request(parts.getOrNull(0).orEmpty(), parts.getOrNull(1).orEmpty(), headers)
        }

        private fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > 8192) throw EOFException()
            }
            return sb.toString()
        }

        private fun skipInterleaved() {
            input.read()
            val length = (input.read() shl 8) or input.read()
            if (length < 0) throw EOFException()
            repeat(length) { if (input.read() < 0) throw EOFException() }
        }

        private fun reply(cseq: String?, status: String, headers: List<Pair<String, String>> = emptyList(), body: String = "") {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val text = StringBuilder("RTSP/1.0 $status\r\nCSeq: ${cseq.orEmpty()}\r\nServer: SmartHomeDashboard\r\n")
            headers.forEach { (k, v) -> text.append(k).append(": ").append(v).append("\r\n") }
            if (bytes.isNotEmpty()) text.append("Content-Length: ").append(bytes.size).append("\r\n")
            text.append("\r\n")
            synchronized(output) {
                output.write(text.toString().toByteArray(Charsets.UTF_8))
                output.write(bytes)
                output.flush()
            }
        }

        private fun handle(r: Request) {
            val cseq = r.headers["cseq"]
            val path = r.uri.substringAfter("://", r.uri).substringAfter('/', "")
            val token = path.substringBefore('/')
            val s = CameraHub.byToken(token)
            if (r.method != "OPTIONS" && (s == null || (stream != null && stream !== s))) { reply(cseq, "404 Not Found"); return }
            when (r.method) {
                "OPTIONS" -> reply(cseq, "200 OK", listOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER"))
                "DESCRIBE" -> {
                    val sdp = s!!.relaySdp ?: run { reply(cseq, "503 Service Unavailable"); return }
                    val base = "rtsp://127.0.0.1:${socket.localPort}/$token/"
                    reply(cseq, "200 OK", listOf("Content-Type" to "application/sdp", "Content-Base" to base), sdp)
                }
                "SETUP" -> {
                    val track = Regex("track(\\d+)$").find(path.trimEnd('/'))?.groupValues?.get(1)?.toIntOrNull()
                    val transport = r.headers["transport"].orEmpty()
                    val channel = Regex("interleaved=(\\d+)").find(transport)?.groupValues?.get(1)?.toIntOrNull()
                    if (track == null) { reply(cseq, "404 Not Found"); return }
                    if (channel == null || !transport.contains("TCP", ignoreCase = true)) { reply(cseq, "461 Unsupported Transport"); return }
                    channels[track] = channel
                    reply(cseq, "200 OK", listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}",
                        "Session" to "$session;timeout=60"))
                }
                "PLAY" -> {
                    val target = s!!
                    if (!target.live) { reply(cseq, "503 Service Unavailable"); return }
                    stream = target
                    reply(cseq, "200 OK", listOf("Session" to session, "Range" to "npt=0.000-"))
                    playing = true
                    target.addClient(this)
                }
                "GET_PARAMETER", "SET_PARAMETER" -> reply(cseq, "200 OK", listOf("Session" to session))
                "TEARDOWN" -> { reply(cseq, "200 OK", listOf("Session" to session)); close() }
                else -> reply(cseq, "405 Method Not Allowed")
            }
        }

        private companion object {
            /** ~ несколько секунд основного потока: дольше плеер не может отставать. */
            const val QUEUE = 2_000
        }
    }
}
