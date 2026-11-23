package com.example.smarthomedashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * «Оставили открытым» и «Закрыть само» (docs/gate-tz.md, решение 8). Только главный поток; данные — от [GateHub].
 *
 * - Напоминание: проход открыт (по датчику, замку или приводу) дольше [Gate.remindMinutes] — голосом на каждой панели,
 *   красным на плитке, уведомлением на телефон — с той панели, где выбран телефон для уведомлений о звонке.
 * - «Закрыть само» — одна попытка, только на той же панели (чтобы две панели не закрывали одно и то же):
 *   - умный замок — «Запереть», когда открыт дольше N мин и дверь закрыта (если есть датчик двери — закрыта ≥ 10 с);
 *   - привод и свой сценарий — «Закрыть» только с датчиком положения, который сейчас на связи, и только если камера
 *     прохода последние 30 с на связи и никого не видит (человека, машину, животное). «Не знаю» — не «пусто»: камера
 *     или Home Assistant недоступны, у камеры нет датчиков людей и машин в Home Assistant — не закрываем (проверка
 *     аудита 23.11.2026: раньше недоступная камера считалась пустым проходом). Без камеры прохода — не закрываем.
 *     Не закрылось или после своей команды ворота снова открылись (сработал фотоэлемент) — «не удалось закрыть» и
 *     больше не пробуем, пока проход не закроют на деле.
 */
