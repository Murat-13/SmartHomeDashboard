package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ровный показ после ключевого кадра: последовательности шагов — из записей домофона (docs/doorbell.md, замеры 21.11.2026). */
class FrameRetimerTest {
    /** Метки кадров по шагам в миллисекундах, начиная с [startMs]. */
    private fun stamps(stepsMs: List<Int>, startMs: Long = 1_000) = stepsMs.runningFold(startMs * 1000) { t, step -> t + step * 1000L }

    /** Все кадры — в декодер (как при запасе плеера), затем показ по порядку; возвращает время показа каждого, мс. */
    private fun play(retimer: FrameRetimer, pts: List<Long>): List<Long> {
        pts.forEach(retimer::queued)
        return pts.map { p -> retimer.timeFor(p).also { retimer.shown(p) } / 1000 }
    }

    private fun steps(times: List<Long>) = times.zipWithNext { a, b -> (b - a).toInt() }

    /** Обычный ход основного потока: датчик 25 к/с, каждый пятый кадр выброшен. */
    private val usual = List(24) { if (it % 4 == 3) 80 else 40 }

    @Test fun evenAndDecimatedStreamsAreShownExactlyByTheirStamps() {
        val pts = stamps(usual + listOf(40, 120, 40, 80, 80, 38, 42, 40))
        assertEquals(pts.map { it / 1000 }, play(FrameRetimer(), pts))
    }

    @Test fun burstAfterKeyframeIsSpreadBackIntoTheGapBeforeIt() {
        // Запись домофона: к ключевому кадру шаг 40, затем 60, пауза 137 и три кадра «пачкой» (2, 2, 32 мс).
        val retimer = FrameRetimer()
        val pts = stamps(usual + listOf(40, 60, 137, 2, 2, 32, 79, 40, 40))
        val shown = play(retimer, pts)
        val tail = steps(shown).takeLast(9)
        assertEquals("было 40 60 137 2 2 32 79 40 40", listOf(40, 60, 65, 36, 36, 36, 79, 40, 40), tail)
        assertTrue("кадр не показывается позже своей метки", shown.zip(pts).all { (at, stamp) -> at <= stamp / 1000 })
        assertEquals(3, retimer.moved)
        assertEquals(72_000L, retimer.maxShiftUs)
    }

    @Test fun pairStampedTogetherMovesEarlierFramesBack() {
        // Субпоток: ключевой кадр, через 41 мс кадр и сразу (3 мс) ещё один, затем пауза 129 мс.
        val shown = play(FrameRetimer(), stamps(usual + listOf(80, 80, 41, 3, 129, 40)))
        assertEquals("было 80 80 41 3 129 40", listOf(80, 52, 36, 36, 129, 40), steps(shown).takeLast(6))
    }

    @Test fun frameAlreadyOnScreenIsNotMovedAndOrderIsKept() {
        val retimer = FrameRetimer()
        val pts = stamps(usual + listOf(40, 137, 2, 2, 40))
        val head = pts.dropLast(4)
        head.forEach(retimer::queued)
        // Плеер без запаса: всё, что пришло до паузы, уже показано, когда приходит «пачка».
        head.forEach(retimer::shown)
        val burst = pts.takeLast(4)
        burst.forEach(retimer::queued)
        val shown = burst.map { p -> retimer.timeFor(p).also { retimer.shown(p) } }
        assertTrue("после уже показанного кадра", shown.first() > head.last())
        assertTrue("порядок сохранён", shown.zipWithNext().all { (a, b) -> b > a })
        assertTrue(shown.zip(burst).all { (at, stamp) -> at <= stamp })
    }

    @Test fun nothingChangesUntilCameraStepIsKnown() {
        val pts = stamps(listOf(40, 40, 137, 2, 2, 40))
        assertEquals(pts.map { it / 1000 }, play(FrameRetimer(), pts))
    }

    @Test fun streamWithReorderedFramesIsLeftAlone() {
        val retimer = FrameRetimer()
        val pts = stamps(usual)
        pts.forEach(retimer::queued)
        retimer.queued(pts.last() - 80_000)   // метка назад: кадры идут в декодер не по порядку показа
        assertFalse(retimer.enabled)
        retimer.queued(pts.last() + 2_000)
        assertEquals(pts.last() + 2_000, retimer.timeFor(pts.last() + 2_000))
        assertEquals(pts[3], retimer.timeFor(pts[3]))
    }

    @Test fun decoderFlushEmptiesQueueButKeepsCameraStep() {
        val retimer = FrameRetimer()
        play(retimer, stamps(usual))
        val gap = retimer.gapUs()
        assertEquals("шаг датчика 40 мс → не ближе 36 мс", 36_000L, gap)
        retimer.reset()
        assertTrue(retimer.enabled)
        assertEquals(gap, retimer.gapUs())
        // После сброса время начинается с любого места — даже раньше прежнего.
        val shown = play(retimer, stamps(listOf(40, 2, 2, 40), startMs = 500))
        assertEquals(listOf(36, 36, 40), steps(shown).takeLast(3))
    }

    @Test fun shiftIsLimitedSoLongBurstCannotPullFramesFarBack() {
        val retimer = FrameRetimer()
        val pts = stamps(usual + listOf(900) + List(20) { 2 })
        val shown = play(retimer, pts)
        assertTrue(shown.zip(pts).all { (at, stamp) -> stamp / 1000 - at <= 250 })
        assertTrue(shown.zipWithNext().all { (a, b) -> b >= a })
    }
}
