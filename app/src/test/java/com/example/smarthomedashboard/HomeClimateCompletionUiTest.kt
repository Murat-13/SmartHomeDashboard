package com.example.smarthomedashboard

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
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
class HomeClimateCompletionUiTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)
    private val owner = MainViewModel()
    private val screens = mutableListOf<ActivityController<HomeClimateEditorActivity>>()
    private fun open(saved: Bundle? = null): HomeClimateEditorActivity {
        if (saved == null) manager.saveTiles(listOf(TileEntity("climate", HomeClimateConfig.TYPE, "grid", "Home", 10, 20, 1, 1,
            config = HomeClimateConfig.newTileConfig())))
        owner.onEntityStateChanged("sensor.temperature", "22", JSONObject().put("unit_of_measurement", "°C"))
        owner.onEntityStateChanged("sensor.humidity", "48", JSONObject().put("unit_of_measurement", "%"))
        owner.onEntityStateChanged("sensor.power", "648", JSONObject().put("unit_of_measurement", "W"))
        TileEditorPreviewSource.attach(owner)
        val controller = Robolectric.buildActivity(HomeClimateEditorActivity::class.java,
            Intent(context, HomeClimateEditorActivity::class.java).putExtra("tile_id", "climate"))
        controller.get().setTheme(R.style.Theme_SmartHomeDashboard)
        controller.create(saved).start()
        if (saved != null) controller.restoreInstanceState(saved)
        screens += controller
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }
    @After fun cleanup() { screens.forEach { it.stop().destroy() }; owner.disconnectWebSocket() }
    private fun field(dialog: AlertDialog, tag: String) = dialog.window!!.decorView.findViewWithTag<EditText>(tag)
    @Test fun roomRejectsPowerSensorAndAcceptsTypedHumidityWithoutExtraApply() {
        val editor = open()
        editor.roomDialog(editor.draft.newRoom(), true)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = editor.openRoomDialog!!
        field(dialog, "hc_room_name").setText("Room")
        field(dialog, "hc_room_temperature_manual").setText("sensor.power")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertTrue(editor.draft.config.rooms.isEmpty())
        field(dialog, "hc_room_temperature_manual").setText("sensor.temperature")
        field(dialog, "hc_room_humidity_manual").setText("sensor.humidity")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.window!!.decorView.findViewWithTag<android.widget.TextView>("hc_room_error").text.toString(), dialog.isShowing)
        assertTrue(HomeClimateConfig.from(manager.loadTiles().single().config).rooms.isEmpty())
        assertTrue(editor.save())
        val room = HomeClimateConfig.from(TileManager(context).loadTiles().single().config).rooms.single()
        assertEquals("sensor.temperature", room.temperatureEntity)
        assertEquals("sensor.humidity", room.humidityEntity)
    }
    @Test fun rotationRetainsPendingRoomWithStableIdAndUnsavedWeatherText() {
        val editor = open()
        editor.showSection(HomeClimateEditorActivity.SECTION_WEATHER)
        editor.findViewById<View>(android.R.id.content).findViewWithTag<EditText>("hc_weather_manual").setText("weather.test")
        val room = editor.draft.newRoom()
        editor.roomDialog(room, true)
        shadowOf(Looper.getMainLooper()).idle()
        field(editor.openRoomDialog!!, "hc_room_name").setText("Unsaved room")
        field(editor.openRoomDialog!!, "hc_room_temperature_manual").setText("sensor.temperature")
        val saved = Bundle()
        screens.last().saveInstanceState(saved).stop().destroy(); screens.removeAt(screens.lastIndex)
        val restored = open(saved)
        assertEquals("weather.test", restored.draft.config.weather.entityId)
        val dialog = restored.openRoomDialog!!
        assertEquals("Unsaved room", field(dialog, "hc_room_name").text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(room.id, restored.draft.config.rooms.single().id)
        assertTrue(HomeClimateConfig.from(manager.loadTiles().single().config).rooms.isEmpty())
    }
    @Test fun changingTimeFormatPreservesOtherUnsavedClockSettings() {
        val editor = open()
        val root = editor.findViewById<View>(android.R.id.content)
        root.findViewWithTag<androidx.appcompat.widget.SwitchCompat>("hc_seconds").isChecked = true
        root.findViewWithTag<android.widget.Spinner>("hc_date_format").setSelection(2)
        shadowOf(Looper.getMainLooper()).idle()
        root.findViewWithTag<View>("hc_time_format_2").performClick()
        assertEquals(false, editor.draft.config.clock.use24h)
        assertTrue(editor.draft.config.clock.seconds)
        assertEquals(HomeClimateConfig.DATE_NUMERIC, editor.draft.config.clock.dateFormat)
    }

}
