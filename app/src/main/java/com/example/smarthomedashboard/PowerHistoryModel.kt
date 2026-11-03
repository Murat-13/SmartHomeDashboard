package com.example.smarthomedashboard

import java.util.Calendar
import java.util.TimeZone

/**
 * Модель истории мощности для графика «Электропитание Modern» (docs/power-graph.md).
 * Значение держится до следующего замера (так его видит HA); [PowerSample.watts] = null — начало
 * разрыва (нет связи, unavailable, остановка приложения): через разрыв линия не рисуется.
 */
internal data class PowerSample(val ts: Long, val watts: Double?)

/** Минутный агрегат данных старше 7 дней: экстремумы и среднее по времени, без выдуманных точек. */
internal data class PowerBucket(
    val start: Long,
    val min: Double,
    val max: Double,
    val avg: Double,
    /** Сколько мс минуты покрыто данными (остальное — разрыв). */
    val coveredMs: Long,
    val count: Int
) {
    val end get() = start + PowerHistory.MINUTE_MS
}

/** Данные диапазона: минутные агрегаты (старые) и точные замеры (последние 7 дней). */
internal data class PowerSeries(val buckets: List<PowerBucket>, val samples: List<PowerSample>) {
    val isEmpty get() = buckets.isEmpty() && samples.none { it.watts != null }
}

internal data class PowerStats(val min: Double?, val max: Double?, val avg: Double?)

internal object PowerHistory {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS
    /** Точные замеры хранятся 7 дней, затем уплотняются в минутные агрегаты. */
    const val RAW_KEEP_MS = 7 * DAY_MS
    /** Вся история источника — не меньше 35 дней. */
    const val KEEP_MS = 35 * DAY_MS
    /** Меньше этого покрытия периода — средняя не показывается («—»). */
    const val MIN_COVERAGE_MS = 10_000L

    /**
     * Число из состояния HA в ваттах: единицы Вт/кВт/МВт (и русские), знак сохраняется.
     * Нечисло, unavailable/unknown, неизвестная единица — null (разрыв, а не ноль).
     */
    fun toWatts(state: String?, unit: String?): Double? {
        val number = state?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val factor = when (unit?.trim()?.lowercase()) {
            "w", "вт" -> 1.0
            "kw", "квт" -> 1_000.0
            "mw", "мвт" -> 1_000_000.0
            else -> return null
        }
        return number * factor
    }

    /**
     * Мин/Макс/Средняя за [from, to): значение держится до следующего замера или разрыва;
     * последнее держится до [now] (связь есть — регистратор пишет разрыв при её потере).
     * Средняя — взвешенная по времени только по покрытым интервалам.
     */
    fun stats(series: PowerSeries, from: Long, to: Long, now: Long): PowerStats {
        val acc = StatsAccumulator(from, minOf(to, now))
        series.buckets.forEach(acc::bucket)
        series.samples.forEach { acc.sample(it.ts, it.watts) }
        return acc.finish()
    }

    /**
     * Потоковый подсчёт для [stats]: база подаёт строки курсора по возрастанию времени, не держа все
     * замеры недели в памяти. Замеры — вместе с последним замером до [from] (его значение держится).
     */
    class StatsAccumulator(private val from: Long, private val end: Long) {
        private var min = Double.POSITIVE_INFINITY
        private var max = Double.NEGATIVE_INFINITY
        private var weighted = 0.0
        private var covered = 0L
        private var prevTs = Long.MIN_VALUE
        private var prevValue: Double? = null

        fun bucket(b: PowerBucket) {
            if (end <= from || b.end <= from || b.start >= end || b.coveredMs <= 0) return
            min = minOf(min, b.min)
            max = maxOf(max, b.max)
            // Минута, попавшая в период частично, учитывается долей своего покрытия.
            val share = (minOf(b.end, end) - maxOf(b.start, from)).toDouble() / MINUTE_MS
            val ms = (b.coveredMs * share).toLong()
            weighted += b.avg * ms
            covered += ms
        }

        fun sample(ts: Long, watts: Double?) {
            close(ts)
            prevTs = ts
            prevValue = watts
        }

        private fun close(stop: Long) {
            val value = prevValue ?: return
            val a = maxOf(prevTs, from)
            val b = minOf(stop, end)
            if (b <= a && prevTs !in from until end) return
            min = minOf(min, value)
            max = maxOf(max, value)
            if (b > a) {
                weighted += value * (b - a)
                covered += b - a
            }
        }

        fun finish(): PowerStats {
            if (end <= from) return PowerStats(null, null, null)
            close(end)
            prevValue = null
            if (min == Double.POSITIVE_INFINITY) return PowerStats(null, null, null)
            return PowerStats(min, max, if (covered >= MIN_COVERAGE_MS) weighted / covered else null)
        }
    }

    /** Последний достоверный замер (null — данных нет или последним был разрыв). */
    fun current(series: PowerSeries): Double? = series.samples.lastOrNull()?.watts

    fun formatWatts(watts: Double?, signed: Boolean = true): String {
        if (watts == null) return "—"
        val abs = kotlin.math.abs(watts)
        val sign = when {
            !signed -> if (watts < 0) "−" else ""
            watts > 0 -> "+"
            watts < 0 -> "−"
            else -> ""
        }
        return if (abs >= 1_000) "$sign${BatteryFormat.decimal(abs / 1_000, if (abs >= 10_000) 1 else 2)} кВт"
        else "$sign${BatteryFormat.decimal(abs, if (abs >= 100) 0 else 1)} Вт"
    }
}

/**
 * Вкладки периода. `1м`/`1ч` — скользящие окна; `Сутки`/`Неделя`/`Месяц` — календарные
 * (полночь, понедельник 00:00, 1-е число 00:00) в часовом поясе планшета, с учётом перехода на летнее время.
 */
internal enum class GraphPeriod(val key: String, val label: String) {
    MINUTE("1m", "1м"), HOUR("1h", "1ч"), DAY("day", "Сутки"), WEEK("week", "Неделя"), MONTH("month", "Месяц");

    val sliding get() = this == MINUTE || this == HOUR

    /** Период, содержащий момент [at] (для календарных) или оканчивающийся в [at] (для скользящих). */
    fun window(at: Long, zone: TimeZone = TimeZone.getDefault()): LongRange {
        if (this == MINUTE) return (at - PowerHistory.MINUTE_MS) until at
        if (this == HOUR) return (at - PowerHistory.HOUR_MS) until at
        val start = Calendar.getInstance(zone).apply {
            timeInMillis = at
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            when (this@GraphPeriod) {
                WEEK -> while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_MONTH, -1)
                MONTH -> set(Calendar.DAY_OF_MONTH, 1)
                else -> {}
            }
        }
        val end = (start.clone() as Calendar).apply {
            when (this@GraphPeriod) {
                DAY -> add(Calendar.DAY_OF_MONTH, 1)
                WEEK -> add(Calendar.DAY_OF_MONTH, 7)
                else -> add(Calendar.MONTH, 1)
            }
        }
        return start.timeInMillis until end.timeInMillis
    }

    companion object {
        val DEFAULT = HOUR
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
