package com.example.smarthomedashboard

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

// Запись с камер: раздел «Запись» в редакторе домофона. Настройки камеры — в плитке, «записывать здесь»
// и место — только этого планшета; всё пишется по «Сохранить».
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class RecordingEditorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)
    private val tile = TileEntity("door", DoorbellConfig.TYPE, "grid", "Домофон", 10, 20, 1, 1,
        config = DoorbellConfig(rtspSub = "rtsp://10.0.0.5/sub").writeTo(DoorbellConfig.newTileConfig()))

    @Before fun setUp() {
        manager.saveTiles(listOf(tile))
        TileEditorPreviewSource.attach(MainViewModel())
    }

    @After fun tearDown() = DoorbellHub.detach()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(): DoorbellEditorActivity = Robolectric.buildActivity(DoorbellEditorActivity::class.java,
        Intent(context, DoorbellEditorActivity::class.java).putExtra("tile_id", "door")).create().get().also { idle() }

    @Test fun recordingSettingsAreSavedSeparatelyForTileAndTablet() {
        val activity = open()
        val root = activity.findViewById<View>(android.R.id.content)
        assertFalse("По умолчанию на планшете не пишется", activity.recordHere)
        assertEquals(RecordingConfig(), activity.recording)
        root.findViewWithTag<SwitchCompat>("rec_here").isChecked = true
        root.findViewWithTag<View>("rec_mode_2").performClick() // «Круглосуточно»
        root.findViewWithTag<View>("rec_remote_mode_1").performClick() // издалека — «Только события»
        root.findViewWithTag<SwitchCompat>("rec_audio_continuous").isChecked = false
        root.findViewWithTag<EditText>("rec_keep_continuous").setText("3")
        root.findViewWithTag<EditText>("rec_limit").setText("40")
        // События в HD (этап 2): движение включено, животное выключено, предзапись/дозапись/длина ролика.
        root.findViewWithTag<SwitchCompat>("rec_trigger_motion").isChecked = true
        root.findViewWithTag<SwitchCompat>("rec_trigger_animal").isChecked = false
        root.findViewWithTag<EditText>("rec_pre").setText("5")
        root.findViewWithTag<EditText>("rec_post").setText("30")
        root.findViewWithTag<EditText>("rec_max_clip").setText("2")
        idle()
        assertTrue(activity.isDirty)
        // До «Сохранить» ничего не записано.
        assertFalse(RecordingDevice(context).enabled("door"))
        assertEquals(RecordingConfig(), RecordingConfig.from(manager.loadTiles().single().config))

        assertTrue(activity.save())
        idle()
        val savedTile = manager.loadTiles().single()
        val rec = RecordingConfig.from(savedTile.config)
        assertEquals(RecordingConfig.Mode.CONTINUOUS, rec.mode)
        assertEquals(RecordingConfig.Mode.EVENTS, rec.remoteMode)
        assertFalse(rec.audioContinuous)
        assertEquals(3, rec.keepContinuousDays)
        assertEquals(setOf("visitor", "person", "vehicle", "motion"), rec.eventTriggers)
        assertEquals(listOf(5, 30, 2), listOf(rec.preSeconds, rec.postSeconds, rec.maxClipMinutes))
        assertEquals("Настройки домофона не потерялись", "rtsp://10.0.0.5/sub", DoorbellConfig.from(savedTile.config).rtspSub)
        val device = RecordingDevice(context)
        assertTrue(device.enabled("door"))
        assertEquals(40, device.limitGb)
        // Флаг «записывать здесь» не лежит в конфигурации плитки (не уедет резервной копией на другой планшет).
        assertFalse(savedTile.config.contains("enabled_tiles"))
        assertFalse(savedTile.config.contains("record_here"))
    }

    @Test fun recordingSectionAloneMakesEditorDirty() {
        val activity = open()
        assertFalse(activity.isDirty)
        activity.findViewById<View>(android.R.id.content).findViewWithTag<SwitchCompat>("rec_voice").isChecked = false
        assertTrue(activity.isDirty)
    }
}
