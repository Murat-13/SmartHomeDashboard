package com.example.smarthomedashboard

import android.content.Context
import android.os.Bundle
import android.os.Looper

/** Только известная искусственная S3-установка. Любое несовпадение останавливает seed до записи. */
internal object WeatherUpgradeFixture {
    const val LEGACY = "  FAKE-WEATHER-UPGRADE-Юникод  "
    const val REPLACEMENT = "FAKE-WEATHER-UPGRADE-REPLACEMENT"
    fun expected(secure: Boolean): Map<String, Any> = HaUpgradeFixture.settings().toMutableMap().apply {
        remove("ha_token")
        put("security_weather_upgrade_fixture", "S3-to-S4b-v1")
        put("yandex_api_key", LEGACY)
        put("yandex_lat", " 10.25 ")
        put("yandex_lon", "-20.5")
        put("use_yandex_weather", true)
        put("yandex_last_temp", "FAKE-WEATHER-CACHE")
        put("yandex_last_desc", "FAKE-WEATHER-DESCRIPTION")
        if (secure) remove("yandex_api_key")
    }
    fun preserved(owner: Context, secure: Boolean) {
        check(HaUpgradeFixture.preferences(owner).all == expected(secure))
        check(HaUpgradeFixture.tiles(owner).all == mapOf("tiles" to HaUpgradeFixture.TILES))
        val ha = HaTokenAccess.create(owner).read()
        check(ha.status == HaTokenAccess.Status.READY && ha.value == HaUpgradeFixture.NEW)
        val store = AndroidSecretStore.create(owner)
        check(store.read(SecretStore.Slot.MQTT_PASSWORD).status == SecretStore.Status.ABSENT)
        check(AndroidSecretKeyProvider().existing()?.encoded == null)
    }

}

/** Production reader и контейнер, без Activity/сетевых клиентов. Каждый вызов — новый процесс. */
internal object WeatherUpgradeChecks {
    fun run(owner: Context, operation: String): Bundle {
        var passed = false
        var step = "weather-target-owner"
        try {
            check(owner.applicationInfo.uid == android.os.Process.myUid())
            check(Looper.myLooper() != Looper.getMainLooper())
            val weather = WeatherKeyAccess.create(owner)
            step = operation
            when (operation) {
                "weatherMigrate" -> {
                    WeatherUpgradeFixture.preserved(owner, secure = false)
                    check(AndroidSecretStore.create(owner).read(SecretStore.Slot.WEATHER_KEY).status == SecretStore.Status.ABSENT)
                    val result = weather.read()
                    check(result.status == WeatherKeyAccess.Status.READY && result.value == WeatherUpgradeFixture.LEGACY)
                }
                "weatherReadAfterRestart" -> {
                    WeatherUpgradeFixture.preserved(owner, secure = true)
                    val result = weather.read()
                    check(result.status == WeatherKeyAccess.Status.READY && result.value == WeatherUpgradeFixture.LEGACY)
                }
                "weatherReplace" -> {
                    WeatherUpgradeFixture.preserved(owner, secure = true)
                    val before = weather.read()
                    check(before.value == WeatherUpgradeFixture.LEGACY)
                    check(weather.save(WeatherUpgradeFixture.REPLACEMENT, checkNotNull(before.configuration), before.revision).status == WeatherKeyAccess.Status.READY)
                    check(weather.read().value == WeatherUpgradeFixture.REPLACEMENT)
                }
                "weatherReadReplacementAfterRestart" -> {
                    WeatherUpgradeFixture.preserved(owner, secure = true)
                    val result = weather.read()
                    check(result.status == WeatherKeyAccess.Status.READY && result.value == WeatherUpgradeFixture.REPLACEMENT)
                }
                else -> error("Unsupported artificial fixture operation")
            }
            step = "weather-independent-settings-ha-tiles"
            WeatherUpgradeFixture.preserved(owner, secure = true)
            val result = weather.read()
            check(result.status == WeatherKeyAccess.Status.READY)
            val configuration = checkNotNull(result.configuration)
            check(configuration.latitude == " 10.25 " && configuration.longitude == "-20.5" && configuration.enabled)
            check(!HaUpgradeFixture.preferences(owner).contains("yandex_key_transfer_phase"))
            val bytes = HaUpgradeFixture.container(owner).readBytes()
            check(!bytes.toString(Charsets.ISO_8859_1).contains(WeatherUpgradeFixture.LEGACY))
            check(!bytes.toString(Charsets.ISO_8859_1).contains(WeatherUpgradeFixture.REPLACEMENT))
            step = "weather-idempotent-native-read"
            check(weather.read().status == WeatherKeyAccess.Status.READY)
            check(bytes.contentEquals(HaUpgradeFixture.container(owner).readBytes()))
            passed = true
        } catch (_: Exception) {
            // Только идентификатор шага; никаких значений или exception.message.
        } catch (_: AssertionError) {
            // Не выводим actual/expected.
        }
        return Bundle().apply {
            putBoolean("passed", passed)
            if (passed) putString("result", "S4b weather upgrade assertions passed")
            else putString("failed_step", step)
        }
    }
}
