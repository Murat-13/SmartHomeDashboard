package com.example.smarthomedashboard

import android.content.Context
import android.os.Bundle
import android.os.Looper
import java.io.File

/**
 * Проверяет перенос заранее подготовленных искусственных данных из прежней версии приложения.
 * Текущий runner не засеивает и не очищает prefs, не запускает Activity/сетевые клиенты.
 */
internal object HaUpgradeFixture {
    const val OLD = "FAKE-UPGRADE-LEGACY-NOT-A-REAL-TOKEN"
    const val NEW = "FAKE-UPGRADE-REPLACEMENT-NOT-A-REAL-TOKEN"
    const val TILES = """[{"id":"fixture-sensor","type":"sensor","container":"grid","title":"Test sensor","x":12,"y":90,"width":1,"height":1,"config":"{\"entity_ids\":[\"sensor.fixture_temperature\"]}"},{"id":"fixture-battery","type":"battery","container":"grid","title":"Test battery","x":180,"y":90,"width":1,"height":1,"config":"{\"entity_id\":\"sensor.fixture_battery\",\"custom_extra\":\"kept\"}"}]"""

    fun settings(): Map<String, Any> = linkedMapOf(
        "security_upgrade_fixture" to "S2-to-S3-v1",
        "ha_token" to OLD,
        "ha_local_host" to "http://127.0.0.1:9",
        "ha_remote_host" to "",
        "setup_completed" to true,
        "mqtt_host" to "127.0.0.1",
        "mqtt_port" to 9,
        "mqtt_username" to "FAKE-UPGRADE-USER",
        "mqtt_password" to "FAKE-UPGRADE-MQTT",
        "admin_pin" to "1234",
        "pin_session_minutes" to 5,
        "last_auth_time" to 0L,
        "kiosk_mode" to false,
        "use_yandex_weather" to false,
        "yandex_api_key" to "FAKE-UPGRADE-WEATHER",
        "yandex_lat" to "0",
        "yandex_lon" to "0",
        "motion_sensor_id" to "",
        "screen_timeout_minutes" to 0,
        "temp_unit" to "F",
        "app_theme" to "dark",
        "future_extra" to "preserved"
    )
    fun preferences(context: Context) = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
    fun tiles(context: Context) = context.getSharedPreferences("tiles_prefs", Context.MODE_PRIVATE)
    fun container(context: Context) = File(context.noBackupFilesDir, "network-secrets/container.bin")
    fun assertPreserved(context: Context, legacy: Boolean) {
        val expected = settings().toMutableMap()
        if (!legacy) expected.remove("ha_token")
        check(preferences(context).all == expected)
        check(tiles(context).all == mapOf("tiles" to TILES))
    }
}

/**
 * Production HaTokenAccess и production noBackup-контейнер, но только после проверки
 * искусственного маркера и точного набора prefs. Каждый запуск — новый процесс instrumentation.
 */
internal object HaTokenUpgradeChecks {
    fun run(owner: Context, operation: String): Bundle {
        var step = "target-owner"
        var passed = false
        try {
            check(owner.applicationInfo.uid == android.os.Process.myUid())
            check(Looper.myLooper() != Looper.getMainLooper())
            val tokens = HaTokenAccess.create(owner)
            when (operation) {
                "migrate" -> {
                    step = "pre-upgrade-data"
                    HaUpgradeFixture.assertPreserved(owner, legacy = true)
                    check(!HaUpgradeFixture.container(owner).exists())
                    step = "verified-production-migration"
                    val result = tokens.read()
                    check(result.status == HaTokenAccess.Status.READY && result.value == HaUpgradeFixture.OLD)
                }
                "readAfterRestart" -> {
                    step = "restart-read"
                    HaUpgradeFixture.assertPreserved(owner, legacy = false)
                    val result = tokens.read()
                    check(result.status == HaTokenAccess.Status.READY && result.value == HaUpgradeFixture.OLD)
                }
                "replace" -> {
                    step = "explicit-replacement"
                    HaUpgradeFixture.assertPreserved(owner, legacy = false)
                    check(tokens.replace(HaUpgradeFixture.NEW).status == HaTokenAccess.Status.READY)
                    check(tokens.read().value == HaUpgradeFixture.NEW)
                }
                "readReplacementAfterRestart" -> {
                    step = "replacement-restart-read"
                    HaUpgradeFixture.assertPreserved(owner, legacy = false)
                    val result = tokens.read()
                    check(result.status == HaTokenAccess.Status.READY && result.value == HaUpgradeFixture.NEW)
                }
                else -> error("Unsupported test mode")
            }
            step = "independent-prefs-and-tiles"
            HaUpgradeFixture.assertPreserved(owner, legacy = false)
            val tiles = com.example.smarthomedashboard.data.TileManager(owner).loadTiles()
            check(tiles.map { it.id } == listOf("fixture-sensor", "fixture-battery"))
            check(tiles[0].x == 12 && tiles[0].y == 90 && tiles[0].width == 1 && tiles[0].height == 1)
            check(org.json.JSONObject(tiles[1].config).getString("custom_extra") == "kept")
            step = "ciphertext-and-nonexportable-key"
            val bytes = HaUpgradeFixture.container(owner).readBytes()
            // Проверяем отсутствие только известных искусственных значений, не печатаем файл/отпечаток.
            check(!bytes.toString(Charsets.ISO_8859_1).contains(HaUpgradeFixture.OLD))
            check(!bytes.toString(Charsets.ISO_8859_1).contains(HaUpgradeFixture.NEW))
            val key = AndroidSecretKeyProvider().existing()
            check(key != null && key.encoded == null)
            step = "idempotent-read-without-container-rewrite"
            check(tokens.read().status == HaTokenAccess.Status.READY)
            check(bytes.contentEquals(HaUpgradeFixture.container(owner).readBytes()))
            passed = true
        } catch (_: Exception) {
            // Никаких plaintext/exception.message/stacktrace в выводе instrumentation.
        } catch (_: AssertionError) {
            // Только безопасный шаг, без actual/expected значений.
        }
        return Bundle().apply {
            putBoolean("passed", passed)
            if (passed) putString("result", "S3 upgrade assertions passed")
            else putString("failed_step", step)
        }
    }
}
