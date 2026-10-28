package com.example.smarthomedashboard

import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers

// Те же missing/empty сценарии после исправления: реальные async reader/UI, только искусственные defaults.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WeatherBridgeRegressionTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences("dashboard_prefs", 0)
    private val access by lazy { weatherKeyTestAccess(app) }
    @Test fun missingKeyMustNotBecomeTheUiCandidate() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).get()
        weatherLive(activity)
        attachWeatherSettingsFields(activity, access)
        val input: EditText = ReflectionHelpers.getField(activity, "etYandexApiKey")
        assertEquals("", input.text.toString())
        assertFalse(prefs.contains("yandex_api_key"))
        assertEquals(WeatherKeyAccess.Status.ABSENT, weatherWorker { access.read() }.status)
    }
    @Test fun missingCoordinatesMustBeCapturedForTheFollowingDefaultRemoval() {
        prefs.edit().putBoolean("use_yandex_weather", false).putString("yandex_last_temp", "FAKE-CACHE").commit()
        val manager = YandexWeatherManager(app)
        ReflectionHelpers.setField(manager, "weatherKeys", access)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            manager.fetchWeather(scope)
            weatherAwait { scope.coroutineContext[Job]!!.children.none() }
            assertEquals("10.0", manager.loadLat())
            assertEquals("20.0", manager.loadLon())
            assertTrue(prefs.contains("yandex_lat"))
            assertTrue(prefs.contains("yandex_lon"))
            assertFalse(prefs.getBoolean("use_yandex_weather", true))
            assertEquals("FAKE-CACHE", prefs.getString("yandex_last_temp", null))
        } finally { scope.cancel(); manager.cancelPendingRequests() }
    }
    @Test fun presentEmptyCoordinatesAreNotDefaults() {
        prefs.edit().putString("yandex_lat", "").putString("yandex_lon", "").commit()
        val result = weatherWorker { access.read() }
        assertEquals("", result.configuration!!.latitude)
        assertEquals("", result.configuration.longitude)
        assertEquals("", prefs.getString("yandex_lat", null))
        assertEquals("", prefs.getString("yandex_lon", null))
    }
}
