package com.example.smarthomedashboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

/** Профиль планшета (docs/diagnostics-tz.md, этап 2): опыт показа видео между запусками и экран «Планшет». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class TabletProfileTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val main = "rtsp://cam.local:554/h264Preview_01_main"
    private val sub = "rtsp://cam.local:554/h264Preview_01_sub"
    private var clock = 1_800_000_000_000L
    private var print = "android-1|955"

    @Before fun setUp() {
        TabletProfile.now = { clock }
        TabletProfile.fingerprint = { print }
        context.getSharedPreferences("tablet_profile", Context.MODE_PRIVATE).edit().clear().commit()
        TabletProfile.load(context)
        DoorbellVideoView.undecodable.clear(); DoorbellVideoView.hardwareRejected.clear()
    }

    @After fun tearDown() {
        TabletProfile.forget()
        TabletProfile.now = System::currentTimeMillis
        PlaybackLog.clear()
        DoorbellHub.detach()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun software(fps: Float, dropped: Float, seconds: Float = 30f) =
        ShowSummary(clock, seconds, "c2.android.avc.decoder", true, 2560, 1920, fps, dropped, 0)

    @Test fun experienceSurvivesRestart() {
        TabletProfile.markUndecodable(main, 2560, 1920)
        TabletProfile.markSoftware(sub)
        TabletProfile.rememberJitter(main, 210)
        TabletProfile.load(context)
        assertTrue(TabletProfile.undecodable(main))
        assertTrue(TabletProfile.software(sub))
        assertEquals(210L, TabletProfile.jitter(main))
        val stored = context.getSharedPreferences("tablet_profile", Context.MODE_PRIVATE).all.toString()
        assertFalse("адрес камеры не хранится", stored.contains("cam.local"))
    }

    @Test fun updateOfAndroidOrAppForgetsExperience() {
        TabletProfile.markUndecodable(main)
        TabletProfile.rememberJitter(main, 210)
        print = "android-2|956"
        TabletProfile.load(context)
        assertFalse(TabletProfile.undecodable(main))
        assertEquals(0L, TabletProfile.jitter(main))
    }

    @Test fun weekOldExperienceIsCheckedAgain() {
        TabletProfile.markUndecodable(main)
        clock += TabletProfile.TTL_MS + 1
        TabletProfile.load(context)
        assertNull(TabletProfile.record(main))
    }

    @Test fun cameraWithNewSizeIsLearnedAgain() {
        TabletProfile.markSoftware(main, 2560, 1920)
        TabletProfile.cameraSize(main, 2560, 1920)
        assertTrue("тот же размер — опыт тот же", TabletProfile.software(main))
        TabletProfile.cameraSize(main, 1920, 1080)
        assertFalse(TabletProfile.software(main))
    }

    @Test fun twoSlowShowsInARowMakeMainHeavy() {
        assertFalse(TabletProfile.mainShown(main, software(9f, 0.5f), 20.0))
        assertFalse("медленный, потом нормальный — не подряд", TabletProfile.mainShown(main, software(19.4f, 0.02f), 20.0))
        assertFalse(TabletProfile.mainShown(main, software(14f, 0.05f), 20.0))
        assertFalse("короткий показ не в счёт", TabletProfile.mainShown(main, software(5f, 0.6f, seconds = 4f), 20.0))
        assertFalse("аппаратный декодер не в счёт", TabletProfile.mainShown(main, software(5f, 0.6f).copy(software = false), 20.0))
        assertTrue("14 из 20 к/с — дважды подряд", TabletProfile.mainShown(main, software(14f, 0.05f), 20.0))
        assertTrue(TabletProfile.heavy(main))
        // Samsung: 2560×1920 программно, 19,4 из 20 к/с — успевает, никогда не «тяжёлый».
        repeat(5) { assertFalse(TabletProfile.mainShown(sub, software(19.4f, 0.02f), 20.0)) }
    }

    @Test fun heavyOrUndecodableMainOpensSubstream() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = DoorbellVideoView(activity)
        val source = DoorbellVideoView.Source(main, "admin", "", false, sub)
        assertEquals(main, view.rtspFor(source))
        repeat(2) { TabletProfile.mainShown(main, software(8f, 0.4f), 20.0) }
        assertEquals(sub, view.rtspFor(source))
        TabletProfile.forget()
        TabletProfile.markUndecodable(main)
        assertEquals("после перезапуска — сразу субпоток, без новой неудачи", sub, view.rtspFor(source))
        assertEquals("без субпотока — основной как есть", main, view.rtspFor(DoorbellVideoView.Source(main, "admin", "", false, "")))
    }

    @Test fun rememberedJitterSeedsNewStream() {
        TabletProfile.rememberJitter(main, 230)
        val stream = SharedStream(CameraHub.Key(main, "admin", null), { _, _, _ -> error("не нужен") }, { error("не нужен") })
        assertEquals(230L, stream.floorJitterMs)
        val memory = JitterMemory()
        memory.add(120); memory.add(900); memory.add(150)
        assertEquals("обычная — середина, сбой не в счёт", 150L, memory.usual)
        assertEquals(900L, memory.max)
    }

    @Test fun tabletScreenShowsDecisionsAndForgets() {
        val config = JSONObject(DoorbellConfig.newTileConfig()).put(DoorbellConfig.KEY, DoorbellConfig(rtspMain = main, rtspSub = sub).toJson()).toString()
        TileManager(context).saveTiles(listOf(TileEntity("door", DoorbellConfig.TYPE, "grid", "Домофон", 10, 20, 1, 1, config = config)))
        repeat(2) { TabletProfile.mainShown(main, software(8f, 0.4f), 20.0) }
        TabletProfile.rememberJitter(sub, 200)
        val controller = Robolectric.buildActivity(TabletActivity::class.java, Intent(context, TabletActivity::class.java)).setup()
        val activity = controller.get()
        org.robolectric.util.ReflectionHelpers.setField(activity, "pinGateAccess", PinAccess(object : PinAccess.Storage {
            override fun read() = PinAccess.State(null, null)
            override fun write(state: PinAccess.State) = true
        }, object : PinAccess.Legacy { override fun read(): String? = null; override fun remove() = true },
            clock = object : PinAccess.Clock { override fun elapsed() = 0L; override fun boot() = "test" }, worker = {}))
        idle()
        fun texts(): List<String> {
            val out = mutableListOf<String>()
            fun walk(v: View) { if (v is TextView) out += v.text.toString(); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
            walk(activity.findViewById<View>(android.R.id.content))
            return out
        }
        val shown = texts()
        assertTrue(shown.joinToString("\n"), shown.any { it.startsWith("H.264 — ") })
        assertTrue(shown.joinToString("\n"), shown.any { it.startsWith("HD: субпоток — программный декодер не успевал") })
        assertTrue(shown.joinToString("\n"), shown.any { it.contains("с 0,2 с (запомнено)") })
        assertTrue(shown.joinToString("\n"), shown.any { it.startsWith("Нагрев: ") })
    }
}
