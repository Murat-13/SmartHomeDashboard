package com.example.smarthomedashboard

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h800dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SideDrawerTest {
    private val activity by lazy {
        Robolectric.buildActivity(Activity::class.java).also { it.get().setTheme(R.style.Theme_SmartHomeDashboard_Modern) }.setup().get()
    }
    private val root by lazy { FrameLayout(activity).also { activity.setContentView(it); it.layout(0, 0, 1280, 800) } }
    private val actions = mutableListOf<String>()
    private val states = mutableListOf<Boolean>()
    private var busy = false
    private val drawer by lazy {
        SideDrawer(root, object : SideDrawer.Host {
            override fun settings() { actions += "settings" }
            override fun addTile() { actions += "add" }
            override fun edit() { actions += "edit" }
            override fun endAdmin() { actions += "end" }
            override fun adminActive() = false
            override fun busy() = busy
            override fun stateChanged(open: Boolean) { states += open }
        })
    }
    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) { shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS); left -= 16 }
    }
    private fun scrim() = (0 until root.childCount).map { root.getChildAt(it) }.first { it.contentDescription == "Закрыть панель" }
    private fun touch(action: Int, x: Float, y: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, x, y, 0)
    }

    @Test fun opensOverTilesAndScrimTapOnlyCloses() {
        drawer.open()
        advance(800)
        assertEquals(0f, drawer.offset, 0.5f)
        assertEquals(View.VISIBLE, scrim().visibility)
        assertEquals(listOf(true), states)
        scrim().performClick()
        advance(800)
        assertFalse(drawer.isOpen)
        assertEquals(View.GONE, scrim().visibility)
        assertEquals(listOf(true, false), states)
        assertTrue(actions.isEmpty())
    }

    @Test fun autoClosesAfterTenSecondsButWaitsForOpenDialog() {
        drawer.open()
        advance(800)
        busy = true
        advance(SideDrawer.AUTO_CLOSE_MS + 500)
        assertTrue(drawer.isOpen)
        busy = false
        advance(SideDrawer.AUTO_CLOSE_MS + 800)
        assertFalse(drawer.isOpen)
    }

    @Test fun touchInsidePanelRestartsAutoCloseTimer() {
        drawer.open()
        advance(800)
        advance(8_000)
        val down = touch(MotionEvent.ACTION_DOWN, 50f, 50f)
        val up = touch(MotionEvent.ACTION_UP, 50f, 50f)
        drawer.handleTouch(down); drawer.handleTouch(up)
        down.recycle(); up.recycle()
        advance(8_000)
        assertTrue(drawer.isOpen)
        advance(3_000)
        assertFalse(drawer.isOpen)
    }

    @Test fun dragFollowsFingerAndReleaseDecidesByDistanceOrFling() {
        drawer.dragOpen(60f)
        assertEquals(-140f, drawer.offset, 1f)
        drawer.release(0f)
        advance(800)
        assertFalse(drawer.isOpen)
        drawer.dragOpen(150f)
        drawer.release(0f)
        advance(800)
        assertEquals(0f, drawer.offset, 0.5f)
        drawer.close(); advance(800)
        drawer.dragOpen(30f)
        drawer.release(2000f)
        advance(800)
        assertEquals(0f, drawer.offset, 0.5f)
    }

    @Test fun pullingPastOpenPositionMeetsResistance() {
        drawer.dragOpen(260f) // 60 пикселей дальше открытого положения
        assertEquals(20f, drawer.offset, 1f)
    }

    @Test fun edgeTapOpensOnlyWhereNoTileIsUnderFinger() {
        drawer.offset
        val down = touch(MotionEvent.ACTION_DOWN, 10f, 400f)
        assertFalse(drawer.edgeTap(down) { _, _ -> true })
        assertTrue(drawer.edgeTap(down) { _, _ -> false })
        val up = touch(MotionEvent.ACTION_UP, 10f, 400f)
        assertTrue(drawer.edgeTap(up) { _, _ -> false })
        down.recycle(); up.recycle()
        advance(800)
        assertEquals(0f, drawer.offset, 0.5f)
        val far = touch(MotionEvent.ACTION_DOWN, 10f, 50f)
        assertFalse(drawer.edgeTap(far) { _, _ -> false })
        far.recycle()
    }

    @Test fun actionsCloseThePanelAndCallHost() {
        drawer.open(); advance(800)
        val buttons = mutableListOf<View>()
        fun collect(v: View) { if (v is android.widget.Button) buttons += v; if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i)) }
        collect(drawer.panel)
        buttons.first { (it as android.widget.Button).text.contains("Настройки") }.performClick()
        advance(800)
        assertEquals(listOf("settings"), actions)
        assertFalse(drawer.isOpen)
        assertEquals(View.GONE, buttons.first { (it as android.widget.Button).text.contains("админ") }.visibility)
    }
}
