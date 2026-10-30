package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class HomeClimateCompletionTest {
    private val config = HomeClimateConfig(weather = HomeClimateConfig.Weather("weather.test"))
    private val session = HaSession(connected = true, synced = true)
    private fun date(text: String) = HomeClimateData.parseTime(text)!!.time
    private fun forecast() = JSONArray().put(JSONObject().put("datetime", "2099-10-30T14:00:00Z").put("temperature", 13))
    private fun snapshot(devices: DashboardDeviceState) = HomeClimateSnapshot.from("Test", config, session, devices)

    @Test fun incorrectSensorUnitsNeverBecomeClimateReadings() {
        val states = mapOf("sensor.power" to "648", "sensor.energy" to "1.8", "sensor.humidity" to "101", "sensor.missing_unit" to "22")
        val attrs = mapOf("sensor.power" to JSONObject().put("unit_of_measurement", "W"),
            "sensor.energy" to JSONObject().put("unit_of_measurement", "kWh"),
            "sensor.humidity" to JSONObject().put("unit_of_measurement", "%"))
        val readings = BatteryReadings(session, states::get, { false }, attrs::get)
        assertNull(HomeClimateData.temperature(readings, "sensor.power", HomeClimateConfig.UNIT_C))
        assertNull(HomeClimateData.temperature(readings, "sensor.missing_unit", HomeClimateConfig.UNIT_C))
        assertEquals("—", HomeClimateData.humidity(readings, "sensor.energy"))
        assertEquals("—", HomeClimateData.humidity(readings, "sensor.humidity"))
        assertNotNull(HomeClimateConfig.roomUnitError("sensor.power", attrs["sensor.power"]))
        assertNotNull(HomeClimateConfig.roomUnitError("sensor.energy", attrs["sensor.energy"], humidity = true))
    }

    @Test fun domainAndEntitySyntaxAreValidatedBeforeSave() {
        assertNotNull(HomeClimateConfig(weather = HomeClimateConfig.Weather("weather.")).validationError())
        assertNotNull(HomeClimateConfig(rooms = listOf(HomeClimateConfig.Room("r", "Room", temperatureEntity = "switch.heater"))).validationError())
        assertNotNull(HomeClimateConfig(rooms = listOf(HomeClimateConfig.Room("r", "Room", temperatureEntity = "sensor.temperature", humidityEntity = "sensor.bad id"))).validationError())
    }

    @Test fun dailyNeverIncludesYesterdayInSelectedTimeZone() {
        val items = JSONArray()
            .put(JSONObject().put("datetime", "2026-10-29T23:00:00+03:00").put("temperature", 1))
            .put(JSONObject().put("datetime", "2026-10-30T08:00:00+03:00").put("temperature", 2))
        val days = HomeClimateData.daily(items, config.weather, "°C", TimeZone.getTimeZone("Europe/Moscow"), date("2026-10-30T00:30:00+03:00"))
        assertEquals(1, days.size)
        assertEquals(2.0, days.single().temperature!!.value, 0.0)
        assertNull(HomeClimateData.parseTime("2026-10-30garbage"))
        assertNull(HomeClimateData.parseTime("2026-13-40T08:00:00Z"))
    }

    @Test fun forecastCachesAreInvalidatedOnDisconnectBeforeFreshSession() {
        val devices = DashboardDeviceState()
        devices.entityChanged("weather.test", "cloudy", JSONObject().put("temperature_unit", "°C"))
        devices.hourlyForecastChanged("weather.test", forecast(), true)
        devices.forecastChanged("weather.test", forecast())
        assertEquals(HomeClimateSnapshot.ForecastState.READY, snapshot(devices).hourly.state)
        assertEquals(HomeClimateSnapshot.ForecastState.READY, snapshot(devices).daily.state)
        devices.markDisconnected()
        devices.entityChanged("weather.test", "sunny", JSONObject().put("temperature_unit", "°C"))
        // Даже если legacy forecast остался в атрибутах, новая плитка ждёт свежие ответы.
        assertEquals(HomeClimateSnapshot.ForecastState.WAITING, snapshot(devices).hourly.state)
        assertEquals(HomeClimateSnapshot.ForecastState.WAITING, snapshot(devices).daily.state)
        devices.hourlyForecastChanged("weather.test", forecast(), true)
        devices.forecastChanged("weather.test", forecast())
        assertEquals(HomeClimateSnapshot.ForecastState.READY, snapshot(devices).hourly.state)
        assertEquals(HomeClimateSnapshot.ForecastState.READY, snapshot(devices).daily.state)
    }

    @Test fun forecastTypesAndSupportStatusReachEditorSnapshot() {
        val devices = DashboardDeviceState()
        devices.entityChanged("weather.test", "cloudy", JSONObject().put("temperature_unit", "°C"))
        devices.hourlyForecastChanged("weather.test", forecast(), true)
        devices.dailyUnsupported.add("weather.test")
        val state = devices.snapshot(MainUiState(haSession = session))
        val preview = HomeClimateSnapshot.from("Test", config, state)
        assertEquals(HomeClimateSnapshot.ForecastState.READY, preview.hourly.state)
        assertEquals(HomeClimateSnapshot.ForecastState.UNSUPPORTED, preview.daily.state)
        assertEquals(snapshot(devices).hourly, preview.hourly)
    }
    @Test fun unavailableWeatherMarksForecastAsCachedWithoutAffectingRoomOrClock() {
        val devices = DashboardDeviceState()
        devices.entityChanged("weather.test", "unavailable", JSONObject().put("temperature_unit", "°C"))
        devices.hourlyForecastChanged("weather.test", forecast(), true)
        val snapshot = snapshot(devices)
        assertNotNull(snapshot.offlineStatus)
        assertNull(snapshot.current.temperature)
        assertTrue(snapshot.hourly.items.isNotEmpty())
    }

    @Test fun missingWeatherTemperatureUnitDoesNotInventCelsius() {
        val devices = DashboardDeviceState()
        devices.entityChanged("weather.test", "sunny", JSONObject().put("temperature", 72))
        devices.forecastChanged("weather.test", forecast())
        val snapshot = snapshot(devices)
        assertNull(snapshot.current.temperature)
        assertNull(snapshot.daily.items.single().temperature)
    }

    @Test fun corruptedOriginalConfigCannotBeSilentlyReplacedBySave() {
        val tile = com.example.smarthomedashboard.data.TileEntity("bad", HomeClimateConfig.TYPE, "grid", "Home", 0, 0, 1, 1,
            config = "{broken")
        assertNotNull(HomeClimateDraft(tile).validationError())
        assertEquals("{broken", tile.config)
    }

}
