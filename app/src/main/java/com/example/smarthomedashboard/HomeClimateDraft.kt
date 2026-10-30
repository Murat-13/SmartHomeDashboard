package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject

/**
 * Несохранённый черновик «Часы · Погода · Климат». Не пишет prefs: плитка меняется только через
 * [applyTo] по «Сохранить». Отмена отбрасывает черновик целиком.
 */
internal class HomeClimateDraft(val original: TileEntity, initial: HomeClimateConfig? = null, title: String? = null) {
    private val originalConfig = HomeClimateConfig.from(original.config)
    var config: HomeClimateConfig = initial ?: originalConfig
        private set
    var title: String = title ?: original.title
        private set

    val isDirty get() = config != originalConfig || title != original.title

    fun setTitle(value: String) { title = value }
    fun setClock(clock: HomeClimateConfig.Clock) { config = config.copy(clock = clock) }
    fun setWeather(weather: HomeClimateConfig.Weather) { config = config.copy(weather = weather) }
    fun putRoom(room: HomeClimateConfig.Room) { config = config.withRoom(room) }
    fun deleteRoom(id: String) { config = config.withoutRoom(id) }
    fun moveRoom(id: String, delta: Int) { config = config.moveRoom(id, delta) }
    fun moveFavorite(id: String, delta: Int) { config = config.moveFavorite(id, delta) }

    fun toggleFavorite(id: String): HomeClimateConfig.FavoriteChange {
        val (next, change) = config.toggleFavorite(id)
        config = next
        return change
    }

    fun newRoom() = HomeClimateConfig.Room(HomeClimateConfig.newRoomId(), "")

    fun validationError(): String? {
        try { JSONObject(original.config) } catch (_: org.json.JSONException) {
            return "Исходные настройки плитки повреждены. Изменения не сохранены."
        }
        if (title.isBlank()) return "Введите название плитки"
        return config.validationError()
    }

    fun applyTo(tile: TileEntity): TileEntity = tile.copy(title = title.trim(), config = config.writeTo(tile.config))

    fun saveState(): String = JSONObject()
        .put("original", JSONObject().put("id", original.id).put("config", original.config).put("title", original.title))
        .put("title", title)
        .put("config", config.toJson())
        .toString()

    companion object {
        /** Восстанавливает черновик той же неизменённой плитки; иначе null. */
        fun restore(state: String, original: TileEntity): HomeClimateDraft? {
            val json = JSONObject(state)
            val saved = json.getJSONObject("original")
            if (saved.optString("id") != original.id || saved.optString("config") != original.config ||
                saved.optString("title") != original.title) return null
            return HomeClimateDraft(original, HomeClimateConfig.fromJson(json.getJSONObject("config")), json.optString("title"))
        }
    }
}
