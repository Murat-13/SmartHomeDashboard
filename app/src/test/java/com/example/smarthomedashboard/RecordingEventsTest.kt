package com.example.smarthomedashboard

import com.example.smarthomedashboard.RecordingTestSupport.accessUnits
import com.example.smarthomedashboard.RecordingTestSupport.boxes
import com.example.smarthomedashboard.RecordingTestSupport.nals
import com.example.smarthomedashboard.RecordingTestSupport.packetize
import com.example.smarthomedashboard.RecordingTestSupport.resource
import com.example.smarthomedashboard.data.TileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// Запись с камер, этап 2 (docs/recording.md): события в HD — предзапись, дозапись, продление, предел ролика, индекс.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordingEventsTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- Логика события ----

    @Test fun eventStartsOnTriggerAndStopsAfterPostRoll() {
        val p = EventPlanner(postMs = 2_000)
        assertEquals(EventPlanner.Decision.IDLE, p.update(0, emptySet()))
        assertEquals(EventPlanner.Decision.START, p.update(100, setOf("person")))
        assertEquals(EventPlanner.Decision.CONTINUE, p.update(1_000, setOf("person")))
        assertEquals(EventPlanner.Decision.CONTINUE, p.update(2_999, emptySet()))
        assertEquals(EventPlanner.Decision.STOP, p.update(3_000, emptySet()))
        assertEquals(setOf("person"), p.takeLabels(emptySet()))
        assertEquals(EventPlanner.Decision.IDLE, p.update(3_100, emptySet()))
    }

    @Test fun newTriggerDuringPostRollExtendsSameClipAndCollectsLabels() {
        val p = EventPlanner(postMs = 2_000)
        p.update(0, setOf("visitor"))
        p.update(500, emptySet())
        assertEquals(EventPlanner.Decision.CONTINUE, p.update(2_000, setOf("vehicle")))
        assertEquals(EventPlanner.Decision.CONTINUE, p.update(3_999, emptySet()))
        assertEquals(EventPlanner.Decision.STOP, p.update(4_000, emptySet()))
        assertEquals(setOf("visitor", "vehicle"), p.takeLabels(null))
    }

    @Test fun unknownHaStateDoesNotStartAndEndsRunningEventByPostRoll() {
        val p = EventPlanner(postMs = 1_000)
        assertEquals(EventPlanner.Decision.IDLE, p.update(0, null))
        p.update(10, setOf("person"))
        assertEquals(EventPlanner.Decision.CONTINUE, p.update(500, null))
        assertEquals(EventPlanner.Decision.STOP, p.update(1_010, null))
    }

    @Test fun triggersComeFromTileSensorsAndOnlyEnabledOnes() {
        val door = DoorbellConfig(visitor = "binary_sensor.v", person = "binary_sensor.p", motion = "binary_sensor.m")
        val rec = RecordingConfig() // по умолчанию всё, кроме движения
        val states = mutableMapOf("binary_sensor.v" to "off", "binary_sensor.p" to "on", "binary_sensor.m" to "on")
        fun readings(connected: Boolean) = BatteryReadings(HaSession(connected, connected), states::get, { false }, { null })
        assertEquals(setOf("person"), RecordingEvents.labels(door.source, rec, readings(true)))
        assertEquals(setOf("person", "motion"), RecordingEvents.labels(door.source, rec.copy(eventTriggers = rec.eventTriggers + "motion"), readings(true)))
        assertNull("Без связи с HA — неизвестно", RecordingEvents.labels(door.source, rec, readings(false)))
        states["binary_sensor.p"] = "unavailable"
        assertEquals(emptySet<String>(), RecordingEvents.labels(door.source, rec, readings(true)))

        val tile = TileEntity("door1", DoorbellConfig.TYPE, "grid", "Домофон", 0, 0, 1, 1,
            config = RecordingConfig().writeTo(door.writeTo("{}")))
        states["binary_sensor.v"] = "on"
        RecordingEvents.update(listOf(tile), readings(true))
        assertEquals(setOf("visitor"), RecordingEvents.of("door1"))
        RecordingEvents.update(listOf(tile), readings(false))
        assertNull(RecordingEvents.of("door1"))
        RecordingEvents.clear()
        assertNull(RecordingEvents.of("door1"))
    }

    // ---- Буфер предзаписи ----

    @Test fun preRollKeepsWholeGopsFromKeyframeBeforeWindow() {
        val (frames, d) = sample(60) // 6 с, ключевой кадр раз в секунду
        val params = VideoParams(d.codec, d.vps, d.sps!!, d.pps!!)
        val buffer = PreRollBuffer(preUs = 2_500_000)
        frames.forEach { buffer.addVideo(it, us(it), if (it.key) params else null) }
        val first = buffer.snapshot().first() as PreRollBuffer.Item.Video
        assertTrue(first.frame.key)
        // Обрезка — на ключевых кадрах: на 5,0 с окно 2,5 с → ключевой кадр 2,0 с; дальше до 5,9 с без обрезки.
        assertEquals(2_000_000L, first.us)
        assertEquals(40, buffer.size)
        // Ролик, начатый на 5,9 с, берёт ключевой кадр не позже 3,4 с — 3,0 с.
        assertEquals(3_000_000L, buffer.startFor(5_900_000)!!.us)
    }

    @Test fun preRollMemoryLimitKeepsAtLeastCurrentGop() {
        val (frames, d) = sample(60)
        val params = VideoParams(d.codec, d.vps, d.sps!!, d.pps!!)
        val buffer = PreRollBuffer(preUs = 30_000_000, maxBytes = 1)
        frames.forEach { buffer.addVideo(it, us(it), if (it.key) params else null) }
        assertEquals(5_000_000L, buffer.snapshot().first().us)
        // Без ключевого кадра буфер не начинается.
        val empty = PreRollBuffer(preUs = 1_000_000)
        empty.addVideo(frames[3], us(frames[3]), null)
        empty.addAudio(AudioFrame(0, ByteArray(10)), 0)
        assertEquals(0, empty.size)
    }

    // ---- Ролики ----

    private class Clip(val file: RecFile, val labels: Set<String>)

    private fun recorder(root: File, config: RecordingConfig, events: (Long) -> Set<String>?, clips: MutableList<Clip>,
                         canWrite: () -> Boolean = { true }, states: MutableList<CameraRecorder.State> = mutableListOf()): Pair<EventRecorder, () -> Long> {
        var now = 0L
        val r = EventRecorder("door", "rtsp://cam/main", "", null, audio = false, root = { root }, canWrite = canWrite,
            events = { events(now) }, settings = { config }, onClip = { f, l -> clips += Clip(f, l) },
            onStatus = { _, s -> states += s.state })
        return r to { now }.also { clock = { v -> now = v } }
    }

    private var clock: (Long) -> Unit = {}

    /** Прогнать [seconds] секунд потока через сессию записи событий. */
    private fun run(r: EventRecorder, seconds: Int, d: VideoDepacketizer, frames: List<VideoFrame>) {
        val sink = r.ClipSink()
        sink.videoClock = 90_000
        for (i in 0 until seconds * 10) {
            val base = frames[i % frames.size]
            val frame = VideoFrame(i * 9_000L, base.nals, base.key)
            clock(i * 100L)
            sink.video(frame, d)
        }
        sink.close()
    }

    @Test fun clipHasPreRollAndPostRollAndLabels() {
        val (frames, d) = sample(30)
        val clips = mutableListOf<Clip>()
        val root = tmp.newFolder("r")
        val (r, _) = recorder(root, RecordingConfig(preSeconds = 3, postSeconds = 2),
            { t -> if (t in 8_000 until 10_000) setOf("person") else emptySet() }, clips)
        run(r, 20, d, frames)
        val clip = clips.single()
        assertEquals(setOf("person"), clip.labels)
        assertEquals(RecFile.Kind.EVENT, clip.file.kind)
        // Начало — ключевой кадр 5,0 с (8 − 3); последний активный кадр 9,9 с, дозапись 2 с → последний кадр 11,9 с.
        assertEquals(6_900L + 40, clip.file.durationMs)
        assertTrue(clip.file.path.name.startsWith("e_"))
        val top = boxes(clip.file.path.readBytes())
        assertEquals("ftyp", top[0]); assertEquals("moov", top[1])
        assertFalse(top.any { it.startsWith("!") })
        // Недописанных файлов не осталось.
        assertTrue(root.walkTopDown().none { it.name.endsWith(".part.mp4") })
    }

    @Test fun eventsCloseTogetherMakeOneClip() {
        val (frames, d) = sample(30)
        val clips = mutableListOf<Clip>()
        val (r, _) = recorder(tmp.newFolder("r"), RecordingConfig(preSeconds = 0, postSeconds = 3),
            { t -> when (t) { in 5_000 until 6_000 -> setOf("visitor"); in 8_000 until 9_000 -> setOf("person"); else -> emptySet() } }, clips)
        run(r, 20, d, frames)
        assertEquals(1, clips.size)
        assertEquals(setOf("visitor", "person"), clips.single().labels)
    }

    @Test fun longEventIsSplitIntoClipsOfMaxLengthAtKeyframes() {
        val (frames, d) = sample(30)
        val clips = mutableListOf<Clip>()
        val (r, _) = recorder(tmp.newFolder("r"), RecordingConfig(preSeconds = 0, postSeconds = 5, maxClipMinutes = 1),
            { t -> if (t in 2_000 until 140_000) setOf("vehicle") else emptySet() }, clips)
        run(r, 150, d, frames)
        assertEquals(3, clips.size)
        // Резка на ключевом кадре 60 с: в файле кадры 0–59,9 с (+ длительность последнего кадра).
        assertEquals(listOf(59_940L, 59_940L), clips.take(2).map { it.file.durationMs })
        assertTrue(clips.all { it.labels == setOf("vehicle") })
        // Файлы идут встык: следующий начинается там, где закончился предыдущий.
        clips.zipWithNext().forEach { (a, b) -> assertEquals(a.file.startMs + 60_000, b.file.startMs) }
    }

    @Test fun noSpaceSkipsClipAndReportsOnce() {
        val (frames, d) = sample(30)
        val clips = mutableListOf<Clip>()
        val states = mutableListOf<CameraRecorder.State>()
        val (r, _) = recorder(tmp.newFolder("r"), RecordingConfig(preSeconds = 1, postSeconds = 2),
            { t -> if (t in 3_000 until 6_000) setOf("person") else emptySet() }, clips, canWrite = { false }, states = states)
        run(r, 10, d, frames)
        assertTrue(clips.isEmpty())
        assertEquals(1, states.count { it == CameraRecorder.State.NO_SPACE })
    }

    @Test fun indexKeepsLabelsAndDropsRowsOfDeletedClips() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val index = RecIndex(context, null)
        val a = RecFile(File("e_1000_5000.mp4"), "door", RecFile.Kind.EVENT, 1_000, 5_000, 10)
        val b = RecFile(File("e_9000_5000.mp4"), "door", RecFile.Kind.EVENT, 9_000, 5_000, 10)
        index.add(a, setOf("person", "visitor"))
        index.add(b, setOf("vehicle"))
        assertEquals(setOf("person", "visitor"), index.labels("door", 1_000))
        assertEquals(listOf(9_000L, 1_000L), index.events("door").map { it.startMs })
        index.reconcile(listOf(b))
        assertEquals(listOf(9_000L), index.events("door").map { it.startMs })
        index.remove(listOf(b))
        assertTrue(index.events("door").isEmpty())
        index.close()
    }

    // ---- Помощники ----

    private fun us(f: VideoFrame) = f.timestamp * 1_000_000L / 90_000

    /** Кадры образца H.264 (10 кадров/с, ключевой раз в секунду), продолжённые до [count]. */
    private fun sample(count: Int): Pair<List<VideoFrame>, VideoDepacketizer> {
        val units = accessUnits(nals(resource("sample.h264")), false)
        val d = H264Depacketizer()
        val decoded = mutableListOf<VideoFrame>()
        var seq = 0
        units.forEachIndexed { i, au -> packetize(au, false, i * 9_000L, seq).forEach { p -> decoded += d.push(RtpPacket.parse(p, p.size)!!); seq++ } }
        val frames = (0 until count).map { i -> val b = decoded[i % decoded.size]; VideoFrame(i * 9_000L, b.nals, b.key) }
        return frames to d
    }
}
