package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

// Демонстрация набора Modern только в изолированном рендере; приложение его ещё не показывает.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ModernDesignSystemTest {
    private val activity by lazy {
        Robolectric.buildActivity(Activity::class.java).also { it.get().setTheme(R.style.Theme_SmartHomeDashboard_Modern) }.setup().get()
    }
    private fun c(id: Int) = ModernUi.color(activity, id)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    @Test fun paletteMatchesWorkingModernTilesAndEditors() {
        assertEquals(BatteryModernCard.ACCENT, c(R.color.modern_accent))
        assertEquals(BatteryModernCard.BORDER, c(R.color.modern_border))
        assertEquals(BatteryModernCard.GREEN, c(R.color.modern_success))
        assertEquals(Color.rgb(200, 214, 222), c(R.color.modern_text_secondary))
        assertEquals(Color.rgb(120, 140, 150), c(R.color.modern_text_hint))
        assertEquals(Color.rgb(10, 16, 20), c(R.color.modern_background))
        assertEquals(Color.rgb(255, 120, 120), c(R.color.modern_error))
        assertEquals(Color.rgb(255, 205, 120), c(R.color.modern_warning))
    }

    @Test fun interactiveElementsKeepFingerSizedTouchTargets() {
        val views = listOf(
            ModernUi.primaryButton(activity, "Сохранить") {},
            ModernUi.secondaryButton(activity, "Проверить") {},
            ModernUi.dangerButton(activity, "Удалить") {},
            ModernUi.switch(activity, "Использовать датчик движения", true) {},
            ModernUi.input(activity, "", "Адрес"),
            ModernUi.navItem(activity, "Соединения"),
            ModernUi.statusRow(activity, ModernUi.Status.OK, "Home Assistant: подключено"),
        )
        views.forEach { view ->
            view.measure(View.MeasureSpec.makeMeasureSpec(dp(400), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertTrue("${view.javaClass.simpleName} ${view.measuredHeight}", view.measuredHeight >= dp(48))
        }
    }

    @Test fun statusRowUpdatesMessageAndState() {
        val row = ModernUi.statusRow(activity, ModernUi.Status.UNKNOWN, "Нет данных")
        ModernUi.setStatus(row, ModernUi.Status.ERROR, "Нет связи")
        assertEquals(ModernUi.Status.ERROR, row.tag)
        assertEquals("Нет связи", (row.getChildAt(1) as android.widget.TextView).text.toString())
    }

    @Test fun leaveDialogOffersSaveDiscardAndContinue() {
        var saved = 0
        var discarded = 0
        val dialog = ModernUi.leaveDialog(activity, null, { saved++ }, { discarded++ })
        dialog.show()
        assertEquals("Сохранить", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertEquals("Не сохранять", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text)
        assertEquals("Продолжить редактирование", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, saved)
        assertEquals(0, discarded)
        assertNotNull(ShadowDialog.getLatestDialog())
    }

    @Test fun dangerDialogRunsActionOnlyOnConfirm() {
        var deleted = 0
        val dialog = ModernUi.dangerDialog(activity, "Удалить плитку «Кухня»?", null, "Удалить") { deleted++ }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, deleted)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, deleted)
        assertEquals(c(R.color.modern_danger), dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor)
    }

    @Test fun motionUsesSystemDurations() {
        assertEquals(activity.resources.getInteger(android.R.integer.config_shortAnimTime).toLong(), ModernUi.Motion.short(activity))
        assertTrue(ModernUi.Motion.medium(activity) > ModernUi.Motion.short(activity))
    }

    // Образец набора в build/reports/modern-design-system: обычный и крупный шрифт.
    @Test fun galleryRendersAtNormalAndLargeFont() {
        draw(gallery(activity), 1280, 800, "gallery")
        val large = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { fontScale = 1.5f })
        val themed = android.view.ContextThemeWrapper(large, R.style.Theme_SmartHomeDashboard_Modern)
        draw(gallery(themed), 1280, 800, "gallery-font-1.5")
    }

    private fun gallery(context: android.content.Context): View {
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            listOf("Соединения", "Экран", "Безопасность", "Устройство", "О приложении").forEachIndexed { i, name ->
                addView(ModernUi.navItem(context, name).apply { isSelected = i == 0 },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ModernUi.secondaryButton(context, "Проверить") {})
            addView(View(context), LinearLayout.LayoutParams(dp(12), 1))
            addView(ModernUi.primaryButton(context, "Проверить и сохранить") {})
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            addView(ModernUi.header(context, "Соединения"))
            addView(ModernUi.section(context, "Home Assistant",
                ModernUi.statusRow(context, ModernUi.Status.OK, "Подключено · локальный адрес"),
                ModernUi.caption(context, "Локальный адрес"),
                ModernUi.input(context, "", "http://адрес:8123"),
                ModernUi.caption(context, "Токен"),
                ModernUi.input(context, "", "Токен не изменён", password = true),
                buttons), spaced())
            addView(ModernUi.section(context, "Экран",
                ModernUi.switch(context, "Использовать датчик движения", true) {},
                ModernUi.statusRow(context, ModernUi.Status.WARNING, "Яндекс Погода: отказ сервера (403)"),
                ModernUi.statusRow(context, ModernUi.Status.UNKNOWN, "Последний бэкап: ещё не создавался"),
                ModernUi.secondary(context, "Затемнение до 1% после 5 минут без движения"),
                ModernUi.dangerButton(context, "Удалить плитку") {}), spaced())
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ModernUi.color(context, R.color.modern_background))
            addView(nav, LinearLayout.LayoutParams(context.resources.getDimensionPixelSize(R.dimen.modern_nav_width),
                ViewGroup.LayoutParams.MATCH_PARENT))
            addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun spaced() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(16) }

    private fun draw(view: View, width: Int, height: Int, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val path = File("build/reports/modern-design-system/$name.png")
        path.parentFile!!.mkdirs()
        path.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        VisualGolden.verify(path, bitmap)
        bitmap.recycle()
    }
}
