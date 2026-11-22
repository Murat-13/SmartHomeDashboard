package com.example.smarthomedashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Исполнение проходов (калитки и ворота, docs/gate-tz.md) — одно на панель, для всех кнопок: раскрытой плитки, окна
 * звонка, отдельной плитки. Только главный поток.
 *
 * - Одна команда на действие: пока ждём подтверждения, повтор не уходит (импульсное реле не открывают дважды).
 * - Итог честный: «открыто (датчик)» / «открыто» (по замку или приводу) / «сработало» (реле отозвалось, положение
 *   неизвестно) / «команда принята, подтверждения нет» / «не удалось».
 * - Состояние — из общего потока Home Assistant ([DoorbellHub.readings], там все сущности); новая подписка не нужна.
 * - Проход открыли где угодно (здесь, другая панель, пульт) — [onOpened]: окно звонка отмечает звонок отвеченным.
 */
// Хранится только applicationContext (attach), не экран: утечки нет.
@android.annotation.SuppressLint("StaticFieldLeak")
internal object GateHub {
    private const val TAG = "Gates"

    /** Способ связи (решение владельца 6). Этап 1 — Home Assistant через панель; подменяется в тестах. */
    fun interface Driver {
        /** false — команда не ушла (нет связи). Итог — в [onResult] на главном потоке. */
        fun send(command: GateLogic.Command, onResult: (ServiceCommandResult) -> Unit): Boolean
    }

    private val haDriver = Driver { command, onResult ->
        DoorbellHub.actions?.call(command.domain, command.service, command.entity, emptyMap(), onResult) == true
    }
    @Volatile var driver: Driver = haDriver
        internal set

    fun resetDriver() { driver = haDriver }

    enum class Phase { SENDING, WAITING, DONE, REACTED, UNCONFIRMED, FAILED }

    /** Идущее или только что закончившееся действие прохода. */
    data class Op(val action: Gate.Action, val phase: Phase, val startedAt: Long, val before: GateStatus,
                  val beforeRaw: String?, val detail: String = "") {
        val busy get() = phase == Phase.SENDING || phase == Phase.WAITING
    }

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var cached: List<Gate>? = null
    private val ops = HashMap<String, Op>()
    /** Проход, которым исполняется действие: может быть ещё не сохранён («Проверить» в редакторе). */
    private val opGates = HashMap<String, Gate>()
    private val deadlines = HashMap<String, Runnable>()
    private val clears = HashMap<String, Runnable>()
    private val lastPosition = HashMap<String, GateStatus.Position>()
    private val lastRaw = HashMap<String, String?>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    /** Последнее состояние Home Assistant, переданное в [onState] (иначе — общее у [DoorbellHub]). */
    private var latest: BatteryReadings? = null
    private val readings get() = latest ?: DoorbellHub.readings

    /** Проход открыли (по датчику, замку, приводу или новой отметке кнопки) — здесь или где угодно. */
    @Volatile var onOpened: ((Gate) -> Unit)? = null

    fun attach(context: Context) { app = context.applicationContext }

    fun gates(context: Context? = app): List<Gate> {
        cached?.let { return it }
        val c = context ?: return emptyList()
        if (app == null) app = c.applicationContext
        return GateStore.load(c).also { cached = it }
    }

    fun gate(id: String, context: Context? = app): Gate? = gates(context).firstOrNull { it.id == id }

    /** Проходы, которые показывает плитка: только существующие и настроенные. */
    fun forTile(context: Context?, tileId: String, config: DoorbellConfig): List<Gate> {
        val all = gates(context)
        return config.gateIds(tileId).mapNotNull { id -> all.firstOrNull { it.id == id } }.filter { it.ready }
    }

    /** Список изменился ([GateStore.save]). */
    fun invalidate() {
        cached = null
        notifyListeners()
    }

    fun status(gate: Gate): GateStatus = GateLogic.status(gate, readings)

    /** Кнопки прохода сейчас (например, «Стоп» — если привод его умеет). */
    fun actions(gate: Gate): List<Gate.Action> = GateLogic.actions(gate, readings,
        moving = GateLogic.status(gate, readings).moving || ops[gate.id]?.let { it.busy && it.action != Gate.Action.STOP } == true)

