package com.example.smarthomedashboard

import android.view.View
import android.widget.FrameLayout
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 0.9.5 — меньше бесполезной работы при 24/7: настройки плиток разбираются один раз на версию,
 * плитки других страниц не пересобирают кэш карточек, форматтеры часов переиспользуются без смены результата.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class RenderEfficiencyTest {
    private val locale = Locale.getDefault()
    @After fun restoreLocale() = Locale.setDefault(locale)

    private fun tile(id: String, config: String, page: Int = 0) =
        TileEntity(id, "sensor", "grid", id, 20, 40, 1, 1, config = config, page = page)

    @Test fun tileConfigIsParsedOncePerVersion() {
        var parses = 0
        val cache = ParsedTileConfig { config -> parses++; JSONObject(config).optString("entity_id") }
        val first = tile("a", """{"entity_id":"sensor.one"}""")
        repeat(100) { assertEquals("sensor.one", cache.of(first)) }
        assertEquals(1, parses)
        // Та же строка в новом экземпляре плитки (перечитана из хранилища) — без повторного разбора.
        assertEquals("sensor.one", cache.of(first.copy(config = String(first.config.toCharArray()))))
        assertEquals(1, parses)
        // Настройки изменены редактором — новая версия разбирается.
        assertEquals("sensor.two", cache.of(first.copy(config = """{"entity_id":"sensor.two"}""")))
        assertEquals(2, parses)
        cache.clear()
        cache.of(first)
        assertEquals(3, parses)
    }

    @Test fun cachedClockFormatsMatchFreshFormattersAcrossZonesAndLocales() {
        val moments = listOf(1_793_706_600_000L, 1_800_000_000_000L, 1_812_345_678_901L).map(::Date)
        for (zoneId in listOf(null, "Europe/Moscow", "America/New_York", "Asia/Tokyo")) {
            val clock = HomeClimateConfig.Clock(timeZone = zoneId)
            val zone = zoneId?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault()
            for (date in moments) {
                fun fresh(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = zone }.format(date)
                assertEquals(fresh("HH:mm"), HomeClimateData.time(clock.copy(use24h = true), false, date, seconds = false))
                assertEquals(fresh("h:mm:ss"), HomeClimateData.time(clock.copy(use24h = false), true, date, seconds = true))
                assertEquals(fresh("a"), HomeClimateData.amPm(clock.copy(use24h = false), true, date))
                assertEquals(fresh("HH:mm"), HomeClimateData.hourLabel(date, clock.copy(use24h = true), false))
            }
        }
        // Язык системы сменился — подписи сразу на новом языке, без перезапуска.
        Locale.setDefault(Locale("ru"))
        val ru = HomeClimateData.date(HomeClimateConfig.Clock(timeZone = "UTC"), moments[0])
        Locale.setDefault(Locale.US)
        val en = HomeClimateData.date(HomeClimateConfig.Clock(timeZone = "UTC"), moments[0])
        assertNotEquals(ru, en)
        assertEquals(SimpleDateFormat("EEEE, d MMMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(moments[0]).replaceFirstChar { it.titlecase(Locale.US) }, en)
    }

    @Test fun forecastTimesStillParseAllHaFormats() {
        val expected = 1_793_721_600_000L // 2026-11-03T16:00:00Z
        for (text in listOf("2026-11-03T16:00:00+00:00", "2026-11-03T16:00:00Z", "2026-11-03T16:00:00.123456+00:00",
            "2026-11-03T19:00:00+03:00", "2026-11-03T16:00+00:00", "2026-11-03T16:00:00")) {
            assertEquals(text, expected, HomeClimateData.parseTime(text)?.time)
        }
        assertNull(HomeClimateData.parseTime("not a date"))
        assertNull(HomeClimateData.parseTime(""))
    }

    @Test fun tileOnAnotherPageDoesNotRebuildTheCardCacheOnEveryEvent() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = 1920
        activity.resources.displayMetrics.heightPixels = 1200
        val manager = TileManager(activity)
        val sensor = """{"collapsed_sensor_configs":[{"entity_id":"sensor.x","display_name":"V"}]}"""
        manager.saveTiles(listOf(tile("here", sensor), tile("elsewhere", sensor, page = 1)))
        val panel = FrameLayout(activity)
        ArchitectureTestAccess.setField(activity, "tileManager", manager)
        ArchitectureTestAccess.setField(activity, "bottomPanel", panel)
        ArchitectureTestAccess.setField(activity, "dimOverlay", View(activity))
        ArchitectureTestAccess.setField(activity, "yandexWeatherManager", YandexWeatherManager(activity))
        ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
        val controller = MainActivity::class.java.getDeclaredMethod("getPanelController")
            .apply { isAccessible = true }.invoke(activity)
        val viewForTile = controller.javaClass.getDeclaredMethod("viewForTile", String::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = org.robolectric.util.ReflectionHelpers.getField<Any>(controller, "renderedViews") as MutableMap<String, View>

        assertNotNull(viewForTile.invoke(controller, "here"))
        assertNull(viewForTile.invoke(controller, "elsewhere"))
        // Метка в кэше: пересчёт кэша её бы стёр.
        val marker = View(activity)
        cache["marker"] = marker
        repeat(50) { assertNull(viewForTile.invoke(controller, "elsewhere")) }
        assertSame("Плитка другой страницы не пересобирает кэш", marker, cache["marker"])
        assertNotNull(viewForTile.invoke(controller, "here"))

        // Дерево изменилось (карточка добавлена вне обычного обновления) — кэш пересобирается.
        panel.addView(View(activity).apply { tag = "added" })
        assertNotNull(viewForTile.invoke(controller, "added"))
        assertNull(cache["marker"])
    }
}
