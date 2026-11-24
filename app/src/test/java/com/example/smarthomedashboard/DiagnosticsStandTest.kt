package com.example.smarthomedashboard

import com.example.smarthomedashboard.CameraDiagnostics.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * Диагностика на стенде камер (tools/camera-stand, docs/diagnostics-tz.md «Приёмка»): каждая камера стенда — свои
 * привычки, каждая проверка должна найти своё и только своё. Только вручную: `DIAG_STAND=<узел стенда>`
 * (обычно 127.0.0.1), секунд на поток — `DIAG_STAND_SECONDS` (по умолчанию 20).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsStandTest {
    private val host = System.getenv("DIAG_STAND")
    private val seconds = System.getenv("DIAG_STAND_SECONDS")?.toIntOrNull() ?: 20
    /** Планшет как московский Samsung: аппаратный H.264 и H.265 до 1920×1088. */
    private val samsung = DecoderFacts(mapOf(VideoDepacketizer.Codec.H264 to (1920 to 1088), VideoDepacketizer.Codec.H265 to (1920 to 1088))) { _, w, h ->
        w <= 1920 && h <= 1088 || w <= 1088 && h <= 1920
    }

    private fun diagnose(port: Int, main: String, sub: String): Map<String, CameraDiagnostics.Check> {
        val pool = Executors.newFixedThreadPool(2)
        val m = pool.submit<StreamSnapshot> { StreamProbe.run("rtsp://$host:$port$main", "", null, seconds) { false } }
        val s = pool.submit<StreamSnapshot> { StreamProbe.run("rtsp://$host:$port$sub", "", null, seconds) { false } }
        val input = DiagInput(main = m.get(), sub = s.get(), wifi = WifiFacts(-40, 5180, 433), thermal = 0, decoders = samsung)
        pool.shutdown()
        val checks = CameraDiagnostics.evaluate(input)
        println("== порт $port: ${CameraDiagnostics.summary(checks)}")
        println("   основной: ${CameraDiagnostics.streamText(input.main!!)}; ключевой ${input.main.keyKb} КБ за ${input.main.keySpanMs} мс " +
            "(${input.main.keyRateKbPerSec} КБ/с), потери ${input.main.lossPercent} %, шаг ${input.main.baseStepMs} мс, двойных ${input.main.skipStepShare}, " +
            "неровность ${input.main.lateMs} мс")
        println("   субпоток: ${CameraDiagnostics.streamText(input.sub!!)}; неровность ${input.sub.lateMs} мс")
        for (c in checks) println("   ${c.level.mark} ${c.id} ${c.title}: ${c.detail}")
        return checks.associateBy { it.id }
    }

    /** Этап 4 на поддельном ONVIF камеры D: применить, вернуть, отказ и «да» без изменения. `DIAG_STAND_MEDIA` — папка роликов. */
    @Test fun applyAndRollbackOnStandOnvif() {
        assumeTrue("Только со стендом: DIAG_STAND=<узел>", !host.isNullOrBlank())
        val media = System.getenv("DIAG_STAND_MEDIA")
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val config = DoorbellConfig(host = host!!, onvifPort = 8580, rtspUser = "admin",
            rtspMain = "rtsp://$host:8557/Streaming/Channels/101", rtspSub = "rtsp://$host:8557/Streaming/Channels/102")
        fun mainFps() = Onvif.probe(host, 8580, "admin", "x").main!!.fps
        val info = Onvif.probe(host, 8580, "admin", "x")
        println("== ONVIF стенда: основной ${info.main!!.width}×${info.main!!.height}, ${info.main!!.fps} к/с, GOP ${info.main!!.gov}, " +
            "возможности ${info.main!!.options}")
        assertEquals(20, info.main!!.fps)
        assertEquals(100, info.main!!.options!!.govMax)
        assertEquals(8192, info.main!!.options!!.bitrateMax)
        val change = CameraDiagnostics.Change(true, CameraDiagnostics.Change.Field.FPS, "20", "25", "частота основного потока: 20 → 25 к/с")
        val done = CameraApply.apply(context, "stand", config, "x", change)
        println("   применить: ${done.ok} — ${done.text}")
        assertTrue(done.ok)
        assertEquals(25, mainFps())
        val back = CameraApply.rollback(context, "stand", config, "x")
        println("   вернуть: ${back.ok} — ${back.text}")
        assertTrue(back.ok)
        assertEquals(20, mainFps())
        if (media != null) {
            val mode = java.io.File(media, "onvif_mode")
            try {
                mode.writeText("refuse")
                val refused = CameraApply.apply(context, "stand", config, "x", change)
                println("   отказ: ${refused.text}")
                assertTrue(!refused.ok && refused.text.startsWith("Камера не приняла"))
                mode.writeText("ignore")
                val ignored = CameraApply.apply(context, "stand", config, "x", change)
                println("   «да» без изменения: ${ignored.text}")
                assertTrue(!ignored.ok && ignored.text.contains("не сменила"))
                assertEquals(20, mainFps())
            } finally { mode.delete() }
        }
    }

    @Test fun eachStandCameraShowsItsOwnHabits() {
        assumeTrue("Только со стендом: DIAG_STAND=<узел>", !host.isNullOrBlank())
        // A «Hikvision»: H.265 2560×1440 — больше аппаратного предела, H.265; линия и приход в порядке.
        val a = diagnose(8554, "/Streaming/Channels/101", "/Streaming/Channels/102")
        assertEquals(Level.IMPROVE, a.getValue("К3").level)
        assertTrue(a.getValue("К4").level != Level.OK)
        // B «Dahua»: H.264 1920×1080, ключевой раз в 2 с — всё в порядке.
        val b = diagnose(8555, "/cam/realmonitor?channel=1&subtype=0", "/cam/realmonitor?channel=1&subtype=1")
        for (id in listOf("С1", "С2", "С3", "К1", "К2", "К3", "К4", "К5")) assertTrue("B $id: ${b.getValue(id)}", b.getValue(id).level == Level.OK)
        // C «Tapo»: ключевой раз в 4 с.
        val c = diagnose(8556, "/stream1", "/stream2")
        assertEquals(Level.IMPROVE, c.getValue("К1").level)
        assertEquals(Level.OK, c.getValue("С2").level)
        // D «плохие условия»: линия 10 Мбит/с, потери 0,5 %, 20 к/с с датчика 25.
        val d = diagnose(8557, "/Streaming/Channels/101", "/Streaming/Channels/102")
        assertTrue("D С1: ${d.getValue("С1")}", d.getValue("С1").level == Level.BAD || d.getValue("С1").level == Level.IMPROVE)
        assertEquals(Level.IMPROVE, d.getValue("С2").level)
        assertEquals(Level.IMPROVE, d.getValue("К2").level)
        assertEquals(Level.OK, d.getValue("К1").level)
    }
}
