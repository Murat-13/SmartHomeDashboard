package com.example.smarthomedashboard

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Раскрытые карточки вписывают содержимое в свой размер (FitScrollView): на Huawei MatePad SE (2000×1200, 320 dpi)
 * «Часы · Погода · Климат» с прогнозами и тремя комнатами раньше не помещалась и прокручивалась.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-w1000dp-h600dp-land-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ExpandedFitTest {
    private val host by lazy { Robolectric.buildActivity(Activity::class.java).setup().visible().get() }
    private val devices = DashboardDeviceState()

    private fun climate(rooms: Int): HomeClimateSnapshot {
        val list = (1..rooms).map { HomeClimateConfig.Room("r$it", "Комната $it", "bed", "sensor.t$it", "sensor.h$it") }
        val config = HomeClimateConfig(clock = HomeClimateConfig.Clock(use24h = true, seconds = false),
            weather = HomeClimateConfig.Weather("weather.test"), rooms = list, favorites = list.take(3).map { it.id })
        devices.entityChanged("weather.test", "rainy", JSONObject().put("temperature", 3.7).put("temperature_unit", "°C")
            .put("humidity", 97).put("wind_speed", 16.2).put("wind_speed_unit", "km/h").put("pressure", 1021).put("pressure_unit", "hPa"))
        list.forEachIndexed { i, room ->
            devices.entityChanged(room.temperatureEntity, (20.1 + i).toString(), JSONObject().put("unit_of_measurement", "°C"))
            devices.entityChanged(room.humidityEntity, "42", JSONObject().put("unit_of_measurement", "%"))
        }
        val daily = JSONArray(); (0..4).forEach { daily.put(JSONObject().put("datetime", "2099-01-%02dT12:00:00Z".format(it + 1))
            .put("temperature", 5).put("templow", -2).put("condition", "cloudy")) }
        val hourly = JSONArray(); (0..23).forEach { hourly.put(JSONObject().put("datetime", "2099-01-01T%02d:00:00Z".format(it))
            .put("temperature", 3).put("condition", "rainy")) }
        devices.hourlyForecastChanged("weather.test", hourly, true); devices.forecastChanged("weather.test", daily)
        return HomeClimateSnapshot.from("Климат", config, HaSession(true, true), devices)
    }

    private fun layout(view: View, w: Int, h: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    private fun FitScrollView.contentHeight() = getChildAt(0).height
    private fun FitScrollView.fits() = contentHeight() <= height - paddingTop - paddingBottom

    @Test fun climateOnHuaweiFitsWithoutScrolling() {
        val card = HomeClimateExpandedCard(host).apply { bind(climate(3)) }
        host.setContentView(card)
        // Размер раскрытия «Климата» на 2000×1200: ширина min(0,92·2000, 1700), высота 0,92·1200.
        layout(card, 1700, 1104)
        val fit = card.findViewWithTag<FitScrollView>("hc_summary_scroll")
        assertTrue("Содержимое уменьшено, а не обрезано: ${fit.fitScale}", fit.fitScale < 1f)
        assertTrue("Не ниже порога читаемости", fit.fitScale >= FitScrollView.MIN_SCALE)
        assertTrue("Всё влезает: ${fit.contentHeight()} > ${fit.height}", fit.fits())
        assertFalse("Прокручивать нечего", fit.canScrollVertically(1))
        assertEquals(3, card.roomsList.childCount)
        // Снимок для просмотра (не эталон: часы и прогноз зависят от времени).
        val bitmap = android.graphics.Bitmap.createBitmap(1700, 1104, android.graphics.Bitmap.Config.ARGB_8888)
        card.draw(android.graphics.Canvas(bitmap))
        java.io.File("build/reports/expanded-fit").mkdirs()
        java.io.File("build/reports/expanded-fit/climate-huawei.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        card.dispose()
    }

    @Test fun roomyCardKeepsFullSizeAndTinyOneScrollsAtMinimum() {
        val big = HomeClimateExpandedCard(host).apply { bind(climate(2)) }
        host.setContentView(big)
        layout(big, 3000, 2400)
        assertEquals(1f, big.findViewWithTag<FitScrollView>("hc_summary_scroll").fitScale)
        big.dispose()
        val crowded = HomeClimateExpandedCard(host).apply { bind(climate(24)) }
        host.setContentView(crowded)
        layout(crowded, 1700, 1104)
        val fit = crowded.findViewWithTag<FitScrollView>("hc_summary_scroll")
        assertEquals(FitScrollView.MIN_SCALE, fit.fitScale, 0.001f)
        assertTrue("Не влезло и при минимуме — прокрутка", fit.canScrollVertically(1))
        crowded.dispose()
    }

    @Test fun updateKeepsScaleWithoutExtraMeasures() {
        val card = HomeClimateExpandedCard(host).apply { bind(climate(3)) }
        host.setContentView(card)
        layout(card, 1700, 1104)
        val fit = card.findViewWithTag<FitScrollView>("hc_summary_scroll")
        val first = fit.fitScale
        devices.entityChanged("sensor.t1", "22.4", JSONObject().put("unit_of_measurement", "°C"))
        card.bind(HomeClimateSnapshot.from("Климат", card.boundSnapshot!!.config, HaSession(true, true), devices))
        layout(card, 1700, 1104)
        assertEquals(first, fit.fitScale, 0.001f)
        assertTrue(fit.fits())
        card.dispose()
    }

    @Test fun fitScrollViewScalesTallContentAndPassesTouchesToIt() {
        val fit = FitScrollView(host)
        val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        repeat(10) { i -> column.addView(TextView(host).apply { text = "Строка $i"; textSize = 20f },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 100)) }
        fit.addView(column)
        host.setContentView(fit)
        layout(fit, 600, 800) // нужно 1000 — масштаб 0,8
        assertEquals(0.8f, fit.fitScale, 0.01f)
        assertTrue(fit.fits())
        assertSame("Содержимое — внутри вписывающего контейнера", column.parent, fit.getChildAt(0))
        // Ширина раскладки — W / s: строки на всю видимую ширину.
        assertEquals(600f, column.width * column.scaleX, 2f)
        layout(fit, 600, 1200)
        assertEquals(1f, fit.fitScale)
    }
}
