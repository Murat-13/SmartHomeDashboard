package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject

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
        } catch (_: Exception) {}
        // По умолчанию (ищем по вхождению слова)
        val title = tile.title.lowercase()
        return when {
            title.contains("сеть") -> listOf(
                "sensor.pzem_energy_monitor_pzem_voltage",
                "sensor.pzem_energy_monitor_pzem_power"
            )
            title.contains("температура") -> listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia")
            else -> emptyList()
        }
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
        // По умолчанию
        val title = tile.title.lowercase()
        return when {
            title.contains("сеть") -> listOf(
                "sensor.pzem_energy_monitor_pzem_voltage",
                "sensor.pzem_energy_monitor_pzem_power",
                "sensor.pzem_energy_monitor_pzem_current",
                "sensor.pzem_energy_monitor_pzem_frequency",
                "sensor.pzem_energy_monitor_pzem_energy"
            )
            title.contains("температура") -> listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia")
            else -> emptyList()
        }
    }

    fun loadSensorConfigs(tile: TileEntity, isExpanded: Boolean): List<SensorConfig> {
        try {
            val config = JSONObject(tile.config)
            val key = if (isExpanded) "expanded_sensor_configs" else "collapsed_sensor_configs"
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
