package com.example.smarthomedashboard

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Просьбы владельца 19.11.2026: шкала как у Reolink, увеличение видео пальцами (архив и раскрытый домофон),
 * «◀ Событие ▶», журнал звонков со снимками, быстрые фразы своим голосом.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArchiveExtrasTest {
    private val context = RuntimeEnvironment.getApplication()
    private val day = RecArchive.dayStart(System.currentTimeMillis())

    // ---- Увеличение ----

    @Test fun zoomKeepsFocusClampsEdgesAndToggles() {
        val z = VideoZoom().pinch(500f, 300f, 2f, 0f, 0f, 1000f, 600f)
        assertEquals(2f, z.scale)
        // Точка картинки под пальцами осталась под пальцами.
        assertEquals(500f, (500f - z.x) / z.scale, 0.01f)
        assertEquals(300f, (300f - z.y) / z.scale, 0.01f)
        // Сдвиг дальше края — упирается: чёрных полос от сдвига нет.
        val panned = z.pan(10_000f, 10_000f, 1000f, 600f)
        assertEquals(0f, panned.x); assertEquals(0f, panned.y)
        val other = z.pan(-10_000f, -10_000f, 1000f, 600f)
        assertEquals(-1000f, other.x); assertEquals(-600f, other.y)
        // Больше 5× и меньше 1× — нельзя; меньше 1× — обычный вид.
        assertEquals(VideoZoom.MAX, z.pinch(0f, 0f, 100f, 0f, 0f, 1000f, 600f).scale)
        assertEquals(VideoZoom(), z.pinch(0f, 0f, 0.01f, 0f, 0f, 1000f, 600f))
        // Двойное касание: увеличить место касания, второе — вернуть.
        val tapped = VideoZoom().toggle(800f, 100f, 1000f, 600f)
        assertEquals(VideoZoom.DOUBLE_TAP, tapped.scale)
        assertTrue(tapped.zoomed)
        assertEquals(VideoZoom(), tapped.toggle(1f, 1f, 1000f, 600f))
        assertEquals("×2,5", tapped.title)
        assertEquals("×2", z.title)
    }

    private fun touch(view: View, action: Int, x: Float, y: Float, at: Long) =
        view.dispatchTouchEvent(MotionEvent.obtain(at, at, action, x, y, 0))

    @Test fun expandedDoorbellZoomsWithDoubleTapAndCollapsesOnSingleTap() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val card = DoorbellCard(activity, expanded = true)
        activity.setContentView(card, android.view.ViewGroup.LayoutParams(1000, 750))
        card.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(750, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, 1000, 750)
        var collapsed = 0
        card.onCollapse = { collapsed++ }
        val looper = shadowOf(Looper.getMainLooper())
        // Двойное касание — увеличение (карточка не сворачивается).
        val t = SystemClock.uptimeMillis()
        touch(card, MotionEvent.ACTION_DOWN, 500f, 375f, t); touch(card, MotionEvent.ACTION_UP, 500f, 375f, t + 40)
        touch(card, MotionEvent.ACTION_DOWN, 500f, 375f, t + 120); touch(card, MotionEvent.ACTION_UP, 500f, 375f, t + 160)
        looper.idleFor(1, TimeUnit.SECONDS)
        assertEquals(VideoZoom.DOUBLE_TAP, card.video.scaleX, 0.01f)
        assertEquals(0, collapsed)
        assertTrue("карточка сама разбирает жесты по всей площади", card.interactiveAt(500f, 375f))
        // Увеличенная картинка: одиночное касание не сворачивает.
        val t2 = SystemClock.uptimeMillis() + 2_000
        touch(card, MotionEvent.ACTION_DOWN, 300f, 300f, t2); touch(card, MotionEvent.ACTION_UP, 300f, 300f, t2 + 40)
        looper.idleFor(1, TimeUnit.SECONDS)
        assertEquals(0, collapsed)
        // Двойное касание — обычный вид; затем одиночное — свернуть.
        val t3 = t2 + 2_000
        touch(card, MotionEvent.ACTION_DOWN, 300f, 300f, t3); touch(card, MotionEvent.ACTION_UP, 300f, 300f, t3 + 40)
        touch(card, MotionEvent.ACTION_DOWN, 300f, 300f, t3 + 120); touch(card, MotionEvent.ACTION_UP, 300f, 300f, t3 + 160)
        looper.idleFor(1, TimeUnit.SECONDS)
        assertEquals(1f, card.video.scaleX, 0.01f)
        val t4 = t3 + 2_000
        touch(card, MotionEvent.ACTION_DOWN, 300f, 300f, t4); touch(card, MotionEvent.ACTION_UP, 300f, 300f, t4 + 40)
        looper.idleFor(1, TimeUnit.SECONDS)
        assertEquals(1, collapsed)
        // В окне звонка увеличения нет (решение владельца): касание ведёт себя как раньше.
        card.ringMode = true
        assertFalse(card.interactiveAt(500f, 375f))
        card.dispose()
    }

    @Test fun expandedDoorbellButtonsStillReceiveTaps() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val card = DoorbellCard(activity, expanded = true)
        var calls = 0
        card.onCalls = { calls++ }
        card.onArchive = {}
        activity.setContentView(card, android.view.ViewGroup.LayoutParams(1000, 750))
        card.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(750, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, 1000, 750)
        val button = card.findViewWithTag<View>("door_calls")
        assertEquals(View.VISIBLE, button.visibility)
        val loc = IntArray(2).also { button.getLocationOnScreen(it) }
        val x = loc[0] + button.width / 2f
        val y = loc[1] + button.height / 2f
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0)
        card.dispatchTouchEvent(down); card.dispatchTouchEvent(up)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, calls)
        assertEquals("кнопка — не увеличение", 1f, card.video.scaleX, 0.01f)
        card.dispose()
    }

    // ---- Шкала ----

    @Test fun timelineStepsTicksAndNeighbours() {
        // Сутки на 1000 px, подписи не теснее 64 px → шаг 2 ч; 10 минут → шаг 1 мин.
        assertEquals(2 * 3_600_000L, ArchiveTimeline.step(24 * 3_600_000L, 1000f, 64f))
        assertEquals(60_000L, ArchiveTimeline.step(10 * 60_000L, 1000f, 64f))
        assertEquals(ArchiveTimeline.MIN_SPAN, ArchiveTimeline.clampSpan(1))
        assertEquals(ArchiveTimeline.MAX_SPAN, ArchiveTimeline.clampSpan(Long.MAX_VALUE / 4))
        // Отметки — по местному времени, через полночь.
        val ticks = ArchiveTimeline.ticks(day - 3_600_000L + 1, day + 3_600_000L, 30 * 60_000L)
        assertEquals(listOf(day - 1_800_000L, day, day + 1_800_000L, day + 3_600_000L), ticks)

        fun ev(minute: Int, label: String = "person") = RecArchive.Event(
            RecFile(File("e$minute"), "door", RecFile.Kind.EVENT, day + minute * 60_000L, 30_000, 1), setOf(label))
        val events = listOf(ev(60), ev(120), ev(180))
        val at = day + 130 * 60_000L
        assertEquals(ev(180), ArchiveTimeline.neighbour(events, at, null, forward = true))
        assertEquals(ev(120), ArchiveTimeline.neighbour(events, at, null, forward = false))
        // Из идущего ролика «назад» — к предыдущему событию, а не в начало этого же.
        assertEquals(ev(60), ArchiveTimeline.neighbour(events, day + 120 * 60_000L + 10_000, ev(120), forward = false))
        assertEquals(ev(180), ArchiveTimeline.neighbour(events, day + 120 * 60_000L + 10_000, ev(120), forward = true))
        assertNull(ArchiveTimeline.neighbour(events, day + 200 * 60_000L, null, forward = true))
        assertEquals(ev(120), ArchiveTimeline.eventNear(events, day + 120 * 60_000L + 15_000, 1_000))
        assertNull(ArchiveTimeline.eventNear(events, day + 125 * 60_000L, 1_000))
        val seg = RecFile(File("c"), "door", RecFile.Kind.CONTINUOUS, day, 200 * 60_000L, 1)
        assertEquals(day + 200 * 60_000L, ArchiveTimeline.latest(listOf(seg), events))
    }

    // ---- Звонки ----

    @Test fun ringLogKeepsLastRingsWithPhotosAndAnswered() {
        val tile = "door_ring_log_test"
        RingLog.dir(context, tile).deleteRecursively()
        val frame = Bitmap.createBitmap(2560, 1920, Bitmap.Config.ARGB_8888)
        RingLog.add(context, tile, 1_000)
        assertTrue(RingLog.savePhoto(context, tile, 1_000, frame, better = false))
        assertFalse("снимок уже есть — снимок плитки его не заменяет", RingLog.savePhoto(context, tile, 1_000, frame, better = false))
        assertTrue("кадр HD из окна звонка заменяет", RingLog.savePhoto(context, tile, 1_000, frame, better = true))
        assertFalse("чужой звонок", RingLog.savePhoto(context, tile, 5_000, frame, better = true))
        val ring = RingLog.list(context, tile).single()
        assertNotNull(ring.photo)
        assertFalse(ring.answered)
        assertEquals("уменьшен до 1280 по ширине", 1280, RingLog.photo(ring.photo, 2000)!!.width)
        for (t in 2..(RingLog.KEEP + 5)) RingLog.add(context, tile, t * 1_000L)
        val list = RingLog.list(context, tile)
        assertEquals(RingLog.KEEP, list.size)
        assertEquals("новые первыми", (RingLog.KEEP + 5) * 1_000L, list.first().atMs)
        assertFalse("старый звонок со снимком удалён вместе с файлом", File(RingLog.dir(context, tile), "1000.jpg").exists())
        RingLog.markAnswered(context, tile, list.first().atMs)
        val until = System.currentTimeMillis() + 3_000
        while (!RingLog.list(context, tile).first().answered && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(RingLog.list(context, tile).first().answered)
        assertFalse(RingLog.list(context, tile)[1].answered)
        RingLog.dir(context, tile).deleteRecursively()
    }

    // ---- Фразы своим голосом ----

    @Test fun phrasesStorageAndTrim() {
        val tile = "door_phrase_test"
        DoorbellPhrases.setList(context, tile, listOf(DoorbellPhrases.Phrase("abc123", "Оставьте у калитки"),
            DoorbellPhrases.Phrase("def456", " ")))
        assertEquals(listOf("Оставьте у калитки", "Фраза"), DoorbellPhrases.list(context, tile).map { it.title })
        assertTrue("чужой id (путь) не принимается", DoorbellPhrases.parse("[{\"id\":\"../x\",\"title\":\"a\"}]").isEmpty())
        // Тишина по краям срезается (с запасом 0,1 с), громкость выравнивается.
        val pcm = ShortArray(8_000) { i -> if (i in 3_200 until 4_800) (if (i % 2 == 0) 4_000 else -4_000).toShort() else 10 }
        val trimmed = DoorbellPhrases.trimAndNormalize(pcm, pcm.size)
        assertTrue("${trimmed.size}", trimmed.size in 1_600..3_400)
        assertEquals("усиление не больше 4×", 16_000, trimmed.maxOf { it.toInt() })
        assertEquals(0, DoorbellPhrases.trimAndNormalize(ShortArray(8_000) { 5 }, 8_000).size)
        assertTrue(DoorbellPhrases.save(context, tile, "abc123", trimmed, trimmed.size))
        assertTrue(DoorbellPhrases.load(context, tile, "abc123")!!.contentEquals(trimmed))
        assertEquals("голос есть только у первой", listOf("abc123"), DoorbellPhrases.ready(context, tile).map { it.id })
        DoorbellPhrases.delete(context, tile, "abc123")
        assertTrue(DoorbellPhrases.ready(context, tile).isEmpty())
    }
}
