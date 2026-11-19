package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/** Ночные задачи строго ночью (NightlyWork): ближайшие 03:00, а не «через 24 ч от прошлого запуска». */
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
}
