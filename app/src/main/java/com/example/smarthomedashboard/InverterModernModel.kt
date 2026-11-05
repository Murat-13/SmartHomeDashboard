package com.example.smarthomedashboard

import kotlin.math.abs

/**
 * Чистая логика плитки «Инвертор»: потоки энергии, режим, загрузка и состояние для цвета.
 * Без Views и времени — всё проверяется юнит-тестами. Отсутствующие и недостоверные показания
 * остаются null и показываются прочерком, а не нулём (правила достоверности Modern).
 */
internal object InverterModel {
    /** Мощность по модулю не больше порога считается «потока нет» (точки не бегут, узел приглушён). */
    const val IDLE_W = 15.0

    /** Режим инвертора по данным HA. */
    enum class Mode { GRID, OFF_GRID, BYPASS, CHARGING, STANDBY, FAULT, UNKNOWN }

    /** Состояние оформления (решение владельца: цвет — по режиму и авариям). */
    enum class Look(val color: Int, val pulse: Boolean) {
        /** Нет связи с HA или данных инвертора. */
        NO_DATA(PowerModernConfig.NEUTRAL, false),
        /** Дом питается от солнца и/или АКБ. */
        AUTONOMOUS(PowerModernConfig.EMERALD, false),
        /** Дом питается от сети (в т. ч. байпас). */
        GRID(SKY, false),
        /** Предупреждение инвертора или перегруз. */
        WARNING(AMBER, false),
        /** Авария. */
        FAULT(PowerModernConfig.RED, true)
    }

    /**
     * Срез данных для отрисовки. Мощности в Вт; направления: [solar] ≥ 0 — к инвертору; [grid] > 0 — из сети
     * в инвертор; [battery] > 0 — заряд (в АКБ), < 0 — разряд; [load] ≥ 0 — к дому.
     */
    data class Snapshot(
        val solar: Double?,
        val grid: Double?,
        val battery: Double?,
        val load: Double?,
        val soc: Double?,
        val loadPercent: Double?,
        val mode: Mode,
        val modeText: String?,
        val fault: String?,
        val warning: String?,
        val look: Look,
        val status: String
    ) {
        val solarActive get() = active(solar)
        val gridActive get() = active(grid)
        val batteryActive get() = active(battery)
        val loadActive get() = active(load)
    }

    fun active(watts: Double?) = watts != null && abs(watts) > IDLE_W

    fun snapshot(config: InverterModernConfig, readings: BatteryReadings): Snapshot {
        fun reading(role: String) = readings.of(config.row(role))
        fun watts(role: String) = watts(config.row(role), reading(role))
        fun number(role: String) = (reading(role) as? BatteryReading.Value)?.number

        val solar = watts(InverterModernConfig.ROLE_PV_POWER) ?: sum(
            watts(InverterModernConfig.ROLE_PV1_POWER), watts(InverterModernConfig.ROLE_PV2_POWER))
        val batteryRaw = watts(InverterModernConfig.ROLE_BATTERY_POWER) ?: product(
            number(InverterModernConfig.ROLE_BATTERY_VOLTAGE), number(InverterModernConfig.ROLE_BATTERY_CURRENT))
        val battery = batteryRaw?.let { if (config.invertBatterySign) -it else it }
        val grid = watts(InverterModernConfig.ROLE_GRID_POWER)?.let { if (config.invertGridSign) -it else it }
        val load = watts(InverterModernConfig.ROLE_LOAD_POWER)
        val loadPercent = number(InverterModernConfig.ROLE_LOAD_PERCENT)
            ?: load?.let { if (config.ratedPowerW > 0) abs(it) * 100 / config.ratedPowerW else null }
        val modeReading = reading(InverterModernConfig.ROLE_MODE) as? BatteryReading.Value
        val mode = parseMode(modeReading?.raw)
        val fault = (reading(InverterModernConfig.ROLE_FAULT) as? BatteryReading.Value)?.raw?.takeIf(::isActiveFlag)
        val warning = (reading(InverterModernConfig.ROLE_WARNING) as? BatteryReading.Value)?.raw?.takeIf(::isActiveFlag)
        val soc = number(InverterModernConfig.ROLE_BATTERY_SOC)

        val overloaded = loadPercent != null && loadPercent >= config.overloadPercent
        val noData = readings.status != null ||
            listOf(solar, grid, battery, load).all { it == null } && modeReading == null
        val look = when {
            noData -> Look.NO_DATA
            fault != null || mode == Mode.FAULT -> Look.FAULT
            warning != null || overloaded -> Look.WARNING
            active(grid) && grid!! > 0 || mode == Mode.GRID || mode == Mode.BYPASS -> Look.GRID
            else -> Look.AUTONOMOUS
        }
        val status = when (look) {
            Look.NO_DATA -> readings.status ?: "Нет данных"
            Look.FAULT -> "Авария" + (fault?.let { describeFlag(it) }?.let { ": $it" } ?: "")
            Look.WARNING -> when {
                overloaded -> "Перегруз ${BatteryFormat.decimal(loadPercent!!, 0, java.util.Locale.ROOT)}%"
                else -> "Предупреждение" + (warning?.let { describeFlag(it) }?.let { ": $it" } ?: "")
            }
            else -> sources(solar, grid, battery, load, mode)
        }
        return Snapshot(solar, grid, battery, load, soc, loadPercent, mode, modeReading?.raw, fault, warning, look, status)
    }

