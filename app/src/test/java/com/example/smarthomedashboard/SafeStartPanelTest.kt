package com.example.smarthomedashboard

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.example.smarthomedashboard.data.TileManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * Безопасный запуск на работающей панели сам кончается через час без сбоев, когда никто не пользуется (аудит 25.11.2026, STAB-04).
 * Раньше отметка снималась только «на следующий запуск», а исправная панель сама не перезапускается — видео, запись и речь были
 * выключены, пока не коснутся плашки.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h800dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SafeStartPanelTest {
    private val context = RuntimeEnvironment.getApplication()
    private var t = 10_000_000_000L

    @Before fun safe() {
        context.getSharedPreferences("tiles_prefs", 0).edit().clear().commit()
        context.getSharedPreferences("dashboard_prefs", 0).edit().clear().putInt("screen_timeout_minutes", 5).commit()
        SafeStart.resetForTests(context)
        SafeStart.now = { t }
        repeat(3) { SafeStart.processStarted(context); t += 4_000; SafeStart.crashed(context); t += 5_000 }
        SafeStart.processStarted(context)
        assertEquals(SafeStart.Mode.SAFE, SafeStart.mode)
        UpdateInstaller.returns = { true }
    }

    @After fun clear() { SafeStart.resetForTests(context); LightMode.resetForTests(); UpdateInstaller.returns = realReturns }
    private val realReturns = UpdateInstaller.returns

    private fun idleMinutes(n: Int) = repeat(n) { t += 60_000; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1)) }

    private fun touch(activity: MainActivity) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) activity.dispatchTouchEvent(MotionEvent.obtain(now, now, action, 900f, 780f, 0))
    }

    private fun banner(activity: MainActivity) = activity.window.decorView.findViewWithTag<android.view.View>("safe_banner")

    @Test fun untouchedPanelReturnsToNormalModeByItself() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        controller.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("плашка безопасного запуска", banner(activity))
        idleMinutes(58)
        assertEquals("часа ещё нет", SafeStart.Mode.SAFE, SafeStart.mode)
        // Человек пользуется панелью — посреди дела режим не меняется.
        idleMinutes(1); touch(activity); idleMinutes(3)
        assertEquals("касались 3 минуты назад", SafeStart.Mode.SAFE, SafeStart.mode)
        // Был в Настройках — касания там панель не видит; вернулся — отсчёт заново.
        controller.pause(); idleMinutes(6); controller.resume(); controller.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        idleMinutes(3)
        assertEquals("вернулся 3 минуты назад", SafeStart.Mode.SAFE, SafeStart.mode)
        idleMinutes(5)
        assertEquals("касаний нет 5 минут — обычный режим", SafeStart.Mode.NORMAL, SafeStart.mode)
        assertNull("и плашки больше нет", banner(controller.get()).takeIf { controller.get() !== activity })
        controller.pause().stop().destroy()
    }

    /** Панель после сбоя сама не откроется (не главный экран, нет «поверх других окон») — безопасная лучше закрытой. */
    @Test fun panelThatCannotReturnStaysSafe() {
        UpdateInstaller.returns = { false }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.windowFocusChanged(true)
        idleMinutes(120)
        assertEquals(SafeStart.Mode.SAFE, SafeStart.mode)
        controller.pause().stop().destroy()
    }
}
