package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/**
 * Экраны поверх панели — как панель (решение владельца 23.11.2026): экран не гаснет по таймеру Android, а без касаний
 * [PanelIdle.timeoutMs] — возврат на панель. Раньше архив «быстро темнел».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PanelIdleTest {
    private val real = PanelIdle.timeoutMs

    @Before fun shortTimeout() { PanelIdle.timeoutMs = { 5 * 60_000L } }
    @After fun restore() { PanelIdle.timeoutMs = real }

    class Video : Activity(), PanelIdle.Busy { var playing = false; override val idleBusy get() = playing }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private fun touch(activity: Activity) {
        val t = SystemClock.uptimeMillis()
        activity.window.callback.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        activity.window.callback.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, 10f, 10f, 0))
    }

    private fun backToPanel(activity: Activity): Intent? = shadowOf(activity).nextStartedActivity
        ?.takeIf { it.component?.className == MainActivity::class.java.name }

    @Test fun screenStaysOnAndReturnsToPanelAfterFiveMinutesWithoutTouch() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertTrue("экран не гаснет", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        idle(4 * 60_000L)
        touch(activity) // касание — отсчёт заново
        idle(4 * 60_000L)
        assertNull("после касания 5 минут ещё не прошло", backToPanel(activity))
        idle(60_000L + 100)
        val intent = backToPanel(activity)
        assertNotNull("без касаний 5 минут — на панель", intent)
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, intent!!.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    @Test fun playingVideoKeepsArchiveOpen() {
        val activity = Robolectric.buildActivity(Video::class.java).setup().get()
        activity.playing = true
        idle(5 * 60_000L + 100)
        assertNull("видео идёт — остаёмся", backToPanel(activity))
        activity.playing = false
        idle(5 * 60_000L + 100)
        assertNotNull("видео кончилось — без касаний на панель", backToPanel(activity))
    }

    @Test fun leftScreenDoesNotJumpToPanel() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.pause().stop()
        idle(6 * 60_000L)
        assertNull("ушли с экрана — он больше не отсчитывает", backToPanel(controller.get()))
    }
}
