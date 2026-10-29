package com.example.smarthomedashboard

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.widget.SwitchCompat
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

// Экран редактора без PIN-шага (onResume не вызывается): черновик, диалоги, запись только по «Сохранить».
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PowerModernEditorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)
    private val model = MainViewModel()
    private val grid = TileEntity("grid", PowerModernConfig.TYPE, "grid", "Сеть · Вход", 10, 20, 1, 1,
        config = PowerModernConfig.newTileConfig(PowerModernConfig.PROFILE_GRID, """{"custom":"keep"}"""))
    private val inverter = TileEntity("inv", PowerModernConfig.TYPE, "grid", "Инвертор · Выход", 400, 20, 1, 1,
        config = PowerModernConfig.newTileConfig(PowerModernConfig.PROFILE_INVERTER))

    @Before fun setUp() {
        manager.saveTiles(listOf(grid, inverter))
        model.onStatesSnapshot(listOf(
            HaEntityState("sensor.grid_voltage", "228", JSONObject().put("friendly_name", "Вход напряжение")),
            HaEntityState("sensor.grid_power", "980", JSONObject().put("friendly_name", "Вход мощность")),
            HaEntityState("sensor.inv_voltage", "230", JSONObject().put("friendly_name", "Выход напряжение"))
        ))
        TileEditorPreviewSource.attach(model)
    }

    private fun open(id: String = "grid"): PowerModernEditorActivity = Robolectric.buildActivity(PowerModernEditorActivity::class.java,
        Intent(context, PowerModernEditorActivity::class.java).putExtra("tile_id", id)).create().get().also { idle() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun saved(id: String = "grid") = PowerModernConfig.from(manager.loadTiles().single { it.id == id }.config)
    private fun AlertDialog.view(tag: String): View = window!!.decorView.findViewWithTag(tag)
    private fun latest() = (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).also { idle() }
    private fun ok(dialog: AlertDialog) = dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

    private fun pick(activity: PowerModernEditorActivity, entityId: String) {
        val dialog = checkNotNull(activity.openRowDialog).also { idle() }
        (dialog.view("bm_row_pick") as Button).performClick()
        val picker = checkNotNull(activity.openPicker)
        (picker.view("bm_pick_search") as EditText).setText(entityId.substringAfter('.'))
        val list = picker.view("bm_pick_list") as ListView
        assertEquals(1, list.adapter.count)
        list.performItemClick(null, 0, 0)
        idle()
        ok(dialog)
    }

    @Test fun tappingVoltageAndPowerSelectsEntitiesAndSaveWritesOnlyOnConfirmation() {
        val activity = open()
        activity.editRole("voltage")
        pick(activity, "sensor.grid_voltage")
        activity.editRole("power")
        pick(activity, "sensor.grid_power")
        assertEquals("sensor.grid_voltage", activity.draft.config.row("voltage")!!.entityId)
        assertEquals("До «Сохранить» конфигурация не меняется", PowerModernConfig.profile(PowerModernConfig.PROFILE_GRID), saved())
        assertTrue(activity.save())
        assertEquals("sensor.grid_voltage", saved().row("voltage")!!.entityId)
        assertEquals("sensor.grid_power", saved().row("power")!!.entityId)
        assertEquals("keep", JSONObject(manager.loadTiles().single { it.id == "grid" }.config).getString("custom"))
        assertEquals("Вторая плитка не изменена", inverter, manager.loadTiles().single { it.id == "inv" })
    }

    @Test fun cancelKeepsWorkingTile() {
        val activity = open()
        activity.editRole("voltage")
        pick(activity, "sensor.grid_voltage")
        activity.leave()
        latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(activity.isFinishing)
        assertEquals(grid, manager.loadTiles().single { it.id == "grid" })
    }

    @Test fun extraRowsCanBeAddedEditedReorderedAndDeleted() {
        val activity = open()
        activity.findViewById<View>(android.R.id.content).findViewWithTag<Button>("pm_add_row").performClick()
        val add = checkNotNull(activity.openRowDialog).also { idle() }
        (add.view("bm_row_label") as EditText).setText("Температура инвертора")
        (add.view("bm_row_decimals") as android.widget.Spinner).setSelection(2)
        ok(add)
        val custom = activity.draft.config.extras.last()
        assertEquals("Температура инвертора", custom.label)
        assertEquals(1, custom.decimals)
        activity.editRow("energy")
        (checkNotNull(activity.openRowDialog).also { idle() }.view("bm_row_up") as Button).performClick()
        assertEquals("energy", activity.draft.config.extras[3].id)
        checkNotNull(activity.openRowDialog).dismiss()
        activity.editRow("reactive_power")
        checkNotNull(activity.openRowDialog).also { idle() }.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertNull(activity.draft.row("reactive_power"))
        assertTrue(activity.save())
        assertNull(saved().row("reactive_power"))
        assertEquals("Температура инвертора", saved().extras.last().label)
    }

    @Test fun rulesCanBeEditedAddedDeletedAndRestored() {
        val activity = open()
        val normIndex = activity.draft.config.rules.indexOfFirst { it.label == "Норма" }
        activity.ruleDialog(normIndex, activity.draft.config.rules[normIndex])
        val dialog = checkNotNull(activity.openRuleDialog).also { idle() }
        (dialog.view("pm_rule_label") as EditText).setText("Сеть есть")
        (dialog.view("pm_rule_color") as EditText).setText("#3B82F6")
        (dialog.view("pm_rule_pulse") as SwitchCompat).isChecked = true
        ok(dialog)
        val edited = activity.draft.config.rules[normIndex]
        assertEquals("Сеть есть", edited.label)
        assertEquals(0xFF3B82F6.toInt(), edited.color)
        assertTrue(edited.pulse)
        assertEquals(200.0, edited.from, 0.0)

        // Некорректный диапазон не принимается.
        activity.ruleDialog(normIndex, edited)
        val bad = checkNotNull(activity.openRuleDialog).also { idle() }
        (bad.view("pm_rule_from") as EditText).setText("250")
        (bad.view("pm_rule_to") as EditText).setText("240")
        ok(bad)
        assertTrue(bad.isShowing)
        assertEquals(edited, activity.draft.config.rules[normIndex])
        bad.dismiss()

        // Пересекающееся правило блокирует сохранение.
        activity.ruleDialog(null, PowerModernConfig.Rule(0.0, true, null, false, PowerModernConfig.YELLOW, "x", false))
        val add = checkNotNull(activity.openRuleDialog).also { idle() }
        (add.view("pm_rule_from") as EditText).setText("220")
        (add.view("pm_rule_to") as EditText).setText("230")
        (add.view("pm_rule_label") as EditText).setText("Пересечение")
        ok(add)
        assertEquals(11, activity.draft.config.rules.size)
        assertFalse(activity.save())
        assertEquals(PowerModernConfig.profile(PowerModernConfig.PROFILE_GRID), saved())

        // Удаление с подтверждением и восстановление шаблона.
        activity.ruleDialog(10, activity.draft.config.rules[10])
        checkNotNull(activity.openRuleDialog).also { idle() }.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(10, activity.draft.config.rules.size)
        activity.findViewById<View>(android.R.id.content).findViewWithTag<Button>("pm_rules_restore").performClick()
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(PowerModernConfig.defaultRules(PowerModernConfig.PROFILE_GRID), activity.draft.config.rules)
    }

    @Test fun gapsRequireExplicitConfirmationAndInclusiveFlagsAreEditable() {
        val activity = open()
        val index = activity.draft.config.rules.indexOfFirst { it.label == "Нижняя граница" }
        activity.ruleDialog(index, activity.draft.config.rules[index])
        val dialog = checkNotNull(activity.openRuleDialog).also { idle() }
        (dialog.view("pm_rule_from_incl") as CheckBox).isChecked = false
        ok(dialog)
        assertFalse(activity.save())
        val warning = latest()
        assertTrue(shadowOf(warning).message.toString().contains("ровно 190 В"))
        warning.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(saved().rules[index].fromInclusive)
    }

    @Test fun previewShowsColorStatusAndPulseWithoutHomeAssistant() {
        val activity = open()
        val expected = mapOf(0 to "Нет сети, мягкая пульсация", 145 to "Очень низкое напряжение, мягкая пульсация",
            180 to "Пониженное напряжение, без пульсации", 195 to "Нижняя граница, без пульсации",
            228 to "Норма, без пульсации", 245 to "Повышенное напряжение, без пульсации",
            255 to "Высокое напряжение, без пульсации", 285 to "Опасно высокое напряжение, мягкая пульсация")
        expected.forEach { (v, text) ->
            activity.findViewById<View>(android.R.id.content).findViewWithTag<Button>("pm_test_$v").performClick()
            assertEquals("$v В → $text", activity.testResult.text.toString())
            assertEquals("$v", activity.testCard.findViewWithTag<android.widget.TextView>("pm_voltage").text.toString())
        }
        assertEquals(PowerModernConfig.RED, PowerVisual.preview(activity.draft.config, 285.0).color)
        assertNull("Предпросмотр не создаёт соединения", model.getWebSocket())
        assertEquals("Тестовое значение не попадает в данные HA", null, model.devices.singleStates[PowerModernEditorActivity.PREVIEW_ENTITY])
        assertEquals(PowerModernConfig.profile(PowerModernConfig.PROFILE_GRID), saved())
    }

    @Test fun tilesHaveIndependentSettings() {
        val gridEditor = open("grid")
        gridEditor.editRole("voltage")
        pick(gridEditor, "sensor.grid_voltage")
        val norm = gridEditor.draft.config.rules.indexOfFirst { it.label == "Норма" }
        gridEditor.ruleDialog(norm, gridEditor.draft.config.rules[norm])
        val dialog = checkNotNull(gridEditor.openRuleDialog).also { idle() }
        (dialog.view("pm_rule_color") as EditText).setText("#A855F7")
        ok(dialog)
        assertTrue(gridEditor.save())
        val inv = saved("inv")
        assertEquals("", inv.row("voltage")!!.entityId)
        assertEquals(PowerModernConfig.CYAN, inv.rules.first { it.label == "Норма" }.color)
        assertEquals(0xFFA855F7.toInt(), saved("grid").rules.first { it.label == "Норма" }.color)
        assertEquals("Нет напряжения на выходе", inv.rules.first().label)
    }
}
