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
    @Test fun freshWeatherCoordinatesHaveNoPersonalLocation() {
        assertEquals("", LegacyWeatherCoordinateDefaults.latitude())
        assertEquals("", LegacyWeatherCoordinateDefaults.longitude())
    }
}
