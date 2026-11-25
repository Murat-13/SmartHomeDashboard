package com.example.smarthomedashboard

import com.example.smarthomedashboard.RecordingTestSupport.accessUnits
import com.example.smarthomedashboard.RecordingTestSupport.nals
import com.example.smarthomedashboard.RecordingTestSupport.packetize
import com.example.smarthomedashboard.RecordingTestSupport.resource
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.util.Collections
import kotlin.math.abs

/**
 * Часы планшета и записи (аудит 25.11.2026, REC-03). Время отрезка считалось от начала подключения к камере: часы поправились позже
 * (планшет включили без интернета) — весь архив до следующего обрыва подписан не тем временем. И очистка по сроку верила любым часам.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingClockTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = 86_400_000L

    /** Непрерывная запись [seconds] секунд с отрезками по 10 с; [clock] — часы планшета в момент потока (мс). */
    private fun record(seconds: Int, clock: (Long) -> Long): List<RecFile> {
        val root = tmp.newFolder()
        val segments = Collections.synchronizedList(mutableListOf<RecFile>())
        val r = ContinuousRecorder("door", "rtsp://cam/sub", "", null, audio = false, root = { root }, canWrite = { true },
            onSegment = { segments += it }, onStatus = { _, _ -> }, segmentMs = 10_000)
        val (frames, d) = sample()
        val sink = r.newSink().apply { videoClock = 90_000 }
        for (i in 0 until seconds * 10) {
            val ms = i * 100L
            r.wallClock = { clock(ms) }
            val base = frames[i % frames.size]
            sink.video(VideoFrame(i * 9_000L, base.nals, base.key), d)
        }
        sink.close()
        assertTrue(RecIo.flush(5_000))
        return segments.sortedBy { it.startMs }
    }

    private val start = 1_763_490_000_000L

    /** Часы поправились на 3 часа через 25 с — отрезок с 30-й секунды подписан новым временем. */
    @Test fun segmentsAfterClockCorrectionHaveNewTime() {
        val files = record(60) { ms -> start + ms + if (ms >= 25_000) 3 * 3_600_000L else 0 }
        val starts = files.map { it.startMs - start }
        assertEquals(listOf(0L, 10_000L, 20_000L, 10_830_000L, 10_840_000L, 10_850_000L), starts)
    }

    /** Назад — тоже: отрезки не уходят в будущее. */
    @Test fun clockMovedBackIsFollowedToo() {
        val files = record(40) { ms -> start + ms - if (ms >= 15_000) 2 * 3_600_000L else 0 }
        assertEquals(listOf(-7_180_000L, -7_170_000L, 0L, 10_000L), files.map { it.startMs - start })
    }

    /** Часы камеры идут медленнее часов планшета: 0,1 с на каждые 2 с — за минуту 3 с. Время новых отрезков не уходит дальше ≈ 3 с. */
    @Test fun slowCameraClockDoesNotDriftAway() {
        val last = record(300) { ms -> start + ms + ms / 20 }.last().startMs - start
        // Последний отрезок начат в 290-ю секунду потока — по часам планшета это 304,5 с.
        assertTrue("последний отрезок в $last мс", abs(last - 304_500) <= 3_500)
    }

    /** Камера прислала кадры пачкой (сеть стояла 5 с): часы в порядке, время отрезков не прыгает. */
    @Test fun burstAfterNetworkPauseDoesNotShiftTime() {
        val files = record(60) { ms -> start + if (ms in 20_000..25_000) 25_000 else ms }
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L), files.map { it.startMs - start })
    }

    /** Событие: часы поправились посреди дозаписи — ролик кончается через те же 5 с, а не сразу и не через час. */
    @Test fun clockCorrectionDoesNotCutOrStretchEventClip() {
        for (shift in listOf(3_600_000L, -3_600_000L)) {
            val root = tmp.newFolder()
            val clips = Collections.synchronizedList(mutableListOf<RecFile>())
            var ms = 0L
            val r = EventRecorder("door", "rtsp://cam/main", "", null, audio = false, root = { root }, canWrite = { true },
                events = { if (ms in 2_000 until 4_000) setOf("person") else emptySet() },
                settings = { RecordingConfig(preSeconds = 0, postSeconds = 5) }, onClip = { f, _ -> clips += f }, onStatus = { _, _ -> })
            val (frames, d) = sample()
            val sink = r.ClipSink().apply { videoClock = 90_000 }
            for (i in 0 until 300) {
                ms = i * 100L
                r.wallClock = { start + ms + if (ms >= 5_000) shift else 0 }
                val base = frames[i % frames.size]
                sink.video(VideoFrame(i * 9_000L, base.nals, base.key), d)
            }
            sink.close()
            assertTrue(RecIo.flush(5_000))
            // Последний активный кадр 3,9 с, дозапись 5 с → конец 8,9 с, начало на ключевом кадре 2,0 с.
            assertEquals("часы на $shift", listOf(6_940L), clips.map { it.durationMs })
        }
    }

    // ---- Очистка по сроку ----

    @Test fun guardTrustsSteadyClockAndStopsForDayAfterJump() {
        val g = ClockGuard()
        assertTrue(g.trusted(start, 1_000, start - 60_000))
        assertTrue("часы идут", g.trusted(start + 600_000, 601_000, start))
        assertTrue("мелкая поправка — не сбой", g.trusted(start + 1_200_000 + 20_000, 1_201_000, start))
        assertFalse("прыгнули на 10 дней", g.trusted(start + 10 * day, 1_801_000, start))
        assertFalse("и через 23 часа", g.trusted(start + 10 * day + day - 3_600_000, 1_801_000 + day - 3_600_000, start))
        // Процесс перезапущен посреди этих суток: последняя запись — уже по новым часам, но срок по-прежнему не действует.
        val again = ClockGuard(g.pausedUntilMs)
        assertFalse(again.trusted(start + 10 * day + day - 3_600_000, 1_801_000 + day - 3_600_000, start + 10 * day + day - 3_660_000))
        assertTrue("через сутки по ним можно судить", g.trusted(start + 11 * day + 1, 1_801_000 + day + 1, start))
        assertTrue(again.trusted(start + 11 * day + 1, 1_801_000 + day + 1, start))
    }

    /** Часы вернулись назад посреди паузы — сутки от нового времени, а не до далёкого будущего. */
    @Test fun guardPauseFollowsClockBack() {
        val g = ClockGuard()
        assertFalse(g.trusted(start + 400 * day, 0, start))
        assertFalse(g.trusted(start, 10_000, start))
        assertTrue(g.trusted(start + day + 10_000, day + 20_000, start))
        assertTrue("то же после перезапуска", ClockGuard(start + 400 * day).let { it.trusted(start, 0, start); it.trusted(start + day, day, start) })
    }

    /** Сначала память планшета, потом карта: последняя запись проверяется на каждом носителе. */
    @Test fun guardChecksEachStorage() {
        val g = ClockGuard()
        assertTrue(g.trusted(start, 0, null))
        g.storageChanged()
        assertFalse("на карте последняя запись — месяц назад", g.trusted(start + 60_000, 60_000, start - 30 * day))
    }

    @Test fun guardWaitsWhenLastRecordingIsOldAtStart() {
        val late = ClockGuard()
        assertFalse("последняя запись трёхдневной давности", late.trusted(start, 0, start - 3 * day))
        assertTrue(late.trusted(start + day, day, start))
        assertTrue("записей нет — удалять нечего", ClockGuard().trusted(start, 0, null))
        assertTrue("часы назад: по сроку всё равно ничего не подойдёт", ClockGuard().trusted(start, 0, start + 5 * day))
    }

    /** Служба запущена с часами на месяц вперёд: прежний архив не удаляется по сроку. */
    @Test fun serviceKeepsArchiveWhenClockIsFarAhead() {
        val context = RuntimeEnvironment.getApplication()
        val root = context.getExternalFilesDirs(null)[0]
        File(root, RecLayout.ROOT).deleteRecursively()
        ShadowStatFs.registerStats(root, 1_000_000, 1_000_000, 1_000_000)
        val always = RecordingConfig.Mode.CONTINUOUS_EVENTS
        val cam = TileEntity("cam_clock", DoorbellConfig.CAMERA_TYPE, "grid", "Камера", 0, 0, 1, 1,
            config = RecordingConfig(mode = always, remoteMode = always).writeTo(DoorbellConfig.newTileConfig()))
        TileManager(context).saveTiles(listOf(cam))
        RecordingDevice(context).setEnabled(cam.id, true)
        // По часам планшета последняя запись — 30 дней назад.
        val s = System.currentTimeMillis() - 30 * day
        val camera = RecLayout.cameraKey(cam.id)
        val old = File(RecLayout.dayDir(root, camera, s).apply { mkdirs() }, RecLayout.closedName(RecFile.Kind.CONTINUOUS, s, 60_000)).apply { writeBytes(ByteArray(100)) }
        // Часы уже сбивались раньше: остались записи «из будущего». И они ничего не доказывают.
        val f = System.currentTimeMillis() + 20 * day
        File(RecLayout.dayDir(root, camera, f).apply { mkdirs() }, RecLayout.closedName(RecFile.Kind.CONTINUOUS, f, 60_000)).writeBytes(ByteArray(100))
        // Идущий отрезок начат уже по этим часам (записи открываются до обхода) — он ничего не доказывает.
        val now = System.currentTimeMillis()
        val (frames, d) = sample()
        val live = Mp4SegmentWriter(File(RecLayout.dayDir(root, camera, now).apply { mkdirs() }, RecLayout.openName(RecFile.Kind.CONTINUOUS, now)),
            VideoParams(d.codec, d.vps, d.sps!!, d.pps!!), null, queued = true)
        (0 until 40).forEach { i -> val b = frames[i % frames.size]; live.writeVideo(VideoFrame(i * 9_000L, b.nals, b.key), i * 100_000L) }
        assertTrue(RecIo.flush(5_000))
        RecordingDevice(context).agePausedUntil = 0
        val service = Robolectric.buildService(RecorderService::class.java).create().startCommand(0, 1)
        try {
            val until = System.currentTimeMillis() + 10_000
            while (RecordingDevice(context).agePausedUntil == 0L && System.currentTimeMillis() < until) Thread.sleep(50)
            assertTrue("очистка прошла и часам не поверила", RecordingDevice(context).agePausedUntil > now)
            Thread.sleep(300)
            assertTrue("архив остался", old.exists())
        } finally {
            live.close()
            val control = RecorderService::class.java.getDeclaredField("control").apply { isAccessible = true }.get(service.get()) as java.util.concurrent.ExecutorService
            service.destroy()
            assertTrue(control.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS))
            RecordingDevice(context).agePausedUntil = 0
            File(root, RecLayout.ROOT).deleteRecursively()
            RecordingDevice(context).setEnabled(cam.id, false)
            RecordingStatus.clear()
        }
    }

    /** Секунда образца H.264: 10 кадров, первый — ключевой. */
    private fun sample(): Pair<List<VideoFrame>, VideoDepacketizer> {
        val d = H264Depacketizer()
        val decoded = mutableListOf<VideoFrame>()
        var seq = 0
        accessUnits(nals(resource("sample.h264")), false).forEachIndexed { i, au ->
            packetize(au, false, i * 9_000L, seq).forEach { p -> decoded += d.push(RtpPacket.parse(p, p.size)!!); seq++ }
        }
        return decoded to d
    }
}
