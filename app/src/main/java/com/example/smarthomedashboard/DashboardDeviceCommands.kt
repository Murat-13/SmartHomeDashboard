package com.example.smarthomedashboard

/** Команды устройств без View/Context. Команды не сохраняются для повторной отправки. */
internal class DashboardDeviceCommands(
    private val state: DashboardDeviceState,
    private val send: (String, String, String) -> Unit,
    private val changed: () -> Unit
) {
    fun toggleSingle(id: String): String {
        val next = if (state.singleStates[id] == "on") "off" else "on"
        send(id.substringBefore("."), service(next), id)
        state.singleStates[id] = next
        changed()
        return next
    }

    fun toggleMember(group: String, id: String): String {
        val next = if (state.groupStates[group]?.get(id) == "on") "off" else "on"
        send(id.substringBefore("."), service(next), id)
        state.updateGroup(group, id, next)
        changed()
        return next
    }

    fun toggleGroup(group: String, ids: List<String>) {
        val before = state.groupStates[group]?.toMap()
        val target = if (state.groupIsOn(group, ids)) "turn_off" else "turn_on"
        if (before != state.groupStates[group]) changed()
        // Порядок и дубликаты сохранены: прежняя панель отправляла одну команду каждому элементу списка.
        ids.forEach { id -> send(id.substringBefore("."), target, id) }
    }

    private fun service(state: String) = if (state == "on") "turn_on" else "turn_off"
}
