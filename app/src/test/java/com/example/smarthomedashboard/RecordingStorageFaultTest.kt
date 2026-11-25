package com.example.smarthomedashboard

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Looper
import com.example.smarthomedashboard.RecordingTestSupport.accessUnits
import com.example.smarthomedashboard.RecordingTestSupport.nals
import com.example.smarthomedashboard.RecordingTestSupport.packetize
import com.example.smarthomedashboard.RecordingTestSupport.resource
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Неисправный или вынутый носитель не рвёт поток камеры (аудит 25.11.2026, REC-02): ошибка записи обрывала сессию — вместе с ней
 * переподключались живое видео и раскрытая карточка, и так каждые секунды, пока карту не вынут. Теперь запись закрывает свой файл и
 * пробует снова сама; карта не пишет несколько раз — запись идёт в память планшета, об этом сказано голосом.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingStorageFaultTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var internal: File
    private lateinit var sd: File
    private var service: ServiceController<RecorderService>? = null

    @Before fun setUp() {
        internal = File(context.filesDir, "fault_internal").apply { deleteRecursively(); mkdirs() }
        sd = File(context.filesDir, "fault_sd").apply { deleteRecursively(); mkdirs() }
        listOf(internal, sd).forEach {
            ShadowEnvironment.setExternalStorageState(it, Environment.MEDIA_MOUNTED)
            ShadowStatFs.registerStats(it, 1_000_000, 1_000_000, 1_000_000)
        }
        RecVolumes.dirsOverride = { arrayOf(internal, sd) }
        RecVolumes.brokenCard = null
    }

    private fun control() = RecorderService::class.java.getDeclaredField("control").apply { isAccessible = true }.get(service!!.get()) as java.util.concurrent.ExecutorService

    @After fun tearDown() {
        // Служба прошлого теста дорабатывает в своём потоке и в конце очищает общее состояние записи — дождаться.
        if (service != null) { service!!.destroy(); assertTrue(control().awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)) }
        RecVolumes.dirsOverride = null
        RecVolumes.brokenCard = null
        RecEject.returned(sd)
        listOf(internal, sd).forEach { it.deleteRecursively() }
        RecordingDevice(context).setEnabled("cam_fault", false)
        RecordingDevice(context).useSdCard = false
        RecordingStatus.clear()
        RecIndex(context).use { }
        context.deleteDatabase(RecIndex.DB_NAME)
    }

    private val start = 1_763_490_000_000L

    /** Поток образца: 10 к/с, ключевой кадр раз в секунду; [at] — перед кадром секунды [s] потока. */
    private fun feed(sink: CameraRecorder.Sink, seconds: Int, setWall: (Long) -> Unit, at: (Int) -> Unit) {
        val d = H264Depacketizer()
        val decoded = mutableListOf<VideoFrame>()
        var seq = 0
        accessUnits(nals(resource("sample.h264")), false).forEachIndexed { i, au ->
            packetize(au, false, i * 9_000L, seq).forEach { p -> decoded += d.push(RtpPacket.parse(p, p.size)!!); seq++ }
        }
        for (i in 0 until seconds * 10) {
            if (i % 10 == 0) { assertTrue(RecIo.flush(5_000)); at(i / 10) }
            setWall(start + i * 100L)
            val base = decoded[i % decoded.size]
            sink.video(VideoFrame(i * 9_000L, base.nals, base.key), d)
        }
        sink.close()
        assertTrue(RecIo.flush(5_000))
    }

    private fun dayDir(root: File) = RecLayout.dayDir(root, "door", start)

    /** Два отказа носителя: папка есть, но файл не создать (ошибка приходит при записи), и папку не создать вовсе. */
    private enum class Fault { READ_ONLY, NO_FOLDER }
    private fun breakStorage(root: File, fault: Fault) {
        val dir = dayDir(root)
        when (fault) {
            Fault.READ_ONLY -> { dir.mkdirs(); assertTrue(dir.setWritable(false)) }
            Fault.NO_FOLDER -> { dir.deleteRecursively(); dir.parentFile!!.mkdirs(); dir.writeText("x") }
        }
    }
    private fun repair(root: File, fault: Fault) {
        if (fault == Fault.READ_ONLY) dayDir(root).setWritable(true) else dayDir(root).delete()
    }
    private fun closed(root: File) = RecLayout.scan(root).filter { !it.open }.sortedBy { it.startMs }

    /** Круглосуточная запись: носитель отказал посреди отрезка — кадры идут дальше без исключения, запись сообщает об ошибке. */
    @Test fun storageErrorDoesNotBreakCameraStreamAndRecordingResumes() {
        for (fault in Fault.values()) continuous(fault)
    }

    private fun continuous(fault: Fault) {
        val root = tmp.newFolder()
        val errors = Collections.synchronizedList(mutableListOf<File>())
        val states = mutableListOf<String>()
        val r = ContinuousRecorder("door", "rtsp://cam/sub", "", null, audio = false, root = { root }, canWrite = { true },
            onSegment = {}, onStatus = { _, s -> states += s.detail.ifBlank { s.state.name } }, segmentMs = 4_000)
        r.storageRetryNs = 3_000_000L // в тесте секунда потока идёт мгновенно: 3 мс
        r.onStorageError = { errors += it }
        // 0–4 с пишется; на 4-й секунде носитель отказал, на 8-й ожил.
        feed(r.newSink().apply { videoClock = 90_000 }, 16, { w -> r.wallClock = { w } }) { s ->
            if (s == 4) breakStorage(root, fault)
            if (s == 8) repair(root, fault)
            if (s in 5..12) Thread.sleep(5)
        }
        assertTrue("$fault: об ошибке носителя сообщено: $errors", errors.isNotEmpty() && errors.all { it == root })
        val expected = if (fault == Fault.READ_ONLY) ContinuousRecorder.STORAGE_RETRYING else "Папка записи недоступна"
        assertTrue("$fault: состояние — не «идёт запись»: $states", expected in states)
        val again = closed(root).map { it.startMs - start }
        assertTrue("$fault: после возврата носителя запись пошла сама: $again", again.any { it >= 8_000 })
        assertEquals("и дошла до конца потока", 15_900L, closed(root).last().let { it.startMs + it.durationMs - start - 40 })
    }

    /** Событие в HD — так же, и прежняя дозапись в силе. */
    @Test fun storageErrorDoesNotBreakEventClip() {
        for (fault in Fault.values()) event(fault)
    }

    private fun event(fault: Fault) {
        val root = tmp.newFolder()
        var second = 0
        val clips = Collections.synchronizedList(mutableListOf<Pair<RecFile, Set<String>>>())
        val errors = Collections.synchronizedList(mutableListOf<File>())
        val states = mutableListOf<String>()
        val r = EventRecorder("door", "rtsp://cam/main", "", null, audio = false, root = { root }, canWrite = { true },
            events = { if (second in 1..5) setOf("person") else emptySet() }, settings = { RecordingConfig(preSeconds = 0, postSeconds = 8) },
            onClip = { f, l -> clips += f to l }, onStatus = { _, s -> states += s.detail })
        r.storageRetryNs = 3_000_000L
        r.onStorageError = { errors += it }
        feed(r.ClipSink().apply { videoClock = 90_000 }, 20, { w -> r.wallClock = { w } }) { s ->
            second = s
            if (s == 0) breakStorage(root, fault)
            if (s == 7) repair(root, fault)
            if (s in 1..10) Thread.sleep(5)
        }
        assertTrue("$fault: об ошибке сообщено", errors.isNotEmpty())
        val after = closed(root).filter { it.startMs - start >= 7_000 }
        assertEquals("$fault: событие продолжено роликом после возврата носителя", 1, after.size)
        assertEquals("$fault: и кончилось дозаписью: последний активный кадр 5,9 с + 8 с", 13_900L, after.single().let { it.startMs + it.durationMs - start - 40 })
        assertEquals("$fault: метки события — и у продолжения, начатого в дозаписи", setOf("person"), clips.single { it.first.startMs == after.single().startMs }.second)
        if (fault == Fault.READ_ONLY) {
            val failed = states.indexOf(ContinuousRecorder.STORAGE_RETRYING)
            assertTrue("об отказе видно в редакторе: $states", failed >= 0)
            assertTrue("…пока не запишется: $states", states.drop(failed + 1).takeWhile { it != EventRecorder.WRITING }.none { it == EventRecorder.IDLE })
        }
    }

    /** Карту извлекают или она отказала: запись закрывает файл и продолжает на другом носителе со следующего ключевого кадра. */
    @Test fun recordingMovesToOtherStorageOnNextKeyFrame() {
        val card = tmp.newFolder(); val tablet = tmp.newFolder()
        var root = card
        val r = ContinuousRecorder("door", "rtsp://cam/sub", "", null, audio = false, root = { root }, canWrite = { true },
            onSegment = {}, onStatus = { _, _ -> }, segmentMs = 60_000)
        feed(r.newSink().apply { videoClock = 90_000 }, 10, { w -> r.wallClock = { w } }) { s -> if (s == 4) root = tablet }
        assertEquals("на карте — до смены", listOf(0L to 3_940L), closed(card).map { (it.startMs - start) to it.durationMs })
        assertEquals("дальше — в памяти планшета", listOf(4_000L), closed(tablet).map { it.startMs - start })
    }

    /**
     * Карту извлекают: Android закрывает приложение, у которого на ней открыт файл, через ≈ 0,1 с — ждать ближайшего ключевого кадра нельзя
     * (эмулятор, 25.11.2026). Файлы закрываются сразу, новые на ней не открываются, недописанное восстанавливается после возврата.
     */
    @Test fun filesOnEjectedCardAreClosedAtOnce() {
        val card = tmp.newFolder(); val tablet = tmp.newFolder()
        var root = card
        fun openStreams(): Int = (RecIo::class.java.getDeclaredField("channels").apply { isAccessible = true }.get(RecIo) as Set<*>).count { c ->
            val out = c!!.javaClass.getDeclaredField("out").apply { isAccessible = true }.get(c) as java.io.FileOutputStream?
            val file = c.javaClass.getDeclaredField("file").apply { isAccessible = true }.get(c) as File
            file.path.startsWith(card.path) && out != null && out.fd.valid()
        }
        val r = ContinuousRecorder("door", "rtsp://cam/sub", "", null, audio = false, root = { root }, canWrite = { true },
            onSegment = {}, onStatus = { _, _ -> }, segmentMs = 60_000)
        r.storageRetryNs = 3_000_000L
        try {
            feed(r.newSink().apply { videoClock = 90_000 }, 12, { w -> r.wallClock = { w } }) { s ->
                if (s == 4) { assertEquals(1, openStreams()); RecEject.start(card.path); assertEquals("закрыт сразу, без кадра и очереди", 0, openStreams()) }
                if (s in 5..7) { Thread.sleep(5); assertEquals("на извлекаемой карте ничего не открывается", 0, openStreams()) }
                if (s == 7) root = tablet
            }
            val left = RecLayout.scan(card)
            assertEquals("недописанный файл не тронут: $left", listOf(true), left.map { it.open })
            assertEquals("дальше — в памяти планшета", listOf(7_000L), closed(tablet).map { it.startMs - start })
            RecEject.returned(File(card, "x"))
            assertEquals(1, RecLayout.recover(card, skip = RecIo.busyFiles()))
            assertTrue("восстановлен", closed(card).single().bytes > 0)
        } finally { RecEject.returned(File(card, "x")) }
    }

    /** Один файл как раз открывается на медленном носителе — остальные закрываются, не дожидаясь его. */
    @Test fun slowFileDoesNotHoldOthersOnEject() {
        val card = tmp.newFolder()
        val channels = List(3) { QueuedFileChannel(File(card, "c_$it.part.mp4")).apply { write(java.nio.ByteBuffer.wrap(ByteArray(10))) } }
        assertTrue(RecIo.flush(5_000))
        fun valid(c: QueuedFileChannel) = (c.javaClass.getDeclaredField("out").apply { isAccessible = true }.get(c) as java.io.FileOutputStream).fd.valid()
        val held = CountDownLatch(1); val free = CountDownLatch(1)
        val slow = thread { (channels[0].javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(channels[0]) as java.util.concurrent.locks.ReentrantLock).let {
            it.lock(); held.countDown(); free.await(); it.unlock() } }
        held.await()
        val eject = thread { RecEject.start(card.path) }
        try {
            waitFor("остальные файлы ждут занятый", 2_000) { channels.drop(1).none(::valid) }
            assertTrue(valid(channels[0]))
        } finally { free.countDown() }
        slow.join(5_000); eject.join(5_000)
        assertFalse(valid(channels[0]))
        channels.forEach { it.close() }
        assertTrue(RecIo.flush(5_000))
        RecEject.returned(File(card, "x"))
    }

    /** Носитель перестал отвечать посреди закрытия файла — записанное не удаляется: восстановится при запуске. */
    @Test fun fileIsKeptWhenStorageFailsWhileClosing() {
        val root = tmp.newFolder()
        val dir = dayDir(root).apply { mkdirs() }
        val f = File(dir, RecLayout.openName(RecFile.Kind.CONTINUOUS, start))
        val d = H264Depacketizer()
        val frames = mutableListOf<VideoFrame>()
        var seq = 0
        accessUnits(nals(resource("sample.h264")), false).forEachIndexed { i, au ->
            packetize(au, false, i * 9_000L, seq).forEach { p -> frames += d.push(RtpPacket.parse(p, p.size)!!); seq++ }
        }
        val w = Mp4SegmentWriter(f, VideoParams(d.codec, d.vps, d.sps!!, d.pps!!), null, queued = true)
        repeat(30) { i -> val b = frames[i % frames.size]; w.writeVideo(VideoFrame(i * 9_000L, b.nals, b.key), i * 100_000L) }
        assertTrue(RecIo.flush(5_000))
        assertTrue(f.setReadable(false) && f.setWritable(false)) // открыть для обрезки нельзя
        RecLayout.finishLater(w, f, "door", RecFile.Kind.CONTINUOUS, start, 3_000) {}
        assertTrue(RecIo.flush(5_000))
        assertTrue("файл остался", f.exists())
        assertTrue(f.setReadable(true) && f.setWritable(true))
        assertEquals(1, RecLayout.recover(root))
    }

    /** На каждом ключевом кадре носитель и система не спрашиваются: в потоке камеры — живое видео. Неизвестно куда — файл не рвётся. */
    @Test fun keyFramesDoNotAskTheSystem() {
        val card = tmp.newFolder()
        var asked = 0
        val r = ContinuousRecorder("door", "rtsp://cam/sub", "", null, audio = false, root = { asked++; card }, canWrite = { true },
            onSegment = {}, onStatus = { _, _ -> }, segmentMs = 60_000)
        r.currentRoot = { null }
        feed(r.newSink().apply { videoClock = 90_000 }, 10, { w -> r.wallClock = { w } }) { }
        assertEquals("только при открытии отрезка", 1, asked)
        assertEquals(listOf(0L to 9_940L), closed(card).map { (it.startMs - start) to it.durationMs })
    }

    /** Носитель не отвечает (запись в него зависла), очередь полна: пишущий получает ошибку, а не ждёт часами вместе с живым видео. */
    @Test fun stuckStorageIsAnErrorNotAnEndlessWait() {
        val real = RecIo.stuckMs
        RecIo.stuckMs = 400
        val free = CountDownLatch(1)
        RecIo.execute { free.await() }
        var error: Exception? = null
        val writer = thread(isDaemon = true) {
            val channel = QueuedFileChannel(File(tmp.newFolder(), "c_1.part.mp4"))
            try { repeat(40) { channel.write(java.nio.ByteBuffer.wrap(ByteArray(1024 * 1024))) } } catch (e: Exception) { error = e }
            channel.close()
        }
        try {
            writer.join(5_000)
            assertFalse("пишущий всё ещё ждёт носитель", writer.isAlive)
            assertEquals("Носитель не отвечает", error?.message)
        } finally { free.countDown(); RecIo.stuckMs = real; writer.join(5_000); assertTrue(RecIo.flush(10_000)) }
    }

    // ---- Служба ----

    private fun startService() {
        val always = RecordingConfig.Mode.CONTINUOUS_EVENTS
        val tile = TileEntity("cam_fault", DoorbellConfig.CAMERA_TYPE, "grid", "Камера", 0, 0, 1, 1,
            config = RecordingConfig(mode = always, remoteMode = always, voiceProblems = true).writeTo(
                DoorbellConfig(rtspMain = "rtsp://127.0.0.1:9/main", rtspSub = "rtsp://127.0.0.1:9/sub").writeTo(DoorbellConfig.newTileConfig())))
        TileManager(context).saveTiles(listOf(tile))
        RecordingDevice(context).setEnabled(tile.id, true)
        RecordingDevice(context).useSdCard = true
        service = Robolectric.buildService(RecorderService::class.java).create().startCommand(0, 1)
        waitFor("служба не выбрала SD-карту") { storage() == sd }
    }

    private fun storage(): File? = RecorderService::class.java.getDeclaredField("rootCache").apply { isAccessible = true }.get(service!!.get()) as? File

    private fun waitFor(what: String, ms: Long = 5_000, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!ok()) { if (System.currentTimeMillis() > until) throw AssertionError(what); Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle() }
    }

    /** Ошибка записи на карту — сразу у всех записей, как при настоящей заминке: тем же путём, что из потока камеры. */
    private fun cardError(afterSeconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(afterSeconds))
        val running = RecorderService::class.java.getDeclaredField("recorders").apply { isAccessible = true }.get(service!!.get()) as Map<*, *>
        assertEquals("круглосуточная и HD", 2, running.size)
        for (r in running.values) {
            val recorder = r!!.javaClass.getDeclaredField("recorder").apply { isAccessible = true }.get(r) as CameraRecorder
            recorder.onStorageError(sd)
        }
        // Поток управления отработал всё поставленное.
        control().submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun broadcast(action: String, path: String) {
        context.sendBroadcast(Intent(action, Uri.fromFile(File(path))))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun cardThatDoesNotWriteIsReplacedByTabletMemoryUntilReturned() {
        startService()
        cardError(0); cardError(10); cardError(30)
        assertEquals("заминка короче минуты — ещё пробуем карту", sd, storage())
        assertNull(RecVolumes.brokenCard)
        cardError(15 * 60); cardError(10)
        assertNull("новая заминка через 15 минут — отсчёт заново", RecVolumes.brokenCard)
        cardError(55)
        waitFor("запись не перешла в память планшета") { storage() == internal }
        waitFor("не видно в редакторе камеры") { RecordingStatus.sdMissing("cam_fault") }
        assertEquals(sd.path, RecVolumes.brokenCard)
        // Подключили другой носитель — эта карта по-прежнему неисправна.
        broadcast(Intent.ACTION_MEDIA_MOUNTED, "/storage/other")
        Thread.sleep(300)
        assertEquals(internal, storage())
        // Карту вынули и вернули — снова она.
        broadcast(Intent.ACTION_MEDIA_MOUNTED, sd.parentFile!!.path)
        waitFor("запись не вернулась на карту") { storage() == sd }
        assertNull(RecVolumes.brokenCard)
    }

    /** Между заминками карта писала — они не складываются; ошибки из-за извлечения — не неисправность. */
    @Test fun recordedSegmentOrEjectResetsCardErrors() {
        startService()
        cardError(0)
        val camera = RecLayout.cameraKey("cam_fault")
        val s = System.currentTimeMillis() - 60_000
        val f = File(RecLayout.dayDir(sd, camera, s).apply { mkdirs() }, RecLayout.closedName(RecFile.Kind.CONTINUOUS, s, 60_000)).apply { writeBytes(ByteArray(100)) }
        RecorderService::class.java.getDeclaredMethod("segmentClosed", RecFile::class.java).apply { isAccessible = true }.invoke(service!!.get(), RecLayout.parse(f, camera)!!)
        cardError(300); cardError(5)
        assertNull("карта между заминками писала", RecVolumes.brokenCard)
        ShadowEnvironment.setExternalStorageState(sd, Environment.MEDIA_EJECTING)
        RecEject.start(sd.path)
        cardError(90); cardError(90)
        assertNull("её извлекают", RecVolumes.brokenCard)
        RecEject.returned(sd)
    }

    /** Поток управления занят, кэш носителя устарел: файл и сверка «пишется уже не туда» узнают носитель из одного места. */
    @Test fun staleStorageCacheIsRefreshedWhenCameraThreadAsks() {
        startService()
        val type = RecorderService::class.java
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        field("rootCache").set(service!!.get(), internal)
        field("rootCacheAt").set(service!!.get(), 0L)
        assertEquals(sd, type.getDeclaredMethod("cameraRoot").apply { isAccessible = true }.invoke(service!!.get()))
        assertEquals("иначе отрезок менялся бы на каждом ключевом кадре", sd, storage())
    }

    /** Извлечение: узнаём сразу, а не через 10 минут, — иначе Android закроет приложение с файлами на карте. */
    @Test fun ejectMovesRecordingAtOnce() {
        startService()
        ShadowEnvironment.setExternalStorageState(sd, Environment.MEDIA_UNMOUNTED)
        broadcast(Intent.ACTION_MEDIA_EJECT, sd.path)
        waitFor("файлы на карте не закрыты", 1_500) { RecEject.on(File(sd, "records/x")) }
        waitFor("запись не ушла с извлекаемой карты", 1_500) { storage() == internal } // обычная сверка носителя — раз в 5 с
        ShadowEnvironment.setExternalStorageState(sd, Environment.MEDIA_MOUNTED)
        broadcast(Intent.ACTION_MEDIA_MOUNTED, sd.path)
        waitFor("запись не вернулась", 1_500) { storage() == sd && !RecEject.on(File(sd, "records/x")) }
    }

    /** Карта пропала между обходами: файлы на ней не «удалены» — их метки остаются. */
    @Test fun filesOfMissingCardAreNotCountedAsDeleted() {
        val camera = RecLayout.cameraKey("cam_fault")
        val s = System.currentTimeMillis() - 2 * 3_600_000L
        val f = File(RecLayout.dayDir(sd, camera, s).apply { mkdirs() }, RecLayout.closedName(RecFile.Kind.EVENT, s, 30_000)).apply { writeBytes(ByteArray(100)) }
        val rec = RecLayout.parse(f, camera)!!
        RecIndex(context).use { it.add(rec, setOf("person")) }
        startService()
        waitFor("обход носителя не прошёл") { RecCatalog.files(sd)?.any { it.path == f } == true }
        // Карта отвалилась (папки нет, но Android ещё считает её подключённой), а места мало — очистка хочет удалить самое старое.
        File(sd, RecLayout.ROOT).deleteRecursively()
        ShadowStatFs.registerStats(sd, 1_000_000, 1_000, 1_000)
        val method = RecorderService::class.java.getDeclaredMethod("retention", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        method.invoke(service!!.get(), false)
        RecIndex(context).use { assertEquals(setOf("person"), it.labels(camera, s)) }
    }
}
