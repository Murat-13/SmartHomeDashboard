package com.example.smarthomedashboard

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SensorWindowTouchHandlerTest {
    @Test fun clearCancelsForwardedGestureAndSwallowsItsRemainder() {
        val source = FrameLayout(RuntimeEnvironment.getApplication()).apply { layout(0, 0, 200, 200) }
        val actions = mutableListOf<Int>()
        source.setOnTouchListener { _, event -> actions.add(event.actionMasked); true }
        var closed = 0
        val handler = SensorWindowTouchHandler({ source }, { true }, {}, {}, { closed++ }, 5)
        fun event(action: Int, x: Float, time: Long): Boolean? {
            val event = MotionEvent.obtain(0, time, action, x, 20f, 0)
            return try { handler.dispatch(event) } finally { event.recycle() }
        }
        assertEquals(true, event(MotionEvent.ACTION_DOWN, 20f, 0))
        event(MotionEvent.ACTION_MOVE, 50f, 10)
        handler.clear()
        assertTrue(actions.contains(MotionEvent.ACTION_CANCEL))
        assertEquals(true, event(MotionEvent.ACTION_UP, 50f, 20))
        assertEquals(0, closed)
        event(MotionEvent.ACTION_DOWN, 20f, 30)
        event(MotionEvent.ACTION_UP, 20f, 40)
        assertEquals(1, closed)
        handler.clear()
    }

    @Test fun absentCardLeavesWindowDispatchUntouched() {
        val handler = SensorWindowTouchHandler({ null }, { false }, {}, {}, {}, 5)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        try { assertNull(handler.dispatch(event)) } finally { event.recycle() }
    }
}
