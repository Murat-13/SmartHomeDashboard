package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/** Ночные задачи строго ночью (NightlyWork): ближайшие 03:00, а не «через 24 ч от прошлого запуска». */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NightlyWorkTest {
    private fun at(h: Int, m: Int, s: Int = 0) = Calendar.getInstance().apply { set(2026, 10, 19, h, m, s); set(Calendar.MILLISECOND, 0) }

    @Test fun nextRunIsTheComingNight() {
        // Найдено на планшете: задача ушла на 15:10 — следующая всё равно в 03:00.
        assertEquals((11 * 60 + 50) * 60_000L, NightlyWork.delayMs(3, 0, at(15, 10)))
        // Отработала в 03:00:05 — следующая завтра в 03:00, не сегодня.
        assertEquals(24 * 3_600_000L - 5_000L, NightlyWork.delayMs(3, 0, at(3, 0, 5)))
        // До 03:30 ещё полчаса — сегодня.
        assertEquals(30 * 60_000L, NightlyWork.delayMs(3, 30, at(3, 0)))
        assertEquals(24 * 3_600_000L, NightlyWork.delayMs(4, 0, at(4, 0)))
    }

    /** Второй аудит 25.11.2026, AUD-18: ночное окно 02:00–05:00; запоздавшая задача днём ничего не делает и встаёт на следующую ночь. */
    @Test fun lateTaskWaitsForNextNight() {
        assertEquals(listOf(false, true, true, true, false, false),
            listOf(at(1, 59), at(2, 0), at(3, 30), at(4, 59), at(5, 0), at(14, 0)).map { NightlyWork.isNight(it) })
        val real = NightlyWork.now
        try {
            var worked = 0; var next = 0
            NightlyWork.now = { at(14, 0) }
            NightlyWork.run("проверка", { next++ }) { worked++ }
            assertEquals("днём работа не делается, следующая ночь поставлена", 0 to 1, worked to next)
            NightlyWork.now = { at(3, 0) }
            NightlyWork.run("проверка", { next++ }) { worked++ }
            assertEquals(1 to 2, worked to next)
        } finally { NightlyWork.now = real }
    }

    /** Аудит 25.11.2026, STAB-08: упавшая работа не рвёт цепочку ночных запусков. */
    @Test fun failedWorkStillSchedulesNextNight() {
        val real = NightlyWork.now
        try {
            var next = 0
            NightlyWork.now = { at(3, 0) }
            NightlyWork.run("проверка", { next++ }) { throw IllegalStateException("база") }
            assertEquals(1, next)
        } finally { NightlyWork.now = real }
    }
}
