package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/** Модель и данные «Часы · Погода · Климат»: хранение, избранное, 2:1, прогнозы, единицы, отсутствие данных. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class HomeClimateModelTest {
    private fun room(id: String, name: String = id, temp: String = "sensor.$id", humidity: String = "") =
        HomeClimateConfig.Room(id, name, "bed", temp, humidity)

    @Test fun configRoundTripKeepsOtherKeysAndStableRoomIds() {
        val config = HomeClimateConfig(
            HomeClimateConfig.Clock(use24h = false, dateFormat = HomeClimateConfig.DATE_SHORT, seconds = true, timeZone = "Europe/Moscow"),
            HomeClimateConfig.Weather(entityId = "weather.home", unit = HomeClimateConfig.UNIT_F, showWind = false, showHourly = false),
            listOf(room("a", humidity = "sensor.a_h"), room("b")), listOf("b", "a"))
        val written = config.writeTo("""{"button_size":640,"widget_height":320,"future":{"x":1}}""")
        val json = JSONObject(written)
        assertEquals(640, json.getInt("button_size"))
        assertEquals(1, json.getJSONObject("future").getInt("x"))
        assertEquals(config, HomeClimateConfig.from(written))
    }

    @Test fun missingOrBrokenConfigGivesEmptyDefaultsWithoutInventedSources() {
        listOf("{}", "{broken", """{"home_climate":"text"}""").forEach {
            val config = HomeClimateConfig.from(it)
            assertEquals("", config.weather.entityId)
            assertTrue(config.rooms.isEmpty())
            assertNull(config.clock.timeZone)
        }
        val created = JSONObject(HomeClimateConfig.newTileConfig())
        assertEquals(2.0, created.getInt("button_size").toDouble() / created.getInt("widget_height"), 0.001)
        assertTrue(HomeClimateConfig.from(created.toString()).rooms.isEmpty())
    }

    @Test fun favoritesAreLimitedToFiveWithExplicitLimitAndKeepTheirOwnOrder() {
        var config = HomeClimateConfig(rooms = (1..7).map { room("r$it") })
        (1..5).forEach { config = config.toggleFavorite("r$it").first }
        val (same, change) = config.toggleFavorite("r6")
        assertEquals(HomeClimateConfig.FavoriteChange.LIMIT, change)
        assertEquals(config, same) // Шестая отметка ничего не вытесняет.
        config = config.moveFavorite("r5", -4)
        assertEquals(listOf("r5", "r1", "r2", "r3", "r4"), config.favoriteRooms.map { it.id })
        // Порядок всех комнат и порядок избранных независимы.
        config = config.moveRoom("r7", -6)
        assertEquals("r7", config.rooms.first().id)
        assertEquals(listOf("r5", "r1", "r2", "r3", "r4"), config.favorites)
        // Удаление комнаты убирает её из избранного по id, соседи не сдвигаются.
        config = config.withoutRoom("r1")
        assertEquals(listOf("r5", "r2", "r3", "r4"), config.favorites)
        assertEquals(HomeClimateConfig.FavoriteChange.ADDED, config.toggleFavorite("r6").second)
    }

    @Test fun unknownAndExcessFavoritesAreDroppedOnLoad() {
        val json = HomeClimateConfig(rooms = (1..7).map { room("r$it") }).toJson()
            .put("favorites", JSONArray(listOf("ghost", "r1", "r1", "r2", "r3", "r4", "r5", "r6")))
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), HomeClimateConfig.fromJson(json).favorites)
    }

    @Test fun validationRequiresNameAndTemperatureButNotHumidity() {
        assertNull(HomeClimateConfig(rooms = listOf(room("a"))).validationError())
        assertNotNull(HomeClimateConfig(rooms = listOf(room("a", name = " "))).validationError())
        assertNotNull(HomeClimateConfig(rooms = listOf(room("a", temp = ""))).validationError())
        assertNotNull(HomeClimateConfig(weather = HomeClimateConfig.Weather(entityId = "sensor.x")).validationError())
    }

    @Test fun displaySizeIsAlwaysTwoToOne() {
        assertEquals(640 to 320, HomeClimateConfig.fit(640, 320))
        assertEquals(400 to 200, HomeClimateConfig.fit(1000, 200)) // не лента
        assertEquals(500 to 250, HomeClimateConfig.fit(500, 900))
        val (w, h) = HomeClimateConfig.fit(10, 10)
        assertEquals(2.0, w.toDouble() / h, 0.01)
    }

    private val session = HaSession(connected = true, synced = true)
    private fun readings(states: Map<String, String>, attrs: Map<String, JSONObject> = emptyMap(), stale: Set<String> = emptySet(),
                         s: HaSession = session) = BatteryReadings(s, states::get, { it in stale }, attrs::get)

    @Test fun roomValuesAreRealOrDashAndHumidityIsOptional() {
        val r = readings(mapOf("sensor.t" to "14.24", "sensor.h" to "48", "sensor.bad" to "unavailable", "sensor.f" to "71.6"),
            mapOf("sensor.t" to JSONObject().put("unit_of_measurement", "°C"), "sensor.h" to JSONObject().put("unit_of_measurement", "%"), "sensor.f" to JSONObject().put("unit_of_measurement", "°F")))
        assertEquals("14,2 °C", HomeClimateData.temperature(r, "sensor.t", HomeClimateConfig.UNIT_AUTO)!!.full())
        assertEquals("14°", HomeClimateData.temperature(r, "sensor.t", HomeClimateConfig.UNIT_AUTO)!!.compact())
        assertEquals("22 °C", HomeClimateData.temperature(r, "sensor.f", HomeClimateConfig.UNIT_C)!!.full())
        assertNull(HomeClimateData.temperature(r, "sensor.bad", HomeClimateConfig.UNIT_AUTO))
        assertEquals("48 %", HomeClimateData.humidity(r, "sensor.h"))
        assertNull(HomeClimateData.humidity(r, "")) // не «0 %»
        assertEquals("—", HomeClimateData.humidity(r, "sensor.bad"))
        // Без связи показания не выдаются за текущие.
        val offline = readings(mapOf("sensor.t" to "14"), s = HaSession())
        assertNull(HomeClimateData.temperature(offline, "sensor.t", HomeClimateConfig.UNIT_AUTO))
    }

    @Test fun currentWeatherUsesOnlyProvidedAttributes() {
        val attrs = JSONObject().put("temperature", 13).put("temperature_unit", "°C").put("humidity", 72)
            .put("wind_speed", 4.0).put("wind_speed_unit", "m/s").put("wind_bearing", 315).put("pressure", 1012).put("pressure_unit", "hPa")
        val weather = HomeClimateConfig.Weather(entityId = "weather.home")
        val current = HomeClimateData.current(readings(mapOf("weather.home" to "cloudy"), mapOf("weather.home" to attrs)), weather, null)
        assertNull(current.status)
        assertEquals("Пасмурно", HomeClimateData.conditionText(current.condition))
        assertEquals(WeatherGlyph.CLOUD, current.glyph)
        assertEquals("13 °C", current.temperature!!.full())
        assertEquals("72 %", current.humidity)
        assertEquals("4 м/с", current.wind)
        assertEquals("СЗ", current.windDirection)
        assertEquals("гПа", current.pressureUnit)
        assertNull(current.feelsLike) // не выдумываем ощущаемую
        assertNull(current.precipitation)

        val missing = HomeClimateData.current(readings(emptyMap()), weather, null)
        assertEquals("Сущность не найдена", missing.status)
        assertNull(missing.temperature)
        val none = HomeClimateData.current(readings(emptyMap()), HomeClimateConfig.Weather(), null)
        assertEquals("Погода не выбрана", none.status)
        val unavailable = HomeClimateData.current(readings(mapOf("weather.home" to "unavailable")), weather, null)
        assertEquals("Недоступно", unavailable.status)
    }

    @Test fun hourlyStartsAtNearestIntervalAndDailyNeverInventsDays() {
        val now = HomeClimateData.parseTime("2026-10-30T14:30:00+03:00")!!.time
        val hourly = JSONArray()
        (0..30).forEach { h ->
            hourly.put(JSONObject().put("datetime", "2026-10-30T%02d:00:00+03:00".format(10 + h % 14)).put("temperature", h)
                .put("condition", "rainy").put("precipitation_probability", 40))
        }
        val weather = HomeClimateConfig.Weather(entityId = "weather.home")
        val items = HomeClimateData.hourly(hourly, weather, "°C", now)
        assertTrue(items.all { it.time.time > now - 3_600_000L })
        assertEquals(items.sortedBy { it.time }, items)
        assertEquals(40.0, items.first().precipitationProbability!!, 0.0)

        val zone = TimeZone.getTimeZone("Europe/Moscow")
        val daily = JSONArray()
        listOf("2026-10-29", "2026-10-30", "2026-10-31", "2026-11-01").forEach { day ->
            daily.put(JSONObject().put("datetime", "${day}T00:00:00+03:00").put("temperature", 12).put("templow", 6).put("condition", "sunny"))
        }
        val days = HomeClimateData.daily(daily, weather, "°C", zone, now)
        assertEquals(3, days.size) // вчерашний отброшен, недостающие до пяти не добавлены
        assertEquals("6°", days.first().low!!.short())
        assertTrue(HomeClimateData.daily(null, weather, "°C", zone, now).isEmpty())
    }

    @Test fun timeParsingConversionsAndCompass() {
        listOf("2026-10-30T12:00:00+00:00", "2026-10-30T12:00:00Z", "2026-10-30T12:00:00.000Z", "2026-10-30T15:00:00+03:00").forEach {
            assertEquals(it, HomeClimateData.parseTime("2026-10-30T12:00:00Z")!!.time, HomeClimateData.parseTime(it)!!.time)
        }
        assertNull(HomeClimateData.parseTime("вчера"))
        assertEquals(212.0, HomeClimateData.convert(100.0, "°C", "°F"), 0.001)
        assertEquals(0.0, HomeClimateData.convert(32.0, "°F", "°C"), 0.001)
        assertEquals("°F", HomeClimateData.targetUnit(HomeClimateConfig.UNIT_AUTO, "°F"))
        assertEquals("С", HomeClimateData.compass(359.0))
        assertEquals("В", HomeClimateData.compass(90.0))
        assertEquals("0", HomeClimateData.number(-0.2, 0))
    }

    @Test fun clockUsesConfiguredFormatAndZone() {
        val moment = HomeClimateData.parseTime("2026-10-30T14:17:05Z")!!
        val utc = HomeClimateConfig.Clock(use24h = true, timeZone = "UTC")
        assertEquals("14:17", HomeClimateData.time(utc, system24h = false, now = moment))
        assertEquals("14:17:05", HomeClimateData.time(utc.copy(seconds = true), false, moment))
        assertEquals("2:17", HomeClimateData.time(utc.copy(use24h = false), true, moment))
        assertNotNull(HomeClimateData.amPm(utc.copy(use24h = false), true, moment))
        assertEquals("17:17", HomeClimateData.time(utc.copy(timeZone = "Europe/Moscow"), false, moment))
        assertEquals("30.10.2026", HomeClimateData.date(utc.copy(dateFormat = HomeClimateConfig.DATE_NUMERIC), moment))
        // Неизвестный пояс — системный, без падения.
        assertNotNull(HomeClimateData.time(utc.copy(timeZone = "Mars/Base"), true, moment))
        assertEquals(1030L, HomeClimateCompactView.delayToNextTick(seconds = true, now = 2_000L))
        assertEquals(60_030L - 15_000L, HomeClimateCompactView.delayToNextTick(seconds = false, now = 15_000L))
    }

    @Test fun snapshotKeepsFavoritesOrderAndForecastStates() {
        val config = HomeClimateConfig(weather = HomeClimateConfig.Weather(entityId = "weather.home"),
            rooms = listOf(room("a"), room("b"), room("c")), favorites = listOf("c", "a"))
        val r = readings(mapOf("weather.home" to "sunny", "sensor.a" to "20", "sensor.c" to "23"))
        val waiting = HomeClimateSnapshot.build("Дом", config, r, { null }, { null }, { false }, { false })
        assertEquals(listOf("c", "a"), waiting.favorites.map { it.room.id })
        assertEquals(3, waiting.rooms.size)
        assertNull(waiting.rooms[1].temperature) // sensor.b нет — «—», не 0
        assertEquals(HomeClimateSnapshot.ForecastState.WAITING, waiting.hourly.state)
        val unsupported = HomeClimateSnapshot.build("Дом", config, r, { null }, { null }, { false }, { true })
        assertEquals(HomeClimateSnapshot.ForecastState.UNSUPPORTED, unsupported.hourly.state)
        val hidden = HomeClimateSnapshot.build("Дом", config.copy(weather = config.weather.copy(showHourly = false, showDaily = false)),
            r, { null }, { null }, { false }, { false })
        assertEquals(HomeClimateSnapshot.ForecastState.HIDDEN, hidden.hourly.state)
        assertEquals(HomeClimateSnapshot.ForecastState.HIDDEN, hidden.daily.state)
        val noWeather = HomeClimateSnapshot.build("Дом", HomeClimateConfig(), r, { null }, { null }, { false }, { false })
        assertEquals(HomeClimateSnapshot.ForecastState.NOT_CONFIGURED, noWeather.daily.state)
    }

    @Test fun subscriptionPlanIncludesClimateSourcesIndependentlyOfYandex() {
        val tile = TileEntity("hc", HomeClimateConfig.TYPE, "grid", "Дом", 0, 0, 1, 1, config = HomeClimateConfig(
            weather = HomeClimateConfig.Weather(entityId = "weather.home"),
            rooms = listOf(room("a", humidity = "sensor.a_h"))).writeTo("{}"))
        val plan = PanelSubscriptionPlan.create(listOf(tile), null, useYandex = true)
        assertTrue(plan.entityIds.containsAll(listOf("weather.home", "sensor.a", "sensor.a_h")))
        assertEquals(setOf("weather.home"), plan.forecastIds)
        assertEquals(setOf("weather.home"), plan.hourlyForecastIds)
        val quiet = tile.copy(config = HomeClimateConfig(weather = HomeClimateConfig.Weather(entityId = "weather.home",
            showDaily = false, showHourly = false, showPrecipitation = false)).writeTo("{}"))
        val none = PanelSubscriptionPlan.create(listOf(quiet), null, useYandex = false)
        assertTrue(none.forecastIds.isEmpty())
        assertTrue(none.hourlyForecastIds.isEmpty())
        // Зависимости адресной перерисовки берутся из config без отдельного списка.
        assertTrue(PanelEntityIds.describe(tile).ids.containsAll(listOf("weather.home", "sensor.a", "sensor.a_h")))
    }

    @Test fun deviceStateKeepsHourlySeparateFromDailyAttribute() {
        val state = DashboardDeviceState()
        state.entityChanged("weather.home", "sunny", JSONObject().put("temperature", 10))
        state.hourlyForecastChanged("weather.home", JSONArray().put(JSONObject().put("datetime", "2026-10-30T12:00:00Z")), true)
        assertFalse(state.entityAttributes["weather.home"]!!.has("forecast"))
        assertEquals(1, state.hourlyForecasts["weather.home"]!!.length())
        state.hourlyForecastChanged("weather.home", null, false)
        assertTrue("weather.home" in state.hourlyUnsupported)
        assertFalse(state.hourlyForecasts.containsKey("weather.home"))
    }
}
