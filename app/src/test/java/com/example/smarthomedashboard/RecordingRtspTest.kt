package com.example.smarthomedashboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Запись с камер: RTSP-клиент против поддельной камеры (Digest как у Reolink, TCP interleaved, keep-alive, молчание).
class RecordingRtspTest {
    private val server = ServerSocket(0)
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private var behaviour: (Socket) -> Unit = {}

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { try { behaviour(s) } catch (_: Exception) {} finally { s.close() } }
            }
        }
    }

    @After fun tearDown() = server.close()

    private val url get() = "rtsp://127.0.0.1:${server.localPort}/h264Preview_01_sub"

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private val sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=control:trackID=1\r\na=rtpmap:96 H264/90000\r\n" +
        "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA==\r\n" +
        "m=audio 0 RTP/AVP 97\r\na=control:trackID=2\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\n" +
        "a=fmtp:97 mode=AAC-hbr;sizelength=13;indexlength=3;config=1408\r\n"

    /** Камера: Digest, затем RTP; [packets] кадров видео, потом молчание [silentMs] или закрытие. */
    private fun camera(packets: Int, silentMs: Long = 0, keepAlive: CountDownLatch? = null): (Socket) -> Unit = { s ->
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val out = s.getOutputStream()
        fun reply(cseq: String, status: String, headers: String = "", body: String = "") {
            val b = body.toByteArray()
            out.write(("RTSP/1.0 $status\r\nCSeq: $cseq\r\n$headers" +
                (if (b.isNotEmpty()) "Content-Length: ${b.size}\r\n" else "") + "\r\n").toByteArray() + b)
            out.flush()
        }
        var playing = false
        loop@ while (true) {
            val first = reader.readLine() ?: break
            if (first.isEmpty()) continue
            val headers = mutableMapOf<String, String>()
            while (true) { val l = reader.readLine() ?: break@loop; if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
            requests += first
            val method = first.substringBefore(' ')
            val uri = first.split(' ')[1]
            val cseq = headers["cseq"].orEmpty()
            if (method == "GET_PARAMETER") { reply(cseq, "200 OK"); keepAlive?.countDown(); continue }
            if (method == "TEARDOWN") { reply(cseq, "200 OK"); break }
            val auth = headers["authorization"]
            val realm = "IP Camera(23435)"
            val nonce = "abc123"
            val expected = md5("${md5("admin:$realm:secret")}:$nonce:${md5("$method:$uri")}")
            if (auth == null || !auth.contains("response=\"$expected\"")) {
                reply(cseq, "401 Unauthorized", "WWW-Authenticate: Basic realm=\"$realm\"\r\n" +
                    "WWW-Authenticate: Digest realm=\"$realm\", nonce=\"$nonce\", stale=\"FALSE\"\r\n")
                continue
            }
            when (method) {
                "OPTIONS" -> reply(cseq, "200 OK", "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n")
                "DESCRIBE" -> reply(cseq, "200 OK", "Content-Type: application/sdp\r\nContent-Base: $url/\r\n", sdp)
                "SETUP" -> reply(cseq, "200 OK", "Session: 7788;timeout=10\r\nTransport: ${headers["transport"]}\r\n")
                "PLAY" -> { reply(cseq, "200 OK", "Session: 7788\r\n"); playing = true }
            }
            if (playing) {
                // Поток: кадры видео каналом 0, RTCP каналом 1 (должен игнорироваться как RTP).
                for (i in 0 until packets) {
                    val nal = byteArrayOf(0x65, 1, 2, 3)
                    val p = RecordingTestSupport.rtp(96, true, i, i * 3000L, nal)
                    out.write(byteArrayOf('$'.code.toByte(), 0, (p.size shr 8).toByte(), p.size.toByte()) + p)
                    out.write(byteArrayOf('$'.code.toByte(), 1, 0, 8, 0x80.toByte(), 200.toByte(), 0, 1, 0, 0, 0, 0))
                }
                out.flush()
                if (silentMs > 0) {
                    s.soTimeout = silentMs.toInt()
                    try { while (true) { val l = reader.readLine() ?: break; if (l.startsWith("GET_PARAMETER")) { requests += l; keepAlive?.countDown() } } } catch (_: SocketTimeoutException) {}
                }
                break
            }
        }
    }

    @Test fun digestAuthSetupBothTracksAndReceiveRtp() {
        behaviour = camera(packets = 5)
        val session = RtspSession(url, "admin", "secret", readTimeoutMs = 2_000)
        val setups = session.open { true }
        assertEquals(2, setups.size)
        assertEquals(listOf(0, 2), setups.map { it.rtpChannel })
        var video = 0
        var rtcp = 0
        try {
            session.read(object : RtspSession.Sink {
                override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) { if (setup.track.isVideo) video++ }
                override fun onRtcp(setup: RtspSession.Setup, packet: ByteArray, length: Int) { rtcp++ }
            })
            fail("Конец потока должен завершить чтение ошибкой")
        } catch (_: java.io.IOException) {}
        session.close()
        assertEquals(5, video); assertEquals(5, rtcp)
        val methods = requests.map { it.substringBefore(' ') }
        assertTrue(methods.containsAll(listOf("OPTIONS", "DESCRIBE", "SETUP", "PLAY")))
        // Дорожки по относительному control от Content-Base.
        assertTrue(requests.any { it.startsWith("SETUP $url/trackID=1 ") })
        assertTrue(requests.any { it.startsWith("SETUP $url/trackID=2 ") })
    }

    @Test fun wrongPasswordIsReportedNotRetriedForever() {
        behaviour = camera(packets = 0)
        val session = RtspSession(url, "admin", "wrong", readTimeoutMs = 2_000)
        try { session.open { true }; fail() } catch (e: RtspException) {
            assertEquals(401, e.status)
            assertTrue(e.message!!.contains("пароль"))
        }
        session.close()
    }

    @Test fun silentCameraTimesOutAndKeepAliveIsSent() {
        val latch = CountDownLatch(1)
        behaviour = camera(packets = 1, silentMs = 8_000, keepAlive = latch)
        var now = 0L
        val session = RtspSession(url, "admin", "secret", readTimeoutMs = 1_500, clock = { now })
        session.open { it.isVideo }
        val started = System.nanoTime()
        try {
            session.read(object : RtspSession.Sink {
                override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) { now += 60_000 } // «время прошло»
            })
            fail()
        } catch (_: SocketTimeoutException) {}
        val waited = (System.nanoTime() - started) / 1_000_000
        assertTrue("таймаут чтения ~1,5 с, было $waited мс", waited in 1_000..5_000)
        assertTrue("keep-alive не отправлен", latch.await(2, TimeUnit.SECONDS))
        session.close()
    }

    @Test fun closedPortFailsFastWithinConnectTimeout() {
        val port = ServerSocket(0).use { it.localPort }
        val started = System.nanoTime()
        try { RtspSession("rtsp://127.0.0.1:$port/x", "", null).open { true }; fail() } catch (_: java.io.IOException) {}
        assertTrue((System.nanoTime() - started) / 1_000_000 < 4_500)
    }
}
