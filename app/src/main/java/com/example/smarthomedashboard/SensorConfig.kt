package com.example.smarthomedashboard

/**
 * Конфигурация одного датчика в виджете.
 * Хранит entity_id, отображаемое имя и количество десятичных знаков.
 */
data class SensorConfig(
    val entityId: String,       // ID датчика в HA (например, sensor.pzem_voltage)
    val displayName: String,    // Отображаемое имя (например, "Напряжение")
    val decimals: Int = 0       // Количество знаков после запятой (0 = целые, 1 = десятые, 2 = сотые)
)