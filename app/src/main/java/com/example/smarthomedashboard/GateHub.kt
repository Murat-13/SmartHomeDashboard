package com.example.smarthomedashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    /** Связь напрямую (этап 4): команда и опрос устройства; блокирующие, зовутся с фонового потока. Подменяется в тестах. */
    interface DirectLink {
        fun send(gate: Gate, action: Gate.Action): GateDirect.Result
        fun poll(gate: Gate): DirectState?
    }

    private val shellyLink = object : DirectLink {
        /** Хранилище ответило или пароль известен с прошлого раза; нет — сбой хранилища, и пароль неизвестен. */
        private fun load(gate: Gate): CameraSecrets.Loaded {
            passwordOverride[gate.id]?.let { return CameraSecrets.Loaded(it, sure = true) }
            val context = app ?: return CameraSecrets.Loaded(null, sure = true)
            // Сбой хранилища ключей не должен оставить калитку закрытой: ещё попытка, затем — пароль, прочитанный раньше.
            repeat(2) {
                val loaded = try { CameraPasswords.load(context, GateDirect.secretKey(gate.id)) } catch (e: Exception) {
                    Log.w(TAG, "${gate.name}: пароль устройства не прочитан (${e.javaClass.simpleName})"); null
                }
                if (loaded?.sure == true) {
                    if (loaded.value == null) knownPasswords.remove(gate.id) else knownPasswords[gate.id] = loaded.value
                    return loaded
                }
            }
            return knownPasswords[gate.id]?.let { CameraSecrets.Loaded(it, sure = true) } ?: CameraSecrets.Loaded(null, sure = false)
        }
        /** Пароль не прочитался — пробуем без него: устройство без пароля всё равно ответит. */
        override fun send(gate: Gate, action: Gate.Action): GateDirect.Result {
            val loaded = load(gate)
            val result = GateDirect.send(gate, action, loaded.value)
            return if (!loaded.sure && result.detail == GateDirect.WRONG_PASSWORD) result.copy(detail = "пароль не прочитан, повторите") else result
        }
        /** Проверке связи «своего HTTP» пароль не нужен. */
        override fun poll(gate: Gate) =
            GateDirect.poll(gate, if (gate.direct?.protocol == Gate.Direct.Protocol.HTTP) null else load(gate).value)
    }
    /** Пароль, который вводят в редакторе прохода прямо сейчас (для «Проверить» до «Сохранить»). */
    internal val passwordOverride = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Пароли устройств, прочитанные раньше (только в памяти). */
    private val knownPasswords = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var direct: DirectLink = shellyLink
        internal set

    /** Свой поток для сети: команды и опрос не ждут друг друга с главным потоком. */
    private var io: ExecutorService? = null
    private fun io(): ExecutorService = io ?: Executors.newSingleThreadExecutor { r -> Thread(r, "gates-direct").apply { isDaemon = true } }.also { io = it }
    /** Последний ответ устройства по проходу; два промаха подряд — «не отвечает». */
    private val directStates = HashMap<String, DirectState>()
    private val directMisses = HashMap<String, Int>()
    private val polling = HashSet<String>()
    private val lastPoll = HashMap<String, Long>()
    private var pollerOn = false
    private val poller = object : Runnable {
        override fun run() {
            pollTick()
            if (pollerOn) main.postDelayed(this, POLL_TICK_MS)
        }
    }

    enum class Phase { SENDING, WAITING, DONE, REACTED, UNCONFIRMED, FAILED }

    /** Идущее или только что закончившееся действие прохода. */
    data class Op(val action: Gate.Action, val phase: Phase, val startedAt: Long, val before: GateStatus,
                  val beforeRaw: String?, val detail: String = "", val origin: GateLog.Origin = GateLog.Origin.PANEL) {
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

    fun attach(context: Context) {
        app = context.applicationContext
        HaEvents.onNotificationAction = { GatePhone.onAction(it) }
        HaEvents.wanted = { gates().any { it.phone } }
        GateStore.hidePasswords(context)
    }

    val context: Context? get() = app
    val currentReadings: BatteryReadings? get() = readings
    /** Перерисовать кнопки и плитки (например, «оставили открытым»). */
    fun changed() = notifyListeners()

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

    fun status(gate: Gate): GateStatus = status(gate, readings)

    private fun status(gate: Gate, readings: BatteryReadings?) = GateLogic.status(gate, readings, directStates[gate.id])

    /** Кнопки прохода сейчас (например, «Стоп» — если привод его умеет). */
    fun actions(gate: Gate): List<Gate.Action> = GateLogic.actions(gate, readings,
        moving = status(gate).moving || ops[gate.id]?.let { it.busy && it.action != Gate.Action.STOP } == true)

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
    fun run(gateId: String, action: Gate.Action, origin: GateLog.Origin = GateLog.Origin.PANEL): Boolean =
        gate(gateId)?.let { run(it, action, origin) } ?: false

    /** То же для прохода, которого может ещё не быть в списке («Проверить» в редакторе прохода). */
    fun run(gate: Gate, action: Gate.Action, origin: GateLog.Origin = GateLog.Origin.PANEL): Boolean {
        val gateId = gate.id
        // Пока ждём подтверждения, повтор не уходит; «Стоп» — всегда: остановить ход ворот важнее ожидания.
        if (ops[gateId]?.busy == true && action != Gate.Action.STOP) return false
        deadlines.remove(gateId)?.let(main::removeCallbacks)
        val readings = readings
        val started = SystemClock.uptimeMillis()
        if (GateLogic.useDirect(gate, readings)) return runDirect(gate, action, started, origin)
        val command = GateLogic.command(gate, action, readings) ?: return false
        val before = status(gate, readings)
        if (before.position == GateStatus.Position.OFFLINE) {
            finish(gate, Op(action, Phase.FAILED, started, before, null, "нет связи с Home Assistant", origin))
            return false
        }
        val op = Op(action, Phase.SENDING, started, before, raw(gate.open), origin = origin)
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

    /** Без Home Assistant: команда устройству напрямую с фонового потока, итог — на главном. */
    private fun runDirect(gate: Gate, action: Gate.Action, started: Long, origin: GateLog.Origin): Boolean {
        if (!GateLogic.directSupports(gate, action)) return false
        val op = Op(action, Phase.SENDING, started, status(gate), null, origin = origin)
        opGates[gate.id] = gate
        set(gate.id, op)
        Log.i(TAG, "${gate.name}: ${action.name.lowercase()} → напрямую (${gate.direct?.protocol?.key})")
        val link = direct
        io().execute {
            val result = link.send(gate, action)
            main.post { onDirectResult(gate, op, result) }
        }
        val deadline = Runnable { onDeadline(gate.id, op) }
        deadlines[gate.id] = deadline
        main.postDelayed(deadline, gate.confirmSeconds * 1000L)
        startPoller()
        return true
    }

    private fun onDirectResult(gate: Gate, op: Op, result: GateDirect.Result) {
        if (!same(gate.id, op)) return
        val current = ops.getValue(gate.id)
        if (!current.busy) return
        if (!result.ok) { finish(gate, current.copy(phase = Phase.FAILED, detail = result.detail)); return }
        if (current.action == Gate.Action.STOP) { finish(gate, current.copy(phase = Phase.DONE)); return }
        // Положение узнать неоткуда (реле без датчика, свой запрос) — честно: «сработало», проверьте глазами.
        if (!GateLogic.directConfirmable(gate, readings)) {
            finish(gate, current.copy(phase = Phase.REACTED, detail = if (gate.direct?.protocol == Gate.Direct.Protocol.HTTP)
                "устройство приняло команду, положение неизвестно — проверьте" else ""))
            return
        }
        set(gate.id, current.copy(phase = Phase.WAITING))
        pollNow(gate)
    }

    // ---- Опрос устройств напрямую ----

    private fun directGates(): List<Gate> {
        val r = readings
        // Не сохранённый проход («Проверить» в редакторе) — только пока его действие идёт: удалённый или брошенный
        // черновик не опрашивается до перезапуска приложения (найдено в Deep tests 22.11).
        val drafts = opGates.values.filter { ops[it.id]?.busy == true }
        return (gates() + drafts).distinctBy { it.id }.filter { it.hasDirect && GateLogic.useDirect(it, r) }
    }

    /** Опрос нужен, пока есть проход на связи напрямую; иначе останавливается сам. */
    fun startPoller() {
        if (pollerOn || app == null) return
        pollerOn = true
        main.post(poller)
    }

    private fun pollTick() {
        val list = directGates()
        if (list.isEmpty()) { pollerOn = false; return }
        val now = SystemClock.uptimeMillis()
        for (gate in list) {
            // Идёт действие — раз в секунду; кнопки на экране — раз в 5 с; никто не смотрит — раз в 15 с.
            val every = when {
                ops[gate.id]?.busy == true -> POLL_BUSY_MS
                listeners.isNotEmpty() -> POLL_SHOWN_MS
                else -> POLL_IDLE_MS
            }
            if (now - (lastPoll[gate.id] ?: 0L) >= every) pollNow(gate)
        }
    }

    private fun pollNow(gate: Gate) {
        if (!polling.add(gate.id)) return
        lastPoll[gate.id] = SystemClock.uptimeMillis()
        val link = direct
        io().execute {
            val state = try { link.poll(gate) } catch (e: Exception) {
                Log.w(TAG, "${gate.name}: опрос не удался (${e.javaClass.simpleName})"); null
            }
            main.post { onPolled(gate, state) }
        }
    }

    private fun onPolled(gate: Gate, state: DirectState?) {
        polling.remove(gate.id)
        if (state != null) {
            directMisses.remove(gate.id)
            directStates[gate.id] = state
        } else {
            val misses = (directMisses[gate.id] ?: 0) + 1
            directMisses[gate.id] = misses
            if (misses == 2) Log.i(TAG, "${gate.name}: устройство не отвечает")
            if (misses >= 2) directStates[gate.id] = DirectState(reachable = false)
        }
        val r = readings
        val live = gates().firstOrNull { it.id == gate.id } ?: opGates[gate.id] ?: return
        check(live, r)
        if (evaluate(live, r)) notifyListeners()
    }

    /** «Подтверждения нет» снимается осознанно — касанием; следующее удержание уже отправит команду. */
    fun dismiss(gateId: String) {
        if (ops[gateId]?.busy == true) return
        opGates.remove(gateId)
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
        val reacted = !GateLogic.useDirect(gate, readings) && GateLogic.reacted(current.beforeRaw, raw(gate.open))
        finish(gate, current.copy(phase = when {
            current.phase == Phase.SENDING -> Phase.FAILED
            reacted -> Phase.REACTED
            else -> Phase.UNCONFIRMED
        }, detail = if (current.phase != Phase.SENDING) "" else if (GateLogic.useDirect(gate, readings)) "устройство не ответило" else "Home Assistant не ответил"))
    }

    /** Ждём факта: новое положение — готово. */
    private fun check(gate: Gate, readings: BatteryReadings? = this.readings) {
        val op = ops[gate.id]?.takeIf { it.phase == Phase.WAITING } ?: return
        val now = status(gate, readings)
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
        journal(gate, op)
        if (op.origin == GateLog.Origin.AUTO && op.phase != Phase.DONE) GateWatch.autoCloseFailed(gate)
        // Итог виден на кнопке какое-то время; «подтверждения нет» — пока не коснутся (повтор — только осознанный).
        val keep = when (op.phase) {
            Phase.DONE, Phase.REACTED -> DONE_SHOW_MS
            Phase.FAILED -> FAILED_SHOW_MS
            else -> return
        }
        val clear = Runnable { if (same(gate.id, op)) { ops.remove(gate.id); opGates.remove(gate.id); notifyListeners() } }
        clears[gate.id] = clear
        main.postDelayed(clear, keep)
    }

    /** Запись в журнал открытий: итог действия кнопки (или «Закрыть само», телефона). Снимок — с камеры прохода. */
    private fun journal(gate: Gate, op: Op) {
        val context = app ?: return
        val result = when (op.phase) {
            Phase.DONE -> if (op.action == Gate.Action.STOP) "остановлено" else GateLogic.statusText(status(gate)).ifBlank { "готово" }
            Phase.REACTED -> "сработало, положение неизвестно"
            Phase.UNCONFIRMED -> "команда принята, подтверждения нет"
            Phase.FAILED -> "не удалось" + op.detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            Phase.SENDING, Phase.WAITING -> return
        }
        GateLog.add(context, GateLog.Entry(System.currentTimeMillis(), gate.id, gate.name, op.action, op.origin, result), video(gate))
        if (op.origin == GateLog.Origin.PHONE) GatePhone.report(gate, result)
    }

    private fun video(gate: Gate): () -> DoorbellVideoView? = {
        GateWatch.tilesOf(gate.id).firstNotNullOfOrNull { DoorbellHub.actions?.tileVideo(it.id) }
    }

    /**
     * Панель закрыта (её ViewModel очищен, соединение с Home Assistant закрыто): новое состояние больше не придёт.
     * Последний снимок перестаёт считаться текущим — «Закрыть само» по нему не решает (независимое ревью 24.11.2026:
     * процесс жив ради записи, а [GateWatch] закрыл бы ворота по застывшему «никого нет»).
     */
    fun panelGone() {
        latest = null
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
            if (evaluate(gate, readings)) changed = true
        }
        lastPosition.keys.retainAll(gates.mapTo(HashSet()) { it.id })
        GateWatch.forget(gates.mapTo(HashSet()) { it.id })
        if (gates.any { it.hasDirect }) startPoller()
        if (changed) notifyListeners()
    }

    /** Положение прохода изменилось? Заодно — «проход открыли» ([onOpened]), откуда бы ни открыли. */
    private fun evaluate(gate: Gate, readings: BatteryReadings?): Boolean {
        val status = status(gate, readings)
        val direct = GateLogic.useDirect(gate, readings)
        val reading = if (direct) null else readings?.of(gate.open)
        val raw = (reading as? BatteryReading.Value)?.raw
        val before = lastPosition.put(gate.id, status.position)
        val rawBefore = lastRaw.put(gate.id, raw)
        var changed = before != status.position
        if (GateWatch.onStatus(gate, status, readings)) changed = true
        // Недоступно — прежнее забываем: возврат сразу в «открыто» не значит, что открыли.
        if (!direct && (reading is BatteryReading.Unavailable || reading == BatteryReading.Missing)) { lastRaw.remove(gate.id); return changed }
        val opened = when {
            before == null -> false
            // Открыли — только из закрытого: остановленные на полпути при закрытии ворота «открытыми» не считаются
            // (найдено на планшете 22.11: «Стоп» при закрытии попадал в журнал как «открыли без команды»).
            status.open && (before == GateStatus.Position.CLOSED || before == GateStatus.Position.JAMMED) -> true
            // Кнопка и сценарий без положения: новая отметка нажатия — факт срабатывания (как в 0.9.48).
            !direct && status.source == GateStatus.Source.NONE && gate.kind != Gate.Kind.COVER && rawBefore != null && raw != rawBefore &&
                gate.open.substringBefore('.') in setOf("button", "input_button") -> true
            else -> false
            }
        if (opened) {
            // Открыли не нашей кнопкой (пульт, ключ, автоматика, другая панель) — тоже в журнал.
            val own = ops[gate.id]?.let { it.action != Gate.Action.CLOSE && it.action != Gate.Action.STOP &&
                SystemClock.uptimeMillis() - it.startedAt < gate.confirmSeconds * 1000L + DONE_SHOW_MS } == true
            if (!own) app?.let { GateLog.add(it, GateLog.Entry(System.currentTimeMillis(), gate.id, gate.name, Gate.Action.OPEN,
                GateLog.Origin.OUTSIDE, GateLogic.statusText(status).ifBlank { "открыто" }), video(gate)) }
            onOpened?.invoke(gate)
        }
        return changed
    }

    /** Для тестов: всё забыть. */
    fun reset() {
        deadlines.values.forEach(main::removeCallbacks); deadlines.clear()
        clears.values.forEach(main::removeCallbacks); clears.clear()
        ops.clear(); opGates.clear(); lastPosition.clear(); lastRaw.clear(); listeners.clear()
        pollerOn = false; main.removeCallbacks(poller)
        directStates.clear(); directMisses.clear(); polling.clear(); lastPoll.clear()
        direct = shellyLink
        passwordOverride.clear()
        knownPasswords.clear()
        GateWatch.reset()
        GatePhone.reset()
        HaEvents.wanted = { false }
        HaEvents.onNotificationAction = null
        latest = null
        cached = null
        onOpened = null
        driver = haDriver
    }

    const val POLL_TICK_MS = 500L
    const val POLL_BUSY_MS = 1_000L
    const val POLL_SHOWN_MS = 5_000L
    const val POLL_IDLE_MS = 15_000L
    const val DONE_SHOW_MS = 5_000L
    const val FAILED_SHOW_MS = 8_000L
}
