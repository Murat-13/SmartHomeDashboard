package com.example.smarthomedashboard

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

// Настоящие MainActivity, контроллер панели, ViewModel и общее раскрытие Modern. Сеть не запускается.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeClimatePanelTest {
    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).get() }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val viewModel get() = MainActivity::class.java.getDeclaredMethod("getViewModel")
        .apply { isAccessible = true }.invoke(activity) as MainViewModel
    private val controller get() = MainActivity::class.java.getDeclaredMethod("getPanelController")
        .apply { isAccessible = true }.invoke(activity) as DashboardPanelController
    private val state get() = ReflectionHelpers.getField<MutableStateFlow<MainUiState>>(viewModel, "_uiState")

    private fun room(id: String, name: String) = HomeClimateConfig.Room(id, name, "bed", "sensor.${id}_t", "sensor.${id}_h")

    private fun config() = HomeClimateConfig(
        weather = HomeClimateConfig.Weather(entityId = "weather.home"),
        rooms = (1..7).map { room("r$it", "Комната $it") },
        favorites = listOf("r3", "r1", "r2", "r4", "r5")
    ).writeTo("""{"button_size":900,"widget_height":300}""")

    private fun open() {
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = 1920
        activity.resources.displayMetrics.heightPixels = 1200
        manager.saveTiles(listOf(
            TileEntity("hc", HomeClimateConfig.TYPE, "grid", "Дом", 40, 140, 1, 1, config = config()),
            TileEntity("classic", "clock", "grid", "Часы", 1200, 140, 1, 1)
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
        state.value = state.value.copy(haSession = HaSession(connected = true))
        viewModel.onStatesSnapshot(listOf(
            HaEntityState("weather.home", "cloudy", JSONObject().put("temperature", 13).put("temperature_unit", "°C")
                .put("forecast", JSONArray().put(JSONObject().put("datetime", "2099-01-01T00:00:00Z").put("temperature", 5).put("templow", 1)))),
            HaEntityState("sensor.r1_t", "14.2", JSONObject().put("unit_of_measurement", "°C")),
            HaEntityState("sensor.r3_t", "17.1", JSONObject().put("unit_of_measurement", "°C")),
            HaEntityState("sensor.r1_h", "48", JSONObject().put("unit_of_measurement", "%"))
        ))
        state.value = state.value.copy(haSession = HaSession(connected = true, synced = true))
        ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
        for (i in 0 until panel.childCount) {
            val v = panel.getChildAt(i)
            v.measure(View.MeasureSpec.makeMeasureSpec(v.layoutParams.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(v.layoutParams.height, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, v.layoutParams.width, v.layoutParams.height)
        }
    }

    private fun tile(): FrameLayout = panel.findViewWithTag("hc")
    private fun compact(): HomeClimateCompactView = tile().findViewWithTag("hc_compact")
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private fun tap(view: View) {
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
            val e = MotionEvent.obtain(time, time + i, action, 20f, 20f, 0)
            try { view.dispatchTouchEvent(e) } finally { e.recycle() }
        }
    }

    @Test fun compactTileIsTwoToOneWithFavoritesInOrderAndRealValues() {
        open()
        // Сохранённые 900×300 не растягивают плитку в ленту.
        assertEquals(600, tile().layoutParams.width)
        assertEquals(300, tile().layoutParams.height)
        val snapshot = compact().boundSnapshot!!
        assertEquals(listOf("r3", "r1", "r2", "r4", "r5"), snapshot.favorites.map { it.room.id })
        assertEquals("17°", snapshot.favorites[0].temperature!!.compact())
        assertNull(snapshot.favorites[2].temperature) // датчика нет — «—», не 0
        assertEquals("13 °C", snapshot.current.temperature!!.full())
        assertTrue(compact().contentDescription.toString().contains("Пасмурно"))
    }

    @Test fun roomEventUpdatesOnlyThisTileAddressed() {
        open()
        viewModel.onEntityStateChanged("sensor.r2_t", "21.5", JSONObject().put("unit_of_measurement", "°C"))
        controller.renderEntityState("sensor.r2_t", "21.5")
        assertEquals("22°", compact().boundSnapshot!!.favorites[2].temperature!!.compact())
        // Потеря связи — показания перестают выдаваться за текущие.
        state.value = state.value.copy(haSession = HaSession())
        controller.renderSessionState()
        assertNull(compact().boundSnapshot!!.favorites[0].temperature)
        assertNotNull(compact().boundSnapshot!!.offlineStatus)
    }

    @Test fun tapExpandsThroughSharedModernExpansionAndHourlyArrivesInPlace() {
        open()
        tap(tile())
        val expansion = controller.modernExpansion
        assertTrue(expansion.isOpen)
        assertEquals("hc", expansion.tileId)
        val card = expansion.card as HomeClimateExpandedCard
        assertEquals(0f, tile().alpha) // исходная плитка скрыта на своём месте
        assertEquals(7, card.roomsList.adapter!!.itemCount) // все комнаты, не только избранные
        assertEquals(HomeClimateSnapshot.ForecastState.WAITING, card.boundSnapshot!!.hourly.state)
        assertEquals(HomeClimateSnapshot.ForecastState.READY, card.boundSnapshot!!.daily.state)

        val hours = JSONArray()
        (0..5).forEach { hours.put(JSONObject().put("datetime", "2099-01-01T%02d:00:00Z".format(it)).put("temperature", it).put("condition", "sunny")) }
        viewModel.devices.hourlyForecastChanged("weather.home", hours, true)
        controller.renderForecastState("weather.home")
        assertEquals(HomeClimateSnapshot.ForecastState.READY, card.boundSnapshot!!.hourly.state)
        assertEquals(6, (card.hourlyScroll.getChildAt(0) as LinearLayout).childCount)

        expansion.collapse()
        advance(BatteryModernExpansion.CLOSE_MS + 50)
        assertFalse(expansion.isOpen)
        assertEquals(1f, tile().alpha)
    }

    @Test fun yandexRefreshUpdatesCompactAndExpandedInPlaceWithoutHaConnection() {
        open()
        val tile = manager.loadTiles().first { it.id == "hc" }
        val config = HomeClimateConfig.from(tile.config)
        manager.updateTile(tile.copy(config = config.copy(weather = config.weather.copy(source = HomeClimateConfig.SOURCE_YANDEX)).writeTo(tile.config)))
        ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
        state.value = state.value.copy(haSession = HaSession())
        fun update(temp: Int) {
            viewModel.onYandexWeatherChanged(YandexClimateWeather.parse(JSONObject().put("fact", JSONObject()
                .put("temp", temp).put("condition", "clear"))))
            controller.updateWeatherViews()
        }
        val compact = compact()
        update(13)
        assertSame(compact, compact())
        assertEquals("13 °C", compact.boundSnapshot!!.current.temperature!!.full())
        assertNull(compact.boundSnapshot!!.rooms.first().temperature)
        tap(tile())
        val card = controller.modernExpansion.card as HomeClimateExpandedCard
        update(15)
        assertSame(card, controller.modernExpansion.card)
        assertEquals("15 °C", card.boundSnapshot!!.current.temperature!!.full())
        assertEquals("15 °C", compact.boundSnapshot!!.current.temperature!!.full())
    }

    @Test fun editModeTapDoesNotExpand() {
        open()
        viewModel.setEditMode(true)
        ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
        tap(tile())
        assertFalse(controller.modernExpansion.isOpen)
    }
}

// Редактор без PIN-шага (onResume не вызывается): черновик, избранное, запись только по «Сохранить».
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeClimateEditorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)
    private val model = MainViewModel()
    private val original = TileEntity("hc", HomeClimateConfig.TYPE, "grid", "Дом", 10, 20, 1, 1,
        config = HomeClimateConfig(rooms = (1..6).map { HomeClimateConfig.Room("r$it", "Комната $it", null, "sensor.t$it") })
            .writeTo(HomeClimateConfig.newTileConfig("""{"custom":"keep"}""")))

    private fun open(): HomeClimateEditorActivity {
        manager.saveTiles(listOf(original))
        model.onStatesSnapshot(listOf(HaEntityState("weather.home", "sunny", JSONObject()),
            HaEntityState("sensor.attic", "14", JSONObject().put("unit_of_measurement", "°C"))))
        TileEditorPreviewSource.attach(model)
        return Robolectric.buildActivity(HomeClimateEditorActivity::class.java,
            Intent(context, HomeClimateEditorActivity::class.java).putExtra("tile_id", "hc")).create().get().also { idle() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun saved() = HomeClimateConfig.from(manager.loadTiles().single().config)

    @Test fun sixthFavoriteIsRefusedWithMessageAndSaveWritesOnlyOnSave() {
        val editor = open()
        assertNotNull(editor.preview.boundSnapshot) // предпросмотр сразу
        editor.showSection(HomeClimateEditorActivity.SECTION_ROOMS)
        (1..5).forEach { editor.toggleFavorite("r$it") }
        editor.toggleFavorite("r6")
        assertTrue(ShadowToast.getTextOfLatestToast().contains("не больше 5"))
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), editor.draft.config.favorites)
        assertTrue(saved().favorites.isEmpty()) // до «Сохранить» ничего не записано
        assertEquals(5, editor.preview.boundSnapshot!!.favorites.size)
        assertTrue(editor.save())
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), saved().favorites)
        assertEquals("keep", JSONObject(manager.loadTiles().single().config).getString("custom"))
        assertEquals(original.x, manager.loadTiles().single().x)
    }

    @Test fun roomDialogRequiresTemperatureAndCancelLeavesTileUnchanged() {
        val editor = open()
        editor.showSection(HomeClimateEditorActivity.SECTION_ROOMS)
        editor.roomDialog(editor.draft.newRoom(), isNew = true)
        val dialog = (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).also { idle() }
        dialog.window!!.decorView.findViewWithTag<EditText>("hc_room_name").setText("Чердак")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing) // без датчика температуры комната не добавляется
        dialog.window!!.decorView.findViewWithTag<EditText>("hc_room_temperature_manual").setText("sensor.attic")
        dialog.window!!.decorView.findViewWithTag<View>("hc_room_temperature_apply").performClick()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals("Чердак", editor.draft.config.rooms.last().name)
        assertEquals("sensor.attic", editor.draft.config.rooms.last().temperatureEntity)
        assertEquals("", editor.draft.config.rooms.last().humidityEntity)
        // «Не сохранять» — плитка прежняя.
        editor.leave()
        val confirm = (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).also { idle() }
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(6, saved().rooms.size)
        assertEquals(original.config, manager.loadTiles().single().config)
    }
}
