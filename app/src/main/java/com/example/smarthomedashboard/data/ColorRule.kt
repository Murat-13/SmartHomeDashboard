package com.example.smarthomedashboard.data

/**
 * Правило динамической смены цвета виджета.
 * @param entityId ID сущности для мониторинга (например, sensor.pzem_voltage)
 * @param condition Оператор сравнения (>, <, ==, !=)
 * @param value Значение, с которым сравниваем (может быть числом или строкой типа "on")
 * @param colorHex Цвет фона в формате #AARRGGBB
 */
data class ColorRule(
    val entityId: String,
    val condition: String,
    val value: String,
    val colorHex: String
)
