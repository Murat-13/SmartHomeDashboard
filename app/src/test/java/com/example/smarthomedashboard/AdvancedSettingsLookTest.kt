package com.example.smarthomedashboard

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
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

/** Расширенные настройки в стиле Modern (0.9.2, этап 6): тот же набор настроек и то же сохранение. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AdvancedSettingsLookTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)
    private val rules = JSONArray().put(JSONObject().put("entity_id", "sensor.t").put("from", "0").put("to", "20")
        .put("color_hex", "#8033CC33")).toString()
    private val config = JSONObject()
        .put("collapsed_sensor_configs", JSONArray().put(JSONObject().put("entity_id", "sensor.t").put("display_name", "Темп").put("decimals", 1)))
        .put("expanded_sensor_configs", JSONArray()
            .put(JSONObject().put("entity_id", "sensor.t").put("display_name", "Темп").put("decimals", 1))
            .put(JSONObject().put("entity_id", "sensor.h").put("display_name", "Влажн").put("decimals", 0)))
        .toString()

    @Before fun seed() {
        context.getSharedPreferences("tiles_prefs", 0).edit().clear().commit()
        manager.saveTiles(listOf(TileEntity("s", "sensor", "grid", "Кухня", 10, 20, 1, 1, config = config,
            colorRules = rules, fontSize = 18)))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(): AdvancedWidgetSettingsActivity = Robolectric.buildActivity(AdvancedWidgetSettingsActivity::class.java,
        Intent(context, AdvancedWidgetSettingsActivity::class.java).putExtra("tile_id", "s")).create().start().resume().get()
        .also { allowAccess(it); idle() }

    /** Как в тестах настроек: доступ без PIN-диалога. */
    private fun allowAccess(activity: AdvancedWidgetSettingsActivity) {
        org.robolectric.util.ReflectionHelpers.setField(activity, "pinAuthorized", true)
        org.robolectric.util.ReflectionHelpers.setField(activity, "pinRequired", false)
        activity.window.decorView.findViewById<View>(android.R.id.content).visibility = View.VISIBLE
    }

    private fun layout(activity: AdvancedWidgetSettingsActivity, width: Int, height: Int): View {
        val root = activity.findViewById<View>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        return root
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    @Test fun usesModernThemeStylesAndKeepsEverySetting() {
        val activity = open()
        layout(activity, 1280, 752)
        // Фон экрана даёт окно темы Modern (лишний фон корня убран — lint Overdraw, вид тот же).
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        assertNull(root.background)
        assertEquals(color(R.color.modern_background), (activity.window.decorView.background as ColorDrawable).color)

        val save = activity.findViewById<Button>(R.id.btnSave)
        val cancel = activity.findViewById<Button>(R.id.btnCancel)
        assertTrue("кнопки — MaterialButton темы Modern", save is MaterialButton && cancel is MaterialButton)
        assertEquals(color(R.color.modern_accent), (cancel as MaterialButton).strokeColor!!.defaultColor)
        listOf(R.id.btnSave, R.id.btnCancel, R.id.btnAddCollapsed, R.id.btnAddExpanded, R.id.btnAddColorRule).forEach {
            assertTrue("касание не меньше 48dp", activity.findViewById<View>(it).height >= dp(48) - 1)
        }

        // Все прежние настройки на месте и заполнены из плитки.
        assertEquals("18", activity.findViewById<EditText>(R.id.etFontSize).text.toString())
        val collapsed = activity.findViewById<RecyclerView>(R.id.rvCollapsedSensors)
        val expanded = activity.findViewById<RecyclerView>(R.id.rvExpandedSensors)
        val colorRules = activity.findViewById<RecyclerView>(R.id.rvColorRules)
        assertEquals(1, collapsed.adapter!!.itemCount)
        assertEquals(2, expanded.adapter!!.itemCount)
        assertEquals(1, colorRules.adapter!!.itemCount)

        val ruleRow = colorRules.getChildAt(0)
        assertNotNull(ruleRow.findViewById<EditText>(R.id.etFrom))
        assertTrue(ruleRow.findViewById<View>(R.id.btnDeleteRule).height >= dp(48) - 1)
        assertTrue(ruleRow.findViewById<View>(R.id.vColorPreview).height >= dp(48) - 1)
        val sensorRow = expanded.getChildAt(0)
        assertTrue(sensorRow.findViewById<Spinner>(R.id.spinnerDecimals).height >= dp(48) - 1)
        assertTrue(sensorRow.findViewById<View>(R.id.btnRemoveSensor).height >= dp(48) - 1)
    }

    @Test fun saveStillWritesSameFormat() {
        val activity = open()
        activity.findViewById<EditText>(R.id.etFontSize).setText("22")
        activity.findViewById<Button>(R.id.btnSave).performClick()
        idle()
        val tile = manager.loadTiles().single()
        assertEquals(22, tile.fontSize)
        assertEquals(1, JSONArray(tile.colorRules).length())
        assertEquals("#8033CC33", JSONArray(tile.colorRules).getJSONObject(0).getString("color_hex"))
        val saved = JSONObject(tile.config)
        assertEquals(listOf("sensor.t"), saved.getJSONArray("collapsed_sensor_configs").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("entity_id") } })
        assertEquals(2, saved.getJSONArray("expanded_sensor_configs").length())
        assertTrue(activity.isFinishing)
    }

    @Test fun rendersLandscape() = render("landscape", 1280, 752)

    @Config(qualifiers = "ru-rRU-w800dp-h1280dp-port-mdpi")
    @Test fun rendersPortrait() = render("portrait", 800, 1232)

    private fun render(name: String, width: Int, height: Int) {
        val activity = open()
        val root = layout(activity, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("build/reports/advanced-settings/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        VisualGolden.verify(file, bitmap)
        bitmap.recycle()
    }
}
