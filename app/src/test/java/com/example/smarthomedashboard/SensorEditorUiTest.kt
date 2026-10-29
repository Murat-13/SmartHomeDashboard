package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.*
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Настоящая XML-форма и редактор; все показания искусственные, соединений нет. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SensorEditorUiTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)
    private val owner = MainViewModel()
    private val screens = mutableListOf<ActivityController<TileSettingsActivity>>()
    @Before fun prepare() {
        context.getSharedPreferences("tiles_prefs", 0).edit().clear().commit()
        context.getSharedPreferences("dashboard_prefs", 0).edit().clear().commit()
        TileEditorPreviewSource.attach(owner)
    }
    @After fun cleanup() {
        screens.forEach { it.stop().destroy() }
        owner.disconnectWebSocket()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun tile() = TileEntity("existing", "sensor", "grid", "Original", 20, 40, 1, 1,
        config = """{"collapsed_sensor_configs":[{"entity_id":"sensor.a","display_name":"A","decimals":1}],"expanded_sensor_configs":[{"entity_id":"sensor.b","display_name":"B","decimals":2}],"widget_height":319}""",
        colorRules = """[{"entity_id":"sensor.hidden","from":"20","to":"30","color_hex":"#FF0000"}]""")
    private fun open(id: String? = null, saved: Bundle? = null): TileSettingsActivity {
        val controller = Robolectric.buildActivity(TileSettingsActivity::class.java,
            Intent(context, TileSettingsActivity::class.java).putExtra("tile_id", id))
        controller.get().setTheme(R.style.Theme_SmartHomeDashboard)
        controller.create(saved).start()
        if (saved != null) controller.restoreInstanceState(saved)
        screens += controller
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }
    private fun editor(a: TileSettingsActivity): SensorTileEditor =
        TileSettingsActivity::class.java.getDeclaredField("sensorEditor").apply { isAccessible = true }.get(a) as SensorTileEditor
    private fun <T : View> tag(a: TileSettingsActivity, key: String): T = a.findViewById<View>(android.R.id.content).findViewWithTag(key)
    private fun choose(a: TileSettingsActivity, ids: List<String>?, code: Int? = null) {
        val requestCode = code ?: shadowOf(a).nextStartedActivityForResult.requestCode
        assertTrue(a.activityResultRegistry.dispatchResult(requestCode,
            if (ids == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            ids?.let { Intent().putStringArrayListExtra("selected_sensors", ArrayList(it)) }))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun allText(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { allText(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun choosingOneSourceShowsPreviewAndSavesBothModesInOneAction() {
        owner.onEntityStateChanged("sensor.voltage", "230", JSONObject().put("friendly_name", "Напряжение").put("unit_of_measurement", "V"))
        val a = open()
        a.findViewById<EditText>(R.id.etTileTitle).setText("New")
        tag<Button>(a, "add-main").performClick()
        choose(a, listOf("sensor.voltage"))
        assertTrue(manager.loadTiles().isEmpty())
        assertTrue(allText(tag<FrameLayout>(a, "sensor-preview")).any { it.contains("230") })
        assertTrue(allText(tag<FrameLayout>(a, "sensor-preview")).any { it.contains("Напряжение") })
        assertSame(a.findViewById<LinearLayout>(R.id.sensorEditorFooter), a.findViewById<Button>(R.id.btnSave).parent)
        a.findViewById<Button>(R.id.btnSave).performClick()
        assertTrue(a.isFinishing)
        val stored = manager.loadTiles().single()
        assertEquals(listOf("sensor.voltage"), SensorConfigResolver.getCollapsedSensorIds(stored))
        assertEquals(listOf("sensor.voltage"), SensorConfigResolver.getExpandedSensorIds(stored))
    }

    @Test fun removingDisplayedSourceDoesNotRemoveIndependentRuleOrDetailedRows() {
        val old = tile()
        manager.saveTiles(listOf(old))
        val a = open(old.id)
        tag<Button>(a, "remove-main-0").performClick()
        assertEquals(old, manager.loadTiles().single())
        a.findViewById<Button>(R.id.btnSave).performClick()
        val stored = manager.loadTiles().single()
        assertEquals(old.colorRules, stored.colorRules)
        assertEquals(listOf("sensor.b"), SensorConfigResolver.getExpandedSensorIds(stored))
        assertEquals(emptyList<String>(), SensorConfigResolver.getCollapsedSensorIds(stored))
        assertEquals(old.copy(config = stored.config), stored)
    }

    @Test fun colorTrialUsesProductionMatcherAndDoesNotChangeRealStateOrPreferences() {
        val old = tile()
        manager.saveTiles(listOf(old))
        owner.onEntityStateChanged("sensor.hidden", "unknown")
        val a = open(old.id)
        val preview = tag<FrameLayout>(a, "sensor-preview")
        assertEquals(Color.parseColor("#80333333"), (preview.background as ColorDrawable).color)
        tag<androidx.appcompat.widget.SwitchCompat>(a, "simulate").isChecked = true
        tag<EditText>(a, "simulation-value").setText("25 V")
        assertEquals(Color.RED, (preview.background as ColorDrawable).color)
        tag<EditText>(a, "simulation-value").setText("unavailable")
        assertEquals(Color.parseColor("#80333333"), (preview.background as ColorDrawable).color)
        assertEquals("unknown", owner.uiState.value.singleStates["sensor.hidden"])
        assertEquals(old, manager.loadTiles().single())
    }

    @Test fun cancelAndAdvancedNavigationNeverCreateOrModifyASensorTile() {
        val a = open()
        a.findViewById<EditText>(R.id.etTileTitle).setText("Unsaved")
        tag<Button>(a, "add-main").performClick()
        choose(a, listOf("sensor.a"))
        a.findViewById<Button>(R.id.btnAdvanced).performClick()
        assertTrue(manager.loadTiles().isEmpty())
        a.findViewById<Button>(R.id.btnSensorCancel).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(a.isFinishing)
        assertTrue(manager.loadTiles().isEmpty())
    }

    @Test fun titleAndRulesChangedTogetherRemainDraftUntilSave() {
        val old = tile()
        manager.saveTiles(listOf(old))
        val a = open(old.id)
        a.findViewById<EditText>(R.id.etTileTitle).setText("Renamed")
        tag<EditText>(a, "rule-from-0").setText("-5.5")
        tag<EditText>(a, "rule-to-0").setText("0")
        tag<EditText>(a, "font-size").setText("24")
        assertEquals(old, manager.loadTiles().single())
        a.findViewById<Button>(R.id.btnSave).performClick()
        val saved = manager.loadTiles().single()
        assertEquals("Renamed", saved.title)
        assertEquals(24, saved.fontSize)
        assertEquals("-5.5", org.json.JSONArray(saved.colorRules).getJSONObject(0).getString("from"))
        assertEquals("sensor.hidden", org.json.JSONArray(saved.colorRules).getJSONObject(0).getString("entity_id"))
    }

    @Test fun staleEditorCannotOverwriteAChangedOrDeletedTile() {
        for (deleted in listOf(false, true)) {
            val old = tile()
            manager.saveTiles(listOf(old))
            val a = open(old.id)
            a.findViewById<EditText>(R.id.etTileTitle).setText("Stale")
            val latest = old.copy(title = "Other screen")
            manager.saveTiles(if (deleted) emptyList() else listOf(latest))
            a.findViewById<Button>(R.id.btnSave).performClick()
            assertFalse(a.isFinishing)
            assertEquals(if (deleted) emptyList<TileEntity>() else listOf(latest), manager.loadTiles())
        }
    }

    @Test fun rotationKeepsUnsavedRowsAndPendingRulePickerWithoutWritingPrefs() {
        val old = tile()
        manager.saveTiles(listOf(old))
        val a = open(old.id)
        a.findViewById<EditText>(R.id.etTileTitle).setText("Unsaved title")
        tag<EditText>(a, "rule-from-0").setText("-7")
        tag<Button>(a, "add-main").performClick()
        choose(a, listOf("sensor.added"))
        tag<Button>(a, "rule-source-0").performClick()
        val pendingCode = shadowOf(a).nextStartedActivityForResult.requestCode
        val bundle = Bundle()
        val controller = screens.removeAt(screens.lastIndex)
        controller.saveInstanceState(bundle).stop().destroy()
        val recreated = open(old.id, bundle)
        choose(recreated, listOf("sensor.new_hidden"), pendingCode)
        assertEquals(old, manager.loadTiles().single())
        assertEquals("Unsaved title", recreated.findViewById<EditText>(R.id.etTileTitle).text.toString())
        assertEquals(listOf("sensor.a", "sensor.added"), editor(recreated).draft.compact.map { it.value.entityId })
        assertEquals("sensor.new_hidden", editor(recreated).draft.rules.single().value.entityId)
        assertEquals("-7", editor(recreated).draft.rules.single().value.from)
    }

    @Test fun unrelatedEntityDoesNotRebuildPreviewAndStoppedScreenDoesNotObserve() {
        val old = tile()
        manager.saveTiles(listOf(old))
        val a = open(old.id)
        val preview = tag<FrameLayout>(a, "sensor-preview")
        val content = preview.getChildAt(0) as LinearLayout
        val row = content.getChildAt(1)
        owner.onEntityStateChanged("sensor.unrelated", "1")
        shadowOf(Looper.getMainLooper()).idle()
        // Первый переход из полностью пустого состояния обновляет только подсказку доступности.
        val afterFirst = preview.background
        owner.onEntityStateChanged("sensor.unrelated", "2")
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(row, content.getChildAt(1))
        assertSame(afterFirst, preview.background)
        screens.single().stop()
        val before = allText(preview)
        owner.onEntityStateChanged("sensor.a", "999")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, allText(preview))
        screens.single().start()
    }

    @Test fun newEmptyCardCannotBeSavedAndLegacyWidgetTypesStillUseTheirOwnControls() {
        val a = open()
        a.findViewById<EditText>(R.id.etTileTitle).setText("Empty")
        a.findViewById<Button>(R.id.btnSave).performClick()
        assertFalse(a.isFinishing)
        assertTrue(manager.loadTiles().isEmpty())
        val spinner = a.findViewById<Spinner>(R.id.spinnerTileType)
        spinner.setSelection(1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, a.findViewById<View>(R.id.sensorEditorContainer).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.legacySourceButtons).visibility)
        assertEquals(View.VISIBLE, a.findViewById<Button>(R.id.btnAdvanced).visibility)
    }

    @Test fun replacingRowSourcePreservesItsCaptionPrecisionAndIndependentRule() {
        val old = tile()
        manager.saveTiles(listOf(old))
        val a = open(old.id)
        tag<View>(a, "edit-main-0").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.window!!.decorView.findViewWithTag<EditText>("row-name").setText("My voltage")
        dialog.window!!.decorView.findViewWithTag<EditText>("row-decimals").setText("3")
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        choose(a, listOf("sensor.replacement"))
        assertEquals("My voltage", editor(a).draft.compact.single().value.displayName)
        assertEquals(3, editor(a).draft.compact.single().value.decimals)
        assertEquals("sensor.replacement", editor(a).draft.compact.single().value.entityId)
        assertEquals("sensor.hidden", editor(a).draft.rules.single().value.entityId)
        assertEquals(old, manager.loadTiles().single())
        a.findViewById<Button>(R.id.btnSave).performClick()
        assertEquals("sensor.replacement", SensorConfigResolver.getCollapsedSensorIds(manager.loadTiles().single()).single())
    }

    @Test fun customTransparentColorIsValidatedAndNotSavedBeforeOverallSave() {
        val old = tile()
        manager.saveTiles(listOf(old))
        val a = open(old.id)
        tag<Button>(a, "rule-color-0").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = dialog.window!!.decorView.findViewWithTag<EditText>("color-hex")
        input.setText("wrong")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertNotNull(input.error)
        assertEquals("#FF0000", editor(a).draft.rules.single().value.colorHex)
        input.setText("#CC123456")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("#CC123456", editor(a).draft.rules.single().value.colorHex)
        assertEquals(old, manager.loadTiles().single())
        a.findViewById<Button>(R.id.btnSave).performClick()
        assertEquals("#CC123456", org.json.JSONArray(manager.loadTiles().single().colorRules).getJSONObject(0).getString("color_hex"))
    }

    @Test fun reorderingTextRulesChangesLastMatchWithoutChangingTheMatchingAlgorithm() {
        val old = tile().copy(colorRules = """[{"entity_id":"sensor.hidden","from":"on","to":"on","color_hex":"#FF0000"},
            {"entity_id":"sensor.hidden","from":"on","to":"on","color_hex":"#00FF00"}]""")
        manager.saveTiles(listOf(old))
        owner.onEntityStateChanged("sensor.hidden", "on")
        val a = open(old.id)
        val preview = tag<FrameLayout>(a, "sensor-preview")
        assertEquals(Color.GREEN, (preview.background as ColorDrawable).color)
        tag<Button>(a, "move-rule-1--1").performClick()
        assertEquals(Color.RED, (preview.background as ColorDrawable).color)
        assertEquals(old, manager.loadTiles().single())
        a.findViewById<Button>(R.id.btnSave).performClick()
        val rules = org.json.JSONArray(manager.loadTiles().single().colorRules)
        assertEquals("#FF0000", rules.getJSONObject(1).getString("color_hex"))
    }

    @Test fun rotationPreservesLocalColorTrialWithoutTurningItIntoRealData() {
        val old = tile()
        manager.saveTiles(listOf(old))
        owner.onEntityStateChanged("sensor.hidden", "unknown")
        val a = open(old.id)
        tag<androidx.appcompat.widget.SwitchCompat>(a, "simulate").isChecked = true
        tag<EditText>(a, "simulation-value").setText("25 V")
        val bundle = Bundle()
        screens.removeAt(screens.lastIndex).saveInstanceState(bundle).stop().destroy()
        val next = open(old.id, bundle)
        assertTrue(tag<androidx.appcompat.widget.SwitchCompat>(next, "simulate").isChecked)
        assertEquals("25 V", tag<EditText>(next, "simulation-value").text.toString())
        assertEquals(Color.RED, (tag<FrameLayout>(next, "sensor-preview").background as ColorDrawable).color)
        assertEquals("unknown", owner.uiState.value.singleStates["sensor.hidden"])
        assertEquals(old, manager.loadTiles().single())
    }

    @Test fun switchingEveryLegacyTypeRestoresItsControlsAndNeverDuplicatesSaveButton() {
        val a = open()
        val spinner = a.findViewById<Spinner>(R.id.spinnerTileType)
        val button = a.findViewById<Button>(R.id.btnSave)
        repeat(2) {
            for (type in 1..5) {
                spinner.setSelection(type)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(View.GONE, a.findViewById<View>(R.id.sensorEditorFooter).visibility)
                assertEquals(View.VISIBLE, a.findViewById<Button>(R.id.btnAdvanced).visibility)
                assertTrue(button.parent !== a.findViewById<View>(R.id.sensorEditorFooter))
            }
            spinner.setSelection(0)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(a.findViewById<View>(R.id.sensorEditorFooter), button.parent)
            assertEquals(2, a.findViewById<LinearLayout>(R.id.sensorEditorFooter).childCount)
        }
        assertTrue(manager.loadTiles().isEmpty())
    }

    @Test fun cancelingSourcePickerDoesNotChangeExistingRule() {
        val old = tile()
        manager.saveTiles(listOf(old))
        val a = open(old.id)
        tag<Button>(a, "rule-source-0").performClick()
        choose(a, null)
        assertFalse(editor(a).draft.isDirty)
        assertEquals(old, manager.loadTiles().single())
    }

    @Test fun openingAndSavingActualEditorKeepsAllUntouchedLegacySettingsExactly() {
        val old = tile().copy(fontSize = 27, appearance = "{\"kept\":1}", sourceType = "legacy", enabled = false)
        manager.saveTiles(listOf(old))
        val a = open(old.id)
        assertFalse(editor(a).draft.isDirty)
        a.findViewById<Button>(R.id.btnSave).performClick()
        assertEquals(old, manager.loadTiles().single())
    }

    @Test fun cancelingNewRuleSourceRemovesOnlyItsProvisionalEmptyRule() {
        val old = tile()
        manager.saveTiles(listOf(old))
        val a = open(old.id)
        tag<Button>(a, "add-rule").performClick()
        assertEquals(2, editor(a).draft.rules.size)
        choose(a, null)
        assertEquals(1, editor(a).draft.rules.size)
        assertFalse(editor(a).draft.isDirty)
        assertEquals(old, manager.loadTiles().single())
    }

    @Test fun invalidLegacyPrecisionDoesNotCrashEditorOrSilentlyRewriteTheTile() {
        val old = tile().copy(config = """{"collapsed_sensor_configs":[{"entity_id":"sensor.a","display_name":"A","decimals":-2}]}""")
        manager.saveTiles(listOf(old))
        owner.onEntityStateChanged("sensor.a", "23.5")
        val a = open(old.id)
        assertFalse(editor(a).draft.isDirty)
        assertTrue(allText(a.findViewById(android.R.id.content)).any { it.contains("формат строки не поддерживается") })
        a.findViewById<Button>(R.id.btnSave).performClick()
        assertEquals(old, manager.loadTiles().single())
    }

    private fun dragEvent(action: Int, source: Any?): android.view.DragEvent {
        // SDK скрывает фабрику DragEvent. Тест создаёт событие, не вызывая скрытых API в production.
        val description = android.content.ClipDescription("Показание", arrayOf("application/x-smarthomedashboard-row"))
        return android.view.DragEvent::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance().also {
            org.robolectric.util.ReflectionHelpers.setField(it, "mAction", action)
            org.robolectric.util.ReflectionHelpers.setField(it, "mX", 10f)
            org.robolectric.util.ReflectionHelpers.setField(it, "mY", 10f)
            org.robolectric.util.ReflectionHelpers.setField(it, "mLocalState", source)
            org.robolectric.util.ReflectionHelpers.setField(it, "mClipDescription", description)
            org.robolectric.util.ReflectionHelpers.setField(it, "mClipData", android.content.ClipData(description, android.content.ClipData.Item("")))
        }
    }
    private fun recycleDrag(event: android.view.DragEvent) {
        android.view.DragEvent::class.java.getDeclaredMethod("recycle").apply { isAccessible = true }.invoke(event)
    }

    @Test fun draggingRowsPreservesTheirSettingsRulesAndSeparateDetailedList() {
        val old = tile().copy(config = """{"collapsed_sensor_configs":[{"entity_id":"sensor.a","display_name":"A","decimals":1},
            {"entity_id":"sensor.c","display_name":"C","decimals":3}],"expanded_sensor_configs":[{"entity_id":"sensor.b","display_name":"B","decimals":2}]}""")
        manager.saveTiles(listOf(old))
        val a = open(old.id)
        screens.last().visible()
        shadowOf(Looper.getMainLooper()).idle()
        val target = tag<View>(a, "row-main-1")
        val drag = SensorTileEditor.RowDrag("main", 0)
        fun event(action: Int) = dragEvent(action, drag)
        val start = event(android.view.DragEvent.ACTION_DRAG_STARTED)
        val drop = event(android.view.DragEvent.ACTION_DROP)
        try {
            assertTrue(target.dispatchDragEvent(start))
            assertTrue(target.dispatchDragEvent(drop))
        } finally { recycleDrag(start); recycleDrag(drop) }
        val draft = editor(a).draft
        assertEquals(listOf("sensor.c", "sensor.a"), draft.compact.map { it.value.entityId })
        assertEquals(listOf(3, 1), draft.compact.map { it.value.decimals })
        assertEquals(listOf("sensor.b"), draft.detailed.map { it.value.entityId })
        assertEquals(old, manager.loadTiles().single())
        a.findViewById<Button>(R.id.btnSave).performClick()
        assertEquals(old.colorRules, manager.loadTiles().single().colorRules)
    }

    @Test fun foreignAndCrossListDragCannotChangeRows() {
        val old = tile()
        manager.saveTiles(listOf(old))
        val a = open(old.id)
        screens.last().visible()
        shadowOf(Looper.getMainLooper()).idle()
        val target = tag<View>(a, "row-main-0")
        for (source in listOf(null, SensorTileEditor.RowDrag("detail", 0))) {
            val event = dragEvent(android.view.DragEvent.ACTION_DROP, source)
            try { assertFalse(target.dispatchDragEvent(event)) } finally { recycleDrag(event) }
        }
        assertFalse(editor(a).draft.isDirty)
        assertEquals(old, manager.loadTiles().single())
    }

    @Test fun destroyingEditorDismissesItsOpenDialogsWithoutSavingAnything() {
        val old = tile()
        manager.saveTiles(listOf(old))
        val a = open(old.id)
        tag<Button>(a, "rule-color-0").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        screens.removeAt(screens.lastIndex).stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(old, manager.loadTiles().single())
    }
}
