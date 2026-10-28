package com.example.smarthomedashboard

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException

// HA присылает события всего дома: посторонние сохраняются, но не перестраивают панель.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PanelEventFilterTest {
    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).get() }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }

    private fun set(name: String, value: Any?) {
        ArchitectureTestAccess.setField(activity, name, value)
    }
    private fun <T> get(name: String): T = ArchitectureTestAccess.getField(activity, name)
    private fun invoke(name: String, vararg args: Any?): Any? = try {
        ArchitectureTestAccess.methods(activity).single { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private fun sensorTile(id: String, eid: String, colorRules: String = "[]") = TileEntity(
        id, "sensor", "grid", id, 20, 40, 1, 1,
        config = JSONObject().put("collapsed_sensor_configs", org.json.JSONArray().put(
            JSONObject().put("entity_id", eid).put("display_name", "V").put("decimals", 1))).toString(),
        colorRules = colorRules)

    private fun open(vararg tiles: TileEntity) {
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = 1920
        activity.resources.displayMetrics.heightPixels = 1200
        manager.saveTiles(tiles.toList())
        set("tileManager", manager)
        set("bottomPanel", panel)
        set("dimOverlay", View(activity))
        set("yandexWeatherManager", YandexWeatherManager(activity))
        val root = FrameLayout(activity)
        root.addView(panel)
        root.addView(TextView(activity).apply { id = R.id.tvHaLiveStatus })
        activity.window.setContentView(root)
        ArchitectureTestAccess.setField(activity.delegate, "mSubDecorInstalled", true)
        ArchitectureTestAccess.setField(activity.delegate, "mWindow", activity.window)
        invoke("refreshBottomPanel")
    }
    private fun event(eid: String, state: String) = invoke("updateTilesForEntity", eid, state, JSONObject())
    private fun row(tileId: String): TextView =
        panel.findViewWithTag<FrameLayout>(tileId).findViewWithTag<LinearLayout>("sensor_text_container")
            .getChildAt(1) as TextView

    @Test fun unrelatedEventIsStoredWithoutRebuildingPanel() {
        open(sensorTile("s1", "sensor.shown"))
        event("sensor.shown", "21.5")
        val rowBefore = row("s1")
        assertTrue(rowBefore.text.endsWith("21.5"))
        event("sensor.elsewhere", "7")
        assertSame("Посторонний датчик не должен перестраивать карточку", rowBefore, row("s1"))
        assertEquals("7", get<Map<String, String>>("singleStates")["sensor.elsewhere"])
    }

    @Test fun shownEntityStillUpdatesCard() {
        open(sensorTile("s1", "sensor.shown"))
        event("sensor.shown", "21.5")
        event("sensor.shown", "22.0")
        assertTrue(row("s1").text.endsWith("22.0"))
    }

    @Test fun tileAddedLaterShowsAlreadyReceivedValue() {
        open(sensorTile("s1", "sensor.shown"))
        event("sensor.later", "42")
        manager.saveTiles(manager.loadTiles() + sensorTile("s2", "sensor.later").copy(x = 400))
        // Так onActivityResult обновляет панель после настроек плитки.
        invoke("refreshBottomPanel")
        assertTrue(row("s2").text.endsWith("42.0"))
        event("sensor.later", "43")
        assertTrue("Новая плитка сразу получает события", row("s2").text.endsWith("43.0"))
    }

    @Test fun colorRuleEntityRecolorsCard() {
        open(sensorTile("s1", "sensor.shown",
            """[{"entity_id":"sensor.rule","from":"10","to":"","color_hex":"#FF112233"}]"""))
        event("sensor.rule", "15")
        val background = panel.findViewWithTag<FrameLayout>("s1").background as ColorDrawable
        assertEquals("#FF112233".toColorInt(), background.color)
    }

    @Test fun motionSensorStillWakesDimmedScreen() {
        open(sensorTile("s1", "sensor.shown"))
        set("motionSensorId", "binary_sensor.hall")
        invoke("dimScreen")
        assertEquals(0.01f, activity.window.attributes.screenBrightness, 0f)
        event("binary_sensor.hall", "on")
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            activity.window.attributes.screenBrightness, 0f)
    }

    @Test fun groupMemberEventRecolorsGroupButton() {
        open(TileEntity("g", "group", "bottom_panel", "Свет", 20, 900, 1, 1,
            config = """{"entity_ids":["light.a","light.b"]}"""))
        event("light.unrelated", "on")
        val button = panel.findViewWithTag<View>("g")
        assertFalse(invoke("getGroupState", "g") as Boolean)
        event("light.b", "on")
        assertTrue(invoke("getGroupState", "g") as Boolean)
        assertNotNull(button.background)
    }
}
