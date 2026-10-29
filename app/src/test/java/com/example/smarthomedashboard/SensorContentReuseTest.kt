package com.example.smarthomedashboard

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import org.junit.Assert.*
import org.junit.Before
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
class SensorContentReuseTest {
    @Before fun pauseFrames() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
    }

    private fun advance(milliseconds: Long) {
        var remaining = milliseconds
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            remaining -= step
        }
    }

    private val context = RuntimeEnvironment.getApplication()
    private val values = mutableMapOf("sensor.a" to "1", "sensor.b" to "2", "sensor.c" to "3")
    private val tile = TileEntity("card", "sensor", "grid", "Header", 0, 0, 1, 1)
    private class Container(context: android.content.Context) : LinearLayout(context) {
        var layouts = 0
        override fun requestLayout() { layouts++; super.requestLayout() }
    }
    private fun container() = Container(context).apply { addView(TextView(context).apply { text = "Header" }) }
    private fun render(container: LinearLayout, ids: List<String> = listOf("sensor.a", "sensor.b"),
                       font: Int = 16, format: (String, Int, String) -> String = { value, _, _ -> value }) {
        SensorContentRenderer.render(tile.copy(fontSize = font), ids, false, container,
            { _, _ -> ids.map { SensorConfig(it, it.substringAfter('.'), 0) } }, values::get, format)
    }
    private fun row(container: LinearLayout, index: Int) = container.getChildAt(index + 1) as TextView
    private fun watch(view: TextView, changed: () -> Unit) = view.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = changed()
    })

    @Test fun unchangedStructureKeepsRowsAndUpdatesOnlyChangedText() {
        val container = container()
        render(container)
        val a = row(container, 0)
        val b = row(container, 1)
        val params = a.layoutParams
        var aChanges = 0
        var bChanges = 0
        watch(a) { aChanges++ }
        watch(b) { bChanges++ }
        values["sensor.a"] = "4"
        render(container)
        assertSame(a, row(container, 0))
        assertSame(b, row(container, 1))
        assertSame(params, a.layoutParams)
        assertEquals("a  4", a.text.toString())
        assertEquals(1, aChanges)
        assertEquals(0, bChanges)
    }

    @Test fun identicalRepeatedUpdatesDoNotAddViewsOrRequestLayout() {
        val container = container()
        render(container)
        var hierarchyChanges = 0
        container.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) { hierarchyChanges++ }
            override fun onChildViewRemoved(parent: View?, child: View?) { hierarchyChanges++ }
        })
        container.layouts = 0
        repeat(20) { render(container) }
        assertEquals(0, hierarchyChanges)
        assertEquals(0, container.layouts)
        container.setOnHierarchyChangeListener(null)
    }

    @Test fun changedStructureAddsAndRemovesOnlyRequiredRows() {
        val container = container()
        render(container)
        val header = container.getChildAt(0)
        val first = row(container, 0)
        val removed = row(container, 1)
        render(container, listOf("sensor.a"))
        assertSame(header, container.getChildAt(0))
        assertSame(first, row(container, 0))
        assertNull(removed.parent)
        render(container, listOf("sensor.b", "sensor.a", "sensor.c"))
        assertEquals(listOf("b  2", "a  1", "c  3"), (1 until container.childCount).map { (container.getChildAt(it) as TextView).text.toString() })
        assertSame(first, row(container, 0))
        render(container, emptyList())
        assertEquals(1, container.childCount)
        assertSame(header, container.getChildAt(0))
    }

    @Test fun fontChangeKeepsOwnedViewAndExistingAppearance() {
        val container = container()
        render(container)
        val first = row(container, 0)
        render(container, font = 22)
        assertSame(first, row(container, 0))
        assertEquals(22f * context.resources.displayMetrics.scaledDensity, first.textSize, 0.001f)
        assertEquals(Color.WHITE, first.currentTextColor)
        assertEquals(Gravity.CENTER, first.gravity)
        assertEquals(4f, first.shadowRadius, 0f)
        assertEquals(-1, first.layoutParams.width)
        assertEquals(-2, first.layoutParams.height)
    }

    @Test fun formatterFailureLeavesAllExistingRowsAndTextUntouched() {
        val container = container()
        render(container)
        val first = row(container, 0)
        val second = row(container, 1)
        values["sensor.a"] = "changed"
        assertThrows(IllegalArgumentException::class.java) {
            render(container, format = { value, _, id -> if (id == "sensor.b") throw IllegalArgumentException("artificial-format-error") else value })
        }
        assertSame(first, row(container, 0))
        assertSame(second, row(container, 1))
        assertEquals("a  1", first.text.toString())
        assertEquals("b  2", second.text.toString())
    }

    @Test fun updatingDuringParentAnimationKeepsRowsAndDoesNotCancelAnimation() {
        val container = container()
        val parent = FrameLayout(context)
        parent.addView(container, FrameLayout.LayoutParams(300, 180))
        render(container)
        val first = row(container, 0)
        val geometry = container.layoutParams
        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            addUpdateListener { parent.translationX = (it.animatedValue as Float) * 100f }
            start()
        }
        try {
            advance(100)
            values["sensor.a"] = "8"
            render(container)
            assertSame(first, row(container, 0))
            assertSame(geometry, container.layoutParams)
            assertTrue(animation.isStarted)
            advance(1100)
            assertEquals(100f, parent.translationX, 0.01f)
        } finally { animation.cancel() }
    }
}
