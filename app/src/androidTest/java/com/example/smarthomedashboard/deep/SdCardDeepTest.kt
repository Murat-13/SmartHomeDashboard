package com.example.smarthomedashboard.deep

import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.example.smarthomedashboard.CameraHub
import com.example.smarthomedashboard.DoorbellCard
import com.example.smarthomedashboard.DoorbellConfig
import com.example.smarthomedashboard.DoorbellVideoView
import com.example.smarthomedashboard.MainActivity
import com.example.smarthomedashboard.RecFile
import com.example.smarthomedashboard.RecLayout
import com.example.smarthomedashboard.RecVolumes
import com.example.smarthomedashboard.RecordingConfig
import com.example.smarthomedashboard.RecordingDevice
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Карту памяти извлекают посреди записи и возвращают (аудит 25.11.2026, REC-02): Android закрывает приложения с открытыми на ней файлами,
 * запись идёт в память планшета, живое видео не рвётся. Нужна виртуальная карта эмулятора больше 1 ГБ (docs/deep-testing.md) — только с
 * аргументом `sdCard=1`.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SdCardDeepTest {
    private lateinit var ha: FakeHomeAssistant
    private var screen: TestScreen<MainActivity>? = null
    private var camera: FakeCamera? = null
    private var volume = ""

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8) }

    @After fun stop() {
        if (volume.isNotEmpty()) shell("sm mount $volume")
        screen?.close()
        if (::ha.isInitialized) ha.close()
        camera?.close()
        RecordingDevice(appContext).setEnabled(TILE, false)
        RecordingDevice(appContext).useSdCard = false
    }

    private fun files(root: File): List<RecFile> = RecLayout.scan(root).filter { it.camera == RecLayout.cameraKey(TILE) }
    private fun card(): DoorbellCard? = (resumedActivity() as? MainActivity)?.let { a ->
        onMain { a.window.decorView.findViewWithTag<View>(TILE)?.findViewWithTag<DoorbellCard>("door_compact") }
    }
    private fun status() = card()?.let { c -> onMain { c.video.status } }

    @Test fun recordingSurvivesCardEjectAndReturn() {
        assumeTrue("Только эмулятор", isEmulator())
        assumeTrue("Нужен аргумент sdCard=1", argument("sdCard") == "1")
        volume = shell("sm list-volumes public").lines().first { "mounted" in it }.substringBefore(' ')
        val (tablet, sd) = RecVolumes.dirs(appContext).let { it[0]!! to it[1]!! }
        listOf(tablet, sd).forEach { File(it, RecLayout.ROOT).deleteRecursively() }
        val cam = FakeCamera().also { camera = it }
        ha = FakeHomeAssistant()
        val size = JSONObject().put("button_size", 480).put("widget_height", 360).toString()
        val config = RecordingConfig(mode = RecordingConfig.Mode.CONTINUOUS, remoteMode = RecordingConfig.Mode.CONTINUOUS).writeTo(
            DoorbellConfig(rtspMain = cam.url, rtspSub = cam.url, talk = false).writeTo(DoorbellConfig.newTileConfig(size, camera = true)))
        prepareApp(ha, listOf(TileEntity(TILE, DoorbellConfig.CAMERA_TYPE, "grid", "Камера", 40, 120, 1, 1, config = config)))
        RecordingDevice(appContext).apply { setEnabled(TILE, true); useSdCard = true; reserveGb = 1; limitGb = 1 }
        screen = TestScreen(MainActivity::class.java)
        waitFor(60_000, "запись на карту: ${files(sd)}") { files(sd).isNotEmpty() }
        waitFor(60_000, "картинка на плитке (${status()})") { status() == DoorbellVideoView.Status.PLAYING }
        assertTrue("плитка смотрит через поток записи", CameraHub.openStreams == 1)
        Thread.sleep(6_000)
        val connections = cam.connections.get()
        assertEquals("до извлечения в памяти планшета записей нет", emptyList<RecFile>(), files(tablet))

        shell("sm unmount $volume")
        waitFor(30_000, "запись в памяти планшета") { files(tablet).isNotEmpty() }
        Thread.sleep(8_000)
        assertEquals("поток камеры не переподключался", connections, cam.connections.get())
        assertEquals("живое видео идёт", DoorbellVideoView.Status.PLAYING, status())
        log("Карту извлекли: запись в памяти планшета, подключений к камере ${cam.connections.get()}")

        shell("sm mount $volume")
        val moved = System.currentTimeMillis()
        waitFor(60_000, "запись вернулась на карту: ${files(sd)}") { files(sd).any { it.startMs >= moved - 2_000 } }
        assertEquals("поток камеры не переподключался", connections, cam.connections.get())
        val before = files(sd).filter { it.startMs < moved - 2_000 }
        assertTrue("записанное на карту до извлечения цело: $before", before.isNotEmpty() && before.all { it.bytes > 0 })
        assertTrue("закончено и переименовано: $before", before.any { !it.open })
        log("Карту вернули: запись снова на карте; до извлечения — ${before.size} файл(ов), закрыто ${before.count { !it.open }}")
    }

    private companion object { const val TILE = "cam_sd_deep" }
}
