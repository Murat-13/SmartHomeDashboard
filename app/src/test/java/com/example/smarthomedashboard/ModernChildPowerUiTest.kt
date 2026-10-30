package com.example.smarthomedashboard

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.Switch
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ModernChildPowerUiTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)
    private val owner = MainViewModel()
    private val screens = mutableListOf<ActivityController<ChildButtonsEditorActivity>>()
    private lateinit var original: TileEntity
    @Before fun prepare() {
        context.getSharedPreferences("dashboard_prefs", 0).edit().clear().commit()
        TileEditorPreviewSource.attach(owner)
        owner.onEntityStateChanged("sensor.child_power", "648", JSONObject().put("unit_of_measurement", "W"))
        original = TileEntity("power-group", "group", "bottom_panel", "Group", 0, 0, 1, 1,
            config = """{"button_style":"modern","modern_icon":"gate","entity_ids":["switch.a","switch.b"],"child_names":["A","B"]}""")
        manager.saveTiles(listOf(original))
    }
    @After fun cleanup() { screens.forEach { it.stop().destroy() }; owner.disconnectWebSocket() }
    private fun open(saved: Bundle? = null): ChildButtonsEditorActivity {
        val controller = Robolectric.buildActivity(ChildButtonsEditorActivity::class.java,
            Intent(context, ChildButtonsEditorActivity::class.java).putExtra("tile_id", original.id))
        controller.get().setTheme(R.style.Theme_SmartHomeDashboard)
        controller.create(saved).start()
        if (saved != null) controller.restoreInstanceState(saved)
        screens += controller
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(a: ChildButtonsEditorActivity, key: String): T =
        ChildButtonsEditorActivity::class.java.getDeclaredField(key).apply { isAccessible = true }.get(a) as T
    private fun edit(a: ChildButtonsEditorActivity, position: Int) {
        ChildButtonsEditorActivity::class.java.getDeclaredMethod("editPower", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(a, position)
    }
    private fun configure(a: ChildButtonsEditorActivity) {
        val editor = field<ModernPowerEditor>(a, "powerEditor")
        (editor.getChildAt(1) as Switch).isChecked = true
        (editor.getChildAt(2) as AutoCompleteTextView).setText("sensor.child_power")
    }
    @Test fun childPreviewIsLiveAndCancelDoesNotSaveOrAlterAnotherChild() {
        val a = open(); edit(a, 1); configure(a)
        assertEquals("648 Вт", field<ModernTileView>(a, "powerPreview").boundModel!!.powerText)
        assertEquals(ModernIcon.GATE, field<ModernTileView>(a, "powerPreview").boundModel!!.icon)
        owner.onEntityStateChanged("sensor.child_power", "42", JSONObject().put("unit_of_measurement", "W"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("42 Вт", field<ModernTileView>(a, "powerPreview").boundModel!!.powerText)
        field<AlertDialog>(a, "powerDialog").getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(original, manager.loadTiles().single())
        assertTrue(field<List<ChildButtonData>>(a, "childItems").all { !it.showPower })
    }
    @Test fun childDraftAndOpenDialogSurviveRotationAndNeedOverallSave() {
        val a = open(); edit(a, 1); configure(a)
        val saved = Bundle()
        screens.last().saveInstanceState(saved).stop().destroy(); screens.removeAt(screens.lastIndex)
        val restored = open(saved)
        assertEquals(LivePowerConfig(true, "sensor.child_power"), field<ModernPowerEditor>(restored, "powerEditor").config)
        field<AlertDialog>(restored, "powerDialog").getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(original, manager.loadTiles().single())
        restored.findViewById<Button>(R.id.btnSave).performClick()
        val config = JSONObject(TileManager(context).loadTiles().single().config)
        val children = GroupChildren.fromConfig(config).items
        assertFalse(children[0].showPower)
        assertTrue(children[1].showPower)
        assertEquals("sensor.child_power", children[1].powerEntityId)
        assertEquals("gate", config.getString("modern_icon"))
    }
    @Test fun childCannotApplyIncompleteEnabledSettings() {
        val a = open(); edit(a, 0)
        val editor = field<ModernPowerEditor>(a, "powerEditor")
        (editor.getChildAt(1) as Switch).isChecked = true
        val dialog = field<AlertDialog>(a, "powerDialog")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertFalse(field<List<ChildButtonData>>(a, "childItems")[0].showPower)
        assertEquals(original, manager.loadTiles().single())
    }
}
