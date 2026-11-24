package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Сводка потока для диагностики ([StreamStats], docs/diagnostics-tz.md): числа, по которым судят проверки. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamStatsTest {
    /**
     * Поток как у основного потока домофона: [fps] к/с, ключевой раз в секунду ([keyBytes]) и обычные кадры по 20 КБ;
     * пакеты по 1400 байт идут со скоростью [bytesPerSec]. [sensorFps] — датчик, с которого камера берёт кадры.
     */
    private fun feed(stats: StreamStats, seconds: Int, fps: Int = 20, keyBytes: Int = 390 * 1024, bytesPerSec: Long = 1_000_000,
                     sensorFps: Int = fps, loseEvery: Int = 0, lateEverySec: Int = 0, lateMs: Long = 0, keyByPackets: Boolean = false) {
        var seq = 1000
        var packetNo = 0
        for (n in 0 until seconds * fps) {
            val sensorIndex = if (sensorFps == fps) n.toLong() else n.toLong() * sensorFps / fps
            val ts = 90_000L * sensorIndex / sensorFps
            val key = n % fps == 0
            val bytes = if (key) keyBytes else 20 * 1024
            val packets = (bytes + 1399) / 1400
            var at = sensorIndex * 1_000_000_000L / sensorFps + 30_000_000L
            if (lateEverySec > 0 && n % (lateEverySec * fps) == 5) at += lateMs * 1_000_000
            for (p in 0 until packets) {
                packetNo++
                if (loseEvery > 0 && packetNo % loseEvery == 0) { seq = (seq + 1) and 0xFFFF; continue }
                val length = if (p == packets - 1) bytes - 1400 * (packets - 1) else 1400
                stats.packet(seq, ts, length, at, keyByPackets && key)
                seq = (seq + 1) and 0xFFFF
                at += length * 1_000_000_000L / bytesPerSec
            }
            stats.frameEnd(ts, at, key && !keyByPackets)
        }
    }

    @Test fun keyFrameOnSlowLineIsSeen() {
        // 24.11.2026, линия 10 Мбит/с: ключевой кадр ≈ 390 КБ шёл 0,38–0,41 с (≈ 1 МБ/с).
        val stats = StreamStats()
        feed(stats, 30, bytesPerSec = 1_050_000)
        val s = stats.snapshot()
        assertTrue("ключевых ${s.keySamples}", s.keySamples >= 20)
        assertEquals(390, s.keyKb)
        assertTrue("идёт ${s.keySpanMs} мс", s.keySpanMs!! in 360..400)
        assertTrue("скорость ${s.keyRateKbPerSec}", s.keyRateKbPerSec!! in 950..1100)
        assertEquals(1000L, s.keyIntervalMs)
        assertTrue("частота ${s.fps}", kotlin.math.abs(s.fps!! - 20.0) < 0.5)
        assertEquals(0.0, s.lossPercent!!, 0.0)
    }

    @Test fun keyFrameOnFastLineIsFast() {
        // 100 Мбит/с: тот же кадр за ≈ 0,1 с (≈ 3,9 МБ/с — темп самой камеры).
        val stats = StreamStats()
        feed(stats, 30, bytesPerSec = 4_000_000)
        val s = stats.snapshot()
        assertTrue("идёт ${s.keySpanMs} мс", s.keySpanMs!! in 90..110)
        assertTrue("скорость ${s.keyRateKbPerSec}", s.keyRateKbPerSec!! > 3_500)
    }

    @Test fun smallKeyFramesSayNothingAboutLine() {
        // Субпоток: ключевой 32 КБ — по нему о линии не судим.
        val stats = StreamStats()
        feed(stats, 30, fps = 15, keyBytes = 32 * 1024, bytesPerSec = 1_000_000)
        val s = stats.snapshot()
        assertEquals(0, s.keySamples)
        assertNull(s.keyRateKbPerSec)
        assertEquals(1000L, s.keyIntervalMs)
    }

    @Test fun lostPacketsAreCountedByNumbers() {
        val stats = StreamStats()
        feed(stats, 30, bytesPerSec = 4_000_000, loseEvery = 200)
        val loss = stats.snapshot().lossPercent!!
        assertTrue("потери $loss %", loss in 0.45..0.55)
    }

    @Test fun keyFramesWithLostPacketsStillCount() {
        // Потеря одного пакета из 290 портит ключевой кадр для разборщика — но интервал ключевых кадров тот же.
        val stats = StreamStats()
        feed(stats, 30, bytesPerSec = 4_000_000, loseEvery = 150, keyByPackets = true)
        val s = stats.snapshot()
        assertEquals(1000L, s.keyIntervalMs)
        assertTrue("ключевых ${s.keySamples}", s.keySamples >= 20)
    }

    @Test fun keyPacketsByNalHeader() {
        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        fun h264(vararg v: Int) = b(*v).let { RtpKey.isKey(false, it, 0, it.size) }
        fun h265(vararg v: Int) = b(*v).let { RtpKey.isKey(true, it, 0, it.size) }
        assertTrue("IDR", h264(0x65, 0x88))
        assertTrue("SPS", h264(0x67, 0x64))
        assertTrue("FU-A начала IDR", h264(0x7C, 0x85, 0))
        assertTrue("FU-A середины IDR", h264(0x7C, 0x05, 0))
        assertFalse("FU-A обычного", h264(0x7C, 0x81, 0))
        assertTrue("STAP-A с SPS", h264(0x78, 0, 4, 0x67, 0x64))
        assertFalse("обычный", h264(0x41, 0x9A))
        assertTrue("IDR_W_RADL", h265(0x26, 0x01))
        assertTrue("VPS", h265(0x40, 0x01))
        assertTrue("FU IDR", h265(0x62, 0x01, 0x93))
        assertTrue("AP с VPS", h265(0x60, 0x01, 0, 4, 0x40, 0x01))
        assertFalse("TRAIL_R", h265(0x02, 0x01))
        assertFalse("FU обычного", h265(0x62, 0x01, 0x81))
    }

    @Test fun newNumberingIsNotLoss() {
        val stats = StreamStats()
        for (i in 0 until 1500) stats.packet(i, 1000L * i, 1000, i * 1_000_000L)
        stats.packet(40_000, 2_000_000, 1000, 2_000_000_000L)
        for (i in 1 until 10) stats.packet(40_000 + i, 2_000_000L + i, 1000, 2_000_000_000L + i)
        stats.packet(65_535, 3_000_000, 1000, 3_000_000_000L)
        stats.packet(0, 3_000_001, 1000, 3_000_000_001L)
        assertEquals(0.0, stats.snapshot().lossPercent!!, 0.0)
    }

    @Test fun twentyOfTwentyFiveGivesDoubleSteps() {
        // Домофон: датчик 25 к/с, поток 20 к/с — каждый пятый кадр датчика выбрасывается, шаги 40, 40, 40, 80 мс.
        val stats = StreamStats()
        feed(stats, 30, fps = 20, sensorFps = 25, bytesPerSec = 4_000_000)
        val s = stats.snapshot()
        assertEquals(40, s.baseStepMs)
        assertTrue("двойных ${s.doubleStepShare}", s.doubleStepShare!! in 0.2..0.3)
        val even = StreamStats().also { feed(it, 30, fps = 25, bytesPerSec = 4_000_000) }.snapshot()
        assertEquals(40, even.baseStepMs)
        assertEquals(0.0, even.doubleStepShare!!, 0.0)
    }

    @Test fun latenessIsSlowestMinusUsual() {
        val stats = StreamStats()
        feed(stats, 30, bytesPerSec = 4_000_000, lateEverySec = 5, lateMs = 300)
        val late = stats.snapshot().lateMs!!
        assertTrue("неровность $late", late in 280..330)
        val calm = StreamStats().also { feed(it, 30, bytesPerSec = 4_000_000) }.snapshot().lateMs!!
        assertTrue("ровно: $calm", calm < 120)
    }

    @Test fun minutesAreRemembered() {
        val stats = StreamStats()
        feed(stats, 200, bytesPerSec = 4_000_000, lateEverySec = 10, lateMs = 400)
        val s = stats.snapshot()
        assertTrue("наблюдали ${s.observedMs}", s.observedMs > 190_000)
        assertTrue("неровность ${s.lateMs}", s.lateMs!! in 380..430)
    }

    @Test fun emptyStatsHaveNoNumbers() {
        val s = StreamStats().snapshot()
        assertEquals(0L, s.observedMs)
        assertNull(s.fps); assertNull(s.lossPercent); assertNull(s.lateMs); assertNull(s.keyIntervalMs)
    }

    @Test fun sizeFromRealReolinkSps() {
        fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        assertEquals(640 to 480, VideoSize.parse(VideoDepacketizer.Codec.H264, hex("67640033ac1514a0a03da1000004f6000063380400")))
        assertEquals(2560 to 1920, VideoSize.parse(VideoDepacketizer.Codec.H264, hex("67640033ac1514a02800f190")))
        assertNull(VideoSize.parse(VideoDepacketizer.Codec.H264, hex("68ce3c80")))
    }

    @Test fun sizeFromH265Sps() {
        // SPS H.265 2560×1440 (Main, уровень 5.0, без подуровней), с окном обрезки 1440 → 1440 и без.
        fun sps(width: Int, height: Int, subLayers: Int = 0, cropBottom: Int = 0): ByteArray {
            val w = H264LowDelay.BitWriter()
            w.u(0, 4); w.u(subLayers, 3); w.bit(1)
            w.u(0, 2); w.bit(0); w.u(1, 5); w.u(0x60000000, 32); w.u(0b1001, 4); repeat(43) { w.bit(0) }; w.bit(0); w.u(150, 8)
            repeat(subLayers) { w.bit(1); w.bit(1) }
            if (subLayers > 0) for (i in subLayers until 8) w.u(0, 2)
            repeat(subLayers) { repeat(88) { w.bit(0) }; w.u(120, 8) }
            w.ue(0); w.ue(1); w.ue(width); w.ue(height + cropBottom * 2)
            if (cropBottom > 0) { w.bit(1); w.ue(0); w.ue(0); w.ue(0); w.ue(cropBottom) } else w.bit(0)
            w.trailing()
            return byteArrayOf(0x42, 0x01) + H264LowDelay.escape(w.bytes())
        }
        assertEquals(2560 to 1440, VideoSize.parse(VideoDepacketizer.Codec.H265, sps(2560, 1440)))
        assertEquals(1920 to 1080, VideoSize.parse(VideoDepacketizer.Codec.H265, sps(1920, 1080, cropBottom = 4)))
        assertEquals(1280 to 720, VideoSize.parse(VideoDepacketizer.Codec.H265, sps(1280, 720, subLayers = 2)))
    }

    @Test fun probeReadsStreamFromCamera() {
        // Проверка без общего потока: своё подключение к поддельной камере, сводка за время проверки.
        val camera = FakeRtspCamera.start()
        try {
            val s = StreamProbe.run(camera.url, "", null, 3) { false }
            assertNotNull(s.codec)
            assertTrue("кадров за ${s.observedMs} мс", s.observedMs > 1500)
        } finally { camera.stop() }
    }
}

