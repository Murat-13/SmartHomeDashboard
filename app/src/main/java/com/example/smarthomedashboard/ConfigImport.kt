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

    /** Пределы файла настроек (второй аудит 25.11.2026, AUD-20): сверх них панель после импорта не открылась бы или гасла бы сразу. */
    const val MAX_PAGES = 50
    const val MAX_TILES = 2_000
    const val MAX_TIMEOUT_MINUTES = 1_440
    const val MAX_CHARS = 8 * 1024 * 1024

    /** Прочитать не больше [MAX_CHARS]: файл в сотни мегабайт закрыл бы приложение по памяти. */
    fun readText(input: java.io.InputStream): String = input.bufferedReader(Charsets.UTF_8).use { reader ->
        val out = StringBuilder(); val buffer = CharArray(1 shl 16)
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            require(out.length + n <= MAX_CHARS)
            out.append(buffer, 0, n)
        }
        out.toString()
    }

    fun parse(text: String): JSONObject {
        require(text.length <= MAX_CHARS)
        val tokener = JSONTokener(text)
        val json = tokener.nextValue() as? JSONObject ?: throw JSONException("Ожидался объект настроек")
        require(tokener.nextClean().code == 0)
        return json
    }

    /** Запись с ответом; подменяется в тестах. */
    @android.annotation.SuppressLint("ApplySharedPref") // нужен ответ: при отказе вторая запись не делается
    internal var writer: (android.content.SharedPreferences.Editor) -> Boolean = { it.commit() }

    private fun int(value: Any?): Int {
        require(value is Int || value is Long && value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return (value as Number).toInt()
    }

    private fun validateTiles(text: String) {
        val tokener = JSONTokener(text)
        val array = tokener.nextValue() as? JSONArray ?: throw JSONException("Ожидался список плиток")
        require(tokener.nextClean().code == 0)
        require(array.length() <= MAX_TILES)
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
            if (tile.has("page")) require(int(tile.opt("page")) in 0 until MAX_PAGES)
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
        integers[DashboardPanelController.PREF_PAGE_COUNT]?.let { require(it in 1..MAX_PAGES) }
        // Настройки принимают любое время от минуты — своя же копия обязана читаться: больше суток обрезается, а не отклоняется.
        integers["screen_timeout_minutes"]?.let { require(it >= 1); integers["screen_timeout_minutes"] = it.coerceAtMost(MAX_TIMEOUT_MINUTES) }
        val address = ConfigBackup.ADDRESS_KEYS.associateWith { key ->
            if (json.has(key)) (json.get(key) as? String ?: throw JSONException("Неверный тип настройки")).trim() else null
        }
        val tiles = if (json.has("tiles_json")) {
            (json.get("tiles_json") as? String ?: throw JSONException("Неверный тип списка плиток"))
                .also { validateTiles(it) }
        } else null
        val skipped = json.keys().asSequence().any { it !in ConfigBackup.ALLOWED_KEYS }

        // Вся проверка завершена до первой записи. Отсутствующие поля, секреты, допуск и адрес HA не изменяем.
        // Сначала плитки, потом настройки, каждая запись — с ответом: не записались плитки (нет места) — настройки не трогаем,
        // иначе новые общие настройки (страницы, проходы) остались бы при старых плитках (второй аудит 25.11.2026, AUD-19).
        val prefs = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
        if (tiles != null) {
            require(writer(context.getSharedPreferences("tiles_prefs", Context.MODE_PRIVATE).edit().putString("tiles", tiles))) { "плитки не записаны" }
        }
        if (strings.isNotEmpty() || integers.isNotEmpty() || booleans.isNotEmpty()) {
            val editor = prefs.edit()
            strings.forEach { (key, value) -> editor.putString(key, value) }
            integers.forEach { (key, value) -> editor.putInt(key, value) }
            booleans.forEach { (key, value) -> editor.putBoolean(key, value) }
            require(writer(editor)) { "настройки не записаны" }
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
