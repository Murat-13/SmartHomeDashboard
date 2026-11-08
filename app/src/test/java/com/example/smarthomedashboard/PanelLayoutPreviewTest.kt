package com.example.smarthomedashboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * Снимки панели для просмотра (не эталоны): раскладка, похожая на Huawei владельца, на нескольких экранах в обеих
 * ориентациях. снимки в build/reports/adaptive — данные HA не подключены, плитки показывают «—».
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PanelLayoutPreviewTest {
    private fun tiles(): List<TileEntity> {
        val size = { w: Int, h: Int -> """{"button_size":$w,"widget_height":$h}""" }
        return listOf(
            TileEntity("grid", PowerModernConfig.TYPE, "grid", "Сеть · Вход", 20, 20, 1, 1,
                config = PowerModernConfig.newTileConfig(PowerModernConfig.PROFILE_GRID, size(270, 330))),
            TileEntity("inv", InverterModernConfig.TYPE, "grid", "Инвертор", 305, 20, 1, 1,
                config = InverterModernConfig.newTileConfig(size(270, 330))),
            TileEntity("bms", BatteryModernConfig.TYPE, "grid", "АКБ", 590, 20, 1, 1,
                config = BatteryModernConfig.newTileConfig(size(560, 330))),
            TileEntity("climate", HomeClimateConfig.TYPE, "grid", "Климат", 1170, 20, 1, 1,
                config = HomeClimateConfig.newTileConfig(size(660, 330))),
        ) + listOf("Свет кухня", "Свет коридор", "Свет крыльцо", "Бойлер", "Насос", "Кондиционер", "Свет сад", "Ворота", "Полив")
            .mapIndexed { i, title ->
                TileEntity("b$i", "button", "bottom_panel", title, 20 + i * 175, 960, 1, 1, config = """{"button_size":160}""")
            }
    }

    private fun shot(name: String, width: Int, height: Int) {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = width
        activity.resources.displayMetrics.heightPixels = height
        val manager = TileManager(activity).apply { saveTiles(tiles()) }
        val panel = FrameLayout(activity).apply { setBackgroundColor(0xFF06101F.toInt()) }
        ReflectionHelpers.setField(activity, "tileManager", manager)
        ReflectionHelpers.setField(activity, "bottomPanel", panel)
        ReflectionHelpers.setField(activity, "dimOverlay", View(activity).apply { visibility = View.GONE })
        ReflectionHelpers.setField(activity, "yandexWeatherManager", YandexWeatherManager(activity))
        activity.window.setContentView(FrameLayout(activity).apply { addView(panel) })
        ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
        ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
        val controller = MainActivity::class.java.getDeclaredMethod("getPanelController")
            .apply { isAccessible = true }.invoke(activity) as DashboardPanelController
        controller.refreshBottomPanel()
        shadowOf(Looper.getMainLooper()).idle()
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        panel.draw(Canvas(bitmap))
        val file = File("build/reports/adaptive/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun huawei() { shot("huawei-land", 2000, 1200); shot("huawei-port", 1200, 2000) }
    @Test fun phone() { shot("phone-land", 2400, 1080); shot("phone-port", 1080, 2400) }
    @Test fun bigTablet() { shot("big-tablet-port", 1600, 2560) }
}
