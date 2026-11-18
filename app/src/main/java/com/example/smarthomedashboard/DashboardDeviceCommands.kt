package com.example.smarthomedashboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Цель общей кнопки группы (docs/group-fault-tolerance.md, 0.9.20). Только память ViewModel: после перезапуска
 * приложения ничего автоматически не отправляется. [unresolved] — участники, ещё не подтвердившие [target];
 * [corrected] — кому уже ушла единственная догоняющая команда.
 */
internal data class GroupIntent(
    val target: String,
    val generation: Long,
    val tappedAtMs: Long,
    val members: List<String>,
    val unresolved: Set<String>,
    val corrected: Set<String> = emptySet()
)

/** Ожидания принадлежат ViewModel, не View; команд повторной отправки здесь нет. */
internal class DashboardDeviceCommands(
    private val state: DashboardDeviceState,
    private val send: (String, String, String, (ServiceCommandResult) -> Unit) -> Boolean,
    private val changed: () -> Unit,
    private val render: (String) -> Unit = {},
    private val cancelRequest: (String) -> Unit = {},
    private val connectionGeneration: () -> Int = { 0 },
    private val error: (String) -> Unit = {},
    /** Выбор варианта сущности `select` (`select.select_option`): id, вариант, результат. */
    private val sendOption: (String, String, (ServiceCommandResult) -> Unit) -> Boolean = { _, _, _ -> false },
    /** Соединение с HA открыто и авторизовано: условие догоняющей команды группы. */
    private val connected: () -> Boolean = { true }
) {
    private val handler = Handler(Looper.getMainLooper())
    private var nextOperationId = 0L
    private val pending = linkedMapOf<String, PendingCommand>()
    private val deadlines = mutableMapOf<String, Runnable>()
    // Время (elapsedRealtime) последней надёжно обнаруженной ошибки команды по entity ID.
    // Только для краткой индикации Modern: факты и Classic от неё не зависят. Разрыв связи
    // ошибкой команды не считается: для него есть состояние «недоступно».
    private val failures = mutableMapOf<String, Long>()
    private val intents = mutableMapOf<String, GroupIntent>()
    private var nextGeneration = 0L
    val pendingCommands: Map<String, PendingCommand> get() = pending.toMap()

    fun pendingFor(id: String): PendingCommand? = pending[id]

    /** Момент ошибки, если она случилась не раньше [windowMs] назад. */
    fun recentFailure(id: String, windowMs: Long, now: Long = SystemClock.elapsedRealtime()): Long? {
        val at = failures[id] ?: return null
        if (now - at < windowMs) return at
        failures.remove(id)
        return null
    }

    private fun failed(id: String) { failures[id] = SystemClock.elapsedRealtime() }

    fun toggleSingle(id: String): String? {
        expireOverdue()
        if (id in pending) return null
        // Направление по последнему факту, как раньше. Неизвестное показание не объявляется OFF в UI.
        val expected = if (state.singleStates[id] == "on") "off" else "on"
        val command = begin(id, expected)
        notifyChanged(listOf(id))
        return if (dispatch(command)) expected else null
    }

    fun toggleMember(group: String, id: String): String? {
        // Проекция группы содержит те же факты, что и одиночная кнопка.
        state.groupIsOn(group, state.groupStates[group].orEmpty().keys.toList() + id)
        // Индивидуальное нажатие важнее общей команды группы: её ожидание и догоняющий план для этого реле снимаются.
        if (pending[id]?.groupId != null) remove(id)
        supersede(id, keep = null)
        return toggleSingle(id)
    }

    /** Действующая цель группы (её показывает родительская кнопка), пока не все участники её подтвердили. */
    fun groupTarget(group: String): String? = activeIntent(group, SystemClock.elapsedRealtime())?.target

    /**
     * Общая кнопка группы. Каждое нажатие принимается сразу, даже если какое-то реле ещё не ответило:
     * команды уходят всем участникам в одном цикле, без ожидания ответов. Пока прежняя цель не подтверждена
     * всеми, повторное нажатие переворачивает её (а не пересчитывает по возможно устаревшим статусам);
     * в покое направление — как раньше: хоть одно включено — выключить.
     */
    fun toggleGroup(group: String, ids: List<String>) {
        expireOverdue()
        if (ids.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val members = ids.distinct()
        val previous = activeIntent(group, now)?.takeIf { it.members == members }
        val anyOn = state.groupIsOn(group, ids)
        val target = when {
            previous != null -> if (previous.target == "on") "off" else "on"
            anyOn -> "off"
            else -> "on"
        }
        val generation = ++nextGeneration
        // Новая цель отменяет ожидания и догоняющие планы прежних нажатий: старые команды не досылаются.
        members.forEach { id ->
            supersede(id, keep = group)
            if (id in pending) remove(id)
        }
        val commands = members.associateWith { begin(it, target, group, generation = generation) }
        intents[group] = GroupIntent(target, generation, now, members,
            unresolved = members.filterNot { reached(it, target) }.toSet())
        log("нажатие: группа=$group поколение=$generation цель=$target участников=${members.size}")
        notifyChanged(commands.keys)
        // Сохраняем прежние порядок, сервис и дубликаты списка участников.
        ids.forEach { id -> commands[id]?.takeIf { pending[id]?.operationId == it.operationId }?.let(::dispatch) }
    }

    private fun reached(id: String, target: String) =
        state.confirmedState(id) != ConfirmedState.UNAVAILABLE && state.singleStates[id] == target

    private fun activeIntent(group: String, now: Long): GroupIntent? {
        val intent = intents[group] ?: return null
        if (intent.unresolved.isEmpty() || now - intent.tappedAtMs > CORRECTION_WINDOW_MS) {
            intents.remove(group)
            return null
        }
        return intent
    }

    /** Реле получило более новое действие ([keep] — группа, которая его отдаёт): прежние планы для него снимаются. */
    private fun supersede(id: String, keep: String?) {
        intents.entries.toList().forEach { (group, intent) ->
            if (group != keep && id in intent.unresolved) intents[group] = intent.copy(unresolved = intent.unresolved - id)
        }
    }

    /**
     * Свежий факт об участнике группы. Совпал с целью — участник подтверждён. Не совпал, а его команда уже
     * завершилась (тайм-аут) и пришло новое событие от устройства, — единственная догоняющая команда к ПОСЛЕДНЕЙ
     * цели, если: окно [CORRECTION_WINDOW_MS] не истекло, соединение живо, остальные доступные участники уже
     * в цели и их команды завершены. Снимок `get_states` после переподключения поводом не служит.
     */
    private fun groupFact(id: String, fromEvent: Boolean) {
        val now = SystemClock.elapsedRealtime()
        intents.keys.toList().forEach { group ->
            val intent = activeIntent(group, now) ?: return@forEach
            if (id !in intent.unresolved) return@forEach
            if (reached(id, intent.target)) {
                intents[group] = intent.copy(unresolved = intent.unresolved - id)
                log("подтверждено: группа=$group реле=$id поколение=${intent.generation}")
                return@forEach
            }
            val actual = state.singleStates[id]
            if (!fromEvent || id in pending || actual != "on" && actual != "off" || id in intent.corrected) return@forEach
            if (!connected()) return@forEach
            val others = intent.members - id
            if (others.any { it in pending || state.confirmedState(it) != ConfirmedState.UNAVAILABLE && state.singleStates[it] != intent.target }) {
                return@forEach
            }
            intents[group] = intent.copy(corrected = intent.corrected + id)
            log("догоняющая команда: группа=$group реле=$id поколение=${intent.generation} было=$actual цель=${intent.target}")
            val command = begin(id, intent.target, group, generation = intent.generation)
            notifyChanged(listOf(id))
            dispatch(command)
        }
    }

    /** Смена Home Assistant или его настроек: цели групп и ожидания прежнего сервера не переносятся. */
    fun reset() {
        intents.clear()
        pending.keys.toList().forEach(::remove)
    }

    private fun log(message: String) {
        try { Log.i(TAG, message) } catch (_: RuntimeException) {}
    }

    /**
     * Выбрать вариант сущности `select` (приоритет источника инвертора и т. п.). Как и переключение:
     * один запрос без повторов, ожидание до фактического состояния [option] в HA, затем снятие ожидания.
     * false — уже идёт команда этой сущности, вариант уже выбран или отправить не удалось.
     */
    fun selectOption(id: String, option: String): Boolean {
        expireOverdue()
        if (id in pending || state.singleStates[id] == option) return false
        val command = begin(id, option, option = true)
        notifyChanged(listOf(id))
        return dispatch(command) { result -> sendOption(id, option, result) }
    }

    private fun begin(
        id: String, expected: String, groupId: String? = null, option: Boolean = false, generation: Long = 0L
    ): PendingCommand {
        val now = SystemClock.elapsedRealtime()
        failures.remove(id)
        val wait = when {
            option -> OPTION_DEADLINE_MS
            groupId != null -> GROUP_DEADLINE_MS
            else -> DEADLINE_MS
        }
        val command = PendingCommand(
            id, expected, ++nextOperationId, connectionGeneration(), now, now + wait,
            alreadyConfirmed = state.confirmedState(id) != ConfirmedState.UNAVAILABLE && state.singleStates[id] == expected,
            groupId = groupId,
            option = option,
            groupGeneration = generation
        )
        pending[id] = command
        val deadline = Runnable {
            if (isCurrent(command)) {
                val observed = pending.getValue(id).observedAfterStart
                remove(id)
                // Вариант select: последнее значение HA остаётся фактом (иначе блок выбора заблокирован
                // до переподключения, если значение так и не изменилось).
                if (!observed && !command.option) state.markUnconfirmed(id)
                failed(id)
                if (command.groupId != null) {
                    // Участник группы: отметка только на его миниатюре/кнопке, без общей плашки — остальные исправны.
                    log("не подтверждено за ${GROUP_DEADLINE_MS / 1000} с: группа=${command.groupId} реле=$id цель=${command.expectedState}")
                } else {
                    error(if (command.option) "Устройство не подтвердило выбранный вариант. Проверьте его состояние"
                        else "Устройство не подтвердило переключение. Проверьте его состояние")
                }
                notifyChanged(listOf(id))
            }
        }
        deadlines[id] = deadline
        handler.postDelayed(deadline, command.deadlineAtMs - now)
        return command
    }

    private fun dispatch(command: PendingCommand): Boolean = dispatch(command) { result ->
        val id = command.entityId
        send(id.substringBefore('.'), if (command.expectedState == "on") "turn_on" else "turn_off", id, result)
    }

    /** Отправка и обработка ответа одной команды; [transmit] только передаёт запрос в HA. */
    private fun dispatch(command: PendingCommand, transmit: ((ServiceCommandResult) -> Unit) -> Boolean): Boolean {
        val id = command.entityId
        val accepted = transmit { result ->
            if (isCurrent(command)) {
                val currentResult = if (command.connectionGeneration == connectionGeneration()) result else ServiceCommandResult.DISCONNECTED
                if (currentResult == ServiceCommandResult.ACKNOWLEDGED) {
                    if (command.alreadyConfirmed && state.confirmedState(id) != ConfirmedState.UNAVAILABLE && state.singleStates[id] == command.expectedState) {
                        // Идемпотентный участник группы уже был в целевом фактическом состоянии.
                        // ACK не меняет его цвет; остальные участники всё ещё ждут свои события.
                        remove(id)
                        notifyChanged(listOf(id))
                    } else {
                        pending[id] = pending.getValue(id).copy(sent = true, acknowledged = true)
                        changed()
                    }
                } else {
                    val observed = pending.getValue(id).observedAfterStart
                    remove(id)
                    if (currentResult == ServiceCommandResult.ACK_TIMEOUT && !observed && !command.option) state.markUnconfirmed(id)
                    if (currentResult == ServiceCommandResult.DISCONNECTED) state.markDisconnected() else failed(id)
                    error(when (currentResult) {
                        ServiceCommandResult.REJECTED -> "Home Assistant отклонил команду"
                        ServiceCommandResult.ACK_TIMEOUT -> "Нет ответа Home Assistant на команду"
                        else -> "Команда не выполнена: соединение потеряно"
                    })
                    notifyChanged(listOf(id))
                }
            }
        }
        if (!accepted && isCurrent(command)) {
            remove(id)
            failed(id)
            error("Команда не отправлена: проверьте соединение с Home Assistant")
            notifyChanged(listOf(id))
        } else if (accepted && isCurrent(command)) {
            pending[id] = pending.getValue(id).copy(sent = true)
            changed()
        }
        return accepted && (pending[id]?.operationId == command.operationId || state.singleStates[id] == command.expectedState)
    }

    private fun isCurrent(command: PendingCommand) =
        pending[command.entityId]?.operationId == command.operationId

    // Вызывается ПОСЛЕ записи факта. Не откатываем его при позднем ACK/ошибке.
    // fromEvent=false — снимок get_states: подтверждает цель группы, но не служит поводом для догоняющей команды.
    fun entityChanged(id: String, fromEvent: Boolean = true) {
        pendingFact(id)
        if (intents.isNotEmpty()) groupFact(id, fromEvent)
    }

    private fun pendingFact(id: String) {
        val command = pending[id] ?: return
        val actual = state.singleStates[id]
        // У select любое значение, кроме недоступного, — обычный факт (прежний вариант ещё не применён).
        val unavailable = if (command.option) actual == null || actual == "unavailable" || actual == "unknown" ||
            id in state.staleEntities else state.confirmedState(id) == ConfirmedState.UNAVAILABLE
        if (actual == command.expectedState || unavailable) {
            remove(id)
            if (actual != command.expectedState) error("Устройство недоступно: переключение не подтверждено")
        } else {
            pending[id] = command.copy(observedAfterStart = true)
        }
    }

    // Handler использует uptime, а дедлайн — elapsedRealtime. После сна не продлеваем ожидание.
    fun expireOverdue() {
        val now = SystemClock.elapsedRealtime()
        pending.values.toList().filter { it.deadlineAtMs <= now }.forEach { deadlines[it.entityId]?.run() }
    }

    fun disconnected() {
        val ids = state.singleStates.keys.toSet() + pending.keys
        pending.keys.toList().forEach(::remove)
        state.markDisconnected()
        notifyChanged(ids)
    }

    private fun remove(id: String) {
        pending.remove(id)
        deadlines.remove(id)?.let(handler::removeCallbacks)
        cancelRequest(id)
    }

    private fun notifyChanged(ids: Collection<String>) {
        changed()
        ids.forEach(render)
    }

    companion object {
        private const val TAG = "GroupCommands"
        private const val DEADLINE_MS = 20_000L
        /** Ожидание факта от участника общей команды группы; дольше — отметка «не подтверждено» только у него. */
        const val GROUP_DEADLINE_MS = 5_000L
        /** Окно единственной догоняющей команды после последнего нажатия группы; позже — ничего автоматически. */
        const val CORRECTION_WINDOW_MS = 5 * 60_000L
        /** Интеграции инверторов опрашивают устройство раз в 30–60 с: ждём факт дольше, чем у выключателей. */
        private const val OPTION_DEADLINE_MS = 90_000L
    }
}
