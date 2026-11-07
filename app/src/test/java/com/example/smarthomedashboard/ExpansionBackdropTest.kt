package com.example.smarthomedashboard

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RenderEffect
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
 * Раскрытие Modern-карточки без покадрового размытия (0.9.14, замер на Huawei MatePad SE): фон размывается
 * один раз (снимок), слой над панелью только меняет прозрачность; карточка на время движения — аппаратный слой;
 * после закрытия/очистки слоя и снимка нет, bitmap освобождён, RenderEffect на панель не ставится.
 */
@RunWith(RobolectricTestRunner::class)
// Размытие только с API 31; 34 уже есть в наборе — новый уровень SDK держал бы в памяти ещё одну копию Android (OOM в полном прогоне).
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ExpansionBackdropTest {
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var panel: CountingPanel
    private lateinit var source: View
    private lateinit var backdrop: FakeBackdrop
    private val handler = Handler(Looper.getMainLooper())
    private val target = BatteryModernExpansion.Bounds(100f, 50f, 1000, 800)
    private val expansions = mutableListOf<BatteryModernExpansion>()

    /** Панель, считающая установки RenderEffect (раньше — на каждом кадре анимации). */
    class CountingPanel(context: Context) : FrameLayout(context) {
        var renderEffects = 0
        override fun setRenderEffect(renderEffect: RenderEffect?) {
            renderEffects++
            super.setRenderEffect(renderEffect)
        }
    }

    /**
     * Снимок по требованию теста: ответ приходит, когда тест вызовет [deliver]. [copied] — успела ли копия
     * кадра к первому кадру раскрытия; [awaits] — сколько раз главный поток её дожидался.
     */
    internal class FakeBackdrop : ExpansionBackdrop {
        val holes = mutableListOf<Rect>()
        private val pending = mutableListOf<(Bitmap?) -> Unit>()
        var copied = true
        var awaits = 0
        override fun capture(panel: View, hole: Rect, done: (Bitmap?) -> Unit): ExpansionBackdrop.Pending {
            holes += hole
            pending += done
            return ExpansionBackdrop.Pending { awaits++; copied }
        }
        fun deliver(bitmap: Bitmap?) = pending.removeAt(0).invoke(bitmap)
        val waiting get() = pending.size
    }

    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        root = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(0x1A, 0x1A, 0x1A)) }
        panel = CountingPanel(activity)
        root.addView(panel, FrameLayout.LayoutParams(1200, 900))
        root.addView(View(activity).apply { tag = "above" }, FrameLayout.LayoutParams(10, 10))
        source = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(300, 220)
            x = 40f
            y = 60f
        }
        panel.addView(source)
        activity.setContentView(root, android.view.ViewGroup.LayoutParams(1200, 900))
        shadowOf(Looper.getMainLooper()).idle()
        backdrop = FakeBackdrop()
    }

    @After fun tearDown() = expansions.forEach { it.clear() }

    private fun expansion(blur: Boolean = true) =
        BatteryModernExpansion(handler, { root }, { panel }, blurSupported = blur, backdrop = backdrop).also { expansions += it }

    private fun advance(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            remaining -= step
        }
    }

    private fun bitmap() = Bitmap.createBitmap(300, 225, Bitmap.Config.ARGB_8888)
    private fun scrim() = (0 until root.childCount).map { root.getChildAt(it) }.single { it.tag == "bm_scrim" }
    private fun backdropLayers() = (0 until root.childCount).map { root.getChildAt(it) }.filter { it.tag == BatteryModernExpansion.BACKDROP_TAG }

    @Test fun cardIsHardwareLayerOnlyWhileMoving() {
        val expansion = expansion()
        val card = View(activity)
        expansion.expand("m", source, card, target)
        advance(48)
        assertEquals("раскрытие: аппаратный слой", View.LAYER_TYPE_HARDWARE, card.layerType)
        advance(400)
        assertEquals("после раскрытия слой снят", View.LAYER_TYPE_NONE, card.layerType)
        expansion.collapse()
        advance(48)
        assertEquals("сворачивание: аппаратный слой", View.LAYER_TYPE_HARDWARE, card.layerType)
        advance(400)
        assertNull(card.parent)
        assertEquals("после закрытия слой снят", View.LAYER_TYPE_NONE, card.layerType)
    }

    @Test fun collapseInTheMiddleOfExpandLeavesNoLayerAndNoSnapshot() {
        val expansion = expansion()
        val card = View(activity)
        expansion.expand("m", source, card, target)
        advance(96)
        expansion.collapse()
        advance(400)
        assertNull(card.parent)
        assertEquals(View.LAYER_TYPE_NONE, card.layerType)
        // Снимок пришёл уже после закрытия: слой не добавляется, bitmap освобождён.
        val late = bitmap()
        backdrop.deliver(late)
        assertTrue(late.isRecycled)
        assertTrue(backdropLayers().isEmpty())
        assertNull(expansion.backdropView)
        assertEquals(0, panel.renderEffects)
    }

    @Test fun snapshotLayerSitsAbovePanelFadesWithAnimationAndIsReleased() {
        val expansion = expansion()
        val card = View(activity)
        expansion.expand("m", source, card, target)
        assertEquals("снимок запрошен один раз", 1, backdrop.holes.size)
        assertEquals("место исходной плитки закрашивается", Rect(40, 60, 340, 280), backdrop.holes.single())
        advance(32)
        val snapshot = bitmap()
        backdrop.deliver(snapshot)
        val layer = backdropLayers().single()
        assertEquals("слой сразу над панелью, ниже остального", root.indexOfChild(panel) + 1, root.indexOfChild(layer))
        assertEquals(1200, layer.layoutParams.width)
        assertEquals(900, layer.layoutParams.height)
        val firstAlpha = layer.alpha
        advance(400)
        assertTrue("слой проявился: $firstAlpha → ${layer.alpha}", firstAlpha < 0.5f && layer.alpha == 1f)
        assertEquals("затемнение как при размытии", BatteryModernExpansion.DIM_WITH_BLUR, scrim().alpha, 0.001f)

        expansion.collapse()
        advance(96)
        assertTrue("слой гаснет: ${layer.alpha}", layer.alpha < 1f && layer.alpha > 0f)
        advance(400)
        assertNull("слой удалён", layer.parent)
        assertTrue("снимок освобождён", snapshot.isRecycled)
        assertNull(expansion.backdropView)
        assertEquals("RenderEffect на панель не ставится", 0, panel.renderEffects)
    }

    @Test fun snapshotArrivingAfterOpenFadesInShortly() {
        val expansion = expansion()
        expansion.expand("m", source, View(activity), target)
        advance(400)
        backdrop.deliver(bitmap())
        val layer = backdropLayers().single()
        assertEquals(0f, layer.alpha, 0f)
        advance(BatteryModernExpansion.BACKDROP_FADE_MS + 50)
        assertEquals(1f, layer.alpha, 0f)
        assertTrue("таймер сворачивания не сброшен", expansion.collapseAt != null)
    }

    @Test fun failedSnapshotFallsBackToDimOnly() {
        val expansion = expansion()
        expansion.expand("m", source, View(activity), target)
        advance(32)
        backdrop.deliver(null)
        advance(400)
        assertTrue(backdropLayers().isEmpty())
        assertEquals(BatteryModernExpansion.DIM_ONLY, scrim().alpha, 0.001f)
        assertEquals(0, panel.renderEffects)
    }

    @Test fun withoutBlurSupportOnlyDims() {
        val expansion = expansion(blur = false)
        expansion.expand("m", source, View(activity), target)
        advance(400)
        assertEquals("снимок не запрашивается", 0, backdrop.waiting)
        assertTrue(backdropLayers().isEmpty())
        assertEquals(BatteryModernExpansion.DIM_ONLY, scrim().alpha, 0.001f)
        assertEquals(0, panel.renderEffects)
    }

    @Test fun clearReleasesSnapshotAndRestoresTile() {
        val expansion = expansion()
        val card = View(activity)
        expansion.expand("m", source, card, target)
        advance(32)
        val snapshot = bitmap()
        backdrop.deliver(snapshot)
        advance(64)
        expansion.clear()
        assertTrue(backdropLayers().isEmpty())
        assertTrue(snapshot.isRecycled)
        assertNull(card.parent)
        assertEquals(View.LAYER_TYPE_NONE, card.layerType)
        assertEquals(1f, source.alpha, 0f)
    }

    @Test fun reopeningReplacesSnapshotWithoutLeaks() {
        val expansion = expansion()
        expansion.expand("a", source, View(activity), target)
        advance(32)
        val first = bitmap()
        backdrop.deliver(first)
        advance(400)
        expansion.expand("b", source, View(activity), target)
        assertTrue("прежний снимок освобождён", first.isRecycled)
        advance(32)
        backdrop.deliver(bitmap())
        advance(400)
        assertEquals("один слой", 1, backdropLayers().size)
    }

    @Test fun prefetchStartsSnapshotBeforeCardAndExpandReusesIt() {
        val expansion = expansion()
        expansion.prefetch(source)
        assertEquals("снимок начат до сборки карточки", 1, backdrop.holes.size)
        assertEquals(Rect(40, 60, 340, 280), backdrop.holes.single())
        expansion.expand("m", source, View(activity), target)
        assertEquals("второй копии нет", 1, backdrop.holes.size)
        assertEquals("до первого кадра копия дождана", 1, backdrop.awaits)
        advance(32)
        val snapshot = bitmap()
        backdrop.deliver(snapshot)
        assertEquals("слой из заранее начатого снимка", 1, backdropLayers().size)
        advance(400)
        assertEquals(1f, backdropLayers().single().alpha, 0f)
        expansion.collapse()
        advance(400)
        assertTrue(snapshot.isRecycled)
        assertTrue(backdropLayers().isEmpty())
    }

    @Test fun prefetchedSnapshotReadyBeforeExpandIsShownWithAnimation() {
        advance(16) // панель разложена, как в приложении к моменту касания
        val expansion = expansion()
        expansion.prefetch(source)
        val snapshot = bitmap()
        backdrop.deliver(snapshot)
        assertTrue("до раскрытия слоя нет", backdropLayers().isEmpty())
        expansion.expand("m", source, View(activity), target)
        val layer = backdropLayers().single()
        assertEquals("проявляется с начала раскрытия", 0f, layer.alpha, 0f)
        advance(400)
        assertEquals(1f, layer.alpha, 0f)
        assertEquals(BatteryModernExpansion.DIM_WITH_BLUR, scrim().alpha, 0.001f)
        expansion.clear()
        assertTrue(snapshot.isRecycled)
    }

    @Test fun copyNotFinishedBeforeFirstFrameIsDropped() {
        val expansion = expansion()
        backdrop.copied = false
        expansion.prefetch(source)
        expansion.expand("m", source, View(activity), target)
        advance(32)
        val late = bitmap()
        backdrop.deliver(late)
        assertTrue("снимок мог захватить карточку — отброшен", late.isRecycled)
        assertTrue(backdropLayers().isEmpty())
        advance(400)
        assertEquals("только затемнение", BatteryModernExpansion.DIM_ONLY, scrim().alpha, 0.001f)
    }

    @Test fun prefetchForAnotherTileOrWithoutExpandIsReleased() {
        val expansion = expansion()
        val other = View(activity).apply { layoutParams = FrameLayout.LayoutParams(100, 100) }
        panel.addView(other)
        expansion.prefetch(other)
        val stale = bitmap()
        backdrop.deliver(stale)
        expansion.expand("m", source, View(activity), target)
        assertTrue("снимок другой плитки освобождён", stale.isRecycled)
        assertEquals("для раскрытия — свой снимок", 2, backdrop.holes.size)
        expansion.clear()
        expansion.prefetch(source)
        expansion.clear()
        val dropped = bitmap()
        backdrop.deliver(bitmap().also { it.recycle() })
        backdrop.deliver(dropped)
        assertTrue("clear отменяет заранее начатый снимок", dropped.isRecycled)
        assertTrue(backdropLayers().isEmpty())
    }

    @Test fun boxBlurRadiusMatchesPreviousRenderEffect() {
        // RenderEffect 28 px → сигма 16,7; на 1/4 — 4,2; три прохода r = 4 дают сигму √20 ≈ 4,5.
        assertEquals(4, BoxBlur.boxRadiusFor(BatteryModernExpansion.BLUR_RADIUS, 0.25f))
        assertEquals(0, BoxBlur.boxRadiusFor(0f, 0.25f))
    }

    @Test fun boxBlurKeepsFlatColorAndSoftensEdges() {
        val flat = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(0x1A, 0x1A, 0x1A)) }
        BoxBlur.blur(flat, 4)
        assertEquals(Color.rgb(0x1A, 0x1A, 0x1A), flat.getPixel(0, 0))
        assertEquals(Color.rgb(0x1A, 0x1A, 0x1A), flat.getPixel(39, 19))

        val edge = Bitmap.createBitmap(40, 10, Bitmap.Config.ARGB_8888)
        for (x in 0 until 40) for (y in 0 until 10) edge.setPixel(x, y, if (x < 20) Color.BLACK else Color.WHITE)
        BoxBlur.blur(edge, 4)
        val mid = Color.red(edge.getPixel(20, 5))
        assertTrue("граница размыта: $mid", mid in 60..200)
        assertEquals("далеко от границы цвет прежний", 0, Color.red(edge.getPixel(0, 5)))
        assertEquals(255, Color.red(edge.getPixel(39, 5)))
    }

    @Test fun backgroundColorComesFromFirstSolidAncestor() {
        assertEquals(Color.rgb(0x1A, 0x1A, 0x1A), PixelCopyBackdrop.backgroundColor(source))
    }
}
