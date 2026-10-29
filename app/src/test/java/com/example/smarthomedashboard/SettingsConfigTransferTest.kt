package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Looper
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException

// Только искусственные prefs и потоки SAF в памяти; onCreate с персональными defaults не вызывается.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsConfigTransferTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private val tiles = context.getSharedPreferences("tiles_prefs", 0)
    private val activity by lazy {
        Robolectric.buildActivity(SettingsActivity::class.java).get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        }
    }
    private val forbidden = listOf("mqtt_host", "mqtt_port", "mqtt_username", "mqtt_password", "ha_token", "yandex_api_key",
        "admin_pin", "pin_verifier", "last_auth_time", "secret_store", "future_token")
    private fun seed() {
        prefs.edit().clear().putString("mqtt_host", "broker.example.invalid").putInt("mqtt_port", 1883)
            .putString("mqtt_username", "FAKE-LOCAL-LOGIN").putString("mqtt_password", "FAKE-LOCAL-PASSWORD")
            .putString("ha_token", "FAKE-LOCAL-HA").putString("yandex_api_key", "FAKE-LOCAL-WEATHER")
            .putString("admin_pin", "FAKE-LOCAL-PIN").putString("pin_verifier", "FAKE-VERIFIER")
            .putLong("last_auth_time", 123L).putString("secret_store", "FAKE-STORE")
            .putString("future_token", "FAKE-FUTURE").putString("ha_local_host", "http://ha.example.invalid")
            .putString("ha_remote_host", "https://remote.example.invalid").putString("yandex_lat", "0")
            .putString("yandex_lon", "0").putInt("pin_session_minutes", 17)
            .putInt("screen_timeout_minutes", 9).putString("motion_sensor_id", "binary_sensor.test_motion")
            .putString("app_theme", "dark").putBoolean("kiosk_mode", true)
            .putString("unknown_local", "KEEP").commit()
        TileManager(context).saveTiles(listOf(TileEntity("test-tile", "sensor", "grid", "Test", 10, 20, 100, 80,
            config = """{"entity_id":"sensor.test","future_display_option":"keep"}""")))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun invoke(name: String, vararg args: Any?) {
        try {
            SettingsActivity::class.java.declaredMethods.single { it.name == name }
                .apply { isAccessible = true }.invoke(activity, *args)
        } catch (e: InvocationTargetException) { throw e.targetException }
    }
    private fun result(code: Int, uri: Uri, status: Int = Activity.RESULT_OK) {
        invoke("onActivityResult", code, status, Intent().setData(uri))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun export(): String {
        val uri = Uri.parse("content://test-only/export")
        val output = ByteArrayOutputStream()
        shadowOf(activity.contentResolver).registerOutputStream(uri, output)
        invoke("exportConfig")
        ShadowAlertDialog.getLatestAlertDialog()?.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
        assertEquals(200, started.requestCode)
        result(200, uri)
        return output.toString("UTF-8")
    }
    private fun import(text: String) {
        val uri = Uri.parse("content://test-only/import")
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
        invoke("importConfig")
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        assertEquals(201, started.requestCode)
        result(201, uri)
    }
    private fun assertSecrets(before: Map<String, *>) {
        for (key in forbidden) assertEquals("Локальное поле $key изменено", before[key], prefs.all[key])
    }

    @Test fun actualSafExportHasOnlyAllowedFieldsAndNoSyntheticSecrets() {
        seed()
        val before = prefs.all.toMap()
        val oldTiles = tiles.all.toMap()
        val text = export()
        val json = JSONObject(text)
        assertEquals(setOf("pin_session_minutes", "screen_timeout_minutes",
            "motion_sensor_id", "app_theme", "kiosk_mode", "tiles_json"), json.keys().asSequence().toSet())
        for (key in forbidden) assertFalse(key, json.has(key))
        assertFalse(text.contains("FAKE-"))
        assertEquals(17, json.getInt("pin_session_minutes"))
        assertEquals(9, json.getInt("screen_timeout_minutes"))
        assertEquals("binary_sensor.test_motion", json.getString("motion_sensor_id"))
        assertEquals("dark", json.getString("app_theme"))
        assertTrue(json.getBoolean("kiosk_mode"))
        assertEquals(tiles.getString("tiles", ""), json.getString("tiles_json"))
        assertEquals(before, prefs.all)
        assertEquals(oldTiles, tiles.all)
    }

    @Test fun exportExplainsOmittedAccessDataBeforeOpeningSaf() {
        seed()
        invoke("exportConfig")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        val message = shadowOf(dialog).message.toString()
        assertTrue(message.contains("PIN"))
        assertTrue(message.contains("адрес"))
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun newImportPreservesSecretsAndUnspecifiedSettings() {
        seed()
        val before = prefs.all.toMap()
        val oldTiles = tiles.all.toMap()
        import("""{"app_theme":"light"}""")
        assertSecrets(before)
        assertEquals("light", prefs.getString("app_theme", ""))
        for ((key, value) in before) if (key != "app_theme") assertEquals(key, value, prefs.all[key])
        assertEquals(oldTiles, tiles.all)
    }

    @Test fun legacySecretFieldsAreIgnoredAndNoticeDoesNotIncludeValues() {
        seed()
        val before = prefs.all.toMap()
        val json = JSONObject().put("app_theme", "light")
        for (key in forbidden) json.put(key, "FAKE-INCOMING-ACCESS")
        import(json.toString())
        assertSecrets(before)
        assertEquals("light", prefs.getString("app_theme", ""))
        val message = ShadowToast.getTextOfLatestToast().toString()
        assertTrue(message.contains("пропущ"))
        assertFalse(message.contains("FAKE-"))
    }

    @Test fun absentAndEmptySecretFieldsNeverRemovePinOrCreateAuthorization() {
        seed()
        val before = prefs.all.toMap()
        import("{}")
        assertEquals(before, prefs.all)
        val json = JSONObject()
        for (key in forbidden) json.put(key, "")
        json.put("last_auth_time", Long.MAX_VALUE)
        import(json.toString())
        assertEquals(before, prefs.all)
    }

    @Test fun secretFieldsOfUnexpectedTypesAreStillIgnored() {
        seed()
        val before = prefs.all.toMap()
        import("""{"mqtt_password":{},"admin_pin":null,"last_auth_time":[],"pin_verifier":false}""")
        assertEquals(before, prefs.all)
    }

    @Test fun completeSupportedConfigAndTileJsonRoundTripThroughSaf() {
        seed()
        val exported = export()
        prefs.edit().putString("mqtt_host", "changed.example.invalid").putInt("mqtt_port", 2883)
            .putInt("pin_session_minutes", 2).putInt("screen_timeout_minutes", 3)
            .putString("motion_sensor_id", "").putString("app_theme", "light").putBoolean("kiosk_mode", false).commit()
        tiles.edit().putString("tiles", "[]").commit()
        val secretsBefore = prefs.all.toMap()
        import(exported)
        assertSecrets(secretsBefore)
        assertEquals("changed.example.invalid", prefs.getString("mqtt_host", ""))
        assertEquals(2883, prefs.getInt("mqtt_port", 0))
        assertEquals(17, prefs.getInt("pin_session_minutes", 0))
        assertEquals(9, prefs.getInt("screen_timeout_minutes", 0))
        assertEquals("binary_sensor.test_motion", prefs.getString("motion_sensor_id", ""))
        assertEquals("dark", prefs.getString("app_theme", ""))
        assertTrue(prefs.getBoolean("kiosk_mode", false))
        assertEquals(JSONObject(exported).getString("tiles_json"), tiles.getString("tiles", ""))
        assertEquals("keep", JSONObject(TileManager(context).loadTiles().single().config).getString("future_display_option"))
    }

    @Test fun powerModernTilesRoundTripWithIndependentProfiles() {
        seed()
        var grid = PowerModernConfig.profile(PowerModernConfig.PROFILE_GRID)
        grid = grid.withRow(grid.row("voltage")!!.copy(entityId = "sensor.test_grid_v"))
            .copy(rules = grid.rules.map { if (it.label == "Норма") it.copy(color = 0xFF3B82F6.toInt()) else it })
        val inverter = PowerModernConfig.profile(PowerModernConfig.PROFILE_INVERTER)
        TileManager(context).saveTiles(listOf(
            TileEntity("grid", PowerModernConfig.TYPE, "grid", "Сеть · Вход", 10, 20, 1, 1, config = grid.writeTo("{}")),
            TileEntity("inv", PowerModernConfig.TYPE, "grid", "Инвертор · Выход", 300, 20, 1, 1, config = inverter.writeTo("{}"))))
        val exported = export()
        tiles.edit().putString("tiles", "[]").commit()
        import(exported)
        val back = TileManager(context).loadTiles()
        assertEquals(grid, PowerModernConfig.from(back.single { it.id == "grid" }.config))
        assertEquals(inverter, PowerModernConfig.from(back.single { it.id == "inv" }.config))
    }

    @Test fun malformedJsonLeavesBothPrefsUntouchedAndDoesNotEchoInput() {
        seed()
        val before = prefs.all.toMap()
        val oldTiles = tiles.all.toMap()
        import("""{"mqtt_port":2883,"admin_pin":"FAKE-SENSITIVE-BROKEN""")
        assertEquals(before, prefs.all)
        assertEquals(oldTiles, tiles.all)
        assertFalse(ShadowToast.getTextOfLatestToast().toString().contains("FAKE-"))
    }

    @Test fun wrongSupportedFieldTypesDoNotApplyEarlierValidFields() {
        seed()
        for ((key, value) in listOf(
            "pin_session_minutes" to JSONObject.NULL,
            "screen_timeout_minutes" to 1.5, "motion_sensor_id" to JSONArray(),
            "app_theme" to false, "kiosk_mode" to "true", "tiles_json" to JSONArray()
        )) {
            val before = prefs.all.toMap()
            val oldTiles = tiles.all.toMap()
            import(JSONObject().put("motion_sensor_id", "binary_sensor.new").put("app_theme", "light").put(key, value).toString())
            assertEquals(key, before, prefs.all)
            assertEquals(key, oldTiles, tiles.all)
        }
    }

    @Test fun invalidEmbeddedTilesDoNotApplyValidMainSettings() {
        seed()
        for (text in listOf("broken", "{}", "[null]", "[42]", "[{}]")) {
            val before = prefs.all.toMap()
            val oldTiles = tiles.all.toMap()
            import(JSONObject().put("screen_timeout_minutes", 3).put("tiles_json", text).toString())
            assertEquals(before, prefs.all)
            assertEquals(oldTiles, tiles.all)
        }
    }

    @Test fun trailingGarbageAndWrongRootAreRejectedBeforeWriting() {
        seed()
        for (text in listOf("""{"mqtt_port":2883} FAKE-TRAILING""", "[]", "null",
            JSONObject().put("screen_timeout_minutes", 3).put("tiles_json", "[] trailing").toString())) {
            val before = prefs.all.toMap()
            val oldTiles = tiles.all.toMap()
            import(text)
            assertEquals(before, prefs.all)
            assertEquals(oldTiles, tiles.all)
            assertFalse(ShadowToast.getTextOfLatestToast().toString().contains("FAKE-"))
        }
    }

    @Test fun invalidTileFieldTypesRejectWholeImportWithoutDroppingExistingTiles() {
        seed()
        val valid = JSONObject(tiles.getString("tiles", "[]")!!.let { JSONArray(it).getJSONObject(0).toString() })
        for ((key, value) in listOf("title" to 42, "x" to "10", "config" to JSONObject(),
            "enabled" to "true", "fontSize" to 1.5)) {
            val before = prefs.all.toMap()
            val oldTiles = tiles.all.toMap()
            val invalid = JSONObject(valid.toString()).put(key, value)
            import(JSONObject().put("app_theme", "light").put("tiles_json", JSONArray().put(invalid).toString()).toString())
            assertEquals(key, before, prefs.all)
            assertEquals(key, oldTiles, tiles.all)
        }
    }

    @Test fun tileOrderDuplicatesAndUnknownFieldsArePreservedWithoutReencoding() {
        seed()
        val original = JSONArray(tiles.getString("tiles", "[]")).getJSONObject(0)
        original.put("future_tile_field", JSONObject().put("display", "keep"))
        val second = JSONObject(original.toString()).put("id", "second")
        val text = JSONArray().put(second).put(original).put(second).toString(2)
        val before = prefs.all.toMap()
        import(JSONObject().put("tiles_json", text).toString())
        assertEquals(before, prefs.all)
        assertEquals(text, tiles.getString("tiles", ""))
        assertEquals(listOf("second", "test-tile", "second"), TileManager(context).loadTiles().map { it.id })
    }

    @Test fun explicitEmptyOrdinaryStringAndEmptyTileListDoNotClearAccessData() {
        seed()
        val before = prefs.all.toMap()
        import("""{"motion_sensor_id":"","tiles_json":"[]"}""")
        assertSecrets(before)
        assertEquals("", prefs.getString("motion_sensor_id", "fallback"))
        assertEquals(emptyList<TileEntity>(), TileManager(context).loadTiles())
    }

    @Test fun outOfRangeIntegerIsRejectedWithoutChangingSettings() {
        seed()
        val before = prefs.all.toMap()
        val oldTiles = tiles.all.toMap()
        import("""{"app_theme":"light","screen_timeout_minutes":2147483648}""")
        assertEquals(before, prefs.all)
        assertEquals(oldTiles, tiles.all)
    }

    @Test fun ioErrorsDoNotEchoSyntheticAccessDataOrPartiallyImport() {
        seed()
        val before = prefs.all.toMap()
        val oldTiles = tiles.all.toMap()
        val uri = Uri.parse("content://test-only/failing")
        shadowOf(activity.contentResolver).registerInputStream(uri, object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("FAKE-INCOMING-ACCESS")
        })
        result(201, uri)
        assertEquals(before, prefs.all)
        assertEquals(oldTiles, tiles.all)
        assertFalse(ShadowToast.getTextOfLatestToast().toString().contains("FAKE-"))
        shadowOf(activity.contentResolver).registerOutputStream(uri, object : java.io.OutputStream() {
            override fun write(value: Int) { throw java.io.IOException("FAKE-INCOMING-ACCESS") }
        })
        result(200, uri)
        assertEquals(before, prefs.all)
        assertEquals(oldTiles, tiles.all)
        assertFalse(ShadowToast.getTextOfLatestToast().toString().contains("FAKE-"))
    }

    @Test fun canceledSafDoesNotReadOrWriteEitherPrefs() {
        seed()
        val before = prefs.all.toMap()
        val oldTiles = tiles.all.toMap()
        result(201, Uri.parse("content://test-only/unread"), Activity.RESULT_CANCELED)
        result(200, Uri.parse("content://test-only/unwritten"), Activity.RESULT_CANCELED)
        assertEquals(before, prefs.all)
        assertEquals(oldTiles, tiles.all)
    }
    @Test fun legacyMqttFieldsAreIgnoredWithoutCreatingPreferences() {
        prefs.edit().clear().commit()
        import("""{"mqtt_host":"mqtt.example.invalid","mqtt_port":1883,"mqtt_username":"FAKE-OLD-LOGIN","mqtt_password":"FAKE-OLD-PASSWORD","mqtt_password_transfer_phase":"verified","app_theme":"light"}""")
        assertTrue(MqttRemovalMigration.KEYS.none(prefs::contains))
        assertFalse(prefs.contains(MqttRemovalMigration.COMPLETED))
        assertEquals("light", prefs.getString("app_theme", null))
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("пропущ"))
    }

    @Test fun unsupportedLegacyMqttTypesAreIgnoredWithoutAValidationError() {
        prefs.edit().clear().commit()
        import("""{"mqtt_host":{},"mqtt_port":"unused","mqtt_username":[],"app_theme":"dark"}""")
        assertTrue(MqttRemovalMigration.KEYS.none(prefs::contains))
        assertEquals("dark", prefs.getString("app_theme", null))
    }

}
