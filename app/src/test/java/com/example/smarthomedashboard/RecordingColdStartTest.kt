package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.After
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
    private fun coldStart(marker: File) {
        service = Robolectric.buildService(RecorderService::class.java).create().startCommand(0, 1)
        val until = System.currentTimeMillis() + 10_000
        while (marker.exists() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertFalse("Первая очистка не прошла за 10 с", marker.exists())
    }

    /** Запись одинакова рядом и издалека — служба не выясняет, где камера, и сразу её запускает. */
    private val always = RecordingConfig.Mode.CONTINUOUS_EVENTS

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
