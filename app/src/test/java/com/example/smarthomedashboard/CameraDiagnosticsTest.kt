package com.example.smarthomedashboard

import android.os.PowerManager
import com.example.smarthomedashboard.CameraDiagnostics.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки диагностики камеры (docs/diagnostics-tz.md): каждая находит своё на «плохом» примере и молчит на «хорошем».
 * Числа — из замеров домофона 24.11.2026 (линия 10 и 100 Мбит/с).
 */
class CameraDiagnosticsTest {
    private fun snap(
        codec: VideoDepacketizer.Codec? = VideoDepacketizer.Codec.H264, width: Int = 2560, height: Int = 1920, fps: Double? = 20.0,
        kbps: Long? = 6300, keyIntervalMs: Long? = 1000, keySamples: Int = 20, keyKb: Int? = 390, keySpanMs: Long? = 100,
        keyRate: Long? = 3800, packets: Long = 20_000, loss: Double? = 0.0, baseStep: Int? = 50, doubles: Double? = 0.0, late: Long? = 110
    ) = StreamSnapshot(60_000, codec, width, height, fps, kbps, keyIntervalMs, keyKb, keySamples, keyKb, keySpanMs, keyRate, packets, loss,
        baseStep, doubles, late)

    private val sub = snap(width = 640, height = 480, fps = 15.0, kbps = 525, keySamples = 0, keyKb = null, keySpanMs = null,
        keyRate = null, baseStep = 70, late = 95)
    /** Планшет как московский Samsung: аппаратный H.264 и H.265 до 1920×1088. */
    private val samsung = DecoderFacts(mapOf(VideoDepacketizer.Codec.H264 to (1920 to 1088), VideoDepacketizer.Codec.H265 to (1920 to 1088))) { _, w, h ->
        w <= 1920 && h <= 1088 || w <= 1088 && h <= 1920
    }
    private val show = ShowSummary(0, 30f, "c2.android.avc.decoder", true, 2560, 1920, 19.4f, 0.02f, 0)
    private val healthy = DiagInput(main = snap(), sub = sub, mainShows = listOf(show), subShows = listOf(show.copy(software = false,
        width = 640, height = 480, shownFps = 15f)), clockOffsetMs = 400, wifi = WifiFacts(-35, 5180, 433), thermal = 0, decoders = samsung)

    private fun check(input: DiagInput, id: String) = CameraDiagnostics.evaluate(input).single { it.id == id }

    @Test fun healthyDoorbellAfterLineFixHasNothingBad() {
        val checks = CameraDiagnostics.evaluate(healthy)
        assertEquals(14, checks.size)
        assertEquals(listOf("С1", "С2", "С3", "С4", "С5", "К1", "К2", "К3", "К4", "К5", "К6", "П1", "П2", "П3"), checks.map { it.id })
        assertTrue(checks.none { it.level == Level.BAD })
        // 2560×1920 — только программно, но успевает: «можно лучше», не «мешает».
        assertEquals(Level.IMPROVE, check(healthy, "К3").level)
        assertEquals("Можно лучше: 1", CameraDiagnostics.summary(checks))
    }

