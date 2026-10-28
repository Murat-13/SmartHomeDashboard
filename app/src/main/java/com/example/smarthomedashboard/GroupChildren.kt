package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Устройства группы и имена их кнопок. Имя хранится вместе с устройством, поэтому
 * замена, удаление и перестановка не сдвигают имена на соседние кнопки.
 */
internal class GroupChildren(val items: MutableList<ChildButtonData> = mutableListOf()) {

    enum class Change { DONE, DUPLICATE, IGNORED }

    /** Меняет устройство кнопки, сохраняя её имя и место в группе. */
    fun replace(position: Int, entityId: String): Change {
        if (position !in items.indices || entityId.isBlank()) return Change.IGNORED
        if (items.withIndex().any { (i, item) -> i != position && item.entityId == entityId }) return Change.DUPLICATE
        items[position] = items[position].copy(entityId = entityId)
        return Change.DONE
    }

    fun remove(position: Int): Boolean {
        if (position !in items.indices) return false
        items.removeAt(position)
        return true
    }

    /** Добавляет новые устройства в конец, пропуская уже входящие в группу. Возвращает число добавленных. */
    fun add(entityIds: List<String>, nameFor: (String) -> String = { "" }): Int {
        var added = 0
        for (id in entityIds) {
            if (id.isBlank() || items.any { it.entityId == id }) continue
            items += ChildButtonData(id, nameFor(id))
            added++
        }
        return added
    }

    /** Перестановка соседних строк, как её выполняет ItemTouchHelper. */
    fun move(from: Int, to: Int) {
        if (from in items.indices && to in items.indices) Collections.swap(items, from, to)
    }

    /** Записывает устройства и имена; пустое имя заменяется номером кнопки. */
    fun writeTo(config: JSONObject) {
        val ids = JSONArray()
        val names = JSONArray()
        items.forEachIndexed { index, item ->
            ids.put(item.entityId)
            names.put(item.name.trim().ifEmpty { (index + 1).toString() })
        }
        config.put("entity_ids", ids)
        config.put("child_names", names)
        // Группа управляет только списком: одиночный entity_id от прежнего типа плитки не нужен.
        config.remove("entity_id")
    }

    companion object {
        fun fromConfig(config: JSONObject): GroupChildren {
            val ids = config.optJSONArray("entity_ids")
            val names = config.optJSONArray("child_names")
            val items = mutableListOf<ChildButtonData>()
            if (ids != null) {
                for (i in 0 until ids.length()) {
                    val name = if (names != null && i < names.length()) names.optString(i) else (i + 1).toString()
                    items += ChildButtonData(ids.optString(i), name)
                }
            } else {
                // Плитка, переделанная из обычной кнопки в группу, хранит одно устройство в entity_id.
                config.optString("entity_id").takeIf { it.isNotBlank() }?.let { items += ChildButtonData(it, "1") }
            }
            return GroupChildren(items)
        }
    }
}
