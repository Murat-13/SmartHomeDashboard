package com.example.smarthomedashboard

import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Сессия соединения с HA для оценки достоверности. [synced] — в этой сессии получен
 * первоначальный снимок состояний; до этого значения в хранилище остаются от прошлой сессии.
 */
data class HaSession(val connected: Boolean = false, val synced: Boolean = false)

/** Достоверность одного показания. Глобальное хранилище состояний не очищается. */
internal sealed interface BatteryReading {
    data object NotConfigured : BatteryReading
    data object Disconnected : BatteryReading
    data object Syncing : BatteryReading
    /** Сущности нет среди данных HA. */
    data object Missing : BatteryReading
    /** Значение есть, но не подтверждено текущей сессией (например, после переподключения его нет в снимке). */
    data object Unconfirmed : BatteryReading
    /** Датчик сам сообщил unknown/unavailable. */
    data class Unavailable(val state: String) : BatteryReading
    data class Value(val raw: String, val number: Double?, val attributes: JSONObject?) : BatteryReading

    val trusted get() = this is Value
}

internal class BatteryReadings(
    private val session: HaSession,
    private val state: (String) -> String?,
    private val stale: (String) -> Boolean,
    private val attributes: (String) -> JSONObject?
) {
    fun of(entityId: String): BatteryReading {
        if (entityId.isBlank()) return BatteryReading.NotConfigured
        if (!session.connected) return BatteryReading.Disconnected
        if (!session.synced) return BatteryReading.Syncing
        val raw = state(entityId) ?: return BatteryReading.Missing
        if (stale(entityId)) return BatteryReading.Unconfirmed
        if (raw == "unknown" || raw == "unavailable" || raw.isBlank()) return BatteryReading.Unavailable(raw)
        return BatteryReading.Value(raw, BatteryFormat.number(raw), attributes(entityId))
    }

    fun of(row: BatteryModernConfig.Row?): BatteryReading = of(row?.entityId.orEmpty())

    /** Общий статус карточки: показания без связи или до синхронизации не считаются текущими. */
    val status: String? get() = when {
        !session.connected -> "Нет связи с Home Assistant"
        !session.synced -> "Синхронизация с Home Assistant…"
        else -> null
    }

    companion object {
        fun from(model: MainUiState) = BatteryReadings(model.haSession, model.singleStates::get,
            { it in model.staleEntities }, model.entityAttributes::get)

        internal fun from(session: HaSession, devices: DashboardDeviceState) = BatteryReadings(session,
            devices.singleStates::get, { it in devices.staleEntities }, devices.entityAttributes::get)
    }
}

/** Направление по знаку мощности. Подпись и стрелка не зависят только от цвета. */
internal enum class PowerFlow(val label: String, val arrow: String, val color: Int) {
    CHARGE("Заряд", "↑", 0xFF34E89E.toInt()),
    DISCHARGE("Разряд", "↓", 0xFFFFB74D.toInt()),
    IDLE("Ожидание", "■", 0xFF90A4AE.toInt()),
    NO_DATA("Нет данных", "—", 0xFF78909C.toInt());

    companion object {
        /** По умолчанию (BMS владельца): минус — разряд, плюс — заряд, около нуля — ожидание. */
        fun of(power: Double?, invert: Boolean, idleThreshold: Double): PowerFlow {
            if (power == null || !power.isFinite()) return NO_DATA
            if (abs(power) <= idleThreshold) return IDLE
            val charging = if (invert) power < 0 else power > 0
            return if (charging) CHARGE else DISCHARGE
        }
    }
}

internal object BatteryFormat {
    data class Text(val value: String, val unit: String = "")

    /** Единицы времени HA и привычные русские обозначения, которые владелец мог ввести сам. */
    private val timeUnits = mapOf(
        "s" to 1.0, "sec" to 1.0, "с" to 1.0, "сек" to 1.0,
        "min" to 60.0, "мин" to 60.0,
        "h" to 3600.0, "hr" to 3600.0, "ч" to 3600.0,
        "d" to 86400.0, "д" to 86400.0, "сут" to 86400.0
    )

