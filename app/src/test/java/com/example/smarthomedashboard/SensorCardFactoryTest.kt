package com.example.smarthomedashboard

import android.content.res.Resources
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

// В JVM нет APK-ресурсов. Проверяем выбор ic_resize и реальные Views,
// но сама иконка здесь заменена простым drawable, а не проверена визуально.
@Implements(Resources::class)
class SensorCardResources : ShadowResources() {
    @RealObject private lateinit var resources: Resources

    companion object {
        val requestedIds = mutableListOf<Int>()
    }

    @Implementation
    fun getDrawable(id: Int, theme: Resources.Theme?): Drawable {
        if (id == R.drawable.ic_resize) {
            requestedIds.add(id)
            return ColorDrawable(Color.WHITE)
        }
        return Shadow.directlyOn(resources, Resources::class.java, "getDrawable",
            ClassParameter.from(Int::class.javaPrimitiveType, id),
            ClassParameter.from(Resources.Theme::class.java, theme))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [SensorCardResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SensorCardFactoryTest {
    @org.junit.Before fun resetResources() {
        SensorCardResources.requestedIds.clear()
    }
    private val activity by lazy {
        Robolectric.buildActivity(MainActivity::class.java).get().apply {
            pinGestureFixture(this)
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            resources.displayMetrics.widthPixels = 1920
            resources.displayMetrics.heightPixels = 1200
        }
    }

    private fun tile(type: String = "sensor", config: String = "{}",
        id: String = "card", title: String = "Мой датчик") =
        TileEntity(id, type, "grid", title, 20, 30, 1, 1, config = config, fontSize = 18)

    private fun set(name: String, value: Any?) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true; set(activity, value) }
    }

    private fun invoke(name: String, vararg args: Any?): Any? {
        try {
            return MainActivity::class.java.declaredMethods.single {
                it.name == name && it.parameterTypes.size == args.size
            }.apply { isAccessible = true }.invoke(activity, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    // Одни и те же сценарии выполняются на исходном методе и после переноса в factory.
    private fun create(tile: TileEntity = tile(), editMode: Boolean = false): FrameLayout {
        set("isEditMode", editMode)
        return invoke("createSensorView", tile) as FrameLayout
    }

    private fun content(card: FrameLayout): LinearLayout = card.findViewWithTag("sensor_text_container")

    private fun handle(card: FrameLayout): ImageView =
        (0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<ImageView>().single()

    private fun layout(card: FrameLayout, width: Int, height: Int) {
        card.forceLayout()
        for (i in 0 until card.childCount) card.getChildAt(i).forceLayout()
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, width, height)
    }

    private fun event(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 1, action, x, y, 0)
        try {
            assertTrue(view.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    @Test fun sensorKeepsTreeTitleContextAndDefaultSizes() {
        val card = create()
        assertEquals(FrameLayout::class.java, card.javaClass)
        assertSame(activity, card.context)
        assertEquals("card", card.tag)
        assertEquals(FrameLayout.LayoutParams::class.java, card.layoutParams.javaClass)
        assertEquals(220, card.layoutParams.width)
        assertEquals(180, card.layoutParams.height)
        assertEquals(2, card.childCount)
        val text = content(card)
        assertSame(text, card.getChildAt(0))
        assertEquals(LinearLayout.VERTICAL, text.orientation)
        assertEquals(Gravity.CENTER, text.gravity)
        assertEquals(FrameLayout.LayoutParams::class.java, text.layoutParams.javaClass)
        assertEquals(-1, text.layoutParams.width)
        assertEquals(-1, text.layoutParams.height)
        assertEquals(listOf(8, 8, 8, 8),
            listOf(text.paddingLeft, text.paddingTop, text.paddingRight, text.paddingBottom))
        assertEquals(1, text.childCount)
        val title = text.getChildAt(0) as TextView
        assertSame(activity, title.context)
        assertEquals("sensor_title", title.tag)
        assertEquals("Мой датчик", title.text.toString())
        assertEquals(18f * activity.resources.displayMetrics.scaledDensity, title.textSize, 0.001f)
        assertEquals(Color.WHITE, title.currentTextColor)
        assertEquals(Gravity.CENTER, title.gravity)
        assertEquals(4f, title.shadowRadius, 0f)
        assertEquals(2f, title.shadowDx, 0f)
        assertEquals(2f, title.shadowDy, 0f)
        assertEquals(Color.BLACK, title.shadowColor)
        assertEquals(LinearLayout.LayoutParams::class.java, title.layoutParams.javaClass)
        assertEquals(-1, title.layoutParams.width)
        assertEquals(-2, title.layoutParams.height)
    }

    @Test fun customSizesAndExistingFallbacksArePreserved() {
        val custom = create(tile(config = """{"button_size":310,"widget_height":210}"""))
        assertEquals(310, custom.layoutParams.width)
        assertEquals(210, custom.layoutParams.height)
        val onlyWidth = create(tile(config = """{"button_size":310}"""))
        assertEquals(180, onlyWidth.layoutParams.height)
        val broken = create(tile(config = "broken"))
        assertEquals(220, broken.layoutParams.width)
        assertEquals(180, broken.layoutParams.height)
    }

    @Test fun appearanceAndOutlineArePreserved() {
        val card = create()
        assertEquals(0x80333333.toInt(), (card.background as ColorDrawable).color)
        assertEquals(0.85f, card.alpha, 0f)
        assertEquals(8f, card.elevation, 0f)
        assertTrue(card.clipToOutline)
        layout(card, 220, 180)
        val outline = Outline()
        card.outlineProvider.getOutline(card, outline)
        val rect = Rect()
        assertTrue(outline.getRect(rect))
        assertEquals(Rect(0, 0, 220, 180), rect)
        assertEquals(32f, outline.radius, 0f)
    }

    @Test fun batteryFillIsBelowTextAndKeepsInitialLayout() {
        val card = create(tile(type = "battery"))
        assertEquals(3, card.childCount)
        val fill = card.getChildAt(0)
        assertEquals(View::class.java, fill.javaClass)
        assertEquals("battery_progress_fill", fill.tag)
        assertEquals(FrameLayout.LayoutParams::class.java, fill.layoutParams.javaClass)
        assertEquals(0, fill.layoutParams.width)
        assertEquals(-1, fill.layoutParams.height)
        assertEquals(0x80FF3333.toInt(), (fill.background as ColorDrawable).color)
        assertSame(content(card), card.getChildAt(1))
        assertSame(handle(card), card.getChildAt(2))
        assertEquals("Мой датчик", (content(card).getChildAt(0) as TextView).text.toString())
    }

    @Test fun knownBatteryStateAppliesAfterFirstMeasureAndResize() {
        val states = ReflectionHelpers.getField<MutableMap<String, String>>(activity, "singleStates")
        states["sensor.soc"] = "50"
        val card = create(tile(type = "battery", config = """{"entity_id":"sensor.soc"}"""))
        val fill = card.findViewWithTag<View>("battery_progress_fill")
        assertEquals(0x8033CC33.toInt(), (fill.background as ColorDrawable).color)
        layout(card, 220, 180)
        layout(card, 220, 180)
        assertEquals(110, fill.layoutParams.width)
        layout(card, 300, 200)
        layout(card, 300, 200)
        assertEquals(150, fill.layoutParams.width)
        assertEquals(150, fill.width)
    }

    @Test fun clockKeepsDateFormatTagsAndTextSizes() {
        val oldLocale = Locale.getDefault()
        val oldZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val card = create(tile(type = "clock", config = """{"widget_height":200}"""))
            val text = content(card)
            assertEquals(2, text.childCount)
            val time = text.getChildAt(0) as TextView
            val date = text.getChildAt(1) as TextView
            assertEquals("clock_text", time.tag)
            assertEquals("clock_date", date.tag)
            assertTrue(time.text.toString().matches(Regex("[0-9]{2}:[0-9]{2}")))
            assertTrue(date.text.toString().matches(Regex("[A-Za-z]+, [0-9]{1,2} [A-Za-z]+")))
            assertEquals(80f, time.textSize, 0.001f)
            assertEquals(30f, date.textSize, 0.001f)
            assertEquals(Typeface.BOLD, time.typeface.style)
            assertEquals(8f, time.shadowRadius, 0f)
            assertEquals(4f, date.shadowRadius, 0f)
            assertEquals(Color.WHITE, time.currentTextColor)
            assertEquals(Gravity.CENTER, date.gravity)
            Locale.setDefault(Locale.GERMANY)
            val german = create(tile(type = "clock"))
            assertTrue(german.findViewWithTag<TextView>("clock_date").text.toString()
                .matches(Regex("[^,]+, [0-9]{1,2} [^ ]+")))
        } finally {
            Locale.setDefault(oldLocale)
            TimeZone.setDefault(oldZone)
        }
    }

    @Test fun weatherKeepsInitialElementsOrderAndSizes() {
        val card = create(tile(type = "weather", config = """{"widget_height":200}"""))
        val text = content(card)
        assertEquals(3, text.childCount)
        val icon = text.getChildAt(0) as TextView
        val temp = text.getChildAt(1) as TextView
        val desc = text.getChildAt(2) as TextView
        assertEquals(listOf("weather_icon", "weather_temp", "weather_desc"),
            listOf(icon.tag, temp.tag, desc.tag))
        assertEquals(listOf("☀️", "—°C", "Загрузка..."),
            listOf(icon.text.toString(), temp.text.toString(), desc.text.toString()))
        assertEquals(70f, icon.textSize, 0.001f)
        assertEquals(50f, temp.textSize, 0.001f)
        assertEquals(24f, desc.textSize, 0.001f)
        assertEquals(Typeface.BOLD, temp.typeface.style)
        listOf(icon, temp, desc).forEach {
            assertEquals(Color.WHITE, it.currentTextColor)
            assertEquals(Gravity.CENTER, it.gravity)
            assertEquals(LinearLayout.LayoutParams::class.java, it.layoutParams.javaClass)
        }
    }

    @Test fun resizeHandleKeepsResourceParamsAndEditVisibility() {
        val normal = create()
        val edit = create(editMode = true)
        assertEquals(View.GONE, handle(normal).visibility)
        val resize = handle(edit)
        assertEquals(View.VISIBLE, resize.visibility)
        assertSame(activity, resize.context)
        assertEquals(listOf(R.drawable.ic_resize, R.drawable.ic_resize), SensorCardResources.requestedIds)
        assertNotNull(resize.drawable)
        val params = resize.layoutParams as FrameLayout.LayoutParams
        assertEquals(40, params.width)
        assertEquals(40, params.height)
        assertEquals(Gravity.BOTTOM or Gravity.END, params.gravity)
        assertEquals(listOf(8, 8, 8, 8),
            listOf(resize.paddingLeft, resize.paddingTop, resize.paddingRight, resize.paddingBottom))
    }

    @Test fun cardsAndChildrenAreIndependent() {
        val first = create(tile(id = "first"))
        val second = create(tile(id = "second"))
        assertNotSame(first.background, second.background)
        assertNotSame(content(first), content(second))
        assertNotSame(handle(first), handle(second))
        first.setBackgroundColor(Color.RED)
        (content(first).getChildAt(0) as TextView).text = "Изменено"
        assertEquals(0x80333333.toInt(), (second.background as ColorDrawable).color)
        assertEquals("Мой датчик", (content(second).getChildAt(0) as TextView).text.toString())
    }

    @Test fun cardTouchStillOpensSettingsInEditModeAndClockDoesNotExpand() {
        val edit = create(tile(id = "edit-card"), editMode = true)
        event(edit, MotionEvent.ACTION_DOWN, 10f, 10f)
        event(edit, MotionEvent.ACTION_UP, 10f, 10f)
        awaitPinGate(activity)
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals("edit-card", started.intent.getStringExtra("tile_id"))
        val clock = create(tile(type = "clock"))
        event(clock, MotionEvent.ACTION_DOWN, 10f, 10f)
        event(clock, MotionEvent.ACTION_UP, 10f, 10f)
        assertNull(sensorSessionFixture(activity).current)
    }

    @Test fun resizeTouchKeepsTextScalingAndPersistsMeasuredSize() {
        val manager = TileManager(activity)
        set("tileManager", manager)
        listOf("clock", "weather").forEach { type ->
            val tile = tile(type = type)
            manager.saveTiles(listOf(tile))
            val card = create(tile, editMode = true)
            layout(card, 220, 180)
            val resize = handle(card)
            event(resize, MotionEvent.ACTION_DOWN, 10f, 10f)
            assertTrue(ReflectionHelpers.getField<Boolean>(activity, "isResizing"))
            event(resize, MotionEvent.ACTION_MOVE, 70f, 50f)
            assertEquals(280, card.layoutParams.width)
            assertEquals(220, card.layoutParams.height)
            if (type == "clock") {
                assertEquals(88f, card.findViewWithTag<TextView>("clock_text").textSize, 0.001f)
                assertEquals(33f, card.findViewWithTag<TextView>("clock_date").textSize, 0.001f)
            } else {
                assertEquals(77f, card.findViewWithTag<TextView>("weather_icon").textSize, 0.001f)
                assertEquals(55f, card.findViewWithTag<TextView>("weather_temp").textSize, 0.001f)
                assertEquals(26.4f, card.findViewWithTag<TextView>("weather_desc").textSize, 0.001f)
            }
            layout(card, 280, 220)
            event(resize, MotionEvent.ACTION_UP, 70f, 50f)
            assertFalse(ReflectionHelpers.getField<Boolean>(activity, "isResizing"))
            val saved = JSONObject(manager.loadTiles().single().config)
            assertEquals(280, saved.getInt("button_size"))
            assertEquals(220, saved.getInt("widget_height"))
        }
    }

    private class RecordingSocket : WebSocket {
        val messages = mutableListOf<String>()
        override fun request() = Request.Builder().url("http://localhost/test-only").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(text); return true }
        override fun send(bytes: ByteString): Boolean = error("Unexpected binary message")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {
            // Соединение не открывается: освобождать сетевые ресурсы здесь не требуется.
        }
    }

    @Test fun constructionDoesNotWriteSettingsOrSendCommands() {
        val manager = TileManager(activity)
        val tiles = listOf(tile(), tile("battery"), tile("clock"), tile("weather"))
        manager.saveTiles(tiles)
        set("tileManager", manager)
        val tilePrefs = activity.getSharedPreferences("tiles_prefs", 0).all.toMap()
        val prefs = activity.getSharedPreferences("dashboard_prefs", 0).all.toMap()
        val socket = RecordingSocket()
        val client = HomeAssistantWebSocket(
            "http://localhost/test-only", "test-token", { _, _, _ -> }, {}, {}
        )
        HomeAssistantWebSocket::class.java.getDeclaredField("authenticated").apply {
            isAccessible = true; set(client, true)
        }
        HomeAssistantWebSocket::class.java.getDeclaredField("webSocket").apply {
            isAccessible = true; set(client, socket)
        }
        val owner = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
        ReflectionHelpers.setField(owner, "webSocket", client)
        tiles.forEach { create(it) }
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertTrue(socket.messages.isEmpty())
        assertEquals(tilePrefs, activity.getSharedPreferences("tiles_prefs", 0).all)
        assertEquals(prefs, activity.getSharedPreferences("dashboard_prefs", 0).all)
    }

    @Test fun factoryFormatsControlledInstantAtOriginalConstructionPoint() {
        val oldLocale = Locale.getDefault()
        val oldZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            listOf(Locale.US to "Monday, 15 January", Locale.GERMANY to "Montag, 15 Januar")
                .forEach { (locale, expectedDate) ->
                    Locale.setDefault(locale)
                    val card = SensorCardFactory.createBase(activity, "clock", "clock", 220, 200)
                    var timeCalls = 0
                    val views = SensorCardFactory.addContent(
                        activity, card, "clock", "Часы", 18, 200, false,
                        currentTime = {
                            timeCalls++
                            // В исходном коде Date создаётся до добавления текста в карточку.
                            assertEquals(0, card.childCount)
                            java.util.Date(1705322096000L)
                        }
                    )
                    assertEquals(1, timeCalls)
                    assertSame(card, views.card)
                    assertSame(card, views.resizeHandle.parent)
                    assertEquals("12:34", card.findViewWithTag<TextView>("clock_text").text.toString())
                    assertEquals(expectedDate, card.findViewWithTag<TextView>("clock_date").text.toString())
                }
        } finally {
            Locale.setDefault(oldLocale)
            TimeZone.setDefault(oldZone)
        }
    }

    @Test fun factoryBaseLeavesBatteryLayerReadyForActivityUpdate() {
        val card = SensorCardFactory.createBase(activity, "battery", "battery", 220, 180)
        assertEquals(1, card.childCount)
        val fill = card.getChildAt(0)
        assertEquals("battery_progress_fill", fill.tag)
        assertEquals(0, fill.layoutParams.width)
        assertEquals(0x8033CC33.toInt(), (fill.background as ColorDrawable).color)
        val views = SensorCardFactory.addContent(
            activity, card, "battery", "Батарея", 18, 180, false
        )
        assertSame(card, views.card)
        assertSame(fill, card.getChildAt(0))
        assertSame(content(card), card.getChildAt(1))
        assertSame(views.resizeHandle, card.getChildAt(2))
        assertFalse(card.hasOnClickListeners())
    }
}
