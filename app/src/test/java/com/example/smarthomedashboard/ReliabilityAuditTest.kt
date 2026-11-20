package com.example.smarthomedashboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Calendar
import java.util.TimeZone

/**
 * Аудит надёжности 20.11.2026 (замечания A1–A10): регрессионные тесты подтверждённых дефектов. Каждый падал
 * на коде до исправления.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReliabilityAuditTest {
    private val defaultZone: TimeZone = TimeZone.getDefault()

    @After fun restoreZone() = TimeZone.setDefault(defaultZone)

    // ---- A1: буфер предзаписи не растёт без предела ----

    private val keyFrame = VideoFrame(0, listOf(ByteArray(40_000)), key = true)
    private val params = VideoParams(VideoDepacketizer.Codec.H264, null, byteArrayOf(0x67, 0x42, 0, 0x1E), byteArrayOf(0x68, 0x01))

    @Test fun preRollWithoutKeyframesStaysBounded() {
        val buffer = PreRollBuffer(preUs = 10_000_000)
        buffer.addVideo(keyFrame, 0, params)
        // Камера долго не шлёт ключевых кадров (или потеряны): 2 000 кадров по 40 КБ ≈ 80 МБ. Массив один —
        // тесту память не нужна, буфер считает байты кадров.
        val payload = listOf(ByteArray(40_000))
        for (i in 1..2_000) buffer.addVideo(VideoFrame(i * 3_000L, payload, key = false), i * 33_333L, null)
        assertTrue("буфер ${buffer.bytes} байт", buffer.bytes <= PreRollBuffer.MAX_BYTES + 8L * 1024 * 1024)
    }

    @Test fun preRollContinuousAudioStaysBounded() {
        val buffer = PreRollBuffer(preUs = 10_000_000)
        buffer.addVideo(keyFrame, 0, params)
        // Видео встало, звук идёт: кадры звука сами очистку не запускали.
        val data = ByteArray(1_000)
        for (i in 1..100_000) buffer.addAudio(AudioFrame(i * 1024L, data), i * 64_000L)
        assertTrue("буфер ${buffer.bytes} байт", buffer.bytes <= PreRollBuffer.MAX_BYTES + 8L * 1024 * 1024)
    }

    @Test fun preRollRestartsFromNextKeyframeAfterOverflow() {
        val buffer = PreRollBuffer(preUs = 10_000_000)
        buffer.addVideo(keyFrame, 0, params)
        val payload = listOf(ByteArray(40_000))
        for (i in 1..2_000) buffer.addVideo(VideoFrame(i * 3_000L, payload, key = false), i * 33_333L, null)
        val nextKey = VideoFrame(2_001 * 3_000L, listOf(ByteArray(40_000)), key = true)
        buffer.addVideo(nextKey, 70_000_000L, params)
        buffer.addVideo(VideoFrame(2_002 * 3_000L, payload, key = false), 70_033_333L, null)
        // Ролик события начинается с ключевого кадра, которому есть параметры потока.
        val start = buffer.startFor(70_100_000L)
        assertNotNull(start)
        assertTrue(start!!.frame.key)
        assertEquals(70_000_000L, start.us)
        assertTrue((buffer.snapshot().first() as PreRollBuffer.Item.Video).frame.key)
    }

    // ---- A5: неизвестное расположение камеры ----

    @Test fun noOrdinaryInterfaceMeansLocalityUnknown() {
        val camera = InetAddress.getByName("192.168.1.20")
        // Wi-Fi ещё не поднялся (или только VPN): решить нельзя — раньше считалось «удалённая», и запись локальной
        // камеры останавливалась до следующей сверки.
        assertNull(RecLocality.isLocal(camera, emptyList()))
        assertNull(RecLocality.isLocal(camera, listOf(RecLocality.Iface("tun0", InetAddress.getByName("10.8.0.2"), 24))))
        assertEquals(true, RecLocality.isLocal(camera, listOf(RecLocality.Iface("wlan0", InetAddress.getByName("192.168.1.5"), 24))))
        assertEquals(false, RecLocality.isLocal(camera, listOf(RecLocality.Iface("wlan0", InetAddress.getByName("10.0.0.5"), 24))))
    }

    // ---- A8: сутки при переводе часов ----

    private fun localMs(zone: TimeZone, y: Int, m: Int, d: Int, h: Int, min: Int): Long = Calendar.getInstance(zone).apply {
        clear(); set(y, m - 1, d, h, min, 0)
    }.timeInMillis

    private fun seg(startMs: Long, durationMs: Long) =
        RecFile(File("c_${startMs}_$durationMs.mp4"), "door", RecFile.Kind.CONTINUOUS, startMs, durationMs, 1)

    @Test fun archiveDayCoversWholeLocalDayAcrossDst() {
        val zone = TimeZone.getTimeZone("Europe/Berlin")
        TimeZone.setDefault(zone)
        // 25.10.2026 — переход на зимнее время: в сутках 25 ч.
        val midnight = RecArchive.dayStart(localMs(zone, 2026, 10, 25, 12, 0))
        val late = seg(localMs(zone, 2026, 10, 25, 23, 30), 600_000)
        val day = ArchiveDay(midnight, listOf(late), emptyList())
        assertEquals("конец суток — местная полночь", localMs(zone, 2026, 10, 26, 0, 0), day.dayEndMs)
        assertEquals("отрезок 23:30 — в своих сутках", 1, day.segments.size)
        assertEquals(1f, day.fraction(day.dayEndMs), 0f)
        assertEquals(day.dayEndMs, day.timeAt(1f))
        // 29.03.2026 — переход на летнее: 23 ч, отрезок 00:10 следующих суток сюда не входит.
        val spring = RecArchive.dayStart(localMs(zone, 2026, 3, 29, 12, 0))
        val next = seg(localMs(zone, 2026, 3, 30, 0, 10), 600_000)
        assertEquals(0, ArchiveDay(spring, listOf(next), emptyList()).segments.size)
    }

    // ---- A3: приёмник вебхуков ----

    @Test fun slowHookClientIsDroppedByDeadline() {
        val port = ServerSocket(0).use { it.localPort }
        val server = CameraHookServer(port) { _, _, _ -> true }
        server.start()
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 200
                val out = s.getOutputStream()
                val started = System.currentTimeMillis()
                var closed = false
                // Медленный клиент: по байту раз в 300 мс, заголовок не кончается — раньше держал поток приёмника
                // часами (5 с ожидания на каждый байт, до 8 КБ).
                while (System.currentTimeMillis() - started < 7_000) {
                    try { out.write('P'.code); out.flush() } catch (_: Exception) { closed = true; break }
                    try { if (s.getInputStream().read() < 0) { closed = true; break } } catch (_: java.net.SocketTimeoutException) {
                    } catch (_: Exception) { closed = true; break }
                    Thread.sleep(100)
                }
                assertTrue("медленное соединение закрыто приёмником", closed)
                assertTrue("за ${System.currentTimeMillis() - started} мс", System.currentTimeMillis() - started < 6_000)
            }
        } finally {
            server.stop()
        }
    }
}
