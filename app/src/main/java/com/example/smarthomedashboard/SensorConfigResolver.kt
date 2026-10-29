package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Какие датчики показывает плитка. Источник — только настройки самой плитки:
 * ни название, ни зашитые в код entity_id не влияют на результат.
 */
internal object SensorConfigResolver {
    fun getCollapsedSensorIds(tile: TileEntity): List<String> {
        try {
            val config = JSONObject(tile.config)
            val specificArr = config.optJSONArray("collapsed_sensor_configs")
            if (specificArr != null && specificArr.length() > 0) {
                val ids = mutableListOf<String>()
                for (i in 0 until specificArr.length()) {
                    ids.add(specificArr.getJSONObject(i).getString("entity_id"))
                }
                return ids
            }

            // Совместимость со старым полем
            val arr = JSONArray(tile.collapsedSensorIds)
            if (arr.length() > 0) {
                val ids = mutableListOf<String>()
                for (i in 0 until arr.length()) ids.add(arr.getString(i))
                return ids
            }
            // Обычный выбор источников тоже задаёт показания, пока пользователь
            // не настроил собственный свёрнутый набор. Явный пустой набор сохраняем.
            if (tile.type == "sensor" && !config.has("collapsed_sensor_configs")) {
                val selected = config.optJSONArray("entity_ids")
                if (selected != null) return (0 until selected.length()).map { selected.getString(it) }
                val single = config.opt("entity_id") as? String
                if (!single.isNullOrBlank()) return listOf(single)
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    fun getExpandedSensorIds(tile: TileEntity): List<String> {
        try {
            val config = JSONObject(tile.config)
            // Пытаемся взять специфичные для режима конфиги
            val specificArr = config.optJSONArray("expanded_sensor_configs")
            if (specificArr != null && specificArr.length() > 0) {
                val ids = mutableListOf<String>()
                for (i in 0 until specificArr.length()) {
                    ids.add(specificArr.getJSONObject(i).getString("entity_id"))
                }
                return ids
            }

            val arr = config.optJSONArray("entity_ids")
            if (arr != null && arr.length() > 0) {
                val ids = mutableListOf<String>()
                for (i in 0 until arr.length()) ids.add(arr.getString(i))
                return ids
            }
        } catch (_: Exception) {}
        // Раскрытая плитка не бывает пустой: без отдельного выбора показывает свёрнутый набор.
        return getCollapsedSensorIds(tile)
    }

    fun loadSensorConfigs(tile: TileEntity, isExpanded: Boolean): List<SensorConfig> {
        val own = loadSensorConfigs(tile, if (isExpanded) "expanded_sensor_configs" else "collapsed_sensor_configs")
        // Строки, взятые из свёрнутого вида, сохраняют свои названия и точность.
        return if (isExpanded && own.isEmpty()) loadSensorConfigs(tile, "collapsed_sensor_configs") else own
    }

    private fun loadSensorConfigs(tile: TileEntity, key: String): List<SensorConfig> {
        try {
            val config = JSONObject(tile.config)
            val arr = config.optJSONArray(key)
            if (arr != null && arr.length() > 0) {
                val list = mutableListOf<SensorConfig>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(SensorConfig(
                        entityId = obj.getString("entity_id"),
                        displayName = obj.optString("display_name", obj.getString("entity_id").substringAfterLast("_")),
                        decimals = obj.optInt("decimals", 0)
                    ))
                }
                return list
            }
        } catch (_: Exception) {}
        return emptyList()
    }
}
