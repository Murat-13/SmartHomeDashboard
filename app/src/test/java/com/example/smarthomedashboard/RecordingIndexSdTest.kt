package com.example.smarthomedashboard

import android.os.Environment
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/**
 * Метки событий («Человек», «Звонок») и временно недоступная SD-карта (ревью 22.11.2026, docs/review-2026-11-22.md,
 * пункт 2). Служба сверяла индекс меток с файлами одного носителя — того, куда пишет сейчас: без карты метки роликов
 * на карте стирались, а после возврата карты — метки роликов, записанных без неё в память планшета.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingIndexSdTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var internal: File
    private lateinit var sd: File
    private var service: ServiceController<RecorderService>? = null
    private val camera = RecLayout.cameraKey("cam_sd")

    @Before fun setUp() {
        // Два носителя: память планшета и SD-карта (Robolectric второй носитель не показывает — подменяем папки).
        internal = File(context.filesDir, "storage_internal").apply { deleteRecursively(); mkdirs() }
        sd = File(context.filesDir, "storage_sd").apply { deleteRecursively(); mkdirs() }
        RecorderService.dirsOverride = { arrayOf(internal, sd) }
        listOf(internal, sd).forEach {
            ShadowEnvironment.setExternalStorageState(it, Environment.MEDIA_MOUNTED)
            ShadowStatFs.registerStats(it, 1_000_000, 1_000_000, 1_000_000)
        }
        val always = RecordingConfig.Mode.CONTINUOUS_EVENTS
        val tile = TileEntity("cam_sd", DoorbellConfig.CAMERA_TYPE, "grid", "Камера", 0, 0, 1, 1,
            config = RecordingConfig(mode = always, remoteMode = always).writeTo(org.json.JSONObject(DoorbellConfig.newTileConfig())
                .put("rtsp_sub", "rtsp://127.0.0.1:9/sub").put("rtsp_main", "rtsp://127.0.0.1:9/main").toString()))
        TileManager(context).saveTiles(listOf(tile))
        RecordingDevice(context).setEnabled(tile.id, true)
        RecordingDevice(context).useSdCard = true
        context.deleteDatabase(RecIndex.DB_NAME)
    }

    @After fun tearDown() {
        stop()
        RecorderService.dirsOverride = null
        listOf(internal, sd).forEach { it.deleteRecursively() }
        RecordingDevice(context).setEnabled("cam_sd", false)
        RecordingDevice(context).useSdCard = false
        RecordingStatus.clear()
        context.deleteDatabase(RecIndex.DB_NAME)
    }

    private fun card(mounted: Boolean) =
        ShadowEnvironment.setExternalStorageState(sd, if (mounted) Environment.MEDIA_MOUNTED else Environment.MEDIA_REMOVED)

    /** Ролик события на носителе [root] с меткой в индексе (как после записи). */
    private fun event(root: File, hoursAgo: Int, label: String): RecFile {
        val start = System.currentTimeMillis() - hoursAgo * 3_600_000L
        val file = File(RecLayout.dayDir(root, camera, start).apply { mkdirs() }, RecLayout.closedName(RecFile.Kind.EVENT, start, 30_000))
            .apply { writeBytes(ByteArray(100)) }
        val rec = RecLayout.parse(file, camera)!!
        RecIndex(context).use { it.add(rec, setOf(label)) }
        return rec
    }

    /** Холодный запуск службы: при нём полный обход носителей и сверка индекса. */
    private fun coldStart() {
        stop()
        service = Robolectric.buildService(RecorderService::class.java).create().startCommand(0, 1)
        Thread.sleep(1_500)
    }

    private fun stop() {
        service?.destroy()
        service = null
        Thread.sleep(300)
    }

    /** Метки, которые увидит архив (он читает все носители). */
    private fun labels(file: RecFile): Set<String> = RecIndex(context).use { index ->
        RecArchive(context, roots = { listOf(internal, sd) }, index = { index }).load(camera).events.single { it.file.startMs == file.startMs }.labels
    }

    @Test fun labelsOfEventsOnCardSurviveStartWithoutCard() {
        card(mounted = true)
        val onCard = event(sd, 2, "person")
        card(mounted = false)
        coldStart() // запуск без карты: пишем в память планшета
        card(mounted = true)
        coldStart()
        assertEquals("Ролик с карты после её возврата находится фильтром «Человек»", setOf("person"), labels(onCard))
    }

    @Test fun labelsOfEventsWrittenWithoutCardSurviveCardReturn() {
        card(mounted = true)
        val withoutCard = event(internal, 1, "visitor") // записан, пока карты не было
        coldStart() // карта на месте: пишем на неё
        assertEquals("Ролик из памяти планшета находится фильтром «Звонок»", setOf("visitor"), labels(withoutCard))
    }

    @Test fun rowOfDeletedClipIsStillRemovedWhenAllStorageIsVisible() {
        card(mounted = true)
        val kept = event(sd, 3, "person")
        val gone = event(internal, 4, "vehicle")
        gone.path.delete() // удалён в обход приложения
        coldStart()
        RecIndex(context).use { index ->
            val starts = index.events(camera).map { it.startMs }
            assertTrue("Строка удалённого ролика убрана", gone.startMs !in starts)
            assertTrue("Строка ролика на карте осталась", kept.startMs in starts)
        }
    }
}
