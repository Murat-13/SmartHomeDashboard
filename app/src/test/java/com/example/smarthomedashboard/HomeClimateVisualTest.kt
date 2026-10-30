package com.example.smarthomedashboard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeClimateVisualTest {
    private val host by lazy { Robolectric.buildActivity(Activity::class.java).setup().visible().get() }
    private val devices = DashboardDeviceState()
    private fun snapshot(count: Int = 24): HomeClimateSnapshot {
        val rooms = (1..count).map { HomeClimateConfig.Room("r$it", "Комната $it", "bed", "sensor.t$it", "sensor.h$it") }
        val config = HomeClimateConfig(clock = HomeClimateConfig.Clock(use24h = true, seconds = true),
            weather = HomeClimateConfig.Weather("weather.test"), rooms = rooms, favorites = rooms.take(5).map { it.id })
        devices.entityChanged("weather.test", "cloudy", JSONObject().put("temperature", 13).put("temperature_unit", "°C")
            .put("humidity", 72).put("wind_speed", 4).put("wind_speed_unit", "m/s").put("pressure", 1012).put("pressure_unit", "hPa"))
        rooms.forEachIndexed { i, room ->
            devices.entityChanged(room.temperatureEntity, (14.2 + i).toString(), JSONObject().put("unit_of_measurement", "°C"))
            devices.entityChanged(room.humidityEntity, "48", JSONObject().put("unit_of_measurement", "%"))
        }
        val forecast = JSONArray()
        (0..23).forEach { forecast.put(JSONObject().put("datetime", "2099-01-%02dT12:00:00Z".format(it + 1)).put("temperature", 13)
            .put("templow", 8).put("condition", "rainy")) }
        val hourly = JSONArray()
        (0..23).forEach { hourly.put(JSONObject().put("datetime", "2099-01-01T%02d:00:00Z".format(it)).put("temperature", 13)
            .put("condition", "rainy")) }
        devices.hourlyForecastChanged("weather.test", hourly, true); devices.forecastChanged("weather.test", forecast)
        return HomeClimateSnapshot.from("Test", config, HaSession(true, true), devices)
    }
    private fun draw(view: View, width: Int, height: Int, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val path = File("build/reports/home-climate-visual/$name.png"); path.parentFile!!.mkdirs()
        path.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    @Test fun compactZeroToFiveAndLargeFontRenderInsideTwoToOneBounds() {
        val all = snapshot()
        val gallery = android.widget.LinearLayout(host).apply { orientation = android.widget.LinearLayout.VERTICAL }
        for (n in 0..5) gallery.addView(HomeClimateCompactView(host).apply {
            bind(all.copy(favorites = all.favorites.take(n)))
        }, android.widget.LinearLayout.LayoutParams(640, 320))
        draw(gallery, 640, 1920, "compact-0-to-5")
        val configuration = android.content.res.Configuration(host.resources.configuration).apply { fontScale = 1.5f }
        val large = host.createConfigurationContext(configuration)
        draw(HomeClimateCompactView(large).apply { bind(all) }, 360, 180, "compact-small-large-font")
    }
    @Test fun expandedSmallScreensKeepRoomsVisibleAndScrollPositionsOnUpdate() {
        val snap = snapshot()
        for ((width, height) in listOf(1100 to 800, 360 to 680, 740 to 330)) {
            var clockMillis = System.currentTimeMillis()
            val card = HomeClimateExpandedCard(host, { java.util.Date(clockMillis) }).apply { bind(snap) }
            host.setContentView(card)
            draw(card, width, height, "expanded-$width-$height")
            assertTrue("Rooms must remain visible at $width x $height", card.roomsList.height > 40)
            assertEquals(24, card.roomsList.adapter!!.itemCount)
            card.hourlyScroll.scrollTo(140, 0)
            card.roomsList.scrollBy(0, 90)
            val scrollX = card.hourlyScroll.scrollX
            val roomScroll = card.roomsList.computeVerticalScrollOffset()
            devices.entityChanged("sensor.t1", "21.5", JSONObject().put("unit_of_measurement", "°C"))
            val next = HomeClimateSnapshot.from("Test", snap.config, HaSession(true, true), devices)
            card.bind(next)
            assertEquals(scrollX, card.hourlyScroll.scrollX)
            assertEquals(roomScroll, card.roomsList.computeVerticalScrollOffset())
            val time = card.findViewWithTag<TextView>("hc_time")
            val previous = time.text.toString()
            clockMillis += 2_000
            shadowOf(Looper.getMainLooper()).idleFor(1100, TimeUnit.MILLISECONDS)
            assertNotEquals("Clock should tick after attached bind", previous, time.text.toString())
            card.dispose()
        }
    }
    @Test fun editorKeepsFieldsAndSaveButtonReachableOnSmallScreens() {
        val context = host.applicationContext
        com.example.smarthomedashboard.data.TileManager(context).saveTiles(listOf(
            com.example.smarthomedashboard.data.TileEntity("visual", HomeClimateConfig.TYPE, "grid", "Тест", 0, 0, 1, 1,
                config = snapshot().config.writeTo(HomeClimateConfig.newTileConfig()))))
        TileEditorPreviewSource.attach(MainViewModel())
        for ((w, h) in listOf(360 to 680, 740 to 330)) {
            val controller = Robolectric.buildActivity(HomeClimateEditorActivity::class.java,
                android.content.Intent(context, HomeClimateEditorActivity::class.java).putExtra("tile_id", "visual"))
            controller.get().setTheme(R.style.Theme_SmartHomeDashboard)
            val editor = controller.create().start().get()
            editor.showSection(HomeClimateEditorActivity.SECTION_ROOMS)
            val root = editor.findViewById<FrameLayout>(android.R.id.content).getChildAt(0)
            draw(root, w, h, "editor-$w-$h")
            val fields = root.findViewWithTag<View>("hc_section").parent as View
            assertTrue("Settings area remains usable", fields.height >= 80)
            val save = root.findViewWithTag<View>("hc_save")
            assertTrue(save.width > 0)
            assertTrue(save.right <= w)
            controller.stop().destroy()
        }
    }

}
