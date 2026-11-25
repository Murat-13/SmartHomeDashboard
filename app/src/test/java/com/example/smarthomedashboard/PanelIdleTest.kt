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

    /**
     * Аудит 25.11.2026, STAB-06: правка идёт в окнах (диалогах) — у них свои касания, экран их не видит. Пять минут правки в окнах
     * закрывали редактор с потерей черновика.
     */
    @Config(sdk = [34])
    @Test fun touchesInDialogCountToo() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var clicked = 0
        val button = android.widget.Button(activity).apply { text = "В окне"; setOnClickListener { clicked++ } }
        val dialog = android.app.AlertDialog.Builder(activity).setTitle("Правка").setView(button).setPositiveButton("Готово", null).create()
        dialog.show()
        activity.window.callback.onWindowFocusChanged(false) // окно забрало фокус ввода
        idle(50)
        val root = dialog.window!!.decorView
        fun touchDialog() {
            val t = SystemClock.uptimeMillis()
            val at = IntArray(2).also { button.getLocationInWindow(it) }
            for ((action, dt) in listOf(MotionEvent.ACTION_DOWN to 0, MotionEvent.ACTION_UP to 30))
                root.dispatchTouchEvent(MotionEvent.obtain(t, t + dt, action, at[0] + 5f, at[1] + 5f, 0))
            idle(200)
        }
        repeat(3) { idle(4 * 60_000L); touchDialog() }
        assertNull("касания в окне — человек здесь", backToPanel(activity))
        assertEquals("сами касания доходят до кнопки окна", 3, clicked)
        idle(5 * 60_000L + 100)
        assertNotNull("окно брошено — на панель, как раньше", backToPanel(activity))
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    /** Окно может забирать фокус много раз (вложенные окна) — слой в каждом один, размеры окна не меняются. */
    @Config(sdk = [34])
    @Test fun watchedWindowLooksTheSame() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val dialog = android.app.AlertDialog.Builder(activity).setTitle("Правка").setMessage("Текст").create()
        dialog.show()
        idle(50)
        val root = dialog.window!!.decorView as android.view.ViewGroup
        val before = root.width to root.height
        repeat(3) { activity.window.callback.onWindowFocusChanged(false); idle(50) }
        assertEquals(1, (0 until root.childCount).count { root.getChildAt(it).tag == PanelIdle.TOUCHES_TAG })
        assertEquals(before, root.width to root.height)
        assertNull("на самом экране слоя нет", activity.window.decorView.findViewWithTag<android.view.View>(PanelIdle.TOUCHES_TAG))
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    /** Окно из окна: фокус экрана при этом уже не меняется. */
    @Config(sdk = [34])
    @Test fun windowOpenedFromWindowIsWatchedToo() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val first = android.app.AlertDialog.Builder(activity).setTitle("Первое").create().apply { show() }
        activity.window.callback.onWindowFocusChanged(false); idle(50)
        val second = android.app.AlertDialog.Builder(activity).setTitle("Второе").create().apply { show() }
        val sensor = first.window!!.decorView.findViewWithTag<android.view.View>(PanelIdle.TOUCHES_TAG)
        sensor.onWindowFocusChanged(false); idle(50)
        assertNotNull(second.window!!.decorView.findViewWithTag<android.view.View>(PanelIdle.TOUCHES_TAG))
        second.dismiss(); first.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun leftScreenDoesNotJumpToPanel() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.pause().stop()
        idle(6 * 60_000L)
        assertNull("ушли с экрана — он больше не отсчитывает", backToPanel(controller.get()))
    }
}
