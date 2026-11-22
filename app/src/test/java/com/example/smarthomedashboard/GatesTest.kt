package com.example.smarthomedashboard

import android.os.Looper
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Калитки и ворота, этап 1 (docs/gate-tz.md): модель, команды, положение, хранение и исполнение. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class GatesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val states = HashMap<String, String>()
    private val attrs = HashMap<String, JSONObject>()
    private var connected = true
    private val readings get() = BatteryReadings(HaSession(connected, connected), states::get, { false }, attrs::get)

    @Before fun setUp() {
        GateHub.reset()
        context.getSharedPreferences("dashboard_prefs", 0).edit().clear().commit()
        TileManager(context).saveTiles(emptyList())
    }

    @After fun tearDown() = GateHub.reset()

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private val relay = Gate("g1", "Калитка", Gate.Kind.RELAY, open = "switch.gate_relay")
    private val cover = Gate("g2", "Ворота", Gate.Kind.COVER, open = "cover.gate", pedestrian = "button.gate_walk", sensor = "binary_sensor.gate")
    private val lock = Gate("g3", "Калитка", Gate.Kind.LOCK, open = "lock.wicket", lockButton = true)

    // ---- Команды и кнопки ----

    @Test fun commandsByKindAndEntity() {
        val cmd = { g: Gate, a: Gate.Action -> GateLogic.command(g, a, readings) }
        assertEquals(GateLogic.Command("switch", "turn_on", "switch.gate_relay"), cmd(relay, Gate.Action.OPEN))
        assertEquals(GateLogic.Command("button", "press", "button.r"), cmd(relay.copy(open = "button.r"), Gate.Action.OPEN))
        assertEquals(GateLogic.Command("script", "turn_on", "script.open"), cmd(relay.copy(open = "script.open"), Gate.Action.OPEN))
        assertNull("У реле нет «Закрыть»", cmd(relay, Gate.Action.CLOSE))
        assertNull("Реле — не замок", cmd(relay.copy(open = "lock.x"), Gate.Action.OPEN))
        // Замок: отщёлкнуть, если умеет (LockEntityFeature.OPEN), иначе снять с запирания.
        assertEquals("unlock", cmd(lock, Gate.Action.OPEN)?.service)
        attrs["lock.wicket"] = JSONObject().put("supported_features", 1); states["lock.wicket"] = "locked"
        assertEquals("open", cmd(lock, Gate.Action.OPEN)?.service)
        assertEquals("lock", cmd(lock, Gate.Action.CLOSE)?.service)
        // «Запереть» есть у любого замка («Закрыть само»), но кнопка — только если её включили.
        assertEquals("lock", cmd(lock.copy(lockButton = false), Gate.Action.CLOSE)?.service)
        assertEquals(listOf(Gate.Action.OPEN), GateLogic.actions(lock.copy(lockButton = false), readings))
        // Привод.
        assertEquals("open_cover", cmd(cover, Gate.Action.OPEN)?.service)
        assertEquals("close_cover", cmd(cover, Gate.Action.CLOSE)?.service)
        assertEquals("stop_cover", cmd(cover, Gate.Action.STOP)?.service)
        assertEquals(GateLogic.Command("button", "press", "button.gate_walk"), cmd(cover, Gate.Action.PEDESTRIAN))
        // «Шаг» и свой сценарий.
        val step = Gate("s", "Ворота", Gate.Kind.STEP, open = "switch.step")
        assertEquals("turn_on", cmd(step, Gate.Action.STEP)?.service)
        val script = Gate("sc", "Ворота", Gate.Kind.SCRIPT, open = "script.gate_open", close = "automation.gate_close")
        assertEquals(GateLogic.Command("script", "turn_on", "script.gate_open"), cmd(script, Gate.Action.OPEN))
        assertEquals(GateLogic.Command("automation", "trigger", "automation.gate_close"), cmd(script, Gate.Action.CLOSE))
    }

    @Test fun buttonsByKindStopOnlyWhenDriveCanStop() {
        assertEquals(listOf(Gate.Action.OPEN), GateLogic.actions(relay, readings))
        assertEquals(listOf(Gate.Action.OPEN, Gate.Action.CLOSE), GateLogic.actions(lock, readings))
        assertEquals("Привод не сообщает, что умеет «Стоп», — кнопки нет", listOf(Gate.Action.OPEN, Gate.Action.CLOSE, Gate.Action.PEDESTRIAN),
            GateLogic.actions(cover, readings))
        states["cover.gate"] = "closed"; attrs["cover.gate"] = JSONObject().put("supported_features", 1 or 2 or 8)
        assertEquals("В покое «Стоп» не нужен", listOf(Gate.Action.OPEN, Gate.Action.CLOSE, Gate.Action.PEDESTRIAN), GateLogic.actions(cover, readings))
        states["cover.gate"] = "opening"
        assertEquals("Ворота движутся — «Стоп»", listOf(Gate.Action.OPEN, Gate.Action.CLOSE, Gate.Action.STOP, Gate.Action.PEDESTRIAN),
            GateLogic.actions(cover, readings))
        assertTrue(GateLogic.instant(Gate.Action.STOP))
        assertFalse(GateLogic.instant(Gate.Action.OPEN))
    }

    @Test fun labelsReadNaturally() {
        val closed = GateStatus(GateStatus.Position.CLOSED, GateStatus.Source.SENSOR)
        assertEquals("Открыть калитку", GateLogic.label(relay, Gate.Action.OPEN, closed))
        assertEquals("Закрыть ворота", GateLogic.label(cover, Gate.Action.CLOSE, closed))
        assertEquals("Запереть калитку", GateLogic.label(lock, Gate.Action.CLOSE, closed))
        assertEquals("Открыть калитку 2", GateLogic.label(relay.copy(name = "Калитка 2"), Gate.Action.OPEN, closed))
        assertEquals("Открыть: Задний двор", GateLogic.label(relay.copy(name = "Задний двор"), Gate.Action.OPEN, closed))
        val step = Gate("s", "Ворота", Gate.Kind.STEP, open = "switch.step", sensor = "binary_sensor.gate")
        assertEquals("Открыть ворота", GateLogic.label(step, Gate.Action.STEP, closed))
        assertEquals("Закрыть ворота", GateLogic.label(step, Gate.Action.STEP, GateStatus(GateStatus.Position.OPEN, GateStatus.Source.SENSOR)))
        assertEquals("Ворота: открыть или закрыть", GateLogic.label(step, Gate.Action.STEP, GateStatus.UNKNOWN))
    }

    // ---- Положение ----

    @Test fun positionFromSensorDeviceOrNothing() {
        // Нет связи — так и пишем.
        connected = false
        assertEquals(GateStatus.Position.OFFLINE, GateLogic.status(relay, readings).position)
        connected = true
        // Реле: включено — открыто (по реле), выключено — закрыто.
        states["switch.gate_relay"] = "on"
        assertEquals(GateStatus(GateStatus.Position.OPEN, GateStatus.Source.DEVICE), GateLogic.status(relay, readings))
        // Датчик важнее реле.
        states["binary_sensor.door"] = "off"
        assertEquals(GateStatus(GateStatus.Position.CLOSED, GateStatus.Source.SENSOR), GateLogic.status(relay.copy(sensor = "binary_sensor.door"), readings))
        // Кнопка без датчика: положения нет.
        states["button.r"] = "2026-11-22T10:00:00"
        assertEquals(GateStatus.Position.UNKNOWN, GateLogic.status(relay.copy(open = "button.r"), readings).position)
        // Замок.
        states["lock.wicket"] = "jammed"
        assertEquals(GateStatus.Position.JAMMED, GateLogic.status(lock, readings).position)
        states["lock.wicket"] = "unlocking"
        assertEquals(GateStatus.Position.OPENING, GateLogic.status(lock, readings).position)
        // Привод: движение — по приводу, даже если датчик ещё «закрыто»; иначе датчик; положение в процентах.
        states["binary_sensor.gate"] = "off"
        states["cover.gate"] = "opening"; attrs["cover.gate"] = JSONObject().put("current_position", 40)
        assertEquals(GateStatus(GateStatus.Position.OPENING, GateStatus.Source.DEVICE, 40), GateLogic.status(cover, readings))
        states["cover.gate"] = "open"
        assertEquals(GateStatus.Position.CLOSED, GateLogic.status(cover, readings).position)
        assertEquals("открывается… 40 %", GateLogic.statusText(GateStatus(GateStatus.Position.OPENING, GateStatus.Source.DEVICE, 40)))
        assertEquals("закрыто (датчик)", GateLogic.statusText(GateStatus(GateStatus.Position.CLOSED, GateStatus.Source.SENSOR)))
    }

    // ---- Хранение и переход со старого поля «Замок калитки» ----

    private fun doorbell(id: String, lock: String = "", gates: List<String>? = null) = TileEntity(id, DoorbellConfig.TYPE, "grid", "Домофон",
        0, 0, 1, 1, config = DoorbellConfig(lock = lock, gates = gates).writeTo(DoorbellConfig.newTileConfig()))

    @Test fun oldLockFieldBecomesWicketShownOnSameTile() {
        TileManager(context).saveTiles(listOf(doorbell("door", lock = "switch.gate_relay"), doorbell("door2")))
        val gates = GateStore.load(context)
        assertEquals(listOf(Gate(GateStore.legacyId("door"), "Калитка", Gate.Kind.RELAY, open = "switch.gate_relay")), gates)
        val config = DoorbellConfig.from(TileManager(context).loadTiles().first { it.id == "door" }.config)
        assertEquals("Настройки плитки не переписаны: прежняя версия работает как раньше", "switch.gate_relay", config.lock)
        assertEquals(listOf(GateStore.legacyId("door")), config.gateIds("door"))
        assertEquals(listOf(gates.single()), GateHub.forTile(context, "door", config))
        assertTrue(DoorbellConfig.from(TileManager(context).loadTiles().first { it.id == "door2" }.config).gateIds("door2").isEmpty())
        // Список сохранён: повторное чтение не создаёт заново (удалённый владельцем проход не возвращается).
        GateStore.save(context, emptyList())
        assertTrue(GateStore.load(context).isEmpty())
        assertTrue(GateHub.forTile(context, "door", config).isEmpty())
    }

    @Test fun ownGateListOfTileWinsAndRoundTrips() {
        val config = DoorbellConfig(lock = "switch.old", gates = listOf("gate_2", "gate_1"))
        val back = DoorbellConfig.from(config.writeTo(DoorbellConfig.newTileConfig()))
        assertEquals(listOf("gate_2", "gate_1"), back.gateIds("door"))
        assertNull("У плитки без списка ключа нет", DoorbellConfig.from(DoorbellConfig().writeTo(DoorbellConfig.newTileConfig())).gates)
        GateStore.save(context, listOf(cover, relay.copy(id = "gate_1"), cover.copy(id = "gate_2")))
        assertEquals(listOf("gate_2", "gate_1"), GateHub.forTile(context, "door", back).map { it.id })
    }

    @Test fun damagedEntriesAreSkippedOthersSurvive() {
        val raw = """[{"id":"a","name":"Калитка","kind":"relay","open":"switch.x"},{"id":"","name":"x","kind":"relay"},
            {"id":"b","name":"Ворота","kind":"нет такого"},"мусор",{"id":"c","name":"Ворота","kind":"cover","open":"cover.g","confirm_seconds":-5}]"""
        val gates = GateStore.parse(raw)
        assertEquals(listOf("a", "c"), gates.map { it.id })
        assertEquals(Gate.DEFAULT_CONFIRM, gates[1].confirmSeconds)
        assertTrue(GateStore.parse("не json").isEmpty())
        val back = GateStore.parse(org.json.JSONArray().put(cover.toJson()).toString())
        assertEquals(listOf(cover), back)
    }

    @Test fun pinModes() {
        assertEquals(GatePin.OFF, GateStore.pin(context))
        assertFalse(GatePin.OFF.required(inRingWindow = false))
        assertFalse(GatePin.OUTSIDE_RING.required(inRingWindow = true))
        assertTrue(GatePin.OUTSIDE_RING.required(inRingWindow = false))
        assertTrue(GatePin.ALWAYS.required(inRingWindow = true))
        GateStore.setPin(context, GatePin.OUTSIDE_RING)
        assertEquals(GatePin.OUTSIDE_RING, GateStore.pin(context))
        assertTrue("Входит в экспорт", GateStore.KEY in ConfigBackup.STRING_DEFAULTS && GateStore.KEY_PIN in ConfigBackup.STRING_DEFAULTS)
    }

    // ---- Исполнение ----

    private val sent = mutableListOf<GateLogic.Command>()
    private var answer: ServiceCommandResult? = ServiceCommandResult.ACKNOWLEDGED

    private fun hub(vararg gates: Gate) {
        GateStore.save(context, gates.toList())
        GateHub.attach(context)
        GateHub.driver = GateHub.Driver { command, onResult -> sent += command; answer?.let(onResult); true }
        GateHub.onState(context, readings)
    }

    private fun state(entity: String, value: String) {
        states[entity] = value
        // DoorbellHub.readings в тестах нет: состояние передаётся напрямую, как это делает DoorbellHub.onState.
        GateHub.onState(context, readings)
        idle()
    }

    @Test fun openConfirmedBySensor() {
        val gate = relay.copy(sensor = "binary_sensor.door")
        states["binary_sensor.door"] = "off"; states["switch.gate_relay"] = "off"
        hub(gate)
        var opened = 0
        GateHub.onOpened = { opened++ }
        assertTrue(GateHub.run("g1", Gate.Action.OPEN))
        idle()
        assertEquals(listOf(GateLogic.Command("switch", "turn_on", "switch.gate_relay")), sent)
        assertEquals(GateHub.Phase.WAITING, GateHub.op("g1")?.phase)
        assertFalse("Пока ждём подтверждения, повтор не уходит", GateHub.run("g1", Gate.Action.OPEN))
        assertEquals(1, sent.size)
        state("binary_sensor.door", "on")
        assertEquals(GateHub.Phase.DONE, GateHub.op("g1")?.phase)
        assertEquals("Открыли — звонок отвечен", 1, opened)
        idle(GateHub.DONE_SHOW_MS)
        assertNull("Итог показан и убран", GateHub.op("g1"))
    }

    @Test fun noConfirmationIsHonest() {
        // Кнопка без датчика: отметка нажатия сменилась — «сработало»; не сменилась — «подтверждения нет».
        val gate = relay.copy(open = "button.r", confirmSeconds = 5)
        states["button.r"] = "2026-11-22T10:00:00"
        hub(gate)
        GateHub.run("g1", Gate.Action.OPEN); idle()
        idle(5_000)
        assertEquals(GateHub.Phase.UNCONFIRMED, GateHub.op("g1")?.phase)
        idle(60_000)
        assertEquals("«Подтверждения нет» держится, пока не коснутся", GateHub.Phase.UNCONFIRMED, GateHub.op("g1")?.phase)
        GateHub.dismiss("g1")
        assertNull(GateHub.op("g1"))
        GateHub.run("g1", Gate.Action.OPEN); idle()
        state("button.r", "2026-11-22T10:05:00")
        idle(5_000)
        assertEquals(GateHub.Phase.REACTED, GateHub.op("g1")?.phase)
    }

    @Test fun failuresAndStop() {
        states["cover.gate"] = "opening"; attrs["cover.gate"] = JSONObject().put("supported_features", 15)
        hub(cover)
        answer = ServiceCommandResult.REJECTED
        GateHub.run("g2", Gate.Action.OPEN); idle()
        assertEquals(GateHub.Phase.FAILED, GateHub.op("g2")?.phase)
        assertEquals("Home Assistant отказал", GateHub.op("g2")?.detail)
        idle(GateHub.FAILED_SHOW_MS)
        answer = ServiceCommandResult.ACKNOWLEDGED
        assertTrue(GateHub.run("g2", Gate.Action.STOP)); idle()
        assertEquals("«Стоп» — по приёму команды", GateHub.Phase.DONE, GateHub.op("g2")?.phase)
        // Нет связи — команда не уходит.
        idle(GateHub.DONE_SHOW_MS)
        connected = false
        GateHub.onState(context, readings)
        // DoorbellHub.readings в тестах пуст — положение считается по тому, что видел GateHub последним.
        assertEquals(2, sent.size)
    }

    @Test fun openedElsewhereIsNoticedButNotOnFirstLook() {
        states["lock.wicket"] = "unlocked"
        hub(lock)
        var opened = 0
        GateHub.onOpened = { opened++ }
        state("lock.wicket", "unlocked")
        assertEquals("Первое состояние только запоминается", 0, opened)
        state("lock.wicket", "locked")
        state("lock.wicket", "unlocked")
        assertEquals(1, opened)
        state("lock.wicket", "unavailable")
        state("lock.wicket", "unlocked")
        assertEquals("Вернулся из недоступности — не «открыли»", 1, opened)
        // Кнопка без положения: новая отметка нажатия — факт срабатывания (как поле «Замок калитки» до 0.9.49).
        GateHub.reset()
        states["button.gate"] = "2026-11-06T01:00:00+00:00"
        hub(relay.copy(open = "button.gate"))
        GateHub.onOpened = { opened++ }
        state("button.gate", "2026-11-06T02:00:00+00:00")
        assertEquals(2, opened)
    }
}
