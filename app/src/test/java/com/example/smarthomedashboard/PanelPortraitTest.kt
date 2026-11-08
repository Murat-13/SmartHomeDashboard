package com.example.smarthomedashboard

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * Панель в вертикальном положении (0.9.19, docs/adaptive-layout.md): настоящие MainActivity и контроллер панели.
 * Автоматическая раскладка без наложений, своя раскладка после правки, сброс, экраны-продолжения; горизонтальные
 * x/y не меняются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PanelPortraitTest {
    // Новый экран на каждый размер — как при повороте (контроллер запоминает размер окна при создании).
    private lateinit var activity: MainActivity
    private lateinit var manager: TileManager
    private lateinit var panel: FrameLayout
    private val controller get() = MainActivity::class.java.getDeclaredMethod("getPanelController")
        .apply { isAccessible = true }.invoke(activity) as DashboardPanelController

    /** Горизонтальная раскладка Huawei: ряд виджетов сверху и ряд из десяти кнопок внизу. */
    private fun ownerTiles(extraWidgets: Int = 0) = listOf(
        TileEntity("s1", "sensor", "grid", "Сеть", 20, 20, 1, 1, config = """{"button_size":300,"widget_height":260}"""),
        TileEntity("s2", "sensor", "grid", "Инвертор", 340, 20, 1, 1, config = """{"button_size":300,"widget_height":260}"""),
        TileEntity("s3", "sensor", "grid", "АКБ", 660, 20, 1, 1, config = """{"button_size":560,"widget_height":260}"""),
        TileEntity("s4", "sensor", "grid", "Климат", 1240, 20, 1, 1, config = """{"button_size":640,"widget_height":320}"""),
    ) + (0 until extraWidgets).map {
        TileEntity("x$it", "sensor", "grid", "Доп $it", 20 + (it % 5) * 380, 360 + (it / 5) * 300, 1, 1,
            config = """{"button_size":360,"widget_height":280}""")
    } + (0 until 10).map {
        TileEntity("b$it", "button", "bottom_panel", "Кнопка $it", 20 + it * 180, 980, 1, 1, config = """{"button_size":160}""")
    }

    private fun open(width: Int, height: Int, tiles: List<TileEntity>) {
        activity = Robolectric.buildActivity(MainActivity::class.java).get()
        manager = TileManager(activity)
        panel = FrameLayout(activity)
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = width
        activity.resources.displayMetrics.heightPixels = height
        manager.saveTiles(tiles)
        ReflectionHelpers.setField(activity, "tileManager", manager)
        ReflectionHelpers.setField(activity, "bottomPanel", panel)
        ReflectionHelpers.setField(activity, "dimOverlay", View(activity).apply { visibility = View.GONE })
        ReflectionHelpers.setField(activity, "yandexWeatherManager", YandexWeatherManager(activity))
        val root = FrameLayout(activity)
        root.addView(panel)
        activity.window.setContentView(root)
        ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
        ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
        refresh()
    }

    private fun refresh() {
        controller.refreshBottomPanel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private data class Box(val id: String, val x: Int, val y: Int, val w: Int, val h: Int)

    private fun boxes() = (0 until panel.childCount).map { panel.getChildAt(it) }.filter { it.tag is String }.map {
        Box(it.tag as String, it.x.toInt(), it.y.toInt(), it.layoutParams.width, it.layoutParams.height)
    }

    private fun assertClean(width: Int, height: Int) {
        val all = boxes()
        assertTrue(all.isNotEmpty())
        all.forEach { assertTrue("Внутри экрана: $it", it.x >= 0 && it.y >= 0 && it.x + it.w <= width && it.y + it.h <= height) }
        for (i in all.indices) for (j in i + 1 until all.size) {
            val a = all[i]
            val b = all[j]
            assertFalse("Наложение $a / $b", a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h)
        }
    }

    private fun tile(id: String) = manager.loadTiles().first { it.id == id }

    @Test fun landscapeKeepsSavedPlaces() {
        open(2000, 1200, ownerTiles())
        val s3 = boxes().first { it.id == "s3" }
        assertEquals(660, s3.x)
        assertEquals(20, s3.y)
        assertEquals(1, controller.screenCount())
    }

    @Test fun portraitBuildsRowsWithoutOverlapsAndKeepsData() {
        val before = ownerTiles()
        open(1200, 2000, before)
        assertClean(1200, 2000)
        assertEquals(14, boxes().size)
        val buttons = boxes().filter { it.id.startsWith("b") }
        val bottom = buttons.maxOf { it.y }
        assertTrue("Нижний ряд кнопок у края", bottom + 160 >= 2000 - 60 - 1)
        assertEquals("Первые кнопки — в нижнем ряду", "b0", buttons.filter { it.y == bottom }.minBy { it.x }.id)
        // Данные плиток не тронуты.
        assertEquals(before, manager.loadTiles())
        assertFalse(controller.hasCustomPortrait())
    }

    @Test fun firstPortraitEditFreezesWholePageAndResetReturnsAuto() {
        open(1200, 2000, ownerTiles())
        val auto = boxes()
        val method = DashboardPanelController::class.java.getDeclaredMethod("writePosition",
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        method.invoke(controller, "s1", 700, 900)
        refresh()
        assertTrue(controller.hasCustomPortrait())
        assertEquals("Сдвинутая плитка — на новом месте", 700, boxes().first { it.id == "s1" }.x)
        // Остальные — там же, где были в автоматической раскладке.
        auto.filter { it.id != "s1" }.forEach { assertEquals(it, boxes().first { b -> b.id == it.id }) }
        // Горизонтальные места не изменились, у всех плиток страницы — своё место в портрете.
        assertEquals(20, tile("s1").x)
        assertEquals(20, tile("s1").y)
        assertTrue(manager.loadTiles().all { it.portrait.isNotEmpty() })
        controller.resetPortraitLayout()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.hasCustomPortrait())
        assertEquals(auto.sortedBy { it.id }, boxes().sortedBy { it.id })
        assertTrue(manager.loadTiles().all { it.portrait.isEmpty() })
    }

    @Test fun tooManyWidgetsContinueOnNextScreenWithButtonsEverywhere() {
        open(1200, 2000, ownerTiles(extraWidgets = 30))
        assertTrue("Есть продолжение: ${controller.screenCount()}", controller.screenCount() > 1)
        assertClean(1200, 2000)
        val first = boxes().map { it.id }.toSet()
        controller.showScreen(1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, controller.currentPage)
        assertClean(1200, 2000)
        val second = boxes().map { it.id }.toSet()
        assertTrue("Кнопки на обоих экранах", (0 until 10).all { "b$it" in first && "b$it" in second })
        assertTrue("Виджеты не повторяются", (first intersect second).all { it.startsWith("b") })
        assertEquals("Все плитки показаны", 4 + 30 + 10, (first + second).size)
    }

    @Test fun phoneSizesNeverOverlap() {
        for ((w, h) in listOf(1080 to 2400, 720 to 1280, 1600 to 2560)) {
            open(w, h, ownerTiles(extraWidgets = 6))
            repeat(controller.screenCount()) { screen ->
                controller.showScreen(screen)
                shadowOf(Looper.getMainLooper()).idle()
                assertClean(w, h)
            }
            controller.showPage(0)
        }
    }
}
