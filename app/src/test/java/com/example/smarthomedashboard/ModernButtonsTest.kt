package com.example.smarthomedashboard

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.InvocationTargetException
import java.time.Duration

/** Modern-кнопки и группы: хранение стиля, подтверждённые состояния, ожидание, ошибки, раскрытие. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [ButtonStateResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ModernButtonsTest {
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
        override fun cancel() = Unit
    }

    private fun set(target: Any, name: String, value: Any?) = ArchitectureTestAccess.setField(target, name, value)

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? = try {
        ArchitectureTestAccess.methods(target).single { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private fun modern(config: JSONObject) = config.put(TileButtonStyle.CONFIG_KEY, "modern").toString()
    private fun button(id: String, eid: String, style: String? = "modern", x: Int = 250) =
        TileEntity(id, "button", "bottom_panel", "Кнопка $id", x, 600, 1, 1,
            config = JSONObject().put("entity_id", eid).apply { style?.let { put(TileButtonStyle.CONFIG_KEY, it) } }.toString())
    private fun group(id: String, vararg ids: String) =
        TileEntity(id, "group", "bottom_panel", "Освещение двора", 800, 700, 1, 1,
            config = modern(JSONObject().put("entity_ids", JSONArray(ids.toList()))
                .put("child_names", JSONArray(ids.map { it.substringAfter('.') }))))

    private lateinit var root: FrameLayout

    private fun open(vararg tiles: TileEntity) {
        manager.saveTiles(tiles.toList())
        set(activity, "tileManager", manager)
        set(activity, "bottomPanel", panel)
        root = FrameLayout(activity)
        (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        root.addView(panel)
        val dim = View(activity).apply { visibility = View.GONE }
        root.addView(dim)
        activity.window.setContentView(root)
        // Присоединённое окно: отложенные задачи View (spinner через 300 мс, клик) выполняются.
        if (!activity.window.decorView.isAttachedToWindow) {
            activity.windowManager.addView(activity.window.decorView, android.view.WindowManager.LayoutParams())
        }
        ArchitectureTestAccess.setField(activity.delegate, "mSubDecorInstalled", true)
        ArchitectureTestAccess.setField(activity.delegate, "mWindow", activity.window)
        set(activity, "dimOverlay", dim)
        val client = HomeAssistantWebSocket("http://localhost/test-only", "test-token", { _, _, _ -> }, {}, {})
        set(client, "authenticated", true)
        set(client, "webSocket", socket)
        ArchitectureTestAccess.setField(owner(), "webSocket", client)
        ArchitectureTestAccess.setField(owner(), "commandStateCallback", { id: String ->
            invoke(activity, "renderCommandState", id)
        })
        invoke(activity, "refreshBottomPanel")
        layout()
    }

    private fun layout() {
        val window = activity.window.decorView
        window.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
        window.layout(0, 0, 1920, 1200)
        idle()
        // Панель вне настоящего окна: раскладываем плитки по их размерам.
        for (i in 0 until panel.childCount) {
            val child = panel.getChildAt(i)
            val w = child.layoutParams?.width?.takeIf { it > 0 } ?: 160
            val h = child.layoutParams?.height?.takeIf { it > 0 } ?: 160
            child.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            child.layout(0, 0, w, h)
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun owner() = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
    private fun controller() = MainActivity::class.java.getDeclaredMethod("getPanelController")
        .apply { isAccessible = true }.invoke(activity) as DashboardPanelController
    private fun event(eid: String, state: String) = invoke(activity, "updateTilesForEntity", eid, state, null)
    private fun calls() = socket.messages.filter { it.optString("type") == "call_service" }
    private fun ack(request: JSONObject, success: Boolean) {
        val message = JSONObject().put("type", "result").put("id", request.getInt("id")).put("success", success)
        invoke(owner().getWebSocket()!!, "handleMessage", requireNotNull(HaInboundMessage.parse(message.toString())))
    }
    private fun tile(id: String): View = panel.findViewWithTag(id)
    private fun modernTile(id: String) = tile(id) as ModernTileView
    private fun touch(view: View, action: Int) {
        val event = MotionEvent.obtain(0, 1, action, 5f, 5f, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun tap(view: View) {
        touch(view, MotionEvent.ACTION_DOWN)
        touch(view, MotionEvent.ACTION_UP)
        idle()
    }

    @Test fun styleIsPerTileAndLegacyTilesWithoutKeyStayClassic() {
        assertEquals(TileButtonStyle.CLASSIC, TileButtonStyle.of("""{"entity_id":"light.a"}"""))
        assertEquals(TileButtonStyle.CLASSIC, TileButtonStyle.of("""{"button_style":"neon"}"""))
        assertEquals(TileButtonStyle.CLASSIC, TileButtonStyle.of("{broken"))
        assertEquals(TileButtonStyle.MODERN, TileButtonStyle.of("""{"button_style":"modern"}"""))
        assertFalse(TileButtonStyle.isModern("sensor", """{"button_style":"modern"}"""))
        assertEquals(TileButtonStyle.MODERN, TileButtonStyle.NEW_TILE)

        // Смена стиля не трогает остальные и неизвестные поля.
        val config = JSONObject("""{"entity_ids":["a.b"],"child_names":["A"],"button_size":200,"future":{"x":1}}""")
        TileStyleEditor.writeIfChanged(config, TileButtonStyle.MODERN, isNew = false)
        assertEquals("modern", config.getString("button_style"))
        assertEquals(200, config.getInt("button_size"))
        assertEquals(1, config.getJSONObject("future").getInt("x"))
        // Classic без изменения не записывает ключ в старую плитку.
        val legacy = JSONObject("""{"entity_id":"light.a"}""")
        TileStyleEditor.writeIfChanged(legacy, TileButtonStyle.CLASSIC, isNew = false)
        assertFalse(legacy.has("button_style"))
    }

    @Test fun chosenIconIsShownOnButtonAndGroupWhileStatesStayConfirmed() {
        val chosen = button("chosen", "switch.a").let {
            it.copy(config = JSONObject(it.config).put("modern_icon", "snowflake").toString())
        }
        // Старая запись Gson без поля icon: в поле попадает null.
        val legacy = button("legacy", "switch.a", x = 600).also { ArchitectureTestAccess.setField(it, "icon", null) }
        val yard = group("g", "light.a").let { it.copy(config = JSONObject(it.config).put("modern_icon", "gate").toString()) }
        open(chosen, legacy, yard)
        assertEquals(ModernIcon.SNOWFLAKE, modernTile("chosen").boundModel!!.icon)
        assertEquals(ModernIcon.POWER, modernTile("legacy").boundModel!!.icon) // прежний автоподбор
        assertEquals(ModernIcon.GATE, modernTile("g").boundModel!!.groupIcon)
        assertEquals(1, modernTile("g").boundModel!!.items.size)
        assertEquals(ModernIcon.GATE, modernTile("g").boundModel!!.items.single().icon)
        socket.messages.clear()
        touch(tile("g"), MotionEvent.ACTION_DOWN)
        advance(1100)
        touch(tile("g"), MotionEvent.ACTION_UP)
        idle()
        assertEquals(ModernIcon.GATE, controller().modernGroup.children.single().boundModel!!.icon)
        assertTrue(calls().isEmpty())
        event("light.a", "on")
        assertEquals(ModernIcon.GATE, controller().modernGroup.children.single().boundModel!!.icon)
        event("switch.a", "on")
        assertEquals(ModernTileView.Visual.ON, modernTile("chosen").displayedVisual)
        event("switch.a", "unavailable")
        assertEquals(ModernTileView.Visual.UNAVAILABLE, modernTile("chosen").displayedVisual)
    }

    @Test fun livePowerUpdatesOnlyOptedInFullSizeButtonsIndependentlyOfSwitchState() {
        fun configured(tile: TileEntity, sensor: String) = tile.copy(config = JSONObject(tile.config)
            .put("show_power", true).put("power_entity_id", sensor).toString())
        val group = group("power_group", "switch.a", "light.b").let {
            it.copy(config = JSONObject(it.config).put("child_power", JSONObject().put("switch.a",
                JSONObject().put("show_power", true).put("power_entity_id", "sensor.child_power"))).toString())
        }
        open(configured(button("with_power", "switch.a"), "sensor.single_power"),
            button("without_power", "switch.a", x = 450), group,
            configured(button("classic", "switch.a", style = "classic", x = 650), "sensor.single_power"))
        event("switch.a", "off")
        fun powerEvent(id: String, value: String) {
            invoke(activity, "updateTilesForEntity", id, value, JSONObject().put("unit_of_measurement", "W"))
        }
        powerEvent("sensor.single_power", "648")
        powerEvent("sensor.child_power", "35")
        val full = modernTile("with_power")
        assertEquals("648 Вт", full.boundModel!!.powerText)
        assertEquals(ModernTileView.Visual.OFF, full.displayedVisual)
        assertNull(modernTile("without_power").boundModel!!.powerText)
        assertTrue(tile("classic") is Button)
        assertNull(modernTile("power_group").boundModel!!.powerText)
        val parentModel = modernTile("power_group").boundModel
        touch(tile("power_group"), MotionEvent.ACTION_DOWN); advance(1100)
        touch(tile("power_group"), MotionEvent.ACTION_UP); idle()
        val children = controller().modernGroup.children
        assertEquals("35 Вт", children[0].boundModel!!.powerText)
        assertNull(children[1].boundModel!!.powerText)
        powerEvent("sensor.child_power", "42")
        assertEquals("42 Вт", children[0].boundModel!!.powerText)
        assertEquals(parentModel, modernTile("power_group").boundModel)
        assertSame(full, tile("with_power"))
        powerEvent("sensor.single_power", "unavailable")
        assertEquals("— Вт", full.boundModel!!.powerText)
        assertEquals(ModernTileView.Visual.OFF, full.displayedVisual)
        powerEvent("sensor.single_power", "0")
        assertEquals("0 Вт", full.boundModel!!.powerText)
        assertTrue(calls().isEmpty())
        tap(full); advance(301)
        assertTrue(full.isSpinnerVisible)
        assertEquals("0 Вт", full.boundModel!!.powerText)
        ack(calls().single(), success = false)
        assertEquals(ModernTileView.Visual.ERROR, full.displayedVisual)
        assertEquals("0 Вт", full.boundModel!!.powerText)
        socket.messages.clear()
        owner().commands.disconnected()
        assertEquals("— Вт", full.boundModel!!.powerText)
        assertEquals("— Вт", children[0].boundModel!!.powerText)
        powerEvent("sensor.single_power", "1820")
        assertEquals("1,82 кВт", full.boundModel!!.powerText)
        assertTrue(calls().isEmpty())
    }

    @Test fun legacyTileRendersClassicAndModernTileRendersModernView() {
        open(button("old", "light.a", style = null), button("new", "light.a", x = 500))
        assertTrue(tile("old") is Button)
        assertTrue(tile("new") is ModernTileView)
        // Отрисовка не меняет сохранённые плитки.
        assertFalse(JSONObject(manager.loadTiles().first { it.id == "old" }.config).has("button_style"))
    }

    @Test fun modernChangesColorOnlyFromConfirmedStateWhileClassicShowsPending() {
        open(button("classic", "light.a", style = "classic"), button("modern", "light.a", x = 500))
        event("light.a", "off")
        tap(tile("classic"))
        assertEquals("turn_on", calls().single().getString("service"))
        assertEquals(ButtonCommandAppearance.PENDING, shadowOf(
            ArchitectureTestAccess.getField<android.graphics.PorterDuffColorFilter>(tile("classic").background, "mTintFilter")).color)
        // Modern по тому же entity_id не показывает предполагаемый ON.
        assertEquals(ModernTileView.Visual.OFF, modernTile("modern").displayedVisual)
        event("light.a", "on")
        assertEquals(ModernTileView.Visual.ON, modernTile("modern").displayedVisual)
    }

    @Test fun spinnerAppearsOnlyAfter300msAndDisappearsOnConfirmation() {
        open(button("modern", "switch.a"))
        event("switch.a", "off")
        // Без анимации нажатия: в Robolectric она сдвигает часы и скрывала бы порог 300 мс.
        tile("modern").performClick()
        assertEquals(1, calls().size)
        assertFalse(modernTile("modern").isSpinnerVisible)
        advance(200)
        assertFalse(modernTile("modern").isSpinnerVisible)
        advance(150)
        assertTrue(modernTile("modern").isSpinnerVisible)
        assertEquals(ModernTileView.Visual.OFF, modernTile("modern").displayedVisual)
        event("switch.a", "on")
        assertFalse(modernTile("modern").isSpinnerVisible)
        assertEquals(ModernTileView.Visual.ON, modernTile("modern").displayedVisual)
    }

    @Test fun fastConfirmationNeverShowsSpinner() {
        open(button("modern", "switch.a"))
        event("switch.a", "off")
        tile("modern").performClick()
        advance(100)
        event("switch.a", "on")
        advance(1000)
        assertFalse(modernTile("modern").isSpinnerVisible)
    }

    @Test fun rejectedCommandShowsErrorFor1500msThenRealState() {
        open(button("modern", "switch.a"))
        event("switch.a", "off")
        tap(tile("modern"))
        ack(calls().single(), success = false)
        idle()
        assertEquals(ModernTileView.Visual.ERROR, modernTile("modern").displayedVisual)
        advance(1600)
        assertEquals(ModernTileView.Visual.OFF, modernTile("modern").displayedVisual)
    }

    @Test fun unknownStateIsUnavailableNotOff() {
        open(button("modern", "switch.a"))
        assertEquals(ModernTileView.Visual.UNAVAILABLE, modernTile("modern").displayedVisual)
        event("switch.a", "unavailable")
        assertEquals(ModernTileView.Visual.UNAVAILABLE, modernTile("modern").displayedVisual)
    }

    @Test fun groupColorFollowsAvailableChildrenAndEmptyGroupIsNavy() {
        open(group("g", "light.a", "light.b", "light.c"), group("empty"))
        assertEquals(ModernTileView.Visual.UNAVAILABLE, modernTile("g").displayedVisual)
        event("light.a", "off")
        assertEquals(ModernTileView.Visual.OFF, modernTile("g").displayedVisual)
        event("light.b", "on")
        assertEquals(ModernTileView.Visual.ON, modernTile("g").displayedVisual)
        assertEquals(ModernTileView.Visual.OFF, modernTile("empty").displayedVisual)
    }

    @Test fun allThumbnailsFitInsideParentForLargeGroups() {
        listOf(1, 3, 6, 12, 25).forEach { count ->
            val ids = (1..count).map { "light.l$it" }.toTypedArray()
            open(group("g", *ids))
            val view = modernTile("g")
            val rect = android.graphics.RectF()
            val bounds = android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
            val seen = mutableListOf<android.graphics.RectF>()
            for (i in 0 until count) {
                assertTrue(view.thumbRect(i, rect))
                assertTrue("$count: $rect in $bounds", bounds.contains(rect))
                assertTrue(seen.none { android.graphics.RectF.intersects(it, rect) })
                seen += android.graphics.RectF(rect)
            }
            controller().clearPanelInteractions()
        }
    }

    @Test fun shortTapSendsGroupCommandWithOneParentSpinnerAndLongPressOpensPanel() {
        open(group("g", "light.a", "light.b"))
        event("light.a", "off")
        event("light.b", "off")
        tap(tile("g"))
        assertEquals(listOf("turn_on", "turn_on"), calls().map { it.getString("service") })
        advance(350)
        assertTrue(modernTile("g").isSpinnerVisible)
        assertEquals("g", owner().commands.pendingFor("light.a")?.groupId)
        event("light.a", "on")
        event("light.b", "on")
        assertFalse(modernTile("g").isSpinnerVisible)

        socket.messages.clear()
        touch(tile("g"), MotionEvent.ACTION_DOWN)
        advance(1100)
        touch(tile("g"), MotionEvent.ACTION_UP)
        idle()
        val group = controller().modernGroup
        assertTrue(group.isOpen)
        assertTrue(calls().isEmpty()) // Раскрытие — не команда.
        assertEquals(2, group.children.size)
        assertTrue(group.parentCopy!!.highlight)
        assertEquals(ModernGroupPanel.DIM_ALPHA, group.dimView!!.alpha, 0.01f)

        // Индивидуальная команда: spinner у дочерней кнопки, не у родителя.
        group.children[1].performClick()
        idle()
        assertEquals("turn_off", calls().single().getString("service"))
        advance(350)
        assertTrue(group.children[1].isSpinnerVisible)
        assertFalse(group.children[0].isSpinnerVisible)
        assertFalse(modernTile("g").isSpinnerVisible)

        group.close()
        advance(500)
        assertFalse(group.isOpen)
        assertNull(group.panelView)
        // В окне осталось только исходное дерево панели: затемнение, копия и панель удалены.
        assertEquals(1, activity.findViewById<FrameLayout>(android.R.id.content).childCount)
    }

    @Test fun panelAutoCollapsesAfterTenSecondsAndEmptyGroupShowsMessage() {
        open(group("g"))
        touch(tile("g"), MotionEvent.ACTION_DOWN)
        advance(1100)
        touch(tile("g"), MotionEvent.ACTION_UP)
        val group = controller().modernGroup
        assertTrue(group.isOpen)
        assertTrue(group.children.isEmpty())
        advance(9_000)
        assertTrue(group.isOpen)
        advance(1_500)
        assertFalse(group.isOpen)
    }

    @Test fun panelIsPlacedAboveParentInsideScreen() {
        open(group("g", "light.a", "light.b", "light.c", "light.d", "light.e", "light.f"))
        touch(tile("g"), MotionEvent.ACTION_DOWN)
        advance(1100)
        touch(tile("g"), MotionEvent.ACTION_UP)
        layout()
        val group = controller().modernGroup
        val panelView = group.panelView!!
        val parent = modernTile("g")
        assertTrue(panelView.y + panelView.height <= parent.y)
        assertTrue(panelView.x >= 0 && panelView.x + panelView.width <= 1920)
        assertEquals(0, group.scrollView!!.scrollY)
    }

    @Test fun editModeTapOnModernTileNeverSendsCommand() {
        open(button("modern", "switch.a"))
        owner().setEditMode(true)
        invoke(activity, "refreshBottomPanel")
        layout()
        assertTrue(modernTile("modern").editOutline)
        touch(tile("modern"), MotionEvent.ACTION_DOWN)
        touch(tile("modern"), MotionEvent.ACTION_UP)
        idle()
        assertTrue(calls().isEmpty())
    }

    @Test fun commandsRecordFailuresOnlyForDetectedErrorsAndMarkGroupOrigin() {
        val state = DashboardDeviceState()
        val callbacks = mutableListOf<(ServiceCommandResult) -> Unit>()
        val commands = DashboardDeviceCommands(state, { _, _, _, callback -> callbacks += callback; true }, {})
        state.entityChanged("light.a", "off", null)
        state.entityChanged("light.b", "off", null)
        commands.toggleGroup("g", listOf("light.a", "light.b"))
        assertEquals("g", commands.pendingFor("light.a")?.groupId)
        callbacks[0](ServiceCommandResult.REJECTED)
        callbacks[1](ServiceCommandResult.DISCONNECTED)
        assertNotNull(commands.recentFailure("light.a", 1_500))
        assertNull(commands.recentFailure("light.b", 1_500))
        assertNull(commands.recentFailure("light.a", 1_500, SystemClock.elapsedRealtime() + 2_000))
        commands.toggleSingle("light.a")
        assertNull(commands.pendingFor("light.a")?.groupId)
        commands.disconnected()
    }

    @Test fun styleEditorPreviewSwitchesWithoutSaving() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(host).apply { id = R.id.buttonStyleSection })
            addView(Button(host).apply { id = R.id.btnStyleClassic })
            addView(Button(host).apply { id = R.id.btnStyleModern })
            addView(FrameLayout(host).apply { id = R.id.buttonStylePreview })
        }
        host.setContentView(content)
        val editor = TileStyleEditor(host, TileButtonStyle.CLASSIC, null, { "Свет" }, { "button" }, { 160 }, { listOf("light.a" to "a") })
        editor.update()
        val preview = host.findViewById<FrameLayout>(R.id.buttonStylePreview)
        assertTrue(preview.getChildAt(0) is Button)
        host.findViewById<Button>(R.id.btnStyleModern).performClick()
        assertEquals(TileButtonStyle.MODERN, editor.style)
        assertTrue(preview.getChildAt(0) is ModernTileView)
        assertFalse((preview.getChildAt(0) as ModernTileView).isClickable)
        host.findViewById<Button>(R.id.btnStyleClassic).performClick()
        assertTrue(preview.getChildAt(0) is Button)
    }
}
