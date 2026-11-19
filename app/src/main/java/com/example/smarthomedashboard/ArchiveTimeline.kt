package com.example.smarthomedashboard

import java.util.Calendar

/**
 * Шкала времени архива как у Reolink (решение владельца 19.11.2026): неподвижная риска по центру — «сейчас
 * смотрю», под ней ведут ленту времени; масштаб двумя пальцами от [MIN_SPAN] до [MAX_SPAN]. Лента не ограничена
 * сутками: за полночь уходит в соседний день. Чистая логика (время — мс на стене).
 */
internal object ArchiveTimeline {
    const val MIN_SPAN = 10 * 60_000L
    const val MAX_SPAN = 24 * 3_600_000L
    const val DEFAULT_SPAN = 6 * 3_600_000L

    /** Шаги подписей: от минуты до 6 часов. */
    private val STEPS = longArrayOf(60_000, 2 * 60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000,
        3_600_000, 2 * 3_600_000, 3 * 3_600_000, 6 * 3_600_000)

    fun clampSpan(span: Long) = span.coerceIn(MIN_SPAN, MAX_SPAN)

    /** Наименьший шаг подписей, при котором между ними не меньше [minGapPx]. */
    fun step(spanMs: Long, widthPx: Float, minGapPx: Float): Long {
        if (widthPx <= 0f) return STEPS.last()
        val perMs = widthPx / spanMs
        return STEPS.firstOrNull { it * perMs >= minGapPx } ?: STEPS.last()
    }

    /** Отметки шага [step] на отрезке [from, to] — по местному времени (полночь, 06:00, 06:15…). */
    fun ticks(from: Long, to: Long, step: Long): List<Long> {
        val midnight = RecArchive.dayStart(from)
        // Сутки не всегда 24 ч (перевод часов): шаг считается от местной полуночи каждого дня.
        val out = ArrayList<Long>()
        var day = midnight
        while (day <= to) {
            val next = nextDay(day)
            var t = day
            while (t < next && t <= to) {
                if (t >= from) out += t
                t += step
            }
            day = next
        }
        return out
    }

    fun nextDay(dayStart: Long): Long = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_MONTH, 1) }
        .let { RecArchive.dayStart(it.timeInMillis) }

    /** Ролик события, ближайший к [atMs] в пределах [toleranceMs] (касание по засечке — ролик в HD). */
    fun eventNear(events: List<RecArchive.Event>, atMs: Long, toleranceMs: Long): RecArchive.Event? =
        events.filter { atMs >= it.file.startMs - toleranceMs && atMs <= maxOf(it.file.endMs, it.file.startMs + toleranceMs) }
            .minByOrNull { kotlin.math.abs(it.file.startMs - atMs) }

    /**
     * Соседнее событие для «◀ Событие» / «Событие ▶»: от момента [atMs]; [current] — идущий сейчас ролик события
     * (из него «назад» — к предыдущему, а не в начало этого же).
     */
    fun neighbour(events: List<RecArchive.Event>, atMs: Long, current: RecArchive.Event?, forward: Boolean): RecArchive.Event? {
        val sorted = events.sortedBy { it.file.startMs }
        return if (forward) {
            val from = current?.file?.startMs ?: atMs
            sorted.firstOrNull { it.file.startMs > from + 500 }
        } else {
            val before = current?.file?.startMs ?: (atMs - 2_000)
            sorted.lastOrNull { it.file.startMs < before }
        }
    }

    /** Последний записанный момент (кнопка «Сейчас»): конец последнего отрезка 24/7 или ролика события. */
    fun latest(continuous: List<RecFile>, events: List<RecArchive.Event>): Long? =
        (continuous.map { it.endMs } + events.map { it.file.endMs }).maxOrNull()
}