    /** «От солнца и АКБ», «От сети», «Нагрузки нет»… — откуда сейчас питается дом. */
    fun sources(solar: Double?, grid: Double?, battery: Double?, load: Double?, mode: Mode): String {
        if (mode == Mode.BYPASS) return "Байпас: от сети"
        val names = buildList {
            if (active(solar) && solar!! > 0) add("солнца")
            if (active(battery) && battery!! < 0) add("АКБ")
            if (active(grid) && grid!! > 0) add("сети")
        }
        if (load != null && !active(load)) return "Нагрузки нет"
        return when (names.size) {
            0 -> if (mode == Mode.STANDBY) "Ожидание" else if (load == null) "Нет данных о нагрузке" else "Питание дома"
            1 -> "От ${names[0]}"
            2 -> "От ${names[0]} и ${names[1]}"
            else -> "От ${names[0]}, ${names[1]} и ${names[2]}"
        }
    }

    /**
     * Режим по тексту или коду. Коды SRNE (регистр 201): 0 — включение, 1 — ожидание, 2 — сеть,
     * 3 — автономный, 4 — байпас, 5 — заряд, 6 — авария. Неизвестное — [Mode.UNKNOWN] (цвет тогда по потокам).
     */
    fun parseMode(raw: String?): Mode {
        val text = raw?.trim()?.lowercase() ?: return Mode.UNKNOWN
        text.toDoubleOrNull()?.toInt()?.let { code ->
            return when (code) {
                1 -> Mode.STANDBY
                2 -> Mode.GRID
                3 -> Mode.OFF_GRID
                4 -> Mode.BYPASS
                5 -> Mode.CHARGING
                6 -> Mode.FAULT
                else -> Mode.UNKNOWN
            }
        }
        fun has(vararg words: String) = words.any { it in text }
        return when {
            has("offline", "нет связи") -> Mode.UNKNOWN
            has("fault", "error", "авари", "ошибк") -> Mode.FAULT
            has("bypass", "байпас") -> Mode.BYPASS
            has("off-grid", "off grid", "offgrid", "battery", "inverter mode", "solar", "автоном", "батаре", "акб") -> Mode.OFF_GRID
            has("line", "mains", "utility", "grid", "сеть", "сети") -> Mode.GRID
            has("charg", "заряд") -> Mode.CHARGING
            has("standby", "power on", "power-on", "ожидани") -> Mode.STANDBY
            else -> Mode.UNKNOWN
        }
    }

    fun modeLabel(mode: Mode): String = when (mode) {
        Mode.GRID -> "От сети"
        Mode.OFF_GRID -> "Автономный"
        Mode.BYPASS -> "Байпас"
        Mode.CHARGING -> "Заряд"
        Mode.STANDBY -> "Ожидание"
        Mode.FAULT -> "Авария"
        Mode.UNKNOWN -> "—"
    }

