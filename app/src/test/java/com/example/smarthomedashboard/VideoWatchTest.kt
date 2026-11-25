package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Соединение живо, а видео не движется (аудит 25.11.2026, REC-01). */
class VideoWatchTest {
    private var nowMs = 0L
    private val watch = VideoWatch(15_000) { nowMs * 1_000_000 }

    private fun stalled(): Boolean = try { watch.check(); false } catch (e: RtspException) {
        assertEquals("Камера не присылает видео", e.message); true
    }

    /** Кадры раз в 100 мс с меткой [ts] от [from] до [to] мс. */
    private fun frames(from: Long, to: Long, ts: (Long) -> Long = { it * 90 }) {
        for (t in from until to step 100) { nowMs = t; watch.check(); watch.packet(ts(t) and 0xFFFFFFFFL); watch.frame() }
    }

    @Test fun onlySoundForFifteenSecondsIsStall() {
        frames(0, 5_000)
        nowMs = 19_000; assertEquals(false, stalled())
        nowMs = 20_000; assertEquals(true, stalled())
    }

    @Test fun noVideoFromTheVeryStartIsStall() {
        nowMs = 14_900; assertEquals(false, stalled())
        nowMs = 15_100; assertEquals(true, stalled())
    }

    /** Часы камеры ушли назад на час: метки меньше прежних, отрезок никогда не кончится. */
    @Test fun cameraClockBackwardsIsStall() {
        frames(0, 5_000) { 90L * 3_600_000 + it * 90 }
        try {
            for (t in 5_000L until 30_000 step 100) { nowMs = t; watch.check(); watch.packet(t * 90); watch.frame() }
            fail("часы камеры ушли назад — поток не подключён заново")
        } catch (_: RtspException) {}
        assertEquals(true, nowMs in 19_000..21_000)
    }

    /** Метка RTP переходит через 2^32 (раз в 13 часов) и кадры переставлены (B-кадры) — это не остановка. */
    @Test fun wrapAndReorderedFramesAreNotStall() {
        val start = 0xFFFFFFFFL - 90L * 10_000
        frames(0, 60_000) { start + it * 90 + (if (it % 300 == 100L) 9_000 else 0) }
        assertEquals(false, stalled())
    }

    /** Пакеты видео идут, а целых кадров нет (потерян кусок до следующего ключевого): редкий ключевой кадр ждём, минуту без кадров — нет. */
    @Test fun packetsWithoutWholeFramesAreStallAfterMinute() {
        frames(0, 5_000)
        for (t in 5_000L until 64_000 step 100) { nowMs = t; watch.check(); watch.packet(t * 90) }
        nowMs = 65_100
        watch.packet(nowMs * 90)
        assertEquals(true, stalled())
    }
}
