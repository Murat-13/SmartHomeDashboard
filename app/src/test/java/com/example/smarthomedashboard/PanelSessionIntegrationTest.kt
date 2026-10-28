package com.example.smarthomedashboard

import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.*
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
import java.util.concurrent.TimeUnit

@Suppress("unused") // Методы вызываются Robolectric через @Implementation.
@Implements(Resources::class)
class PanelSessionResources : ShadowResources() {
    @RealObject private lateinit var resources: Resources
    @Implementation
    fun getDrawable(id: Int, theme: Resources.Theme?): Drawable {
        if (id == R.drawable.ic_resize) return ColorDrawable(Color.WHITE)
        if (id == R.drawable.bg_button_rounded) return GradientDrawable().apply { setColor(0xFF424242.toInt()) }
        return Shadow.directlyOn(resources, Resources::class.java, "getDrawable",
            ClassParameter.from(Int::class.javaPrimitiveType, id),
            ClassParameter.from(Resources.Theme::class.java, theme))
    }
}

// Настоящие методы, Views и анимации; XML/onCreate и сеть не запускаются.
// Подменённые drawable не являются проверкой оформления приложения.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PanelSessionIntegrationTest {
    private val controller by lazy { Robolectric.buildActivity(MainActivity::class.java) }
    private val activity get() = controller.get()
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val dim by lazy { View(activity).apply { visibility = View.GONE } }

    private fun set(name: String, value: Any?) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true; set(activity, value) }
    }
    private fun <T> get(name: String): T = ReflectionHelpers.getField(activity, name)
    private fun invoke(name: String, vararg args: Any?): Any? = try {
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
    private fun tile(id: String, type: String = "sensor", config: String =
        """{"collapsed_sensor_configs":[{"entity_id":"sensor.temp","display_name":"T","decimals":1}]}""") =
        TileEntity(id, type, if (type == "group" || type == "button") "bottom_panel" else "grid",
            id, 20, 100, 1, 1, config = config)

    private fun open(vararg tiles: TileEntity) {
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = 1920
        activity.resources.displayMetrics.heightPixels = 1200
        manager.saveTiles(tiles.toList() + tile("anchor", "button", """{"entity_id":"switch.anchor"}"""))
        set("tileManager", manager)
        set("bottomPanel", panel)
        set("dimOverlay", dim)
        set("yandexWeatherManager", YandexWeatherManager(activity))
        val root = FrameLayout(activity)
        root.addView(panel)
        root.addView(dim)
        activity.window.setContentView(root)
        ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
        ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
        invoke("refreshBottomPanel")
        layoutCards()
    }
    private fun card(id: String): FrameLayout = panel.findViewWithTag(id)
    private fun layoutCards() {
        for (i in 0 until panel.childCount) {
            val view = panel.getChildAt(i)
            view.forceLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(view.layoutParams.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(view.layoutParams.height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.layoutParams.width, view.layoutParams.height)
        }
    }
    private fun advance(ms: Long = 400) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
        layoutCards()
    }
    private fun expandFirst() {
        invoke("expandSensor", manager.loadTiles().single { it.id == "first" }, card("first"))
        advance()
    }
    private fun send(view: View, action: Int) {
        val event = MotionEvent.obtain(0, 1, action, 5f, 5f, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun tap(id: String) {
        send(card(id), MotionEvent.ACTION_DOWN)
        send(card(id), MotionEvent.ACTION_UP)
    }
    private fun rows(id: String): List<String> {
        val c = card(id).findViewWithTag<LinearLayout>("sensor_text_container")
        return (0 until c.childCount).map { (c.getChildAt(it) as TextView).text.toString() }
    }
    private fun assertNoExpansion() {
        assertNull(get<View?>("expandedSensorView"))
        assertNull(get<View?>("expandedSensorSource"))
    }

    @Test fun rebuildClearsExpandedCardSoAnotherCardOpensOnFirstTap() {
        open(tile("first"), tile("second"))
        expandFirst()
        val old = card("first")
        invoke("refreshBottomPanel")
        layoutCards()
        assertNoExpansion()
        assertNull(old.parent)
        tap("second")
        advance()
        assertSame(card("second"), get<View>("expandedSensorView"))
    }

    @Test fun deletedExpandedTileDoesNotKeepStaleReferencesOrTimer() {
        open(tile("first"), tile("second"))
        expandFirst()
        manager.deleteTile("first")
        invoke("collapseSensor")
        assertNoExpansion()
        assertNull(get<Runnable?>("sensorCollapseRunnable"))
        tap("second")
        advance()
        assertSame(card("second"), get<View>("expandedSensorView"))
    }

    @Test fun rebuildImmediatelyUsesKnownSensorBatteryWeatherAndButtonValues() {
        get<MutableMap<String, String>>("singleStates").putAll(mapOf(
            "sensor.temp" to "12.5", "sensor.soc" to "50", "weather.home" to "snow", "light.safe" to "on"))
        get<MutableMap<String, JSONObject>>("entityAttributes")["weather.home"] = JSONObject().put("temperature", -4)
        open(tile("sensor"), tile("battery", "battery", """{"entity_id":"sensor.soc"}"""),
            tile("weather", "weather", """{"entity_id":"weather.home"}"""),
            tile("clock", "clock"), tile("light", "button", """{"entity_id":"light.safe"}"""))
        assertEquals(listOf("sensor", "T  12.5"), rows("sensor"))
        assertEquals("-4°C", card("weather").findViewWithTag<TextView>("weather_temp").text.toString())
        assertEquals("❄️", card("weather").findViewWithTag<TextView>("weather_icon").text.toString())
        assertEquals(110, card("battery").findViewWithTag<View>("battery_progress_fill").layoutParams.width)
        val button = panel.findViewWithTag<Button>("light")
        val filter = ReflectionHelpers.getField<android.graphics.PorterDuffColorFilter>(button.background, "mTintFilter")
        assertEquals(0x8033CC33.toInt(), shadowOf(filter).color)
        assertTrue(card("clock").findViewWithTag<TextView>("clock_text").text.matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test fun rebuildUsesUpdatedPreferencesBeforeFormatting() {
        open(tile("sensor"))
        get<MutableMap<String, String>>("singleStates")["sensor.temp"] = "0"
        get<MutableMap<String, JSONObject>>("entityAttributes")["sensor.temp"] = JSONObject().put("unit_of_measurement", "°C")
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putString("temp_unit", "F")
            .putString("motion_sensor_id", "binary_sensor.motion").putInt("screen_timeout_minutes", 2).commit()
        invoke("refreshBottomPanel")
        assertEquals(listOf("sensor", "T  32.0"), rows("sensor"))
        assertEquals("binary_sensor.motion", get<String>("motionSensorId"))
        assertEquals(2, get<Int>("screenTimeoutMinutes"))
    }

    @Test fun staleExpansionAnimationAndTimerCannotAffectNewTree() {
        open(tile("first"), tile("second"))
        val old = card("first")
        invoke("expandSensor", manager.loadTiles().single { it.id == "first" }, old)
        invoke("refreshBottomPanel")
        layoutCards()
        val oldWidthAfterCleanup = old.layoutParams.width
        tap("second")
        advance(1000)
        assertEquals(oldWidthAfterCleanup, old.layoutParams.width)
        assertSame(card("second"), get<View>("expandedSensorView"))
        // Новый таймер ещё не истёк, старый уже должен был бы сработать.
        advance(28000)
        invoke("resetSensorCollapseTimer")
        advance(2000)
        assertSame(card("second"), get<View>("expandedSensorView"))
    }

    @Test fun oldCollapseAnimationCannotReattachTouchListenerAfterRebuild() {
        open(tile("first"))
        expandFirst()
        val old = card("first")
        invoke("collapseSensor")
        invoke("refreshBottomPanel")
        val listenerAfterCleanup = shadowOf(old).onTouchListener
        advance(1000)
        assertSame(listenerAfterCleanup, shadowOf(old).onTouchListener)
        assertNull(old.parent)
        assertNoExpansion()
    }

    @Test fun rebuildRemovesExpandedChildrenAndOverlaySynchronously() {
        open(tile("group", "group", """{"entity_ids":["switch.a","light.b"]}"""))
        val button = panel.findViewWithTag<Button>("group")
        invoke("expandChildButtons", manager.loadTiles().single { it.id == "group" }, button)
        val oldChildren = get<List<Button>>("expandedChildButtons").toList()
        assertTrue(oldChildren.isNotEmpty())
        invoke("refreshBottomPanel")
        assertTrue(get<List<Button>>("expandedChildButtons").isEmpty())
        assertNull(get<String?>("expandedGroupId"))
        assertNull(get<View?>("expandedSourceButton"))
        assertNull(get<TileEntity?>("expandedTile"))
        assertEquals(View.GONE, dim.visibility)
        assertEquals(0f, dim.alpha, 0f)
        assertTrue(oldChildren.all { it.parent == null })
        advance(11000)
        assertEquals(View.GONE, dim.visibility)
    }

    @Test fun cleanupAlsoRemovesChildrenAlreadyAnimatingClosed() {
        open(tile("group", "group", """{"entity_ids":["switch.a"]}"""))
        invoke("expandChildButtons", manager.loadTiles().single { it.id == "group" }, panel.findViewWithTag<Button>("group"))
        val oldChildren = get<List<Button>>("expandedChildButtons").toList()
        invoke("collapseChildButtons")
        invoke("refreshBottomPanel")
        assertTrue(oldChildren.all { it.parent == null })
        // Старое завершение overlay не должно скрыть новое раскрытие.
        invoke("expandChildButtons", manager.loadTiles().single { it.id == "group" }, panel.findViewWithTag<Button>("group"))
        advance(500)
        assertEquals(View.VISIBLE, dim.visibility)
        assertEquals(0.4f, dim.alpha, 0.001f)
    }

    @Test fun repeatedRebuildDoesNotWriteSettingsOrDuplicateContent() {
        open(tile("first"))
        val before = activity.getSharedPreferences("tiles_prefs", 0).all.toMap()
        repeat(3) { invoke("refreshBottomPanel") }
        assertNoExpansion()
        assertEquals(2, panel.childCount)
        assertEquals(before, activity.getSharedPreferences("tiles_prefs", 0).all)
        assertEquals(2, rows("first").size)
    }

    @Test fun normalCollapseStillAnimatesAndAutomaticCollapseStillWorks() {
        open(tile("first"))
        expandFirst()
        val card = card("first")
        invoke("collapseSensor")
        assertNotEquals(180, card.layoutParams.height)
        advance()
        assertEquals(180, card.layoutParams.height)
        tap("first")
        advance()
        assertSame(card, get<View>("expandedSensorView"))
        advance(31000)
        assertNoExpansion()
        assertEquals(180, card.layoutParams.height)
    }

    private fun prepareLifecycleCallbacks() {
        // Как в существующих тестах настроек: инициализируем реестры без XML/onCreate.
        androidx.activity.ComponentActivity::class.java.declaredFields.single {
            it.type == androidx.savedstate.SavedStateRegistryController::class.java
        }.apply {
            isAccessible = true
            (get(activity) as androidx.savedstate.SavedStateRegistryController).performRestore(null)
        }
        androidx.fragment.app.FragmentActivity::class.java.declaredFields.single {
            it.type == androidx.fragment.app.FragmentController::class.java
        }.apply {
            isAccessible = true
            (get(activity) as androidx.fragment.app.FragmentController).attachHost(null)
        }
        (activity.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.CREATED
        ReflectionHelpers.getField<LifecycleRegistry>(activity, "mFragmentLifecycleRegistry")
            .currentState = Lifecycle.State.CREATED
    }

    @Test fun destroyCancelsPendingIdleAndOwnedClockCallbacksButNotForeignHandler() {
        open(tile("clock", "clock"))
        prepareLifecycleCallbacks()
        set("motionSensorId", "binary_sensor.motion")
        set("screenTimeoutMinutes", 1)
        invoke("resetIdleTimer")
        val idleHandler = get<Handler>("idleHandler")
        val idle = get<Runnable>("idleRunnable")
        assertTrue(idleHandler.hasCallbacks(idle))
        get<Handler>("clockHandler").post(get<Runnable>("clockRunnable"))
        var foreignRan = false
        Handler(Looper.getMainLooper()).postDelayed({ foreignRan = true }, 1000)
        val oldBrightness = activity.window.attributes.screenBrightness
        controller.destroy()
        advance(61000)
        assertEquals(oldBrightness, activity.window.attributes.screenBrightness, 0f)
        assertFalse(idleHandler.hasCallbacks(idle))
        assertFalse(get<Boolean>("isDimmed"))
        assertTrue(foreignRan)
        assertFalse(get<Handler>("clockHandler").hasCallbacks(get<Runnable>("clockRunnable")))
    }

    @Test fun onResumeUsesNewTemperatureSettingsBeforeRebuildWithoutConnecting() {
        open(tile("sensor"))
        prepareLifecycleCallbacks()
        get<MutableMap<String, String>>("singleStates")["sensor.temp"] = "0"
        get<MutableMap<String, JSONObject>>("entityAttributes")["sensor.temp"] = JSONObject().put("unit_of_measurement", "°C")
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putString("temp_unit", "F").commit()
        // Токен загружается асинхронно; отсутствие токена не требует сети и не мешает rebuild.
        set("haTokens", haTokenTestAccess(activity))
        set("mqttRemovalMigration", MqttRemovalMigration(
            activity.getSharedPreferences("dashboard_prefs", 0), { SecretStore.Status.ABSENT }, {}))
        ReflectionHelpers.setField(get<YandexWeatherManager>("yandexWeatherManager"), "weatherKeys", weatherKeyTestAccess(activity))
        (panel.parent as FrameLayout).addView(TextView(activity).apply { id = R.id.tvHaLiveStatus })
        invoke("onResume")
        assertEquals(listOf("sensor", "T  32.0"), rows("sensor"))
        invoke("onPause")
    }

    @Test fun pendingLongPressOfOldButtonCannotExpandAfterRebuild() {
        open(tile("first"), tile("group", "group", """{"entity_ids":["switch.a"]}"""))
        val oldButton = panel.findViewWithTag<Button>("group")
        send(oldButton, MotionEvent.ACTION_DOWN)
        invoke("refreshBottomPanel")
        layoutCards()
        tap("first")
        advance(1200)
        assertSame(card("first"), get<View>("expandedSensorView"))
        assertTrue(get<List<Button>>("expandedChildButtons").isEmpty())
        assertEquals(View.GONE, dim.visibility)
    }

    @Test fun destroyDuringInteractiveAnimationsAndRepeatedCleanupAreSafe() {
        open(tile("first"), tile("group", "group", """{"entity_ids":["switch.a"]}"""))
        prepareLifecycleCallbacks()
        val old = card("first")
        invoke("expandSensor", manager.loadTiles().single { it.id == "first" }, old)
        invoke("expandChildButtons", manager.loadTiles().single { it.id == "group" },
            panel.findViewWithTag<Button>("group"))
        val children = get<List<Button>>("expandedChildButtons").toList()
        controller.destroy()
        val stoppedWidth = old.layoutParams.width
        invoke("clearPanelInteractions")
        invoke("clearPanelInteractions")
        assertNoExpansion()
        assertNull(get<String?>("expandedGroupId"))
        assertEquals(View.GONE, dim.visibility)
        assertTrue(children.all { it.parent == null })
        advance(31000)
        assertEquals(stoppedWidth, old.layoutParams.width)
        assertNull(shadowOf(old).onTouchListener)
        assertEquals(View.GONE, dim.visibility)
    }

    @Test fun rebuildUsesYandexCacheWithoutNetworkMessagesOrSettingsWrites() {
        open(tile("weather", "weather", """{"entity_id":"weather.home"}"""))
        val yandex = get<YandexWeatherManager>("yandexWeatherManager")
        yandex.currentTemp = "7"
        yandex.currentState = "clear"
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putBoolean("use_yandex_weather", true).commit()
        val messages = mutableListOf<String>()
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("http://localhost/test-only").build()
            override fun queueSize() = 0L
            override fun send(text: String): Boolean { messages.add(text); return true }
            override fun send(bytes: ByteString): Boolean = error("Unexpected binary message")
            override fun close(code: Int, reason: String?) = true
            override fun cancel() {
                // Сокет только записывает сообщения и не подключается к сети.
            }
        }
        val client = HomeAssistantWebSocket("http://localhost/test-only", "test-token", { _, _, _ -> }, {}, {})
        ReflectionHelpers.setField(client, "authenticated", true)
        ReflectionHelpers.setField(client, "webSocket", socket)
        set("webSocket", client)
        val beforeTiles = activity.getSharedPreferences("tiles_prefs", 0).all.toMap()
        val beforePrefs = activity.getSharedPreferences("dashboard_prefs", 0).all.toMap()
        repeat(2) { invoke("refreshBottomPanel") }
        assertEquals(listOf("☀️", "7°C", "Ясно"), rows("weather"))
        assertTrue(messages.isEmpty())
        assertEquals(beforeTiles, activity.getSharedPreferences("tiles_prefs", 0).all)
        assertEquals(beforePrefs, activity.getSharedPreferences("dashboard_prefs", 0).all)
    }


    @Test fun normalChildCollapseKeepsAnimationAndTenSecondAutoCollapse() {
        open(tile("group", "group", """{"entity_ids":["switch.a"]}"""))
        fun expandGroup() {
            invoke("expandChildButtons", manager.loadTiles().single { it.id == "group" },
                panel.findViewWithTag<Button>("group"))
        }
        expandGroup()
        advance()
        val firstChildren = get<List<Button>>("expandedChildButtons").toList()
        invoke("collapseChildButtons")
        assertTrue(firstChildren.all { it.parent != null })
        advance()
        assertTrue(firstChildren.all { it.parent == null })
        assertEquals(View.GONE, dim.visibility)
        assertTrue(get<Set<Button>>("closingChildButtons").isEmpty())
        expandGroup()
        val secondChildren = get<List<Button>>("expandedChildButtons").toList()
        advance(11000)
        assertNull(get<String?>("expandedGroupId"))
        assertTrue(secondChildren.all { it.parent == null })
        assertEquals(View.GONE, dim.visibility)
        assertTrue(get<Set<Button>>("closingChildButtons").isEmpty())
    }

    @Test fun unchangedConfigurationRestartsStoppedSocketOnResume() {
        open(tile("sensor"))
        prepareLifecycleCallbacks()
        val prefs = activity.getSharedPreferences("dashboard_prefs", 0)
        prefs.edit().putString("ha_local_host", "http://localhost:1").commit()
        val tokens = haTokenTestAccess(activity)
        weatherWorker { tokens.replace("FAKE-RESUME-TOKEN") }
        set("haTokens", tokens)
        set("mqttCredentials", mqttTestAccess(activity))
        ReflectionHelpers.setField(get<YandexWeatherManager>("yandexWeatherManager"), "weatherKeys", weatherKeyTestAccess(activity))
        (panel.parent as FrameLayout).addView(TextView(activity).apply { id = R.id.tvHaLiveStatus })
        val socket = HomeAssistantWebSocket("http://localhost:1", "FAKE-RESUME-TOKEN", { _, _, _ -> }, {}, {})
        set("webSocket", socket)
        set("haConfiguration", listOf("http://localhost:1", "", "FAKE-RESUME-TOKEN"))
        set("connectedTokenRevision", HaTokenAccess.revision)
        assertFalse(ReflectionHelpers.getField<Boolean>(socket, "running"))
        invoke("onResume")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!ReflectionHelpers.getField<Boolean>(socket, "running") && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertSame(socket, get<HomeAssistantWebSocket>("webSocket"))
        assertTrue(ReflectionHelpers.getField<Boolean>(socket, "running"))
        socket.disconnect()
        invoke("onPause")
    }

}
