package com.example.smarthomedashboard

import android.view.View
import android.widget.TextView
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
import org.robolectric.annotation.Config
import java.io.File

/**
 * Проверочные выключатели-файлы действуют, только пока человек включил проверочный режим (аудит 25.11.2026, SEC-08): подложенный
 * файл сам по себе не включает запись разговора, измерение со звуком в домофон и другие проверочные ветки.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugSwitchesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val base = context.getExternalFilesDir(null)!!
    private var now = 1_800_000_000_000L

    @Before fun setUp() {
        DebugSwitches.resetForTests(); DebugSwitches.now = { now }; DebugSwitches.set(context, false)
        for ((dir, name) in listOf("talk_debug" to "on", "talk_debug" to "no_aec", "talk_debug" to "probe_near", "video_debug" to "trough", "video_debug" to "arrival_trace", "tablet_debug" to "dim"))
            File(base, dir).apply { mkdirs() }.resolve(name).writeText("")
    }
    @After fun tearDown() { DebugSwitches.set(context, false); DebugSwitches.resetForTests(); listOf("talk_debug", "video_debug", "tablet_debug").forEach { File(base, it).deleteRecursively() } }

    private fun recorded(): Boolean {
        TalkDebug.begin(context); TalkDebug.write("mic", ShortArray(160), 160, 8000); TalkDebug.end()
        return File(base, "talk_debug/mic.pcm").exists().also { File(base, "talk_debug/mic.pcm").delete() }
    }

    @Test fun filesAloneDoNothing() {
        assertFalse("разговор не записывается", recorded())
        assertFalse(TalkDebug.flag(context, "no_aec"))
        assertFalse(LiveTuning.off(context, "trough"))
        assertFalse(PanelCooling.debug(context, "dim"))
        TalkProbe.maybeRun(context) { emptyList() }
        assertTrue("запрос измерения не тронут и не исполнен", File(base, "talk_debug/probe_near").exists())
    }

    @Test fun modeEnablesThemForADay() {
        DebugSwitches.set(context, true)
        assertTrue(recorded())
        assertTrue(TalkDebug.flag(context, "no_aec") && LiveTuning.off(context, "trough") && PanelCooling.debug(context, "dim"))
        now += 23 * 3_600_000L
        assertTrue(DebugSwitches.allowed(context))
        now += 2 * 3_600_000L
        assertFalse("через сутки гаснет сам", DebugSwitches.allowed(context))
        assertFalse(recorded())
        // Часы ушли далеко назад — режим не становится вечным.
        DebugSwitches.set(context, true); now -= 40L * 86_400_000
        assertFalse(DebugSwitches.allowed(context))
        // Переживает перезапуск приложения.
        now += 40L * 86_400_000; DebugSwitches.resetForTests(); DebugSwitches.now = { now }
        assertTrue(DebugSwitches.allowed(context))
    }

    @Test fun switchIsOnSupportScreen() {
        val activity = Robolectric.buildActivity(SupportActivity::class.java).setup().get()
        val button = activity.window.decorView.findViewWithTag<TextView>("support_debug")
        assertEquals("Проверочный режим: выключен — включить", button.text.toString())
        button.performClick()
        assertTrue(DebugSwitches.allowed(context))
        assertEquals("Проверочный режим: включён — выключить", button.text.toString())
        (button as View).performClick()
        assertFalse(DebugSwitches.allowed(context))
    }
}
