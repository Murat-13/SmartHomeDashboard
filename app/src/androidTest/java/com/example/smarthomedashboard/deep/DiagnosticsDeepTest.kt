package com.example.smarthomedashboard.deep

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smarthomedashboard.DoorbellConfig
import com.example.smarthomedashboard.DoorbellEditorActivity
import com.example.smarthomedashboard.TabletActivity
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Диагностика камеры (docs/diagnostics-tz.md, этап 1) на эмуляторе со стендом камер: редактор домофона →
 * «Проверить сейчас» → находки камеры D «плохие условия» (линия 10 Мбит/с, потери, 20 к/с с датчика 25). Только со
 * стендом: аргументы `rtspHost` (с эмулятора — 10.0.2.2) и `diagPort` (8557); на RTSP-стенде CI (без `diagPort`) тест
 * пропускается. Снимок экрана — в папке приложения.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsDeepTest {
    private lateinit var ha: FakeHomeAssistant
    private var screen: TestScreen<DoorbellEditorActivity>? = null

    @After fun stop() {
        screen?.close()
        if (::ha.isInitialized) ha.close()
    }

    @Test fun checkNowFindsStandProblems() {
        val host = argument("rtspHost")
        val port = argument("diagPort")
        // Только со стендом tools/camera-stand и его камерой D: у RTSP-стенда CI (rtspHost без diagPort) её нет.
        assumeTrue("Нужны аргументы rtspHost и diagPort (камера D стенда камер)", host != null && port != null)
        ha = FakeHomeAssistant()
        val door = DoorbellConfig(rtspMain = "rtsp://$host:$port/Streaming/Channels/101", rtspSub = "rtsp://$host:$port/Streaming/Channels/102")
        val config = JSONObject(DoorbellConfig.newTileConfig()).put(DoorbellConfig.KEY, door.toJson()).toString()
        prepareApp(ha, listOf(TileEntity("deep_door", DoorbellConfig.TYPE, "grid", "Домофон", 40, 40, 1, 1, config = config)))
        DoorbellEditorActivity.diagSeconds = 30
        screen = TestScreen(DoorbellEditorActivity::class.java, Intent(appContext, DoorbellEditorActivity::class.java).putExtra("tile_id", "deep_door"))
        fun view(tag: String): View? { val activity = resumedActivity(); return onMain { activity?.window?.decorView?.findViewWithTag<View>(tag) } }
        waitFor(10_000, "раздел «Диагностика»") { view("door_diag_run") != null }
        val run = view("door_diag_run")!!
        onMain {
            // Раздел — в прокрутке справа: показать его, как сделал бы человек.
            run.requestRectangleOnScreen(android.graphics.Rect(0, 0, run.width, run.height * 12), true)
            run.performClick()
        }
        waitFor(60_000, "конец проверки") { onMain { (run as TextView).text.toString() } == "Проверить сейчас" }
        val list = view("door_diag_list") as android.view.ViewGroup
        val rows = onMain { (0 until list.childCount).map { (list.getChildAt(it) as TextView).text.toString() } }
        val summaryView = view("door_diag_summary") as TextView
        val summary = onMain { summaryView.text.toString() }
        log("Диагностика стенда: $summary")
        rows.forEach { log("  $it") }
        SystemClockSleep.sleep(800)
        val shot = instrumentation.uiAutomation.takeScreenshot()
        File(appContext.getExternalFilesDir(null), "diag_editor.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(summary, summary.startsWith("Мешает"))
        assertTrue(rows.joinToString("\n"), rows.any { it.startsWith("✗ Скорость линии камеры") || it.startsWith("⚠ Скорость линии камеры") })
        assertTrue(rows.joinToString("\n"), rows.any { it.startsWith("⚠ Потери по дороге") })
        assertTrue(rows.joinToString("\n"), rows.any { it.startsWith("⚠ Частота кадров") })
        assertTrue("кодек виден", rows.any { it.startsWith("✓ Кодек: H.264") })
    }

    /**
     * Этап 4: камера D стенда с поддельным ONVIF (порт `onvifPort`, обычно 8580) — после «Проверить сейчас» у совета о частоте
     * есть «Применить: частота основного потока: 20 → 25 к/с» (камера разрешает до 30). Кнопку не нажимаем: изменение и
     * возврат проверены на том же ONVIF тестом JVM (DiagnosticsStandTest).
     */
    @Test fun applyButtonOnStandCamera() {
        val host = argument("rtspHost")
        val port = argument("diagPort")
        val onvif = argument("onvifPort")
        assumeTrue("Нужны аргументы rtspHost, diagPort и onvifPort (камера D стенда)", host != null && port != null && onvif != null)
        ha = FakeHomeAssistant()
        val cam = DoorbellConfig(host = host!!, onvifPort = onvif!!.toInt(), rtspUser = "admin",
            rtspMain = "rtsp://$host:$port/Streaming/Channels/101", rtspSub = "rtsp://$host:$port/Streaming/Channels/102")
        val config = JSONObject(DoorbellConfig.newTileConfig(camera = true)).put(DoorbellConfig.KEY, cam.toJson()).toString()
        prepareApp(ha, listOf(TileEntity("deep_cam", DoorbellConfig.CAMERA_TYPE, "grid", "Камера", 40, 40, 1, 1, config = config)))
        com.example.smarthomedashboard.CameraApply.setEnabled(appContext, true)
        DoorbellEditorActivity.diagSeconds = 20
        screen = TestScreen(DoorbellEditorActivity::class.java, Intent(appContext, DoorbellEditorActivity::class.java).putExtra("tile_id", "deep_cam"))
        fun view(tag: String): View? { val activity = resumedActivity(); return onMain { activity?.window?.decorView?.findViewWithTag<View>(tag) } }
        try {
            waitFor(10_000, "раздел «Диагностика»") { view("door_diag_run") != null }
            val password = view("door_password") as TextView
            onMain { password.text = "x" }
            val run = view("door_diag_run")!!
            onMain { run.requestRectangleOnScreen(android.graphics.Rect(0, 0, run.width, run.height * 12), true); run.performClick() }
            waitFor(60_000, "конец проверки") { onMain { (run as TextView).text.toString() } == "Проверить сейчас" }
            waitFor(5_000, "кнопка «Применить» у частоты") { view("door_diag_apply_К2") != null }
            val button = view("door_diag_apply_К2") as TextView
            onMain { button.requestRectangleOnScreen(android.graphics.Rect(0, 0, button.width, button.height), true) }
            val label = onMain { button.text.toString() }
            log("Стенд: $label")
            assertTrue(label, label.startsWith("Применить: частота основного потока: ") && label.endsWith(" → 25 к/с"))
            SystemClockSleep.sleep(500)
            val shot = instrumentation.uiAutomation.takeScreenshot()
            File(appContext.getExternalFilesDir(null), "diag_apply.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { com.example.smarthomedashboard.CameraApply.setEnabled(appContext, false) }
    }

    /** Экран «Планшет» (этап 2): настоящие декодеры эмулятора и решение по плитке домофона. Стенд не нужен. */
    @Test fun tabletScreenShowsDecoders() {
        ha = FakeHomeAssistant()
        val door = DoorbellConfig(rtspMain = "rtsp://10.0.2.2:1/main", rtspSub = "rtsp://10.0.2.2:1/sub")
        val config = JSONObject(DoorbellConfig.newTileConfig()).put(DoorbellConfig.KEY, door.toJson()).toString()
        prepareApp(ha, listOf(TileEntity("deep_door", DoorbellConfig.TYPE, "grid", "Домофон", 40, 40, 1, 1, config = config)))
        val tablet = TestScreen(TabletActivity::class.java)
        try {
            fun text(tag: String): String? { val activity = resumedActivity(); return onMain { (activity?.window?.decorView?.findViewWithTag<View>(tag) as? TextView)?.text?.toString() } }
            waitFor(10_000, "экран «Планшет»") { text("tablet_h264") != null }
            val h264 = text("tablet_h264")!!
            log("Планшет: ${text("tablet_model")}; $h264; ${text("tablet_h265")}; ${text("tablet_heat")}")
            assertTrue(h264, h264.startsWith("H.264 — "))
            val shown = resumedActivity()
            val camera = onMain { (shown?.window?.decorView?.findViewWithTag<View>("tablet_camera_deep_door") as? android.view.ViewGroup)?.childCount }
            assertTrue("строка домофона", (camera ?: 0) >= 2)
            SystemClockSleep.sleep(500)
            val shot = instrumentation.uiAutomation.takeScreenshot()
            File(appContext.getExternalFilesDir(null), "tablet_screen.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { tablet.close() }
    }

    private object SystemClockSleep { fun sleep(ms: Long) = android.os.SystemClock.sleep(ms) }
}
