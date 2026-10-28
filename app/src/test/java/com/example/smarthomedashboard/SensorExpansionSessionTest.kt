package com.example.smarthomedashboard

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

// Только переходы настоящих Views и виртуальное время, без Activity/network.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SensorExpansionSessionTest {
    private val handler = Handler(Looper.getMainLooper())
    private val session = SensorExpansionSession(handler)
    private fun view(w: Int = 220, h: Int = 180) = View(RuntimeEnvironment.getApplication()).apply {
        layoutParams = FrameLayout.LayoutParams(w, h)
        x = 40f; y = 100f
    }
    private val target = SensorExpansionSession.Bounds(400, 350, 10f, 20f)
    @Before fun pauseFrames() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
    }
    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            left -= step
        }
    }
    private fun animations() = ArchitectureTestAccess.getField<Map<View, ValueAnimator>>(session, "animations")

    @Test fun closeKeepsOwnSnapshotWhileAnotherCardOpens() {
        val a = view()
        val b = view(300, 230)
        val originalA = SensorExpansionSession.Bounds.of(a)
        var closed = 0
        session.expand(a, target) {}
        advance(400)
        session.collapse { assertSame(a, it); closed++ }
        assertNull(session.current)
        assertNull(session.original)
        advance(32)
        session.expand(b, target) {}
        advance(400)
        assertEquals(originalA, SensorExpansionSession.Bounds.of(a))
        assertSame(b, session.current)
        assertEquals(1, closed)
        session.clear()
    }

    @Test fun reverseOnSameViewStopsPreviousAnimator() {
        val card = view()
        val original = SensorExpansionSession.Bounds.of(card)
        session.expand(card, target) {}
        advance(32)
        val opening = animations().getValue(card)
        session.collapse {}
        assertFalse(opening.isRunning)
        assertEquals(1, animations().size)
        advance(400)
        assertEquals(original, SensorExpansionSession.Bounds.of(card))
        assertTrue(animations().isEmpty())
    }

    @Test fun clearNeutralizesRetainedAnimationAndDoesNotClearForeignHandlerWork() {
        val card = view()
        var timedOut = 0
        var foreignRan = false
        handler.postDelayed({ foreignRan = true }, 1000)
        session.expand(card, target) { timedOut++ }
        advance(32)
        val old = animations().getValue(card)
        session.clear()
        session.clear()
        val stopped = SensorExpansionSession.Bounds.of(card)
        old.end()
        advance(31000)
        assertEquals(stopped, SensorExpansionSession.Bounds.of(card))
        assertEquals(0, timedOut)
        assertTrue(foreignRan)
        assertNull(session.current)
        assertNull(session.original)
        assertTrue(animations().isEmpty())
    }

    @Test fun touchExtendsOnlyCurrentTimerAndCloseCancelsIt() {
        var timedOut = 0
        session.expand(view(), target) { timedOut++ }
        advance(20000)
        session.touch()
        advance(20000)
        assertEquals(0, timedOut)
        advance(10000)
        assertEquals(1, timedOut)
        session.collapse {}
        advance(31000)
        assertEquals(1, timedOut)
        session.clear()
    }

    @Test fun clearDuringClosingSuppressesEndCallbackAndAllowsNewExpansion() {
        val a = view()
        var closed = 0
        session.expand(a, target) {}
        advance(400)
        session.collapse { closed++ }
        advance(32)
        session.clear()
        val b = view()
        session.expand(b, target) {}
        advance(400)
        assertEquals(0, closed)
        assertSame(b, session.current)
        session.clear()
    }
}

// Фикстуры читают нового владельца; старые assertions состояния/геометрии не меняются.
internal fun sensorSessionFixture(activity: MainActivity): SensorExpansionSession =
    ArchitectureTestAccess.getField(activity, "sensorExpansion")
