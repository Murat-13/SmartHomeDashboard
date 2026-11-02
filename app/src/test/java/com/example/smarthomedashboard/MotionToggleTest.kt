package com.example.smarthomedashboard

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Явный переключатель датчика движения: выключен — затемнения нет, выбранный датчик не стирается. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MotionToggleTest {
    private fun run(enabled: Boolean?): Pair<Boolean, String?> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val prefs = activity.getSharedPreferences("dashboard_prefs", 0)
        prefs.edit().clear().putString("motion_sensor_id", "binary_sensor.kitchen_motion")
            .putInt("screen_timeout_minutes", 1).apply { if (enabled != null) putBoolean(MainActivity.PREF_MOTION_ENABLED, enabled) }.commit()
        ArchitectureTestAccess.method(activity, "readPanelPreferences").apply { isAccessible = true }.invoke(activity)
        ArchitectureTestAccess.method(activity, "resetIdleTimer").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        return ArchitectureTestAccess.getField<Boolean>(activity, "isDimmed") to prefs.getString("motion_sensor_id", null)
    }

    @Test fun defaultKeepsExistingMotionDimming() {
        assertTrue(run(enabled = null).first)
    }

    @Test fun disabledSensorNeverDimsAndKeepsSelectedEntity() {
        val (dimmed, entity) = run(enabled = false)
        assertFalse(dimmed)
        assertEquals("binary_sensor.kitchen_motion", entity)
    }
}
