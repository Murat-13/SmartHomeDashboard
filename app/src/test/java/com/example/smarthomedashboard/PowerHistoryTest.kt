package com.example.smarthomedashboard

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/** История мощности: единицы, разрывы, статистика по времени, календарные периоды, база и уплотнение. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PowerHistoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = PowerHistoryStore(context, name = null)
    private val t0 = 1_800_000_000_000L
    private val min = PowerHistory.MINUTE_MS

    @After fun close() = store.close()

    @Test fun unitsKeepSignAndRejectNonNumbers() {
        assertEquals(-820.0, PowerHistory.toWatts("-820", "W")!!, 0.0)
        assertEquals(1250.0, PowerHistory.toWatts("1,25", "kW")!!, 1e-9)
        assertEquals(62.64, PowerHistory.toWatts("62.64", "Вт")!!, 1e-9)
        assertNull(PowerHistory.toWatts("unavailable", "W"))
        assertNull(PowerHistory.toWatts("unknown", "W"))
        assertNull(PowerHistory.toWatts("12", "A"))
        assertNull(PowerHistory.toWatts("12", null))
        assertNull(PowerHistory.toWatts("NaN", "W"))
    }

    @Test fun statsAreTimeWeightedAndSkipGaps() {
        // 100 Вт 10 мин, затем разрыв 30 мин, затем −200 Вт 20 мин.
        val series = PowerSeries(emptyList(), listOf(
            PowerSample(t0, 100.0), PowerSample(t0 + 10 * min, null),
            PowerSample(t0 + 40 * min, -200.0)))
        val s = PowerHistory.stats(series, t0, t0 + 60 * min, now = t0 + 60 * min)
        assertEquals(-200.0, s.min!!, 0.0)
        assertEquals(100.0, s.max!!, 0.0)
        // (100·10 − 200·20) / 30 = −100; пропуск не считается измеренным нулём.
        assertEquals(-100.0, s.avg!!, 1e-9)
    }

    @Test fun valueHeldFromBeforePeriodCountsAndFutureIsNotInvented() {
        val series = PowerSeries(emptyList(), listOf(PowerSample(t0 - 5 * min, 50.0)))
        val s = PowerHistory.stats(series, t0, t0 + 60 * min, now = t0 + 10 * min)
        assertEquals(50.0, s.min!!, 0.0)
        assertEquals(50.0, s.avg!!, 0.0)
        val empty = PowerHistory.stats(PowerSeries(emptyList(), emptyList()), t0, t0 + min, t0 + min)
        assertNull(empty.min); assertNull(empty.avg)
    }

    @Test fun calendarPeriodsFollowLocalMidnightMondayAndFirstDayWithDst() {
        val zone = TimeZone.getTimeZone("Europe/Berlin")
        // Воскресенье 29.03.2026 — переход на летнее время: сутки длятся 23 часа.
        val at = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.MARCH, 29, 15, 30) }.timeInMillis
        val day = GraphPeriod.DAY.window(at, zone)
        assertEquals(23 * PowerHistory.HOUR_MS, day.last + 1 - day.first)
        val week = GraphPeriod.WEEK.window(at, zone)
        val monday = Calendar.getInstance(zone).apply { timeInMillis = week.first }
        assertEquals(Calendar.MONDAY, monday.get(Calendar.DAY_OF_WEEK))
        assertEquals(23, monday.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, monday.get(Calendar.HOUR_OF_DAY))
        val month = GraphPeriod.MONTH.window(at, zone)
        assertEquals(1, Calendar.getInstance(zone).apply { timeInMillis = month.first }.get(Calendar.DAY_OF_MONTH))
        assertEquals(PowerHistory.HOUR_MS, GraphPeriod.HOUR.window(at).let { it.last + 1 - it.first })
        assertEquals(GraphPeriod.HOUR, GraphPeriod.of(null))
        assertEquals(GraphPeriod.WEEK, GraphPeriod.of("week"))
    }

    @Test fun sourcesAreSeparatedByServerAndEntity() {
        val a = store.sourceId("serverA", "sensor.p")!!
        val b = store.sourceId("serverB", "sensor.p")!!
        val c = store.sourceId("serverA", "sensor.q")!!
        assertEquals(3, setOf(a, b, c).size)
        assertEquals(a, store.sourceId("serverA", "sensor.p"))
        store.insert(a, listOf(PowerSample(t0, 10.0)))
        store.insert(b, listOf(PowerSample(t0, 99.0)))
        assertEquals(10.0, store.series(a, t0, t0 + min).samples.single().watts!!, 0.0)
        assertEquals(99.0, store.series(b, t0, t0 + min).samples.single().watts!!, 0.0)
    }

    @Test fun insertNeverOverwritesButHistoryFillsBreaks() {
        val s = store.sourceId("i", "sensor.p")!!
        store.insert(s, listOf(PowerSample(t0, 10.0), PowerSample(t0 + min, null)))
        store.insert(s, listOf(PowerSample(t0, 500.0)))
        assertEquals(10.0, store.series(s, t0, t0 + 1).samples.first().watts!!, 0.0)
        store.insert(s, listOf(PowerSample(t0 + min, 30.0)), fillBreaks = true)
        assertEquals(30.0, store.last(s)!!.watts!!, 0.0)
    }

    @Test fun gapsCoverMissingHistoryAndBreaks() {
        val s = store.sourceId("i", "sensor.p")!!
        assertEquals(listOf(t0 until t0 + 60 * min), store.gaps(s, t0, t0 + 60 * min))
        store.insert(s, listOf(PowerSample(t0 + 10 * min, 5.0), PowerSample(t0 + 20 * min, null), PowerSample(t0 + 30 * min, 7.0)))
        assertEquals(listOf(t0 until t0 + 10 * min, (t0 + 20 * min) until (t0 + 30 * min)), store.gaps(s, t0, t0 + 60 * min))
    }

    @Test fun downsamplingKeepsExtremesAndBreaks() {
        val s = store.sourceId("i", "sensor.p")!!
        val points = (0 until 10_000).map { PowerSample(t0 + it * 1000L, if (it == 5_000) null else if (it == 1234) 9_000.0 else if (it == 7777) -3_000.0 else 100.0) }
        store.insert(s, points)
        val series = store.series(s, t0, t0 + 10_000_000L, maxSamples = 400)
        assertTrue(series.samples.size <= 420)
        assertTrue(series.samples.any { it.watts == 9_000.0 })
        assertTrue(series.samples.any { it.watts == -3_000.0 })
        assertTrue(series.samples.any { it.watts == null })
        assertEquals(series.samples.sortedBy { it.ts }, series.samples)
        val full = store.stats(s, t0, t0 + 10_000_000L, now = t0 + 10_000_000L)
        assertEquals(-3_000.0, full.min!!, 0.0)
        assertEquals(9_000.0, full.max!!, 0.0)
    }

    @Test fun compactionKeepsExtremesAndAverageAndDropsOld() {
        val s = store.sourceId("i", "sensor.p")!!
        val now = t0 + 8 * PowerHistory.DAY_MS
        // Минута t0: 0 Вт 30 с, затем 1000 Вт 30 с; потом 400 Вт держится через границу уплотнения.
        store.insert(s, listOf(PowerSample(t0, 0.0), PowerSample(t0 + 30_000, 1000.0), PowerSample(t0 + min, 400.0)))
        store.compact(now)
        val series = store.series(s, t0, now)
        val first = series.buckets.first()
        assertEquals(t0, first.start)
        assertEquals(0.0, first.min, 0.0)
        assertEquals(1000.0, first.max, 0.0)
        assertEquals(500.0, first.avg, 1e-9)
        assertEquals(min, first.coveredMs)
        // Значение на границе сохранено точкой: линия продолжается без выдуманных данных.
        val cutoff = Math.floorDiv(now - PowerHistory.RAW_KEEP_MS, min) * min
        assertEquals(PowerSample(cutoff, 400.0), store.last(s))
        val stats = store.stats(s, t0, t0 + 2 * min, now)
        assertEquals(1000.0, stats.max!!, 0.0)
        assertEquals((500.0 + 400.0) / 2, stats.avg!!, 1e-9)
        store.compact(t0 + 40 * PowerHistory.DAY_MS)
        assertTrue(store.series(s, t0, t0 + 2 * min).buckets.isEmpty())
    }

    @Test fun formatsSignedPower() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale("ru", "RU"))
        try { checkFormats() } finally { java.util.Locale.setDefault(saved) }
    }

    private fun checkFormats() {
        assertEquals("+62,6 Вт", PowerHistory.formatWatts(62.64))
        assertEquals("−820 Вт", PowerHistory.formatWatts(-820.0))
        assertEquals("+1,25 кВт", PowerHistory.formatWatts(1250.0))
        assertEquals("—", PowerHistory.formatWatts(null))
    }
}
