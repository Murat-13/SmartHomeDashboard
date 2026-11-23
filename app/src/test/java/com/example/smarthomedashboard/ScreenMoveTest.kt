package com.example.smarthomedashboard

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

/**
 * Поворот экрана (решение владельца 23.11.2026: «при переворачивании… плитка домофон в раскрытом виде
 * переподключается… посмотри у плитки камера, звонок… переделай всё, что необходимо»). Карточки домофона и камер и окно
 * звонка — те же объекты до и после поворота: видео не подключается заново, разговор и окно звонка не закрываются.
 * На прежнем коде: карточки закрывались вместе со старым экраном, окно звонка после поворота не возвращалось.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenMoveTest {
    private val config = DoorbellConfig(camera = "camera.door", visitor = "binary_sensor.door_visitor", lock = "lock.gate")
    private val tile = TileEntity("door", DoorbellConfig.TYPE, "grid", "Калитка", 0, 0, 1, 1,
        config = config.writeTo(DoorbellConfig.newTileConfig()))
    private val values = mutableMapOf("camera.door" to "idle", "binary_sensor.door_visitor" to "off", "lock.gate" to "locked")

    @Before fun setUp() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("dashboard_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        com.example.smarthomedashboard.data.TileManager(app).saveTiles(listOf(tile))
        GateHub.reset()
        DoorbellHub.attach(app)
    }

    @After fun tearDown() {
        DoorbellMove.clear()
        DoorbellHub.RingUi.closeAll()
        DoorbellHub.actions = null
        DoorbellHub.detach()
        GateHub.reset()
    }

    private fun idle(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(50L, left)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            left -= step
        }
    }

    private fun screen(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private fun root(activity: Activity) = activity.findViewById<ViewGroup>(android.R.id.content)

    /** Разговор карточки закрыт ([DoorbellTalk.dispose]) — так раньше кончался поворот. */
    private fun talkClosed(owner: Any): Boolean {
        val talk = ReflectionHelpers.getField<DoorbellTalk>(owner, "talk")
        return ReflectionHelpers.getField(talk, "disposed")
    }

    @Test fun cardMovesToNewScreenWithVideoAndTalkAlive() {
        val old = screen()
        val card = DoorbellCard(Movable.context(old), expanded = true)
        var archiveOpened = 0
        card.onArchive = { archiveOpened++ }
        root(old).addView(card, ViewGroup.LayoutParams(800, 600))
        idle(50)
        assertTrue(card.isAttachedToWindow)
        assertSame("вид знает свой экран и через переносной контекст", old, Movable.activityOf(card.context))

        DoorbellMove.park("door", card)
        assertNull("со старого экрана снята", card.parent)
        assertTrue("видео в пути — не закрыто", card.video.moving)
        assertFalse("разговор не закрыт", talkClosed(card))
        assertNull("привязки прежнего экрана сняты — он не остаётся в памяти", card.onArchive)

        val next = screen()
        val taken = DoorbellMove.take("door", expanded = true, camera = false, activity = next)
        assertSame("новый экран получает ту же карточку", card, taken)
        assertSame("её виды теперь живут на новом экране", next, Movable.activityOf(card.context))
        assertSame(next, Movable.activityOf(card.video.context))
        root(next).addView(card, ViewGroup.LayoutParams(600, 800))
        idle(50)
        assertTrue(card.isAttachedToWindow)
        assertFalse("встала на место — обычный режим", card.video.moving)
        assertFalse(talkClosed(card))
        assertEquals(0, DoorbellMove.waiting)
        // Срок ожидания прошёл — забранную карточку он не трогает.
        idle(DoorbellMove.HOLD_MS + 500)
        assertFalse(talkClosed(card))
        card.dispose()
    }

    @Test fun cardNobodyTookIsClosed() {
        val old = screen()
        val card = DoorbellCard(Movable.context(old), expanded = true)
        root(old).addView(card, ViewGroup.LayoutParams(800, 600))
        DoorbellMove.park("door", card)
        idle(DoorbellMove.HOLD_MS - 500)
        assertFalse("ещё ждёт новый экран", talkClosed(card))
        idle(1_000)
        assertTrue("новый экран не забрал — разговор и видео закрыты, без экрана не работают", talkClosed(card))
        assertFalse(card.video.moving)
        assertEquals(0, DoorbellMove.waiting)
        assertNull(DoorbellMove.take("door", expanded = true, camera = false, activity = screen()))
    }

    @Test fun onlyOwnTileAndKindIsTaken() {
        val old = screen()
        val compact = DoorbellCard(Movable.context(old), expanded = false)
        val expanded = DoorbellCard(Movable.context(old), expanded = true)
        DoorbellMove.park("door", compact)
        DoorbellMove.park("door", expanded)
        assertEquals(2, DoorbellMove.waiting)
        val next = screen()
        assertNull("чужая плитка", DoorbellMove.take("other", expanded = false, camera = false, activity = next))
        assertSame(compact, DoorbellMove.take("door", expanded = false, camera = false, activity = next))
        assertNull("плитка стала камерой — прежняя карточка не подходит и закрывается",
            DoorbellMove.take("door", expanded = true, camera = true, activity = next))
        assertTrue(talkClosed(expanded))
        assertEquals(0, DoorbellMove.waiting)
        compact.dispose()
    }

    // ---- Окно звонка ----

    private fun readings() = BatteryReadings(HaSession(true, true), { values[it] }, { false }, { null as JSONObject? })
    private fun state() = DoorbellHub.onState(listOf(tile), readings())

    private fun ringIn(controller: ActivityController<Activity>): DoorbellRingWindow {
        val activity = controller.get()
        DoorbellHub.actions = object : DoorbellHub.Actions {
            override fun call(domain: String, service: String, entityId: String, data: Map<String, Any>,
                              onResult: (ServiceCommandResult) -> Unit): Boolean { onResult(ServiceCommandResult.ACKNOWLEDGED); return true }
            override fun prepareForRing() {}
            override fun tileBounds(tileId: String) = null
        }
        DoorbellHub.RingUi.resumed(activity)
        state()
        values["binary_sensor.door_visitor"] = "on"; state()
        values["binary_sensor.door_visitor"] = "off"; state()
        // В обычном экране — баннер; «Открыть» показывает окно звонка.
        root(activity).findViewWithTag<View>("door_banner")!!.findViewWithTag<View>("door_banner_open").performClick()
        idle(400)
        return DoorbellHub.RingUi.current!!.also { assertTrue(it.isShowing) }
    }

    @Test fun ringWindowSurvivesRotationWithItsTalkAndTimer() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val old = controller.get()
        val window = ringIn(controller)
        assertSame(old, window.host)
        idle(10_000) // звонок идёт уже 10 с

        // Поворот: Android уничтожает экран «ради смены конфигурации» и тут же строит такой же заново.
        ReflectionHelpers.setField(Activity::class.java, old, "mChangingConfigurations", true)
        controller.pause().stop().destroy()
        assertTrue("между экранами окно в пути, а не закрыто", window.moving)
        val next = screen()
        idle(50)
        assertNotSame(old, next)
        assertSame("окно звонка — то же самое, не закрылось и не открыто заново", window, DoorbellHub.RingUi.current)
        assertTrue(window.isShowing)
        assertFalse(window.moving)
        assertSame("теперь оно на новом экране", next, window.host)
        assertSame(window, root(next).findViewWithTag<DoorbellRingWindow>("door_ring_window"))
        assertSame("кнопки и видео окна живут на новом экране", next, Movable.activityOf(window.card.context))
        assertFalse("разговор окна не закрыт", talkClosed(window))
        assertFalse(window.card.video.moving)

        // Отсчёт закрытия не начался заново: минута с последнего касания (открытие окна), а не с поворота.
        idle(DoorbellRingWindow.RING_MS - 10_000 - 2_000)
        assertTrue(window.isShowing)
        idle(4_000)
        assertNull("минута прошла — окно закрылось само", DoorbellHub.RingUi.current)
        assertTrue(talkClosed(window))
    }

    @Test fun ringWindowWithoutNewScreenIsClosed() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val window = ringIn(controller)
        val old = controller.get()
        // Экран уничтожается «ради поворота», но новый так и не появляется.
        ReflectionHelpers.setField(Activity::class.java, old, "mChangingConfigurations", true)
        controller.pause().stop().destroy()
        assertTrue("ждёт новый экран", window.moving)
        assertSame(window, DoorbellHub.RingUi.current)
        idle(DoorbellMove.HOLD_MS + 500)
        assertNull("без экрана окно звонка не остаётся", DoorbellHub.RingUi.current)
        assertFalse(window.isShowing)
        assertTrue("разговор завершён", talkClosed(window))
    }

    @Test fun closedScreenStillClosesRingWindow() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val window = ringIn(controller)
        controller.pause().stop().destroy() // экран закрыли совсем (не поворот)
        assertNull(DoorbellHub.RingUi.current)
        assertFalse(window.isShowing)
        assertNotNull(window)
    }
}
