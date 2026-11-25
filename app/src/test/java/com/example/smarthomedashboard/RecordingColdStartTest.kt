package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/**
 * Очистка записей при холодном запуске службы (ревью 22.11.2026, docs/review-2026-11-22.md, пункт 1): первая
 * очистка шла раньше, чем служба узнавала настройки камер, — по срокам по умолчанию (7 дней 24/7, 30 дней события).
 * Холодный запуск — каждая установка новой версии, перезагрузка, падение.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingColdStartTest {
    private val context = RuntimeEnvironment.getApplication()
    private val day = 86_400_000L
    private lateinit var root: File
    private var service: ServiceController<RecorderService>? = null

    @Before fun setUp() {
        root = context.getExternalFilesDirs(null)[0]
        File(root, RecLayout.ROOT).deleteRecursively()
        roomy()
    }

    @After fun tearDown() {
        service?.destroy()
        Thread.sleep(300)
        File(root, RecLayout.ROOT).deleteRecursively()
        RecordingDevice(context).agePausedUntil = 0
        RecordingDevice(context).enabledTiles.forEach { RecordingDevice(context).setEnabled(it, false) }
        RecordingStatus.clear()
    }

    /** Свободно ~4 ГБ: остаток (2 ГБ) соблюдён, лимит (авто, половина) не достигнут. */
    private fun roomy() = ShadowStatFs.registerStats(root, 1_000_000, 1_000_000, 1_000_000)

    private fun tile(id: String, rec: RecordingConfig) = TileEntity(id, DoorbellConfig.CAMERA_TYPE, "grid", "Камера", 0, 0, 1, 1,
        config = rec.writeTo(org.json.JSONObject(DoorbellConfig.newTileConfig())
            .put("rtsp_sub", "rtsp://127.0.0.1:9/sub").put("rtsp_main", "rtsp://127.0.0.1:9/main").toString()))

    private fun put(tileId: String, kind: RecFile.Kind, ageDays: Double, starred: Boolean = false, bytes: Int = 100): File {
        val camera = RecLayout.cameraKey(tileId)
        val start = System.currentTimeMillis() - (ageDays * day).toLong()
        return File(RecLayout.dayDir(root, camera, start).apply { mkdirs() }, RecLayout.closedName(kind, start, 60_000, starred))
            .apply { writeBytes(ByteArray(bytes)) }
    }

    /**
     * Холодный запуск службы и её первая очистка (поток управления). Очистка одна на все файлы: дождавшись удаления
     * [marker] (он старше любого срока), проверяем остальные.
     */
    private fun coldStart(marker: File, fresh: Boolean = true) {
        // Запись, законченная только что: без свежих записей запуск похож на сбитые часы (ClockGuard), и срок сутки не действует.
        if (fresh) put("cam_fresh", RecFile.Kind.CONTINUOUS, 0.01)
        service = Robolectric.buildService(RecorderService::class.java).create().startCommand(0, 1)
        val until = System.currentTimeMillis() + 10_000
        while (marker.exists() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertFalse("Первая очистка не прошла за 10 с", marker.exists())
    }

    /** Запись одинакова рядом и издалека — служба не выясняет, где камера, и сразу её запускает. */
    private val always = RecordingConfig.Mode.CONTINUOUS_EVENTS

    /**
     * Записи открываются до обхода носителя (24.11.2026): на SD-карте обход занимает секунды, и плитка не дожидалась
     * общего потока — шла к камере вторым подключением. Мало места — сначала очистка, как раньше.
     */
    /** Плитка с настоящими адресами потоков (у [tile] они не читаются — тем тестам записи не нужны). */
    private fun streamingTile(id: String) = TileEntity(id, DoorbellConfig.CAMERA_TYPE, "grid", "Камера", 0, 0, 1, 1,
        config = RecordingConfig(mode = always, remoteMode = always).writeTo(
            DoorbellConfig(rtspMain = "rtsp://127.0.0.1:9/main", rtspSub = "rtsp://127.0.0.1:9/sub").writeTo(DoorbellConfig.newTileConfig())))

    @Test fun recordingsOpenBeforeStorageWalk() {
        val cam = streamingTile("cam_fast")
        TileManager(context).saveTiles(listOf(cam))
        RecordingDevice(context).setEnabled(cam.id, true)
        val marker = put(cam.id, RecFile.Kind.CONTINUOUS, 400.0)
        var streamsAtWalk = -1
        RecorderService.cleanupProbe = { streamsAtWalk = CameraHub.openStreams }
        try { coldStart(marker) } finally { RecorderService.cleanupProbe = null }
        assertTrue("записи открыты до обхода носителя: потоков $streamsAtWalk", streamsAtWalk > 0)
    }

    /** Архив открывается сразу (25.11.2026): служба отдаёт список записей в память и дальше ведёт его сама. */
    @Test fun serviceKeepsCatalogOfRecordings() {
        RecCatalog.clear()
        val cam = tile("cam_list", RecordingConfig(mode = always, remoteMode = always))
        TileManager(context).saveTiles(listOf(cam))
        RecordingDevice(context).setEnabled(cam.id, true)
        val kept = put(cam.id, RecFile.Kind.EVENT, 1.0)
        val marker = put(cam.id, RecFile.Kind.CONTINUOUS, 400.0)
        coldStart(marker, fresh = false)
        val camera = RecLayout.cameraKey(cam.id)
        fun known() = RecCatalog.camera(listOf(root), camera)?.map { it.path.path }
        val until = System.currentTimeMillis() + 5_000
        while (known() != listOf(kept.path) && System.currentTimeMillis() < until) Thread.sleep(50)
        assertEquals("удалённого очисткой нет, остальное есть", listOf(kept.path), known())
        assertEquals("размеры — для оценки места", listOf(100L), RecCatalog.files(root)?.map { it.bytes })
        // Закончился ролик события — он в списке без нового обхода.
        val clip = put(cam.id, RecFile.Kind.EVENT, 0.1)
        val file = RecLayout.parse(clip, camera)!!
        val method = RecorderService::class.java.getDeclaredMethod("segmentClosed", RecFile::class.java).apply { isAccessible = true }
        method.invoke(service!!.get(), file)
        assertEquals(setOf(kept.path, clip.path), known()?.toSet())
    }

    @Test fun lowSpaceCleansBeforeRecording() {
        ShadowStatFs.registerStats(root, 1_000_000, 1_000, 1_000) // свободно меньше остатка
        val cam = streamingTile("cam_low")
        TileManager(context).saveTiles(listOf(cam))
        RecordingDevice(context).setEnabled(cam.id, true)
        val marker = put(cam.id, RecFile.Kind.CONTINUOUS, 400.0)
        var streamsAtWalk = -1
        RecorderService.cleanupProbe = { streamsAtWalk = CameraHub.openStreams }
        try { coldStart(marker) } finally { RecorderService.cleanupProbe = null }
        assertEquals("мало места — сначала очистка", 0, streamsAtWalk)
    }

    @Test fun ownerKeepDaysHoldOnColdStart() {
        val cam = tile("cam_long", RecordingConfig(mode = always, remoteMode = always, keepContinuousDays = 14, keepEventDays = 90))
        TileManager(context).saveTiles(listOf(cam))
        RecordingDevice(context).setEnabled(cam.id, true)
        val tenDays = put(cam.id, RecFile.Kind.CONTINUOUS, 10.0)
        val twentyDays = put(cam.id, RecFile.Kind.CONTINUOUS, 20.0)
        val event60 = put(cam.id, RecFile.Kind.EVENT, 60.0)
        val event100 = put(cam.id, RecFile.Kind.EVENT, 100.0)
        coldStart(marker = twentyDays)
        assertTrue("24/7 за 10 дней при сроке 14 — остаётся", tenDays.exists())
        assertTrue("Событие за 60 дней при сроке 90 — остаётся", event60.exists())
        assertFalse("Событие за 100 дней — старше срока", event100.exists())
    }

    /**
     * Камеры, у которых запись сейчас не идёт (выключена на этом планшете, камера видна только издалека), — их архив
     * на планшете по-прежнему хранится по их же срокам, а не по умолчанию.
     */
    @Test fun keepDaysOfCamerasNotRecordingNowAreKept() {
        val running = tile("cam_run", RecordingConfig(mode = always, remoteMode = always))
        val off = tile("cam_off", RecordingConfig(mode = always, remoteMode = always, keepContinuousDays = 14))
        val remote = tile("cam_far", RecordingConfig(mode = always, remoteMode = RecordingConfig.Mode.OFF, keepContinuousDays = 14))
        TileManager(context).saveTiles(listOf(running, off, remote))
        RecordingDevice(context).setEnabled(running.id, true)
        RecordingDevice(context).setEnabled(remote.id, true)
        val offFile = put(off.id, RecFile.Kind.CONTINUOUS, 10.0)
        val remoteFile = put(remote.id, RecFile.Kind.CONTINUOUS, 10.0)
        val runningFile = put(running.id, RecFile.Kind.CONTINUOUS, 10.0)
        coldStart(marker = runningFile) // срок по умолчанию (7 дней) у камеры без своего — как раньше
        assertTrue("Запись выключена на планшете — срок камеры 14 дней", offFile.exists())
        assertTrue("Камера не в сети планшета — срок камеры 14 дней", remoteFile.exists())
    }

    /**
     * Второй аудит 25.11.2026, NEW-REC-1: ★ поставили самому старому ролику, пока место заполнено. Служба знала его по прежнему имени:
     * очистка «удаляла» запись (файла с тем именем уже нет) и стирала метки сохранённого ролика.
     */
    @Test fun starredFromArchiveKeepsLabelsWhenCleanupRuns() {
        context.deleteDatabase(RecIndex.DB_NAME)
        val cam = tile("cam_star", RecordingConfig(mode = always, remoteMode = always, keepContinuousDays = 90, keepEventDays = 90))
        TileManager(context).saveTiles(listOf(cam))
        RecordingDevice(context).setEnabled(cam.id, true)
        val camera = RecLayout.cameraKey(cam.id)
        val event = RecLayout.parse(put(cam.id, RecFile.Kind.EVENT, 3.0), camera)!!
        RecIndex(context).use { it.add(event, setOf("person")) }
        val marker = put(cam.id, RecFile.Kind.CONTINUOUS, 400.0)
        coldStart(marker)
        val until = System.currentTimeMillis() + 5_000
        while (RecCatalog.camera(listOf(root), camera)?.any { it.path == event.path } != true && System.currentTimeMillis() < until) Thread.sleep(50)
        // Человек в архиве ставит ★.
        val starred = RecArchive(context, roots = { listOf(root) }).setStarred(event, true)!!
        // Места не хватает — очистка удаляет самое старое.
        ShadowStatFs.registerStats(root, 100_000, 100_000, 100_000)
        val newer = RecLayout.parse(put(cam.id, RecFile.Kind.CONTINUOUS, 0.2), camera)!!
        RecorderService::class.java.getDeclaredMethod("segmentClosed", RecFile::class.java).apply { isAccessible = true }.let { m ->
            val control = RecorderService::class.java.getDeclaredField("control").apply { isAccessible = true }.get(service!!.get()) as java.util.concurrent.ExecutorService
            control.submit { m.invoke(service!!.get(), newer) }.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertTrue("ролик со ★ на месте", starred.path.exists())
        RecIndex(context).use { assertEquals("и его метки тоже", setOf("person"), it.labels(camera, event.startMs)) }
        context.deleteDatabase(RecIndex.DB_NAME)
    }

    /** Нехватка места по-прежнему важнее срока: удаляется самое старое, ★ не трогается. */
    @Test fun lowSpaceStillDeletesOldestButKeepsStarred() {
        val cam = tile("cam_full", RecordingConfig(mode = always, remoteMode = always, keepContinuousDays = 90))
        TileManager(context).saveTiles(listOf(cam))
        RecordingDevice(context).setEnabled(cam.id, true)
        val starred = put(cam.id, RecFile.Kind.CONTINUOUS, 3.0, starred = true)
        val oldest = put(cam.id, RecFile.Kind.CONTINUOUS, 2.0)
        val newest = put(cam.id, RecFile.Kind.CONTINUOUS, 0.5)
        // Свободно меньше остатка (2 ГБ): освобождать нечем, но самое старое удаляется первым.
        ShadowStatFs.registerStats(root, 100_000, 100_000, 100_000)
        coldStart(marker = oldest) // места не хватает — самое старое удалено, хотя срок 90 дней
        assertTrue("★ очистка не трогает", starred.exists())
        assertFalse("Освобождать нечем — удалено всё, кроме ★", newest.exists())
    }
}
