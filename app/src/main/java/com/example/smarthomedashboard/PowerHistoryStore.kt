package com.example.smarthomedashboard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Локальная история мощности (docs/power-graph.md): отдельно для каждого сервера HA ([instance]) и датчика.
 * Только фоновый поток: методы блокирующие. `raw.value = NULL` — начало разрыва.
 * Точные замеры — 7 дней, затем минутные агрегаты; всё старше 35 дней удаляется.
 */
internal class PowerHistoryStore(context: Context, name: String? = DB_NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {

    data class Source(val id: Long, val instance: String, val entityId: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE source (id INTEGER PRIMARY KEY AUTOINCREMENT, instance TEXT NOT NULL, " +
            "entity_id TEXT NOT NULL, UNIQUE(instance, entity_id))")
        db.execSQL("CREATE TABLE raw (source INTEGER NOT NULL, ts INTEGER NOT NULL, value REAL, " +
            "PRIMARY KEY(source, ts)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE minute (source INTEGER NOT NULL, start INTEGER NOT NULL, min REAL NOT NULL, " +
            "max REAL NOT NULL, avg REAL NOT NULL, covered INTEGER NOT NULL, count INTEGER NOT NULL, " +
            "PRIMARY KEY(source, start)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE alive (source INTEGER PRIMARY KEY, until INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Первая версия схемы; будущие миграции — здесь, без потери данных.
    }

    fun sourceId(instance: String, entityId: String, create: Boolean = true): Long? {
        val db = writableDatabase
        db.rawQuery("SELECT id FROM source WHERE instance = ? AND entity_id = ?", arrayOf(instance, entityId)).use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        if (!create) return null
        return db.insertOrThrow("source", null, ContentValues().apply {
            put("instance", instance)
            put("entity_id", entityId)
        })
    }

    fun sources(): List<Source> = readableDatabase.rawQuery("SELECT id, instance, entity_id FROM source ORDER BY id", null).use {
        val list = ArrayList<Source>()
        while (it.moveToNext()) list += Source(it.getLong(0), it.getString(1), it.getString(2))
        list
    }

    /**
     * Вставка без перезаписи имеющихся замеров. [fillBreaks]: точка из истории HA заменяет
     * локальную метку разрыва с тем же временем (разрыв закрыт реальными данными).
     */
    fun insert(source: Long, samples: List<PowerSample>, fillBreaks: Boolean = false): Int {
        if (samples.isEmpty()) return 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            val insert = db.compileStatement("INSERT OR IGNORE INTO raw (source, ts, value) VALUES (?, ?, ?)")
            val clear = db.compileStatement("DELETE FROM raw WHERE source = ? AND ts = ? AND value IS NULL")
            samples.forEach { s ->
                if (fillBreaks && s.watts != null) {
                    clear.bindLong(1, source); clear.bindLong(2, s.ts); clear.executeUpdateDelete()
                }
                insert.bindLong(1, source)
                insert.bindLong(2, s.ts)
                if (s.watts == null) insert.bindNull(3) else insert.bindDouble(3, s.watts)
                if (insert.executeInsert() != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun last(source: Long): PowerSample? =
        readableDatabase.rawQuery("SELECT ts, value FROM raw WHERE source = ? ORDER BY ts DESC LIMIT 1", arrayOf(source.toString())).use {
            if (it.moveToFirst()) PowerSample(it.getLong(0), if (it.isNull(1)) null else it.getDouble(1)) else null
        }

    fun aliveUntil(source: Long): Long? =
        readableDatabase.rawQuery("SELECT until FROM alive WHERE source = ?", arrayOf(source.toString())).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }

    fun setAlive(source: Long, until: Long) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO alive (source, until) VALUES (?, ?)", arrayOf<Any>(source, until))
    }

    /**
     * Данные для [from, to): минутные агрегаты и замеры, плюс по одному замеру за краями (значение
     * держится, линия входит в окно). Замеров больше [maxSamples] — прореживание только для экрана:
     * в каждом интервале остаются минимум и максимум в порядке времени и разрывы.
     */
    fun series(source: Long, from: Long, to: Long, maxSamples: Int = Int.MAX_VALUE): PowerSeries {
        val db = readableDatabase
        val id = source.toString()
        val buckets = ArrayList<PowerBucket>()
        db.rawQuery("SELECT start, min, max, avg, covered, count FROM minute WHERE source = ? AND start >= ? AND start < ? ORDER BY start",
            arrayOf(id, (from - PowerHistory.MINUTE_MS).toString(), to.toString())).use {
            while (it.moveToNext()) buckets += PowerBucket(it.getLong(0), it.getDouble(1), it.getDouble(2), it.getDouble(3), it.getLong(4), it.getInt(5))
        }
        val samples = ArrayList<PowerSample>()
        db.rawQuery("SELECT ts, value FROM raw WHERE source = ? AND ts < ? ORDER BY ts DESC LIMIT 1", arrayOf(id, from.toString())).use {
            if (it.moveToFirst()) samples += PowerSample(it.getLong(0), if (it.isNull(1)) null else it.getDouble(1))
        }
        val count = db.rawQuery("SELECT COUNT(*) FROM raw WHERE source = ? AND ts >= ? AND ts < ?", arrayOf(id, from.toString(), to.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        db.rawQuery("SELECT ts, value FROM raw WHERE source = ? AND ts >= ? AND ts < ? ORDER BY ts", arrayOf(id, from.toString(), to.toString())).use { c ->
            if (count <= maxSamples) {
                while (c.moveToNext()) samples += PowerSample(c.getLong(0), if (c.isNull(1)) null else c.getDouble(1))
            } else {
                val width = ((to - from) / (maxSamples / 2).coerceAtLeast(1)).coerceAtLeast(1)
                val reducer = Reducer(samples)
                while (c.moveToNext()) {
                    val ts = c.getLong(0)
                    reducer.add((ts - from) / width, ts, if (c.isNull(1)) null else c.getDouble(1))
                }
                reducer.flush()
            }
        }
        db.rawQuery("SELECT ts, value FROM raw WHERE source = ? AND ts >= ? ORDER BY ts LIMIT 1", arrayOf(id, to.toString())).use {
            if (it.moveToFirst()) samples += PowerSample(it.getLong(0), if (it.isNull(1)) null else it.getDouble(1))
        }
        return PowerSeries(buckets, samples)
    }

    /** Прореживание по интервалам: минимум и максимум (в порядке времени), разрыв в конце интервала. */
    private class Reducer(private val out: MutableList<PowerSample>) {
        private var bucket = Long.MIN_VALUE
        private var minTs = 0L; private var min = 0.0
        private var maxTs = 0L; private var max = 0.0
        private var any = false
        private var breakTs: Long? = null

        fun add(index: Long, ts: Long, value: Double?) {
            if (index != bucket) { flush(); bucket = index }
            if (value == null) { breakTs = ts; return }
            if (breakTs != null) { flush(); bucket = index }
            if (!any || value < min) { min = value; minTs = ts }
            if (!any || value > max) { max = value; maxTs = ts }
            any = true
        }

        fun flush() {
            if (any) {
                if (minTs <= maxTs) { out += PowerSample(minTs, min); if (maxTs != minTs) out += PowerSample(maxTs, max) }
                else { out += PowerSample(maxTs, max); out += PowerSample(minTs, min) }
            }
            breakTs?.let { out += PowerSample(it, null) }
            any = false
            breakTs = null
        }
    }

    /** Мин/Макс/Средняя за [from, to) потоком по курсору, без загрузки всех замеров. */
    fun stats(source: Long, from: Long, to: Long, now: Long): PowerStats {
        val acc = PowerHistory.StatsAccumulator(from, minOf(to, now))
        val db = readableDatabase
        val id = source.toString()
        db.rawQuery("SELECT start, min, max, avg, covered, count FROM minute WHERE source = ? AND start >= ? AND start < ? ORDER BY start",
            arrayOf(id, (from - PowerHistory.MINUTE_MS).toString(), to.toString())).use {
            while (it.moveToNext()) acc.bucket(PowerBucket(it.getLong(0), it.getDouble(1), it.getDouble(2), it.getDouble(3), it.getLong(4), it.getInt(5)))
        }
        db.rawQuery("SELECT ts, value FROM raw WHERE source = ? AND ts < ? ORDER BY ts DESC LIMIT 1", arrayOf(id, from.toString())).use {
            if (it.moveToFirst()) acc.sample(it.getLong(0), if (it.isNull(1)) null else it.getDouble(1))
        }
        db.rawQuery("SELECT ts, value FROM raw WHERE source = ? AND ts >= ? AND ts < ? ORDER BY ts", arrayOf(id, from.toString(), to.toString())).use {
            while (it.moveToNext()) acc.sample(it.getLong(0), if (it.isNull(1)) null else it.getDouble(1))
        }
        db.rawQuery("SELECT ts, value FROM raw WHERE source = ? AND ts >= ? ORDER BY ts LIMIT 1", arrayOf(id, to.toString())).use {
            if (it.moveToFirst()) acc.sample(it.getLong(0), if (it.isNull(1)) null else it.getDouble(1))
        }
        return acc.finish()
    }

    /**
     * Интервалы без локальных данных в [from, to): от метки разрыва до следующего замера, а также
     * от [from] до первого замера (истории ещё нет). Для подгрузки из истории HA.
     */
    fun gaps(source: Long, from: Long, to: Long): List<LongRange> {
        val result = ArrayList<LongRange>()
        // До окна держалось значение — начало окна не пробел.
        var gapStart: Long? = if (hasDataBefore(source, from)) null else from
        readableDatabase.rawQuery("SELECT ts, value FROM raw WHERE source = ? AND ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(source.toString(), from.toString(), to.toString())).use {
            while (it.moveToNext()) {
                val ts = it.getLong(0)
                gapStart?.let { start -> if (ts > start) result += start until ts }
                gapStart = if (it.isNull(1)) ts else null
            }
        }
        gapStart?.let { if (to > it) result += it until to }
        return result
    }

    private fun hasDataBefore(source: Long, ts: Long): Boolean {
        readableDatabase.rawQuery("SELECT value FROM raw WHERE source = ? AND ts < ? ORDER BY ts DESC LIMIT 1",
            arrayOf(source.toString(), ts.toString())).use { if (it.moveToFirst()) return !it.isNull(0) }
        return false
    }

    /**
     * Уплотнение: замеры старше 7 дней → минутные агрегаты (в одной транзакции с удалением);
     * значение, державшееся на границе, остаётся точкой на самой границе. Старше 35 дней — удаление.
     */
    fun compact(now: Long) {
        val cutoff = Math.floorDiv(now - PowerHistory.RAW_KEEP_MS, PowerHistory.MINUTE_MS) * PowerHistory.MINUTE_MS
        val db = writableDatabase
        sources().forEach { source -> compactSource(db, source.id, cutoff) }
        val oldest = now - PowerHistory.KEEP_MS
        db.execSQL("DELETE FROM minute WHERE start < ?", arrayOf<Any>(oldest))
        db.execSQL("DELETE FROM raw WHERE ts < ?", arrayOf<Any>(oldest))
    }

    private class Acc(var min: Double, var max: Double, var sum: Double = 0.0, var covered: Long = 0, var count: Int = 0)

    private fun compactSource(db: SQLiteDatabase, source: Long, cutoff: Long) {
        val id = source.toString()
        val rows = ArrayList<PowerSample>()
        db.rawQuery("SELECT ts, value FROM raw WHERE source = ? AND ts < ? ORDER BY ts", arrayOf(id, cutoff.toString())).use {
            while (it.moveToNext()) rows += PowerSample(it.getLong(0), if (it.isNull(1)) null else it.getDouble(1))
        }
        if (rows.isEmpty()) return
        val next = db.rawQuery("SELECT ts FROM raw WHERE source = ? AND ts >= ? ORDER BY ts LIMIT 1", arrayOf(id, cutoff.toString())).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
        val minutes = sortedMapOf<Long, Acc>()
        fun acc(minute: Long, value: Double) = minutes.getOrPut(minute) { Acc(value, value) }
        rows.forEachIndexed { i, s ->
            val value = s.watts ?: return@forEachIndexed
            val stop = minOf(if (i + 1 < rows.size) rows[i + 1].ts else (next ?: cutoff), cutoff)
            acc(Math.floorDiv(s.ts, PowerHistory.MINUTE_MS) * PowerHistory.MINUTE_MS, value).count++
            var t = s.ts
            while (t < stop) {
                val minute = Math.floorDiv(t, PowerHistory.MINUTE_MS) * PowerHistory.MINUTE_MS
                val until = minOf(stop, minute + PowerHistory.MINUTE_MS)
                acc(minute, value).apply {
                    min = minOf(min, value); max = maxOf(max, value)
                    sum += value * (until - t); covered += until - t
                }
                t = until
            }
        }
        val carry = rows.last().watts?.takeIf { next == null || next > cutoff }
        db.beginTransaction()
        try {
            val insert = db.compileStatement("INSERT OR REPLACE INTO minute (source, start, min, max, avg, covered, count) VALUES (?, ?, ?, ?, ?, ?, ?)")
            minutes.forEach { (start, a) ->
                insert.bindLong(1, source); insert.bindLong(2, start)
                insert.bindDouble(3, a.min); insert.bindDouble(4, a.max)
                insert.bindDouble(5, if (a.covered > 0) a.sum / a.covered else (a.min + a.max) / 2)
                insert.bindLong(6, a.covered); insert.bindLong(7, a.count.toLong())
                insert.executeInsert()
            }
            db.execSQL("DELETE FROM raw WHERE source = ? AND ts < ?", arrayOf<Any>(source, cutoff))
            if (carry != null) db.execSQL("INSERT OR IGNORE INTO raw (source, ts, value) VALUES (?, ?, ?)", arrayOf<Any>(source, cutoff, carry))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val DB_NAME = "power_history.db"
        const val VERSION = 1
    }
}
