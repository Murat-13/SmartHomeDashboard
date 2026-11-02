package com.example.smarthomedashboard

import android.os.SystemClock
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Распознаватель жеста двумя пальцами и пружина доведения. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NavigationGestureTest {
    private val log = mutableListOf<String>()
    private var accept = true
    private var enabled = true
    private val swipe = TwoFingerSwipe(8, { enabled }, object : TwoFingerSwipe.Listener {
        override fun onStart(direction: Int): Boolean { log += "start $direction"; return accept }
        override fun onClaim() { log += "claim" }
        override fun onDrag(dx: Float) { log += "drag" }
        override fun onEnd(dx: Float, velocityX: Float) { log += "end ${dx.toInt()}" }
        override fun onCancel() { log += "cancel" }
    })

    private fun event(action: Int, vararg points: Pair<Float, Float>): Boolean {
        val properties = Array(points.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(points.size) { i -> MotionEvent.PointerCoords().apply { x = points[i].first; y = points[i].second; pressure = 1f; size = 1f } }
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, action, points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        return try { swipe.onTouch(e) } finally { e.recycle() }
    }
    private val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private val secondUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun twoFingers(dx: Float, dy: Float = 0f): List<Boolean> = listOf(
        event(MotionEvent.ACTION_DOWN, 100f to 300f),
        event(secondDown, 100f to 300f, 100f to 400f),
        event(MotionEvent.ACTION_MOVE, 100f + dx / 2 to 300f + dy / 2, 100f + dx / 2 to 400f + dy / 2),
        event(MotionEvent.ACTION_MOVE, 100f + dx to 300f + dy, 100f + dx to 400f + dy),
        event(secondUp, 100f + dx to 300f + dy, 100f + dx to 400f + dy),
        event(MotionEvent.ACTION_UP, 100f + dx to 300f + dy),
    )

    @Test fun oneFingerSwipeNeverBecomesNavigation() {
        assertFalse(event(MotionEvent.ACTION_DOWN, 100f to 300f))
        assertFalse(event(MotionEvent.ACTION_MOVE, 400f to 300f))
        assertFalse(event(MotionEvent.ACTION_UP, 400f to 300f))
        assertTrue(log.isEmpty())
    }

    @Test fun twoFingerHorizontalSwipeIsClaimedAndRestOfGestureConsumed() {
        val consumed = twoFingers(200f)
        assertEquals(listOf(false, false), consumed.take(2)) // до распознавания касания идут плиткам
        assertTrue(consumed.drop(3).all { it })
        assertEquals("start 1", log.first())
        assertEquals("claim", log[1])
        assertEquals("end 200", log.last())
        assertFalse(swipe.active)
    }

    @Test fun leftwardDirectionIsReported() {
        twoFingers(-200f)
        assertEquals("start -1", log.first())
    }

    @Test fun verticalTwoFingerMoveIsNotNavigation() {
        val consumed = twoFingers(0f, 200f)
        assertTrue(consumed.none { it })
        assertTrue(log.isEmpty())
    }

    @Test fun refusedDirectionAndDisabledStateLeaveTouchesToTiles() {
        accept = false
        assertTrue(twoFingers(200f).none { it })
        assertEquals(listOf("start 1"), log)
        log.clear()
        accept = true
        enabled = false
        assertTrue(twoFingers(200f).none { it })
        assertTrue(log.isEmpty())
    }

    @Test fun springSettlesOnTargetWithSmallOvershoot() {
        var state = SpringMotion.State(-300f, 0f)
        var max = state.position
        var frames = 0
        while (!SpringMotion.settled(state, 0f) && frames < 120) {
            state = SpringMotion.step(state, 0f, 1f / 60f)
            max = maxOf(max, state.position)
            frames++
        }
        assertTrue("кадров: $frames", frames < 60) // меньше секунды
        assertTrue("перелёт: $max", max < 300f * 0.05f)
    }
}
