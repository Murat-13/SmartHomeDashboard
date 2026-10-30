package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class YandexClimateWeatherTest {
    private val now = HomeClimateData.parseTime("2026-10-30T08:00:00Z")!!.time
    private fun response() = JSONObject("""{
      "fact":{"temp":13,"feels_like":11,"condition":"overcast","humidity":72,"pressure_mm":748,"wind_speed":4,"wind_dir":"nw"},
      "info":{"tzinfo":{"name":"Europe/Moscow"}},
      "forecasts":[
        {"date":"2099-01-01","parts":{"day":{"temp_min":8,"temp_max":15,"condition":"clear"},"night":{"temp_min":5,"temp_max":9}},
         "hours":[{"hour":"23","temp":12,"condition":"rain","prec_prob":20}]},
        {"date":"2099-01-02","parts":{"day_short":{"temp":17,"condition":"clear"},"night_short":{"temp":6}},
         "hours":[{"hour":"0","temp":10,"condition":"clear","daytime":"n"}]}
      ]}""")
    private fun config(source: String = HomeClimateConfig.SOURCE_YANDEX) = HomeClimateConfig(
        weather = HomeClimateConfig.Weather(entityId = "weather.previous", source = source),
        rooms = listOf(HomeClimateConfig.Room("room", "Room", temperatureEntity = "sensor.temperature")), favorites = listOf("room"))

    @Test fun sourceDefaultsToHaAndRoundTripsWithoutLosingPreviousEntity() {
        assertEquals(HomeClimateConfig.SOURCE_HA, HomeClimateConfig.from("{}").weather.source)
        val value = config()
        assertEquals(value, HomeClimateConfig.from(value.writeTo("{}")))
        assertEquals("weather.previous", HomeClimateConfig.from(value.writeTo("{}")).weather.entityId)
    }
    @Test fun parserKeepsRealValuesBothForecastDaysAndCorrectMidnightOrder() {
        val data = YandexClimateWeather.parse(response(), now)
        assertEquals("cloudy", data.condition)
        assertEquals(13.0, data.attributes.getDouble("temperature"), 0.0)
        assertEquals(11.0, data.attributes.getDouble("apparent_temperature"), 0.0)
        assertEquals("mmHg", data.attributes.getString("pressure_unit"))
        assertEquals(2, data.hourly.length())
        assertEquals("clear-night", data.hourly.getJSONObject(1).getString("condition"))
        val a = HomeClimateData.parseTime(data.hourly.getJSONObject(0).getString("datetime"))!!.time
        val b = HomeClimateData.parseTime(data.hourly.getJSONObject(1).getString("datetime"))!!.time
        assertEquals(3_600_000L, b - a)
        assertEquals(15, data.daily.getJSONObject(0).getInt("temperature"))
        assertEquals(5, data.daily.getJSONObject(0).getInt("templow"))
    }
    @Test fun missingFieldsNeverBecomeFakeZerosOrInventedHourlyTimes() {
        val data = YandexClimateWeather.parse(JSONObject("""{"fact":{"condition":"clear"},"forecasts":[{"date":"2099-01-01","hours":[{"hour":"8","temp":5}]}]}"""), now)
        assertFalse(data.attributes.has("temperature"))
        assertFalse(data.attributes.has("humidity"))
        assertEquals(0, data.hourly.length()) // Нет часового пояса или hour_ts.
        assertFalse(data.daily.getJSONObject(0).has("temperature"))
        assertFalse(data.daily.getJSONObject(0).has("templow"))
    }
    @Test fun yandexWeatherDoesNotDependOnHaAndRoomsKeepTheirOwnHaStatus() {
        val data = YandexClimateWeather.parse(response(), System.currentTimeMillis())
        val snapshot = HomeClimateSnapshot.from("Home", config(), MainUiState(yandexWeather = data))
        assertEquals("13 °C", snapshot.current.temperature!!.full())
        assertEquals(HomeClimateSnapshot.ForecastState.READY, snapshot.hourly.state)
        assertEquals(HomeClimateSnapshot.ForecastState.READY, snapshot.daily.state)
        assertNull(snapshot.rooms.single().temperature)
        assertTrue(snapshot.offlineStatus!!.contains("Комнаты"))
        val noRooms = HomeClimateSnapshot.from("Home", config().copy(rooms = emptyList(), favorites = emptyList()), MainUiState(yandexWeather = data))
        assertNull(noRooms.offlineStatus)
    }
    @Test fun selectedUnitsApplyToYandexAndActualZeroIsKept() {
        val json = response().apply { getJSONObject("fact").put("temp", 0) }
        val data = YandexClimateWeather.parse(json)
        val config = config().copy(weather = config().weather.copy(unit = HomeClimateConfig.UNIT_F))
        val snapshot = HomeClimateSnapshot.from("Home", config, MainUiState(yandexWeather = data))
        assertEquals("32 °F", snapshot.current.temperature!!.full())
    }
    @Test fun cachedOrFailedDataIsExplicitAndMissingKeyDoesNotBecomeHaDisconnected() {
        val data = YandexClimateWeather.parse(response(), now)
        val cached = YandexClimateWeather.fromCache(data.toJson())!!
        assertTrue(cached.status()!!.contains("кэш"))
        val snapshot = HomeClimateSnapshot.from("Home", config(), MainUiState(yandexWeather = cached))
        assertTrue(snapshot.current.status!!.contains("кэш"))
        val failed = data.copy(error = "Не удалось загрузить погоду Яндекса")
        assertTrue(failed.status()!!.contains("кэш"))
        val missing = HomeClimateSnapshot.from("Home", config(), MainUiState(yandexWeather = YandexClimateWeather(error = "Ключ Яндекса не настроен")))
        assertEquals("Ключ Яндекса не настроен", missing.current.status)
        assertNull(missing.current.temperature)
    }
    @Test fun planKeepsRoomSensorsButDoesNotSubscribeToHaWeatherForYandexTile() {
        val yandex = TileEntity("yandex", HomeClimateConfig.TYPE, "grid", "", 0, 0, 1, 1, config = config().writeTo("{}"))
        val ha = yandex.copy(id = "ha", config = config(HomeClimateConfig.SOURCE_HA).writeTo("{}"))
        val plan = PanelSubscriptionPlan.create(listOf(yandex), null, false)
        assertTrue("sensor.temperature" in plan.entityIds)
        assertFalse("weather.previous" in plan.entityIds)
        assertTrue(plan.forecastIds.isEmpty()); assertTrue(plan.hourlyForecastIds.isEmpty())
        val mixed = PanelSubscriptionPlan.create(listOf(yandex, ha), null, true)
        assertEquals(setOf("weather.previous"), mixed.forecastIds)
        assertEquals(setOf("weather.previous"), mixed.hourlyForecastIds)
    }
}
