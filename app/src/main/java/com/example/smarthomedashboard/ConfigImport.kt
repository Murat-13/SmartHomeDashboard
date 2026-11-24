package com.example.smarthomedashboard

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Применение файла настроек (состав — [ConfigBackup]): одинаково в Настройках и на первом экране мастера. Вся проверка —
 * до первой записи; неверный формат — [JSONException] или [IllegalArgumentException], ничего не записано. Секреты и адрес
 * Home Assistant не применяются никогда: адрес возвращается для проверки подключением.
 */
internal object ConfigImport {
    /** [skipped] — в файле были неподдерживаемые поля; [local]/[remote] — адрес HA, если он другой, иначе null. */
    class Outcome(val skipped: Boolean, val local: String?, val remote: String?)

    fun parse(text: String): JSONObject {
        val tokener = JSONTokener(text)
        val json = tokener.nextValue() as? JSONObject ?: throw JSONException("Ожидался объект настроек")
        require(tokener.nextClean().code == 0)
        return json
    }

    private fun int(value: Any?): Int {
        require(value is Int || value is Long && value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return (value as Number).toInt()
    }

    private fun validateTiles(text: String) {
        val tokener = JSONTokener(text)
        val array = tokener.nextValue() as? JSONArray ?: throw JSONException("Ожидался список плиток")
        require(tokener.nextClean().code == 0)
        for (i in 0 until array.length()) {
            val tile = array.getJSONObject(i)
            // Проверяем типы данных, но не пересобираем JSON: порядок и дополнительные поля сохраняются.
            for (key in listOf("id", "type", "container", "title")) require(tile.opt(key) is String)
            for (key in listOf("x", "y", "width", "height")) int(tile.opt(key))
            for (key in listOf("appearance", "conditions", "config", "collapsedSensorIds", "colorRules", "icon", "sourceType")) {
                if (tile.has(key)) require(tile.opt(key) is String)
            }
            if (tile.has("fontSize")) int(tile.opt("fontSize"))
            if (tile.has("enabled")) require(tile.opt("enabled") is Boolean)
            if (tile.has("page")) require(int(tile.opt("page")) >= 0)
        }
    }

    fun apply(context: Context, json: JSONObject): Outcome {
        val strings = linkedMapOf<String, String>()
        val integers = linkedMapOf<String, Int>()
        val booleans = linkedMapOf<String, Boolean>()
        ConfigBackup.STRING_DEFAULTS.keys.forEach { key ->
            if (json.has(key)) strings[key] = json.get(key) as? String ?: throw JSONException("Неверный тип настройки")
        }
        ConfigBackup.INT_DEFAULTS.keys.forEach { key ->
            if (json.has(key)) integers[key] = int(json.get(key))
        }
        ConfigBackup.BOOLEAN_DEFAULTS.keys.forEach { key ->
            if (json.has(key)) booleans[key] = json.get(key) as? Boolean ?: throw JSONException("Неверный тип настройки")
        }
        val address = ConfigBackup.ADDRESS_KEYS.associateWith { key ->
            if (json.has(key)) (json.get(key) as? String ?: throw JSONException("Неверный тип настройки")).trim() else null
        }
        val tiles = if (json.has("tiles_json")) {
            (json.get("tiles_json") as? String ?: throw JSONException("Неверный тип списка плиток"))
                .also { validateTiles(it) }
        } else null
        val skipped = json.keys().asSequence().any { it !in ConfigBackup.ALLOWED_KEYS }

        // Вся проверка завершена до первой записи. Отсутствующие поля, секреты, допуск и адрес HA не изменяем.
        val prefs = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
        if (strings.isNotEmpty() || integers.isNotEmpty() || booleans.isNotEmpty()) {
            prefs.edit {
                strings.forEach { (key, value) -> putString(key, value) }
                integers.forEach { (key, value) -> putInt(key, value) }
                booleans.forEach { (key, value) -> putBoolean(key, value) }
            }
        }
        if (tiles != null) {
            context.getSharedPreferences("tiles_prefs", Context.MODE_PRIVATE).edit { putString("tiles", tiles) }
        }
        val local = address[ConfigBackup.HA_LOCAL]
        val remote = address[ConfigBackup.HA_REMOTE]
        val currentLocal = prefs.getString(ConfigBackup.HA_LOCAL, "").orEmpty().trim()
        val currentRemote = prefs.getString(ConfigBackup.HA_REMOTE, "").orEmpty().trim()
        val differs = (local != null && local != currentLocal) || (remote != null && remote != currentRemote)
        return if (differs && (!local.isNullOrEmpty() || !remote.isNullOrEmpty()))
            Outcome(skipped, local ?: currentLocal, remote ?: currentRemote) else Outcome(skipped, null, null)
    }
}
