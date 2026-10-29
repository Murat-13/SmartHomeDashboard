package com.example.smarthomedashboard

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
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

// Экран редактора без PIN-шага (onResume не вызывается): только черновик, диалоги и запись по «Сохранить».
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class BatteryModernEditorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)
    private val model = MainViewModel()
    private val tile = TileEntity("m", BatteryModernConfig.TYPE, "grid", "АКБ", 10, 20, 1, 1,
        config = BatteryModernConfig.newTileConfig("""{"custom":"keep"}"""))

    @Before fun setUp() {
        manager.saveTiles(listOf(tile))
        model.onStatesSnapshot(listOf(
            HaEntityState("sensor.bms_soc", "89", JSONObject().put("friendly_name", "BMS SOC")),
            HaEntityState("sensor.bms_power", "-407.3", JSONObject().put("friendly_name", "BMS Power")),
            HaEntityState("sensor.kitchen_temp", "22", JSONObject().put("friendly_name", "Кухня"))
        ))
        TileEditorPreviewSource.attach(model)
    }

    private fun open(): BatteryModernEditorActivity = Robolectric.buildActivity(BatteryModernEditorActivity::class.java,
        Intent(context, BatteryModernEditorActivity::class.java).putExtra("tile_id", "m")).create().get()
        .also { shadowOf(Looper.getMainLooper()).idle() }

    private fun saved() = BatteryModernConfig.from(manager.loadTiles().single().config)
    private fun AlertDialog.view(tag: String): View = window!!.decorView.findViewWithTag(tag)
    private fun AlertDialog.click(tag: String) = (view(tag) as Button).performClick()
    private fun latest() = (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).also { idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    // onShow (где подключаются кнопки диалога) доставляется через главный поток.
    private fun rowDialog(activity: BatteryModernEditorActivity) = checkNotNull(activity.openRowDialog).also { idle() }

    private fun pick(activity: BatteryModernEditorActivity, dialog: AlertDialog, entityId: String) {
        dialog.click("bm_row_pick")
        val picker = checkNotNull(activity.openPicker)
        val list = picker.view("bm_pick_list") as ListView
        val search = picker.view("bm_pick_search") as EditText
        search.setText(entityId.substringAfter('.'))
        assertEquals("Поиск сужает список", 1, list.adapter.count)
        list.performItemClick(null, 0, 0)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun tappingSocZoneSelectsEntityAndSaveWritesOnlyOnConfirmation() {
        val activity = open()
        activity.editRole("soc")
        val dialog = rowDialog(activity)
        pick(activity, dialog, "sensor.bms_soc")
        (dialog.view("bm_row_label") as EditText).setText("SOC")
        (dialog.view("bm_row_unit") as EditText).setText("%")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("sensor.bms_soc", activity.draft.config.row("soc")!!.entityId)
        assertEquals("До «Сохранить» конфигурация не меняется", BatteryModernConfig(), saved())

        assertTrue(activity.save())
        val row = saved().row("soc")!!
        assertEquals("sensor.bms_soc", row.entityId)
        assertEquals("SOC", row.label)
        assertEquals("%", row.unit)
        assertEquals("keep", JSONObject(manager.loadTiles().single().config).getString("custom"))
        assertTrue(activity.isFinishing)
    }

    @Test fun everyIndicatorOpensItsOwnEditorAndPrecisionIconApply() {
        val activity = open()
        BatteryModernConfig().rows.forEachIndexed { index, template ->
            activity.editRow(template.id)
            val dialog = rowDialog(activity)
            (dialog.view("bm_row_label") as EditText).setText("${template.label} #$index")
            (dialog.view("bm_row_decimals") as android.widget.Spinner).setSelection(2)
            (dialog.view("bm_row_icon") as android.widget.Spinner).setSelection(0)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        activity.draft.config.rows.forEachIndexed { index, row ->
            assertTrue(row.label.endsWith("#$index"))
            assertEquals(1, row.decimals)
            assertEquals("battery", row.icon)
        }
        listOf("power", "runtime", "voltage", "temperature").forEach { role ->
            activity.editRole(role)
            assertTrue(rowDialog(activity).isShowing)
            rowDialog(activity).dismiss()
        }
    }

    @Test fun cancelKeepsActiveConfigurationAndAsksBeforeDiscarding() {
        val activity = open()
        activity.editRole("power")
        val dialog = rowDialog(activity)
        pick(activity, dialog, "sensor.bms_power")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(activity.draft.isDirty)
        activity.leave()
        val confirm = latest()
        assertFalse(activity.isFinishing)
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertEquals(tile, manager.loadTiles().single())
    }

    @Test fun rowsCanBeAddedReorderedHiddenAndDeletedWithConfirmation() {
        val activity = open()
        activity.findViewById<View>(android.R.id.content).findViewWithTag<Button>("bm_add_diagnostics").performClick()
        val add = rowDialog(activity)
        (add.view("bm_row_label") as EditText).setText("Сопротивление")
        add.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val custom = activity.draft.config.rows.last()
        assertEquals(BatteryModernConfig.Section.DIAGNOSTICS, custom.section)
        assertEquals("Сопротивление", custom.label)

        activity.editRow("charging")
        rowDialog(activity).click("bm_row_up")
        assertEquals(listOf("charging", "soc"), activity.draft.config.rows.take(2).map { it.id })
        (rowDialog(activity).view("bm_row_hidden") as SwitchCompat).isChecked = true
        rowDialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(activity.draft.row("charging")!!.hidden)

        activity.editRow("cycles")
        val edit = rowDialog(activity)
        edit.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        val confirm = latest()
        assertNotNull("Удаление подтверждается", activity.draft.row("cycles"))
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(activity.draft.row("cycles"))
        assertTrue(activity.save())
        assertNull(saved().row("cycles"))
        assertEquals("Сопротивление", saved().rows.last().label)
    }

    @Test fun signInversionAndPreviewUseExistingDataWithoutNewConnection() {
        val activity = open()
        activity.editRole("power")
        pick(activity, rowDialog(activity), "sensor.bms_power")
        rowDialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("Нет связи с Home Assistant", activity.preview.findViewWithTag<android.widget.TextView>("bm_status").text)
        activity.findViewById<View>(android.R.id.content).findViewWithTag<SwitchCompat>("bm_invert").isChecked = true
        assertTrue(activity.draft.config.invertPowerSign)
        assertNull("Редактор не создаёт соединения", model.getWebSocket())
        assertTrue(activity.entityChoices().any { it.first == "sensor.kitchen_temp" })
    }

    @Test fun concurrentChangeIsNotOverwritten() {
        val activity = open()
        activity.editRole("soc")
        pick(activity, rowDialog(activity), "sensor.bms_soc")
        rowDialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val other = tile.copy(title = "Изменено другим экраном")
        manager.saveTiles(listOf(other))
        assertFalse(activity.save())
        assertEquals(other, manager.loadTiles().single())
    }
}
