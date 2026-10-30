package com.example.smarthomedashboard

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/**
 * Стиль одной кнопки или группы. Хранится в config плитки ключом `button_style`.
 * Нет ключа или неизвестное значение — Classic: старые плитки не мигрируют и не перезаписываются.
 */
internal enum class TileButtonStyle(val key: String) {
    CLASSIC("classic"), MODERN("modern");

    companion object {
        const val CONFIG_KEY = "button_style"
        /** Новая кнопка или группа создаётся в Modern. */
        val NEW_TILE = MODERN

        fun of(config: String): TileButtonStyle = try {
            of(JSONObject(config))
        } catch (_: JSONException) {
            CLASSIC
        }

        fun of(config: JSONObject): TileButtonStyle =
            if (config.optString(CONFIG_KEY) == MODERN.key) MODERN else CLASSIC

        /** Только для кнопок и групп; сенсоры не получают переключателя стиля. */
        fun supports(type: String) = type == "button" || type == "group"

        fun isModern(type: String, config: String) = supports(type) && of(config) == MODERN

        /** Меняет только ключ стиля, остальные (в том числе неизвестные) поля config не трогает. */
        fun write(config: JSONObject, style: TileButtonStyle) {
            config.put(CONFIG_KEY, style.key)
        }
    }
}

/** Общая настройка виброотклика Modern. Classic её не читает. */
internal object ModernHaptics {
    const val PREF = "modern_haptics"

    fun enabled(context: Context) =
        context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE).getBoolean(PREF, true)
}
