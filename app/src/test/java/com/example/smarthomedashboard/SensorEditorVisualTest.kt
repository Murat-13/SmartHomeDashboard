package com.example.smarthomedashboard

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.EditText
import com.example.smarthomedashboard.data.ColorRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Настоящий Android View render, искусственные данные. Не скриншот физического устройства. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w800dp-h1280dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SensorEditorVisualTest {
    @Test fun actualEditorRendersWithArtificialReadingsAndColorRules() {
        val context = RuntimeEnvironment.getApplication()
        val owner = MainViewModel()
        owner.onEntityStateChanged("sensor.artificial_voltage", "230.4", JSONObject().put("friendly_name", "Напряжение").put("unit_of_measurement", "V"))
        owner.onEntityStateChanged("sensor.artificial_frequency", "50.01", JSONObject().put("friendly_name", "Частота").put("unit_of_measurement", "Hz"))
        TileEditorPreviewSource.attach(owner)
        val controller = Robolectric.buildActivity(TileSettingsActivity::class.java, Intent(context, TileSettingsActivity::class.java))
        val activity = controller.get()
        activity.setTheme(R.style.Theme_SmartHomeDashboard)
        try {
            controller.create().start().visible()
            shadowOf(Looper.getMainLooper()).idle()
            activity.findViewById<EditText>(R.id.etTileTitle).setText("Электросеть — пример")
            val editor = TileSettingsActivity::class.java.getDeclaredField("sensorEditor").apply { isAccessible = true }.get(activity) as SensorTileEditor
            editor.pendingPick = SensorTileEditor.PickTarget("main")
            editor.pickerResult(listOf("sensor.artificial_voltage", "sensor.artificial_frequency"))
            editor.draft.rules += SensorTileDraft.Rule(ColorRule("sensor.artificial_voltage", "220", "240", "#804CAF50"))
            editor.pendingPick = SensorTileEditor.PickTarget("rule", 0)
            editor.pickerResult(listOf("sensor.artificial_voltage"))
            shadowOf(Looper.getMainLooper()).idle()
            val decor = activity.window.decorView
            fun draw(name: String) {
                decor.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, 1200, 1920)
                val bitmap = Bitmap.createBitmap(1200, 1920, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(bitmap))
                val path = File("build/reports/editor-visual/$name.png")
                requireNotNull(path.parentFile).mkdirs()
                path.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                assertTrue(path.length() > 10_000)
                assertEquals(2, editor.draft.compact.size)
            }
            draw("editor-main")
            val footer = activity.findViewById<View>(R.id.sensorEditorFooter)
            val toolbar = activity.findViewById<View>(R.id.toolbar)
            val footerTop = footer.top
            val toolbarTop = toolbar.top
            decor.findViewWithTag<View>("jump-rules").performClick()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            draw("editor-colors")
            assertEquals(footerTop, footer.top)
            assertEquals(toolbarTop, toolbar.top)
            assertEquals(0, decor.scrollY)
            assertEquals(View.VISIBLE, footer.visibility)
            assertTrue(footer.width > 0 && footer.height > 0)
            assertTrue(activity.findViewById<View>(R.id.btnSave).width > 0)
            println("EDITOR_GEOMETRY footerTop=" + footer.top + " toolbarTop=" + toolbar.top + " rootScroll=" + decor.scrollY)
        } finally {
            controller.stop().destroy()
            owner.disconnectWebSocket()
        }
    }
}