    /** Флаг аварии/предупреждения активен: не ноль, не «off/false/нет/ok/normal». */
    fun isActiveFlag(raw: String): Boolean {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return false
        text.toDoubleOrNull()?.let { return it != 0.0 }
        return text !in setOf("off", "false", "no", "none", "нет", "ok", "normal", "норма", "no fault", "no warning",
            "no error", "unknown", "unavailable")
    }

    /** Текст аварии для подписи: «on/true/1» сами по себе ничего не объясняют и не показываются. */
    private fun describeFlag(raw: String): String? = raw.trim().takeIf {
        it.isNotEmpty() && it.lowercase() !in setOf("on", "true", "yes", "да") && it.toDoubleOrNull() == null
    } ?: raw.trim().toDoubleOrNull()?.let { "код ${BatteryFormat.decimal(it, 0, java.util.Locale.ROOT)}" }

    /** Мощность строки в Вт: единица — явная в строке, иначе из HA; без единицы — Вт. */
    fun watts(row: BatteryModernConfig.Row?, reading: BatteryReading): Double? {
        val value = reading as? BatteryReading.Value ?: return null
        val number = value.number ?: return null
        val unit = row?.unit?.takeIf { it.isNotBlank() } ?: value.attributes?.optString("unit_of_measurement").orEmpty()
        return when (unit.trim().lowercase()) {
            "kw", "квт" -> number * 1_000
            "mw", "мвт" -> number * 1_000_000
            else -> number
        }
    }

    private fun sum(a: Double?, b: Double?) = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
    private fun product(a: Double?, b: Double?) = if (a == null || b == null) null else a * b

    /** «1,25 кВт» / «640 Вт» / «—»: короткая подпись для схемы, со знаком при [signed]. */
    fun power(watts: Double?, signed: Boolean = false): String {
        if (watts == null) return "—"
        val sign = if (signed && watts > IDLE_W) "+" else if (signed && watts < -IDLE_W) "−" else ""
        val value = abs(watts)
        return sign + if (value >= 1_000) BatteryFormat.decimal(value / 1_000, if (value >= 10_000) 1 else 2, RU) + " кВт"
            else BatteryFormat.decimal(value, 0, RU) + " Вт"
    }

    /** Понятные названия известных кодов приоритета SRNE/Voltronic; неизвестные — как есть. */
    fun priorityLabel(option: String): String = when (option.trim().uppercase()) {
        "SBU" -> "Солнце → АКБ → Сеть"
        "SUB" -> "Солнце → Сеть → АКБ"
        "UTI", "USB" -> "Сеть первой"
        "SOL" -> "Солнце первым"
        "SUF" -> "Солнце, излишки в сеть"
        else -> option
    }

    private val RU = java.util.Locale("ru")
    val SKY = 0xFF38BDF8.toInt()
    val AMBER = 0xFFFFB547.toInt()
}

/**
 * Подтверждённый цвет плитки «Инвертор» (без мерцания у границ, как у «Электропитание Modern»).
 * Чистая логика: время передаёт вызывающий. Хранится во ViewModel по id плитки и переживает пересоздание UI.
 *
 * - «Нет данных» — сразу (связь потеряна — это видно без задержки).
 * - Первое состояние после запуска или после «Нет данных» — сразу (нечему мерцать).
 * - Любая другая смена должна держаться [confirmMs], иначе остаётся прежний цвет.
 */
internal class InverterLookTracker(private val confirmMs: Long = CONFIRM_MS) {
    var confirmed: InverterModel.Look? = null
        private set
    private var candidate: InverterModel.Look? = null
    private var candidateSince = 0L

    /** Время следующей проверки подтверждения; null — ждать нечего. */
    val nextCheckAt: Long? get() = if (candidate != null) candidateSince + confirmMs else null

    fun update(look: InverterModel.Look, now: Long): InverterModel.Look {
        val current = confirmed
        if (look == InverterModel.Look.NO_DATA || current == null || current == InverterModel.Look.NO_DATA || look == current) {
            confirmed = look
            candidate = null
            return look
        }
        if (candidate != look) {
            candidate = look
            candidateSince = now
        }
        if (now - candidateSince >= confirmMs) {
            confirmed = look
            candidate = null
        }
        return confirmed ?: look
    }

    companion object {
        const val CONFIRM_MS = 1_800L
    }
}
