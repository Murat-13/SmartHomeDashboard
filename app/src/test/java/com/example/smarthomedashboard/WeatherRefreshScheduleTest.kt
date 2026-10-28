package com.example.smarthomedashboard

import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

// Панель может неделями не уходить с экрана: погода Яндекса обновляется по таймеру, а не только в onResume.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WeatherRefreshScheduleTest {
    @Test fun refreshReschedulesItselfHourlyAndPauseStopsIt() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        ReflectionHelpers.setField(activity, "yandexWeatherManager", YandexWeatherManager(activity))
        val handler = ReflectionHelpers.getField<Handler>(activity, "clockHandler")
        val refresh = ReflectionHelpers.getField<Runnable>(activity, "weatherRefreshRunnable")

        refresh.run()
        assertTrue(handler.hasCallbacks(refresh))
        val looper = shadowOf(Looper.getMainLooper())
        // Раньше следующего часа повторного запроса нет — бережём суточный лимит ключа.
        looper.idleFor(Duration.ofMinutes(59))
        assertTrue(handler.hasCallbacks(refresh))
        assertEquals(60 * 60 * 1000L, MainActivity.WEATHER_REFRESH_MS)

        handler.removeCallbacks(refresh) // то же делает onPause
        assertFalse(handler.hasCallbacks(refresh))
    }
}
