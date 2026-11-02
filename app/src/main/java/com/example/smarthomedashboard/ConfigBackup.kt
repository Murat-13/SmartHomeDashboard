package com.example.smarthomedashboard

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Состав открытого файла настроек — один для ручного экспорта и автокопий. Только явно перечисленные
 * несекретные поля: токен HA, ключ Яндекса и PIN не входят никогда, новые prefs не попадают сами.
 */
internal object ConfigBackup {
    const val HA_LOCAL = "ha_local_host"
    const val HA_REMOTE = "ha_remote_host"

    val STRING_DEFAULTS = linkedMapOf(
        "motion_sensor_id" to "", "app_theme" to "system", "temp_unit" to "C",
        "yandex_lat" to "", "yandex_lon" to "",
    )
    val INT_DEFAULTS = linkedMapOf(
        "pin_session_minutes" to 60, "screen_timeout_minutes" to 5,
        DashboardPanelController.PREF_PAGE_COUNT to 1, MainActivity.PREF_HOME_SECONDS to 60,
    )
    val BOOLEAN_DEFAULTS = linkedMapOf(
        "kiosk_mode" to false, ModernHaptics.PREF to true, MainActivity.PREF_MOTION_ENABLED to true,
        "use_yandex_weather" to false,
    )
    /** Адрес HA при импорте применяется только после согласия и успешной проверки подключения. */
    val ADDRESS_KEYS = listOf(HA_LOCAL, HA_REMOTE)
    val ALLOWED_KEYS: Set<String> = STRING_DEFAULTS.keys + INT_DEFAULTS.keys + BOOLEAN_DEFAULTS.keys + ADDRESS_KEYS + "tiles_json"

    fun build(context: Context): JSONObject {
        val main = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
        val tiles = context.getSharedPreferences("tiles_prefs", Context.MODE_PRIVATE)
        return JSONObject().apply {
            STRING_DEFAULTS.forEach { (key, default) -> put(key, main.getString(key, default)) }
            INT_DEFAULTS.forEach { (key, default) -> put(key, main.getInt(key, default)) }
            BOOLEAN_DEFAULTS.forEach { (key, default) -> put(key, main.getBoolean(key, default)) }
            ADDRESS_KEYS.forEach { key -> put(key, main.getString(key, "").orEmpty()) }
            put("tiles_json", tiles.getString("tiles", "[]"))
        }
    }

    /** Отпечаток значимого содержимого: статус связи, кеш погоды и сущностей в файл не входят вовсе. */
    fun fingerprint(json: String): String =
        MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
