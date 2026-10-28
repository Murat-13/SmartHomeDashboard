package com.example.smarthomedashboard

import android.app.AlertDialog
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException

// Первый запуск: стартовые плитки, подсказки и приветствие. XML/onCreate не исполняются.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingTest {
    private val activity by lazy {
        Robolectric.buildActivity(MainActivity::class.java).get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            resources.displayMetrics.widthPixels = 1920
            resources.displayMetrics.heightPixels = 1200
        }
    }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val hint by lazy { TextView(activity).apply { id = R.id.tvPanelHint; visibility = View.GONE } }
    private val prefs get() = activity.getSharedPreferences("dashboard_prefs", 0)

    private fun set(name: String, value: Any?) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true; set(activity, value) }
    }
    private fun invoke(name: String): Any? = try {
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private fun attachPanel() {
        set("tileManager", manager)
        set("bottomPanel", panel)
        set("dimOverlay", View(activity))
        set("panelHint", hint)
        set("yandexWeatherManager", YandexWeatherManager(activity))
        val root = FrameLayout(activity)
        root.addView(panel)
        root.addView(hint)
        root.addView(TextView(activity).apply { id = R.id.tvHaLiveStatus })
        activity.window.setContentView(root)
        ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
        ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
    }

    @Test fun cleanInstallGetsOnlyClockAndWeatherWithoutPersonalDevices() {
        attachPanel()
        invoke("createStarterTilesOnFirstLaunch")
        val tiles = manager.loadTiles()
        assertEquals(listOf("clock", "weather"), tiles.map { it.type })
        assertTrue(tiles.all { it.container == "grid" && it.enabled })
        assertTrue("Стартовые плитки без устройств", tiles.all { it.config == "{}" && it.colorRules == "[]" })
        val (a, b) = tiles
        assertFalse("Плитки не перекрываются", a.x < b.x + 220 && b.x < a.x + 220 && a.y < b.y + 180 && b.y < a.y + 180)
    }

    @Test fun deletedStarterTilesAreNotRecreated() {
        attachPanel()
        invoke("createStarterTilesOnFirstLaunch")
        manager.saveTiles(emptyList())
        invoke("createStarterTilesOnFirstLaunch")
        assertTrue(manager.loadTiles().isEmpty())
    }

    @Test fun panelWithoutButtonsStaysWithoutButtons() {
        attachPanel()
        manager.saveTiles(listOf(TileEntity("c", "clock", "grid", "Часы", 20, 40, 1, 1)))
        invoke("refreshBottomPanel")
        // Раньше при отсутствии кнопок каждый раз создавались «Свет» и «Бойлер» с личными entity_id.
        assertEquals(listOf("c"), manager.loadTiles().map { it.id })
    }

    @Test fun existingInstallKeepsItsTilesAndSkipsBeginnerHints() {
        attachPanel()
        val own = TileEntity("mine", "button", "bottom_panel", "Свет кухня", 20, 900, 1, 1,
            config = """{"entity_id":"switch.kitchen"}""")
        manager.saveTiles(listOf(own))
        invoke("createStarterTilesOnFirstLaunch")
        assertEquals(listOf(own), manager.loadTiles())
        invoke("refreshBottomPanel")
        assertEquals(View.GONE, hint.visibility)
        invoke("showWelcomeOnFirstLaunch")
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test fun beginnerHintGuidesIntoEditModeAndThenExplainsIt() {
        attachPanel()
        invoke("createStarterTilesOnFirstLaunch")
        invoke("refreshBottomPanel")
        assertEquals(View.VISIBLE, hint.visibility)
        assertEquals(UserHints.HOW_TO_EDIT, hint.text.toString())

        set("pinAccess", null)
        invoke("enterEditMode")
        assertEquals(View.VISIBLE, hint.visibility)
        assertEquals(UserHints.EDIT_MODE, hint.text.toString())

        invoke("exitEditMode")
        assertEquals("После первого входа подсказка больше не нужна", View.GONE, hint.visibility)
        assertTrue(prefs.getBoolean("edit_mode_found", false))
    }

    @Test fun welcomeIsShownOnceOnCleanInstall() {
        attachPanel()
        invoke("createStarterTilesOnFirstLaunch")
        invoke("showWelcomeOnFirstLaunch")
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals(UserHints.WELCOME, org.robolectric.Shadows.shadowOf(dialog).message.toString())
        dialog.dismiss()
        ShadowAlertDialog.reset()
        invoke("showWelcomeOnFirstLaunch")
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test fun everyTileTypeHasDescription() {
        for (type in listOf("sensor", "button", "group", "battery", "clock", "weather")) {
            assertTrue(type, UserHints.tileType(type).isNotBlank())
        }
    }
}
