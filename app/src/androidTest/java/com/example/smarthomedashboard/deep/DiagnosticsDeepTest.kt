package com.example.smarthomedashboard.deep

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smarthomedashboard.DoorbellConfig
import com.example.smarthomedashboard.DoorbellEditorActivity
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
 * стендом: аргументы `rtspHost` (с эмулятора — 10.0.2.2) и `diagPort` (8557). Снимок экрана — в папке приложения.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsDeepTest {
    private lateinit var ha: FakeHomeAssistant
    private var scenario: ActivityScenario<DoorbellEditorActivity>? = null

    @After fun stop() {
        scenario?.close()
        if (::ha.isInitialized) ha.close()
    }

    @Test fun checkNowFindsStandProblems() {
        val host = argument("rtspHost")
        assumeTrue("Нужен аргумент rtspHost (стенд камер)", host != null)
        val port = argument("diagPort") ?: "8557"
        ha = FakeHomeAssistant()
        val door = DoorbellConfig(rtspMain = "rtsp://$host:$port/Streaming/Channels/101", rtspSub = "rtsp://$host:$port/Streaming/Channels/102")
        val config = JSONObject(DoorbellConfig.newTileConfig()).put(DoorbellConfig.KEY, door.toJson()).toString()
        prepareApp(ha, listOf(TileEntity("deep_door", DoorbellConfig.TYPE, "grid", "Домофон", 40, 40, 1, 1, config = config)))
        DoorbellEditorActivity.diagSeconds = 30
        scenario = ActivityScenario.launch(Intent(appContext, DoorbellEditorActivity::class.java).putExtra("tile_id", "deep_door"))
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

    private object SystemClockSleep { fun sleep(ms: Long) = android.os.SystemClock.sleep(ms) }
}
