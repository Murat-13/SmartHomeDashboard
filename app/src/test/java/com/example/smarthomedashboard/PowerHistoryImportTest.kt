package com.example.smarthomedashboard

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 0.9.5 — подгрузка истории мощности из HA: пропуски по обеим таблицам и отметкам «проверено»,
 * запросы по одному куску ≤ 6 ч, старые куски сразу в минутные агрегаты, миграция базы 0.9.4 без потерь.
 * Только искусственные данные; запросы — через подставной reader, без сети.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PowerHistoryImportTest {
    private val context = RuntimeEnvironment.getApplication()
    private val stores = mutableListOf<PowerHistoryStore>()
    private val store = PowerHistoryStore(context, name = null).also { stores += it }
    private var now = 1_800_000_000_000L
    private val min = PowerHistory.MINUTE_MS
    private val hour = PowerHistory.HOUR_MS
    private val day = PowerHistory.DAY_MS

    /** Запросы истории ждут ответа теста: видно, сколько их в полёте одновременно. */
    private val waiting = ArrayDeque<Pair<JSONObject, (Any?) -> Unit>>()
    private val sent = mutableListOf<JSONObject>()
    private val made = mutableListOf<PowerHistoryRecorder>()

    private fun recorder() = PowerHistoryRecorder(store, worker = { it.run() }, post = { it() }, clock = { now }).also { r ->
        made += r
        r.reader = { type, params, result ->
            assertEquals(PowerHistoryRecorder.HISTORY, type)
            sent += params
            waiting.addLast(params to result)
        }
    }

    @After fun close() {
        made.forEach { it.stop() }
        stores.forEach { it.close() }
    }

    private fun w() = JSONObject().put("unit_of_measurement", "W")
    private fun ms(params: JSONObject, key: String) = java.time.Instant.parse(params.getString(key)).toEpochMilli()
    private fun answerAll(rows: (JSONObject) -> JSONArray = { JSONArray() }) {
        while (waiting.isNotEmpty()) {
            val (params, result) = waiting.removeFirst()
            result(JSONObject().put("sensor.inv", rows(params)))
        }
    }

    /** Регистратор, подключённый к серверу «home», с источником sensor.inv и известной единицей. */
    private fun connected(r: PowerHistoryRecorder = recorder()): PowerHistoryRecorder {
        r.setSources(setOf("sensor.inv"))
        r.setInstance("home")
        r.onState("sensor.inv", "100", w())
        r.flush()
        r.onSnapshotDone()
        return r
    }

    @Test fun compactedMinutesAreNotGapsAnymore() {
        val s = store.sourceId("home", "sensor.inv")!!
        val t0 = now - 20 * day
        store.insert(s, listOf(PowerSample(t0, 50.0), PowerSample(t0 + hour, null)))
        store.compact(now)
        // Точных замеров старше 7 дней нет, но минутные агрегаты есть — это не пропуск.
        val gaps = store.gaps(s, t0, t0 + 2 * hour)
        assertEquals(listOf((t0 + hour) until (t0 + 2 * hour)), gaps)
    }

    @Test fun checkedPeriodsAreNotGapsAndOldMarksAreDropped() {
        val s = store.sourceId("home", "sensor.inv")!!
        val t0 = now - 2 * day
        store.markChecked(s, t0, t0 + hour)
        assertEquals(listOf((t0 + hour) until (t0 + 2 * hour)), store.gaps(s, t0, t0 + 2 * hour))
        store.markChecked(s, now - 40 * day, now - 39 * day)
        store.compact(now)
        assertEquals("Отметка старше 35 дней удалена", listOf((now - 40 * day) until (now - 39 * day)),
            store.gaps(s, now - 40 * day, now - 39 * day))
    }

    @Test fun historyIsRequestedOneChunkAtATimeAndNoLongerThanSixHours() {
        connected()
        assertEquals("Одновременно в полёте только один запрос", 1, waiting.size)
        repeat(5) {
            val (params, result) = waiting.removeFirst()
            assertTrue(ms(params, "end_time") - ms(params, "start_time") <= 6 * hour)
            result(JSONObject().put("sensor.inv", JSONArray()))
            assertEquals(1, waiting.size)
        }
        // Новые куски раньше старых.
        val ends = sent.take(6).map { ms(it, "end_time") }
        assertEquals(ends.sortedDescending(), ends)
    }

    @Test fun answeredPeriodsAreNotRequestedAgainAfterReconnect() {
        val r = connected()
        answerAll()
        val first = sent.size
        assertTrue("35 дней кусками по 6 ч", first >= 35 * 4)
        assertTrue(sent.all { ms(it, "start_time") >= now - 35 * day - 1000 })

        // Переподключение через 15 минут: запрашивается только новое, а не 35 дней заново.
        r.onDisconnected()
        now += 15 * min
        sent.clear()
        r.setInstance("home")
        r.onState("sensor.inv", "100", w())
        r.flush()
        r.onSnapshotDone()
        answerAll()
        assertTrue("После переподключения — только недавние минуты: ${sent.size}", sent.size <= 3)
        assertTrue(sent.all { ms(it, "start_time") >= now - 30 * min })
    }

    @Test fun recentChunkIsNotMarkedCheckedBecauseHaMayNotHaveWrittenIt() {
        val s = store.sourceId("home", "sensor.inv")!!
        val r = recorder()
        r.setSources(setOf("sensor.inv"))
        r.setInstance("home")
        // Данные были до разрыва 3 минуты назад: пропуск — только последние минуты.
        store.insert(s, (0..35 * 24).map { PowerSample(now - 35 * day + it * hour, 10.0) } + PowerSample(now - 3 * min, null))
        r.onState("sensor.inv", "100", w())
        r.onSnapshotDone()
        answerAll()
        assertTrue(sent.isNotEmpty())
        // HA ничего не вернул за последние минуты, но кусок не отмечен: при следующем подключении спросим снова.
        assertTrue(store.gaps(s, now - 3 * min, now - 1000).isNotEmpty())
    }

    @Test fun failedRequestStopsQueueWithoutMarking() {
        val r = connected()
        val s = store.sourceId("home", "sensor.inv")!!
        val (params, result) = waiting.removeFirst()
        result(null)
        assertTrue("Отказ — очередь остановлена до следующего подключения", waiting.isEmpty())
        assertEquals(1, sent.size)
        val start = ms(params, "start_time"); val end = ms(params, "end_time")
        assertEquals(listOf(start until end), store.gaps(s, start, end))
        r.onDisconnected()
    }

    @Test fun oldChunkGoesStraightToMinuteAggregatesAndRecentToRawSamples() {
        connected()
        val oldTs = now - 20 * day + 30 * min
        val recentTs = now - 2 * day + 30 * min
        answerAll { params ->
            val range = ms(params, "start_time") until ms(params, "end_time")
            JSONArray().apply {
                if (oldTs in range) put(JSONObject().put("s", "-250").put("lu", oldTs / 1000.0))
                if (recentTs in range) put(JSONObject().put("s", "420").put("lu", recentTs / 1000.0))
            }
        }
        val s = store.sourceId("home", "sensor.inv")!!
        val buckets = mutableListOf<PowerBucket>()
        store.forEachMinute(s) { buckets += it }
        assertTrue("Старая точка — минутный агрегат", buckets.any { it.start == oldTs && it.min == -250.0 })
        val raw = mutableListOf<Pair<Long, Double?>>()
        store.forEachRaw(s) { ts, v -> raw += ts to v }
        assertTrue("Старых точных замеров нет", raw.none { it.first < now - 7 * day })
        assertTrue("Недавняя точка — точный замер", raw.any { it.first == recentTs && it.second == 420.0 })
    }

    @Test fun databaseFrom094IsUpgradedWithoutLosingData() {
        val name = "power_history_upgrade_test.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, 0, null).use { db ->
            // Схема версии 1 (0.9.4) без таблицы checked.
            db.execSQL("CREATE TABLE source (id INTEGER PRIMARY KEY AUTOINCREMENT, instance TEXT NOT NULL, " +
                "entity_id TEXT NOT NULL, UNIQUE(instance, entity_id))")
            db.execSQL("CREATE TABLE raw (source INTEGER NOT NULL, ts INTEGER NOT NULL, value REAL, PRIMARY KEY(source, ts)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE minute (source INTEGER NOT NULL, start INTEGER NOT NULL, min REAL NOT NULL, max REAL NOT NULL, " +
                "avg REAL NOT NULL, covered INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(source, start)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE alive (source INTEGER PRIMARY KEY, until INTEGER NOT NULL)")
            db.execSQL("INSERT INTO source (id, instance, entity_id) VALUES (1, 'home', 'sensor.inv')")
            db.execSQL("INSERT INTO raw (source, ts, value) VALUES (1, ?, 123.0)", arrayOf<Any>(now - hour))
            db.version = 1
        }
        val upgraded = PowerHistoryStore(context, name).also { stores += it }
        assertEquals(PowerHistoryStore.VERSION, upgraded.readableDatabase.version)
        assertEquals(123.0, upgraded.last(1)!!.watts!!, 0.0)
        upgraded.markChecked(1, now - 3 * hour, now - 2 * hour)
        assertEquals(listOf((now - 2 * hour) until (now - hour)), upgraded.gaps(1, now - 3 * hour, now - hour))
        upgraded.close()
        context.deleteDatabase(name)
    }

    private inline fun <T> SQLiteDatabase.use(block: (SQLiteDatabase) -> T): T = try { block(this) } finally { close() }
}
