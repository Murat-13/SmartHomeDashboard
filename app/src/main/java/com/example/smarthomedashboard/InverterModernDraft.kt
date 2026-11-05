package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject

/**
 * Несохранённый черновик плитки «Инвертор». Ничего не пишет: плитка меняется только через [applyTo]
 * и явное «Сохранить» редактора. Отмена отбрасывает черновик целиком.
 */
internal class InverterModernDraft(val original: TileEntity, initial: InverterModernConfig? = null, title: String? = null) {
    private val originalConfig = InverterModernConfig.from(original.config)
    var config: InverterModernConfig = initial ?: originalConfig
        private set
    var title: String = title ?: original.title
        private set

    val isDirty get() = config != originalConfig || title != original.title

    fun row(id: String) = config.rows.firstOrNull { it.id == id }
    fun update(row: BatteryModernConfig.Row) { config = config.withRow(row) }
    fun setTitle(value: String) { title = value }
    fun setRatedPower(watts: Double) { config = config.copy(ratedPowerW = watts) }
    fun setOverloadPercent(percent: Double) { config = config.copy(overloadPercent = percent) }
    fun setInvertBattery(value: Boolean) { config = config.copy(invertBatterySign = value) }
    fun setInvertGrid(value: Boolean) { config = config.copy(invertGridSign = value) }

    fun validationError(): String? {
        if (title.isBlank()) return "Введите название плитки"
        if (config.rows.any { it.label.isBlank() }) return "У каждой строки должна быть подпись"
        if (!(config.ratedPowerW > 0)) return "Номинальная мощность должна быть больше нуля"
        if (config.overloadPercent !in 1.0..200.0) return "Порог перегруза — от 1 до 200%"
        return null
    }

    fun applyTo(tile: TileEntity): TileEntity = tile.copy(title = title.trim(), config = config.writeTo(tile.config))

    fun saveState(): String = JSONObject()
        .put("original", JSONObject().put("id", original.id).put("config", original.config).put("title", original.title))
        .put("title", title)
        .put("config", config.toJson())
        .toString()

    companion object {
        /** Восстанавливает черновик той же неизменённой плитки; иначе null. */
        fun restore(state: String, original: TileEntity): InverterModernDraft? {
            val json = JSONObject(state)
            val saved = json.getJSONObject("original")
            if (saved.optString("id") != original.id || saved.optString("config") != original.config ||
                saved.optString("title") != original.title) return null
            return InverterModernDraft(original, InverterModernConfig.fromJson(json.getJSONObject("config")), json.optString("title"))
        }
    }
}
