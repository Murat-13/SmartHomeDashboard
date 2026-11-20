package com.example.smarthomedashboard

import android.media.MediaFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Аудит надёжности 20.11.2026, A2: общий поток камеры ([SharedStream]) при подключении и отключении записей
 * во время идущей сессии.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedStreamAuditTest {
    private val server = ServerSocket(0)
    @Volatile private var sdp = ""

    private val url get() = "rtsp://127.0.0.1:${server.localPort}/stream"

    private val videoSdp = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=cam\r\na=control:*\r\n" +
        "m=video 0 RTP/AVP 96\r\na=control:trackID=1\r\na=rtpmap:96 H264/90000\r\n" +
        "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA==\r\n"
    private val pcmuSdp = videoSdp + "m=audio 0 RTP/AVP 0\r\na=control:trackID=2\r\na=rtpmap:0 PCMU/8000\r\n"

    @Before fun setUp() {
        // Кодер AAC в Robolectric: настоящий MediaCodec недоступен, достаточно, что кодер создаётся.
        ShadowMediaCodec.addEncoder(MediaFormat.MIMETYPE_AUDIO_AAC,
            ShadowMediaCodec.CodecConfig(4096, 4096) { input, output -> output.put(input) })
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { try { camera(s) } catch (_: Exception) {} finally { s.close() } }
            }
        }
    }

    @After fun tearDown() {
        server.close()
        ShadowMediaCodec.clearCodecs()
    }

    /** Камера без пароля: после PLAY — видео и (если есть) звук PCMU раз в 20 мс. */
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
                "OPTIONS" -> reply(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n")
                "DESCRIBE" -> reply(cseq, "Content-Type: application/sdp\r\nContent-Base: $url/\r\n", sdp)
                "SETUP" -> reply(cseq, "Session: 42;timeout=60\r\nTransport: ${headers["transport"]}\r\n")
                "PLAY" -> {
                    reply(cseq, "Session: 42\r\n")
                    val audio = sdp.contains("PCMU")
                    thread(isDaemon = true) {
                        var i = 0
                        try {
                            while (!s.isClosed) {
                                val v = RecordingTestSupport.rtp(96, true, i and 0xFFFF, i * 1800L, byteArrayOf(0x65, i.toByte(), 2, 3))
                                synchronized(out) { out.write(byteArrayOf('$'.code.toByte(), 0, (v.size shr 8).toByte(), v.size.toByte()) + v); out.flush() }
                                if (audio) {
                                    val a = RecordingTestSupport.rtp(0, false, i and 0xFFFF, i * 160L, ByteArray(160) { 0xFF.toByte() })
                                    synchronized(out) { out.write(byteArrayOf('$'.code.toByte(), 2, (a.size shr 8).toByte(), a.size.toByte()) + a); out.flush() }
                                }
                                i++
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

    private class NullSink : CameraRecorder.Sink {
        override var audioParams: AudioParams? = null
        override var videoClock: Int = 90_000
        override fun video(frame: VideoFrame, d: VideoDepacketizer) = Unit
        override fun audioArrival(ts: Long) = Unit
        override fun audio(frame: AudioFrame, rate: Int) = Unit
        override fun close() = Unit
    }

    /** Запись-наблюдатель: запоминает приёмники; [hold] — первый приёмник создаётся, только когда его отпустят. */
    private class Probe(url: String, audio: Boolean, sessions: (String, String, String?) -> RtspSession, val hold: CountDownLatch? = null) :
        CameraRecorder("door", url, "", null, audio, { _, _ -> }, sessions, { AacEncoder() }, "probe") {
        val sinks = CopyOnWriteArrayList<NullSink>()
        val entered = CountDownLatch(1)
        override fun newSink(): Sink {
            entered.countDown()
            hold?.await(5, TimeUnit.SECONDS)
            return NullSink().also { sinks += it }
        }
    }

    private fun waitFor(ms: Long, what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > until) fail("Не дождались: $what"); Thread.sleep(20) }
    }

    @Test fun recordingWithSoundJoiningG711StreamGetsSound() {
        sdp = pcmuSdp
        val sessions: (String, String, String?) -> RtspSession = { u, l, p -> RtspSession(u, l, p) }
        // 24/7 без звука держит поток (у камеры один поток — субпоток и основной совпадают).
        val quiet = Probe(url, audio = false, sessions)
        quiet.start()
        try {
            waitFor(5_000, "сессия камеры") { quiet.sinks.isNotEmpty() }
            // Запись событий со звуком подключается к идущей сессии (например, после изменения её настроек).
            val loud = Probe(url, audio = true, sessions)
            loud.start()
            try {
                waitFor(3_000, "приёмник записи со звуком") { loud.sinks.isNotEmpty() }
                assertNotNull("звук G.711 → AAC есть и у подключившейся позже записи", loud.sinks.first().audioParams)
            } finally { loud.stop() }
        } finally { quiet.stop() }
    }

    @Test fun recordingRemovedDuringSessionStartGetsNoSink() {
        sdp = videoSdp
        val go = CountDownLatch(1)
        val sessions: (String, String, String?) -> RtspSession = { u, l, p -> go.await(5, TimeUnit.SECONDS); RtspSession(u, l, p) }
        val hold = CountDownLatch(1)
        val first = Probe(url, audio = false, sessions, hold)
        val second = Probe(url, audio = false, sessions)
        first.start(); second.start()
        try {
            go.countDown()
            // Поток камеры раздаёт приёмники новой сессии и застрял на первой записи; вторую в это время останавливают.
            assertTrue(first.entered.await(5, TimeUnit.SECONDS))
            second.stop()
            hold.countDown()
            waitFor(3_000, "приёмник первой записи") { first.sinks.isNotEmpty() }
            Thread.sleep(300)
            assertEquals("остановленной записи приёмник не создаётся (он бы не закрылся)", 0, second.sinks.size)
        } finally {
            hold.countDown()
            first.stop()
        }
    }
}
