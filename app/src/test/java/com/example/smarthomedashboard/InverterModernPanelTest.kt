package com.example.smarthomedashboard

import android.app.AlertDialog
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.TimeUnit

// Настоящие MainActivity, контроллер панели, ViewModel и клиент HA с записывающим сокетом:
// команды не уходят в сеть, а только записываются. XML/onCreate не запускаются.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class InverterModernPanelTest {
    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).get().apply { pinGestureFixture(this) } }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val viewModel get() = MainActivity::class.java.getDeclaredMethod("getViewModel")
        .apply { isAccessible = true }.invoke(activity) as MainViewModel
    private val controller get() = MainActivity::class.java.getDeclaredMethod("getPanelController")
        .apply { isAccessible = true }.invoke(activity) as DashboardPanelController
    private val state get() = ReflectionHelpers.getField<MutableStateFlow<MainUiState>>(viewModel, "_uiState")
    private val socket = RecordingSocket()

    private class RecordingSocket : WebSocket {
        val messages = mutableListOf<JSONObject>()
        override fun request() = Request.Builder().url("http://localhost/test-only").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(JSONObject(text)); return true }
        override fun send(bytes: ByteString): Boolean = error("Unexpected binary message")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {
            // Только запись команд, сети нет.
        }
    }

    @Before fun noSession() = PinSecurityUi.session.revoke()
    @After fun cleanup() {
        PinSecurityUi.session.revoke()
        PanelMotion.dimmed = false
    }

    private val roles = listOf(InverterModernConfig.ROLE_PV_POWER, InverterModernConfig.ROLE_GRID_POWER,
        InverterModernConfig.ROLE_BATTERY_POWER, InverterModernConfig.ROLE_LOAD_POWER, InverterModernConfig.ROLE_BATTERY_SOC,
        InverterModernConfig.ROLE_PV1_POWER, InverterModernConfig.ROLE_FAULT, InverterModernConfig.ROLE_PRIORITY)

    private fun tileConfig(): String {
        var c = InverterModernConfig()
        roles.forEach { role ->
            val id = if (role == InverterModernConfig.ROLE_PRIORITY) "select.inv_priority" else "sensor.inv_$role"
            c = c.withRow(c.row(role)!!.copy(entityId = id))
        }
        return c.writeTo(InverterModernConfig.newTileConfig())
    }

    private fun open(values: Map<String, String>) {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = 1920
        activity.resources.displayMetrics.heightPixels = 1200
        manager.saveTiles(listOf(
            TileEntity("inv", InverterModernConfig.TYPE, "grid", "Инвертор", 40, 140, 1, 1, config = tileConfig()),
            TileEntity("grid", PowerModernConfig.TYPE, "grid", "Сеть · Вход", 420, 140, 1, 1,
                config = PowerModernConfig.profile(PowerModernConfig.PROFILE_GRID).writeTo("""{"button_size":300,"widget_height":360}"""))
        ))
        ReflectionHelpers.setField(activity, "tileManager", manager)
        ReflectionHelpers.setField(activity, "bottomPanel", panel)
        ReflectionHelpers.setField(activity, "dimOverlay", View(activity).apply { visibility = View.GONE })
        ReflectionHelpers.setField(activity, "yandexWeatherManager", YandexWeatherManager(activity))
        val root = FrameLayout(activity)
        root.addView(panel)
        activity.window.setContentView(root)
        ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
        ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
        val client = HomeAssistantWebSocket("http://localhost/test-only", "test-token", { _, _, _ -> }, {}, {})
        ReflectionHelpers.setField(client, "authenticated", true)
        ReflectionHelpers.setField(client, "webSocket", socket)
        ArchitectureTestAccess.setField(viewModel, "webSocket", client)
        // Как в onCreate: смена ожидания команды перерисовывает зависящие плитки.
        ArchitectureTestAccess.setField(viewModel, "commandStateCallback", { id: String -> controller.renderCommandState(id) })
        state.value = state.value.copy(haSession = HaSession(connected = true))
        viewModel.onStatesSnapshot(values.map { (id, v) -> HaEntityState(id, v, attributes(id)) })
        state.value = state.value.copy(haSession = HaSession(connected = true, synced = true))
        ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
        layout()
    }

    private fun attributes(id: String) = when {
        id == "select.inv_priority" -> JSONObject().put("options", JSONArray(listOf("UTI", "SOL", "SBU", "SUB")))
        id.endsWith("_soc") -> JSONObject().put("unit_of_measurement", "%")
        id.endsWith("power") -> JSONObject().put("unit_of_measurement", "W")
        else -> JSONObject()
    }

    private fun values(load: String = "3850", fault: String = "0") = mapOf(
        "sensor.inv_pv_power" to "3200", "sensor.inv_grid_power" to "0", "sensor.inv_battery_power" to "-650",
        "sensor.inv_load_power" to load, "sensor.inv_battery_soc" to "76", "sensor.inv_pv1_power" to "1600",
        "sensor.inv_fault" to fault, "select.inv_priority" to "SBU", "sensor.v" to "228")

    private fun layout() {
        for (i in 0 until panel.childCount) {
            val v = panel.getChildAt(i)
            v.measure(View.MeasureSpec.makeMeasureSpec(v.layoutParams.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(v.layoutParams.height, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, v.layoutParams.width, v.layoutParams.height)
        }
    }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            left -= step
        }
    }

    private fun card(): InverterModernCard = panel.findViewWithTag<FrameLayout>("inv").findViewWithTag("inv_compact")
    private fun text(view: View, tag: String) = view.findViewWithTag<TextView>(tag).text.toString()
    private fun event(id: String, value: String) {
        viewModel.onEntityStateChanged(id, value, attributes(id))
        controller.renderEntityState(id, value)
    }
    private fun calls() = socket.messages.filter { it.optString("type") == "call_service" }

    private fun tap(view: View) {
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
            val e = MotionEvent.obtain(time, time + i, action, 20f, 20f, 0)
            try { view.dispatchTouchEvent(e) } finally { e.recycle() }
        }
    }

    private fun expand(): InverterModernCard {
        tap(panel.findViewWithTag<FrameLayout>("inv"))
        advance(400)
        assertTrue(controller.modernExpansion.isOpen)
        val expanded = controller.modernExpansion.card as InverterModernCard
        expanded.measure(View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY))
        expanded.layout(0, 0, 1500, 960)
        return expanded
    }

    @Test fun compactShowsFlowsAndConfirmedColor() {
        open(values())
        assertEquals("От солнца и АКБ", text(card(), "inv_status"))
        assertEquals(PowerModernConfig.EMERALD, card().accent)
        val flow = card().flow
        assertEquals(3200.0, flow.boundSnapshot!!.solar!!, 0.0)
        // Перегруз: цифры сразу, цвет и подпись — после подтверждения (без мерцания).
        event("sensor.inv_load_power", "9300")
        assertEquals(9300.0, flow.boundSnapshot!!.load!!, 0.0)
        assertEquals(PowerModernConfig.EMERALD, card().accent)
        assertEquals("От солнца и АКБ", text(card(), "inv_status"))
        advance(2_500)
        assertEquals(InverterModel.AMBER, card().accent)
        assertEquals("Перегруз 85%", text(card(), "inv_status"))
        // Авария — красная с пульсацией.
        event("sensor.inv_fault", "Перегрев")
        advance(2_500)
        assertEquals(PowerModernConfig.RED, card().accent)
        assertTrue(card().pulseRequested)
        assertEquals("Авария: Перегрев", text(card(), "inv_status"))
    }

    @Test fun connectionLossIsImmediateNeutralAndDotsStop() {
        open(values())
        viewModel.devices.markDisconnected()
        state.value = state.value.copy(haSession = HaSession())
        controller.renderSessionState()
        assertEquals("Нет связи с Home Assistant", text(card(), "inv_status"))
        advance(600)
        assertEquals(PowerModernConfig.NEUTRAL, card().accent)
        assertNull(card().flow.boundSnapshot!!.load)
    }

    @Test fun expandedShowsDetailsByNodeAndOnlyConfiguredRows() {
        open(values())
        val expanded = expand()
        assertEquals("1600", text(expanded, "inv_value_${InverterModernConfig.ROLE_PV1_POWER}").substringBefore(" "))
        assertNotNull(expanded.findViewWithTag<View>("inv_group_solar"))
        assertNotNull(expanded.findViewWithTag<View>("inv_group_battery"))
        assertNull("Не настроено — не показывается", expanded.findViewWithTag<View>("inv_row_${InverterModernConfig.ROLE_PV2_POWER}"))
        assertEquals("Солнце → АКБ → Сеть", text(expanded, "inv_priority_current"))
        assertEquals(4, expanded.findViewWithTag<android.view.ViewGroup>("inv_priority_chips").childCount)
        assertEquals("нет", text(expanded, "inv_value_${InverterModernConfig.ROLE_FAULT}"))
    }

    @Test fun priorityChangeAsksConfirmationThenSendsSelectOptionAndWaitsForFact() {
        open(values())
        val expanded = expand()
        expanded.findViewWithTag<View>("inv_option_UTI").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertTrue(calls().isEmpty())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advance(100)
        val call = calls().single()
        assertEquals("select", call.getString("domain"))
        assertEquals("select_option", call.getString("service"))
        assertEquals("select.inv_priority", call.getJSONObject("service_data").getString("entity_id"))
        assertEquals("UTI", call.getJSONObject("service_data").getString("option"))
        // Ожидание факта: подпись «Переключение…», варианты недоступны, повтор не отправляется.
        assertEquals("Переключение: Сеть первой…", text(expanded, "inv_priority_current"))
        expanded.findViewWithTag<View>("inv_option_SOL").performClick()
        assertFalse("Во время ожидания вариант не нажимается", ShadowAlertDialog.getLatestAlertDialog() !== dialog &&
            (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).isShowing)
        assertEquals(1, calls().size)
        // Факт HA — ожидание снято, выбран новый вариант.
        event("select.inv_priority", "UTI")
        assertEquals("Сеть первой", text(expanded, "inv_priority_current"))
        assertNull(viewModel.commands.pendingFor("select.inv_priority"))
    }

    @Test fun cancelOrMissingPinSendsNothing() {
        open(values())
        val expanded = expand()
        expanded.findViewWithTag<View>("inv_option_UTI").performClick()
        (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        advance(100)
        assertTrue(calls().isEmpty())
        // Состояние PIN неизвестно, сессии нет: подтверждение сначала ведёт к проверке PIN, команда сразу не уходит.
        ReflectionHelpers.setField(activity, "pinDisabled", null)
        expanded.findViewWithTag<View>("inv_option_UTI").performClick()
        (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(calls().isEmpty())
        assertNull(viewModel.commands.pendingFor("select.inv_priority"))
    }

    @Test fun recreatedUiKeepsConfirmedLookAndOtherTilesUntouched() {
        open(values(load = "9300"))
        advance(2_500)
        assertEquals(InverterModel.AMBER, card().accent)
        val gridBinds = panel.findViewWithTag<FrameLayout>("grid").findViewWithTag<PowerModernCard>("pm_compact").binds
        event("sensor.inv_load_power", "9400")
        assertEquals("Событие инвертора не трогает другие плитки", gridBinds,
            panel.findViewWithTag<FrameLayout>("grid").findViewWithTag<PowerModernCard>("pm_compact").binds)
        ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
        layout()
        assertEquals("Новая View сразу с подтверждённым цветом", InverterModel.AMBER, card().accent)
    }

    @Test fun oldValueEventKeepsWaitingAndRejectClearsPendingOnCard() {
        open(values())
        val expanded = expand()
        expanded.findViewWithTag<View>("inv_option_UTI").performClick()
        (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advance(50)
        val request = calls().single()
        val client = viewModel.getWebSocket()!!
        fun reply(success: Boolean) = ArchitectureTestAccess.methods(client).single { it.name == "handleMessage" && it.parameterTypes.size == 1 }
            .apply { isAccessible = true }.invoke(client, requireNotNull(HaInboundMessage.parse(JSONObject().put("type", "result")
                .put("id", request.getInt("id")).put("success", success).toString())))
        reply(true)
        // Опросная интеграция прислала прежнее значение: инвертор ещё не применил — ждём дальше, без ложной ошибки.
        event("select.inv_priority", "SBU")
        assertNotNull(viewModel.commands.pendingFor("select.inv_priority"))
        assertEquals("Переключение: Сеть первой…", text(expanded, "inv_priority_current"))
        // 20 с — ещё ждём (опрос раз в 30–60 с); через 90 с — ошибка, но прежнее значение остаётся фактом.
        advance(21_000)
        assertNotNull(viewModel.commands.pendingFor("select.inv_priority"))
        advance(70_000)
        assertNull(viewModel.commands.pendingFor("select.inv_priority"))
    }

    @Test fun deadlineKeepsLastValueAndChipsUsable() {
        open(values())
        viewModel.commands.selectOption("select.inv_priority", "UTI")
        advance(91_000)
        assertNull(viewModel.commands.pendingFor("select.inv_priority"))
        assertFalse("Значение HA не помечается недостоверным", "select.inv_priority" in viewModel.devices.staleEntities)
        val expanded = expand()
        assertEquals("Солнце → АКБ → Сеть", text(expanded, "inv_priority_current"))
        assertTrue(expanded.findViewWithTag<View>("inv_option_UTI").hasOnClickListeners())
    }

    @Test fun rejectedCommandUpdatesOpenCardWithoutEntityEvent() {
        open(values())
        val expanded = expand()
        expanded.findViewWithTag<View>("inv_option_UTI").performClick()
        (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advance(50)
        val request = calls().single()
        val client = viewModel.getWebSocket()!!
        ArchitectureTestAccess.methods(client).single { it.name == "handleMessage" && it.parameterTypes.size == 1 }
            .apply { isAccessible = true }.invoke(client, requireNotNull(HaInboundMessage.parse(JSONObject().put("type", "result")
                .put("id", request.getInt("id")).put("success", false).toString())))
        advance(50)
        assertEquals("Солнце → АКБ → Сеть", text(expanded, "inv_priority_current"))
    }

    @Test fun forgottenConfirmationClosesItselfAndCollapseCancelsIt() {
        open(values())
        val expanded = expand()
        expanded.findViewWithTag<View>("inv_option_UTI").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        // Как на устройстве: диалог забирает фокус окна, автосворачивание карточки на паузе.
        controller.setInteractive(false)
        advance(DashboardPanelController.PRIORITY_DIALOG_MS + 100)
        controller.setInteractive(true)
        assertTrue(controller.modernExpansion.isOpen)
        assertFalse("Через 30 с закрыто", dialog.isShowing)
        assertTrue(calls().isEmpty())
        expanded.findViewWithTag<View>("inv_option_UTI").performClick()
        val second = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "collapseModernBattery")
        advance(50)
        assertFalse("Карточка свернулась — подтверждение закрыто", second.isShowing)
        assertTrue(calls().isEmpty())
    }

    @Test fun backCollapsesExpandedCardInsteadOfLeavingPanel() {
        open(values())
        assertFalse("Нечего сворачивать — «Назад» идёт дальше", controller.collapseOnBack())
        expand()
        assertTrue(controller.collapseOnBack())
        advance(600)
        assertFalse(controller.modernExpansion.isOpen)
        assertFalse(activity.isFinishing)
    }
}
