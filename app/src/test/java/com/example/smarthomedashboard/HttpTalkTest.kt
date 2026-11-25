package com.example.smarthomedashboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Голос в динамик по HTTP (docs/talk-tz.md, «Hikvision и Dahua»): на поддельных камерах, по открытым описаниям протоколов. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpTalkTest {
    private val servers = java.util.concurrent.CopyOnWriteArrayList(mutableListOf<ServerSocket>())
    private val onvif = TalkChannels.onvif
    private val http = TalkChannels.http
    private val talks = mutableListOf<TalkChannel>()

    @Before fun setUp() { TalkChannels.forget() }
    @After fun tearDown() {
        talks.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
        TalkChannels.onvif = onvif; TalkChannels.http = http; TalkChannels.forget(); HttpTalk.port = 80
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Поддельная камера: [answer] — что ответить на запрос (null — промолчать и слушать звук). */
    private inner class Camera(val answer: Camera.(method: String, path: String) -> String?) {
        val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).also { servers += it }
        val requests = java.util.concurrent.CopyOnWriteArrayList(mutableListOf<String>())
        val heads = java.util.concurrent.CopyOnWriteArrayList(mutableListOf<String>())
        val audio = ByteArrayOutputStream()
        val streams = java.util.concurrent.CopyOnWriteArrayList(mutableListOf<Socket>())
        var nonce = "n1"
        /** Новый nonce на каждый запрос (так у Dahua). */
        var fresh = false
        init {
            HttpTalk.port = server.localPort
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) { try { serve(s) } catch (_: Exception) {} }
                }
            }
        }

        private fun line(s: Socket): String? {
            val out = StringBuilder()
            while (true) { val b = s.getInputStream().read(); if (b < 0) return null; if (b == 10) return out.toString().trimEnd('\r'); out.append(b.toChar()) }
        }

        private fun serve(s: Socket) {
            while (true) {
                val first = line(s) ?: return
                val headers = LinkedHashMap<String, String>()
                while (true) { val l = line(s) ?: return; if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
                val (method, path) = first.split(' ')
                heads += "$first " + headers.entries.joinToString(";")
                val p = Regex("(\\w+)=(\"([^\"]*)\"|[^,\\s]+)").findAll(headers["authorization"].orEmpty()).associate { it.groupValues[1] to (it.groups[3]?.value ?: it.groupValues[2]) }
                val expected = md5("${md5("admin:cam:secret")}:$nonce:${p["nc"]}:${p["cnonce"]}:${p["qop"]}:${md5("$method:$path")}")
                if (p["response"] != expected || p["nonce"] != nonce) {
                    if (fresh) nonce = "n" + System.nanoTime()
                    reply(s, "401 Unauthorized", "", "WWW-Authenticate: Digest realm=\"cam\", qop=\"auth\", nonce=\"$nonce\", stale=\"FALSE\"\r\n")
                    if (headers["connection"] == "close" || method == "POST") { s.close(); return }
                    continue
                }
                if (fresh) nonce = "n" + System.nanoTime()
                requests += "$method $path"
                val text = answer(method, path)
                if (text != null) {
                    val status = text.substringBefore('|')
                    reply(s, status, text.substringAfter('|'))
                    if ("audioData" !in path || !status.startsWith("200")) { s.close(); return }
                }
                streams += s
                val buffer = ByteArray(512)
                while (true) { val n = s.getInputStream().read(buffer); if (n < 0) return; synchronized(audio) { audio.write(buffer, 0, n) } }
            }
        }

        fun reply(s: Socket, status: String, body: String, extra: String = "") {
            s.getOutputStream().write("HTTP/1.1 $status\r\n${extra}Content-Length: ${body.toByteArray().size}\r\n\r\n$body".toByteArray())
            s.getOutputStream().flush()
        }

        val bytes get() = synchronized(audio) { audio.toByteArray() }
    }

    private fun channels(vararg codecs: String) = "200 OK|<TwoWayAudioChannelList xmlns=\"http://www.hikvision.com/ver20/XMLSchema\">" +
        codecs.withIndex().joinToString("") { (i, c) -> "<TwoWayAudioChannel version=\"2.0\"><id>${i + 1}</id><enabled>false</enabled><audioCompressionType>$c</audioCompressionType></TwoWayAudioChannel>" } +
        "</TwoWayAudioChannelList>"

    private fun hikvision(codec: String = "G.711ulaw", openStatuses: MutableList<String> = mutableListOf(), audio: String = "200 OK|") = Camera { _, path ->
        when {
            path.endsWith("/channels") -> channels(codec)
            path.endsWith("/open") -> (openStatuses.removeFirstOrNull() ?: "200 OK") + "|<TwoWayAudioSession><sessionId>2093716360</sessionId></TwoWayAudioSession>"
            path.endsWith("/close") -> "200 OK|<ResponseStatus><statusCode>1</statusCode></ResponseStatus>"
            "audioData" in path -> audio
            else -> "404 Not Found|"
        }
    }

    private fun dahua(speakers: Int = 1, post: String? = null) = Camera { method, path ->
        when {
            "devAudioOutput" in path -> "200 OK|result=$speakers\r\n"
            method == "POST" && "postAudio" in path -> post
            else -> "404 Not Found|"
        }
    }

    private val hikRtsp = "rtsp://127.0.0.1:554/Streaming/Channels/101"
    private val dahuaRtsp = "rtsp://127.0.0.1:554/cam/realmonitor?channel=1&subtype=0"

    private fun open(rtsp: String = hikRtsp) = HttpTalk.open(rtsp, "127.0.0.1", "admin", "secret")?.also { talks += it }

    private fun until(text: String, ms: Long = 4_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { assertTrue(text, System.currentTimeMillis() < end); Thread.sleep(10) }
    }

    private fun speak(talk: TalkChannel, amplitude: Int = 8_000) {
        talk.beginTalkspurt()
        val pcm = ShortArray(1_600) { (amplitude * kotlin.math.sin(2 * Math.PI * 400 * it / 8_000)).toInt().toShort() }
        talk.send(pcm, pcm.size)
    }

    @Test fun hikvisionSpeaksOverIsapi() {
        val camera = hikvision()
        val talk = open()!!
        assertEquals("Hikvision (ISAPI)", talk.path)
        assertEquals(listOf("GET /ISAPI/System/TwoWayAudio/channels", "PUT /ISAPI/System/TwoWayAudio/channels/1/open",
            "PUT /ISAPI/System/TwoWayAudio/channels/1/audioData?sessionId=2093716360"), camera.requests)
        until("тишина идёт сразу") { camera.bytes.size >= 480 }
        assertTrue("тишина μ-law", camera.bytes.take(160).all { it == 0xFF.toByte() })
        speak(talk)
        until("голос ушёл") { talk.sentVoice >= 10 }
        val expected = G711.ulaw((8_000 * kotlin.math.sin(2 * Math.PI * 400 * 5 / 8_000)).toInt().toShort().toInt())
        until("голос дошёл") { camera.bytes.any { it == expected } }
        assertTrue(talk.alive)
        assertFalse("пароль не уходит открытым текстом", camera.heads.any { "secret" in it || "Basic" in it })
        // Ровно по времени: за секунду — около 8000 байт, а не рывком.
        val from = camera.bytes.size
        Thread.sleep(1_000)
        val perSecond = camera.bytes.size - from
        assertTrue("$perSecond", perSecond in 6_000..10_000)
        talk.close()
        until("сеанс закрыт") { camera.requests.last().endsWith("/channels/1/close") }
    }

    @Test fun alawAndClosedByCamera() {
        val camera = hikvision("G.711alaw")
        val talk = open()!!
        until("A-law") { camera.bytes.size >= 320 && camera.bytes.all { it == 0xD5.toByte() } }
        camera.streams.single().close()
        until("обрыв замечен") { !talk.alive }
    }

    /** Прошлая сессия не закрыта (приложение убили): закрыть и открыть заново — один раз. */
    @Test fun unclosedSessionIsClosedFirst() {
        val camera = hikvision(openStatuses = mutableListOf("403 Forbidden"))
        open()!!
        assertEquals(listOf("open", "close", "open"), camera.requests.map { it.substringAfterLast('/') }.filter { it == "open" || it == "close" })
        servers.forEach { it.close() }
        val busy = hikvision(openStatuses = mutableListOf("503 Service Unavailable", "503 Service Unavailable"))
        val e = runCatching { open() }.exceptionOrNull()
        assertEquals("Сейчас говорит другое устройство", e?.message)
        assertEquals("не перебирает", 2, busy.requests.count { it.endsWith("/open") })
    }

    @Test fun unsupportedCodecIsExplained() {
        hikvision("AAC")
        val e = runCatching { open() }.exceptionOrNull()
        assertTrue("${e?.message}", e is TalkException && e.message!!.contains("AAC") && e.message!!.contains("G.711"))
    }

    @Test fun refusedAudioClosesSession() {
        val camera = hikvision(audio = "403 Forbidden|")
        val e = runCatching { open() }.exceptionOrNull()
        assertEquals("Камера не приняла голос (HTTP 403)", e?.message)
        assertTrue("сеанс закрыт", camera.requests.last().endsWith("/close"))
    }

    @Test fun dahuaSpeaksWithDeclaredLength() {
        val camera = dahua().apply { fresh = true }
        val talk = open(dahuaRtsp)!!
        assertEquals("Dahua (CGI)", talk.path)
        until("A-law идёт") { camera.bytes.size >= 480 }
        assertTrue(camera.bytes.take(160).all { it == 0xD5.toByte() })
        val head = camera.heads.last { "postAudio" in it }
        assertTrue(head, "content-type=Audio/G.711A" in head && "content-length=9999840" in head && "chunked" !in head)
        assertEquals("динамик спросили, звук — один раз", listOf("GET /cgi-bin/devAudioOutput.cgi?action=getCollect",
            "POST /cgi-bin/audio.cgi?action=postAudio&httptype=singlepart&channel=1"), camera.requests)
        speak(talk)
        until("голос") { talk.sentVoice >= 10 }
        assertTrue(talk.alive)
        // «Error» посреди разговора — канал закрыт.
        camera.reply(camera.streams.last(), "200 OK", "Error")
        until("отказ замечен") { !talk.alive }
    }

    @Test fun dahuaRefusals() {
        dahua(speakers = 0)
        assertEquals("У камеры нет динамика", runCatching { open(dahuaRtsp) }.exceptionOrNull()?.message)
        servers.forEach { it.close() }
        dahua(post = "400 Bad Request|Error")
        assertEquals("Камера не приняла голос (HTTP 400)", runCatching { open(dahuaRtsp) }.exceptionOrNull()?.message)
    }

    @Test fun otherCameraIsLeftAlone() {
        val camera = Camera { _, _ -> "404 Not Found|" }
        assertNull(open())
        assertEquals("спросили по разу", 2, camera.requests.size)
    }

    @Test fun wrongPasswordOnce() {
        val camera = hikvision()
        val e = runCatching { HttpTalk.open(hikRtsp, "127.0.0.1", "admin", "нет") }.exceptionOrNull()
        assertTrue("$e", e is TalkException && e.auth)
        assertEquals("без перебора: один вопрос и один ответ", 2, camera.heads.size)
    }

    // ---- Когда до этого доходит ----

    private class Silent(val error: Exception) : TalkChannel {
        override fun open(): String = throw error
        override fun beginTalkspurt() {}
        override fun send(pcm: ShortArray, count: Int) {}
        override val queued = 0
        override val alive = false
        override val sentPackets = 0
        override val sentVoice = 0
        override fun close() {}
    }

    @Test fun onlyAfterOnvifRefusesAndNeverForReolink() {
        var tries = 0
        TalkChannels.http = { _, _, _, _ -> tries++; null }
        fun attempt(rtsp: String, error: Exception): Throwable? {
            TalkChannels.onvif = { _, _, _, _ -> Silent(error) }
            return runCatching { TalkChannels.open(rtsp, "admin", "secret", 1_000) }.exceptionOrNull()
        }
        val none = TalkException(TalkSession.NO_CHANNEL)
        assertEquals("не умеет — прежнее объяснение", TalkSession.NO_CHANNEL, attempt(hikRtsp, none)?.message)
        assertEquals(1, tries)
        attempt(hikRtsp, RtspException("DESCRIBE: 400", 400)); assertEquals(2, tries)
        attempt(hikRtsp, RtspException("нет", 401)); attempt(hikRtsp, RtspException("занято", 453)); attempt(hikRtsp, java.net.ConnectException())
        assertEquals("пароль, «занято», нет связи — не повод", 2, tries)
        TalkChannels.reolink = { _, _, _ -> Silent(java.net.ConnectException()) }
        try { attempt("rtsp://127.0.0.1/Preview_01_main", none) } finally { TalkChannels.reolink = { h, u, p -> BaichuanTalkSession(h, u, p) } }
        assertEquals("Reolink — никогда", 2, tries)
    }

    @Test fun rememberedPathIsTriedFirst() {
        val camera = hikvision()
        var onvifTries = 0
        TalkChannels.onvif = { _, _, _, _ -> onvifTries++; Silent(TalkException(TalkSession.NO_CHANNEL)) }
        talks += TalkChannels.open(hikRtsp, "admin", "secret", 1_000)
        talks += TalkChannels.open(hikRtsp, "admin", "secret", 1_000)
        assertEquals("второй разговор — сразу по HTTP", 1, onvifTries)
        assertEquals(2, camera.requests.count { "audioData" in it })
    }
}
