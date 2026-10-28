package com.example.smarthomedashboard

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Настоящие activity_main.xml и activity_tile_settings.xml в теме приложения на экране планшета
 * (1200×1920, 240dpi). Ловит ошибку, при которой длинный текст забирал всю ширину плашки,
 * а крестик сжимался до нуля.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w800dp-h1280dp-port-hdpi")
// Нативная графика: текст измеряется и переносится как на устройстве.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelHintLayoutTest {
    private val screenW = 1200
    private val screenH = 1920

    private fun inflate(layout: Int): View {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        activity.setTheme(R.style.Theme_SmartHomeDashboard)
        return activity.layoutInflater.inflate(layout, null).also { root ->
            root.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, screenW, screenH)
        }
    }

    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, screenW, screenH)
    }

    private fun boundsIn(root: View, view: View): Rect {
        val rect = Rect(0, 0, view.width, view.height)
        (root as ViewGroup).offsetDescendantRectToMyCoords(view, rect)
        return rect
    }

    private fun assertCloseFullyVisible(root: View, container: View, text: TextView, close: View) {
        val density = root.resources.displayMetrics.density
        assertEquals("Крестик 48dp по ширине", (48 * density).toInt(), close.width)
        assertEquals("Крестик 48dp по высоте", (48 * density).toInt(), close.height)
        val box = boundsIn(root, container)
        val button = boundsIn(root, close)
        assertTrue("Крестик внутри плашки: $button в $box", box.contains(button))
        assertTrue("Текст не заходит на крестик", boundsIn(root, text).right <= button.left)
        assertTrue("Длинный текст переносится, а не растягивает плашку", text.lineCount > 1)
        assertTrue("Крестик в правом верхнем углу", button.top - box.top < (12 * density))
    }

    @Test fun panelHintKeepsCloseButtonWithLongEditModeText() {
        val root = inflate(R.layout.activity_main)
        val container = root.findViewById<View>(R.id.panelHint)
        val text = root.findViewById<TextView>(R.id.tvPanelHint)
        container.visibility = View.VISIBLE
        text.text = UserHints.EDIT_MODE
        relayout(root)

        assertCloseFullyVisible(root, container, text, root.findViewById(R.id.btnPanelHintClose))
        val density = root.resources.displayMetrics.density
        // По ширине экрана с отступами 24dp, но не шире 720dp, по центру.
        val expected = minOf(screenW - (48 * density).toInt(), (720 * density).toInt())
        assertEquals(expected, container.width)
        val box = boundsIn(root, container)
        assertEquals("Плашка по центру", screenW - box.right, box.left)
    }

    @Test fun panelHintFillsNarrowScreenWithinMargins() {
        val root = inflate(R.layout.activity_main)
        val container = root.findViewById<View>(R.id.panelHint)
        container.visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.tvPanelHint).text = UserHints.EDIT_MODE
        // Телефон: 360dp при 240dpi. Плашка уже экрана, крестик всё равно виден.
        root.measure(View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 540, 1100)
        val density = root.resources.displayMetrics.density
        assertEquals(540 - (48 * density).toInt(), container.width)
        assertCloseFullyVisible(root, container, root.findViewById(R.id.tvPanelHint),
            root.findViewById(R.id.btnPanelHintClose))
    }

    @Test fun tileTypeHintKeepsCloseButtonForEveryType() {
        val root = inflate(R.layout.activity_tile_settings)
        val container = root.findViewById<View>(R.id.tileTypeHint)
        val text = root.findViewById<TextView>(R.id.tvTileTypeHint)
        for (type in listOf("sensor", "button", "group", "battery", "clock", "weather")) {
            text.text = UserHints.tileType(type)
            relayout(root)
            val close = root.findViewById<View>(R.id.btnTileTypeHintClose)
            val density = root.resources.displayMetrics.density
            assertEquals(type, (48 * density).toInt(), close.width)
            assertTrue(type, boundsIn(root, container).contains(boundsIn(root, close)))
            assertTrue(type, boundsIn(root, text).right <= boundsIn(root, close).left)
        }
    }

    @Test fun hintStandsAboveBottomButtonRow() {
        val gap = 12
        // Кнопки внизу (верх на 1600 и 1640) и плитка датчика сверху — она не нижний ряд.
        val buttons = listOf(1600f to 240, 1640f to 240, 100f to 240)
        val margin = TilePlacement.hintBottomMargin(buttons, screenH, gap, minMargin = 18)
        assertEquals(screenH - 1600 + gap, margin)
        // Без кнопок внизу — обычный отступ от края.
        assertEquals(18, TilePlacement.hintBottomMargin(listOf(100f to 240), screenH, gap, 18))
        assertEquals(18, TilePlacement.hintBottomMargin(emptyList(), screenH, gap, 18))
        // Даже при странной раскладке плашка не уезжает выше середины экрана.
        assertEquals(screenH / 2, TilePlacement.hintBottomMargin(listOf(900f to 400), screenH, gap, 18))
    }
}
