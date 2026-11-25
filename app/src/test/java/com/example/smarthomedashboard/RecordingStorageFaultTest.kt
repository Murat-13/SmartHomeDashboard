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

    @After fun tearDown() {
        service?.destroy()
        Thread.sleep(300)
        RecVolumes.dirsOverride = null
        RecVolumes.brokenCard = null
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
        val clips = Collections.synchronizedList(mutableListOf<RecFile>())
        val errors = Collections.synchronizedList(mutableListOf<File>())
        val r = EventRecorder("door", "rtsp://cam/main", "", null, audio = false, root = { root }, canWrite = { true },
            events = { if (second in 1..11) setOf("person") else emptySet() }, settings = { RecordingConfig(preSeconds = 0, postSeconds = 2) },
            onClip = { f, _ -> clips += f }, onStatus = { _, _ -> })
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
        assertEquals("$fault: и кончилось дозаписью: последний активный кадр 11,9 с + 2 с", 13_900L, after.single().let { it.startMs + it.durationMs - start - 40 })
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

    private fun cardError() {
        val method = RecorderService::class.java.getDeclaredMethod("storageFailed", File::class.java).apply { isAccessible = true }
        method.invoke(service!!.get(), sd)
    }

    private fun broadcast(action: String, path: String) {
        context.sendBroadcast(Intent(action, Uri.fromFile(File(path))))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun cardThatDoesNotWriteIsReplacedByTabletMemoryUntilReturned() {
        startService()
        cardError(); cardError()
        Thread.sleep(200)
        assertEquals("одна-две ошибки — ещё пробуем карту", sd, storage())
        assertNull(RecVolumes.brokenCard)
        cardError()
        waitFor("запись не перешла в память планшета") { storage() == internal }
        assertTrue("видно в редакторе камеры", RecordingStatus.sdMissing("cam_fault"))
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

    /** Извлечение: узнаём сразу, а не через 10 минут, — иначе Android закроет приложение с файлами на карте. */
    @Test fun ejectMovesRecordingAtOnce() {
        startService()
        ShadowEnvironment.setExternalStorageState(sd, Environment.MEDIA_UNMOUNTED)
        broadcast(Intent.ACTION_MEDIA_EJECT, sd.path)
        waitFor("запись не ушла с извлекаемой карты", 1_500) { storage() == internal } // обычная сверка носителя — раз в 5 с
        ShadowEnvironment.setExternalStorageState(sd, Environment.MEDIA_MOUNTED)
        broadcast(Intent.ACTION_MEDIA_MOUNTED, sd.path)
        waitFor("запись не вернулась", 1_500) { storage() == sd }
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
