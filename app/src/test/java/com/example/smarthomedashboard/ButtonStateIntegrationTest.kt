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
        override fun request() = Request.Builder().url("http://localhost/test-only").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(JSONObject(text)); return true }
        override fun send(bytes: ByteString): Boolean = error("Unexpected binary message")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {
            // Соединения с сетью нет: этот сокет только записывает команды.
        }
    }

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true; set(target, value) }
    }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? = try {
        target.javaClass.declaredMethods.single { it.name == name && it.parameterTypes.size == args.size }
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
        ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
        ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
        set(activity, "dimOverlay", dim)
        val client = HomeAssistantWebSocket("http://localhost/test-only", "test-token", { _, _, _ -> }, {}, {})
        set(client, "authenticated", true)
        set(client, "webSocket", socket)
        val owner = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
        org.robolectric.util.ReflectionHelpers.setField(owner, "webSocket", client)
        invoke(activity, "refreshBottomPanel")
    }

    private fun view(id: String): Button = panel.findViewWithTag(id)
    private fun color(id: String): Int {
        val filter = ReflectionHelpers.getField<PorterDuffColorFilter>(view(id).background, "mTintFilter")
        return shadowOf(filter).color
    }
    private fun event(eid: String, state: String) {
        invoke(activity, "updateTilesForEntity", eid, state, null)
    }
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
            ReflectionHelpers.getField<MutableMap<String, MutableMap<String, String>>>(activity, "groupStates")["group"] =
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
        assertEquals(0xFF424242.toInt(), color("switch"))
        assertEquals(0xFF424242.toInt(), color("other"))
        event("switch.b", "on")
        assertEquals(0x8033CC33.toInt(), color("switch"))
        event("light.a", "off")
        assertEquals(0xFF424242.toInt(), color("light"))
        assertEquals(0x8033CC33.toInt(), color("switch"))
        assertEquals(0xFF424242.toInt(), color("other"))
    }

    @Test fun arbitraryDomainAndChangedOrdinarySourceUseCurrentEntityId() {
        open(button("custom", "custom.a"), button("other", "light.other"))
        event("custom.a", "on")
        assertEquals(0x8033CC33.toInt(), color("custom"))
        saveSelection("custom", listOf("light.new"))
        assertEquals(0xFF424242.toInt(), color("custom"))
        event("custom.a", "on")
        assertEquals(0xFF424242.toInt(), color("custom"))
        event("light.new", "on")
        assertEquals(0x8033CC33.toInt(), color("custom"))
        assertEquals(0xFF424242.toInt(), color("other"))
    }

    @Test fun ordinaryShortTapKeepsCommandAndOptimisticState() {
        open(button("light", "light.a"))
        view("light").performClick()
        assertEquals("light", calls().single().getString("domain"))
        assertEquals("turn_on", calls().single().getString("service"))
        assertEquals(0x8033CC33.toInt(), color("light"))
        view("light").performClick()
        assertEquals("turn_off", calls().last().getString("service"))
        assertEquals(0xFF424242.toInt(), color("light"))
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
        assertEquals("group", ReflectionHelpers.getField<String>(activity, "expandedGroupId"))
        val children = ReflectionHelpers.getField<List<Button>>(activity, "expandedChildButtons")
        assertEquals(listOf("switch.b", "light.a"), children.map { it.tag })
        val tint = ReflectionHelpers.getField<PorterDuffColorFilter>(children[1].background, "mTintFilter")
        assertEquals(0x8033CC33.toInt(), shadowOf(tint).color)
        assertTrue(calls().isEmpty())
        children[1].performClick()
        assertEquals("turn_off", calls().single().getString("service"))
        assertEquals("light.a", calls().single().getJSONObject("service_data").getString("entity_id"))
        assertEquals(0xFF424242.toInt(), color("group"))
    }

    @Test fun realUnknownAndUnavailableUpdateStateAndRemainInactive() {
        open(group("group", "light.a"), button("light", "light.a"))
        event("light.a", "on")
        event("light.a", "unknown")
        assertEquals(0xFF424242.toInt(), color("group"))
        // Настоящий unknown больше не оставляет устаревший on у обычной кнопки.
        assertEquals(0xFF424242.toInt(), color("light"))
        event("light.a", "unavailable")
        assertEquals(0xFF424242.toInt(), color("group"))
        assertEquals(0xFF424242.toInt(), color("light"))
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

}