    private fun timeFactor(unit: String?): Double? =
        unit?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT)?.let(timeUnits::get)

    /** Число из состояния HA целиком: «12.5», «-7,69», «1e3». Части строк не склеиваются. */
    fun number(raw: String): Double? = raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    fun unit(row: BatteryModernConfig.Row, reading: BatteryReading.Value): String =
        row.unit.ifBlank { reading.attributes?.optString("unit_of_measurement").orEmpty() }

    /** Текст для строк списка и компактных зон. Недостоверное — «—», без подстановки нуля. */
    fun text(row: BatteryModernConfig.Row?, reading: BatteryReading, tempUnit: String = "C",
             locale: Locale = Locale.getDefault()): Text = when (reading) {
        is BatteryReading.Value -> value(requireNotNull(row), reading, tempUnit, locale)
        is BatteryReading.Unavailable -> Text(if (reading.state == "unavailable") "Недоступно" else "Неизвестно")
        BatteryReading.Missing -> Text("Нет сущности")
        else -> Text("—")
    }

    private fun value(row: BatteryModernConfig.Row, reading: BatteryReading.Value, tempUnit: String, locale: Locale): Text {
        val unit = unit(row, reading)
        val deviceClass = reading.attributes?.optString("device_class").orEmpty()
        var number = reading.number ?: return Text(word(reading.raw, deviceClass))
        // Длительность всегда «4 ч 16 мин», а не «4,27 ч»: точность строки для времени не применяется.
        // Пересчёт — от единицы HA (в ней пришло число); без неё — от единицы, заданной в строке.
        val haUnit = reading.attributes?.optString("unit_of_measurement").orEmpty()
        val seconds = timeFactor(haUnit)
            ?: if (haUnit.isBlank() || deviceClass == "duration") timeFactor(unit) else null
        if (seconds != null) return Text(duration(number * seconds))
        var shownUnit = unit
        if (unit.contains("°")) {
            if (unit.contains("C") && tempUnit == "F") { number = number * 9 / 5 + 32; shownUnit = "°F" }
            else if (unit.contains("F") && tempUnit == "C") { number = (number - 32) * 5 / 9; shownUnit = "°C" }
        }
        return Text(decimal(number, row.decimals ?: autoDecimals(reading.raw), locale), shownUnit)
    }

    fun decimal(number: Double, decimals: Int, locale: Locale = Locale.getDefault()): String {
        // «-0,0» не показываем: округлённый ноль не имеет знака. Знак остальных значений сохраняется.
        val rounded = java.math.BigDecimal(number).setScale(decimals, java.math.RoundingMode.HALF_UP)
        val value = if (rounded.signum() == 0) 0.0 else number
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            isGroupingUsed = abs(number) >= 10_000
        }.format(value)
    }

    private fun autoDecimals(raw: String): Int {
        val text = raw.trim().replace(',', '.')
        if (text.contains('e', ignoreCase = true)) return 2
        return text.substringAfter('.', "").length.coerceAtMost(2)
    }

    fun duration(totalSeconds: Double): String {
        if (!totalSeconds.isFinite() || totalSeconds < 0) return "—"
        val minutes = (totalSeconds / 60).roundToLong()
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val rest = minutes % 60
        return when {
            days > 0 -> "$days д $hours ч"
            hours > 0 -> "$hours ч $rest мин"
            else -> "$rest мин"
        }
    }

    private fun word(raw: String, deviceClass: String): String = when (raw.lowercase(Locale.ROOT)) {
        "on" -> when (deviceClass) {
            "battery_charging" -> "Заряжается"
            "problem" -> "Проблема"
            else -> "Включено"
        }
        "off" -> when (deviceClass) {
            "battery_charging" -> "Не заряжается"
            "problem" -> "OK"
            else -> "Выключено"
        }
        else -> raw
    }

    /** Доля шкалы: только достоверное число 0–100. Иначе null — нейтральная шкала. */
    fun socFraction(reading: BatteryReading): Float? {
        val value = (reading as? BatteryReading.Value)?.number ?: return null
        if (value < 0 || value > 100) return null
        return (value / 100.0).toFloat()
    }
}
