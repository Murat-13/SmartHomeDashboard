package com.example.smarthomedashboard.data

/**
 * Правило динамической смены цвета виджета по диапазону.
 */
data class ColorRule(
    val entityId: String,
    val from: String? = null,
    val to: String? = null,
    val colorHex: String
)