internal object GateWatch {
    private const val TAG = "Gates"
    const val TICK_MS = 10_000L
    /** Значения датчика «есть кто-то / нет никого», которым можно верить. */
    private val BINARY = setOf("on", "off")
    const val CLEAR_MS = 30_000L
    const val DOOR_CLOSED_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    /** С какого момента открыт (по положению) и с какого замок не заперт. */
    private val openSince = HashMap<String, Long>()
    private val unlockedSince = HashMap<String, Long>()
    private val doorClosedSince = HashMap<String, Long>()
    private val reminded = HashSet<String>()
    private val autoTried = HashSet<String>()
    /** Открыт дольше порога — красный на плитке. */
    private val leftOpen = HashSet<String>()
    /**
     * Когда камера плитки последний раз видела человека, машину или животное: пока видит — каждая проверка, когда
     * перестала — момент, когда это заметили (30 с «пусто» считаются от него, а не от последней проверки).
     */
    private val lastSeen = HashMap<String, Long>()
    /** Камеры, которые видят кого-то прямо сейчас. */
    private val busyNow = HashSet<String>()
    /** С какого момента все датчики людей и машин камеры в Home Assistant на связи без перерыва; нет — не на связи. */
    private val trustedSince = HashMap<String, Long>()
    /** После своей команды «Закрыть» ворота начали закрываться — если снова откроются, закрытие не удалось. */
    private val autoClosing = HashSet<String>()
    /** «Не удалось закрыть» уже сказано за это открытие: привод сам пробует снова — второй раз не повторяем. */
    private val autoFailed = HashSet<String>()
    /** Почему «Закрыть само» ждёт — в журнал один раз за открытие. */
    private val waitNoted = HashMap<String, String>()
    /** Последнее положение прохода: закрывать можно, только если оно — от датчика положения. */
    private val lastStatus = HashMap<String, GateStatus>()
    private var ticking = false
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }
    /** Плитки домофона и камеры (подменяется в тестах). */
    internal var tiles: () -> List<com.example.smarthomedashboard.data.TileEntity> = { DoorbellHub.doorbellTiles() }
    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            check()
        }
    }

    fun isLeftOpen(gateId: String) = gateId in leftOpen

    /** Новое положение прохода (из [GateHub.evaluate]). true — изменилось «оставили открытым» (перерисовать). */
    fun onStatus(gate: Gate, status: GateStatus, readings: BatteryReadings?): Boolean {
        val now = clock()
        seen(readings, now)
        lastStatus[gate.id] = status
        val known = status.open && status.source != GateStatus.Source.NONE
        var changed = false
        if (known) openSince.putIfAbsent(gate.id, now) else {
            openSince.remove(gate.id)
            reminded.remove(gate.id)
            waitNoted.remove(gate.id)
            if (leftOpen.remove(gate.id)) changed = true
        }
        if (gate.kind == Gate.Kind.LOCK) {
            val raw = (readings?.of(gate.open) as? BatteryReading.Value)?.raw
            if (raw == "unlocked" || raw == "open") unlockedSince.putIfAbsent(gate.id, now) else unlockedSince.remove(gate.id)
            val doorClosed = gate.sensor.isBlank() || (readings?.of(gate.sensor) as? BatteryReading.Value)?.raw == "off"
            if (doorClosed) doorClosedSince.putIfAbsent(gate.id, now) else doorClosedSince.remove(gate.id)
            if (raw == "locked") autoTried.remove(gate.id)
        } else {
            // Новая попытка — только после того, как проход закрыли на деле. Раньше признак снимался при любом
            // «не открыто» — в том числе на «закрываются» от своей же команды и на сбое связи: если привод упирался в
            // препятствие и откатывал ворота, приложение закрывало их снова каждые N минут.
            if (status.position == GateStatus.Position.CLOSED && status.source != GateStatus.Source.NONE) {
                autoTried.remove(gate.id); autoClosing.remove(gate.id); autoFailed.remove(gate.id)
            } else if (gate.id in autoTried) {
                if (status.position == GateStatus.Position.CLOSING) autoClosing += gate.id
                else if (status.open && autoClosing.remove(gate.id) && autoFailed.add(gate.id)) {
                    Log.i(TAG, "${gate.name}: после «Закрыть само» снова открыты")
                    autoCloseFailed(gate)
                }
            }
        }
        schedule()
        return changed
    }

    /** Проход удалён из списка. */
    fun forget(keep: Set<String>) {
        listOf(openSince, unlockedSince, doorClosedSince, lastStatus, waitNoted).forEach { it.keys.retainAll(keep) }
        reminded.retainAll(keep); autoTried.retainAll(keep); leftOpen.retainAll(keep); autoClosing.retainAll(keep); autoFailed.retainAll(keep)
    }

    private fun schedule() {
        if (ticking || (openSince.isEmpty() && unlockedSince.isEmpty())) return
        ticking = true
        main.postDelayed(tick, TICK_MS)
    }

    /**
     * Камеры, которые сейчас видят человека, машину или животное (события камеры напрямую или датчики в Home Assistant),
     * и камеры, чьи датчики в Home Assistant на связи. Видеть «кого-то» может и событие напрямую; подтвердить «пусто» —
     * только датчики Home Assistant, которые на связи (событие напрямую приходит, только когда кто-то есть: его
     * отсутствие ничего не доказывает).
     */
    private fun seen(readings: BatteryReadings?, now: Long) {
        for (tile in tiles()) {
            val config = DoorbellConfig.from(tile.config)
            val sensors = sensorsOf(config).map { readings?.of(it) }
            val direct = DirectEvents.activeDetections(tile.id).any { it != DoorbellConfig.Detection.MOTION }
            val ha = sensors.any { (it as? BatteryReading.Value)?.raw == "on" }
            // Подтвердить «пусто» может только датчик «есть / нет» (on/off). Другое значение («detected», число,
            // событие) — неизвестно, есть ли кто-то (независимое ревью 24.11.2026): раньше оно считалось «пусто».
            if (sensors.isNotEmpty() && sensors.all { (it as? BatteryReading.Value)?.raw in BINARY }) trustedSince.putIfAbsent(tile.id, now)
            else trustedSince.remove(tile.id)
            if (direct || ha) { lastSeen[tile.id] = now; busyNow += tile.id }
            else if (busyNow.remove(tile.id)) lastSeen[tile.id] = now
        }
    }

    private fun sensorsOf(config: DoorbellConfig) = config.detections
        .filter { (entity, kind) -> kind != DoorbellConfig.Detection.MOTION && entity.isNotBlank() }.map { it.first }

    /**
     * Проход у ворот пуст, по камерам: хоть одна камера с датчиками людей и машин в Home Assistant; все такие камеры
     * последние [CLEAR_MS] на связи; никто из камер прохода (в том числе события напрямую) за это время никого не видел.
     * null — пусто; иначе — почему ждём (в журнал).
     */
    private fun cameraWait(cameras: List<com.example.smarthomedashboard.data.TileEntity>, now: Long): String? {
        val watching = cameras.filter { sensorsOf(DoorbellConfig.from(it.config)).isNotEmpty() }
        if (watching.isEmpty()) return "у камеры прохода нет датчиков людей и машин в Home Assistant"
        if (cameras.any { it.id in busyNow || lastSeen[it.id]?.let { seen -> now - seen < CLEAR_MS } == true }) return "в проходе кто-то есть"
        if (watching.any { tile -> trustedSince[tile.id]?.let { now - it >= CLEAR_MS } != true }) return "камера прохода или Home Assistant не на связи"
        return null
    }

    /** Плитки домофона и камеры, где проход отмечен. */
    fun tilesOf(gateId: String) = tiles().filter { DoorbellConfig.from(it.config).gateIds(it.id).contains(gateId) }

    /** Служба уведомлений телефона на этой панели (выбрана у плитки домофона или камеры) — она и исполняет «Закрыть само». */
    fun phoneService(context: Context, gateId: String): String? {
        val own = tilesOf(gateId) + tiles()
        return own.asSequence().map { DoorbellAway.settings(context, it.id).notifyService }.firstOrNull { it.isNotBlank() }
    }

    internal fun check() {
        val context = GateHub.context ?: return
        val now = clock()
        val readings = GateHub.currentReadings
        seen(readings, now)
        var changed = false
        for (gate in GateHub.gates(context)) {
            val since = openSince[gate.id]
            if (since != null && gate.remindMinutes > 0 && gate.id !in reminded && now - since >= gate.remindMinutes * 60_000L) {
                reminded += gate.id
                leftOpen += gate.id
                changed = true
                remind(context, gate, "${gate.name}: открыто дольше ${gate.remindMinutes} мин")
            }
            autoClose(context, gate, now, readings)
        }
        if (changed) GateHub.changed()
        schedule()
    }

    private fun autoClose(context: Context, gate: Gate, now: Long, readings: BatteryReadings?) {
        if (!gate.autoClose || gate.id in autoTried) return
        val action = GateLogic.autoCloseKind(gate) ?: return
        val limit = gate.autoCloseMinutes * 60_000L
        val due = if (gate.kind == Gate.Kind.LOCK) {
            val unlocked = unlockedSince[gate.id] ?: return
            val closed = doorClosedSince[gate.id] ?: return
            now - unlocked >= limit && (gate.sensor.isBlank() || now - closed >= DOOR_CLOSED_MS)
        } else {
            val since = openSince[gate.id] ?: return
            now - since >= limit
        }
        if (!due) return
        if (gate.kind != Gate.Kind.LOCK) {
            val cameras = tilesOf(gate.id)
            if (cameras.isEmpty()) return
            // Положение — от датчика положения, который сейчас на связи: состояние самого привода бывает «оптимистичным».
            if (lastStatus[gate.id]?.let { it.open && it.source == GateStatus.Source.SENSOR } != true) {
                noteWait(gate, "датчик положения не на связи"); return
            }
            // Кто-то в проходе или неизвестно, есть ли, — ждём: закрыть ворота на человека или машину нельзя.
            cameraWait(cameras, now)?.let { noteWait(gate, it); return }
        }
        if (phoneService(context, gate.id) == null) return
        if (GateHub.op(gate.id)?.busy == true) return
        autoTried += gate.id
        autoClosing.remove(gate.id)
        Log.i(TAG, "${gate.name}: закрываю само (открыто дольше ${gate.autoCloseMinutes} мин)")
        val before = GateHub.op(gate.id)
        // Не ушла команда: если GateHub уже записал неудачу, напоминание он отправил сам — второе не нужно.
        if (!GateHub.run(gate, action, GateLog.Origin.AUTO) && GateHub.op(gate.id) === before) autoCloseFailed(gate)
    }

    private fun noteWait(gate: Gate, reason: String) {
        if (waitNoted[gate.id] == reason) return
        waitNoted[gate.id] = reason
        Log.i(TAG, "${gate.name}: «Закрыть само» ждёт — $reason")
    }

    /** «Закрыть само» не получилось — напомнить. */
    fun autoCloseFailed(gate: Gate) {
        val context = GateHub.context ?: return
        leftOpen += gate.id
        GateHub.changed()
        remind(context, gate, "${gate.name}: не удалось закрыть")
    }

    private fun remind(context: Context, gate: Gate, text: String) {
        Log.i(TAG, "Напоминание: $text")
        PanelAlerts.external(Alert(AlertEvent.GATE_LEFT_OPEN, text))
        val service = phoneService(context, gate.id) ?: return
        DoorbellAway.sender(service.substringBefore('.'), service.substringAfter('.'), mapOf(
            "title" to gate.name, "message" to text,
            "data" to org.json.JSONObject().put("tag", "gate_${gate.id}").put("channel", "Калитки и ворота")
                .put("push", org.json.JSONObject().put("sound", "default").put("interruption-level", "time-sensitive"))
        )) { ok -> Log.i(TAG, if (ok) "Напоминание отправлено на телефон" else "Напоминание на телефон не отправлено") }
    }

    fun reset() {
        main.removeCallbacks(tick)
        ticking = false
        openSince.clear(); unlockedSince.clear(); doorClosedSince.clear()
        reminded.clear(); autoTried.clear(); leftOpen.clear(); lastSeen.clear()
        busyNow.clear(); trustedSince.clear(); autoClosing.clear(); autoFailed.clear(); waitNoted.clear(); lastStatus.clear()
        clock = { SystemClock.uptimeMillis() }
        tiles = { DoorbellHub.doorbellTiles() }
    }
}
