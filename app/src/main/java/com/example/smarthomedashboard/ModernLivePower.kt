package com.example.smarthomedashboard

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/** Независимая от управляющей сущности настройка; отсутствие полей всегда означает OFF. */
internal data class LivePowerConfig(val enabled: Boolean = false, val entityId: String = "") {
    fun error(attributes: JSONObject? = null): String? = when {
        !enabled -> null
        !entityId.matches(Regex("sensor\\.[a-z0-9_]+")) -> "Выберите сущность sensor.* мощности"
        attributes != null && !supportedUnit(attributes) -> "Нужен сенсор мощности с единицей W или kW, не энергии"
        else -> null
    }
    fun writeTo(json: JSONObject) {
        json.put("show_power", enabled)
        json.put("power_entity_id", entityId)
    }
    fun text(states: Map<String, String>, attrs: Map<String, JSONObject>, stale: Set<String>): String? {
        if (!enabled) return null
        if (entityId in stale || error() != null) return "— Вт"
        val attributes = attrs[entityId] ?: return "— Вт"
        if (!supportedUnit(attributes)) return "— Вт"
        val source = states[entityId]?.takeIf { it.length <= 64 } ?: return "— Вт"
        val raw = source.toBigDecimalOrNull() ?: return "— Вт"
        // Ограничиваем патологические экспоненты до операций BigDecimal и форматирования.
        if (raw.scale() !in -100..100) return "— Вт"
        val watts = if (attributes.optString("unit_of_measurement") in setOf("kW", "кВт")) raw.multiply(BigDecimal(1000)) else raw
        if (watts.abs() > BigDecimal("1000000000")) return "— Вт"
        val kilo = watts.abs() >= BigDecimal(1000)
        val number = (if (kilo) watts.divide(BigDecimal(1000)) else watts)
            .setScale(if (kilo) 2 else 0, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')
        return number + if (kilo) " кВт" else " Вт"
    }
    companion object {
        fun read(json: JSONObject?) = LivePowerConfig(json?.optBoolean("show_power", false) == true,
            json?.optString("power_entity_id").orEmpty().trim())
        fun read(config: String) = read(runCatching { JSONObject(config) }.getOrNull())
        private fun supportedUnit(attrs: JSONObject): Boolean =
            attrs.optString("unit_of_measurement") in setOf("W", "kW", "Вт", "кВт") &&
                attrs.optString("device_class") != "energy"
    }
}
