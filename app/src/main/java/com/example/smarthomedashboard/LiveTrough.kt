package com.example.smarthomedashboard

/**
 * Запас живого видео «по дну» (опыт 24.11.2026, docs/doorbell.md, «Запас по дну»).
 *
 * Кадры от камеры приходят пилой: ключевой — раз в секунду и с опозданием (у домофона Reolink на линии 10 Мбит/с —
 * на 0,4 с), за ним подтягиваются остальные. Запас плеера каждую секунду проваливается и восстанавливается, а нужен он
 * ровно на глубину этого провала. Поэтому плеер держит самое низкое место запаса за последние секунды («дно») на
 * уровне [targetMs]. Прежнее правило смотрело на запас раз в секунду — в одну и ту же фазу пилы — и от показа к
 * показу держало его то с лишними 0,3 с, то у самого нуля.
 *
 * Цель подстраивается сама: остановка плеера (запаса не хватило) поднимает её на [STALL_STEP_MS], без остановок
 * [CALM_MS] — снижается на [DECAY_STEP_MS], но не ниже наименьшей. Дно ниже половины цели — плеер чуть замедляется
 * и набирает запас без остановки. Лишний запас снимается мягким ускорением (1,05–1,2×). Субпоток приходит неровно,
 * и его дно от секунды к секунде гуляет на 0,1–0,2 с: цель от этих колебаний не растёт (так было в первом варианте —
 * запас плитки за 4 минуты вырос до 0,8 с).
 *
 * Программный декодер (HD 2560×1920) держит у себя два кадра и разбирает ключевой кадр 0,15–0,2 с: при дне ниже
 * этого плеер встаёт на каждом ключевом кадре (замер 24.11.2026) — его цель не ниже [SOFTWARE_MIN_TARGET_MS].
 */
internal class TroughControl(initialTargetMs: Long = START_TARGET_MS) {
    /** Желаемое дно запаса, мс. */
    var targetMs = initialTargetMs
        private set

    private val times = LongArray(CAPACITY)
    private val values = LongArray(CAPACITY)
    private var next = 0
    private var count = 0
    private var calmSince = -1L
    private var lastCheck = -1L
    private var minTargetMs = MIN_TARGET_MS

    /** Видео разбирает программный декодер: цель — не ниже [SOFTWARE_MIN_TARGET_MS], с [SOFTWARE_START_TARGET_MS]. */
    var software = false
        set(value) {
            if (value == field) return
            field = value
            minTargetMs = if (value) SOFTWARE_MIN_TARGET_MS else MIN_TARGET_MS
            if (value && targetMs < SOFTWARE_START_TARGET_MS) targetMs = SOFTWARE_START_TARGET_MS
        }

    /** За показ — для журнала: самое низкое дно, сколько времени плеер шёл быстрее и медленнее 1×. */
    var lowestMs = Long.MAX_VALUE
        private set
    var fasterMs = 0L
        private set
    var slowerMs = 0L
        private set

    /** Замер запаса [bufferedMs] в момент [nowMs] (20 раз в секунду). */
    fun add(nowMs: Long, bufferedMs: Long) {
        times[next] = nowMs
        values[next] = bufferedMs
        next = (next + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    /** Дно запаса за последние [windowMs]; null — замеров ещё не на всё окно. */
    fun low(nowMs: Long, windowMs: Long): Long? {
        var oldest = Long.MAX_VALUE
        var min = Long.MAX_VALUE
        for (i in 0 until count) {
            val t = times[i]
            if (t < nowMs - windowMs || t > nowMs) continue
            oldest = minOf(oldest, t)
            min = minOf(min, values[i])
        }
        if (min == Long.MAX_VALUE || nowMs - oldest < windowMs * COVERAGE_PERCENT / 100) return null
        return min
    }

    /** Плеер остановился (кончился запас): цель сразу выше. */
    fun stalled(nowMs: Long) {
        targetMs = minOf(MAX_TARGET_MS, targetMs + STALL_STEP_MS)
        calmSince = nowMs
    }

    /** Раз в секунду: скорость плеера при текущей [current]. */
    fun speed(nowMs: Long, current: Float): Float {
        if (lastCheck >= 0) {
            val dt = nowMs - lastCheck
            if (current > 1f) fasterMs += dt else if (current < 1f) slowerMs += dt
        }
        lastCheck = nowMs
        val short = low(nowMs, SHORT_MS) ?: return 1f
        lowestMs = minOf(lowestMs, short)
        // Дно у самого края: плеер чуть медленнее, пока запас не вернётся к цели (без остановки).
        if (short < targetMs / 2 || (current < 1f && short < targetMs)) return SLOW_SPEED
        if (calmSince < 0) calmSince = nowMs
        if (nowMs - calmSince >= CALM_MS) {
            targetMs = maxOf(minTargetMs, targetMs - DECAY_STEP_MS)
            calmSince = nowMs
        }
        val long = low(nowMs, LONG_MS) ?: return 1f
        val excess = long - targetMs
        return when {
            excess > FAR_EXCESS_MS -> FAR_SPEED
            excess > MID_EXCESS_MS -> MID_SPEED
            excess > START_EXCESS_MS -> SOFT_SPEED
            current > 1f && excess > STOP_EXCESS_MS -> minOf(current, SOFT_SPEED)
            else -> 1f
        }
    }

    companion object {
        const val SAMPLE_MS = 50L
        /** Окно «провала»: дольше промежутка между ключевыми кадрами (раз в секунду). */
        const val SHORT_MS = 1_500L
        /** Окно «лишнего запаса»: несколько ключевых кадров — ускоряемся, только если запас лишний всё это время. */
        const val LONG_MS = 6_000L
        private const val CAPACITY = 160
        private const val COVERAGE_PERCENT = 80

        const val START_TARGET_MS = 80L
        const val MIN_TARGET_MS = 50L
        const val SOFTWARE_START_TARGET_MS = 200L
        const val SOFTWARE_MIN_TARGET_MS = 150L
        const val MAX_TARGET_MS = 600L
        const val STALL_STEP_MS = 60L
        const val CALM_MS = 30_000L
        const val DECAY_STEP_MS = 10L

        private const val START_EXCESS_MS = 40L
        private const val STOP_EXCESS_MS = 15L
        private const val MID_EXCESS_MS = 150L
        private const val FAR_EXCESS_MS = 500L
        const val SOFT_SPEED = 1.05f
        private const val MID_SPEED = 1.1f
        private const val FAR_SPEED = 1.2f
        const val SLOW_SPEED = 0.95f
    }
}
