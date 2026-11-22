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
 *   - привод и свой сценарий — «Закрыть» только с датчиком положения, только если камера прохода последние 30 с не
 *     видела человека, машину или животное; без камеры прохода — не закрываем. Не закрылось — «не удалось закрыть».
 */
internal object GateWatch {
    private const val TAG = "Gates"
    const val TICK_MS = 10_000L
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
    /** Когда камера плитки последний раз видела человека, машину или животное. */
    private val lastSeen = HashMap<String, Long>()
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
        val known = status.open && status.source != GateStatus.Source.NONE
        var changed = false
        if (known) openSince.putIfAbsent(gate.id, now) else {
            openSince.remove(gate.id)
            reminded.remove(gate.id)
            if (leftOpen.remove(gate.id)) changed = true
        }
        if (gate.kind == Gate.Kind.LOCK) {
            val raw = (readings?.of(gate.open) as? BatteryReading.Value)?.raw
            if (raw == "unlocked" || raw == "open") unlockedSince.putIfAbsent(gate.id, now) else unlockedSince.remove(gate.id)
            val doorClosed = gate.sensor.isBlank() || (readings?.of(gate.sensor) as? BatteryReading.Value)?.raw == "off"
            if (doorClosed) doorClosedSince.putIfAbsent(gate.id, now) else doorClosedSince.remove(gate.id)
            if (raw == "locked") autoTried.remove(gate.id)
        } else if (!known) autoTried.remove(gate.id)
        schedule()
        return changed
    }

    /** Проход удалён из списка. */
    fun forget(keep: Set<String>) {
        listOf(openSince, unlockedSince, doorClosedSince).forEach { it.keys.retainAll(keep) }
        reminded.retainAll(keep); autoTried.retainAll(keep); leftOpen.retainAll(keep)
    }

    private fun schedule() {
        if (ticking || (openSince.isEmpty() && unlockedSince.isEmpty())) return
        ticking = true
        main.postDelayed(tick, TICK_MS)
    }

    /** Камеры, которые сейчас видят человека, машину или животное (события камеры напрямую или датчики в Home Assistant). */
    private fun seen(readings: BatteryReadings?, now: Long) {
        for (tile in tiles()) {
            val config = DoorbellConfig.from(tile.config)
            val direct = DirectEvents.activeDetections(tile.id).any { it != DoorbellConfig.Detection.MOTION }
            val ha = config.detections.any { (entity, kind) ->
                kind != DoorbellConfig.Detection.MOTION && entity.isNotBlank() && (readings?.of(entity) as? BatteryReading.Value)?.raw == "on"
            }
            if (direct || ha) lastSeen[tile.id] = now
        }
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
            // Кто-то в проходе — ждём: закрыть ворота на человека или машину нельзя.
            if (cameras.any { tile -> lastSeen[tile.id]?.let { now - it < CLEAR_MS } == true }) return
        }
        if (phoneService(context, gate.id) == null) return
        if (GateHub.op(gate.id)?.busy == true) return
        autoTried += gate.id
        Log.i(TAG, "${gate.name}: закрываю само (открыто дольше ${gate.autoCloseMinutes} мин)")
        if (!GateHub.run(gate, action, GateLog.Origin.AUTO)) autoCloseFailed(gate)
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
        clock = { SystemClock.uptimeMillis() }
        tiles = { DoorbellHub.doorbellTiles() }
    }
}
