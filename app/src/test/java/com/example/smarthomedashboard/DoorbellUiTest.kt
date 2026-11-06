package com.example.smarthomedashboard

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

// «Домофон»: карточка, окно звонка, баннер в других экранах, калитка, свет. Команды только записываются.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class DoorbellUiTest {
    private val config = DoorbellConfig(camera = "camera.door", visitor = "binary_sensor.door_visitor",
        person = "binary_sensor.door_person", lock = "lock.gate", light = "light.entrance", quickReply = "select.door_reply")
    private val tile = TileEntity("door", DoorbellConfig.TYPE, "grid", "Калитка", 0, 0, 1, 1,
        config = config.writeTo(DoorbellConfig.newTileConfig()))
    private val values = mutableMapOf("camera.door" to "idle", "binary_sensor.door_visitor" to "off",
        "binary_sensor.door_person" to "off", "lock.gate" to "locked", "light.entrance" to "off", "sun.sun" to "below_horizon",
        "select.door_reply" to "off")
    private val attrs = mapOf("select.door_reply" to JSONObject().put("options", JSONArray(listOf("off", "Сейчас подойду"))))
    private val calls = mutableListOf<String>()
    private var prepared = 0
    private lateinit var activity: Activity

    private fun readings(connected: Boolean = true) =
        BatteryReadings(HaSession(connected, connected), { values[it] }, { false }, { attrs[it] })

    @Before fun setUp() {
        // Кадры анимаций — по часам теста (иначе Robolectric сам двигает время на каждый кадр).
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("dashboard_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        DoorbellHub.attach(app)
        DoorbellHub.actions = object : DoorbellHub.Actions {
            override fun call(domain: String, service: String, entityId: String, data: Map<String, Any>,
                              onResult: (ServiceCommandResult) -> Unit): Boolean {
                calls += "$domain.$service $entityId" + (data["option"]?.let { " $it" } ?: "")
                onResult(ServiceCommandResult.ACKNOWLEDGED)
                return true
            }
            override fun prepareForRing() { prepared++ }
            override fun tileBounds(tileId: String) = null
        }
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.resources.displayMetrics.widthPixels = 1920
        activity.resources.displayMetrics.heightPixels = 1200
        DoorbellHub.RingUi.resumed(activity)
        state()
    }

    @After fun tearDown() {
        DoorbellHub.actions = null
        DoorbellHub.detach()
    }

    private fun state(connected: Boolean = true) = DoorbellHub.onState(listOf(tile), readings(connected))

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(50L, left)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            left -= step
        }
    }

    private fun root() = activity.findViewById<ViewGroup>(android.R.id.content)
    private fun window() = root().findViewWithTag<DoorbellRingWindow>("door_ring_window")
    private fun banner() = root().findViewWithTag<View>("door_banner")

    private fun layoutRoot() {
        val r = root().rootView
        r.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
        r.layout(0, 0, 1920, 1200)
    }

    private fun ring() {
        values["binary_sensor.door_visitor"] = "on"; state()
        values["binary_sensor.door_visitor"] = "off"; state()
    }

    private fun openFromBanner(): DoorbellRingWindow {
        ring()
        layoutRoot()
        assertNotNull("В экране настроек — сначала баннер", banner())
        assertNull(window())
        banner()!!.findViewWithTag<View>("door_banner_open").performClick()
        advance(400)
        layoutRoot()
        return window()!!.also { assertTrue(it.isShowing) }
    }

    private fun hold(view: View, ms: Long) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        view.dispatchTouchEvent(down); down.recycle()
        advance(ms)
        val up = MotionEvent.obtain(t, t + ms, MotionEvent.ACTION_UP, 10f, 10f, 0)
        view.dispatchTouchEvent(up); up.recycle()
    }

    @Test fun compactCardStatesAndMissedBadge() {
        val card = DoorbellCard(activity, expanded = false)
        root().addView(card, ViewGroup.LayoutParams(480, 360))
        advance(50)
        layoutRoot()
        fun bind(missed: Int = 0, offline: Boolean = false) =
            card.bind("Калитка", config, DoorbellModel.snapshot(config, readings()), offline, missed, null, System.currentTimeMillis(), animate = false)
        bind()
        assertEquals("В сети", card.findViewWithTag<TextView>("door_status").text.toString())
        assertEquals(DoorbellFrame.NEUTRAL, card.accent)
        values["binary_sensor.door_person"] = "on"; bind()
        assertEquals("Человек у калитки", card.findViewWithTag<TextView>("door_status").text.toString())
        assertEquals(DoorbellFrame.PERSON, card.accent)
        assertEquals(View.VISIBLE, card.findViewWithTag<View>("door_icon_person").visibility)
        values["binary_sensor.door_visitor"] = "on"; bind(missed = 3)
        assertEquals(DoorbellFrame.RING, card.accent)
        assertTrue("Звонок — пульс рамки", card.pulsing)
        assertEquals("3", card.findViewWithTag<TextView>("door_missed").text.toString())
        values["camera.door"] = "unavailable"; bind()
        assertEquals(DoorbellFrame.OFFLINE, card.accent)
        assertEquals(View.VISIBLE, card.findViewWithTag<View>("door_offline").visibility)
        assertEquals(View.GONE, card.findViewWithTag<View>("door_missed").visibility)
        card.dispose()
    }

    /** Плитка в режиме «снимок» не берёт основной поток раскрытия «досматривать»: отпустить его было бы некому. */
    @Test fun snapshotTileDoesNotHostGuestStream() {
        val card = DoorbellCard(activity, expanded = false)
        root().addView(card, ViewGroup.LayoutParams(480, 360))
        advance(50)
        card.bind("Калитка", config.copy(mode = DoorbellConfig.VideoMode.SNAPSHOT), DoorbellModel.snapshot(config, readings()),
            false, 0, null, System.currentTimeMillis(), animate = false)
        val exo = androidx.media3.exoplayer.ExoPlayer.Builder(activity).build()
        try {
            assertFalse(card.video.canHostGuest)
            assertFalse(card.video.acceptGuest(exo, null, 4f / 3f))
            assertFalse(card.video.hasGuest)
            card.bind("Калитка", config, DoorbellModel.snapshot(config, readings()), false, 0, null, System.currentTimeMillis(), animate = false)
            assertTrue("Живая плитка временную картинку берёт", card.video.canHostGuest)
        } finally { exo.release(); card.dispose() }
    }

    @Test fun hdBadgeFadesInAndOutWithStream() {
        val card = DoorbellCard(activity, expanded = true)
        root().addView(card, ViewGroup.LayoutParams(800, 600))
        advance(50)
        val hd = card.findViewWithTag<TextView>("door_hd")
        assertEquals("Пока основного потока нет — надписи нет", View.GONE, hd.visibility)
        card.showHd(true)
        advance(120)
        assertTrue("Проявляется плавно", hd.alpha > 0f && hd.alpha < DoorbellCard.HD_ALPHA)
        advance(300)
        assertEquals(DoorbellCard.HD_ALPHA, hd.alpha, 0.01f)
        assertEquals("HD", hd.text.toString())
        card.showHd(false)
        advance(400)
        assertEquals(View.GONE, hd.visibility)
        card.dispose()
    }

    @Test fun ringInOtherScreenShowsBannerThenWindowWithoutLosingTheScreen() {
        val window = openFromBanner()
        assertNull("Баннер убран", banner())
        assertEquals(DoorbellHub.RING_PHRASE, window.findViewWithTag<TextView>("door_ring_heading").text.toString())
        assertEquals("Счётчик пропущенных обнулён «Открыть»", 0, DoorbellHub.missed("door"))
        assertTrue("Экран разбужен, раскрытое свёрнуто", prepared >= 1)
        assertEquals("После заката свет включается автоматически", listOf("light.turn_on light.entrance"), calls)
    }

    @Test fun laterHidesBannerAndRingStaysMissed() {
        ring()
        layoutRoot()
        banner()!!.findViewWithTag<View>("door_banner_later").performClick()
        advance(400)
        assertNull(banner()); assertNull(window())
        assertEquals(1, DoorbellHub.missed("door"))
        assertNotNull(DoorbellHub.lastRing("door"))
    }

    @Test fun holdOneSecondOpensGateAndFactClosesWindow() {
        val window = openFromBanner()
        calls.clear()
        hold(window.openButton, 400)
        advance(300)
        assertTrue("Отпустили раньше — команды нет: $calls", calls.isEmpty())
        hold(window.openButton, 1_100)
        assertEquals(listOf("lock.unlock lock.gate"), calls)
        assertEquals(DoorbellRingWindow.Gate.WAITING, window.openButton.state)
        values["lock.gate"] = "unlocked"; state()
        assertEquals("Калитку открыли", window.findViewWithTag<TextView>("door_ring_sub").text.toString())
        advance(DoorbellRingWindow.OPENED_SHOW_MS + DoorbellRingWindow.CLOSE_MS + 100)
        assertNull(window())
        assertFalse(window.isShowing)
    }

    @Test fun acceptedWithoutFactDoesNotInviteImmediateRepeat() {
        val window = openFromBanner()
        calls.clear()
        hold(window.openButton, 1_100)
        advance(DoorbellRingWindow.GATE_TIMEOUT_MS + 100)
        assertEquals(DoorbellRingWindow.Gate.UNCONFIRMED, window.openButton.state)
        assertTrue(window.isShowing)
        hold(window.openButton, 100)
        assertEquals("Касание лишь снимает предупреждение", 1, calls.size)
        assertEquals(DoorbellRingWindow.Gate.IDLE, window.openButton.state)
        hold(window.openButton, 1_100)
        assertEquals("Повтор — новым удержанием", 2, calls.size)
    }

    @Test fun repeatedRingAfterOpenedKeepsWindowAndUnlocksButton() {
        val window = openFromBanner()
        advance(3_500)
        values["lock.gate"] = "unlocked"; state()
        assertEquals(DoorbellRingWindow.Gate.OPENED, window.openButton.state)
        values["lock.gate"] = "locked"; state()
        advance(500)
        ring()
        advance(3_000)
        assertTrue("Новый звонок отменил закрытие после «Калитку открыли»", window.isShowing)
        assertEquals(DoorbellRingWindow.Gate.IDLE, window.openButton.state)
    }

    @Test fun autoCloseAfterMinuteAndRepeatedRingExtends() {
        val window = openFromBanner()
        advance(50_000)
        ring()
        advance(30_000)
        assertTrue("Повторный звонок продлил окно", window.isShowing)
        advance(31_000)
        assertNull(window())
    }

    @Test fun autoLightTurnsOffAfterWindowClosesUnlessTouched() {
        val window = openFromBanner()
        values["light.entrance"] = "on"; state()
        calls.clear()
        window.findViewWithTag<View>("door_close").performClick()
        advance(DoorbellRingWindow.CLOSE_MS + 100)
        assertNull(window())
        advance(3 * 60_000L - 1_000)
        assertTrue(calls.isEmpty())
        advance(2_000)
        assertEquals(listOf("light.turn_off light.entrance"), calls)
    }

    /** Второй звонок, когда свет уже включён автоматически, а окно не открывали: выключение не теряется. */
    @Test fun secondRingKeepsAutoLightOffTimer() {
        ring(); layoutRoot()
        banner()!!.findViewWithTag<View>("door_banner_later").performClick()
        values["light.entrance"] = "on"; state()
        advance(10_000)
        ring(); layoutRoot()
        banner()?.findViewWithTag<View>("door_banner_later")?.performClick()
        calls.clear()
        advance(DoorbellRingWindow.RING_MS + 3 * 60_000L + 2_000)
        assertEquals("Свет, включённый звонком, гаснет и после повторного звонка", listOf("light.turn_off light.entrance"), calls)
    }

    /** Свет выключили руками (выключатель, плитка) — дальше им управляет человек, автоматика не трогает. */
    @Test fun lightTurnedOffManuallyIsNotTouchedAgain() {
        ring(); layoutRoot()
        banner()!!.findViewWithTag<View>("door_banner_later").performClick()
        values["light.entrance"] = "on"; state()
        values["light.entrance"] = "off"; state()
        advance(1_000)
        values["light.entrance"] = "on"; state() // включил человек
        calls.clear()
        advance(DoorbellRingWindow.RING_MS + 3 * 60_000L + 2_000)
        assertTrue("Ручное включение не выключается автоматикой: $calls", calls.isEmpty())
    }

    @Test fun manualLightTakesOverAutomatic() {
        val window = openFromBanner()
        values["light.entrance"] = "on"; state()
        calls.clear()
        window.findViewWithTag<View>("door_light").performClick()
        assertEquals(listOf("light.turn_off light.entrance"), calls)
        window.close()
        advance(5 * 60_000L)
        assertEquals("Свет больше не трогаем", 1, calls.size)
    }

    @Test fun quickReplySendsSelectOption() {
        val window = openFromBanner()
        calls.clear()
        window.findViewWithTag<View>("door_reply").performClick()
        val replies = window.findViewWithTag<ViewGroup>("door_replies")
        assertEquals("«off» не показывается", 1, replies.childCount)
        replies.getChildAt(0).performClick()
        assertEquals(listOf("select.select_option select.door_reply Сейчас подойду"), calls)
    }

    @Test fun gateOpenedElsewhereDismissesBanner() {
        ring()
        layoutRoot()
        assertNotNull(banner())
        values["lock.gate"] = "unlocked"; state()
        advance(400)
        assertNull("Калитку открыли на другом планшете", banner())
    }
}
