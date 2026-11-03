package com.example.smarthomedashboard

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Конечные кадры раскрытия/закрытия (0.9.2, этап 5): к моменту удаления большой карточки она уже невидима, а
 * компактная плитка полностью видна на прежнем месте; прерванное раскрытие закрывается без скачка масштаба;
 * тень сенсорной карточки меняется плавно. Окончательную плавность подтверждает только планшет.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ExpansionSmoothnessTest {
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var panel: FrameLayout
    private lateinit var source: View
    private lateinit var expansion: BatteryModernExpansion
    private val handler = Handler(Looper.getMainLooper())
    private val target = BatteryModernExpansion.Bounds(100f, 50f, 1000, 800)

    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        root = FrameLayout(activity)
        panel = FrameLayout(activity)
        root.addView(panel, FrameLayout.LayoutParams(1200, 900))
        source = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(300, 220)
            x = 40f
            y = 60f
        }
        panel.addView(source)
        activity.setContentView(root, android.view.ViewGroup.LayoutParams(1200, 900))
        expansion = BatteryModernExpansion(handler, { root }, { panel }, blurSupported = false)
    }

    @After fun tearDown() = expansion.clear()

    private fun advance(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            remaining -= step
        }
    }

    private fun card() = View(activity)

    @Test fun crossfadeCurveStartsLateAndEndsComplete() {
        assertEquals(0f, BatteryModernExpansion.crossfade(0f), 0f)
        assertEquals(0f, BatteryModernExpansion.crossfade(BatteryModernExpansion.CROSSFADE_FROM), 0f)
        assertEquals(0.5f, BatteryModernExpansion.crossfade(0.8f), 0.001f)
        assertEquals(1f, BatteryModernExpansion.crossfade(1f), 0f)
    }

    @Test fun closeEndsWithInvisibleCardOverFullyVisibleTileAtSameBounds() {
        val card = card()
        expansion.expand("m", source, card, target)
        advance(400)
        assertEquals(1f, card.alpha, 0f)
        assertEquals(0f, source.alpha, 0f)
        val elevation = card.elevation

        expansion.collapse()
        assertEquals("первый кадр закрытия совпадает с открытым состоянием", 1f, card.scaleX, 0.001f)
        assertEquals(elevation, card.elevation, 0.001f)
        advance(32)
        assertEquals("в начале закрытия плитка ещё скрыта", 0f, source.alpha, 0f)
        // Покадрово до удаления: запоминаем последний видимый кадр большой карточки.
        var last = Triple(card.alpha, source.alpha, card.elevation)
        var lastX = card.x
        var lastScale = card.scaleX
        var frames = 0
        while (card.parent != null && frames++ < 60) {
            last = Triple(card.alpha, source.alpha, card.elevation)
            lastX = card.x
            lastScale = card.scaleX
            advance(16)
        }
        assertNull("карточка снята", card.parent)
        assertTrue("перед удалением карточка почти прозрачна: ${last.first}", last.first < 0.25f)
        assertTrue("перед удалением плитка почти видна: ${last.second}", last.second > 0.75f)
        assertTrue("тень почти погасла: ${last.third}", last.third < elevation * 0.25f)
        // Уменьшенная карточка совпадает с прямоугольником плитки (без сдвига в конце).
        assertEquals(source.x, lastX, 6f)
        assertEquals(300f, 1000 * lastScale, 6f)
        assertEquals(1f, source.alpha, 0f)
        assertEquals(40f, source.x, 0f)
        assertEquals(60f, source.y, 0f)
        assertEquals(300, source.layoutParams.width)
        assertEquals(220, source.layoutParams.height)
    }

    @Test fun closingDuringOpenStartsFromCurrentFrameWithoutJump() {
        val card = card()
        expansion.expand("m", source, card, target)
        advance(96)
        val scale = card.scaleX
        val x = card.x
        assertTrue("раскрытие прервано посередине: $scale", scale > 0.3f && scale < 1f)
        expansion.collapse()
        assertEquals("масштаб не прыгает к полному", scale, card.scaleX, 0.001f)
        assertEquals(x, card.x, 0.001f)
        advance(400)
        assertNull(card.parent)
        assertEquals(1f, source.alpha, 0f)
        assertEquals(40f, source.x, 0f)
    }

    @Test fun reopeningRightAfterCloseLeavesConsistentState() {
        val first = card()
        expansion.expand("m", source, first, target)
        advance(400)
        expansion.collapse()
        advance(80)
        val second = card()
        expansion.expand("m", source, second, target)
        assertNull("прежняя карточка снята", first.parent)
        advance(400)
        assertTrue(expansion.isOpen)
        assertEquals(1f, second.scaleX, 0.001f)
        assertEquals(0f, source.alpha, 0f)
    }

    @Test fun groupCloseFadesCopyAndChildrenBeforeRemoval() {
        val items = listOf(ModernTileView.Item(ModernIcon.POWER, ConfirmedState.OFF), ModernTileView.Item(ModernIcon.POWER, ConfirmedState.ON))
        val parent = ModernTileView.Model("Группа", ModernIcon.POWER, ConfirmedState.ON, items = items)
        val host = object : ModernGroupPanel.Host {
            override fun childModel(tileId: String, entityId: String, name: String) =
                ModernTileView.Model(name, ModernIcon.POWER, ConfirmedState.OFF)
            override fun parentModel(tileId: String) = parent
            override fun childTapped(tileId: String, entityId: String, view: View) = Unit
            override fun closed(tileId: String) = Unit
        }
        val tile = ModernTileView(activity, ModernTileView.Kind.GROUP).apply {
            bind(parent, animate = false)
            x = 40f
            y = 60f
        }
        panel.addView(tile, FrameLayout.LayoutParams(300, 220))
        advance(32)
        val group = ModernGroupPanel(activity, { root }, host)
        group.open("g", "Группа", listOf(ModernGroupPanel.Entry("light.a", "A"), ModernGroupPanel.Entry("light.b", "B")),
            tile, animate = true)
        advance(600)
        val copy = group.parentCopy!!
        val children = group.children.toList()
        assertTrue(copy.thumbnailsHidden)
        assertEquals(1f, copy.alpha, 0f)
        children.forEach { assertEquals(1f, it.alpha, 0.01f) }

        group.close()
        var copyAlpha = copy.alpha
        var childAlpha = children.maxOf { it.alpha }
        var thumbnailsShown = false
        var frames = 0
        while (copy.parent != null && frames++ < 60) {
            copyAlpha = copy.alpha
            childAlpha = children.maxOf { it.alpha }
            thumbnailsShown = !copy.thumbnailsHidden
            advance(16)
        }
        assertNull("копия снята", copy.parent)
        assertTrue("анимация шла несколько кадров: $frames", frames > 5)
        assertTrue("миниатюры показаны до удаления копии", thumbnailsShown)
        assertTrue("перед удалением копия практически растворилась: $copyAlpha", copyAlpha < 0.4f)
        assertTrue("перед удалением кнопки погасли: $childAlpha", childAlpha < 0.4f)
        assertNull(group.panelView)
        assertEquals("исходная плитка на месте и видна", 1f, tile.alpha, 0f)
    }

    @Test fun sensorCardShadowFollowsMotionAndBoundsReturnExactly() {
        val session = SensorExpansionSession(handler)
        val tile = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(300, 220)
            x = 40f
            y = 60f
            elevation = SensorExpansionSession.COLLAPSED_ELEVATION
        }
        panel.addView(tile)
        session.expand(tile, SensorExpansionSession.Bounds(800, 600, 100f, 50f)) {}
        advance(48)
        assertTrue("тень растёт вместе с размером: ${tile.elevation}",
            tile.elevation > SensorExpansionSession.COLLAPSED_ELEVATION && tile.elevation < SensorExpansionSession.EXPANDED_ELEVATION)
        advance(400)
        assertEquals(SensorExpansionSession.EXPANDED_ELEVATION, tile.elevation, 0.001f)
        assertEquals(800, tile.layoutParams.width)

        var closed = false
        session.collapse { closed = true }
        assertEquals("начало закрытия без скачка тени", SensorExpansionSession.EXPANDED_ELEVATION, tile.elevation, 0.001f)
        advance(400)
        assertTrue(closed)
        assertEquals(SensorExpansionSession.COLLAPSED_ELEVATION, tile.elevation, 0.001f)
        assertEquals(300, tile.layoutParams.width)
        assertEquals(220, tile.layoutParams.height)
        assertEquals(40f, tile.x, 0f)
        assertEquals(60f, tile.y, 0f)
    }

    @Test fun sensorCollapseDuringExpandReturnsToOriginalBounds() {
        val session = SensorExpansionSession(handler)
        val tile = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(300, 220)
            x = 40f
            y = 60f
            elevation = SensorExpansionSession.COLLAPSED_ELEVATION
        }
        panel.addView(tile)
        session.expand(tile, SensorExpansionSession.Bounds(800, 600, 100f, 50f)) {}
        advance(64)
        session.collapse {}
        advance(400)
        assertEquals(300, tile.layoutParams.width)
        assertEquals(220, tile.layoutParams.height)
        assertEquals(40f, tile.x, 0f)
        assertEquals(SensorExpansionSession.COLLAPSED_ELEVATION, tile.elevation, 0.001f)
    }
}