    fun op(gateId: String): Op? = ops[gateId]

    fun listen(listener: () -> Unit) { listeners.add(listener) }
    fun unlisten(listener: () -> Unit) { listeners.remove(listener) }

    private fun notifyListeners() {
        if (Looper.myLooper() == Looper.getMainLooper()) listeners.forEach { it() } else main.post { listeners.forEach { it() } }
    }

    private fun raw(entity: String): String? = (readings?.of(entity) as? BatteryReading.Value)?.raw

    /**
     * Действие кнопки. false — не отправлено (уже идёт, нет такого действия, нет связи); причина видна на кнопке ([op]).
     * PIN проверяет вызывающий ([GatePin]) — здесь только исполнение.
     */
    fun run(gateId: String, action: Gate.Action): Boolean = gate(gateId)?.let { run(it, action) } ?: false

    /** То же для прохода, которого может ещё не быть в списке («Проверить» в редакторе прохода). */
    fun run(gate: Gate, action: Gate.Action): Boolean {
        val gateId = gate.id
        // Пока ждём подтверждения, повтор не уходит; «Стоп» — всегда: остановить ход ворот важнее ожидания.
        if (ops[gateId]?.busy == true && action != Gate.Action.STOP) return false
        deadlines.remove(gateId)?.let(main::removeCallbacks)
        val readings = readings
        val command = GateLogic.command(gate, action, readings) ?: return false
        val before = GateLogic.status(gate, readings)
        val started = SystemClock.uptimeMillis()
        if (before.position == GateStatus.Position.OFFLINE) {
            finish(gate, Op(action, Phase.FAILED, started, before, null, "нет связи с Home Assistant"))
            return false
        }
        val op = Op(action, Phase.SENDING, started, before, raw(gate.open))
        opGates[gate.id] = gate
        set(gate.id, op)
        Log.i(TAG, "${gate.name}: ${action.name.lowercase()} → ${command.domain}.${command.service}")
        val sent = driver.send(command) { result -> main.post { onResult(gate, op, result) } }
        if (!sent) {
            finish(gate, op.copy(phase = Phase.FAILED, detail = "нет связи с Home Assistant"))
            return false
        }
        val deadline = Runnable { onDeadline(gate.id, op) }
        deadlines[gate.id] = deadline
        main.postDelayed(deadline, gate.confirmSeconds * 1000L)
        return true
    }

    /** «Подтверждения нет» снимается осознанно — касанием; следующее удержание уже отправит команду. */
    fun dismiss(gateId: String) {
        if (ops[gateId]?.busy == true) return
        if (ops.remove(gateId) != null) notifyListeners()
    }

    private fun same(gateId: String, op: Op) = ops[gateId]?.startedAt == op.startedAt

    private fun onResult(gate: Gate, op: Op, result: ServiceCommandResult) {
        if (!same(gate.id, op)) return
        val current = ops.getValue(gate.id)
        if (!current.busy) return
        if (result != ServiceCommandResult.ACKNOWLEDGED) {
            finish(gate, current.copy(phase = Phase.FAILED, detail = when (result) {
                ServiceCommandResult.REJECTED -> "Home Assistant отказал"
                ServiceCommandResult.ACK_TIMEOUT -> "Home Assistant не ответил"
                else -> "нет связи с Home Assistant"
            }))
            return
        }
        // «Стоп» подтверждается приёмом команды: движение прекращается мгновенно, ждать нечего.
        if (current.action == Gate.Action.STOP) { finish(gate, current.copy(phase = Phase.DONE)); return }
        if (current.phase == Phase.SENDING) set(gate.id, current.copy(phase = Phase.WAITING))
        check(gate)
    }

