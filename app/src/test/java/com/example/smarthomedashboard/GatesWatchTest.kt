package com.example.smarthomedashboard

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
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
import java.time.Duration

/**
 * Калитки и ворота, этапы 2–3 (docs/gate-tz.md): «оставили открытым», «закрыть само», журнал открытий, плитка «Проход»
 * и кнопка в уведомлении на телефоне. Home Assistant и телефон — поддельные: команды и уведомления только записываются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class GatesWatchTest {
    private val context = RuntimeEnvironment.getApplication()
    private val states = HashMap<String, String>()
    private val readings get() = BatteryReadings(HaSession(true, true), states::get, { false }, { null })
    private val commands = mutableListOf<String>()
    private val phone = mutableListOf<Map<String, Any>>()
    private var acked = true

    private val drive = Gate("g_drive", "Ворота", Gate.Kind.COVER, open = "cover.drive", sensor = "binary_sensor.drive_open",
        confirmSeconds = 120, remindMinutes = 3, autoClose = true, autoCloseMinutes = 5)
    private val lock = Gate("g_lock", "Дверь", Gate.Kind.LOCK, open = "lock.door", sensor = "binary_sensor.door",
        autoClose = true, autoCloseMinutes = 2, phone = true)
    private val wicket = Gate("g_wicket", "Калитка", Gate.Kind.RELAY, open = "switch.wicket", sensor = "binary_sensor.wicket", phone = true)
    private val camera = TileEntity("cam", DoorbellConfig.TYPE, "grid", "Домофон", 0, 0, 1, 1,
        config = DoorbellConfig(camera = "camera.door", person = "binary_sensor.person", gates = listOf("g_drive", "g_lock", "g_wicket"))
            .writeTo(DoorbellConfig.newTileConfig()))

    @Before fun setUp() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        GateHub.reset()
        context.getSharedPreferences("dashboard_prefs", 0).edit().clear().commit()
        GateLog.dir(context).deleteRecursively()
        GateStore.save(context, listOf(drive, lock, wicket))
        GateHub.attach(context)
        GateWatch.tiles = { listOf(camera) }
        GateHub.driver = GateHub.Driver { c, onResult ->
            commands += "${c.domain}.${c.service} ${c.entity}"
            onResult(if (acked) ServiceCommandResult.ACKNOWLEDGED else ServiceCommandResult.REJECTED); true
        }
        DoorbellAway.sender = { _, _, data, done -> phone += data; done(true) }
        DoorbellAway.save(context, "cam", DoorbellAway.Settings(notifyService = "notify.mobile_app_iphone"))
        states["binary_sensor.drive_open"] = "off"
        states["binary_sensor.door"] = "off"
        states["binary_sensor.wicket"] = "off"
        states["binary_sensor.person"] = "off"
        states["cover.drive"] = "closed"
        states["lock.door"] = "locked"
        states["switch.wicket"] = "off"
        push()
    }

    @After fun tearDown() {
        DoorbellAway.save(context, "cam", DoorbellAway.Settings())
        GateHub.reset()
    }

    private fun push() { GateHub.onState(context, readings); idle() }
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun minutes(m: Double) {
        var left = (m * 60_000).toLong()
        while (left > 0) { val step = minOf(5_000L, left); idle(step); left -= step }
    }

    // ---- «Оставили открытым» ----

    @Test fun leftOpenRemindsOnceAndClearsWhenClosed() {
        states["binary_sensor.wicket"] = "on"; push()
        minutes(2.9)
        assertFalse(GateWatch.isLeftOpen("g_wicket"))
        minutes(0.3)
        assertTrue("Открыта дольше 3 мин — красная", GateWatch.isLeftOpen("g_wicket"))
        assertEquals("Калитка: открыто дольше 3 мин", phone.single()["message"])
        minutes(10.0)
        assertEquals("Одно напоминание на одно открытие", 1, phone.size)
        states["binary_sensor.wicket"] = "off"; push()
        assertFalse(GateWatch.isLeftOpen("g_wicket"))
        assertTrue("Защёлку само не закрываем", commands.isEmpty())
    }

    // ---- «Закрыть само» ----

    @Test fun driveClosesItselfOnlyWhenCameraSeesNobody() {
        states["cover.drive"] = "open"; states["binary_sensor.drive_open"] = "on"; push()
        // Человек в проходе на 5-й минуте — ворота не закрываются, пока его видно и ещё 30 с.
        minutes(4.9)
        states["binary_sensor.person"] = "on"; push()
        minutes(0.5)
        assertTrue("Кто-то в проходе — не закрываем", commands.isEmpty())
        states["binary_sensor.person"] = "off"; push()
        minutes(0.2)
        assertTrue("После человека ждём 30 с", commands.isEmpty())
        minutes(0.5)
        assertEquals(listOf("cover.close_cover cover.drive"), commands)
        GateLog.flush()
        // В журнале уже есть «открыли без команды с панели» (ворота открылись не с панели); своей записи пока нет.
        assertEquals(listOf(GateLog.Origin.OUTSIDE), GateLog.list(context, listOf("g_drive")).map { it.origin })
        states["cover.drive"] = "closed"; states["binary_sensor.drive_open"] = "off"; push()
        GateLog.flush(); idle()
        val entry = GateLog.list(context, listOf("g_drive")).first()
        assertEquals(GateLog.Origin.AUTO, entry.origin)
        assertEquals(Gate.Action.CLOSE, entry.action)
        minutes(6.0)
        assertEquals("Одна попытка, и закрытые не закрываем", 1, commands.size)
    }

    @Test fun driveWithoutCameraOrPhoneOrSensorIsNeverClosedByApp() {
        GateWatch.tiles = { emptyList() }
        states["cover.drive"] = "open"; states["binary_sensor.drive_open"] = "on"; push()
        minutes(8.0)
        assertTrue("Без камеры прохода — не закрываем", commands.isEmpty())
        assertNull("Без датчика положения «Закрыть само» недоступно", GateLogic.autoCloseKind(drive.copy(sensor = "")))
        assertNull("Защёлка через реле — нечего закрывать", GateLogic.autoCloseKind(wicket))
    }

    /**
     * Проверка аудита 23.11.2026: «не знаю» — не «пусто». Камера прохода недоступна в Home Assistant — раньше это
     * считалось пустым проходом, и ворота закрывались через 5 минут.
     */
    @Test fun unavailableCameraIsNotAnEmptyPassage() {
        states["binary_sensor.person"] = "unavailable"; push()
        states["cover.drive"] = "open"; states["binary_sensor.drive_open"] = "on"; push()
        minutes(8.0)
        assertTrue("Камера недоступна — не закрываем", commands.isEmpty())
        // Камера снова на связи и никого не видит — ворота закрываются, но не раньше чем через 30 с.
        states["binary_sensor.person"] = "off"; push()
        minutes(0.4)
        assertTrue("Камера на связи меньше 30 с", commands.isEmpty())
        minutes(0.3)
        assertEquals(listOf("cover.close_cover cover.drive"), commands)
    }

    @Test fun cameraWithoutDetectionSensorsDoesNotProveEmptyPassage() {
        val bare = TileEntity("bare", DoorbellConfig.TYPE, "grid", "Камера", 0, 0, 1, 1,
            config = DoorbellConfig(camera = "camera.door", gates = listOf("g_drive")).writeTo(DoorbellConfig.newTileConfig()))
        GateWatch.tiles = { listOf(bare) }
        DoorbellAway.save(context, "bare", DoorbellAway.Settings(notifyService = "notify.mobile_app_iphone"))
        try {
            states["cover.drive"] = "open"; states["binary_sensor.drive_open"] = "on"; push()
            minutes(8.0)
            assertTrue("Камера без датчиков людей и машин ничего не подтверждает", commands.isEmpty())
        } finally { DoorbellAway.save(context, "bare", DoorbellAway.Settings()) }
    }

    /**
     * Панель закрыли («Назад»), процесс жив ради записи: состояние Home Assistant больше не приходит. Ворота не
     * закрываются по последнему снимку, где «никого нет» (независимое ревью 24.11.2026).
     */
    @Test fun closedPanelDoesNotCloseOnFrozenState() {
        states["cover.drive"] = "open"; states["binary_sensor.drive_open"] = "on"; push()
        minutes(1.0)
        DoorbellHub.detach()
        minutes(8.0)
        assertTrue("Панели нет — по застывшему состоянию не закрываем", commands.isEmpty())
    }

    /** Датчик камеры не «on/off» («detected», «clear», число) — неизвестно, есть ли кто-то: не закрываем (ревью 24.11.2026). */
    @Test fun nonBinaryDetectionStateIsNotAnEmptyPassage() {
        states["binary_sensor.person"] = "detected"; push()
        states["cover.drive"] = "open"; states["binary_sensor.drive_open"] = "on"; push()
        minutes(8.0)
        assertTrue("«detected» — не «никого нет»", commands.isEmpty())
        states["binary_sensor.person"] = "off"; push()
        minutes(0.6)
        assertEquals(listOf("cover.close_cover cover.drive"), commands)
    }

    @Test fun positionSensorOfflineBlocksAutoClose() {
        states["cover.drive"] = "open"; states["binary_sensor.drive_open"] = "on"; push()
        minutes(1.0)
        // Датчик положения пропал: положение берётся от привода, но закрывать по нему — нет.
        states["binary_sensor.drive_open"] = "unavailable"; push()
        minutes(8.0)
        assertTrue("Без датчика положения на связи — не закрываем", commands.isEmpty())
    }

    /**
     * Ворота начали закрываться по «Закрыть само» и откатились (сработал фотоэлемент привода) — одно «не удалось
     * закрыть» и больше ни одной команды, пока их не закроют на деле. Раньше приложение закрывало снова каждые 5 минут.
     */
    @Test fun reversedAutoCloseIsNotRepeated() {
        states["cover.drive"] = "open"; states["binary_sensor.drive_open"] = "on"; push()
        minutes(5.2)
        assertEquals(listOf("cover.close_cover cover.drive"), commands)
        phone.clear()
        states["cover.drive"] = "closing"; push()
        minutes(0.1)
        states["cover.drive"] = "opening"; push()
        minutes(0.1)
        states["cover.drive"] = "open"; push()
        // Привод сам пробует закрыть ещё раз и снова откатывается (ревью 24.11.2026) — второго «не удалось» нет.
        states["cover.drive"] = "closing"; push()
        minutes(0.1)
        states["cover.drive"] = "open"; push()
        minutes(12.0)
        assertEquals("Одна команда за открытие", 1, commands.size)
        assertEquals(listOf("Ворота: не удалось закрыть"), phone.map { it["message"] }.filter { it == "Ворота: не удалось закрыть" })
        // Закрыли на деле (пультом) и открыли снова — новая попытка разрешена.
        states["cover.drive"] = "closed"; states["binary_sensor.drive_open"] = "off"; push()
        states["cover.drive"] = "open"; states["binary_sensor.drive_open"] = "on"; push()
        minutes(5.2)
        assertEquals(2, commands.size)
    }

    @Test fun unsentAutoCloseRemindsOnce() {
        GateHub.driver = GateHub.Driver { c, _ -> commands += "${c.domain}.${c.service} ${c.entity}"; false }
        states["cover.drive"] = "open"; states["binary_sensor.drive_open"] = "on"; push()
        minutes(3.1)
        phone.clear()
        minutes(2.2)
        assertEquals(1, commands.size)
        assertEquals("Одно напоминание, не два", listOf("Ворота: не удалось закрыть"), phone.map { it["message"] })
    }

    @Test fun failedAutoCloseRemindsNotCloses() {
        acked = false
        states["cover.drive"] = "open"; states["binary_sensor.drive_open"] = "on"; push()
        minutes(3.1)
        phone.clear()
        minutes(2.0)
        assertEquals(1, commands.size)
        assertEquals("Ворота: не удалось закрыть", phone.single()["message"])
    }

    @Test fun lockLocksItselfOnlyWithDoorClosed() {
        states["lock.door"] = "unlocked"; states["binary_sensor.door"] = "on"; push()
        minutes(3.0)
        assertTrue("Дверь открыта — не запираем", commands.isEmpty())
        states["binary_sensor.door"] = "off"; push()
        minutes(0.1)
        assertTrue("Дверь только что закрыли — ждём 10 с", commands.isEmpty())
        minutes(0.4)
        assertEquals(listOf("lock.lock lock.door"), commands)
    }

    // ---- Журнал ----

    @Test fun journalKeepsOwnAndOutsideOpenings() {
        assertTrue(GateHub.run("g_wicket", Gate.Action.OPEN))
        idle()
        states["switch.wicket"] = "on"; states["binary_sensor.wicket"] = "on"; push()
        states["switch.wicket"] = "off"; states["binary_sensor.wicket"] = "off"; push()
        // Открыли не с панели (пульт, ключ) — тоже в журнал.
        minutes(1.0)
        states["binary_sensor.wicket"] = "on"; push()
        GateLog.flush(); idle()
        val log = GateLog.list(context, listOf("g_wicket"))
        assertEquals(log.joinToString { "${it.atMs} ${it.origin} ${it.result}" }, listOf(GateLog.Origin.OUTSIDE, GateLog.Origin.PANEL), log.map { it.origin })
        assertTrue(log.last().result, log.last().result.contains("открыт"))
        assertTrue("Чужие проходы не в списке", GateLog.list(context, listOf("g_drive")).isEmpty())
    }

    // ---- Кнопка «Открыть» в уведомлении ----

    @Test fun phoneButtonWorksOnceAndOnlyWhileFresh() {
        val config = DoorbellConfig.from(camera.config)
        val actions = GatePhone.actions("cam", config, "notify.mobile_app_iphone")
        assertEquals("Только проходы с «можно с телефона»", listOf("Открыть дверь", "Открыть калитку"),
            (0 until actions.length()).map { actions.getJSONObject(it).getString("title") })
        val wicketAction = actions.getJSONObject(1).getString("action")
        assertTrue(wicketAction.startsWith(GatePhone.PREFIX))
        assertFalse("Чужое действие", GatePhone.onAction("OTHER_ACTION"))
        assertTrue(GatePhone.onAction(wicketAction))
        assertFalse("Второе нажатие того же уведомления — ничего", GatePhone.onAction(wicketAction))
        assertEquals(listOf("switch.turn_on switch.wicket"), commands)
        states["switch.wicket"] = "on"; states["binary_sensor.wicket"] = "on"; push()
        GateLog.flush(); idle()
        assertEquals(GateLog.Origin.PHONE, GateLog.list(context, listOf("g_wicket")).first().origin)
        assertTrue("Итог — тем же телефоном", phone.any { (it["message"] as String).startsWith("Открыт") })
        // Кнопка старше 5 минут — не исполняется.
        val stale = GatePhone.actions("cam", config, "notify.mobile_app_iphone").getJSONObject(0).getString("action")
        minutes(5.5)
        assertFalse(GatePhone.onAction(stale))
        assertEquals(1, commands.size)
    }

    @Test fun ringNotificationCarriesGateButtons() {
        DoorbellAway.onRing(context, "cam", "Домофон", DoorbellConfig.from(camera.config), 1_000L)
        val data = phone.single()["data"] as JSONObject
        val buttons = data.getJSONArray("actions")
        assertEquals(2, buttons.length())
        // Без «можно с телефона» — уведомление как раньше, без кнопок.
        GateStore.save(context, listOf(drive, lock.copy(phone = false), wicket.copy(phone = false)))
        GateHub.invalidate()
        phone.clear()
        DoorbellAway.onRing(context, "cam", "Домофон", DoorbellConfig.from(camera.config), 2_000L)
        assertFalse((phone.single()["data"] as JSONObject).has("actions"))
    }

    // ---- Плитка «Проход» ----

    @Test fun gateTileHoldOpensTapExpands() {
        val config = GateTileConfig.newTileConfig("{}", "g_wicket")
        assertEquals("g_wicket", GateTileConfig.gateId(config))
        assertEquals("g_drive", GateTileConfig.gateId(GateTileConfig.withGate(config, "g_drive")))
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val (tile, _) = GateTileCard.createTile(activity, "t1", 600, 300, isEditMode = false)
        activity.findViewById<ViewGroup>(android.R.id.content).addView(tile)
        val card = tile.findViewWithTag<GateTileCard>("gate_compact")
        var held: Pair<String, Gate.Action>? = null
        var taps = 0
        card.bind("g_wicket")
        card.onAction = { gate, action -> held = gate.id to action }
        card.onTap = { taps++ }
        idle()
        assertEquals("Калитка", card.button.label)
        assertTrue(card.button.stateHint.orEmpty(), card.button.stateHint.orEmpty().startsWith("Закрыт"))
        touch(card.button, 100)
        assertEquals("Касание — раскрыть", 1, taps)
        assertNull(held)
        touch(card.button, 1_100)
        assertEquals("g_wicket" to Gate.Action.OPEN, held)
        // В режиме редактирования кнопка касаний не берёт: плитку двигают и меняют размер.
        card.bind("g_wicket", editMode = true)
        assertFalse(card.button.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis())))
        // Оставили открытым — плитка красная.
        states["binary_sensor.wicket"] = "on"; push()
        minutes(3.1)
        assertEquals(DoorbellFrame.OFFLINE, card.button.idleAccent)
    }

    private fun event(action: Int, down: Long, at: Long = down) = MotionEvent.obtain(down, at, action, 10f, 10f, 0)

    private fun touch(view: View, ms: Long) {
        val t = SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN, t).also { view.dispatchTouchEvent(it); it.recycle() }
        var left = ms
        while (left > 0) { val step = minOf(50L, left); idle(step); left -= step }
        event(MotionEvent.ACTION_UP, t, t + ms).also { view.dispatchTouchEvent(it); it.recycle() }
        idle()
    }

    @Suppress("unused") private fun ids(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it).getString("action") }
}