/** Поддельная камера без пароля: H.264 (SPS домофона 2560×1920), 25 к/с, ключевой кадр раз в секунду. */
internal class FakeRtspCamera private constructor(private val server: java.net.ServerSocket) {
    val url get() = "rtsp://127.0.0.1:${server.localPort}/main"

    fun stop() = server.close()

    private fun camera(s: java.net.Socket) {
        val reader = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val out = s.getOutputStream()
        val sdp = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=cam\r\na=control:*\r\n" +
            "m=video 0 RTP/AVP 96\r\na=control:trackID=1\r\na=rtpmap:96 H264/90000\r\n" +
            "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA==\r\n"
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
                "DESCRIBE" -> reply(cseq, "Content-Type: application/sdp\r\nContent-Base: $url/\r\n", sdp)
                "SETUP" -> reply(cseq, "Session: 42;timeout=60\r\nTransport: ${headers["transport"]}\r\n")
                "PLAY" -> {
                    reply(cseq, "Session: 42\r\n")
                    kotlin.concurrent.thread(isDaemon = true) {
                        var seq = 0
                        var frame = 0
                        try {
                            while (!s.isClosed) {
                                val key = frame % 25 == 0
                                val nals = if (key) listOf(SPS, PPS, byteArrayOf(0x65, 1, 2, 3)) else listOf(byteArrayOf(0x41, 1, 2, 3))
                                for ((index, nal) in nals.withIndex()) {
                                    val p = RecordingTestSupport.rtp(96, index == nals.lastIndex, seq and 0xFFFF, frame * 3600L, nal)
                                    synchronized(out) { out.write(byteArrayOf('$'.code.toByte(), 0, (p.size shr 8).toByte(), p.size.toByte()) + p); out.flush() }
                                    seq++
                                }
                                frame++
                                Thread.sleep(40)
                            }
                        } catch (_: Exception) {}
                    }
                }
                "TEARDOWN" -> { reply(cseq); return }
                else -> reply(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n")
            }
        }
    }

    companion object {
        private val SPS: ByteArray = java.util.Base64.getDecoder().decode("Z2QAM6wVFKAoAPGQ")
        private val PPS: ByteArray = java.util.Base64.getDecoder().decode("aO48sA==")

        fun start(): FakeRtspCamera {
            val camera = FakeRtspCamera(java.net.ServerSocket(0))
            kotlin.concurrent.thread(isDaemon = true) {
                while (!camera.server.isClosed) {
                    val s = try { camera.server.accept() } catch (_: Exception) { break }
                    kotlin.concurrent.thread(isDaemon = true) { try { camera.camera(s) } catch (_: Exception) {} finally { s.close() } }
                }
            }
            return camera
        }
    }
}
