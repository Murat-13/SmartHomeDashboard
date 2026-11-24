package com.example.smarthomedashboard

import android.content.ClipboardManager
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Раздел «Диагностика видео» в редакторе домофона (docs/diagnostics-tz.md, этап 1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class DiagnosticsEditorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)

    @After fun tearDown() {
        DoorbellHub.detach()
        PlaybackLog.clear()
        DoorbellEditorActivity.diagSeconds = 30
    }

    private fun open(main: String, sub: String = ""): DoorbellEditorActivity {
        val config = JSONObject(DoorbellConfig.newTileConfig())
            .put(DoorbellConfig.KEY, DoorbellConfig(rtspMain = main, rtspSub = sub).toJson()).toString()
        manager.saveTiles(listOf(TileEntity("door", DoorbellConfig.TYPE, "grid", "Домофон", 10, 20, 1, 1, config = config)))
        return Robolectric.buildActivity(DoorbellEditorActivity::class.java,
            Intent(context, DoorbellEditorActivity::class.java).putExtra("tile_id", "door")).create().get().also { idle() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun DoorbellEditorActivity.view(tag: String): View? = findViewById<View>(android.R.id.content).findViewWithTag(tag)
    private fun DoorbellEditorActivity.text(tag: String) = (view(tag) as TextView).text.toString()

    @Test fun nothingSeenYetAsksToCheck() {
        val activity = open("")
        assertEquals("Нажмите «Проверить сейчас»", activity.text("door_diag_summary"))
        assertNotNull(activity.view("door_diag_run"))
    }

    @Test fun shownVideoGivesVerdictWithoutCheck() {
        val main = "rtsp://cam.local:554/main"
        // Основной поток показывался программно и не успевал — это видно сразу, без «Проверить».
        PlaybackLog.add(main, ShowSummary(0, 30f, "c2.android.avc.decoder", true, 2560, 1920, 8f, 0.5f, 7))
        val activity = open(main)
        assertTrue(activity.text("door_diag_summary"), activity.text("door_diag_summary").startsWith("Пока видно: Мешает"))
        assertNotNull("остановки", activity.view("door_diag_П1"))
        assertNotNull("субпотока нет", activity.view("door_diag_К5"))
    }

    @Test fun checkNowWatchesCameraAndCopiesReportWithoutAddress() {
        val camera = FakeRtspCamera.start()
        try {
            DoorbellEditorActivity.diagSeconds = 2
            val activity = open(camera.url)
            activity.view("door_diag_run")!!.performClick()
            val until = System.currentTimeMillis() + 30_000
            while (activity.text("door_diag_run") != "Проверить сейчас") {
                if (System.currentTimeMillis() > until) fail("Проверка не закончилась")
                Thread.sleep(100); idle()
            }
            val summary = activity.text("door_diag_summary")
            assertFalse(summary, summary.startsWith("Проверка не удалась") || summary.startsWith("Проверить не удалось"))
            assertNotNull("кодек виден по SPS", activity.view("door_diag_К4"))
            assertTrue(activity.text("door_diag_К4"), activity.text("door_diag_К4").contains("H.264"))
            activity.view("door_diag_copy")!!.performClick()
            idle()
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(clip, clip.startsWith("Диагностика камеры"))
            assertTrue(clip, clip.contains("Основной поток: H.264, 2560×1920"))
            assertFalse("адрес камеры", clip.contains("127.0.0.1") || clip.contains("rtsp", ignoreCase = true))
        } finally { camera.stop() }
    }
}
