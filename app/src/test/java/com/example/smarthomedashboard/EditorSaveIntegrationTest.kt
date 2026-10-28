package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.widget.Button
import android.widget.EditText
import org.json.JSONObject
import org.robolectric.util.ReflectionHelpers
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

// Реальные onCreate/XML и кнопки Save, без onResume и доступа к сети/защищённому хранилищу.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class EditorSaveIntegrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)
    private fun tile(type: String, config: String) =
        TileEntity("target", type, "grid", "Тест", 20, 30, 1, 1, config = config)

    private fun child(): ChildButtonsEditorActivity {
        val controller = Robolectric.buildActivity(ChildButtonsEditorActivity::class.java,
            Intent(context, ChildButtonsEditorActivity::class.java).putExtra("tile_id", "target"))
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        return controller.create().get()
    }

    private fun advanced(): AdvancedWidgetSettingsActivity {
        val controller = Robolectric.buildActivity(AdvancedWidgetSettingsActivity::class.java,
            Intent(context, AdvancedWidgetSettingsActivity::class.java).putExtra("tile_id", "target"))
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        return controller.create().get()
    }

    @Test fun damagedGroupCannotReportSuccessfulSaveOrClose() {
        manager.saveTiles(listOf(tile("group", "{broken")))
        val before = context.getSharedPreferences("tiles_prefs", 0).all.toMap()
        val editor = child()
        editor.findViewById<Button>(R.id.btnSave).performClick()
        assertFalse(editor.isFinishing)
        assertNotEquals(Activity.RESULT_OK, shadowOf(editor).resultCode)
        assertEquals("Не удалось прочитать настройки плитки. Изменения не сохранены.",
            ShadowToast.getTextOfLatestToast())
        assertEquals(before, context.getSharedPreferences("tiles_prefs", 0).all)
    }

    @Test fun removedTileCannotBeRecreatedOrReportSuccessFromEitherEditor() {
        for (type in listOf("group", "sensor")) {
            manager.saveTiles(listOf(tile(type, "{}")))
            val editor = if (type == "group") child() else advanced()
            manager.deleteTile("target")
            val before = context.getSharedPreferences("tiles_prefs", 0).all.toMap()
            editor.findViewById<Button>(R.id.btnSave).performClick()
            assertFalse(editor.isFinishing)
            assertNotEquals(Activity.RESULT_OK, shadowOf(editor).resultCode)
            assertEquals("Не удалось сохранить: плитка больше не существует.", ShadowToast.getTextOfLatestToast())
            assertEquals(before, context.getSharedPreferences("tiles_prefs", 0).all)
        }
    }

    @Test fun successfulGroupSaveKeepsCurrentIndependentFieldsAndSibling() {
        val original = tile("group",
            """{"entity_ids":["light.a","light.b"],"child_names":["A","B"],"button_size":180,"unknown":"old"}""")
        val sibling = tile("sensor", "{}").copy(id = "sibling")
        manager.saveTiles(listOf(original, sibling))
        val editor = child()
        val items: MutableList<ChildButtonData> = ReflectionHelpers.getField(editor, "childItems")
        items.reverse()
        items[0].name = "Новое имя"
        ReflectionHelpers.setField(editor, "changed", true)
        val latest = original.copy(x = 91, y = 92, title = "Актуальная подпись",
            config = JSONObject(original.config).put("unknown", "latest").toString())
        manager.updateTile(latest)
        editor.findViewById<Button>(R.id.btnSave).performClick()
        assertTrue(editor.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(editor).resultCode)
        assertEquals("Сохранено", ShadowToast.getTextOfLatestToast())
        assertFalse(ReflectionHelpers.getField<Boolean>(editor, "changed"))
        val saved = manager.loadTiles().single { it.id == "target" }
        val config = JSONObject(saved.config)
        assertEquals("""["light.b","light.a"]""", config.getJSONArray("entity_ids").toString())
        assertEquals("""["Новое имя","A"]""", config.getJSONArray("child_names").toString())
        assertEquals("latest", config.getString("unknown"))
        assertEquals(180, config.getInt("button_size"))
        assertEquals(latest.copy(config = saved.config), saved)
        assertEquals(sibling, manager.loadTiles().single { it.id == "sibling" })
    }

    @Test fun successfulAdvancedSaveKeepsCurrentIndependentFieldsAndSibling() {
        val original = tile("sensor", """{"entity_id":"sensor.primary","unknown":"old"}""")
        val sibling = tile("group", "{}").copy(id = "sibling")
        manager.saveTiles(listOf(original, sibling))
        val editor = advanced()
        editor.findViewById<EditText>(R.id.etFontSize).setText("23")
        val collapsed: MutableList<SensorConfig> = ReflectionHelpers.getField(editor, "collapsedConfigs")
        val expanded: MutableList<SensorConfig> = ReflectionHelpers.getField(editor, "expandedConfigs")
        collapsed.add(SensorConfig("sensor.a", "Кратко", 1))
        expanded.add(SensorConfig("sensor.b", "Подробно", 2))
        val latest = original.copy(x = 91, sourceType = "test",
            config = JSONObject(original.config).put("unknown", "latest").toString())
        manager.updateTile(latest)
        editor.findViewById<Button>(R.id.btnSave).performClick()
        assertTrue(editor.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(editor).resultCode)
        assertEquals("Настройки сохранены", ShadowToast.getTextOfLatestToast())
        val saved = manager.loadTiles().single { it.id == "target" }
        val config = JSONObject(saved.config)
        assertEquals("sensor.primary", config.getString("entity_id"))
        assertEquals("latest", config.getString("unknown"))
        assertEquals("Кратко", config.getJSONArray("collapsed_sensor_configs").getJSONObject(0).getString("display_name"))
        assertEquals(2, config.getJSONArray("expanded_sensor_configs").getJSONObject(0).getInt("decimals"))
        assertEquals(latest.copy(config = saved.config, colorRules = saved.colorRules, fontSize = 23), saved)
        assertEquals(sibling, manager.loadTiles().single { it.id == "sibling" })
    }

    @Test fun failedGroupSaveRetainsDraftAndAllowsLaterRetry() {
        manager.saveTiles(listOf(tile("group", "{broken")))
        val editor = child()
        val items: MutableList<ChildButtonData> = ReflectionHelpers.getField(editor, "childItems")
        items.add(ChildButtonData("light.a", "Черновик"))
        ReflectionHelpers.setField(editor, "changed", true)
        editor.findViewById<Button>(R.id.btnSave).performClick()
        assertFalse(editor.isFinishing)
        assertTrue(ReflectionHelpers.getField<Boolean>(editor, "changed"))
        assertEquals("Черновик", items.single().name)
        // Только тестовое окружение восстанавливает исходный config; рабочие данные не изменяются.
        manager.updateTile(tile("group", """{"independent":true}"""))
        editor.findViewById<Button>(R.id.btnSave).performClick()
        assertTrue(editor.isFinishing)
        val saved = JSONObject(manager.loadTiles().single().config)
        assertTrue(saved.getBoolean("independent"))
        assertEquals("Черновик", saved.getJSONArray("child_names").getString(0))
    }

    @Test fun failedAdvancedSaveLeavesInputsAndCancelDoesNotWriteAnything() {
        manager.saveTiles(listOf(tile("sensor", "{broken")))
        val editor = advanced()
        val input = editor.findViewById<EditText>(R.id.etFontSize)
        input.setText("27")
        val before = context.getSharedPreferences("tiles_prefs", 0).all.toMap()
        editor.findViewById<Button>(R.id.btnSave).performClick()
        assertFalse(editor.isFinishing)
        assertEquals("27", input.text.toString())
        assertEquals("Не удалось прочитать настройки плитки. Изменения не сохранены.",
            ShadowToast.getTextOfLatestToast())
        editor.findViewById<Button>(R.id.btnCancel).performClick()
        assertTrue(editor.isFinishing)
        assertNotEquals(Activity.RESULT_OK, shadowOf(editor).resultCode)
        assertEquals(before, context.getSharedPreferences("tiles_prefs", 0).all)
    }

    @Test fun damagedAdvancedConfigCannotCrashSaveOrChangeStoredData() {
        manager.saveTiles(listOf(tile("sensor", "{broken")))
        val before = context.getSharedPreferences("tiles_prefs", 0).all.toMap()
        val editor = advanced()
        editor.findViewById<Button>(R.id.btnSave).performClick()
        assertFalse(editor.isFinishing)
        assertNotEquals(Activity.RESULT_OK, shadowOf(editor).resultCode)
        assertEquals(before, context.getSharedPreferences("tiles_prefs", 0).all)
    }
}
