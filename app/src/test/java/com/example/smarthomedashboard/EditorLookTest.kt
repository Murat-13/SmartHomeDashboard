package com.example.smarthomedashboard

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.Spinner
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import com.google.android.material.tabs.TabLayout
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

/** Единый вид редакторов: вкладки по типу плитки и удаление Modern-плитки с подтверждением. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class EditorLookTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)

    @Before fun prepare() {
        context.getSharedPreferences("tiles_prefs", 0).edit().clear().commit()
        TileEditorPreviewSource.attach(MainViewModel())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun labels(tabs: TabLayout) = (0 until tabs.tabCount).map { tabs.getTabAt(it)!!.text.toString() }

    @Test fun tabsFollowTileTypeAndShowOneSectionAtATime() {
        val controller = Robolectric.buildActivity(TileSettingsActivity::class.java, Intent(context, TileSettingsActivity::class.java))
        controller.get().setTheme(R.style.Theme_SmartHomeDashboard_Modern)
        val editor = controller.create().start().get()
        idle()
        val tabs = editor.findViewById<TabLayout>(R.id.editorTabs)
        editor.findViewById<Spinner>(R.id.spinnerTileType).setSelection(1) // кнопка
        idle()
        assertEquals(listOf("Основное", "Вид", "Данные"), labels(tabs))
        tabs.getTabAt(2)!!.select()
        assertEquals(View.VISIBLE, editor.findViewById<View>(R.id.tabData).visibility)
        assertEquals(View.GONE, editor.findViewById<View>(R.id.tabMain).visibility)
        assertEquals(View.GONE, editor.findViewById<View>(R.id.tabLook).visibility)
        editor.findViewById<Spinner>(R.id.spinnerTileType).setSelection(4) // часы: стиля кнопки нет
        idle()
        assertEquals(listOf("Основное", "Данные"), labels(tabs))
        // Сохранить и Отмена всегда видны внизу, вне вкладок.
        assertEquals(View.VISIBLE, editor.findViewById<View>(R.id.sensorEditorFooter).visibility)
        controller.stop().destroy()
    }

    @Test fun modernEditorDeletesTileOnlyAfterConfirmation() {
        manager.saveTiles(listOf(
            TileEntity("m", BatteryModernConfig.TYPE, "grid", "АКБ", 10, 20, 1, 1, config = BatteryModernConfig.newTileConfig("{}")),
            TileEntity("other", "sensor", "grid", "Соседняя", 300, 20, 1, 1)))
        val editor = Robolectric.buildActivity(BatteryModernEditorActivity::class.java,
            Intent(context, BatteryModernEditorActivity::class.java).putExtra("tile_id", "m")).create().get()
        idle()
        val delete = editor.findViewById<View>(android.R.id.content).findViewWithTag<Button>("bm_delete")
        delete.performClick()
        idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(2, manager.loadTiles().size)
        delete.performClick()
        idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(listOf("other"), manager.loadTiles().map { it.id })
        assertTrue(editor.isFinishing)
    }
}
