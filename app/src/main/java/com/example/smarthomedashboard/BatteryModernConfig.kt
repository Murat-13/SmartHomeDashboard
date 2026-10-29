package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Настройки плитки «АКБ Modern» (`type = battery_modern`). Хранятся в `tile.config`
 * под ключом [KEY]; остальные ключи config (размер, координаты) не трогаются.
 *
 * Строки шаблона — только подписи и роли. Источник (`entity_id`) выбирает владелец:
 * название строки не считается доказательством того, какой датчик к ней относится.
 */
internal data class BatteryModernConfig(
    val subtitle: String = "",
    /** false: «минус — разряд, плюс — заряд» (BMS владельца). true — обратное соглашение. */
    val invertPowerSign: Boolean = false,
    /** Модуль мощности не больше порога — «Ожидание». В единицах датчика мощности. */
    val idleThreshold: Double = DEFAULT_IDLE_THRESHOLD,
    /** Строки без выбранного источника: показывать «—» (false) или скрывать (true). */
    val hideUnconfigured: Boolean = false,
    val rows: List<Row> = template()
) {
    enum class Section(val key: String, val title: String) {
        SENSORS("sensors", "Сенсоры"),
        DIAGNOSTICS("diagnostics", "Диагностика");

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: SENSORS
        }
    }

    data class Row(
        val id: String,
        /** Роль шаблона. Компактные зоны находят свою строку по роли, а не по entity_id. */
        val role: String,
        val section: Section,
        val label: String,
        val icon: String,
        val entityId: String = "",
        /** null — точность как в данных HA (не больше двух знаков). */
        val decimals: Int? = null,
        /** Пусто — единица из атрибута `unit_of_measurement` HA. */
        val unit: String = "",
        val hidden: Boolean = false
    ) {
        val configured get() = entityId.isNotBlank()
    }

    fun row(role: String): Row? = rows.firstOrNull { it.role == role }

    fun visibleRows(section: Section): List<Row> = rows.filter {
        it.section == section && !it.hidden && (it.configured || !hideUnconfigured)
    }

    fun withRow(row: Row): BatteryModernConfig =
        copy(rows = rows.map { if (it.id == row.id) row else it })

    fun withoutRow(id: String): BatteryModernConfig = copy(rows = rows.filterNot { it.id == id })

    /** Перестановка внутри раздела; [delta] = -1 вверх, +1 вниз. Края не меняют порядок. */
    fun move(id: String, delta: Int): BatteryModernConfig {
        val row = rows.firstOrNull { it.id == id } ?: return this
        val sameSection = rows.filter { it.section == row.section }
        val target = sameSection.indexOf(row) + delta
        if (target !in sameSection.indices) return this
        val other = sameSection[target]
        return copy(rows = rows.map {
            when (it.id) {
                row.id -> other
                other.id -> row
                else -> it
            }
        })
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("subtitle", subtitle)
        put("invert_power_sign", invertPowerSign)
        put("idle_threshold", idleThreshold)
        put("hide_unconfigured", hideUnconfigured)
        put("rows", JSONArray().also { array ->
            rows.forEach { row ->
                array.put(JSONObject().apply {
                    put("id", row.id)
                    put("role", row.role)
                    put("section", row.section.key)
                    put("label", row.label)
                    put("icon", row.icon)
                    put("entity_id", row.entityId)
                    row.decimals?.let { put("decimals", it) }
                    put("unit", row.unit)
                    put("hidden", row.hidden)
                })
            }
        })
    }

    /** Записывает себя в существующий config, сохраняя все посторонние ключи. */
    fun writeTo(config: String): String {
        val json = try { JSONObject(config) } catch (_: Exception) { JSONObject() }
        return json.put(KEY, toJson()).toString()
    }

    companion object {
        const val TYPE = "battery_modern"
        const val KEY = "modern_battery"
        const val VERSION = 1
        const val DEFAULT_IDLE_THRESHOLD = 20.0
        const val ROLE_CUSTOM = "custom"
        const val DEFAULT_WIDTH = 520
        const val DEFAULT_HEIGHT = 380

        // Роли компактного вида: на них ссылаются зоны карточки.
        const val ROLE_SOC = "soc"
        const val ROLE_POWER = "power"
        const val ROLE_RUNTIME = "runtime"
        const val ROLE_VOLTAGE = "voltage"
        const val ROLE_TEMPERATURE = "temperature"

        /** Готовый шаблон по образцу карточки BMS в Home Assistant. Все источники пусты. */
        fun template(): List<Row> {
            fun s(role: String, label: String, icon: String) = Row(role, role, Section.SENSORS, label, icon)
            fun d(role: String, label: String, icon: String) = Row(role, role, Section.DIAGNOSTICS, label, icon)
            return listOf(
                s(ROLE_SOC, "Батарея", "battery"),
                s("charging", "Зарядка", "charging"),
                s(ROLE_POWER, "Мощность", "power"),
                s("power_inverted", "Мощность Inverted", "power"),
                s("energy", "Накопленная энергия", "energy"),
                s(ROLE_VOLTAGE, "Напряжение", "voltage"),
                s(ROLE_TEMPERATURE, "Температура", "temperature"),
                s("current", "Ток", "current"),
                s("health", "Battery health", "health"),
                s("cycles", "Cycles", "cycles"),
                s(ROLE_RUNTIME, "Runtime", "runtime"),
                d("problem", "Проблема", "problem"),
                d("signal", "Уровень сигнала", "signal"),
                d("balancer", "Balancer", "balancer"),
                d("charge_mosfet", "Charge MOSFET", "mosfet"),
                d("delta_cell_voltage", "Delta cell voltage", "cell"),
                d("design_capacity", "Design capacity", "capacity"),
                d("discharge_mosfet", "Discharge MOSFET", "mosfet"),
                d("highest_cell_voltage", "Highest cell voltage", "cell_high"),
                d("lowest_cell_voltage", "Lowest cell voltage", "cell_low")
            )
        }

        /** Нет блока или он повреждён — готовый шаблон; ничего не пишется до сохранения. */
        fun from(config: String): BatteryModernConfig {
            val json = try { JSONObject(config).optJSONObject(KEY) } catch (_: Exception) { null }
                ?: return BatteryModernConfig()
            return fromJson(json)
        }

        fun fromJson(json: JSONObject): BatteryModernConfig {
            val array = json.optJSONArray("rows")
            val rows = if (array == null) template() else (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id").ifBlank { "row_$index" }
                Row(
                    id = id,
                    role = obj.optString("role").ifBlank { ROLE_CUSTOM },
                    section = Section.of(obj.optString("section")),
                    label = obj.optString("label"),
                    icon = obj.optString("icon").ifBlank { "custom" },
                    entityId = obj.optString("entity_id").trim(),
                    decimals = if (obj.has("decimals") && !obj.isNull("decimals")) obj.optInt("decimals").coerceIn(0, 4) else null,
                    unit = obj.optString("unit"),
                    hidden = obj.optBoolean("hidden", false)
                )
            }.distinctBy { it.id }
            val threshold = json.optDouble("idle_threshold", DEFAULT_IDLE_THRESHOLD)
            return BatteryModernConfig(
                subtitle = json.optString("subtitle"),
                invertPowerSign = json.optBoolean("invert_power_sign", false),
                idleThreshold = if (threshold.isFinite() && threshold >= 0) threshold else DEFAULT_IDLE_THRESHOLD,
                hideUnconfigured = json.optBoolean("hide_unconfigured", false),
                rows = rows
            )
        }

        /** Config новой плитки: шаблон и размер, при котором помещается компактный вид. */
        fun newTileConfig(existing: String = "{}"): String {
            val json = try { JSONObject(existing) } catch (_: Exception) { JSONObject() }
            if (!json.has("button_size")) json.put("button_size", DEFAULT_WIDTH)
            if (!json.has("widget_height")) json.put("widget_height", DEFAULT_HEIGHT)
            if (!json.has(KEY)) json.put(KEY, BatteryModernConfig().toJson())
            return json.toString()
        }
    }
}
