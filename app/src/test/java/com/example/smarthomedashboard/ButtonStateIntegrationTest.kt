package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.PorterDuffColorFilter
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.*
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowResources
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

// Объединяем существующие подмены drawable кнопки и строки выбора для двух экранов.
// Эта подмена не подтверждает оформление APK на планшете.
@Suppress("unused") // Методы вызываются Robolectric через @Implementation.
@Implements(Resources::class)
class ButtonStateResources : ShadowResources() {
    @RealObject private lateinit var resources: Resources

    @Implementation
    fun getDrawable(id: Int, theme: Resources.Theme?): Drawable {
        if (id == R.drawable.bg_button_rounded) {
            return GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(0xFF424242.toInt())
            }
        }
        return Shadow.directlyOn(resources, Resources::class.java, "getDrawable",
            ClassParameter.from(Int::class.javaPrimitiveType, id),
            ClassParameter.from(Resources.Theme::class.java, theme))
    }

    @Implementation
    fun getText(id: Int): CharSequence {
        if (id == R.string.none_selected) return "Не выбрано"
        return Shadow.directlyOn(resources, Resources::class.java, "getText",
            ClassParameter.from(Int::class.javaPrimitiveType, id))
    }
}

// Реальные callbacks, сохранение и обработчики; XML/onCreate и реальная сеть не запускаются.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [ButtonStateResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ButtonStateIntegrationTest {
    private val activity by lazy {
        Robolectric.buildActivity(MainActivity::class.java).get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            resources.displayMetrics.widthPixels = 1920
            resources.displayMetrics.heightPixels = 1200
        }
    }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val socket = RecordingSocket()

    private class RecordingSocket : WebSocket {
        val messages = mutableListOf<JSONObject>()
        var accepts = true
        override fun request() = Request.Builder().url("http://localhost/test-only").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            if (accepts) messages.add(JSONObject(text))
            return accepts
        }
        override fun send(bytes: ByteString): Boolean = error("Unexpected binary message")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {
            // Соединения с сетью нет: этот сокет только записывает команды.
        }
    }

    private fun set(target: Any, name: String, value: Any?) {
        ArchitectureTestAccess.setField(target, name, value)
    }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? = try {
        ArchitectureTestAccess.methods(target).single { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private fun group(id: String = "group", vararg ids: String) =
        TileEntity(id, "group", "bottom_panel", "Группа", 20, 300, 1, 1,
            config = JSONObject().put("entity_ids", JSONArray(ids.toList())).toString())
    private fun button(id: String, eid: String) =
        TileEntity(id, "button", "bottom_panel", "Кнопка", 250, 300, 1, 1,
            config = JSONObject().put("entity_id", eid).toString())

    private fun open(vararg tiles: TileEntity) {
        manager.saveTiles(tiles.toList())
        set(activity, "tileManager", manager)
        set(activity, "bottomPanel", panel)
        val root = FrameLayout(activity)
        root.addView(panel)
        val dim = View(activity).apply { visibility = View.GONE }
        root.addView(dim)
        activity.window.setContentView(root)
        // XML AppCompat здесь не загружается; findViewById работает с программным деревом окна.
        ArchitectureTestAccess.setField(activity.delegate, "mSubDecorInstalled", true)
        ArchitectureTestAccess.setField(activity.delegate, "mWindow", activity.window)
        set(activity, "dimOverlay", dim)
        val client = HomeAssistantWebSocket("http://localhost/test-only", "test-token", { _, _, _ -> }, {}, {})
        set(client, "authenticated", true)
        set(client, "webSocket", socket)
        val owner = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
        ArchitectureTestAccess.setField(owner, "webSocket", client)
        ArchitectureTestAccess.setField(owner, "commandStateCallback", { id: String ->
            invoke(activity, "renderCommandState", id)
        })
        invoke(activity, "refreshBottomPanel")
    }

    private fun view(id: String): Button = panel.findViewWithTag(id)
    private fun color(id: String): Int {
        val filter = ArchitectureTestAccess.getField<PorterDuffColorFilter>(view(id).background, "mTintFilter")
        return shadowOf(filter).color
    }
    private fun event(eid: String, state: String) {
        invoke(activity, "updateTilesForEntity", eid, state, null)
    }
    private fun owner() = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
    private fun ack(request: JSONObject, success: Boolean = true) {
        val message = JSONObject().put("type", "result").put("id", request.getInt("id")).put("success", success)
        invoke(owner().getWebSocket()!!, "handleMessage", requireNotNull(HaInboundMessage.parse(message.toString())))
    }
    private fun childColor(child: Button): Int = shadowOf(
        ArchitectureTestAccess.getField<PorterDuffColorFilter>(child.background, "mTintFilter")).color

    private fun calls() = socket.messages.filter { it.optString("type") == "call_service" }
    private fun send(view: View, action: Int) {
        val event = MotionEvent.obtain(0, 1, action, 5f, 5f, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun tap(id: String) {
        val button = view(id)
        button.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY))
        button.layout(0, 0, 160, 160)
        send(button, MotionEvent.ACTION_DOWN)
        send(view(id), MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun returned() {
        invoke(activity, "onActivityResult", 100, Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun saveSelection(id: String, selected: List<String>) {
        val settings = Robolectric.buildActivity(TileSettingsActivity::class.java,
            Intent(activity, TileSettingsActivity::class.java).putExtra("tile_id", id)).get()
        set(settings, "tileManager", manager)
        set(settings, "tileId", id)
        set(settings, "etTitle", EditText(settings))
        set(settings, "etButtonSize", EditText(settings))
        set(settings, "tvSelectedSensors", TextView(settings))
        set(settings, "spinnerType", Spinner(settings).apply {
            adapter = ArrayAdapter(settings, android.R.layout.simple_spinner_item,
                listOf("sensor", "button", "group", "battery", "clock", "weather"))
        })
        invoke(settings, "loadTileData")
        invoke(settings, "onActivityResult", 1001, Activity.RESULT_OK,
            Intent().putStringArrayListExtra("selected_sensors", ArrayList(selected)))
        invoke(settings, "saveTile")
        shadowOf(Looper.getMainLooper()).idle()
        returned()
    }

    @Test fun removingActiveMemberThroughSettingsMakesGroupOffAndTurnsRemainingMemberOn() {
        open(group("group", "switch.a", "switch.b"))
        event("switch.a", "on")
        event("switch.b", "off")
        assertEquals(0x8033CC33.toInt(), color("group"))
        saveSelection("group", listOf("switch.b"))
        assertEquals(listOf("switch.b"), JSONObject(manager.loadTiles().single().config)
            .getJSONArray("entity_ids").let { (0 until it.length()).map(it::getString) })
        assertEquals(0xFF424242.toInt(), color("group"))
        tap("group")
        assertEquals(listOf("turn_on"), calls().map { it.getString("service") })
        assertEquals(listOf("switch.b"), calls().map { it.getJSONObject("service_data").getString("entity_id") })
    }

    @Test fun remainingActiveMemberKeepsItsKnownState() {
        open(group("group", "switch.a", "switch.b"))
        event("switch.a", "off")
        event("switch.b", "on")
        saveSelection("group", listOf("switch.b"))
        assertEquals(0x8033CC33.toInt(), color("group"))
        tap("group")
        assertEquals("turn_off", calls().single().getString("service"))
    }

    @Test fun addingAlreadyKnownOnMemberImmediatelyChangesGroupStateAndCommand() {
        open(group("group", "switch.b"))
        event("switch.b", "off")
        event("light.a", "on")
        saveSelection("group", listOf("switch.b", "light.a"))
        assertEquals(0x8033CC33.toInt(), color("group"))
        tap("group")
        assertEquals(listOf("turn_off", "turn_off"), calls().map { it.getString("service") })
        assertEquals(listOf("switch.b", "light.a"), calls().map { it.getJSONObject("service_data").getString("entity_id") })
    }

    @Test fun emptyAndCorruptGroupsIgnoreOldActiveCacheAndNeverSendCommands() {
        open(group("group", "switch.a"))
        event("switch.a", "on")
        listOf("{}", """{"entity_ids":[]}""", "{broken", """{"entity_ids":["switch.a",null]}""").forEach { config ->
            val tile = manager.loadTiles().single().copy(config = config)
            manager.updateTile(tile)
            ArchitectureTestAccess.getField<MutableMap<String, MutableMap<String, String>>>(activity, "groupStates")["group"] =
                mutableMapOf("switch.a" to "on")
            returned()
            assertEquals(0xFF424242.toInt(), color("group"))
            socket.messages.clear()
            tap("group")
            assertTrue(calls().isEmpty())
            assertEquals(config, manager.loadTiles().single().config)
        }
    }

    @Test fun groupsRemainIndependentWhenMembershipChanges() {
        open(group("first", "switch.a", "switch.b"), group("second", "switch.a"))
        event("switch.a", "on")
        event("switch.b", "off")
        saveSelection("first", listOf("switch.b"))
        assertEquals(0xFF424242.toInt(), color("first"))
        assertEquals(0x8033CC33.toInt(), color("second"))
        tap("first")
        tap("second")
        assertEquals(listOf("turn_on", "turn_off"), calls().map { it.getString("service") })
    }

    @Test fun lightAndSwitchEventsUpdateOnlyButtonsWithMatchingSource() {
        open(button("light", "light.a"), button("switch", "switch.b"), button("other", "light.other"))
        event("light.a", "on")
        assertEquals(0x8033CC33.toInt(), color("light"))
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("switch"))
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("other"))
        event("switch.b", "on")
        assertEquals(0x8033CC33.toInt(), color("switch"))
        event("light.a", "off")
        assertEquals(0xFF424242.toInt(), color("light"))
        assertEquals(0x8033CC33.toInt(), color("switch"))
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("other"))
    }

    @Test fun arbitraryDomainAndChangedOrdinarySourceUseCurrentEntityId() {
        open(button("custom", "custom.a"), button("other", "light.other"))
        event("custom.a", "on")
        assertEquals(0x8033CC33.toInt(), color("custom"))
        saveSelection("custom", listOf("light.new"))
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("custom"))
        event("custom.a", "on")
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("custom"))
        event("light.new", "on")
        assertEquals(0x8033CC33.toInt(), color("custom"))
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("other"))
    }

    @Test fun ordinaryTapShowsPendingForBothDirectionsUntilFactsArrive() {
        open(button("light", "light.a"))
        event("light.a", "off")
        view("light").performClick()
        assertEquals("light", calls().single().getString("domain"))
        assertEquals("turn_on", calls().single().getString("service"))
        assertEquals(ButtonCommandAppearance.PENDING, color("light"))
        assertTrue(view("light").text.contains("Включается"))
        view("light").performClick()
        assertEquals(1, calls().size)
        event("light.a", "on")
        assertEquals(ButtonCommandAppearance.ON, color("light"))
        view("light").performClick()
        assertEquals("turn_off", calls().last().getString("service"))
        assertEquals(ButtonCommandAppearance.PENDING, color("light"))
        assertTrue(view("light").text.contains("Выключается"))
        event("light.a", "off")
        assertEquals(ButtonCommandAppearance.OFF, color("light"))
        assertEquals("Кнопка", view("light").text.toString())
    }

    @Test fun longPressExpandsKnownAddedMemberWithoutSendingCommandAndChildStillToggles() {
        open(group("group", "switch.b"))
        event("switch.b", "off")
        event("light.a", "on")
        saveSelection("group", listOf("switch.b", "light.a"))
        socket.messages.clear()
        send(view("group"), MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        send(view("group"), MotionEvent.ACTION_UP)
        assertEquals("group", ArchitectureTestAccess.getField<String>(activity, "expandedGroupId"))
        val children = ArchitectureTestAccess.getField<List<Button>>(activity, "expandedChildButtons")
        assertEquals(listOf("switch.b", "light.a"), children.map { it.tag })
        val tint = ArchitectureTestAccess.getField<PorterDuffColorFilter>(children[1].background, "mTintFilter")
        assertEquals(0x8033CC33.toInt(), shadowOf(tint).color)
        assertTrue(calls().isEmpty())
        children[1].performClick()
        assertEquals("turn_off", calls().single().getString("service"))
        assertEquals("light.a", calls().single().getJSONObject("service_data").getString("entity_id"))
        assertEquals(ButtonCommandAppearance.PENDING, color("group"))
    }

    @Test fun realUnknownAndUnavailableUpdateStateAndRemainInactive() {
        open(group("group", "light.a"), button("light", "light.a"))
        event("light.a", "on")
        event("light.a", "unknown")
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("group"))
        // Настоящий unknown больше не оставляет устаревший on у обычной кнопки.
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("light"))
        event("light.a", "unavailable")
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("group"))
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("light"))
    }

    @Test fun removedActiveMemberCannotSelectTurnOffForRemainingOffMember() {
        open(group("group", "switch.a", "switch.b"))
        event("switch.a", "on")
        event("switch.b", "off")
        saveSelection("group", listOf("switch.b"))
        tap("group")
        assertEquals("turn_on", calls().single().getString("service"))
        assertEquals("switch.b", calls().single().getJSONObject("service_data").getString("entity_id"))
    }


    @Test fun evenPreviouslyCreatedHandlerUsesCurrentCompositionAtTap() {
        open(group("group", "switch.a", "switch.b"))
        event("switch.a", "on")
        event("switch.b", "off")
        // Имитируем смену конфигурации до перестроения Views; команды всё равно
        // должны адресоваться текущему составу, а не захваченному TileEntity.
        manager.updateTile(group("group", "switch.b"))
        tap("group")
        assertEquals(listOf("turn_on"), calls().map { it.getString("service") })
        assertEquals(listOf("switch.b"), calls().map { it.getJSONObject("service_data").getString("entity_id") })
    }


    @Test fun failedSendNeverChangesButtonColorOrStateOnRepeatedPresses() {
        open(button("single", "switch.test"))
        event("switch.test", "off")
        socket.accepts = false
        repeat(2) {
            view("single").performClick()
            assertEquals(0xFF424242.toInt(), color("single"))
            assertEquals("off", ArchitectureTestAccess.getField<Map<String, String>>(activity, "singleStates")["switch.test"])
        }
        assertTrue(calls().isEmpty())
    }

    @Test fun rejectedAckRestoresSingleAndChildColorsWithoutSyntheticSensorEvents() {
        open(button("single", "switch.test"), group("group", "switch.test"))
        event("switch.test", "off")
        view("single").performClick()
        assertEquals(ButtonCommandAppearance.PENDING, color("single"))
        val owner = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
        val client = requireNotNull(owner.getWebSocket())
        fun rejectLast() {
            val message = JSONObject().put("type", "result").put("id", calls().last().getInt("id")).put("success", false)
            client.javaClass.getDeclaredMethod("handleMessage", HaInboundMessage::class.java)
                .apply { isAccessible = true }.invoke(client, requireNotNull(HaInboundMessage.parse(message.toString())))
        }
        rejectLast()
        assertEquals(0xFF424242.toInt(), color("single"))
        invoke(activity, "expandChildButtons", manager.loadTiles().single { it.id == "group" }, view("group"))
        val child = ArchitectureTestAccess.getField<List<Button>>(activity, "expandedChildButtons").single()
        child.performClick()
        assertEquals(ButtonCommandAppearance.PENDING, shadowOf(ArchitectureTestAccess.getField<PorterDuffColorFilter>(child.background, "mTintFilter")).color)
        rejectLast()
        assertEquals(0xFF424242.toInt(), color("group"))
        assertEquals(0xFF424242.toInt(), shadowOf(ArchitectureTestAccess.getField<PorterDuffColorFilter>(child.background, "mTintFilter")).color)
        child.performClick()
        event("switch.test", "off")
        assertEquals(ButtonCommandAppearance.PENDING, shadowOf(ArchitectureTestAccess.getField<PorterDuffColorFilter>(child.background, "mTintFilter")).color)
    }
    @Test fun acceptedTapDoesNotChangeConfirmedStateOrShowGreenBeforeFact() {
        open(button("single", "switch.test"))
        event("switch.test", "off")
        view("single").performClick()
        assertEquals("off", ArchitectureTestAccess.getField<Map<String, String>>(activity, "singleStates")["switch.test"])
        assertEquals(0xFF80652F.toInt(), color("single"))
        val client = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].getWebSocket()!!
        val ack = JSONObject().put("type", "result").put("id", calls().single().getInt("id")).put("success", true)
        invoke(client, "handleMessage", requireNotNull(HaInboundMessage.parse(ack.toString())))
        assertEquals(0xFF80652F.toInt(), color("single"))
        event("switch.test", "on")
        assertEquals(0x8033CC33.toInt(), color("single"))
    }

    @Test fun duplicateButtonsChildrenAndGroupSharePendingAndConfirmedColor() {
        open(button("first", "switch.test"), button("second", "switch.test"), group("group", "switch.test"))
        event("switch.test", "off")
        invoke(activity, "expandChildButtons", manager.loadTiles().single { it.id == "group" }, view("group"))
        val child = ArchitectureTestAccess.getField<List<Button>>(activity, "expandedChildButtons").single()
        child.performClick()
        listOf("first", "second", "group").forEach { assertEquals(ButtonCommandAppearance.PENDING, color(it)) }
        assertEquals(ButtonCommandAppearance.PENDING, childColor(child))
        view("first").performClick()
        tap("group")
        assertEquals(1, calls().size)
        ack(calls().single())
        listOf("first", "second", "group").forEach { assertEquals(ButtonCommandAppearance.PENDING, color(it)) }
        event("switch.test", "on")
        listOf("first", "second", "group").forEach { assertEquals(ButtonCommandAppearance.ON, color(it)) }
        assertEquals(ButtonCommandAppearance.ON, childColor(child))
    }

    @Test fun partialGroupExecutionNeverConfusesAckWithActualMemberStates() {
        open(group("group", "switch.a", "switch.b"), button("a", "switch.a"), button("b", "switch.b"))
        event("switch.a", "off")
        event("switch.b", "off")
        tap("group")
        calls().forEach { ack(it) }
        assertEquals(ButtonCommandAppearance.PENDING, color("group"))
        assertEquals(ButtonCommandAppearance.PENDING, color("a"))
        event("switch.a", "on")
        assertEquals(ButtonCommandAppearance.ON, color("a"))
        assertEquals(ButtonCommandAppearance.PENDING, color("group"))
        // Второй участник не переключился: ожидание ограничено и не создаёт повторной команды.
        shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS)
        assertEquals(ButtonCommandAppearance.ON, color("group")) // Прежняя семантика any-on.
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("b"))
        assertEquals("off", owner().uiState.value.singleStates["switch.b"])
        assertEquals(2, calls().size)
    }

    @Test fun timeoutUnavailableAndRecoveryAreVisibleWithoutLateRollback() {
        open(button("single", "switch.test"))
        event("switch.test", "on")
        view("single").performClick()
        val request = calls().single()
        ack(request)
        shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS)
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("single"))
        assertTrue(view("single").text.contains("Нет данных"))
        event("switch.test", "off")
        assertEquals(ButtonCommandAppearance.OFF, color("single"))
        ack(request, false)
        assertEquals(ButtonCommandAppearance.OFF, color("single"))
        view("single").performClick()
        event("switch.test", "unknown")
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("single"))
        assertTrue(owner().uiState.value.pendingCommands.isEmpty())
    }

    @Test fun rebuildRetainsPendingAndDetachReleasesViewsWithoutResending() {
        open(button("single", "switch.test"))
        event("switch.test", "off")
        view("single").performClick()
        val oldView = view("single")
        val operation = owner().uiState.value.pendingCommands.getValue("switch.test")
        owner().detachUi()
        invoke(activity, "refreshBottomPanel")
        assertNotSame(oldView, view("single"))
        assertEquals(ButtonCommandAppearance.PENDING, color("single"))
        assertEquals(operation, owner().uiState.value.pendingCommands["switch.test"])
        assertFalse(oldView.hasOnClickListeners())
        assertEquals(1, calls().size)
        ArchitectureTestAccess.setField(owner(), "commandStateCallback", { id: String -> invoke(activity, "renderCommandState", id) })
        owner().disconnectWebSocket()
        assertEquals(ButtonCommandAppearance.UNAVAILABLE, color("single"))
        assertTrue(owner().uiState.value.pendingCommands.isEmpty())
        assertTrue(ArchitectureTestAccess.getField<Map<*, *>>(owner().commands, "deadlines").isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.DAYS)
        assertEquals(1, calls().size)
    }

    @Test fun replacementActivityUsesRetainedOwnerAndFreshViewsWithoutResending() {
        open(button("single", "switch.test"))
        event("switch.test", "off")
        view("single").performClick()
        val retained = owner()
        val oldView = view("single")
        val operation = retained.uiState.value.pendingCommands.getValue("switch.test")
        retained.detachUi()
        invoke(activity, "clearPanelInteractions")
        // Сохранённая ViewModel передана новому экрану, как при configuration change.
        // onCreate/XML и реальный сетевой транспорт эта фикстура не запускает.
        val replacement = Robolectric.buildActivity(MainActivity::class.java).get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        }
        ReflectionHelpers.setField(replacement, "viewModel\$delegate", lazy { retained })
        val newPanel = FrameLayout(replacement)
        val root = FrameLayout(replacement)
        val dim = View(replacement).apply { visibility = View.GONE }
        root.addView(newPanel)
        root.addView(dim)
        replacement.window.setContentView(root)
        ArchitectureTestAccess.setField(replacement.delegate, "mSubDecorInstalled", true)
        ArchitectureTestAccess.setField(replacement.delegate, "mWindow", replacement.window)
        set(replacement, "tileManager", manager)
        set(replacement, "bottomPanel", newPanel)
        set(replacement, "dimOverlay", dim)
        invoke(replacement, "refreshBottomPanel")
        val fresh = newPanel.findViewWithTag<Button>("single")
        assertNotSame(oldView, fresh)
        assertFalse(oldView.hasOnClickListeners())
        assertEquals(ButtonCommandAppearance.PENDING, childColor(fresh))
        assertEquals(operation, retained.uiState.value.pendingCommands["switch.test"])
        retained.onEntityStateChanged("switch.test", "on")
        invoke(replacement, "renderEntityState", "switch.test", "on")
        assertEquals(ButtonCommandAppearance.ON, childColor(fresh))
        assertEquals("Кнопка", fresh.text.toString())
        assertEquals(1, calls().size)
        assertTrue(retained.uiState.value.pendingCommands.isEmpty())
        invoke(replacement, "clearPanelInteractions")
        retained.disconnectWebSocket()
    }

    @Test fun oppositeFreshFactStaysVisibleAfterTimeoutAndLateAck() {
        open(button("single", "switch.test"))
        event("switch.test", "on")
        view("single").performClick()
        val request = calls().single()
        ack(request)
        event("switch.test", "on")
        assertEquals(ButtonCommandAppearance.PENDING, color("single"))
        shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS)
        assertEquals(ButtonCommandAppearance.ON, color("single"))
        ack(request, false)
        assertEquals(ButtonCommandAppearance.ON, color("single"))
    }

}
