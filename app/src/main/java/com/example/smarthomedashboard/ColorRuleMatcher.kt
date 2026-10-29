package com.example.smarthomedashboard

internal object ColorRuleMatcher {
    // Одно число с единицей; также сохраняем существующий вид «Температура: -12.5 °C».
    // Не склеиваем цифры из ошибочных строк вроде «1 2» или «5 V 5».
    private val numberWithUnit = Regex(
        """^\s*(?:[^\d]*:\s*)?([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)\s*([^\d.,+\-]*)$"""
    )

    /** Числовые диапазоны не переходят к строковому сравнению при ошибке показания. */
    fun matches(value: String, from: String, to: String): Boolean {
        if (value.isEmpty()) return false
        val lower = from.trim()
        val upper = to.trim()
        val fNum = lower.toDoubleOrNull()
        val tNum = upper.toDoubleOrNull()
        if ((fNum != null && !fNum.isFinite()) || (tNum != null && !tNum.isFinite())) return false

        if (fNum != null || tNum != null) {
            // Смешанные числовая/текстовая границы неоднозначны: безопасно не совпадают.
            if ((lower.isNotEmpty() && fNum == null) || (upper.isNotEmpty() && tNum == null)) return false
            val vNum = numericValue(value) ?: return false
            return (fNum == null || vNum >= fNum) && (tNum == null || vNum <= tNum)
        }
        if (lower.isEmpty() && upper.isEmpty()) return true

        // Текстовые состояния сохраняют прежнее сравнение и регистронезависимое равенство.
        val fromOk = if (from.isEmpty()) true else value.equals(from, ignoreCase = true) || value >= from
        val toOk = if (to.isEmpty()) true else value.equals(to, ignoreCase = true) || value <= to
        return fromOk && toOk
    }

    private fun numericValue(value: String): Double? {
        value.trim().toDoubleOrNull()?.let { return it.takeIf { number -> number.isFinite() } }
        val match = numberWithUnit.matchEntire(value) ?: return null
        // Незавершённую экспоненту не принимаем за единицу измерения.
        if (match.groupValues[2].trim().equals("e", ignoreCase = true)) return null
        return match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
