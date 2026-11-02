package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
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
import java.lang.reflect.InvocationTargetException

/** Один сеанс редактирования — одна запись: вложенные экраны правят черновик, «Отмена» откатывает всё. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class EditorTransactionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)
    private val owner = MainViewModel()
    private val screens = mutableListOf<ActivityController<*>>()
    private fun stored() = context.getSharedPreferences("tiles_prefs", 0).getString("tiles", null)

    @Before fun prepare() {
        context.getSharedPreferences("tiles_prefs", 0).edit().clear().commit()
        context.getSharedPreferences("dashboard_prefs", 0).edit().clear().commit()
        TileEditorPreviewSource.attach(owner)
    }
    @After fun cleanup() {
        screens.forEach { runCatching { it.stop().destroy() } }
        owner.disconnectWebSocket()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun button() = TileEntity("light", "button", "bottom_panel", "Свет", 20, 40, 1, 1,
        config = """{"entity_id":"light.kitchen","button_size":160}""")

    private fun open(id: String?): TileSettingsActivity {
        val controller = Robolectric.buildActivity(TileSettingsActivity::class.java,
            Intent(context, TileSettingsActivity::class.java).putExtra("tile_id", id))
        controller.get().setTheme(R.style.Theme_SmartHomeDashboard)
        controller.create().start()
        allowPin(controller.get())
        screens += controller
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }
    private fun allowPin(activity: Activity) {
        org.robolectric.util.ReflectionHelpers.setField(activity, "pinAuthorized", true)
        org.robolectric.util.ReflectionHelpers.setField(activity, "pinRequired", false)
    }
    private fun invoke(a: Any, name: String, vararg args: Any?) = try {
        ArchitectureTestAccess.methods(a).single { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(a, *args)
    } catch (e: InvocationTargetException) { throw e.targetException }
    private fun result(a: TileSettingsActivity, code: Int, tile: TileEntity) {
        invoke(a, "onActivityResult", code, Activity.RESULT_OK, TileDraft.result(tile))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun draftSentTo(a: TileSettingsActivity): TileEntity =
        TileDraft.from(shadowOf(a).nextStartedActivityForResult.intent)!!

    @Test fun advancedEditsStayInDraftAndDiscardLeavesStorageUntouched() {
        manager.saveTiles(listOf(button()))
        val before = stored()
        val editor = open("light")
        editor.findViewById<EditText>(R.id.etTileTitle).setText("Кухня")
        editor.findViewById<Button>(R.id.btnAdvanced).performClick()
        val draft = draftSentTo(editor)
        assertEquals("Кухня", draft.title)
        assertEquals(before, stored())
        result(editor, 1003, draft.copy(fontSize = 22))
        assertFalse("«Расширенные» больше не закрывают редактор", editor.isFinishing)
        assertEquals(before, stored())
        invoke(editor, "leaveEditor")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("Продолжить редактирование", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(editor.isFinishing)
        assertEquals(before, stored())
    }

    @Test fun saveWritesFormAndNestedDraftOnce() {
        manager.saveTiles(listOf(button()))
        val editor = open("light")
        editor.findViewById<EditText>(R.id.etTileTitle).setText("Кухня")
        editor.findViewById<Button>(R.id.btnAdvanced).performClick()
        result(editor, 1003, draftSentTo(editor).copy(fontSize = 22))
        editor.findViewById<Button>(R.id.btnSave).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val saved = manager.loadTiles().single()
        assertEquals("Кухня", saved.title)
        assertEquals(22, saved.fontSize)
        assertEquals("light.kitchen", JSONObject(saved.config).getString("entity_id"))
        assertEquals(20 to 40, saved.x to saved.y)
    }

    @Test fun newGroupIsNotCreatedByOpeningDevicesAndIsSavedOnlyOnSave() {
        val editor = open(null)
        editor.findViewById<EditText>(R.id.etTileTitle).setText("Свет в зале")
        editor.findViewById<Spinner>(R.id.spinnerTileType).setSelection(2)
        shadowOf(Looper.getMainLooper()).idle()
        editor.findViewById<Button>(R.id.btnEditChildren).performClick()
        val draft = draftSentTo(editor)
        assertTrue(manager.loadTiles().isEmpty())
        val withChildren = draft.copy(config = JSONObject(draft.config)
            .put("entity_ids", org.json.JSONArray(listOf("light.a", "light.b"))).toString())
        result(editor, 1002, withChildren)
        assertTrue(manager.loadTiles().isEmpty())
        editor.findViewById<Button>(R.id.btnSave).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val saved = manager.loadTiles().single()
        assertEquals(draft.id, saved.id)
        assertEquals("group", saved.type)
        assertEquals(2, JSONObject(saved.config).getJSONArray("entity_ids").length())
    }

    @Test fun deletingAnyTileAsksFirst() {
        manager.saveTiles(listOf(button()))
        val editor = open("light")
        editor.findViewById<Button>(R.id.btnDelete).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, manager.loadTiles().size)
        editor.findViewById<Button>(R.id.btnDelete).performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.loadTiles().isEmpty())
    }

    @Test fun unchangedEditorClosesWithoutQuestion() {
        manager.saveTiles(listOf(button()))
        val editor = open("light")
        invoke(editor, "leaveEditor")
        assertTrue(editor.isFinishing)
    }

    @Test fun childEditorInDraftModeReturnsDraftWithoutWriting() {
        val group = TileEntity("g", "group", "bottom_panel", "Группа", 20, 40, 1, 1,
            config = """{"entity_ids":["light.a"],"children":[{"entity_id":"light.a","name":"A"}]}""")
        val controller = Robolectric.buildActivity(ChildButtonsEditorActivity::class.java,
            Intent(context, ChildButtonsEditorActivity::class.java).putExtra("tile_id", "g")
                .putExtra(TileDraft.EXTRA, TileDraft.encode(group)))
        controller.get().setTheme(R.style.Theme_SmartHomeDashboard)
        controller.create().start()
        screens += controller
        val child = controller.get()
        allowPin(child)
        shadowOf(Looper.getMainLooper()).idle()
        child.findViewById<Button>(R.id.btnSave).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(stored())
        assertEquals(Activity.RESULT_OK, shadowOf(child).resultCode)
        assertEquals("g", TileDraft.from(shadowOf(child).resultIntent)!!.id)
    }
}
