package com.example.smarthomedashboard

internal object ColorRuleMatcher {
    /**
     * Проверяет, подходит ли значение под диапазон.
     * Если оба числа — математическое сравнение.
     * Если нет — строковое.
     */
    fun matches(value: String, from: String, to: String): Boolean {
        if (value.isEmpty()) return false

        // Очищаем значение от единиц измерения для попытки парсинга числа
        val cleanValue = value.replace(Regex("[^0-9.-]"), "")
        val vNum = cleanValue.toDoubleOrNull()
        val fNum = from.toDoubleOrNull()
        val tNum = to.toDoubleOrNull()

        // Если значение — число, и хотя бы одна граница — число
        if (vNum != null && (fNum != null || tNum != null)) {
            val fromOk = if (from.isEmpty()) true else (fNum != null && vNum >= fNum)
            val toOk = if (to.isEmpty()) true else (tNum != null && vNum <= tNum)
            return fromOk && toOk
        }

        // Иначе сравниваем как строки
        val fromOk = if (from.isEmpty()) true else value.equals(from, ignoreCase = true) || value >= from
        val toOk = if (to.isEmpty()) true else value.equals(to, ignoreCase = true) || value <= to
        return fromOk && toOk
    }
}
