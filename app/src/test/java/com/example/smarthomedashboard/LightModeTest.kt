package com.example.smarthomedashboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.example.smarthomedashboard.CameraDiagnostics.Check
import com.example.smarthomedashboard.CameraDiagnostics.Level
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/** Этап 3 диагностики (docs/diagnostics-tz.md): слежение за советами камер, облегчённый режим, нагрев. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LightModeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var clock = 1_800_000_000_000L
    private val gb = 1_000_000_000L

    @Before fun setUp() {
        context.getSharedPreferences("tablet_settings", Context.MODE_PRIVATE).edit().clear().commit()
        LightMode.resetForTests()
        LightMode.memory = { 4 * gb to false }
        LightMode.load(context)
        CameraWatch.clear()
        CameraWatch.now = { clock }
    }

    @After fun tearDown() {
        LightMode.resetForTests()
        CameraWatch.clear()
        CameraWatch.now = System::currentTimeMillis
        DoorbellHub.detach()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun check(id: String, level: Level = Level.IMPROVE) = Check(id, "Проверка $id", level, "")

    @Test fun adviceShowsAfterTenMinutesAndFadesTenMinutesLater() {
        repeat(10) { CameraWatch.update(listOf("door" to listOf(check("С1"), check("К1", Level.OK)))); clock += 60_000 }
        assertTrue("9 минут — ещё рано", CameraWatch.advice.isEmpty())
        CameraWatch.update(listOf("door" to listOf(check("С1"))))
        assertEquals(listOf(CameraWatch.Advice("door", "С1", "Проверка С1")), CameraWatch.advice)
        assertEquals("Камеры: есть советы (1)", CameraWatch.text())
        // Проблема ушла: строка держится ещё 10 минут.
        clock += 9 * 60_000
        CameraWatch.update(listOf("door" to listOf(check("С1", Level.OK))))
        assertEquals(1, CameraWatch.advice.size)
        clock += 2 * 60_000
        CameraWatch.update(listOf("door" to listOf(check("С1", Level.OK))))
        assertNull(CameraWatch.text())
    }

    @Test fun tabletChecksCountOncePerTablet() {
        repeat(11) { CameraWatch.update(listOf("a" to listOf(check("С4"), check("К2")), "b" to listOf(check("С4")))); clock += 60_000 }
        assertEquals(listOf("К2", "С4"), CameraWatch.advice.map { it.checkId })
        assertEquals("Камеры: есть советы (2)", CameraWatch.text())
    }

    @Test fun lightModeByMemorySmoothnessAndChoice() {
        assertFalse(LightMode.active)
        LightMode.memory = { 2 * gb to false }
        LightMode.refresh(context)
        assertTrue(LightMode.active)
        assertEquals("мало памяти (2,0 ГБ)", LightMode.reason)
        assertEquals(180L, LightMode.ms(300))
        LightMode.set(context, LightMode.Choice.OFF)
        assertFalse("выбор пользователя сильнее автоматики", LightMode.active)
        assertEquals(300L, LightMode.ms(300))
        LightMode.memory = { 4 * gb to false }
        LightMode.set(context, LightMode.Choice.AUTO)
        assertFalse(LightMode.active)
        repeat(3) { LightMode.expansionMeasured(20, 9) }
        assertTrue(LightMode.active)
        assertTrue(LightMode.reason!!, LightMode.reason!!.startsWith("раскрытие запинается (45 %"))
        LightMode.resetForTests()
        LightMode.set(context, LightMode.Choice.ON)
        LightMode.load(context)
        assertEquals("выбор переживает перезапуск", LightMode.Choice.ON, LightMode.choice)
        assertEquals("по выбору", LightMode.reason)
    }

    @Test fun heatTurnsLightOnAndHdToSubstream() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = DoorbellVideoView(activity)
        val source = DoorbellVideoView.Source("rtsp://cam.local/main", "admin", "", false, "rtsp://cam.local/sub")
        assertEquals("rtsp://cam.local/main", view.rtspFor(source))
        LightMode.heat(PowerManager.THERMAL_STATUS_SEVERE)
        assertTrue(LightMode.active)
        assertEquals("планшет перегрет", LightMode.reason)
        assertEquals("rtsp://cam.local/sub", view.rtspFor(source))
        LightMode.heat(PowerManager.THERMAL_STATUS_LIGHT)
        assertFalse(LightMode.active)
        assertEquals("rtsp://cam.local/main", view.rtspFor(source))
    }

    @Test fun onlyTwoLiveVideosPerPageInLightMode() {
        fun tile(id: String, x: Int, y: Int, camera: String = "camera.$id", page: Int = 0): TileEntity {
            val config = JSONObject(DoorbellConfig.newTileConfig(camera = true))
                .put(DoorbellConfig.KEY, DoorbellConfig(rtspSub = "rtsp://cam.local/$id", camera = camera).toJson()).toString()
            return TileEntity(id, DoorbellConfig.CAMERA_TYPE, "grid", id, x, y, 1, 1, config = config, page = page)
        }
        TileManager(context).saveTiles(listOf(tile("c", 10, 400), tile("a", 10, 10), tile("b", 600, 10), tile("d", 10, 800, camera = ""),
            tile("e", 10, 10, page = 1)))
        assertTrue("режим выключен — все живые", LightMode.liveAllowed(context, "c"))
        LightMode.set(context, LightMode.Choice.ON)
        assertTrue(LightMode.liveAllowed(context, "a"))
        assertTrue(LightMode.liveAllowed(context, "b"))
        assertFalse("третья сверху — снимок", LightMode.liveAllowed(context, "c"))
        assertTrue("без камеры HA снимка нет — остаётся живой", LightMode.liveAllowed(context, "d"))
        assertTrue("другая страница — свой счёт", LightMode.liveAllowed(context, "e"))
    }

    @Test fun tabletScreenSwitchesLightMode() {
        val activity = Robolectric.buildActivity(TabletActivity::class.java, Intent(context, TabletActivity::class.java)).setup().get()
        org.robolectric.util.ReflectionHelpers.setField(activity, "pinGateAccess", PinAccess(object : PinAccess.Storage {
            override fun read() = PinAccess.State(null, null)
            override fun write(state: PinAccess.State) = true
        }, object : PinAccess.Legacy { override fun read(): String? = null; override fun remove() = true },
            clock = object : PinAccess.Clock { override fun elapsed() = 0L; override fun boot() = "test" }, worker = {}))
        idle()
        fun view(tag: String) = activity.findViewById<View>(android.R.id.content).findViewWithTag<View>(tag)
        assertEquals("Сейчас: выключен", (view("tablet_light_state") as TextView).text.toString())
        view("tablet_light_1").performClick()
        idle()
        assertEquals(LightMode.Choice.ON, LightMode.choice)
        assertEquals("Сейчас: включён — по выбору", (view("tablet_light_state") as TextView).text.toString())
    }
}
