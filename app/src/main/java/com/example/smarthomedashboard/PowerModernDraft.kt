package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject

/**
 * Несохранённый черновик «Электропитание Modern». Не пишет prefs: работающая плитка меняется
 * только через [applyTo] и явное «Сохранить» экрана. Отмена отбрасывает черновик целиком.
 */
internal class PowerModernDraft(val original: TileEntity, initial: PowerModernConfig? = null, title: String? = null) {
    private val originalConfig = PowerModernConfig.from(original.config)
    var config: PowerModernConfig = initial ?: originalConfig
        private set
    var title: String = title ?: original.title
        private set

    val isDirty get() = config != originalConfig || title != original.title

    fun row(id: String) = config.rows.firstOrNull { it.id == id }

    /** Основная строка по роли; удалённую предлагаем из шаблона, в черновик — только через [update]. */
    fun rowForRole(role: String): BatteryModernConfig.Row =
        config.row(role) ?: PowerModernConfig.templateRows().first { it.role == role }

    fun update(row: BatteryModernConfig.Row) { config = config.withRow(row) }
    fun delete(id: String) { config = config.withoutRow(id) }
    fun move(id: String, delta: Int) { config = config.moveExtra(id, delta) }

    fun newRow(): BatteryModernConfig.Row {
        val used = config.rows.mapTo(HashSet()) { it.id }
        var index = 1
        while ("custom_$index" in used) index++
        return BatteryModernConfig.Row("custom_$index", BatteryModernConfig.ROLE_CUSTOM,
            BatteryModernConfig.Section.DIAGNOSTICS, "Новый показатель", "custom")
    }

    fun setTitle(value: String) { title = value }

    // ---- Правила ----

    fun updateRule(index: Int, rule: PowerModernConfig.Rule) {
        config = config.copy(rules = config.rules.mapIndexed { i, r -> if (i == index) rule else r })
    }

    fun addRule(rule: PowerModernConfig.Rule) { config = config.copy(rules = config.rules + rule) }

    fun deleteRule(index: Int) { config = config.copy(rules = config.rules.filterIndexed { i, _ -> i != index }) }

    fun moveRule(index: Int, delta: Int) {
        val target = index + delta
        if (index !in config.rules.indices || target !in config.rules.indices) return
        val list = config.rules.toMutableList()
        list[index] = config.rules[target]
        list[target] = config.rules[index]
        config = config.copy(rules = list)
    }

    /** Стандартный шаблон правил этого профиля; источники и строки не меняются. */
    fun restoreDefaultRules() { config = config.copy(rules = PowerModernConfig.defaultRules(config.profile)) }

    fun validationError(): String? {
        if (title.isBlank()) return "Введите название плитки"
        if (config.rows.any { it.label.isBlank() }) return "У каждой строки должна быть подпись"
        return config.validate().errors.firstOrNull()
    }

    fun applyTo(tile: TileEntity): TileEntity = tile.copy(title = title.trim(), config = config.writeTo(tile.config))

    fun saveState(): String = JSONObject()
        .put("original", JSONObject().put("id", original.id).put("config", original.config).put("title", original.title))
        .put("title", title)
        .put("config", config.toJson())
        .toString()

    companion object {
        /** Восстанавливает черновик той же неизменённой плитки; иначе null. */
        fun restore(state: String, original: TileEntity): PowerModernDraft? {
            val json = JSONObject(state)
            val saved = json.getJSONObject("original")
            if (saved.optString("id") != original.id || saved.optString("config") != original.config ||
                saved.optString("title") != original.title) return null
            return PowerModernDraft(original, PowerModernConfig.fromJson(json.getJSONObject("config")), json.optString("title"))
        }
    }
}
