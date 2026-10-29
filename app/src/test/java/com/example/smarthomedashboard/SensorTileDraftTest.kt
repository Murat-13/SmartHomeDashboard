package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.ColorRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SensorTileDraftTest {
    private fun tile(config: String = "{}", rules: String = "[]") = TileEntity(
        "tile", "sensor", "grid", "Original", 20, 40, 1, 1, config = config,
        colorRules = rules, appearance = "{\"custom\":true}", conditions = "{\"custom\":true}",
        fontSize = 21, icon = "custom", sourceType = "custom"
    )
    private fun row(id: String, name: String = id, decimals: Int = 0) = SensorTileDraft.Row(SensorConfig(id, name, decimals))

    @Test fun openingAndSavingDoesNotNormalizeExistingConfigurationOrRules() {
        val original = tile("""{ "entity_id":"sensor.one", "entity_ids":["sensor.old"],
            "collapsed_sensor_configs":[{"entity_id":"sensor.a","display_name":"A","decimals":7,"extra":42}],
            "expanded_sensor_configs":[{"entity_id":"sensor.b","display_name":"","decimals":3}],"unknown":{"keep":true} }""",
            """[ {"entity_id":"sensor.hidden","from":null,"to":"10","color_hex":"#FF0000","extra":1} ]""")
        val draft = SensorTileDraft(original)
        assertNull(draft.loadError)
        assertFalse(draft.isDirty)
        assertFalse(draft.followsCompact)
        assertEquals(original, draft.applyTo(original))
        val restored = SensorTileDraft.restore(draft.saveState())
        assertFalse(restored.isDirty)
        assertEquals(original, restored.applyTo(original))
    }

    @Test fun newSelectionAutomaticallyPopulatesBothModesAndKeepsNamesAndPrecision() {
        val original = tile()
        val draft = SensorTileDraft(original)
        draft.add(listOf("sensor.voltage", "sensor.temp"), false) { "Friendly $it" }
        draft.compact[0] = row("sensor.voltage", "Напряжение", 2)
        val saved = draft.applyTo(original)
        assertEquals(listOf("sensor.voltage", "sensor.temp"), SensorConfigResolver.getCollapsedSensorIds(saved))
        assertEquals(SensorConfigResolver.getCollapsedSensorIds(saved), SensorConfigResolver.getExpandedSensorIds(saved))
        assertEquals(SensorConfigResolver.loadSensorConfigs(saved, false), SensorConfigResolver.loadSensorConfigs(saved, true))
        assertEquals("Напряжение", SensorConfigResolver.loadSensorConfigs(saved, true)[0].displayName)
        assertEquals(2, SensorConfigResolver.loadSensorConfigs(saved, true)[0].decimals)
        assertEquals("Friendly sensor.temp", SensorConfigResolver.loadSensorConfigs(saved, true)[1].displayName)
    }

    @Test fun clearingMainListDoesNotResurrectLegacySources() {
        val original = tile("""{"entity_id":"sensor.old","entity_ids":["sensor.old"]}""")
        val draft = SensorTileDraft(original)
        draft.compact.clear()
        val saved = draft.applyTo(original)
        assertEquals(emptyList<String>(), SensorConfigResolver.getCollapsedSensorIds(saved))
        assertEquals(emptyList<String>(), SensorConfigResolver.getExpandedSensorIds(saved))
        assertEquals(original.config, """{"entity_id":"sensor.old","entity_ids":["sensor.old"]}""")
    }

    @Test fun independentExpandedRowsAndHiddenRuleSurviveCompactRemoval() {
        val original = tile("""{"collapsed_sensor_configs":[{"entity_id":"sensor.a","display_name":"A","decimals":1}],
            "expanded_sensor_configs":[{"entity_id":"sensor.b","display_name":"B","decimals":2}],"widget_height":319,"extra":"kept"}""",
            """[{"entity_id":"sensor.a","from":"10","to":"20","color_hex":"#FF0000"}]""")
        val draft = SensorTileDraft(original)
        draft.compact.clear()
        val saved = draft.applyTo(original)
        assertEquals(listOf("sensor.b"), SensorConfigResolver.getExpandedSensorIds(saved))
        assertEquals(original.colorRules, saved.colorRules)
        assertEquals(319, JSONObject(saved.config).getInt("widget_height"))
        assertEquals("kept", JSONObject(saved.config).getString("extra"))
        assertEquals(original.copy(config = saved.config), saved)
    }

    @Test fun switchingToSharedDetailedModeIsExplicitAndDoesNotLoseStoredCustomDraft() {
        val original = tile("""{"collapsed_sensor_configs":[{"entity_id":"sensor.a"}],"expanded_sensor_configs":[{"entity_id":"sensor.b"}]}""")
        val draft = SensorTileDraft(original)
        draft.followsCompact = true
        assertEquals(listOf("sensor.a"), SensorConfigResolver.getExpandedSensorIds(draft.applyTo(original)))
        draft.followsCompact = false
        assertEquals(listOf("sensor.b"), SensorConfigResolver.getExpandedSensorIds(draft.applyTo(original)))
        assertFalse(draft.isDirty)
    }

    @Test fun ruleReorderKeepsEveryFieldAndLastMatchPriority() {
        val original = tile(rules = """[{"entity_id":"sensor.hidden","from":"0","to":"100","color_hex":"#FF0000","meta":1},
            {"entity_id":"sensor.hidden","from":"0","to":"100","color_hex":"#00FF00","meta":2}]""")
        val draft = SensorTileDraft(original)
        draft.rules.add(draft.rules.removeAt(0))
        val saved = draft.applyTo(original)
        val rules = org.json.JSONArray(saved.colorRules)
        assertEquals(2, rules.getJSONObject(0).getInt("meta"))
        assertEquals(1, rules.getJSONObject(1).getInt("meta"))
        val frame = android.widget.FrameLayout(org.robolectric.RuntimeEnvironment.getApplication())
        SensorBackgroundRenderer.render(saved, frame) { "50" }
        assertEquals(android.graphics.Color.RED, (frame.background as android.graphics.drawable.ColorDrawable).color)
    }

    @Test fun restoreKeepsUnsavedNamesOrderRulesAndCustomMetadataWithoutSavingAnything() {
        val original = tile("""{"collapsed_sensor_configs":[{"entity_id":"sensor.a","display_name":"A","extra":1},
            {"entity_id":"sensor.a","display_name":"A","extra":2}]}""")
        val draft = SensorTileDraft(original)
        draft.compact.add(0, draft.compact.removeAt(1))
        draft.compact[1] = draft.compact[1].copy(value = draft.compact[1].value.copy(displayName = "Changed", decimals = 2))
        draft.rules += SensorTileDraft.Rule(ColorRule("sensor.hidden", "-5.5", "0", "#8000FF00"))
        draft.fontSizeText = "28"
        val restored = SensorTileDraft.restore(draft.saveState())
        val saved = restored.applyTo(original)
        val array = JSONObject(saved.config).getJSONArray("collapsed_sensor_configs")
        assertEquals(2, array.getJSONObject(0).getInt("extra"))
        assertEquals(1, array.getJSONObject(1).getInt("extra"))
        assertEquals("Changed", array.getJSONObject(1).getString("display_name"))
        assertEquals(28, saved.fontSize)
        assertEquals("sensor.hidden", org.json.JSONArray(saved.colorRules).getJSONObject(0).getString("entity_id"))
        assertEquals(21, original.fontSize)
        assertEquals("[]", original.colorRules)
    }

    @Test fun malformedConfigurationOrRulesCannotBeSilentlyReplaced() {
        for (original in listOf(tile("{broken"), tile(rules = "[broken"), tile("""{"collapsed_sensor_configs":[42]}"""))) {
            val draft = SensorTileDraft(original)
            assertNotNull(draft.loadError)
            assertThrows(IllegalStateException::class.java) { draft.applyTo(original) }
        }
    }

    @Test fun invalidFontBlocksSaveAndOriginalNonstandardFontIsNotClamped() {
        val original = tile().copy(fontSize = 300)
        val draft = SensorTileDraft(original)
        assertEquals(original, draft.applyTo(original))
        draft.fontSizeText = "wrong"
        assertThrows(IllegalArgumentException::class.java) { draft.applyTo(original) }
        draft.fontSizeText = "201"
        assertThrows(IllegalArgumentException::class.java) { draft.applyTo(original) }
    }
}
