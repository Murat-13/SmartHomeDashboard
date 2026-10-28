package com.example.smarthomedashboard

internal object SensorValueFormatter {
    fun formatSensorValue(
        value: String,
        decimals: Int,
        sourceUnit: String,
        targetTempUnit: String
    ): String {
        var floatVal = value.toFloatOrNull() ?: return value

        if (sourceUnit.contains("°")) {
            if (sourceUnit.contains("C") && targetTempUnit == "F") {
                // Конвертируем C в F
                floatVal = (floatVal * 9f / 5f) + 32f
            } else if (sourceUnit.contains("F") && targetTempUnit == "C") {
                // Конвертируем F в C
                floatVal = (floatVal - 32f) * 5f / 9f
            }
        }

        return String.format("%.${decimals}f", floatVal)
    }

    fun formatFloat(v: String, d: Int): String {
        return v.toFloatOrNull()?.let { String.format("%.${d}f", it) } ?: "—"
    }
}
