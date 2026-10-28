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
    private val hintText by lazy { TextView(activity).apply { id = R.id.tvPanelHint } }
    private val hintClose by lazy { android.widget.ImageButton(activity).apply { id = R.id.btnPanelHintClose } }
    // Та же структура, что в activity_main.xml: текст и крестик в одной плашке.
    private val hint by lazy {
        android.widget.LinearLayout(activity).apply {
            id = R.id.panelHint
            visibility = View.GONE
            addView(hintText)
            addView(hintClose)
        }
    }
    private val prefs get() = activity.getSharedPreferences("dashboard_prefs", 0)

    private fun set(name: String, value: Any?) {
        ArchitectureTestAccess.setField(activity, name, value)
    }
    private fun invoke(name: String): Any? = try {
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private val notices = mutableListOf<Pair<String, () -> Unit>>()
    private val noticeAnchors = mutableListOf<View?>()
    private val defaultNotice = UserHints.undoNotice

    @org.junit.Before fun recordUndoNotices() {
        UserHints.undoNotice = { _, message, above, undo ->
            notices += message to undo
            noticeAnchors += above
        }
    }

    @org.junit.After fun restoreUndoNotices() {
        UserHints.undoNotice = defaultNotice
    }

    private fun attachPanel() {
        set("tileManager", manager)
        set("bottomPanel", panel)
        set("dimOverlay", View(activity))
        MainActivity::class.java.getDeclaredMethod("setupPanelHint", View::class.java, TextView::class.java,
            View::class.java).apply { isAccessible = true }.invoke(activity, hint, hintText, hintClose)
        set("yandexWeatherManager", YandexWeatherManager(activity))
        val root = FrameLayout(activity)
        root.addView(panel)
        root.addView(hint)
        root.addView(TextView(activity).apply { id = R.id.tvHaLiveStatus })
        activity.window.setContentView(root)
        ArchitectureTestAccess.setField(activity.delegate, "mSubDecorInstalled", true)
        ArchitectureTestAccess.setField(activity.delegate, "mWindow", activity.window)
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
        assertEquals(UserHints.HOW_TO_EDIT, hintText.text.toString())

        set("pinAccess", null)
        invoke("enterEditMode")
        assertEquals(View.VISIBLE, hint.visibility)
        assertEquals(UserHints.EDIT_MODE, hintText.text.toString())

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

    @Test fun closeButtonHidesOnlyTheShownHintAndItStaysHidden() {
        attachPanel()
        invoke("createStarterTilesOnFirstLaunch")
        invoke("refreshBottomPanel")
        hintClose.performClick()
        assertEquals(View.GONE, hint.visibility)
        assertTrue(UserHints.isHidden(activity, UserHints.Hint.HOW_TO_EDIT))
        assertEquals(UserHints.HIDDEN_NOTICE, notices.single().first)
        invoke("refreshBottomPanel")
        assertEquals("Скрытая подсказка не возвращается сама", View.GONE, hint.visibility)

        // Пояснение режима редактирования — отдельная подсказка, свой крестик.
        set("pinAccess", null)
        invoke("enterEditMode")
        assertEquals(View.VISIBLE, hint.visibility)
        assertEquals(UserHints.EDIT_MODE, hintText.text.toString())
        hintClose.performClick()
        assertEquals(View.GONE, hint.visibility)
        assertTrue(UserHints.isHidden(activity, UserHints.Hint.EDIT_MODE))
        invoke("exitEditMode")
        invoke("enterEditMode")
        assertEquals(View.GONE, hint.visibility)
    }

    @Test fun hintIsPlacedAboveBottomButtonsInNormalAndEditMode() {
        attachPanel()
        UserHints.restoreAll(activity)
        hint.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT)
        manager.saveTiles(listOf(TileEntity("b", "button", "bottom_panel", "Свет", 20, 980, 1, 1,
            config = """{"entity_id":"switch.kitchen"}""")))
        val density = activity.resources.displayMetrics.density
        val gap = (8 * density).toInt()
        fun margin() = (hint.layoutParams as FrameLayout.LayoutParams).bottomMargin

        invoke("refreshBottomPanel")
        assertEquals(View.VISIBLE, hint.visibility)
        // Кнопка стоит на y=980 при высоте экрана 1200: плашка заканчивается на 8dp выше.
        assertEquals(1200 - 980 + gap, margin())

        set("pinAccess", null)
        invoke("enterEditMode")
        // В редактировании выше всех кнопки «+» и «⚙»: верх на 1200 − 160 − 80 = 960.
        assertEquals(UserHints.EDIT_MODE, hintText.text.toString())
        assertEquals(1200 - 960 + gap, margin())

        // Snackbar «Отменить» показывается над самой высокой кнопкой нижнего ряда («+»/«⚙»).
        hintClose.performClick()
        val above = noticeAnchors.single() as android.widget.Button
        assertEquals(960f, above.y, 0f)
        assertTrue(above.text.toString() in listOf("+", "⚙"))
    }

    @Test fun undoRightAfterClosingBringsHintBack() {
        attachPanel()
        invoke("createStarterTilesOnFirstLaunch")
        invoke("refreshBottomPanel")
        hintClose.performClick()
        assertEquals(View.GONE, hint.visibility)
        // Кнопка «Отменить» в Snackbar на случай случайного нажатия крестика.
        notices.single().second()
        assertEquals(View.VISIBLE, hint.visibility)
        assertEquals(UserHints.HOW_TO_EDIT, hintText.text.toString())
        assertFalse(UserHints.isHidden(activity, UserHints.Hint.HOW_TO_EDIT))
    }

    @Test fun restoreFromSettingsBringsBackHintsIncludingHowToEdit() {
        attachPanel()
        invoke("createStarterTilesOnFirstLaunch")
        set("pinAccess", null)
        invoke("enterEditMode")
        hintClose.performClick()
        invoke("exitEditMode")
        UserHints.setHidden(activity, UserHints.Hint.TILE_TYPE, true)
        assertEquals(View.GONE, hint.visibility)
        assertTrue(UserHints.canRestore(activity))

        UserHints.restoreAll(activity)
        assertFalse(UserHints.canRestore(activity))
        UserHints.Hint.entries.forEach { assertFalse(it.name, UserHints.isHidden(activity, it)) }
        // Так MainActivity.onResume возвращает панель после настроек.
        invoke("refreshBottomPanel")
        assertEquals(View.VISIBLE, hint.visibility)
        assertEquals(UserHints.HOW_TO_EDIT, hintText.text.toString())
    }

    @Test fun existingUserCanBringBackBeginnerHints() {
        attachPanel()
        manager.saveTiles(listOf(TileEntity("mine", "clock", "grid", "Часы", 20, 40, 1, 1)))
        invoke("createStarterTilesOnFirstLaunch")
        // На существующей панели подсказка новичка скрыта, но её можно вернуть.
        assertTrue(UserHints.canRestore(activity))
        UserHints.restoreAll(activity)
        invoke("refreshBottomPanel")
        assertEquals(View.VISIBLE, hint.visibility)
    }

    @Test fun everyTileTypeHasDescription() {
        for (type in listOf("sensor", "button", "group", "battery", "clock", "weather")) {
            assertTrue(type, UserHints.tileType(type).isNotBlank())
        }
    }
}
