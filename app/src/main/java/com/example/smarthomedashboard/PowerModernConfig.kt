package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Настройки универсальной плитки «Электропитание Modern» (`type = power_modern`), ключ [KEY]
 * в `tile.config`. Каждая плитка хранит собственные источники, правила и цвета: «Сеть · Вход»
 * и «Инвертор · Выход» — два профиля одной реализации, не зависящие друг от друга.
 *
 * Строки — та же модель, что у АКБ Modern ([BatteryModernConfig.Row]): основные показатели
 * (напряжение, мощность, частота) — по ролям, дополнительные — раздел DIAGNOSTICS.
 */
internal data class PowerModernConfig(
    val profile: String = PROFILE_GRID,
    val icon: String = ICON_PYLON,
    val rows: List<BatteryModernConfig.Row> = templateRows(),
    val rules: List<Rule> = defaultRules(PROFILE_GRID),
    /** Сколько новый диапазон должен держаться, прежде чем сменится окраска. */
    val confirmMs: Long = DEFAULT_CONFIRM_MS,
    /** Длительность плавной смены цвета. */
    val transitionMs: Long = DEFAULT_TRANSITION_MS,
    /** Насколько напряжение должно выйти за границу текущего диапазона, чтобы начать смену. */
    val hysteresis: Double = DEFAULT_HYSTERESIS
) {
    /**
     * Визуальное правило напряжения. Границы с явным включением: так 150 В относится
     * к «очень низкому» (с пульсацией), а 200 В — к «норме». [to] = null — без верхней границы.
     */
    data class Rule(
        val from: Double,
        val fromInclusive: Boolean,
        val to: Double?,
        val toInclusive: Boolean,
        val color: Int,
        val label: String,
        val pulse: Boolean
    ) {
        fun contains(v: Double): Boolean {
            val aboveFrom = if (fromInclusive) v >= from else v > from
            val belowTo = to == null || (if (toInclusive) v <= to else v < to)
            return aboveFrom && belowTo
        }

        /** Расстояние от значения до диапазона (0 — внутри или на границе). */
        fun distance(v: Double): Double = when {
            v < from -> from - v
            to != null && v > to -> v - to
            else -> 0.0
        }

        val isPoint get() = to != null && to == from

        fun rangeText(): String {
            val a = BatteryFormat.decimal(from, if (from % 1.0 == 0.0) 0 else 1)
            if (isPoint) return "$a В"
            val left = if (fromInclusive) "от $a" else "больше $a"
            val b = to?.let { BatteryFormat.decimal(it, if (it % 1.0 == 0.0) 0 else 1) }
            val right = when {
                b == null -> ""
                toInclusive -> " до $b включительно"
                else -> " до $b"
            }
            return "$left$right В"
        }
    }

    fun row(role: String) = rows.firstOrNull { it.role == role }
    val extras get() = rows.filter { it.section == BatteryModernConfig.Section.DIAGNOSTICS }

    /** Первое совпавшее правило; при корректной таблице совпадение единственное. */
    fun ruleFor(voltage: Double): Int = rules.indexOfFirst { it.contains(voltage) }

    fun withRow(row: BatteryModernConfig.Row) = copy(rows = if (rows.any { it.id == row.id })
        rows.map { if (it.id == row.id) row else it } else rows + row)

    fun withoutRow(id: String) = copy(rows = rows.filterNot { it.id == id })

    /** Перестановка среди дополнительных строк. */
    fun moveExtra(id: String, delta: Int): PowerModernConfig {
        val list = extras
        val index = list.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in list.indices) return this
        val a = list[index]
        val b = list[target]
        return copy(rows = rows.map { when (it.id) { a.id -> b; b.id -> a; else -> it } })
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("profile", profile)
        put("icon", icon)
        put("confirm_ms", confirmMs)
        put("transition_ms", transitionMs)
        put("hysteresis", hysteresis)
        put("rows", JSONArray().also { array -> rows.forEach { array.put(rowJson(it)) } })
        put("rules", JSONArray().also { array ->
            rules.forEach { rule ->
                array.put(JSONObject().apply {
                    put("from", rule.from)
                    put("from_inclusive", rule.fromInclusive)
                    rule.to?.let { put("to", it) }
                    put("to_inclusive", rule.toInclusive)
                    put("color", String.format("#%08X", rule.color))
                    put("label", rule.label)
                    put("pulse", rule.pulse)
                })
            }
        })
    }

    /** Пишет себя в существующий config, сохраняя посторонние ключи (размер, координаты). */
    fun writeTo(config: String): String {
        val json = try { JSONObject(config) } catch (_: Exception) { JSONObject() }
        return json.put(KEY, toJson()).toString()
    }

    /** Проблемы таблицы правил: пересечения блокируют сохранение, пропуски — предупреждение. */
    data class Validation(val errors: List<String>, val gaps: List<String>)

    fun validate(): Validation {
        val errors = mutableListOf<String>()
        rules.forEachIndexed { index, rule ->
            val n = index + 1
            if (!rule.from.isFinite() || (rule.to != null && !rule.to.isFinite())) errors += "Правило $n: граница не число"
            else if (rule.to != null && (rule.to < rule.from || (rule.to == rule.from && !(rule.fromInclusive && rule.toInclusive))))
                errors += "Правило $n: нижняя граница больше верхней или диапазон пуст"
            if (rule.label.isBlank()) errors += "Правило $n: пустая подпись"
        }
        if (errors.isNotEmpty()) return Validation(errors, emptyList())
        val sorted = rules.withIndex().sortedWith(compareBy<IndexedValue<Rule>>({ it.value.from }, { if (it.value.fromInclusive) 0 else 1 }))
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            if (overlap(sorted[i].value, sorted[j].value)) {
                errors += "Правила ${sorted[i].index + 1} и ${sorted[j].index + 1} пересекаются"
            }
        }
        val gaps = mutableListOf<String>()
        if (errors.isEmpty()) {
            val list = sorted.map { it.value }
            if (list.isEmpty()) gaps += "Нет ни одного правила: при любых данных плитка будет нейтральной"
            else {
                val first = list.first()
                if (first.from > 0 || (first.from == 0.0 && !first.fromInclusive)) gaps += "Не покрыто: от 0 до ${fmt(first.from)} В"
                for (k in 0 until list.size - 1) {
                    val a = list[k]
                    val b = list[k + 1]
                    val end = a.to ?: continue
                    if (end < b.from) gaps += "Не покрыто: от ${fmt(end)} до ${fmt(b.from)} В"
                    else if (end == b.from && !a.toInclusive && !b.fromInclusive) gaps += "Не покрыто: ровно ${fmt(end)} В"
                }
                list.last().to?.let { gaps += "Не покрыто: выше ${fmt(it)} В" }
            }
        }
        return Validation(errors, gaps)
    }

    private fun overlap(a: Rule, b: Rule): Boolean {
        // b начинается не раньше a (отсортировано). Пересечение — если начало b внутри a.
        val aEnd = a.to ?: return true
        return when {
            b.from < aEnd -> true
            b.from == aEnd -> a.toInclusive && b.fromInclusive
            else -> false
        }
    }

    private fun fmt(v: Double) = BatteryFormat.decimal(v, if (v % 1.0 == 0.0) 0 else 1)

    companion object {
        const val TYPE = "power_modern"
        const val KEY = "power_modern"
        const val VERSION = 1
        const val PROFILE_GRID = "grid_in"
        const val PROFILE_INVERTER = "inverter_out"
        const val ICON_PYLON = "pylon"
        const val ICON_INVERTER = "inverter"
        const val DEFAULT_CONFIRM_MS = 1_800L
        const val DEFAULT_TRANSITION_MS = 500L
        const val DEFAULT_HYSTERESIS = 2.0
        const val DEFAULT_WIDTH = 300
        const val DEFAULT_HEIGHT = 360

        const val ROLE_VOLTAGE = "voltage"
        const val ROLE_POWER = "power"
        const val ROLE_FREQUENCY = "frequency"
        /** Строка «Статус» показывает вычисленный статус, если источник не выбран. */
        const val ROLE_STATUS = "status"

        val RED = 0xFFFF4D5E.toInt()
        val ORANGE = 0xFFFF9A3C.toInt()
        val YELLOW = 0xFFF5D547.toInt()
        val EMERALD = 0xFF2EE6A0.toInt()
        val CYAN = 0xFF22D3EE.toInt()
        val NEUTRAL = 0xFF6B7A86.toInt()

        fun title(profile: String) = if (profile == PROFILE_INVERTER) "Инвертор · Выход" else "Сеть · Вход"

        fun profile(profile: String) = PowerModernConfig(
            profile = profile,
            icon = if (profile == PROFILE_INVERTER) ICON_INVERTER else ICON_PYLON,
            rules = defaultRules(profile)
        )

        fun templateRows(): List<BatteryModernConfig.Row> {
            fun main(role: String, label: String, icon: String) =
                BatteryModernConfig.Row(role, role, BatteryModernConfig.Section.SENSORS, label, icon)
            fun extra(role: String, label: String, icon: String) =
                BatteryModernConfig.Row(role, role, BatteryModernConfig.Section.DIAGNOSTICS, label, icon)
            return listOf(
                main(ROLE_VOLTAGE, "Напряжение", "voltage"),
                main(ROLE_POWER, "Мощность", "power"),
                main(ROLE_FREQUENCY, "Частота", "custom"),
                extra("current", "Ток", "current"),
                extra("apparent_power", "Полная мощность", "power"),
                extra("reactive_power", "Реактивная мощность", "power"),
                extra("power_factor", "Коэффициент мощности", "custom"),
                extra("energy", "Потреблённая энергия", "energy"),
                extra(ROLE_STATUS, "Статус", "problem")
            )
        }

        /**
         * Стандартная таблица (визуальные предупреждения, не нормативная оценка безопасности):
         * 0 | (0;150] пульс. | (150;170) | [170;190) | [190;200) | [200;240) норма | [240;250) |
         * [250;260) | [260;280) | [280;∞) пульс.
         */
        fun defaultRules(profile: String): List<Rule> {
            val normal = if (profile == PROFILE_INVERTER) CYAN else EMERALD
            val zero = if (profile == PROFILE_INVERTER) "Нет напряжения на выходе" else "Нет сети"
            return listOf(
                Rule(0.0, true, 0.0, true, RED, zero, true),
                Rule(0.0, false, 150.0, true, RED, "Очень низкое напряжение", true),
                Rule(150.0, false, 170.0, false, RED, "Низкое напряжение", false),
                Rule(170.0, true, 190.0, false, ORANGE, "Пониженное напряжение", false),
                Rule(190.0, true, 200.0, false, YELLOW, "Нижняя граница", false),
                Rule(200.0, true, 240.0, false, normal, "Норма", false),
                Rule(240.0, true, 250.0, false, YELLOW, "Повышенное напряжение", false),
                Rule(250.0, true, 260.0, false, ORANGE, "Высокое напряжение", false),
                Rule(260.0, true, 280.0, false, RED, "Опасно высокое напряжение", false),
                Rule(280.0, true, null, false, RED, "Опасно высокое напряжение", true)
            )
        }

        /** Нет блока или он повреждён — профиль «Сеть · Вход»; запись только при сохранении. */
        fun from(config: String): PowerModernConfig {
            val json = try { JSONObject(config).optJSONObject(KEY) } catch (_: Exception) { null }
                ?: return PowerModernConfig()
            return fromJson(json)
        }

        fun fromJson(json: JSONObject): PowerModernConfig {
            val profile = json.optString("profile").ifBlank { PROFILE_GRID }
            val rowsArray = json.optJSONArray("rows")
            val rows = if (rowsArray == null) templateRows() else (0 until rowsArray.length()).mapNotNull { index ->
                rowsArray.optJSONObject(index)?.let { obj -> parseRow(obj, index) }
            }.distinctBy { it.id }
            val rulesArray = json.optJSONArray("rules")
            val rules = if (rulesArray == null) defaultRules(profile) else (0 until rulesArray.length()).mapNotNull { index ->
                val obj = rulesArray.optJSONObject(index) ?: return@mapNotNull null
                Rule(
                    from = obj.optDouble("from", 0.0),
                    fromInclusive = obj.optBoolean("from_inclusive", true),
                    to = if (obj.has("to") && !obj.isNull("to")) obj.optDouble("to") else null,
                    toInclusive = obj.optBoolean("to_inclusive", false),
                    color = parseColor(obj.optString("color")) ?: NEUTRAL,
                    label = obj.optString("label"),
                    pulse = obj.optBoolean("pulse", false)
                )
            }
            val hysteresis = json.optDouble("hysteresis", DEFAULT_HYSTERESIS)
            return PowerModernConfig(
                profile = profile,
                icon = json.optString("icon").ifBlank { if (profile == PROFILE_INVERTER) ICON_INVERTER else ICON_PYLON },
                rows = rows,
                rules = rules,
                confirmMs = json.optLong("confirm_ms", DEFAULT_CONFIRM_MS).coerceIn(0L, 60_000L),
                transitionMs = json.optLong("transition_ms", DEFAULT_TRANSITION_MS).coerceIn(0L, 5_000L),
                hysteresis = if (hysteresis.isFinite() && hysteresis >= 0) hysteresis else DEFAULT_HYSTERESIS
            )
        }

        fun parseColor(text: String?): Int? = try {
            if (text.isNullOrBlank()) null else android.graphics.Color.parseColor(text.trim())
        } catch (_: IllegalArgumentException) { null }

        private fun rowJson(row: BatteryModernConfig.Row) = JSONObject().apply {
            put("id", row.id)
            put("role", row.role)
            put("section", row.section.key)
            put("label", row.label)
            put("icon", row.icon)
            put("entity_id", row.entityId)
            row.decimals?.let { put("decimals", it) }
            put("unit", row.unit)
            put("hidden", row.hidden)
        }

        private fun parseRow(obj: JSONObject, index: Int) = BatteryModernConfig.Row(
            id = obj.optString("id").ifBlank { "row_$index" },
            role = obj.optString("role").ifBlank { BatteryModernConfig.ROLE_CUSTOM },
            section = BatteryModernConfig.Section.of(obj.optString("section")),
            label = obj.optString("label"),
            icon = obj.optString("icon").ifBlank { "custom" },
            entityId = obj.optString("entity_id").trim(),
            decimals = if (obj.has("decimals") && !obj.isNull("decimals")) obj.optInt("decimals").coerceIn(0, 4) else null,
            unit = obj.optString("unit"),
            hidden = obj.optBoolean("hidden", false)
        )

        /** Config новой плитки выбранного профиля; посторонние ключи и уже заданный размер сохраняются. */
        fun newTileConfig(profile: String, existing: String = "{}"): String {
            val json = try { JSONObject(existing) } catch (_: Exception) { JSONObject() }
            if (!json.has("button_size")) json.put("button_size", DEFAULT_WIDTH)
            if (!json.has("widget_height")) json.put("widget_height", DEFAULT_HEIGHT)
            if (!json.has(KEY)) json.put(KEY, profile(profile).toJson())
            return json.toString()
        }
    }
}
