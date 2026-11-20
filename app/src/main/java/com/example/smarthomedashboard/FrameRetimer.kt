package com.example.smarthomedashboard

/**
 * Ровный показ кадров после ключевого (docs/doorbell.md, «Сводка замеров 21.11.2026»).
 *
 * Камера ставит метку времени кадра при отправке, а не при съёмке. Пока кодер занят ключевым кадром, следующие кадры
 * ждут и уходят пачкой: в метках — пауза 135–180 мс и затем 2–3 кадра с шагом 2–5 мс (Reolink, основной поток), хотя
 * сняты они были через равные промежутки. Плеер, показывающий по меткам, повторяет это на экране: замирание и
 * «проглоченные» кадры раз в пару секунд.
 *
 * Метка не бывает раньше съёмки, значит, настоящее время кадров пачки — раньше их меток. Правило одно: между соседними
 * кадрами не меньше [gapUs] (чуть меньше обычного шага камеры); кадр, за которым следующий идёт слишком близко,
 * показывается раньше своей метки. Сдвиг — только назад и только у кадров, которые ещё не показаны: они известны
 * заранее, потому что декодер получает кадры раньше срока показа на весь запас плеера. Ровные потоки правило не
 * задевает; поток с перестановкой кадров (метки на входе идут не по порядку) не трогается вовсе.
 *
 * Все вызовы — из потока плеера; счётчики для журнала читает главный поток.
 */
internal class FrameRetimer {
    private val pts = LongArray(CAPACITY)
    private val out = LongArray(CAPACITY)
    private var size = 0
    private val steps = LongArray(STEPS)
    private var stepCount = 0
    private var stepNext = 0
    private val sorted = LongArray(STEPS)
    private var lastQueued = Long.MIN_VALUE
    private var lastShown = Long.MIN_VALUE

    /** false — метки на входе шли не по порядку (кадры с перестановкой): поток показывается как есть. */
    var enabled = true
        private set

    /** Для журнала: сколько кадров показано раньше своей метки и наибольший сдвиг. */
    @Volatile var moved = 0
        private set
    @Volatile var maxShiftUs = 0L
        private set

    /** Кадр с меткой [ptsUs] ушёл в декодер (до показа ему ещё весь запас плеера). */
    fun queued(ptsUs: Long) {
        if (!enabled) return
        if (lastQueued != Long.MIN_VALUE) {
            val step = ptsUs - lastQueued
            if (step <= 0) { enabled = false; size = 0; return }
            if (step < MAX_STEP_US) {
                steps[stepNext] = step
                stepNext = (stepNext + 1) % STEPS
                if (stepCount < STEPS) stepCount++
            }
        }
        lastQueued = ptsUs
        if (size == CAPACITY) drop(1)
        pts[size] = ptsUs
        out[size] = ptsUs
        size++
        val gap = gapUs()
        if (gap == 0L) return
        // От нового кадра назад: предыдущий не ближе шага камеры — значит, раньше своей метки.
        for (i in size - 2 downTo 0) {
            val limit = out[i + 1] - gap
            if (out[i] <= limit) break
            out[i] = maxOf(limit, pts[i] - MAX_SHIFT_US)
        }
        // Но не раньше уже показанного и не позже своей метки; порядок кадров не меняется.
        var floor = lastShown
        for (i in 0 until size) {
            if (floor != Long.MIN_VALUE && out[i] <= floor) out[i] = minOf(floor + MIN_STEP_US, pts[i])
            floor = out[i]
        }
    }

    /** Когда показывать кадр с меткой [ptsUs]: не позже самой метки. */
    fun timeFor(ptsUs: Long): Long {
        for (i in 0 until size) if (pts[i] == ptsUs) return out[i]
        return ptsUs
    }

    /** Кадр с меткой [ptsUs] показан или пропущен: он и все более ранние больше не нужны. */
    fun shown(ptsUs: Long) {
        var count = 0
        while (count < size && pts[count] <= ptsUs) count++
        if (count == 0) return
        val last = count - 1
        if (pts[last] == ptsUs) {
            val shift = pts[last] - out[last]
            if (shift > 0) { moved++; if (shift > maxShiftUs) maxShiftUs = shift }
        }
        lastShown = out[last]
        drop(count)
    }

    /** Декодер сброшен (переход, пропуск к ключевому кадру): очередь пуста, оценка шага камеры остаётся. */
    fun reset() {
        size = 0
        lastQueued = Long.MIN_VALUE
        lastShown = Long.MIN_VALUE
        enabled = true
    }

    /**
     * Наименьший шаг между кадрами: 0,9 обычного шага камеры. Обычный шаг — нижняя четверть недавних шагов без
     * «пачек»: у камеры, выбрасывающей кадры (шаг 40, 80, 120 мс), это шаг датчика. 0 — шаг ещё не известен.
     */
    internal fun gapUs(): Long {
        var count = 0
        for (i in 0 until stepCount) if (steps[i] >= BURST_US) sorted[count++] = steps[i]
        if (count < MIN_STEPS) return 0
        java.util.Arrays.sort(sorted, 0, count)
        return sorted[count / 4].coerceIn(MIN_PERIOD_US, MAX_PERIOD_US) * 9 / 10
    }

    private fun drop(count: Int) {
        System.arraycopy(pts, count, pts, 0, size - count)
        System.arraycopy(out, count, out, 0, size - count)
        size -= count
    }

    private companion object {
        /** Кадров в очереди «в декодере, но ещё не показаны». */
        const val CAPACITY = 64
        /** По стольким недавним шагам оценивается шаг камеры; оценка — не раньше [MIN_STEPS] шагов. */
        const val STEPS = 48
        const val MIN_STEPS = 12
        /** Шаг короче — «пачка», в оценку шага камеры не идёт. */
        const val BURST_US = 15_000L
        const val MIN_PERIOD_US = 20_000L
        const val MAX_PERIOD_US = 200_000L
        /** Шаг длиннее — разрыв потока, не шаг камеры. */
        const val MAX_STEP_US = 1_000_000L
        /** Кадр показывается раньше своей метки не больше чем на столько. */
        const val MAX_SHIFT_US = 250_000L
        const val MIN_STEP_US = 1_000L
    }
}
