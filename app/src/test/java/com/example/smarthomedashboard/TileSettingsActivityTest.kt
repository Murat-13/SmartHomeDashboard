package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.os.Looper
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowResources
import org.robolectric.util.ReflectionHelpers.ClassParameter

// В текущей конфигурации JVM нет APK ресурсов приложения. Подменяем только строку сводки выбора;
// callbacks Activity, сохранение через lifecycleScope и TileManager остаются настоящими.
@Implements(Resources::class)
class SelectionResources : ShadowResources() {
    @RealObject private lateinit var resources: Resources

    @Implementation
    fun getText(id: Int): CharSequence {
        if (id == R.string.none_selected) return "Не выбрано"
        return Shadow.directlyOn(resources, Resources::class.java, "getText",
            ClassParameter.from(Int::class.javaPrimitiveType, id))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [SelectionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class TileSettingsActivityTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)

    private fun tile(config: String = """{"entity_id":"sensor.old"}""") =
        TileEntity("tile", "sensor", "grid", "Test", 10, 20, 100, 80, config = config)

    private fun set(activity: TileSettingsActivity, name: String, value: Any?) {
        TileSettingsActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(activity, value)
        }
    }

    private fun invoke(activity: TileSettingsActivity, name: String) {
        TileSettingsActivity::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
            invoke(activity)
        }
    }

    private fun open(savedInstanceState: Bundle? = null, tileId: String? = "tile"): TileSettingsActivity {
        val currentId = savedInstanceState?.getString("tile_settings_tile_id") ?: tileId
        val activity = Robolectric.buildActivity(TileSettingsActivity::class.java,
            Intent(context, TileSettingsActivity::class.java).putExtra("tile_id", currentId)).get()
        // Выполняем восстановление SavedStateRegistry без AppCompat.onCreate: APK ресурсов
        // нет, но настоящий onSaveInstanceState требует восстановленный реестр компонента.
        androidx.activity.ComponentActivity::class.java.declaredFields.single {
            it.type == androidx.savedstate.SavedStateRegistryController::class.java
        }.apply {
            isAccessible = true
            (get(activity) as androidx.savedstate.SavedStateRegistryController).performRestore(null)
        }
        androidx.fragment.app.FragmentActivity::class.java.declaredFields.single {
            it.type == androidx.fragment.app.FragmentController::class.java
        }.apply {
            isAccessible = true
            (get(activity) as androidx.fragment.app.FragmentController).attachHost(null)
        }
        set(activity, "tileManager", TileManager(activity))
        set(activity, "tileId", currentId)
        set(activity, "etTitle", EditText(activity))
        set(activity, "etButtonSize", EditText(activity))
        set(activity, "tvSelectedSensors", TextView(activity))
        set(activity, "spinnerType", Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item,
                listOf("sensor", "button", "group", "battery", "clock", "weather"))
        })
        invoke(activity, "loadTileData")
        if (savedInstanceState != null) {
            TileSettingsActivity::class.java.getDeclaredMethod("restoreSensorSelection", Bundle::class.java).apply {
                isAccessible = true
                invoke(activity, savedInstanceState)
            }
        }
        return activity
    }

    private fun choose(activity: TileSettingsActivity, ids: List<String>) {
        TileSettingsActivity::class.java.getDeclaredMethod("onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java).apply {
            isAccessible = true
            invoke(activity, 1001, Activity.RESULT_OK,
                Intent().putStringArrayListExtra("selected_sensors", ArrayList(ids)))
        }
    }

    private fun save(activity: TileSettingsActivity): TileEntity {
        invoke(activity, "saveTile")
        shadowOf(Looper.getMainLooper()).idle()
        return manager.loadTiles().single()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(activity: TileSettingsActivity, name: String): T =
        TileSettingsActivity::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(activity) as T
        }

    private fun result(activity: TileSettingsActivity, code: Int, data: Intent?) {
        TileSettingsActivity::class.java.getDeclaredMethod("onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java).apply {
            isAccessible = true
            invoke(activity, 1001, code, data)
        }
    }

    private fun savedState(activity: TileSettingsActivity): Bundle {
        val bundle = Bundle()
        TileSettingsActivity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java).apply {
            isAccessible = true
            invoke(activity, bundle)
        }
        return bundle
    }

    @Test fun hiddenButtonSizeDoesNotChangeSensorBatteryOrWeatherDimensionsOnSave() {
        for (type in listOf("sensor", "battery", "weather")) {
            for (size in listOf<String?>(null, "287")) {
                val config = JSONObject().put("entity_id", "sensor.old")
                if (size != null) config.put("button_size", size)
                val old = tile(config.toString()).copy(type = type)
                manager.saveTiles(listOf(old))
                val activity = open()
                get<EditText>(activity, "etTitle").setText("Renamed")
                val saved = JSONObject(save(activity).config)
                if (size == null) assertFalse(type, saved.has("button_size"))
                else assertEquals(size, saved.get("button_size"))
            }
        }
    }

    @Test fun editableButtonAndGroupSizeIsStillSaved() {
        for (type in listOf("button", "group")) {
            manager.saveTiles(listOf(tile().copy(type = type, container = "bottom_panel")))
            val activity = open()
            get<EditText>(activity, "etButtonSize").setText("240")
            val stored = save(activity)
            assertEquals(240, JSONObject(stored.config).getInt("button_size"))
            assertEquals("sensor.old", JSONObject(stored.config).getString("entity_id"))
        }
    }

    @Test fun newTileKeepsConfirmedSelectionAndDoesNotOverwriteExistingTiles() {
        val other = tile().copy(id = "other")
        manager.saveTiles(listOf(other))
        val activity = open(tileId = null)
        get<EditText>(activity, "etTitle").setText("New")
        choose(activity, listOf("sensor.new"))
        invoke(activity, "saveTile")
        shadowOf(Looper.getMainLooper()).idle()
        val tiles = manager.loadTiles()
        assertEquals(2, tiles.size)
        assertEquals(other, tiles.first { it.id == "other" })
        val created = tiles.first { it.id != "other" }
        assertEquals("New", created.title)
        assertEquals("sensor.new", JSONObject(created.config).getString("entity_id"))
    }

    @Test fun newTilesArePlacedInFreePanelPixelsInsteadOfStackingInCorner() {
        manager.saveTiles(listOf(tile().copy(id = "other", x = 20, y = 40, config = "{}")))
        val created = mutableListOf<TileEntity>()
        repeat(2) { n ->
            val activity = open(tileId = null)
            activity.resources.displayMetrics.widthPixels = 1920
            activity.resources.displayMetrics.heightPixels = 1200
            get<EditText>(activity, "etTitle").setText("New $n")
            invoke(activity, "saveTile")
            shadowOf(Looper.getMainLooper()).idle()
            created += manager.loadTiles().single { it.title == "New $n" }
        }
        val all = manager.loadTiles()
        // Раньше x/y были номером ячейки (0..3, 0..1) и плитки ложились друг на друга.
        for (tile in created) {
            for (other in all.filter { it.id != tile.id }) {
                val a = TilePlacement.size(tile.container, tile.config)
                val b = TilePlacement.size(other.container, other.config)
                assertFalse("${tile.title} перекрывает ${other.title}",
                    tile.x < other.x + b.width && tile.x + a.width > other.x &&
                        tile.y < other.y + b.height && tile.y + a.height > other.y)
            }
        }
    }

    @Test fun changingGroupSelectionKeepsEachNameWithItsDevice() {
        manager.saveTiles(listOf(tile("""{"entity_ids":["light.a","light.b","light.c"],
            "child_names":["Кухня","Коридор","Ванная"]}""").copy(type = "group", container = "bottom_panel")))
        val activity = open()
        // Раньше имена оставались на прежних позициях: «Ванная» становилась именем нового устройства.
        choose(activity, listOf("light.a", "light.c", "light.new"))
        val config = JSONObject(save(activity).config)
        assertEquals("""["light.a","light.c","light.new"]""", config.getJSONArray("entity_ids").toString())
        assertEquals("""["Кухня","Ванная","3"]""", config.getJSONArray("child_names").toString())
    }

    @Test fun emptyTitleKeepsScreenOpenAndSavesNothing() {
        manager.saveTiles(emptyList())
        val activity = open(tileId = null)
        get<EditText>(activity, "etTitle").setText("   ")
        choose(activity, listOf("sensor.new"))
        invoke(activity, "saveTile")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Экран не должен закрываться с несохранённым вводом", activity.isFinishing)
        assertEquals("Введите название", get<EditText>(activity, "etTitle").error?.toString())
        assertTrue(manager.loadTiles().isEmpty())
        assertEquals(listOf("sensor.new"), get<List<String>>(activity, "selectedSensors"))
    }

    @Test fun newTileIdSavedBeforeAdvancedScreenSurvivesRestorationWithoutDuplicate() {
        manager.saveTiles(emptyList())
        val activity = open(tileId = null)
        get<EditText>(activity, "etTitle").setText("New")
        choose(activity, listOf("sensor.new"))
        // Это тот же путь первичного сохранения, который использует кнопка расширенных настроек.
        invoke(activity, "saveTileWithoutFinish")
        shadowOf(Looper.getMainLooper()).idle()
        val created = manager.loadTiles().single()
        val recreated = open(savedState(activity), tileId = null)
        val stored = save(recreated)
        assertEquals(created.id, stored.id)
        assertEquals("sensor.new", JSONObject(stored.config).getString("entity_id"))
        assertEquals(1, manager.loadTiles().size)
    }

    @Test fun pendingClearSurvivesSavedStateAndNewScreenInstance() {
        manager.saveTiles(listOf(tile()))
        val activity = open()
        choose(activity, emptyList())
        val bundle = savedState(activity)
        assertEquals("tile", bundle.getString("tile_settings_tile_id"))
        val recreated = open(bundle)
        assertEquals(emptyList<String>(), get<List<String>>(recreated, "selectedSensors"))
        val json = JSONObject(save(recreated).config)
        assertFalse(json.has("entity_id"))
        assertFalse(json.has("entity_ids"))
    }

    @Test fun pendingReplacementAndOrderSurviveSavedState() {
        manager.saveTiles(listOf(tile()))
        val activity = open()
        choose(activity, listOf("sensor.b", "sensor.a"))
        val recreated = open(savedState(activity))
        assertEquals(listOf("sensor.b", "sensor.a"), get<List<String>>(recreated, "selectedSensors"))
        assertEquals("""["sensor.b","sensor.a"]""",
            JSONObject(save(recreated).config).getJSONArray("entity_ids").toString())
    }

    @Test fun unchangedSelectionRemainsUnchangedAfterSavedState() {
        manager.saveTiles(listOf(tile("""{"entity_id":"sensor.single","entity_ids":["sensor.a","sensor.b"]}""")))
        val recreated = open(savedState(open()))
        val json = JSONObject(save(recreated).config)
        assertEquals("sensor.single", json.getString("entity_id"))
        assertEquals("""["sensor.a","sensor.b"]""", json.getJSONArray("entity_ids").toString())
    }

    @Test fun explicitClearActionUsesRealSavePathAndPreservesIndependentSettings() {
        val old = tile("""{"entity_id":"sensor.old","button_size":160,
            "expanded_sensor_configs":[{"entity_id":"sensor.independent"}]}""").copy(fontSize = 25)
        manager.saveTiles(listOf(old))
        val activity = open()
        invoke(activity, "clearSelectedSensors")
        assertEquals("Не выбрано", get<TextView>(activity, "tvSelectedSensors").text.toString())
        val stored = save(activity)
        assertFalse(JSONObject(stored.config).has("entity_id"))
        assertEquals(old.copy(config = stored.config), stored)
        assertEquals("""[{"entity_id":"sensor.independent"}]""",
            JSONObject(stored.config).getJSONArray("expanded_sensor_configs").toString())
    }

    @Test fun cancelingPickerDoesNotUndoAnEarlierExplicitClear() {
        manager.saveTiles(listOf(tile()))
        val activity = open()
        invoke(activity, "clearSelectedSensors")
        result(activity, Activity.RESULT_CANCELED,
            Intent().putStringArrayListExtra("selected_sensors", arrayListOf("sensor.old")))
        assertEquals(emptyList<String>(), get<List<String>>(activity, "selectedSensors"))
        assertFalse(JSONObject(save(activity).config).has("entity_id"))
    }

    @Test fun confirmedReplacementThenPickerCancelKeepsPendingReplacement() {
        manager.saveTiles(listOf(tile()))
        val activity = open()
        choose(activity, listOf("sensor.new"))
        result(activity, Activity.RESULT_CANCELED, null)
        assertEquals("sensor.new", JSONObject(save(activity).config).getString("entity_id"))
    }

    @Test fun clearRemovesEitherOrBothLegacySourceFields() {
        for (config in listOf(
            """{"entity_id":"sensor.old"}""",
            """{"entity_ids":["sensor.old","sensor.other"]}""",
            """{"entity_ids":["sensor.old"],"entity_id":"sensor.alternative"}""")) {
            manager.saveTiles(listOf(tile(config)))
            val activity = open()
            choose(activity, emptyList())
            val stored = save(activity)
            assertFalse(config, JSONObject(stored.config).has("entity_id"))
            assertFalse(config, JSONObject(stored.config).has("entity_ids"))
            assertEquals(emptyList<String>(), get<List<String>>(open(), "selectedSensors"))
        }
    }

    @Test fun canceledPickerDoesNotChangeSourceWhenSavingOtherSettings() {
        val config = """{"entity_ids":["sensor.a","sensor.b"],"entity_id":"sensor.alternative","button_size":160}"""
        manager.saveTiles(listOf(tile(config)))
        val activity = open()
        result(activity, Activity.RESULT_CANCELED,
            Intent().putStringArrayListExtra("selected_sensors", arrayListOf()))
        get<EditText>(activity, "etTitle").setText("Renamed")
        val json = JSONObject(save(activity).config)
        assertEquals("sensor.alternative", json.getString("entity_id"))
        assertEquals("""["sensor.a","sensor.b"]""", json.getJSONArray("entity_ids").toString())
    }

    @Test fun unchangedSelectionPreservesLegacyRepresentationOrderAndDuplicates() {
        val config = """{"entity_ids":["sensor.b","sensor.a","sensor.b"],"entity_id":"sensor.alternative","button_size":160}"""
        manager.saveTiles(listOf(tile(config)))
        val activity = open()
        get<EditText>(activity, "etTitle").setText("Renamed")
        val json = JSONObject(save(activity).config)
        assertEquals("sensor.alternative", json.getString("entity_id"))
        assertEquals("""["sensor.b","sensor.a","sensor.b"]""", json.getJSONArray("entity_ids").toString())
    }

    @Test fun confirmingUnchangedNonEmptyPickerDoesNotNormalizeLegacySources() {
        val old = tile("""{"entity_ids":["sensor.a"],"entity_id":"sensor.alternative"}""")
        manager.saveTiles(listOf(old))
        val activity = open()
        choose(activity, listOf("sensor.a"))
        val json = JSONObject(save(activity).config)
        assertEquals("sensor.alternative", json.getString("entity_id"))
        assertEquals("""["sensor.a"]""", json.getJSONArray("entity_ids").toString())
    }

    @Test fun singleReplacementRemovesAlternativeOldArray() {
        manager.saveTiles(listOf(tile("""{"entity_id":"sensor.old","entity_ids":["sensor.older"]}""")))
        val activity = open()
        choose(activity, listOf("sensor.new"))
        val stored = save(activity)
        val json = JSONObject(stored.config)
        assertEquals("sensor.new", json.getString("entity_id"))
        assertFalse(json.has("entity_ids"))
        assertEquals(listOf("sensor.new"), get<List<String>>(open(), "selectedSensors"))
    }

    @Test fun multipleReplacementPreservesConfirmedOrderAndRemovesAlternativeSingle() {
        manager.saveTiles(listOf(tile()))
        val activity = open()
        choose(activity, listOf("sensor.b", "sensor.a", "sensor.c"))
        val json = JSONObject(save(activity).config)
        assertFalse(json.has("entity_id"))
        assertEquals("""["sensor.b","sensor.a","sensor.c"]""", json.getJSONArray("entity_ids").toString())
        assertEquals(listOf("sensor.b", "sensor.a", "sensor.c"), get<List<String>>(open(), "selectedSensors"))
    }

    @Test fun independentTileAndJsonSettingsSurviveSavingOtherSettings() {
        val old = tile("""{"entity_id":"sensor.old","button_size":160,"widget_height":244,
            "collapsed_sensor_configs":[{"entity_id":"sensor.c","display_name":"Name","decimals":2}],
            "expanded_sensor_configs":[{"entity_id":"sensor.e","display_name":"Other","decimals":1}],
            "custom":{"future":true},"child_names":["Keep"]}""").copy(
            appearance = """{"alpha":0.4}""", conditions = """{"rule":"keep"}""",
            enabled = false, collapsedSensorIds = """["sensor.legacy"]""",
            colorRules = """[{"entity_id":"sensor.c","color_hex":"#FF0000"}]""",
            fontSize = 27, icon = "custom-icon", sourceType = "mqtt")
        manager.saveTiles(listOf(old))
        val activity = open()
        get<EditText>(activity, "etTitle").setText("Renamed")
        val stored = save(activity)
        assertEquals(old.copy(title = "Renamed", config = stored.config), stored)
        val json = JSONObject(stored.config)
        val original = JSONObject(old.config)
        for (key in listOf("entity_id", "collapsed_sensor_configs", "expanded_sensor_configs",
            "widget_height", "custom", "child_names")) {
            assertEquals(key, original.get(key).toString(), json.get(key).toString())
        }
    }

    @Test fun clearingCommonChoicePreservesIndependentDisplayConfigsAndLegacyCollapsedIds() {
        val old = tile("""{"entity_id":"sensor.old","button_size":160,
            "collapsed_sensor_configs":[{"entity_id":"sensor.c","display_name":"Keep","decimals":2}],
            "expanded_sensor_configs":[{"entity_id":"sensor.e","display_name":"Also keep","decimals":1}],
            "future_key":[1,2]}""").copy(collapsedSensorIds = """["sensor.legacy"]""",
            colorRules = "keep-colors", fontSize = 24, sourceType = "mqtt")
        manager.saveTiles(listOf(old))
        val activity = open()
        choose(activity, emptyList())
        val stored = save(activity)
        assertEquals(old.copy(config = stored.config), stored)
        val json = JSONObject(stored.config)
        assertFalse(json.has("entity_id"))
        assertFalse(json.has("entity_ids"))
        for (key in listOf("collapsed_sensor_configs", "expanded_sensor_configs", "future_key")) {
            assertEquals(JSONObject(old.config).get(key).toString(), json.get(key).toString())
        }
    }

    @Test fun leavingWithoutSaveDoesNotPersistPendingClear() {
        val old = tile()
        manager.saveTiles(listOf(old))
        val activity = open()
        choose(activity, emptyList())
        activity.finish()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(old, manager.loadTiles().single())
        assertEquals(listOf("sensor.old"), get<List<String>>(open(), "selectedSensors"))
    }

    @Test fun resultOkWithoutSelectionExtraDoesNotMeanClear() {
        manager.saveTiles(listOf(tile()))
        val activity = open()
        result(activity, Activity.RESULT_OK, Intent())
        assertEquals(listOf("sensor.old"), get<List<String>>(activity, "selectedSensors"))
        assertEquals("sensor.old", JSONObject(save(activity).config).getString("entity_id"))
    }

    @Test fun commonSelectionClearsForEachTypeUsingThisSettingsPath() {
        for (type in listOf("sensor", "button", "group", "battery", "weather")) {
            val old = tile().copy(type = type,
                container = if (type == "button" || type == "group") "bottom_panel" else "grid")
            manager.saveTiles(listOf(old))
            val activity = open()
            choose(activity, emptyList())
            val stored = save(activity)
            assertEquals(type, stored.type)
            assertFalse(JSONObject(stored.config).has("entity_id"))
            assertFalse(JSONObject(stored.config).has("entity_ids"))
        }
    }

    @Test fun confirmedSingleGroupMemberUsesArrayConsumedByGroupControls() {
        val old = tile("""{"entity_ids":["switch.old1","switch.old2"],
            "child_names":["First","Second"],"button_size":160}""")
            .copy(type = "group", container = "bottom_panel")
        manager.saveTiles(listOf(old))
        val activity = open()
        choose(activity, listOf("switch.new"))
        val json = JSONObject(save(activity).config)
        assertFalse(json.has("entity_id"))
        assertEquals("""["switch.new"]""", json.getJSONArray("entity_ids").toString())
        // Имя принадлежит устройству: новое устройство не наследует чужое имя, и имён столько же, сколько кнопок.
        assertEquals("""["1"]""", json.getJSONArray("child_names").toString())
    }

    @Test fun groupMultipleSelectionKeepsNamesOfRemainingDevicesInNewOrder() {
        val old = tile("""{"entity_ids":["switch.old1","switch.old2"],
            "child_names":["First","Second"],"button_size":160}""")
            .copy(type = "group", container = "bottom_panel")
        manager.saveTiles(listOf(old))
        val activity = open()
        choose(activity, listOf("switch.old2", "switch.new1"))
        val json = JSONObject(save(activity).config)
        assertEquals("""["switch.old2","switch.new1"]""", json.getJSONArray("entity_ids").toString())
        assertEquals("""["Second","2"]""", json.getJSONArray("child_names").toString())
        assertEquals(160, json.getInt("button_size"))
    }

    @Test fun confirmedEmptyPickerChoiceDoesNotRestoreOldSourceAfterSaveAndReload() {
        manager.saveTiles(listOf(tile()))
        val activity = open()
        choose(activity, emptyList())
        val stored = save(activity)
        assertFalse(JSONObject(stored.config).has("entity_id"))
        assertFalse(JSONObject(stored.config).has("entity_ids"))
        val reopened = open()
        val field = TileSettingsActivity::class.java.getDeclaredField("selectedSensors").apply { isAccessible = true }
        assertEquals(emptyList<String>(), field.get(reopened))
    }
    @Test fun malformedConfigDoesNotLeakIntoLogs() {
        org.robolectric.shadows.ShadowLog.clear()
        manager.saveTiles(listOf(tile(config="{FAKE-PRIVATE-CONFIG")))
        open()
        val logs=org.robolectric.shadows.ShadowLog.getLogs().joinToString { it.msg }
        assertFalse(logs.contains("FAKE-PRIVATE-CONFIG"))
    }
}