    private fun onDeadline(gateId: String, op: Op) {
        if (!same(gateId, op)) return
        val gate = opGates[gateId] ?: gate(gateId) ?: return
        val current = ops.getValue(gateId)
        if (!current.busy) return
        val reacted = GateLogic.reacted(current.beforeRaw, raw(gate.open))
        finish(gate, current.copy(phase = when {
            current.phase == Phase.SENDING -> Phase.FAILED
            reacted -> Phase.REACTED
            else -> Phase.UNCONFIRMED
        }, detail = if (current.phase == Phase.SENDING) "Home Assistant не ответил" else ""))
    }

    /** Ждём факта: новое положение — готово. */
    private fun check(gate: Gate, readings: BatteryReadings? = this.readings) {
        val op = ops[gate.id]?.takeIf { it.phase == Phase.WAITING } ?: return
        val now = GateLogic.status(gate, readings)
        if (GateLogic.confirmed(op.action, op.before, now)) finish(gate, op.copy(phase = Phase.DONE))
    }

    private fun set(gateId: String, op: Op) {
        ops[gateId] = op
        notifyListeners()
    }

    private fun finish(gate: Gate, op: Op) {
        deadlines.remove(gate.id)?.let(main::removeCallbacks)
        clears.remove(gate.id)?.let(main::removeCallbacks)
        Log.i(TAG, "${gate.name}: ${op.action.name.lowercase()} — ${op.phase.name.lowercase()}${op.detail.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}")
        set(gate.id, op)
        // Итог виден на кнопке какое-то время; «подтверждения нет» — пока не коснутся (повтор — только осознанный).
        val keep = when (op.phase) {
            Phase.DONE, Phase.REACTED -> DONE_SHOW_MS
            Phase.FAILED -> FAILED_SHOW_MS
            else -> return
        }
        val clear = Runnable { if (same(gate.id, op)) { ops.remove(gate.id); notifyListeners() } }
        clears[gate.id] = clear
        main.postDelayed(clear, keep)
    }

    /** Новое состояние Home Assistant (из [DoorbellHub.onState]). */
    fun onState(context: Context?, readings: BatteryReadings) {
        latest = readings
        val gates = gates(context)
        // Проверка ещё не сохранённого прохода: ждём его подтверждения так же.
        opGates.values.filter { draft -> gates.none { it.id == draft.id } }.forEach { check(it, readings) }
        var changed = false
        for (gate in gates) {
            check(gate, readings)
            val status = GateLogic.status(gate, readings)
            val raw = (readings.of(gate.open) as? BatteryReading.Value)?.raw
            val reading = readings.of(gate.open)
            val before = lastPosition.put(gate.id, status.position)
            val rawBefore = lastRaw.put(gate.id, raw)
            if (before != status.position) changed = true
            // Недоступно — прежнее забываем: возврат сразу в «открыто» не значит, что открыли.
            if (reading is BatteryReading.Unavailable || reading == BatteryReading.Missing) { lastRaw.remove(gate.id); continue }
            val opened = when {
                before == null -> false
                status.open && before != GateStatus.Position.OPEN && before != GateStatus.Position.OPENING &&
                    before != GateStatus.Position.OFFLINE && before != GateStatus.Position.UNKNOWN -> true
                // Кнопка и сценарий без положения: новая отметка нажатия — факт срабатывания (как в 0.9.48).
                status.source == GateStatus.Source.NONE && gate.kind != Gate.Kind.COVER && rawBefore != null && raw != rawBefore &&
                    gate.open.substringBefore('.') in setOf("button", "input_button") -> true
                else -> false
            }
            if (opened) onOpened?.invoke(gate)
        }
        lastPosition.keys.retainAll(gates.mapTo(HashSet()) { it.id })
        if (changed) notifyListeners()
    }

    /** Для тестов: всё забыть. */
    fun reset() {
        deadlines.values.forEach(main::removeCallbacks); deadlines.clear()
        clears.values.forEach(main::removeCallbacks); clears.clear()
        ops.clear(); opGates.clear(); lastPosition.clear(); lastRaw.clear(); listeners.clear()
        latest = null
        cached = null
        onOpened = null
        driver = haDriver
    }

    const val DONE_SHOW_MS = 5_000L
    const val FAILED_SHOW_MS = 8_000L
}
