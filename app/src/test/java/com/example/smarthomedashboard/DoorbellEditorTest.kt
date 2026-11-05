package com.example.smarthomedashboard

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ListView
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

// Редактор «Домофона» без PIN-шага: черновик, выбор сущностей по роли, запись только по «Сохранить».
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class DoorbellEditorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)
    private val model = MainViewModel()
    private val tile = TileEntity("door", DoorbellConfig.TYPE, "grid", "Домофон", 10, 20, 1, 1,
        config = DoorbellConfig.newTileConfig("""{"custom":"keep"}"""))

    @Before fun setUp() {
        manager.saveTiles(listOf(tile))
        model.onStatesSnapshot(listOf(
            HaEntityState("camera.reolink_door", "idle", JSONObject().put("friendly_name", "Домофон")),
            HaEntityState("binary_sensor.reolink_visitor", "off", JSONObject().put("friendly_name", "Звонок")),
            HaEntityState("sensor.temperature", "22", JSONObject())))
        TileEditorPreviewSource.attach(model)
    }

    @After fun tearDown() = DoorbellHub.detach()

    private fun open(): DoorbellEditorActivity = Robolectric.buildActivity(DoorbellEditorActivity::class.java,
        Intent(context, DoorbellEditorActivity::class.java).putExtra("tile_id", "door")).create().get()
        .also { idle() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun saved() = DoorbellConfig.from(manager.loadTiles().single().config)

    @Test fun pickerOffersOnlyFittingEntitiesAndSaveWritesConfig() {
        val activity = open()
        activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("door_row_camera").performClick()
        val picker = checkNotNull(activity.openPicker)
        val list = picker.window!!.decorView.findViewWithTag<ListView>("bm_pick_list")
        assertEquals("Для камеры — только camera.*", 1, list.adapter.count)
        list.performItemClick(null, 0, 0)
        idle()
        assertEquals("camera.reolink_door", activity.config.camera)
        val root = activity.findViewById<View>(android.R.id.content)
        root.findViewWithTag<EditText>("door_rtsp_main").setText("rtsp://10.0.0.5:554/h264Preview_01_main")
        root.findViewWithTag<EditText>("door_title_input").setText("Калитка")
        root.findViewWithTag<View>("door_mode_1").performClick()
        assertEquals("До «Сохранить» конфигурация не меняется", DoorbellConfig(), saved())
        assertTrue(activity.isDirty)
        assertTrue(activity.save())
        val after = saved()
        assertEquals("camera.reolink_door", after.camera)
        assertEquals("rtsp://10.0.0.5:554/h264Preview_01_main", after.rtspMain)
        assertEquals(DoorbellConfig.VideoMode.SNAPSHOT, after.mode)
        assertEquals("Калитка", manager.loadTiles().single().title)
        assertEquals("keep", JSONObject(manager.loadTiles().single().config).getString("custom"))
        assertTrue(activity.isFinishing)
    }

    @Test fun leavingDirtyEditorAsks() {
        val activity = open()
        activity.findViewById<View>(android.R.id.content).findViewWithTag<EditText>("door_rtsp_user").setText("admin")
        activity.leave()
        assertFalse(activity.isFinishing)
        assertNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog() as? AlertDialog)
    }
}
