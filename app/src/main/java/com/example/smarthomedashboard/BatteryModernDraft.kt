package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject

/**
 * Несохранённый черновик «АКБ Modern». Не пишет prefs: действующая конфигурация меняется
 * только [applyTo] + явным сохранением экрана. Отмена просто отбрасывает черновик.
 */
internal class BatteryModernDraft(val original: TileEntity, initial: BatteryModernConfig? = null, title: String? = null) {
    private val originalConfig = BatteryModernConfig.from(original.config)
    var config: BatteryModernConfig = initial ?: originalConfig
        private set
    var title: String = title ?: original.title
        private set

    val isDirty get() = config != originalConfig || title != original.title

    fun row(id: String) = config.rows.firstOrNull { it.id == id }

    /**
     * Строка компактной зоны. Удалённую роль предлагаем из шаблона (пустой источник);
     * в черновик она попадает только после [update].
     */
    fun rowForRole(role: String): BatteryModernConfig.Row {
        config.row(role)?.let { return it }
        val template = BatteryModernConfig.template().first { it.role == role }
        return template.copy(id = uniqueId(template.id))
    }

    /** Заменяет строку с тем же id или добавляет её в конец. */
    fun update(row: BatteryModernConfig.Row) {
        config = if (row(row.id) != null) config.withRow(row) else config.copy(rows = config.rows + row)
    }
    fun delete(id: String) { config = config.withoutRow(id) }
    fun move(id: String, delta: Int) { config = config.move(id, delta) }

    /** Новая произвольная строка; в черновик она попадает только через [update]. */
    fun newRow(section: BatteryModernConfig.Section): BatteryModernConfig.Row =
        BatteryModernConfig.Row(uniqueId("custom"), BatteryModernConfig.ROLE_CUSTOM, section, "Новый показатель", "custom")

    fun setHeader(title: String, subtitle: String) {
        this.title = title
        config = config.copy(subtitle = subtitle)
    }

    fun setInvertPowerSign(value: Boolean) { config = config.copy(invertPowerSign = value) }
    fun setHideUnconfigured(value: Boolean) { config = config.copy(hideUnconfigured = value) }

    /** false — значение не принято (не число или отрицательное). */
    fun setIdleThreshold(text: String): Boolean {
        val value = BatteryFormat.number(text) ?: return false
        if (value < 0) return false
        config = config.copy(idleThreshold = value)
        return true
    }

    fun validationError(): String? = when {
        title.isBlank() -> "Введите название плитки"
        config.rows.any { it.label.isBlank() } -> "У каждой строки должна быть подпись"
        else -> null
    }

    /** Остальные поля и ключи config исходной плитки сохраняются как есть. */
    fun applyTo(tile: TileEntity): TileEntity = tile.copy(title = title.trim(), config = config.writeTo(tile.config))

    fun saveState(): String = JSONObject()
        .put("original", JSONObject().put("id", original.id).put("config", original.config).put("title", original.title))
        .put("title", title)
        .put("config", config.toJson())
        .toString()

    private fun uniqueId(base: String): String {
        val used = config.rows.mapTo(HashSet()) { it.id }
        if (base !in used) return base
        var index = 2
        while ("${base}_$index" in used) index++
        return "${base}_$index"
    }

    companion object {
        /** Восстанавливает черновик для той же плитки; чужое состояние не применяется. */
        fun restore(state: String, original: TileEntity): BatteryModernDraft? {
            val json = JSONObject(state)
            val saved = json.getJSONObject("original")
            if (saved.optString("id") != original.id || saved.optString("config") != original.config ||
                saved.optString("title") != original.title) return null
            return BatteryModernDraft(original, BatteryModernConfig.fromJson(json.getJSONObject("config")), json.optString("title"))
        }
    }
}
