package com.example.smarthomedashboard

import java.util.TimeZone

/**
 * Состояние просмотра графика мощности: общее у карточки и полноэкранного графика (docs/power-graph.md).
 * Видимый участок — [end] и [span]. В режиме [live] правый край следует за текущим временем
 * (для `1м`/`1ч`) или за текущим календарным периодом; после ухода в прошлое новые значения
 * участок не сдвигают. Статистика — за период вкладки ([statsWindow]), а не за увеличенный участок.
 */
internal class PowerGraphState(period: GraphPeriod, private val zone: TimeZone = TimeZone.getDefault()) {
    var period: GraphPeriod = period
        private set
    var live = true
        private set
    var span: Long = 0
        private set
    private var fixedEnd = 0L
    /** Момент, по которому выбран просматриваемый период статистики (null — текущий). */
    private var anchor: Long? = null
    /** Время курсора (реальная метка замера) или null. */
    var cursor: Long? = null

    fun end(now: Long): Long = if (live) liveEnd(now) else fixedEnd
    fun viewport(now: Long): LongRange = (end(now) - span) until end(now)

    private fun liveEnd(now: Long) = if (period.sliding) now else period.window(now, zone).last + 1

    private fun periodLength(at: Long) = period.window(at, zone).let { it.last + 1 - it.first }

    fun reset(now: Long) {
        live = true
        anchor = null
        cursor = null
        val window = period.window(now, zone)
        span = window.last + 1 - window.first
    }

    fun select(next: GraphPeriod, now: Long) {
        period = next
        reset(now)
    }

    /** Окно Мин/Макс/Средней: текущий период или просматриваемый исторический (календарный — целиком). */
    fun statsWindow(now: Long): LongRange {
        val at = anchor ?: return period.window(now, zone)
        if (!period.sliding) return period.window(at, zone)
        val length = periodLength(at)
        return (at - length / 2) until (at + length / 2)
    }

    /** Сдвиг на [deltaMs] (минус — в прошлое). Дойдя до настоящего, график снова следует за временем. */
    fun pan(deltaMs: Long, now: Long) {
        val current = end(now)
        val limit = liveEnd(now)
        val earliest = now - PowerHistory.KEEP_MS + span
        val next = (current + deltaMs).coerceIn(minOf(earliest, limit), limit)
        if (next >= limit && deltaMs >= 0) {
            live = true
            anchor = null
            return
        }
        live = false
        fixedEnd = next
        // Календарный период — тот, где середина экрана (сдвиг внутри суток статистику не меняет);
        // скользящий — окно длины вкладки, оканчивающееся на правом краю экрана.
        anchor = if (period.sliding) next - periodLength(next) / 2 else next - span / 2
    }

    /**
     * Масштаб вокруг момента [focus]: [factor] > 1 — крупнее (меньше времени на экране).
     * Пределы: от [MIN_SPAN_MS] до длины периода вкладки.
     */
    fun zoom(factor: Float, focus: Long, now: Long) {
        if (!factor.isFinite() || factor <= 0f) return
        val currentEnd = end(now)
        val maxSpan = periodLength(now)
        val nextSpan = (span / factor).toLong().coerceIn(MIN_SPAN_MS, maxSpan)
        if (nextSpan == span) return
        val ratio = (currentEnd - focus).toDouble() / span
        var nextEnd = focus + (ratio * nextSpan).toLong()
        val limit = liveEnd(now)
        if (nextEnd >= limit) nextEnd = limit
        // Календарный период: увеличенный участок не уходит целиком в будущее без данных.
        if (!period.sliding && nextSpan < maxSpan) nextEnd = minOf(nextEnd, now + nextSpan / 2)
        span = nextSpan
        // Скользящий период у правого края остаётся «живым»; иначе участок фиксируется.
        if (live && nextEnd == limit) return
        live = false
        fixedEnd = nextEnd.coerceAtLeast(now - PowerHistory.KEEP_MS + span)
    }

    companion object {
        const val MIN_SPAN_MS = 10_000L
    }
}