    @Test fun slowLineIsFoundWithAdvice() {
        // 24.11.2026 до починки: ключевой кадр 390 КБ шёл 0,39 с, ≈ 1 МБ/с.
        val bad = healthy.copy(main = snap(keySpanMs = 390, keyRate = 1000, late = 335))
        val line = check(bad, "С1")
        assertEquals(Level.BAD, line.level)
        assertTrue(line.detail, line.detail.contains("10 Мбит/с"))
        assertTrue(line.advice, line.advice.contains("Extend"))
        assertEquals(Level.IMPROVE, check(bad, "С3").level)
        assertTrue("совет С3 отсылает к линии", check(bad, "С3").advice.contains("С1"))
        assertEquals("6,3 Мбит/с из 10 — больше 60 %", Level.IMPROVE, check(bad.copy(main = bad.main!!.copy(kbps = 6300)), "С5").level)
        assertEquals(Level.OK, check(healthy, "С1").level)
        assertEquals(Level.OK, check(healthy, "С5").level)
        // Ключевой кадр поменьше (200 КБ, стенд D) на той же линии: 0,17 с — «можно лучше».
        assertEquals(Level.IMPROVE, check(healthy.copy(main = snap(keyKb = 200, keySpanMs = 170, keyRate = 1150)), "С1").level)
        // Быстрая линия с медленной камерой: кадр идёт 0,15 с, но 2,6 МБ/с — не линия.
        assertEquals(Level.OK, check(healthy.copy(main = snap(keySpanMs = 150, keyRate = 2600)), "С1").level)
        assertEquals(Level.UNKNOWN, check(healthy.copy(main = snap(keySamples = 1)), "С1").level)
        // Ключевые кадры меньше 100 КБ: линия почти не влияет (стенд «Dahua» 1080p).
        val small = check(healthy.copy(main = snap(keySamples = 0, keyKb = 60, keySpanMs = null, keyRate = null)), "С1")
        assertEquals(Level.OK, small.level)
        assertTrue(small.detail, small.detail.contains("60 КБ"))
    }

    @Test fun lossAndLatenessThresholds() {
        assertEquals(Level.OK, check(healthy, "С2").level)
        assertEquals(Level.IMPROVE, check(healthy.copy(main = snap(loss = 0.3)), "С2").level)
        assertEquals(Level.BAD, check(healthy.copy(main = snap(loss = 2.0), sub = sub.copy(lossPercent = 2.0)), "С2").level)
        assertEquals(Level.UNKNOWN, check(healthy.copy(main = snap(packets = 10), sub = sub.copy(packets = 10)), "С2").level)
        assertEquals(Level.OK, check(healthy, "С3").level)
        assertEquals(Level.BAD, check(healthy.copy(main = snap(late = 700)), "С3").level)
    }

    @Test fun weakWifiOfTablet() {
        assertEquals(Level.OK, check(healthy, "С4").level)
        assertEquals(Level.IMPROVE, check(healthy.copy(wifi = WifiFacts(-74, 5180, 120)), "С4").level)
        assertEquals(Level.IMPROVE, check(healthy.copy(wifi = WifiFacts(-55, 2437, 26)), "С4").level)
        assertEquals(Level.BAD, check(healthy.copy(wifi = WifiFacts(-84, 2437, 6)), "С4").level)
        assertEquals(Level.UNKNOWN, check(healthy.copy(wifi = null), "С4").level)
    }

    @Test fun rareKeyFramesAndDroppedSensorFrames() {
        assertEquals(Level.OK, check(healthy, "К1").level)
        // Стенд «Tapo»: ключевой раз в 4 с.
        assertEquals(Level.IMPROVE, check(healthy.copy(main = snap(keyIntervalMs = 4000)), "К1").level)
        assertEquals(Level.BAD, check(healthy.copy(sub = sub.copy(keyIntervalMs = 8000)), "К1").level)
        // Без потока — по ONVIF: GOP 80 при 20 к/с = раз в 4 с.
        val onvifOnly = healthy.copy(main = null, sub = null, encoder = Onvif.Stream("", 2560, 1920, "H264", "p", 20, 6000, 80))
        assertEquals(Level.IMPROVE, check(onvifOnly, "К1").level)
        // Домофон 20 к/с с датчика 25: шаги 40 и 80 мс.
        val jerky = check(healthy.copy(main = snap(baseStep = 40, doubles = 0.25)), "К2")
        assertEquals(Level.IMPROVE, jerky.level)
        assertTrue(jerky.advice, jerky.advice.contains("25") && jerky.advice.contains("12,5"))
        assertEquals(Level.OK, check(healthy, "К2").level)
    }

    @Test fun heavyMainStreamDependsOnWhetherTabletKeepsUp() {
        val fitting = healthy.copy(main = snap(width = 1920, height = 1080))
        assertEquals(Level.OK, check(fitting, "К3").level)
        val slow = healthy.copy(mainShows = listOf(show.copy(shownFps = 9f, droppedShare = 0.5f, longStalls = 6)))
        assertEquals(Level.BAD, check(slow, "К3").level)
        assertTrue(check(slow, "К3").advice.contains("1920×1088"))
        assertEquals(Level.BAD, check(healthy.copy(mainUndecodable = true), "К3").level)
        assertEquals(Level.BAD, check(healthy.copy(mainUndecodable = true), "П3").level)
        assertEquals(Level.OK, check(healthy, "П3").level)
    }

