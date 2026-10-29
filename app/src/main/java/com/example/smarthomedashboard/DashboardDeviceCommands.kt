package com.example.smarthomedashboard

/** Команды устройств без View/Context. Команды не сохраняются для повторной отправки. */
internal class DashboardDeviceCommands(
    private val state: DashboardDeviceState,
    private val send: (String, String, String, () -> Unit) -> Boolean,
    private val changed: () -> Unit,
    private val restored: (String) -> Unit = {}
) {
    private data class Target(val id: String, val group: String? = null)
    private class Pending(val before: String?) {
        var finished = false
    }
    private val pending = mutableMapOf<Target, Pending>()

    fun toggleSingle(id: String): String? = toggle(Target(id))

    fun toggleMember(group: String, id: String): String? = toggle(Target(id, group))

    private fun toggle(target: Target): String? {
        val values = if (target.group == null) state.singleStates else state.groupStates[target.group]
        val next = if (values?.get(target.id) == "on") "off" else "on"
        val previous = pending[target]
        val request = Pending(if (previous != null) previous.before else values?.get(target.id))
        val accepted = send(target.id.substringBefore("."), service(next), target.id) {
            request.finished = true
            if (pending[target] === request) {
                pending.remove(target)
                val current = if (target.group == null) state.singleStates else state.groupStates[target.group]
                if (request.before == null) current?.remove(target.id)
                else current?.set(target.id, request.before)
                changed()
                restored(target.id)
            }
        }
        if (!accepted || request.finished) return null
        pending[target] = request
        if (target.group == null) state.singleStates[target.id] = next
        else state.updateGroup(target.group, target.id, next)
        changed()
        return next
    }

    // Фактическое событие приоритетнее любого позднего ACK или тайм-аута старой команды.
    fun entityChanged(id: String) {
        pending.keys.removeAll { it.id == id }
    }

    fun toggleGroup(group: String, ids: List<String>) {
        val before = state.groupStates[group]?.toMap()
        val target = if (state.groupIsOn(group, ids)) "turn_off" else "turn_on"
        if (before != state.groupStates[group]) changed()
        // Порядок и дубликаты сохранены: прежняя панель отправляла одну команду каждому элементу списка.
        ids.forEach { id -> send(id.substringBefore("."), target, id) {} }
    }

    private fun service(state: String) = if (state == "on") "turn_on" else "turn_off"
}
