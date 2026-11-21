package com.example.smarthomedashboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// Этап 4 (docs/recording.md): общий поток камеры — одно подключение на поток для всех записей и живого видео
// через локальный ретранслятор.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordingHubTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = ServerSocket(0)
    private val connections = AtomicInteger()
    private val sent = AtomicInteger()
    /** Каждый такой по счёту кадр камеры — ключевой (SPS, PPS, IDR тремя пакетами); 0 — ключевых нет. */
    @Volatile private var keyEvery = 0

    private val sdp = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=cam\r\na=control:*\r\n" +
        "m=video 0 RTP/AVP 96\r\na=control:trackID=1\r\na=rtpmap:96 H264/90000\r\n" +
        "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA==\r\n" +
        "m=audio 0 RTP/AVP 97\r\na=control:trackID=2\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\n" +
        "a=fmtp:97 mode=AAC-hbr;sizelength=13;indexlength=3;config=1408\r\n" +
        "m=application 0 RTP/AVP 107\r\na=control:trackID=3\r\na=rtpmap:107 vnd.onvif.metadata/90000\r\n"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                connections.incrementAndGet()
                thread(isDaemon = true) { try { camera(s) } catch (_: Exception) {} finally { s.close() } }
            }
        }
    }

    @After fun tearDown() = server.close()

    private val url get() = "rtsp://127.0.0.1:${server.localPort}/Preview_01_sub"

    /** Камера без пароля: после PLAY шлёт пакет видео раз в 20 мс, пока не закроют. */
    private fun camera(s: Socket) {
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val out = s.getOutputStream()
        fun reply(cseq: String, headers: String = "", body: String = "") {
            val b = body.toByteArray()
            synchronized(out) {
                out.write(("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$headers" + (if (b.isNotEmpty()) "Content-Length: ${b.size}\r\n" else "") + "\r\n").toByteArray() + b)
                out.flush()
            }
        }
        while (true) {
            val first = reader.readLine() ?: return
            if (first.isEmpty()) continue
            val headers = mutableMapOf<String, String>()
            while (true) { val l = reader.readLine() ?: return; if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
            val cseq = headers["cseq"].orEmpty()
            when (first.substringBefore(' ')) {
                "OPTIONS" -> reply(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n")
                "DESCRIBE" -> reply(cseq, "Content-Type: application/sdp\r\nContent-Base: $url/\r\n", sdp)
                "SETUP" -> reply(cseq, "Session: 42;timeout=60\r\nTransport: ${headers["transport"]}\r\n")
                "PLAY" -> {
                    reply(cseq, "Session: 42\r\n")
                    thread(isDaemon = true) {
                        var i = 0
                        var frame = 0
                        try {
                            while (!s.isClosed) {
                                val key = keyEvery > 0 && frame % keyEvery == 0
                                val nals = if (key) listOf(SPS, PPS, byteArrayOf(0x65, i.toByte(), 2, 3)) else listOf(byteArrayOf(0x41, i.toByte(), 2, 3))
                                for ((index, nal) in nals.withIndex()) {
                                    val p = RecordingTestSupport.rtp(96, index == nals.lastIndex, i and 0xFFFF, frame * 3000L, nal)
                                    synchronized(out) { out.write(byteArrayOf('$'.code.toByte(), 0, (p.size shr 8).toByte(), p.size.toByte()) + p); out.flush() }
                                    sent.incrementAndGet()
                                    i++
                                }
                                frame++
                                Thread.sleep(20)
                            }
                        } catch (_: Exception) {}
                    }
                }
                "TEARDOWN" -> { reply(cseq); return }
                else -> reply(cseq)
            }
        }
    }

    private fun recorder(audio: Boolean) = ContinuousRecorder("door", url, "", null, audio, root = { tmp.root }, canWrite = { true },
        onSegment = {}, onStatus = { _, _ -> })

    private fun waitFor(ms: Long, what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > until) fail("Не дождались: $what"); Thread.sleep(20) }
    }

    @Test fun relaySdpKeepsOnlySetUpTracksWithLocalControls() {
        val tracks = SdpParser.parse(sdp)
        val setups = listOf(RtspSession.Setup(tracks[0], 0), RtspSession.Setup(tracks[1], 2))
        val out = RtspRelay.sdpFor(sdp, setups)
        val parsed = SdpParser.parse(out)
        assertEquals(listOf("H264", "MPEG4-GENERIC"), parsed.map { it.encoding })
        assertEquals(listOf("track0", "track1"), parsed.map { it.control })
        assertTrue(out.contains("sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA=="))
        assertFalse("метаданные ONVIF не отданы", out.contains("onvif"))
        assertTrue(out.contains("a=control:*"))
        assertFalse("адреса камеры не утекают в описание", out.contains("trackID"))
    }

    @Test fun twoRecordingsAndLiveViewShareOneCameraConnection() {
        val a = recorder(audio = true)
        val b = recorder(audio = false)
        a.start(); b.start()
        try {
            assertEquals(1, CameraHub.openStreams)
            waitFor(5_000, "поток пошёл") { CameraHub.relayUrl(url, "") != null }
            val relay = CameraHub.relayUrl(url, "")!!
            assertTrue(relay.startsWith("rtsp://127.0.0.1:"))
            // Живое видео через планшет: те же пакеты камеры, без второго подключения к ней.
            val viewer = RtspSession(relay, "", null, readTimeoutMs = 3_000)
            val setups = viewer.open { true }
            assertEquals(listOf("H264", "MPEG4-GENERIC"), setups.map { it.track.encoding })
            var received = 0
            val payloads = mutableListOf<Int>()
            try {
                viewer.read(object : RtspSession.Sink {
                    override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {
                        val rtp = RtpPacket.parse(packet, length)!!
                        assertEquals(96, packet[1].toInt() and 0x7F)
                        payloads += rtp.payload[rtp.offset + 1].toInt() and 0xFF
                        if (++received >= 10) viewer.close()
                    }
                })
            } catch (_: Exception) {}
            assertTrue("пакетов через ретранслятор: $received", received >= 10)
            assertTrue("идут по порядку", payloads.zipWithNext().all { (x, y) -> (y - x + 256) % 256 == 1 })
            assertEquals("к камере одно подключение на две записи и плеер", 1, connections.get())
        } finally {
            a.stop(); b.stop()
        }
        assertEquals(0, CameraHub.openStreams)
        assertNull("записи нет — смотреть напрямую", CameraHub.relayUrl(url, ""))
    }

    /**
     * Быстрый старт плитки: плеер, подключившийся посреди группы кадров, сразу получает её ключевой кадр (а не ждёт
     * следующего до 4 с), и дальше живые пакеты идут за сохранёнными без пропуска и повторов.
     */
    @Test fun relayViewerStartsFromLastKeyFrameWithoutGap() {
        keyEvery = 10
        val a = recorder(audio = false)
        a.start()
        try {
            waitFor(5_000, "поток пошёл") { CameraHub.relayUrl(url, "") != null }
            waitFor(5_000, "прошёл второй ключевой кадр и несколько обычных") { sent.get() >= 18 }
            Thread.sleep(100)
            val viewer = RtspSession(CameraHub.relayUrl(url, "")!!, "", null, readTimeoutMs = 3_000)
            viewer.open { it.isVideo }
            val sentBefore = sent.get()
            val types = mutableListOf<Int>()
            val sequences = mutableListOf<Int>()
            try {
                viewer.read(object : RtspSession.Sink {
                    override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {
                        val rtp = RtpPacket.parse(packet, length)!!
                        types += rtp.payload[rtp.offset].toInt() and 0x1F
                        sequences += rtp.sequence
                        if (sequences.size >= 30) viewer.close()
                    }
                })
            } catch (_: Exception) {}
            assertEquals("первым — ключевой кадр целиком: SPS, PPS, IDR", listOf(7, 8, 5), types.take(3))
            assertTrue("это уже прошедший ключевой кадр, а не следующий: ${sequences.first()} из $sentBefore", sequences.first() < sentBefore)
            assertTrue("сохранённое и живое идут подряд: $sequences", sequences.size >= 30 && sequences.zipWithNext().all { (x, y) -> y == x + 1 })
        } finally {
            a.stop()
        }
    }

    /**
     * Старт через общий поток: пока запись запускается, подключается к камере или ждёт первый ключевой кадр, живому
     * видео стоит подождать; с ключевым кадром — смотреть через планшет.
     */
    @Test fun liveViewWaitsForSharedStreamUntilFirstKeyFrame() {
        assertFalse("записи нет и не будет — ждать нечего", CameraHub.startingUp(url, "", recordingStarting = false))
        assertTrue("запись на планшете запускается", CameraHub.startingUp(url, "", recordingStarting = true))
        keyEvery = 10
        val a = recorder(audio = false)
        a.start()
        try {
            assertTrue("поток записи подключается к камере", CameraHub.startingUp(url, "", recordingStarting = false))
            waitFor(5_000, "ключевой кадр прошёл") { !CameraHub.startingUp(url, "", recordingStarting = false) }
            assertNotNull(CameraHub.relayUrl(url, ""))
            assertFalse("другой логин — другой поток", CameraHub.startingUp(url, "other", recordingStarting = false))
        } finally {
            a.stop()
        }
        assertFalse(CameraHub.startingUp(url, "", recordingStarting = false))
    }

    @Test fun relayRejectsUnknownAddress() {
        val a = recorder(audio = false)
        a.start()
        try {
            waitFor(5_000, "поток пошёл") { CameraHub.relayUrl(url, "") != null }
            val relay = CameraHub.relayUrl(url, "")!!
            val wrong = relay.substringBeforeLast('/') + "/0123456789abcdef0123456789abcdef"
            try { RtspSession(wrong, "", null, readTimeoutMs = 2_000).open { true }; fail() } catch (e: RtspException) {
                assertTrue(e.message!!.contains("404"))
            }
            // Другой логин — другой поток (не тот, что держит запись).
            assertNull(CameraHub.relayUrl(url, "other"))
        } finally {
            a.stop()
        }
    }

    @Test fun lastRecordingStoppedClosesCameraConnectionAndRelayClients() {
        val a = recorder(audio = false)
        a.start()
        waitFor(5_000, "поток пошёл") { CameraHub.relayUrl(url, "") != null }
        val viewer = RtspSession(CameraHub.relayUrl(url, "")!!, "", null, readTimeoutMs = 5_000)
        viewer.open { true }
        val reader = thread { try { viewer.read(object : RtspSession.Sink { override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {} }) } catch (_: Exception) {} }
        Thread.sleep(200)
        a.stop()
        reader.join(3_000)
        assertFalse("плеер ретранслятора отключён вместе с потоком", reader.isAlive)
        val before = sent.get()
        Thread.sleep(300)
        assertTrue("камера больше не шлёт: ${sent.get() - before}", sent.get() - before <= 2)
        assertNotNull(a.status)
        assertEquals(CameraRecorder.State.STOPPED, a.status.state)
        viewer.close()
    }

    private companion object {
        /** Параметры видео из описания потока (sprop-parameter-sets): с ними запись принимает ключевой кадр. */
        val SPS: ByteArray = java.util.Base64.getDecoder().decode("Z2QAM6wVFKAoAPGQ")
        val PPS: ByteArray = java.util.Base64.getDecoder().decode("aO48sA==")
    }
}
