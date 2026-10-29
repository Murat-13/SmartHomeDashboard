package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Единый консервативный расчёт зависимостей для фильтра и адресного индекса. */
internal object PanelEntityIds {
    data class Dependencies(val ids: Set<String>, val malformed: Boolean)

    fun collect(tiles: List<TileEntity>, extra: Collection<String>): Set<String> =
        HashSet<String>(extra).also { ids -> tiles.forEach { ids.addAll(describe(it).ids) } }

    fun describe(tile: TileEntity): Dependencies {
        val ids = HashSet<String>()
        var malformed = false
        fun read(text: String?, array: Boolean?) {
            if (text.isNullOrBlank()) return
            val value = try { JSONTokener(text).nextValue() } catch (_: Exception) {
                malformed = true
                return
            }
            if ((array == true && value !is JSONArray) || (array == false && value !is JSONObject)) malformed = true
            walkStrings(value, ids)
        }
        // Все строки расширенных полей оставлены: неизвестное поле безопаснее учесть избыточно.
        read(tile.config, false)
        read(tile.colorRules, true)
        read(tile.collapsedSensorIds, true)
        read(tile.appearance, null)
        read(tile.conditions, null)
        ids += SensorConfigResolver.getCollapsedSensorIds(tile)
        ids += SensorConfigResolver.getExpandedSensorIds(tile)
        if (tile.type == "weather") ids += WeatherEntityResolver.resolve(tile.config)
        return Dependencies(ids, malformed)
    }

    private fun walkStrings(value: Any?, into: MutableSet<String>) {
        when (value) {
            is String -> into += value
            is JSONObject -> value.keys().forEach { walkStrings(value.opt(it), into) }
            is JSONArray -> for (i in 0 until value.length()) walkStrings(value.opt(i), into)
        }
    }
}
