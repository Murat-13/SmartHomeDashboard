package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Настройки плитки «Инвертор» (`type = inverter_modern`, docs/inverter-modern.md). Хранятся в `tile.config`
 * под ключом [KEY]; размер и прочие ключи config не трогаются.
 *
 * Плитка не привязана к интеграции: каждая строка — сущность HA, выбранная владельцем в редакторе
 * (решение владельца 05.11.2026). Строки шаблона задают только роль и подпись.
 */
internal data class InverterModernConfig(
    val rows: List<BatteryModernConfig.Row> = templateRows(),
    /** Номинальная мощность инвертора, Вт: от неё считается загрузка, если датчика «% нагрузки» нет. */
    val ratedPowerW: Double = DEFAULT_RATED_W,
    /** Загрузка, %, начиная с которой плитка янтарная («перегруз»). */
    val overloadPercent: Double = DEFAULT_OVERLOAD_PERCENT,
    /** false: плюс мощности АКБ — заряд (вход в АКБ), минус — разряд. true — обратное соглашение источника. */
    val invertBatterySign: Boolean = false,
    /** false: плюс мощности сети — потребление из сети. true — обратное соглашение источника. */
    val invertGridSign: Boolean = false
) {
    fun row(role: String): BatteryModernConfig.Row? = rows.firstOrNull { it.role == role }

    fun withRow(row: BatteryModernConfig.Row) = copy(rows = rows.map { if (it.id == row.id) row else it })

    /** Сущности HA, от которых зависит плитка (подписки и адресная перерисовка). */
    val entityIds: Set<String> get() = rows.mapNotNullTo(LinkedHashSet()) { it.entityId.trim().takeIf(String::isNotEmpty) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("rated_power_w", ratedPowerW)
        put("overload_percent", overloadPercent)
        put("invert_battery_sign", invertBatterySign)
        put("invert_grid_sign", invertGridSign)
        put("rows", JSONArray().also { array -> rows.forEach { array.put(rowJson(it)) } })
    }

    /** Блок [KEY] записывается в config плитки; остальные ключи остаются как были. */
    fun writeTo(config: String): String {
        val json = try { JSONObject(config) } catch (_: Exception) { JSONObject() }
        return json.put(KEY, toJson()).toString()
    }

    /** Группа строк раскрытой карточки: узел схемы, к которому относится показатель. */
    enum class Node(val title: String) { SOLAR("Солнце"), BATTERY("АКБ"), GRID("Сеть"), LOAD("Нагрузка"), INVERTER("Инвертор") }

    companion object {
        const val TYPE = "inverter_modern"
        const val KEY = "inverter_modern"
        const val VERSION = 1
        const val DEFAULT_TITLE = "Инвертор"
        /** Anenji ANJ-HHS-11KW-48V: 11 000 Вт. */
        const val DEFAULT_RATED_W = 11_000.0
        const val DEFAULT_OVERLOAD_PERCENT = 80.0
        const val DEFAULT_SIZE = 340

        const val ROLE_PV1_POWER = "pv1_power"
        const val ROLE_PV1_VOLTAGE = "pv1_voltage"
        const val ROLE_PV2_POWER = "pv2_power"
        const val ROLE_PV2_VOLTAGE = "pv2_voltage"
        /** Суммарная мощность PV; если не задана — сумма PV1 и PV2. */
        const val ROLE_PV_POWER = "pv_power"
        const val ROLE_BATTERY_POWER = "battery_power"
        const val ROLE_BATTERY_VOLTAGE = "battery_voltage"
        const val ROLE_BATTERY_CURRENT = "battery_current"
        const val ROLE_BATTERY_SOC = "battery_soc"
        const val ROLE_GRID_POWER = "grid_power"
        const val ROLE_GRID_VOLTAGE = "grid_voltage"
        const val ROLE_GRID_FREQUENCY = "grid_frequency"
        const val ROLE_LOAD_POWER = "load_power"
        const val ROLE_LOAD_VOLTAGE = "load_voltage"
        const val ROLE_LOAD_CURRENT = "load_current"
        const val ROLE_LOAD_APPARENT = "load_apparent"
        const val ROLE_LOAD_PERCENT = "load_percent"
        /** Режим работы инвертора (текст или код SRNE). */
        const val ROLE_MODE = "mode"
        /** Авария: любой «активный» ответ (не 0, не off, не «нет») — красный. */
        const val ROLE_FAULT = "fault"
        /** Предупреждение: как авария, но янтарный. */
        const val ROLE_WARNING = "warning"
        /** Приоритет источника — сущность `select`; меняется из раскрытой карточки под PIN. */
        const val ROLE_PRIORITY = "priority"

        private val NODES = mapOf(
            ROLE_PV_POWER to Node.SOLAR, ROLE_PV1_POWER to Node.SOLAR, ROLE_PV1_VOLTAGE to Node.SOLAR,
            ROLE_PV2_POWER to Node.SOLAR, ROLE_PV2_VOLTAGE to Node.SOLAR,
            ROLE_BATTERY_POWER to Node.BATTERY, ROLE_BATTERY_SOC to Node.BATTERY, ROLE_BATTERY_VOLTAGE to Node.BATTERY,
            ROLE_BATTERY_CURRENT to Node.BATTERY,
            ROLE_GRID_POWER to Node.GRID, ROLE_GRID_VOLTAGE to Node.GRID, ROLE_GRID_FREQUENCY to Node.GRID,
            ROLE_LOAD_POWER to Node.LOAD, ROLE_LOAD_PERCENT to Node.LOAD, ROLE_LOAD_VOLTAGE to Node.LOAD,
            ROLE_LOAD_CURRENT to Node.LOAD, ROLE_LOAD_APPARENT to Node.LOAD,
            ROLE_MODE to Node.INVERTER, ROLE_PRIORITY to Node.INVERTER, ROLE_FAULT to Node.INVERTER, ROLE_WARNING to Node.INVERTER
        )

        fun node(row: BatteryModernConfig.Row): Node = NODES[row.role] ?: Node.INVERTER

        fun templateRows(): List<BatteryModernConfig.Row> {
            fun row(role: String, label: String, icon: String) =
                BatteryModernConfig.Row(role, role, BatteryModernConfig.Section.SENSORS, label, icon)
            return listOf(
                row(ROLE_PV_POWER, "Солнце, всего", "power"),
                row(ROLE_PV1_POWER, "PV1 — мощность", "power"),
                row(ROLE_PV1_VOLTAGE, "PV1 — напряжение", "voltage"),
                row(ROLE_PV2_POWER, "PV2 — мощность", "power"),
                row(ROLE_PV2_VOLTAGE, "PV2 — напряжение", "voltage"),
                row(ROLE_BATTERY_POWER, "АКБ — мощность", "power"),
                row(ROLE_BATTERY_SOC, "АКБ — заряд", "battery"),
                row(ROLE_BATTERY_VOLTAGE, "АКБ — напряжение", "voltage"),
                row(ROLE_BATTERY_CURRENT, "АКБ — ток", "current"),
                row(ROLE_GRID_POWER, "Сеть — мощность", "power"),
                row(ROLE_GRID_VOLTAGE, "Сеть — напряжение", "voltage"),
                row(ROLE_GRID_FREQUENCY, "Сеть — частота", "custom"),
                row(ROLE_LOAD_POWER, "Нагрузка — мощность", "power"),
                row(ROLE_LOAD_PERCENT, "Нагрузка — загрузка", "custom"),
                row(ROLE_LOAD_VOLTAGE, "Выход — напряжение", "voltage"),
                row(ROLE_LOAD_CURRENT, "Выход — ток", "current"),
                row(ROLE_LOAD_APPARENT, "Выход — полная мощность", "power"),
                row(ROLE_MODE, "Режим работы", "custom"),
                row(ROLE_PRIORITY, "Приоритет источника", "custom"),
                row(ROLE_FAULT, "Авария", "problem"),
                row(ROLE_WARNING, "Предупреждение", "problem")
            )
        }

        /** Нет блока или он повреждён — шаблон без источников; запись только при сохранении. */
        fun from(config: String): InverterModernConfig {
            val json = try { JSONObject(config).optJSONObject(KEY) } catch (_: Exception) { null }
                ?: return InverterModernConfig()
            return fromJson(json)
        }

        fun fromJson(json: JSONObject): InverterModernConfig {
            val saved = json.optJSONArray("rows")?.let { array ->
                (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::parseRow) }.associateBy { it.role }
            }.orEmpty()
            // Порядок и состав — по шаблону: новые роли будущих версий появляются сами, неизвестные не ломают плитку.
            val rows = templateRows().map { template ->
                saved[template.role]?.copy(id = template.id, role = template.role) ?: template
            }
            fun positive(key: String, default: Double) = json.optDouble(key, default).takeIf { it.isFinite() && it > 0 } ?: default
            return InverterModernConfig(
                rows = rows,
                ratedPowerW = positive("rated_power_w", DEFAULT_RATED_W),
                overloadPercent = positive("overload_percent", DEFAULT_OVERLOAD_PERCENT).coerceAtMost(200.0),
                invertBatterySign = json.optBoolean("invert_battery_sign", false),
                invertGridSign = json.optBoolean("invert_grid_sign", false)
            )
        }

        private fun rowJson(row: BatteryModernConfig.Row) = JSONObject().apply {
            put("role", row.role)
            put("label", row.label)
            put("entity_id", row.entityId)
            row.decimals?.let { put("decimals", it) }
            put("unit", row.unit)
        }

        private fun parseRow(obj: JSONObject): BatteryModernConfig.Row? {
            val role = obj.optString("role").takeIf { it.isNotBlank() } ?: return null
            val template = templateRows().firstOrNull { it.role == role } ?: return null
            return template.copy(
                label = obj.optString("label").ifBlank { template.label },
                entityId = obj.optString("entity_id").trim(),
                decimals = if (obj.has("decimals") && !obj.isNull("decimals")) obj.optInt("decimals").coerceIn(0, 4) else null,
                unit = obj.optString("unit")
            )
        }

        /** Config новой плитки: квадрат и пустой шаблон; посторонние ключи и уже заданный размер сохраняются. */
        fun newTileConfig(existing: String = "{}"): String {
            val json = try { JSONObject(existing) } catch (_: Exception) { JSONObject() }
            if (!json.has("button_size")) json.put("button_size", DEFAULT_SIZE)
            if (!json.has("widget_height")) json.put("widget_height", DEFAULT_SIZE)
            if (!json.has(KEY)) json.put(KEY, InverterModernConfig().toJson())
            return json.toString()
        }

        /** Сущности всех плиток «Инвертор» — для плана подписок панели. */
        fun entityIds(tiles: List<TileEntity>): Set<String> =
            tiles.filter { it.type == TYPE }.flatMapTo(LinkedHashSet()) { from(it.config).entityIds }
    }
}
