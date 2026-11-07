package com.example.smarthomedashboard

internal object SensorValueFormatter {
    /**
     * Точность из настроек плитки (в т. ч. из импортированной копии) не проверялась: «-1» роняло панель
     * (UnknownFormatConversionException), огромное число строило строку на гигабайты. Найдено хаос-прогоном 0.9.13.
     */
    const val MAX_DECIMALS = 6
    private fun safe(decimals: Int) = decimals.coerceIn(0, MAX_DECIMALS)

    fun formatSensorValue(
        value: String,
        decimals: Int,
        sourceUnit: String,
        targetTempUnit: String
    ): String {
        var floatVal = value.toFloatOrNull() ?: return value
        // «NaN»/«Infinity» от сломанной интеграции — не число для показа.
        if (!floatVal.isFinite()) return "—"

        if (sourceUnit.contains("°")) {
            if (sourceUnit.contains("C") && targetTempUnit == "F") {
                // Конвертируем C в F
                floatVal = (floatVal * 9f / 5f) + 32f
            } else if (sourceUnit.contains("F") && targetTempUnit == "C") {
                // Конвертируем F в C
                floatVal = (floatVal - 32f) * 5f / 9f
            }
        }

        if (!floatVal.isFinite()) return "—"
        return String.format("%.${safe(decimals)}f", floatVal)
    }

    fun formatFloat(v: String, d: Int): String {
        return v.toFloatOrNull()?.takeIf { it.isFinite() }?.let { String.format("%.${safe(d)}f", it) } ?: "—"
    }
}
