package com.example.smarthomedashboard

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WeatherPreferencesBridgeTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences("weather-artificial", 0)
    private fun legacy(p: SharedPreferences = prefs) = PreferenceWeatherLegacy({ p }, { "10.0" }, { "20.0" })

    @Test fun productionFactoryIsLazyAndRejectsMainThreadBeforeReadingAnything() {
        val access = WeatherKeyAccess.create(app)
        try { access.read(); fail("worker required") } catch (_: IllegalStateException) {}
        assertTrue(app.getSharedPreferences("dashboard_prefs", 0).all.isEmpty())
    }

    @Test fun missingCoordinatesAreCapturedOnceWithoutChangingAnythingElse() {
        prefs.edit().putBoolean("use_yandex_weather", true).putString("yandex_last_temp", "FAKE-CACHE")
            .putString("future_unknown", "kept").commit()
        val configuration = legacy().prepareConfiguration()
        assertEquals("10.0", configuration.latitude)
        assertEquals("20.0", configuration.longitude)
        assertTrue(configuration.enabled)
        assertEquals(mapOf("use_yandex_weather" to true, "yandex_last_temp" to "FAKE-CACHE",
            "future_unknown" to "kept", "yandex_lat" to "10.0", "yandex_lon" to "20.0"), prefs.all)
        val repeated = PreferenceWeatherLegacy({ prefs }, { error("No default") }, { error("No default") }).prepareConfiguration()
        assertEquals("10.0", repeated.latitude)
    }
    @Test fun existingEmptyAndWhitespaceCoordinatesAreNeverDefaultsOrTrimmed() {
        prefs.edit().putString("yandex_lat", "").putString("yandex_lon", "  20.0  ").commit()
        val before = prefs.all
        val configuration = PreferenceWeatherLegacy({ prefs }, { error("No default") }, { error("No default") }).prepareConfiguration()
        assertEquals("", configuration.latitude)
        assertEquals("  20.0  ", configuration.longitude)
        assertEquals(before, prefs.all)
    }
    @Test fun oneMissingCoordinateDoesNotReplaceTheOther() {
        prefs.edit().putString("yandex_lat", "30.0").commit()
        val configuration = legacy().prepareConfiguration()
        assertEquals("30.0", configuration.latitude)
        assertEquals("20.0", configuration.longitude)
        assertFalse(prefs.contains("use_yandex_weather"))
    }
    @Test fun wrongCoordinateOrSourceTypesAreErrorsNotMissing() {
        for (field in listOf("yandex_lat", "yandex_lon", "use_yandex_weather")) {
            prefs.edit().clear().putString("yandex_lat", "10.0").putString("yandex_lon", "20.0")
                .putInt(field, 7).commit()
            val before = prefs.all
            try { legacy().prepareConfiguration(); fail("Invalid type") }
            catch (e: WeatherLegacyFailure) { assertEquals(WeatherKeyAccess.Status.LEGACY_ERROR, e.status) }
            assertEquals(before, prefs.all)
        }
    }
    @Test fun emptyLegacyKeyIsPresentAndDifferentFromAbsent() {
        assertNull(legacy().readKey())
        prefs.edit().putString("yandex_api_key", "").commit()
        assertEquals("", legacy().readKey())
    }
    @Test fun wrongSecretTypeDoesNotBecomeAbsence() {
        prefs.edit().putInt("yandex_api_key", 7).commit()
        try { legacy().readKey(); fail() }
        catch (e: WeatherLegacyFailure) { assertEquals(WeatherKeyAccess.Status.LEGACY_ERROR, e.status) }
    }
    @Test fun changedLegacyCannotBeDeletedByOldExpectedValue() {
        prefs.edit().putString("yandex_api_key", "FAKE-FRESH").commit()
        assertFalse(legacy().removeIfUnchanged("FAKE-OLD"))
        assertEquals("FAKE-FRESH", prefs.getString("yandex_api_key", null))
    }
    @Test fun failedCoordinateCommitIsRetriedEvenIfValuesAlreadyExistInMemory() {
        var failed = true
        var commits = 0
        val wrapped = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val edit = prefs.edit()
                return object : SharedPreferences.Editor by edit {
                    override fun commit(): Boolean { commits++; val result = edit.commit(); return !failed && result }
                }
            }
        }
        try { legacy(wrapped).prepareConfiguration(); fail() }
        catch (e: WeatherLegacyFailure) { assertEquals(WeatherKeyAccess.Status.CONFIG_WRITE_FAILED, e.status) }
        val previous = commits
        failed = false
        legacy(wrapped).prepareConfiguration()
        assertTrue(commits > previous)
    }
}
