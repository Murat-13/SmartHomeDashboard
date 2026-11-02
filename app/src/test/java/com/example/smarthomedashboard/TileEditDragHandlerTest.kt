package com.example.smarthomedashboard

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class TileEditDragHandlerTest {
    private val view = View(RuntimeEnvironment.getApplication()).apply {
        layout(0, 0, 100, 80)
        x = 20f
        y = 30f
        alpha = 0.7f
        elevation = 11f
    }
    private var taps = 0
    private val drops = mutableListOf<Pair<Int, Int>>()
    private var saveAllowed = true
    private val edges = mutableListOf<Int>()
    private val drag = TileEditDragHandler(view, Handler(Looper.getMainLooper()), 400, 300,
        { taps++ }, { x, y -> drops.add(x to y); saveAllowed }, { edges += it })

    private fun send(action: Int, x: Float = 0f, y: Float = 0f) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        try { drag.onTouch(event) } finally { event.recycle() }
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    @Test fun shortTapOpensSettingsOnceWithoutSaving() {
        send(MotionEvent.ACTION_DOWN)
        send(MotionEvent.ACTION_UP)
        send(MotionEvent.ACTION_UP)
        advance(600)
        assertEquals(1, taps)
        assertTrue(drops.isEmpty())
        assertEquals(0.7f, view.alpha, 0f)
    }

    @Test fun movementBeforeDelayCancelsDragWithoutOpeningSettings() {
        send(MotionEvent.ACTION_DOWN)
        advance(499)
        send(MotionEvent.ACTION_MOVE, 11f)
        advance(100)
        send(MotionEvent.ACTION_UP, 11f)
        assertEquals(20f, view.x, 0f)
        assertEquals(0.7f, view.alpha, 0f)
        assertEquals(0, taps)
        assertTrue(drops.isEmpty())
    }

    @Test fun delayedDragClampsAndSavesOnce() {
        send(MotionEvent.ACTION_DOWN)
        advance(500)
        send(MotionEvent.ACTION_MOVE, 900f, 900f)
        send(MotionEvent.ACTION_UP, 900f, 900f)
        send(MotionEvent.ACTION_UP)
        assertEquals(listOf(300 to 160), drops)
        assertEquals(1f, view.alpha, 0f)
        assertEquals(8f, view.elevation, 0f)
        assertEquals(0, taps)
    }

    @Test fun rejectedDropReturnsTileToGestureStart() {
        saveAllowed = false
        send(MotionEvent.ACTION_DOWN)
        advance(500)
        send(MotionEvent.ACTION_MOVE, 100f, 50f)
        send(MotionEvent.ACTION_UP, 100f, 50f)
        assertEquals(listOf(120 to 80), drops)
        assertEquals(20f, view.x, 0f)
        assertEquals(30f, view.y, 0f)
        assertEquals(1f, view.alpha, 0f)
        assertEquals(0, taps)
    }

    @Test fun holdingAtEdgeAsksForNeighbourPageOnlyAfterDelay() {
        send(MotionEvent.ACTION_DOWN)
        advance(500)
        send(MotionEvent.ACTION_MOVE, 900f, 0f)
        advance(TileEditDragHandler.EDGE_HOLD_MS - 100)
        send(MotionEvent.ACTION_MOVE, 100f, 0f) // ушли от края — отсчёт сброшен
        advance(1000)
        assertTrue(edges.isEmpty())
        send(MotionEvent.ACTION_MOVE, -900f, 0f)
        advance(TileEditDragHandler.EDGE_HOLD_MS + 50)
        assertEquals(listOf(-1), edges)
        send(MotionEvent.ACTION_UP, -900f, 0f)
    }

    @Test fun disposalRestoresActiveGestureAndIgnoresRetainedListener() {
        send(MotionEvent.ACTION_DOWN)
        advance(500)
        send(MotionEvent.ACTION_MOVE, 50f, 50f)
        drag.dispose()
        drag.dispose()
        send(MotionEvent.ACTION_UP)
        send(MotionEvent.ACTION_DOWN)
        advance(1000)
        assertEquals(20f, view.x, 0f)
        assertEquals(30f, view.y, 0f)
        assertEquals(0.7f, view.alpha, 0f)
        assertEquals(11f, view.elevation, 0f)
        assertEquals(0, taps)
        assertTrue(drops.isEmpty())
    }
}
