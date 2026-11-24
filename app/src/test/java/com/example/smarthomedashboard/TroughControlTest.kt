package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Запас живого видео «по дну» ([TroughControl]): пила запаса от поздних ключевых кадров раз в секунду. */
class TroughControlTest {
    /** Запас пилой: раз в секунду проваливается до [low] и за 0,4 с поднимается на [swing]. */
    private fun saw(ctl: TroughControl, fromMs: Long, toMs: Long, low: (Long) -> Long, swing: Long = 400) {
        var t = fromMs
        while (t < toMs) {
            val phase = t % 1000
            val b = low(t) + if (phase < 400) swing * phase / 400 else swing
            ctl.add(t, b)
            t += TroughControl.SAMPLE_MS
        }
    }

    private fun run(ctl: TroughControl, fromMs: Long, toMs: Long, low: (Long) -> Long, start: Float = 1f): Float {
        var speed = start
        var t = fromMs
        while (t < toMs) {
            saw(ctl, t, t + 1000, low)
            t += 1000
            speed = ctl.speed(t, speed)
        }
        return speed
    }

    @Test fun lowIsNullUntilWindowIsCovered() {
        val ctl = TroughControl()
        saw(ctl, 0, 500, { 300 })
        assertNull(ctl.low(500, TroughControl.SHORT_MS))
        saw(ctl, 500, 2_000, { 300 })
        assertEquals(300L, ctl.low(2_000, TroughControl.SHORT_MS))
    }

    @Test fun extraBufferAtTroughIsTakenOffGently() {
        val ctl = TroughControl()
        // Дно 380 мс при цели 80 — лишние 0,3 с (как во втором раскрытии HD 24.11): плеер ускоряется.
        val speed = run(ctl, 0, 7_000, { 380 })
        assertTrue("ускоряется: $speed", speed > 1f && speed <= 1.1f)
    }

    @Test fun troughAtTargetKeepsNormalSpeed() {
        val ctl = TroughControl()
        assertEquals(1f, run(ctl, 0, 10_000, { 90 }), 0f)
    }

    @Test fun dipNearEdgeSlowsWithoutRaisingTarget() {
        val ctl = TroughControl()
        run(ctl, 0, 8_000, { 90 })
        // Дно ниже половины цели: плеер замедляется; цель от колебаний дна не растёт (растёт только от остановки).
        assertEquals(TroughControl.SLOW_SPEED, run(ctl, 8_000, 9_000, { 20 }), 0f)
        assertEquals(TroughControl.START_TARGET_MS, ctl.targetMs)
        // Замедлившись, не возвращается к 1×, пока дно ниже цели.
        assertEquals(TroughControl.SLOW_SPEED, run(ctl, 9_000, 11_000, { 60 }, start = TroughControl.SLOW_SPEED), 0f)
        assertEquals(1f, run(ctl, 11_000, 13_000, { 90 }, start = TroughControl.SLOW_SPEED), 0f)
    }

    @Test fun noisyTroughDoesNotRatchetTarget() {
        val ctl = TroughControl()
        // Субпоток: дно каждую секунду своё (60–260 мс) — цель остаётся прежней, запас лишним не становится.
        val rnd = java.util.Random(7)
        run(ctl, 0, 240_000, { 60L + rnd.nextInt(200) })
        assertTrue("цель ${ctl.targetMs}", ctl.targetMs <= TroughControl.START_TARGET_MS)
    }

    @Test fun softwareDecoderKeepsHigherTarget() {
        val ctl = TroughControl()
        ctl.software = true
        assertEquals(TroughControl.SOFTWARE_START_TARGET_MS, ctl.targetMs)
        run(ctl, 0, 600_000, { 260 })
        assertEquals(TroughControl.SOFTWARE_MIN_TARGET_MS, ctl.targetMs)
    }

    @Test fun calmStreamLowersTargetSlowly() {
        val ctl = TroughControl()
        run(ctl, 0, 3_000, { 100 })
        run(ctl, 3_000, 3_000 + TroughControl.CALM_MS + 2_000, { 100 })
        assertEquals(TroughControl.START_TARGET_MS - TroughControl.DECAY_STEP_MS, ctl.targetMs)
        // Не ниже наименьшей цели.
        run(ctl, 25_000, 400_000, { 100 })
        assertEquals(TroughControl.MIN_TARGET_MS, ctl.targetMs)
    }

    @Test fun stallRaisesTargetAtOnce() {
        val ctl = TroughControl()
        ctl.stalled(1_000)
        assertEquals(TroughControl.START_TARGET_MS + TroughControl.STALL_STEP_MS, ctl.targetMs)
    }
}
