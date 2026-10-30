package com.example.smarthomedashboard

/**
 * Подтверждённое цветовое состояние одной плитки «Электропитание Modern». Только чистая логика:
 * время передаёт вызывающий, таймеры и Views — у панели и карточки.
 *
 * - Нет достоверного напряжения → сразу нейтральное состояние (без задержки подтверждения).
 * - Любой новый диапазон, в том числе первый после запуска или после отсутствия данных,
 *   должен держаться [PowerModernConfig.confirmMs]; до этого цвет и пульсация не включаются.
 * - Смена подтверждённого диапазона: напряжение выходит за его границу минимум на
 *   гистерезис (кроме точечных правил вроде «0 В») и держится в новом диапазоне столько же.
 *
 * Экземпляр хранится во ViewModel по id плитки: уже подтверждённое состояние переживает
 * пересоздание UI и показывается новой View сразу (единственное исключение из подтверждения).
 */
internal class PowerStateTracker {
    /** null — нейтрально (нет достоверного напряжения); -1 — значение вне всех правил. */
    var confirmed: Int? = null
        private set
    var candidate: Int? = null
        private set
    private var candidateSince = 0L
    private var rulesKey: List<PowerModernConfig.Rule>? = null

    /** Время следующей проверки подтверждения; null — ждать нечего. */
    val nextCheckAt: Long? get() = if (candidate != null) candidateSince + confirmMsCache else null
    private var confirmMsCache = PowerModernConfig.DEFAULT_CONFIRM_MS

    /** [voltage] — только достоверное напряжение текущей сессии, иначе null. */
    fun update(voltage: Double?, config: PowerModernConfig, now: Long): Int? {
        confirmMsCache = config.confirmMs
        if (rulesKey != config.rules) {
            // Правила изменены (сохранён редактор): классифицируем заново без ожидания.
            rulesKey = config.rules
            confirmed = null
            candidate = null
        }
        if (voltage == null || !voltage.isFinite()) {
            confirmed = null
            candidate = null
            return null
        }
        val target = config.ruleFor(voltage)
        val current = confirmed
        if (current == null) {
            // Первый диапазон (запуск, восстановление данных) тоже подтверждается, без гистерезиса.
            if (candidate != target) {
                candidate = target
                candidateSince = now
            }
            if (now - candidateSince >= config.confirmMs) {
                confirmed = target
                candidate = null
            }
            return confirmed
        }
        if (target == current) {
            candidate = null
            return current
        }
        val currentRule = config.rules.getOrNull(current)
        val targetRule = config.rules.getOrNull(target)
        // Граница пульсации (вход в критический интервал и выход из него) — без гистерезиса, но с тем же
        // подтверждением: иначе устойчивые 280 В после 279 В не пульсируют, а 151 В после 150 В — пульсируют.
        val pulseBoundary = currentRule != null && targetRule != null && currentRule.pulse != targetRule.pulse
        val beyond = currentRule == null || targetRule == null || targetRule.isPoint || pulseBoundary ||
            currentRule.distance(voltage) >= config.hysteresis
        if (!beyond) {
            // Колебание у границы в пределах гистерезиса: ожидание нового диапазона сбрасывается.
            candidate = null
            return current
        }
        if (candidate != target) {
            candidate = target
            candidateSince = now
        }
        if (now - candidateSince >= config.confirmMs) {
            confirmed = target
            candidate = null
        }
        return confirmed
    }

    fun reset() {
        confirmed = null
        candidate = null
        rulesKey = null
    }
}

/** Что показывает плитка: цвет оформления, подпись статуса и пульсация. Цифры — всегда белые. */
internal data class PowerVisual(val color: Int, val status: String, val pulse: Boolean, val neutral: Boolean) {
    companion object {
        /**
         * [voltage] — показание напряжения с оценкой достоверности; [confirmed] — результат трекера.
         * Отсутствие данных никогда не превращается в «0 В» и не окрашивается как диапазон.
         */
        fun of(config: PowerModernConfig, voltage: BatteryReading, readings: BatteryReadings, confirmed: Int?): PowerVisual {
            readings.status?.let { return PowerVisual(PowerModernConfig.NEUTRAL, it, pulse = false, neutral = true) }
            if (voltage is BatteryReading.Value && voltage.number != null && confirmed == null) {
                // Значение уже показано, диапазон ещё подтверждается: не авария и не «Норма».
                return PowerVisual(PowerModernConfig.NEUTRAL, "Определение состояния…", pulse = false, neutral = true)
            }
            if (voltage !is BatteryReading.Value || voltage.number == null || confirmed == null) {
                val status = when (voltage) {
                    BatteryReading.NotConfigured -> "Нет данных: напряжение не выбрано"
                    BatteryReading.Missing -> "Нет данных: сущность не найдена"
                    is BatteryReading.Unavailable -> "Нет данных"
                    else -> "Нет данных"
                }
                return PowerVisual(PowerModernConfig.NEUTRAL, status, pulse = false, neutral = true)
            }
            val rule = config.rules.getOrNull(confirmed)
                ?: return PowerVisual(PowerModernConfig.NEUTRAL, "Вне заданных диапазонов", pulse = false, neutral = true)
            return PowerVisual(rule.color, rule.label, rule.pulse, neutral = false)
        }

        /** Предпросмотр редактора: тестовое значение без HA, сразу подтверждённое. */
        fun preview(config: PowerModernConfig, voltage: Double): PowerVisual {
            val rule = config.rules.getOrNull(config.ruleFor(voltage))
                ?: return PowerVisual(PowerModernConfig.NEUTRAL, "Вне заданных диапазонов", pulse = false, neutral = true)
            return PowerVisual(rule.color, rule.label, rule.pulse, neutral = false)
        }
    }
}
