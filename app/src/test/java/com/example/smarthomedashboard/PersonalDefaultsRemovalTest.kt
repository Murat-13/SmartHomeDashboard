package com.example.smarthomedashboard

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PersonalDefaultsRemovalTest {
    @Test fun freshMqttBridgeHasNoEmbeddedCredentials() {
        val app = RuntimeEnvironment.getApplication()
        val manager = MqttManager(app)
        assertEquals("", manager.legacyHostForBridge())
        assertEquals("", manager.legacyUsernameForBridge)
        assertEquals("", manager.legacyPasswordForBridge)
        assertTrue(app.getSharedPreferences("dashboard_prefs", 0).all.isEmpty())
    }
    @Test fun removalPreservesExplicitLegacyMqttValuesExactly() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("dashboard_prefs", 0)
        prefs.edit().putString("mqtt_host", "mqtt.example.invalid")
            .putString("mqtt_username", " fixture-user ")
            .putString("mqtt_password", " fixture-password ").commit()
        val before = prefs.all
        val manager = MqttManager(app)
        assertEquals("mqtt.example.invalid", manager.legacyHostForBridge())
        assertEquals(" fixture-user ", manager.legacyUsernameForBridge)
        assertEquals(" fixture-password ", manager.legacyPasswordForBridge)
        assertEquals(before, prefs.all)
    }
    @Test fun freshWeatherCoordinatesHaveNoPersonalLocation() {
        assertEquals("", LegacyWeatherCoordinateDefaults.latitude())
        assertEquals("", LegacyWeatherCoordinateDefaults.longitude())
    }
}
