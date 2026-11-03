package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Регистратор: только текущие источники, разрывы, разные серверы HA, подгрузка пропусков только чтением. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PowerHistoryRecorderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = PowerHistoryStore(context, name = null)
    private var now = 1_800_000_000_000L
    private val reads = mutableListOf<Pair<String, JSONObject>>()
    private var answer: (String, JSONObject) -> Any? = { _, _ -> null }

    private fun recorder() = PowerHistoryRecorder(store, io = { it.run() }, post = { it() }, clock = { now }).also { r ->
        r.reader = { type, params, result -> reads += type to params; result(answer(type, params)) }
    }
    private fun w(unit: String = "W") = JSONObject().put("unit_of_measurement", unit)
    private fun samples(instance: String, id: String) =
        store.sourceId(instance, id, create = false)?.let { store.series(it, 0, Long.MAX_VALUE).samples } ?: emptyList()

    @After fun close() = store.close()

    @Test fun recordsOnlySourcesConvertsUnitsAndMarksUnavailableAsBreak() {
        val r = recorder()
        r.setSources(setOf("sensor.inv"))
        r.setInstance("home")
        r.onState("sensor.other", "500", w()); now += 1000
        r.onState("sensor.inv", "1.5", w("kW")); now += 1000
        r.onState("sensor.inv", "1.5", w("kW")); now += 1000 // повтор не пишется
        r.onState("sensor.inv", "unavailable", w()); now += 1000
        r.onState("sensor.inv", "-820", w()); now += 1000
        r.flush()
        assertEquals(listOf(1500.0, null, -820.0), samples("home", "sensor.inv").map { it.watts })
        assertNull(store.sourceId("home", "sensor.other", create = false))
        assertEquals(-820.0, r.latest("sensor.inv")!!, 0.0)
    }

    @Test fun samplesBeforeServerIsKnownWaitInMemory() {
        val r = recorder()
        r.setSources(setOf("sensor.inv"))
        r.onState("sensor.inv", "10", w())
        r.flush()
        assertTrue(store.sources().isEmpty())
        r.setInstance("home")
        assertEquals(listOf(10.0), samples("home", "sensor.inv").map { it.watts })
    }

    @Test fun disconnectAndAppRestartLeaveGapsNotHeldValues() {
        val r = recorder()
        r.setSources(setOf("sensor.inv"))
        r.setInstance("home")
        r.onState("sensor.inv", "100", w()); r.flush()
        now += 60_000
        r.onDisconnected()
        val afterDisconnect = samples("home", "sensor.inv")
        assertEquals(PowerSample(now, null), afterDisconnect.last())

        // Новый процесс: прошлая запись оборвалась без метки — разрыв ставится сразу после «запись шла».
        r.setInstance("home"); now += 1000; r.onState("sensor.inv", "200", w()); r.flush()
        val alive = now
        now += 3_600_000
        val restarted = recorder()
        restarted.setSources(setOf("sensor.inv"))
        restarted.setInstance("home")
        val list = samples("home", "sensor.inv")
        assertEquals(PowerSample(alive + 1, null), list.last())
    }

    @Test fun switchingSourceKeepsArchiveAndServersDoNotMix() {
        val r = recorder()
        r.setSources(setOf("sensor.a"))
        r.setInstance("home1")
        r.onState("sensor.a", "1", w()); r.flush()
        now += 1000
        r.setSources(setOf("sensor.b"))
        r.onState("sensor.b", "2", w()); r.flush()
        assertEquals(listOf(1.0, null), samples("home1", "sensor.a").map { it.watts })
        assertEquals(listOf(2.0), samples("home1", "sensor.b").map { it.watts })
        r.onDisconnected()
        r.setInstance("home2")
        r.onState("sensor.b", "3", w()); r.flush()
        assertEquals(listOf(3.0), samples("home2", "sensor.b").map { it.watts })
        assertEquals(2.0, samples("home1", "sensor.b").first().watts!!, 0.0)
    }

    @Test fun haHistoryFillsGapsWithReadOnlyRequestsOnly() {
        val r = recorder()
        r.setSources(setOf("sensor.inv"))
        r.setInstance("home")
        r.onState("sensor.inv", "100", w()); r.flush()
        val gapStart = now + 1_000
        now += 1_000; r.onDisconnected()
        now += 600_000
        // HA помнит: в момент разрыва было 100 Вт, через 5 минут −50 Вт.
        answer = { type, _ ->
            assertEquals(PowerHistoryRecorder.HISTORY, type)
            JSONObject().put("sensor.inv", JSONArray()
                .put(JSONObject().put("s", "100").put("lu", gapStart / 1000.0))
                .put(JSONObject().put("s", "-50").put("lu", (gapStart + 300_000) / 1000.0))
                .put(JSONObject().put("s", "999").put("lu", (now + 60_000) / 1000.0)))
        }
        r.setInstance("home")
        r.onState("sensor.inv", "70", w())
        r.onSnapshotDone()
        r.flush()
        assertTrue(reads.isNotEmpty())
        assertTrue(reads.all { it.first == PowerHistoryRecorder.HISTORY })
        val params = reads.first().second
        assertEquals("sensor.inv", params.getJSONArray("entity_ids").getString(0))
        assertTrue(params.getBoolean("no_attributes"))
        val list = samples("home", "sensor.inv")
        assertEquals(listOf(100.0, 100.0, -50.0, 70.0), list.map { it.watts })
        assertFalse(list.any { it.watts == 999.0 })
    }

    @Test fun serverKeyIgnoresAddressesAndRejectsEmptyConfig() {
        val config = JSONObject().put("location_name", "Дом").put("time_zone", "Europe/Moscow")
            .put("latitude", 55.0).put("longitude", 37.0).put("internal_url", "http://a").put("external_url", "https://b")
        val sameHomeOtherUrl = JSONObject(config.toString()).put("internal_url", "http://c")
        val key = PowerHistoryRecorder.instanceKey(config)!!
        assertEquals(16, key.length)
        assertEquals(key, PowerHistoryRecorder.instanceKey(sameHomeOtherUrl))
        assertNotEquals(key, PowerHistoryRecorder.instanceKey(JSONObject(config.toString()).put("location_name", "Дача")))
        assertNull(PowerHistoryRecorder.instanceKey(null))
        assertNull(PowerHistoryRecorder.instanceKey(JSONObject()))
        assertFalse(key.contains("55"))
    }

    @Test fun graphSourceDefaultsToCardPowerSensor() {
        val base = PowerModernConfig.profile(PowerModernConfig.PROFILE_INVERTER)
        val withPower = base.withRow(base.row(PowerModernConfig.ROLE_POWER)!!.copy(entityId = "sensor.out_power"))
        assertEquals("sensor.out_power", withPower.graphSource)
        val chosen = withPower.copy(graphEntityId = "sensor.other")
        assertEquals("sensor.other", PowerModernConfig.fromJson(chosen.toJson()).graphSource)
        assertEquals("", base.graphSource)
        // Без выбора ключ в config не появляется — старые плитки читаются как раньше.
        assertFalse(withPower.toJson().has("graph_entity_id"))
        val tiles = listOf(
            TileEntity("p1", PowerModernConfig.TYPE, "", "Инвертор", 0, 0, 1, 1, config = withPower.writeTo("{}")),
            TileEntity("p2", PowerModernConfig.TYPE, "", "Сеть", 0, 0, 1, 1, config = base.writeTo("{}")),
            TileEntity("b", "button", "light.x", "Свет", 0, 0, 1, 1))
        assertEquals(setOf("sensor.out_power"), PowerModernConfig.graphSources(tiles))
    }

    @Test fun webSocketReadAcceptsOnlyReadOnlyCommands() {
        val socket = HomeAssistantWebSocket("http://127.0.0.1:1", "t", onStateChanged = { _, _, _ -> }, onConnected = {}, onDisconnected = {})
        var result: Any? = "unset"
        socket.read("get_config") { result = it }
        assertNull(result) // не подключено — сразу отказ, без отправки
        assertThrows(IllegalArgumentException::class.java) { socket.read("call_service") {} }
    }
}