    @Test fun h265WithoutHardwareDecoder() {
        val noHevc = DecoderFacts(mapOf(VideoDepacketizer.Codec.H264 to (1920 to 1088))) { codec, w, h ->
            codec == VideoDepacketizer.Codec.H264 && w <= 1920 && h <= 1088
        }
        val hevc = healthy.copy(main = snap(codec = VideoDepacketizer.Codec.H265, width = 1920, height = 1080), decoders = noHevc)
        val c = check(hevc, "К4")
        assertEquals("H.264 того же размера планшет показал бы аппаратно", Level.BAD, c.level)
        assertTrue(c.advice.contains("H.264"))
        assertEquals(Level.OK, check(healthy.copy(main = snap(codec = VideoDepacketizer.Codec.H265, width = 1920, height = 1080)), "К4").level)
        assertEquals(Level.OK, check(healthy, "К4").level)
    }

    @Test fun subStreamAndClock() {
        assertEquals(Level.OK, check(healthy, "К5").level)
        assertEquals(Level.IMPROVE, check(healthy.copy(subMissing = true, sub = null), "К5").level)
        assertEquals(Level.IMPROVE, check(healthy.copy(sub = sub.copy(width = 1920, height = 1080)), "К5").level)
        assertEquals(Level.OK, check(healthy, "К6").level)
        val behind = check(healthy.copy(clockOffsetMs = -180_000), "К6")
        assertEquals(Level.BAD, behind.level)
        assertTrue(behind.detail, behind.detail.contains("отстают на 3 мин"))
        assertEquals(Level.IMPROVE, check(healthy.copy(clockOffsetMs = 12_000), "К6").level)
        assertEquals(Level.UNKNOWN, check(healthy.copy(clockOffsetMs = null), "К6").level)
    }

    @Test fun tabletStallsAndHeat() {
        assertEquals(Level.OK, check(healthy, "П1").level)
        val stalling = healthy.copy(mainShows = List(3) { show.copy(longStalls = 2) })
        assertEquals("6 за 90 с ≈ 20 за 5 мин", Level.BAD, check(stalling, "П1").level)
        assertEquals(Level.UNKNOWN, check(healthy.copy(mainShows = emptyList(), subShows = emptyList()), "П1").level)
        assertEquals(Level.BAD, check(healthy.copy(thermal = PowerManager.THERMAL_STATUS_SEVERE), "П2").level)
        assertEquals(Level.IMPROVE, check(healthy.copy(thermal = PowerManager.THERMAL_STATUS_MODERATE), "П2").level)
        assertEquals(Level.UNKNOWN, check(healthy.copy(thermal = null), "П2").level)
    }

    @Test fun nothingKnownMeansNoVerdict() {
        val checks = CameraDiagnostics.evaluate(DiagInput())
        assertTrue(checks.all { it.level == Level.UNKNOWN })
        assertNull(CameraDiagnostics.summary(checks))
    }

    @Test fun reportHasNoAddressesOrSecrets() {
        val bad = healthy.copy(main = snap(keySpanMs = 390, keyRate = 1000, late = 335))
        val text = CameraDiagnostics.report(CameraDiagnostics.evaluate(bad), ReportFacts("0.9.55", "Samsung SM-X216B, Android 16",
            "Reolink Video Doorbell PoE, прошивка v3.0.0.6460", bad.main, bad.sub, bad.wifi))
        assertTrue(text, text.contains("Основной поток: H.264, 2560×1920, 20 к/с, 6300 кбит/с, ключевой раз в 1 с"))
        assertTrue(text.contains("✗ С1. Скорость линии камеры"))
        assertTrue(text.contains("Что сделать: Проверьте на PoE-свитче"))
        assertFalse("адрес", Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""").containsMatchIn(text))
        assertFalse("ссылка", text.contains("rtsp", ignoreCase = true))
    }
}
