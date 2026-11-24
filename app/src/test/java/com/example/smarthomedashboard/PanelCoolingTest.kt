package com.example.smarthomedashboard

import android.provider.Settings
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Остывание меняет только окно панели (docs/setup-tz.md, часть 4): затемнение и окно звонка своё не теряют. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PanelCoolingTest {
    private val activity = Robolectric.buildActivity(MainActivity::class.java).get()
    private val none = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    @Before fun setUp() {
        TabletWatch.resetForTests()
        Settings.System.putInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 204)
    }
    @After fun tearDown() = TabletWatch.resetForTests()

    private fun call(name: String) { ArchitectureTestAccess.method(activity, name).apply { isAccessible = true }.invoke(activity) }
    private fun heat(hot: Boolean) = ReflectionHelpers.setField(TabletWatch, "hot", hot)
    private fun brightness() = activity.window.attributes.screenBrightness

    @Test fun brightnessGoesDownByQuarterAndReturns() {
        call("coolPanel")
        assertEquals("ничего не трогаем", none, brightness())
        heat(true); call("coolPanel")
        assertEquals(204 / 255f * 0.75f, brightness(), 0.001f)
        call("coolPanel")
        assertEquals("каждую минуту не темнеет", 204 / 255f * 0.75f, brightness(), 0.001f)
        heat(false); call("coolPanel")
        assertEquals(none, brightness())
    }

    @Test fun dimmedPanelStaysDimmedAndWakesCooled() {
        call("dimScreen")
        assertEquals(0.01f, brightness())
        heat(true); call("coolPanel")
        assertEquals("затемнение важнее", 0.01f, brightness())
        call("wakeScreen")
        call("coolPanel")
        assertEquals(204 / 255f * 0.75f, brightness(), 0.001f)
        // Остыло, пока панель затемнена: проснётся и через минуту вернёт обычную яркость.
        call("dimScreen"); heat(false); call("coolPanel"); call("wakeScreen"); call("coolPanel")
        assertEquals(none, brightness())
    }

    @Test fun brightnessOfRingWindowIsNotTouched() {
        val params = activity.window.attributes.apply { screenBrightness = 0.4f } // окно звонка в тихие часы
        activity.window.attributes = params
        heat(true); call("coolPanel")
        assertEquals(0.4f, brightness())
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = none } // звонок закончился
        call("coolPanel")
        assertEquals(204 / 255f * 0.75f, brightness(), 0.001f)
    }
}
