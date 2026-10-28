package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Сущности HA, которые может прочитать хоть одна плитка панели.
 * Набор намеренно избыточный: в него попадает каждая строка из JSON-полей плитки
 * и результат всех резолверов, включая значения по умолчанию. Лишний id стоит одну
 * перерисовку, а пропущенный оставил бы на экране устаревшее значение.
 */
internal object PanelEntityIds {
    fun collect(tiles: List<TileEntity>, extra: Collection<String>): Set<String> {
        val ids = HashSet<String>(extra)
        for (tile in tiles) {
            listOf(tile.config, tile.colorRules, tile.collapsedSensorIds, tile.appearance, tile.conditions)
                .forEach { parseStrings(it, ids) }
            ids += SensorConfigResolver.getCollapsedSensorIds(tile)
            ids += SensorConfigResolver.getExpandedSensorIds(tile)
            ids += WeatherEntityResolver.resolve(tile.config)
        }
        return ids
    }

    private fun parseStrings(json: String?, into: MutableSet<String>) {
        if (json.isNullOrBlank()) return
        val value = try { JSONTokener(json).nextValue() } catch (_: Exception) { return }
        walkStrings(value, into)
    }

    private fun walkStrings(value: Any?, into: MutableSet<String>) {
        when (value) {
            is String -> into += value
            is JSONObject -> value.keys().forEach { walkStrings(value.opt(it), into) }
            is JSONArray -> for (i in 0 until value.length()) walkStrings(value.opt(i), into)
        }
    }
}
