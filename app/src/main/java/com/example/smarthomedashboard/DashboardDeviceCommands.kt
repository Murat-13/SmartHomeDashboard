package com.example.smarthomedashboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Ожидания принадлежат ViewModel, не View; команд повторной отправки здесь нет. */
internal class DashboardDeviceCommands(
    private val state: DashboardDeviceState,
    private val send: (String, String, String, (ServiceCommandResult) -> Unit) -> Boolean,
    private val changed: () -> Unit,
    private val render: (String) -> Unit = {},
    private val cancelRequest: (String) -> Unit = {},
    private val connectionGeneration: () -> Int = { 0 },
    private val error: (String) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private var nextOperationId = 0L
    private val pending = linkedMapOf<String, PendingCommand>()
    private val deadlines = mutableMapOf<String, Runnable>()
    // Время (elapsedRealtime) последней надёжно обнаруженной ошибки команды по entity ID.
    // Только для краткой индикации Modern: факты и Classic от неё не зависят. Разрыв связи
    // ошибкой команды не считается: для него есть состояние «недоступно».
    private val failures = mutableMapOf<String, Long>()
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
        return toggleSingle(id)
    }

    fun toggleGroup(group: String, ids: List<String>) {
        expireOverdue()
        if (ids.isEmpty() || ids.any { it in pending }) return
        val active = state.groupIsOn(group, ids)
        val expected = if (active) "off" else "on"
        val commands = ids.distinct().associateWith { begin(it, expected, group) }
        notifyChanged(commands.keys)
        // Сохраняем прежние порядок, сервис и дубликаты списка участников.
        ids.forEach { id -> commands[id]?.takeIf { pending[id]?.operationId == it.operationId }?.let(::dispatch) }
    }

    private fun begin(id: String, expected: String, groupId: String? = null): PendingCommand {
        val now = SystemClock.elapsedRealtime()
        failures.remove(id)
        val command = PendingCommand(
            id, expected, ++nextOperationId, connectionGeneration(), now, now + 20_000L,
            alreadyConfirmed = state.confirmedState(id) != ConfirmedState.UNAVAILABLE && state.singleStates[id] == expected,
            groupId = groupId
        )
        pending[id] = command
        val deadline = Runnable {
            if (isCurrent(command)) {
                val observed = pending.getValue(id).observedAfterStart
                remove(id)
                if (!observed) state.markUnconfirmed(id)
                failed(id)
                error("Устройство не подтвердило переключение. Проверьте его состояние")
                notifyChanged(listOf(id))
            }
        }
        deadlines[id] = deadline
        handler.postDelayed(deadline, command.deadlineAtMs - now)
        return command
    }

    private fun dispatch(command: PendingCommand): Boolean {
        val id = command.entityId
        val accepted = send(id.substringBefore('.'), if (command.expectedState == "on") "turn_on" else "turn_off", id) { result ->
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
                    if (currentResult == ServiceCommandResult.ACK_TIMEOUT && !observed) state.markUnconfirmed(id)
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
    fun entityChanged(id: String) {
        val command = pending[id] ?: return
        val actual = state.singleStates[id]
        if (actual == command.expectedState || state.confirmedState(id) == ConfirmedState.UNAVAILABLE) {
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
}
