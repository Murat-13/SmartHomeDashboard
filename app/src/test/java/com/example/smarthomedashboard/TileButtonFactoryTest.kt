package com.example.smarthomedashboard

import android.content.res.Resources
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import com.example.smarthomedashboard.data.TileEntity
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowResources
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.lang.reflect.InvocationTargetException

// JVM-тесты не загружают APK-ресурсы. Подменяем только этот drawable, сохраняя
// общий ConstantState между экземплярами, как при загрузке ресурса из Android-кеша.
@Implements(Resources::class)
class TileButtonResources : ShadowResources() {
    @RealObject private lateinit var resources: Resources

    companion object {
        var buttonState: Drawable.ConstantState? = null
        val requestedThemes = mutableListOf<Resources.Theme?>()
    }

    @Implementation
    fun getDrawable(id: Int, theme: Resources.Theme?): Drawable {
        if (id == R.drawable.bg_button_rounded) {
            requestedThemes.add(theme)
            if (buttonState == null) {
                buttonState = GradientDrawable().apply {
                    cornerRadius = 16f * resources.displayMetrics.density
                    setColor(0xFF424242.toInt())
                }.constantState
            }
            return buttonState!!.newDrawable(resources)
        }
        return Shadow.directlyOn(resources, Resources::class.java, "getDrawable",
            ClassParameter.from(Int::class.javaPrimitiveType, id),
            ClassParameter.from(Resources.Theme::class.java, theme))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [TileButtonResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class TileButtonFactoryTest {
    private val activity by lazy {
        Robolectric.buildActivity(MainActivity::class.java).get().apply {
            pinGestureFixture(this)
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        }
    }
    private val states = linkedMapOf<String, String>()
    private val groups = linkedMapOf<String, MutableMap<String, String>>()

    private class RecordingSocket : WebSocket {
        val messages = mutableListOf<JSONObject>()
        override fun request() = Request.Builder().url("http://localhost/test-only").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(JSONObject(text)); return true }
        override fun send(bytes: ByteString): Boolean = error("Binary message is not expected")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {
            // Сокет только записывает сообщения: сетевого соединения и ресурсов для закрытия нет.
        }
    }

    @Before fun resetResource() {
        TileButtonResources.buttonState = null
        TileButtonResources.requestedThemes.clear()
    }

    private fun tile(
        type: String = "button",
        config: String = """{"entity_id":"switch.test"}""",
        id: String = "tile",
        title: String = "Свет"
    ) = TileEntity(id, type, "bottom_panel", title, 0, 0, 1, 1, config = config)

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true; set(target, value) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun syncStates() {
        (ReflectionHelpers.getField<Any>(activity, "singleStates") as MutableMap<String, String>).apply {
            clear(); putAll(states)
        }
        (ReflectionHelpers.getField<Any>(activity, "groupStates") as
            MutableMap<String, MutableMap<String, String>>).apply {
            clear(); putAll(groups)
        }
    }

    // Вызываем настоящий путь создания кнопки вместе с подключением обработчиков Activity.
    private fun create(tile: TileEntity = tile()): Button {
        // Состояние группы сверяется с сохранённым составом, как при создании панели.
        val manager = com.example.smarthomedashboard.data.TileManager(activity)
        manager.saveTiles(manager.loadTiles().filterNot { it.id == tile.id } + tile)
        set(activity, "tileManager", manager)
        syncStates()
        return try {
            MainActivity::class.java.getDeclaredMethod("createTileButton", TileEntity::class.java).apply {
                isAccessible = true
            }.invoke(activity, tile) as Button
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun tint(button: Button): Int {
        val filter = ReflectionHelpers.getField<PorterDuffColorFilter>(button.background, "mTintFilter")
        return shadowOf(filter).color
    }

    private fun recordingSocket(): RecordingSocket {
        val socket = RecordingSocket()
        val client = HomeAssistantWebSocket(
            "http://localhost/test-only", "test-token", { _, _, _ -> }, {}, {}
        )
        set(client, "authenticated", true)
        set(client, "webSocket", socket)
        set(activity, "webSocket", client)
        return socket
    }

    private fun tap(button: Button) {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        val up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, 10f, 10f, 0)
        try {
            assertTrue(button.dispatchTouchEvent(down))
            assertTrue(button.dispatchTouchEvent(up))
        } finally {
            down.recycle()
            up.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun textTagContextAndExactButtonTypeArePreserved() {
        val button = create(tile(id = "custom-id", title = "Длинная подпись"))
        assertEquals(Button::class.java, button.javaClass)
        assertSame(activity, button.context)
        assertEquals("Длинная подпись", button.text.toString())
        assertEquals("custom-id", button.tag)
    }

    @Test fun defaultLayoutIsSquareFrameLayoutParams() {
        val params = create().layoutParams
        assertEquals(FrameLayout.LayoutParams::class.java, params.javaClass)
        assertEquals(160, params.width)
        assertEquals(160, params.height)
    }

    @Test fun customSizeIsPreserved() {
        val params = create(tile(config = """{"entity_id":"switch.test","button_size":217}""")).layoutParams
        assertEquals(217, params.width)
        assertEquals(217, params.height)
    }

    @Test fun missingAndInvalidSizeUseExistingDefault() {
        assertEquals(160, create(tile(config = """{"button_size":"bad"}""")).layoutParams.width)
        assertEquals(160, create(tile(config = "{}")).layoutParams.width)
        assertEquals(160, create(tile(type = "group", config = "broken")).layoutParams.width)
    }

    @Test fun malformedNormalButtonConfigKeepsButtonAndRejectsAction() {
        val socket = recordingSocket()
        val button = create(tile(config = "broken", id = "broken-id", title = "Моя кнопка"))
        assertEquals("Моя кнопка", button.text.toString())
        assertEquals("broken-id", button.tag)
        assertEquals(160, button.layoutParams.width)
        assertEquals(0xFF424242.toInt(), tint(button))
        assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        button.performClick()
        assertTrue(socket.messages.isEmpty())
        assertEquals("Не удалось прочитать конфигурацию плитки. Откройте настройки.",
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test fun backgroundResourceUsesNullThemeAndKeepsAppearance() {
        val button = create()
        assertEquals(listOf<Resources.Theme?>(null), TileButtonResources.requestedThemes)
        assertTrue(button.background is GradientDrawable)
        assertEquals(16f * activity.resources.displayMetrics.density,
            (button.background as GradientDrawable).cornerRadius, 0f)
        assertEquals(0.8f, button.alpha, 0f)
        assertEquals(8f, button.elevation, 0f)
        assertEquals(0xFF424242.toInt(), tint(button))
    }

    @Test fun textAppearanceIsPreserved() {
        val button = create()
        assertEquals(0xFFFFFFFF.toInt(), button.currentTextColor)
        assertEquals(14f * activity.resources.displayMetrics.scaledDensity, button.textSize, 0.001f)
        assertEquals(2, button.maxLines)
        assertEquals(TextUtils.TruncateAt.END, button.ellipsize)
        assertEquals(Gravity.CENTER, button.gravity)
        assertFalse(button.isAllCaps)
    }

    @Test fun normalButtonOnIsActive() {
        states["switch.test"] = "on"
        assertEquals(0x8033CC33.toInt(), tint(create()))
    }

    @Test fun normalButtonOffAndAbsentStateAreInactive() {
        states["switch.test"] = "off"
        assertEquals(0xFF424242.toInt(), tint(create()))
        states.clear()
        assertEquals(0xFF424242.toInt(), tint(create()))
    }

    @Test fun groupIsActiveIfAnyMemberIsOn() {
        groups["tile"] = linkedMapOf("switch.first" to "off", "switch.second" to "on")
        assertEquals(0x8033CC33.toInt(), tint(create(tile(type = "group",
            config = """{"entity_ids":["switch.first","switch.second"]}"""))))
    }

    @Test fun groupOffEmptyAndAbsentAreInactive() {
        groups["tile"] = linkedMapOf("switch.first" to "off")
        assertEquals(0xFF424242.toInt(), tint(create(tile(type = "group"))))
        groups["tile"] = linkedMapOf()
        assertEquals(0xFF424242.toInt(), tint(create(tile(type = "group"))))
        groups.clear()
        assertEquals(0xFF424242.toInt(), tint(create(tile(type = "group"))))
    }

    @Test fun backgroundsRemainIndependentDuringTintUpdates() {
        states["switch.test"] = "on"
        val first = create()
        states["switch.test"] = "off"
        val second = create(tile(id = "second"))
        assertNotSame(first.background, second.background)
        assertEquals(0x8033CC33.toInt(), tint(first))
        assertEquals(0xFF424242.toInt(), tint(second))
        second.background.setTint(0xFF2196F3.toInt())
        assertEquals(0x8033CC33.toInt(), tint(first))
        first.isPressed = true
        assertEquals(0x8033CC33.toInt(), tint(first))
    }

    @Test fun ordinaryClickUsesExistingHandlerAndOptimisticColors() {
        val socket = recordingSocket()
        val button = create()
        assertTrue(socket.messages.isEmpty())
        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, socket.messages.size)
        assertEquals("call_service", socket.messages[0].getString("type"))
        assertEquals("switch", socket.messages[0].getString("domain"))
        assertEquals("turn_on", socket.messages[0].getString("service"))
        assertEquals("switch.test", socket.messages[0].getJSONObject("service_data").getString("entity_id"))
        assertEquals(0x8033CC33.toInt(), tint(button))
        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("turn_off", socket.messages[1].getString("service"))
        assertEquals(0xFF424242.toInt(), tint(button))
    }

    @Test fun groupTouchUsesGroupHandlerAndDoesNotOptimisticallyToggleSingleEntity() {
        val socket = recordingSocket()
        groups["tile"] = linkedMapOf("switch.first" to "on")
        val button = create(tile(type = "group",
            config = """{"entity_ids":["switch.first","light.second"],"entity_id":"switch.unused"}"""))
        assertFalse(button.hasOnClickListeners())
        tap(button)
        assertEquals(listOf("switch", "light"), socket.messages.map { it.getString("domain") })
        assertEquals(listOf("turn_off", "turn_off"), socket.messages.map { it.getString("service") })
        assertEquals(listOf("switch.first", "light.second"),
            socket.messages.map { it.getJSONObject("service_data").getString("entity_id") })
    }

    @Test fun creationDoesNotSendCommandsOrChangeStates() {
        val socket = recordingSocket()
        states["switch.test"] = "on"
        groups["group"] = linkedMapOf("switch.first" to "off")
        create()
        create(tile(type = "group", id = "group", config = """{"entity_ids":["switch.first"]}"""))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(socket.messages.isEmpty())
        assertEquals("on", ReflectionHelpers.getField<Map<String, String>>(activity, "singleStates")["switch.test"])
        assertEquals("off", groups["group"]?.get("switch.first"))
    }

    private fun invoke(name: String, vararg args: Any?) {
        val method = MainActivity::class.java.declaredMethods.single {
            it.name == name && it.parameterTypes.size == args.size
        }
        try {
            method.isAccessible = true
            method.invoke(activity, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun panel(vararg tiles: TileEntity): FrameLayout {
        val manager = com.example.smarthomedashboard.data.TileManager(activity)
        manager.saveTiles(tiles.toList())
        set(activity, "tileManager", manager)
        val panel = FrameLayout(activity)
        set(activity, "bottomPanel", panel)
        return panel
    }

    @Test fun missingEmptyAndWrongTypeSourcesNeverSendCommands() {
        val socket = recordingSocket()
        val invalid = listOf(
            "{}", """{"entity_id":""}""", """{"entity_id":"   "}""",
            """{"entity_id":42}""", """{"entity_id":null}""",
            """{"entity_id":true}""", """{"entity_id":{}}""", """{"entity_id":[]}"""
        )
        invalid.forEach { config ->
            org.robolectric.shadows.ShadowToast.reset()
            val button = create(tile(config = config))
            assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            button.performClick()
            assertTrue("Command for " + config, socket.messages.isEmpty())
            assertNotNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        }
    }

    @Test fun missingSourceAndDamagedConfigHaveDifferentMessages() {
        create(tile(config = "{}")).performClick()
        assertEquals("У кнопки не выбрано устройство.",
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        create(tile(config = """{"entity_id":123}""")).performClick()
        assertEquals("Не удалось прочитать конфигурацию плитки. Откройте настройки.",
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test fun malformedGroupTapAndLongPressDoNotCrashOrSendCommands() {
        val socket = recordingSocket()
        val button = create(tile(type = "group", config = "broken"))
        assertEquals(0xFF424242.toInt(), tint(button))
        assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        tap(button)
        assertTrue(socket.messages.isEmpty())
        assertEquals("Не удалось прочитать конфигурацию плитки. Откройте настройки.",
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try {
            button.dispatchTouchEvent(event)
            shadowOf(Looper.getMainLooper()).idleFor(1, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            event.recycle()
        }
        assertTrue(socket.messages.isEmpty())
        assertNull(ReflectionHelpers.getField<String?>(activity, "expandedGroupId"))
    }

    @Test fun badGroupMemberPreventsAllCommandsIncludingEarlierValidMembers() {
        val socket = recordingSocket()
        listOf("{}", "null", "42", "true", "\"\"", "\"   \"").forEach { bad ->
            val button = create(tile(type = "group",
                config = """{"entity_ids":["switch.first",${bad},"light.last"]}"""))
            tap(button)
            assertTrue("Partial command for " + bad, socket.messages.isEmpty())
        }
    }

    @Test fun wrongGroupArrayTypeIsReportedAtActionTime() {
        val socket = recordingSocket()
        listOf("""{"entity_ids":"switch.first"}""", """{"entity_ids":null}""").forEach { config ->
            val button = create(tile(type = "group", config = config))
            tap(button)
            assertEquals("Не удалось прочитать конфигурацию плитки. Откройте настройки.",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertTrue(socket.messages.isEmpty())
        }
    }

    @Test fun validSingleMemberGroupStillWorks() {
        val socket = recordingSocket()
        val button = create(tile(type = "group", config = """{"entity_ids":["switch.first"]}"""))
        tap(button)
        assertEquals(1, socket.messages.size)
        assertEquals("turn_on", socket.messages.single().getString("service"))
        assertEquals("switch.first",
            socket.messages.single().getJSONObject("service_data").getString("entity_id"))
    }

    @Test fun correctGroupPreservesMemberOrderDuplicatesAndArbitraryDomains() {
        val socket = recordingSocket()
        val button = create(tile(type = "group",
            config = """{"entity_ids":["custom.first","light.last","custom.first"]}"""))
        tap(button)
        assertEquals(listOf("custom.first", "light.last", "custom.first"),
            socket.messages.map { it.getJSONObject("service_data").getString("entity_id") })
        assertEquals(listOf("custom", "light", "custom"), socket.messages.map { it.getString("domain") })
    }

    @Test fun badTileDoesNotPreventPanelCreationAndValidNeighborControl() {
        val socket = recordingSocket()
        val bad = tile(config = "broken", id = "bad").copy(x = 20, y = 40)
        val good = tile(id = "good")
        val panel = panel(bad, good)
        invoke("refreshBottomPanel")
        assertEquals(2, panel.childCount)
        val badButton = panel.findViewWithTag<Button>("bad")
        val goodButton = panel.findViewWithTag<Button>("good")
        assertNotNull(badButton)
        assertNotNull(goodButton)
        assertEquals(20f, badButton.x, 0f)
        assertEquals(40f, badButton.y, 0f)
        goodButton.performClick()
        assertEquals(1, socket.messages.size)
        assertEquals("switch.test",
            socket.messages.single().getJSONObject("service_data").getString("entity_id"))
    }

    @Test fun malformedButtonCanOpenSettingsInEditModeWithoutSendingCommand() {
        val socket = recordingSocket()
        val button = create(tile(config = "broken", id = "bad"))
        set(activity, "isEditMode", true)
        tap(button)
        awaitPinGate(activity)
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(started)
        assertEquals(TileSettingsActivity::class.java.name, started.intent.component?.className)
        assertEquals("bad", started.intent.getStringExtra("tile_id"))
        assertTrue(socket.messages.isEmpty())
    }

    @Test fun malformedGroupCanOpenSettingsInEditModeWithoutSendingCommand() {
        val socket = recordingSocket()
        val button = create(tile(type = "group", config = "broken", id = "bad-group"))
        set(activity, "isEditMode", true)
        tap(button)
        awaitPinGate(activity)
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals("bad-group", started.intent.getStringExtra("tile_id"))
        assertTrue(socket.messages.isEmpty())
    }

    @Test fun renderingActionsAndStateUpdatesDoNotRewriteStoredConfig() {
        val bad = tile(config = "broken", id = "bad")
        val good = tile(id = "good")
        val brokenGroup = tile(type = "group", id = "group",
            config = """{"entity_ids":["switch.test",null]}""")
        val panel = panel(bad, good, brokenGroup)
        invoke("refreshBottomPanel")
        (panel.getChildAt(0) as Button).performClick()
        tap(panel.getChildAt(2) as Button)
        org.robolectric.shadows.ShadowToast.reset()
        repeat(3) {
            invoke("updateTilesForEntity", "switch.test", if (it % 2 == 0) "on" else "off", null)
        }
        assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertEquals(0x8033CC33.toInt(), tint(panel.getChildAt(1) as Button))
        val manager = com.example.smarthomedashboard.data.TileManager(activity)
        assertEquals(listOf(bad, good, brokenGroup), manager.loadTiles())
    }

    @Test fun subscriptionCollectionIgnoresInvalidSourcesWithoutAffectingOtherTiles() {
        val socket = recordingSocket()
        panel(
            tile(id = "bad", config = """{"entity_id":"weather.first","entity_ids":["weather.partial",null]}"""),
            tile(type = "weather", id = "good", config = """{"entity_id":"weather.good"}""")
        )
        invoke("subscribeToNeededEntities")
        val forecasts = socket.messages.filter { it.optString("type") == "weather/subscribe_forecast" }
        assertEquals(listOf("weather.good"), forecasts.map { it.getString("entity_id") })
        assertTrue(socket.messages.none { it.optString("type") == "call_service" })
    }

    @Test fun invalidGroupDoesNotStartOrReplaceExpandedContent() {
        val socket = recordingSocket()
        val group = tile(type = "group",
            config = """{"entity_ids":["switch.first",null],"child_names":["Первый","Второй"]}""")
        val button = create(group)
        set(activity, "expandedGroupId", "already-open")
        invoke("expandChildButtons", group, button)
        assertEquals("already-open", ReflectionHelpers.getField<String>(activity, "expandedGroupId"))
        assertTrue(ReflectionHelpers.getField<List<Button>>(activity, "expandedChildButtons").isEmpty())
        assertTrue(socket.messages.isEmpty())
        assertEquals("Не удалось прочитать конфигурацию плитки. Откройте настройки.",
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test fun emptyGroupReportsMissingDevicesWithoutCommands() {
        val socket = recordingSocket()
        listOf("{}", """{"entity_ids":[]}""").forEach { config ->
            val button = create(tile(type = "group", config = config))
            tap(button)
            assertEquals("В группе не выбраны устройства.",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertTrue(socket.messages.isEmpty())
        }
    }

    @Test fun normalButtonKeepsArbitraryDomainAndOriginalEntityString() {
        val socket = recordingSocket()
        val button = create(tile(config = """{"entity_id":"custom.device"}"""))
        button.performClick()
        assertEquals("custom", socket.messages.single().getString("domain"))
        assertEquals("custom.device",
            socket.messages.single().getJSONObject("service_data").getString("entity_id"))
    }
}
