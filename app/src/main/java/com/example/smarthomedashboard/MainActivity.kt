package com.example.smarthomedashboard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import android.util.Log
import android.graphics.Typeface

class MainActivity : AppCompatActivity() {

    // ==================== UI ====================
    private lateinit var bottomPanel: FrameLayout
    private lateinit var overlayContainer: FrameLayout
    private lateinit var dimOverlay: View
    private var panelHint: View? = null
    private var panelHintText: TextView? = null
    private var shownPanelHint: UserHints.Hint? = null

    // ==================== ДАННЫЕ ====================
    private lateinit var tileManager: TileManager
    private lateinit var yandexWeatherManager: YandexWeatherManager
    private var mqttRemovalMigration: MqttRemovalMigration? = null
    // Соединением владеет ViewModel; экран не хранит отдельную копию ссылки.
    private val webSocket: HomeAssistantWebSocket? get() = viewModel.getWebSocket()

    private var tempUnit = "C"
    private var motionSensorId: String? = null
    private var screenTimeoutMinutes = 5
    private var isDimmed = false
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { dimScreen() }

    // ==================== СОСТОЯНИЯ & VIEWMODEL ====================
    private val viewModel: MainViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
    }

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var collapseChildrenRunnable: Runnable? = null

    // Только представления данных владельца: отдельных копий в Activity нет.
    private val groupStates get() = viewModel.devices.groupStates
    private val singleStates get() = viewModel.devices.singleStates
    private val entityAttributes get() = viewModel.devices.entityAttributes
    private val syntheticForecasts get() = viewModel.devices.syntheticForecasts
    private var isEditMode: Boolean
        get() = viewModel.uiState.value.isEditMode
        set(value) { viewModel.setEditMode(value) }

    private val expandedChildButtons = mutableListOf<Button>()
    private val closingChildButtons = mutableSetOf<Button>()
    private var expandedGroupId: String? = null
    private var expandedSourceButton: Button? = null
    private var expandedTile: TileEntity? = null

    private val sensorExpansion = SensorExpansionSession(handler)
    private val expandedSensorView: View? get() = sensorExpansion.current
    private val weatherForecastScroll = WeatherForecastScrollState()
    private var isResizing = false
    private val resizeHandlers = mutableListOf<SensorResizeHandler>()
    private val sensorTouchSlop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop }
    private var sensorWindowGesture: SensorWindowGesture? = null
    private var consumeSensorWindowGesture = false

    private class SensorWindowGesture(
        val source: View,
        val down: MotionEvent,
        val startX: Float,
        val startY: Float,
        val inside: Boolean
    ) {
        var moved = false
        var forwarded = false
    }

    private val screenW by lazy { resources.displayMetrics.widthPixels }
    private val screenH by lazy { resources.displayMetrics.heightPixels }

    // ==================== КОНСТАНТЫ ====================
    companion object {
        private const val REQUEST_TILE_SETTINGS = 100
        private const val DEFAULT_BUTTON_SIZE = 160
        private const val PREF_STARTER_TILES_DONE = "starter_tiles_done"
        private const val PREF_WELCOME_SHOWN = "welcome_shown"
        internal const val WEATHER_REFRESH_MS = 60 * 60 * 1000L

        /** Панель уже создана: по кнопке «Домой» заставка не нужна. */
        @Volatile internal var isAlive = false
            private set
    }

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isAlive = true
        setupPanelWindow()
        setContentView(R.layout.activity_main)
        bottomPanel = findViewById(R.id.bottomPanel)
        overlayContainer = findViewById(R.id.overlayContainer)
        dimOverlay = findViewById(R.id.dimOverlay)
        dimOverlay.setOnClickListener { collapseAll() }
        setupPanelHint(findViewById(R.id.panelHint), findViewById(R.id.tvPanelHint), findViewById(R.id.btnPanelHintClose))
        // Реальная высота панели известна только после layout: тогда уточняем место подсказки.
        bottomPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) updatePanelHint()
        }

        tileManager = TileManager(this)
        yandexWeatherManager = YandexWeatherManager(this)
        yandexWeatherManager.loadFromCache()

        createStarterTilesOnFirstLaunch()

        setupBottomPanel()
        setupWebSocket()
        showWelcomeOnFirstLaunch()
    }

    private var uiStateJob: Job? = null

    override fun onStart() {
        super.onStart()
        uiStateJob = lifecycleScope.launch {
            viewModel.uiState.map { it.statusMessage }.distinctUntilChanged().collect { status ->
                status?.let { setHaStatus(it) }
            }
        }
    }

    override fun onStop() {
        uiStateJob?.cancel()
        uiStateJob = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!::tileManager.isInitialized) return
        refreshBottomPanel()
        // Удаляем устаревшие данные подключения без доступа к сети и без блокировки UI.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                (mqttRemovalMigration ?: MqttRemovalMigration.create(applicationContext).also { mqttRemovalMigration = it }).run()
            }
        }
        
        // Сразу и далее раз в час: панель может неделями не уходить с экрана.
        clockHandler.removeCallbacks(weatherRefreshRunnable)
        clockHandler.post(weatherRefreshRunnable)

        setupWebSocket()
        subscribeToNeededEntities()
        checkKioskMode()
        resetIdleTimer()
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        pendingPin?.cancel()
        pendingPin=null
        haTokenRequest++
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        clockHandler.removeCallbacks(weatherRefreshRunnable)
        if (::yandexWeatherManager.isInitialized) yandexWeatherManager.cancelPendingRequests()
    }

    // Бесплатный ключ Яндекса ограничен ~50 запросами в сутки: раз в час — 24 запроса с запасом.
    private val weatherRefreshRunnable = object : Runnable {
        override fun run() {
            refreshYandexWeather()
            clockHandler.postDelayed(this, WEATHER_REFRESH_MS)
        }
    }

    private fun refreshYandexWeather() {
        // Bridge выполняет только локальный IO. Сетевой запрос по-прежнему разрешён лишь выбранному Яндексу.
        yandexWeatherManager.fetchWeather(lifecycleScope, object : YandexWeatherManager.WeatherCallback {
            override fun onWeatherUpdated() {
                runOnUiThread { updateWeatherViews() }
            }
            override fun onError(error: String) {
                Log.e("MainActivity", "Yandex Weather Error: $error")
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun setupPanelWindow() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        // Панель висит на стене: экран не гаснет, пока она на переднем плане.
        // Флаг действует только для видимого окна, поэтому WAKE_LOCK не нужен.
        // Экономию даёт затемнение по датчику движения (dimScreen), а не отключение экрана:
        // так панель мгновенно просыпается по событию движения из HA.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun checkKioskMode() {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val isKiosk = prefs.getBoolean("kiosk_mode", false)
        try {
            if (isKiosk) {
                startLockTask()
            } else {
                stopLockTask()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        // При пересоздании (поворот) новая панель снова пометит себя в onCreate.
        isAlive = false
        clockHandler.removeCallbacks(weatherRefreshRunnable)
        if (::yandexWeatherManager.isInitialized) yandexWeatherManager.cancelPendingRequests()
        super.onDestroy()
        clearPanelInteractions()
        idleHandler.removeCallbacks(idleRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        haTokenRequest++
        haGeneration++
        viewModel.detachUi()
    }

    // ==================== БЕЗОПАСНОСТЬ И PIN-КОД ====================

    /**
     * Выполняет действие только после проверки PIN-кода (если он установлен).
     * Поддерживает "сессию": если верный PIN был введен недавно, повторный запрос не выводится.
     */
    private var pinAccess: PinAccess? = null
    private var pendingPin: kotlinx.coroutines.Job? = null
    private fun openWithPinCheck(action: () -> Unit) {
        if(pendingPin?.isActive==true)return
        val access=pinAccess ?: AndroidPinAccess.create(this).also {pinAccess=it}
        pendingPin=PinSecurityUi.authorize(this, action,access=access)
    }

    // ==================== РЕЖИМ РЕДАКТИРОВАНИЯ ====================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        resetIdleTimer()
        if (event.action == MotionEvent.ACTION_DOWN) {
            val hit = findViewAt(event.x, event.y)
            if (hit == null || hit == dimOverlay) {
                if (isEditMode) exitEditMode()
                else {
                    longPressRunnable = Runnable { openWithPinCheck { enterEditMode() } }
                    handler.postDelayed(longPressRunnable!!, 3000L)
                }
            }
        } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            longPressRunnable?.let { handler.removeCallbacks(it) }
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        resetIdleTimer()
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            cancelSensorWindowGesture()
            consumeSensorWindowGesture = false
            val source = expandedSensorView ?: return super.dispatchTouchEvent(event)
            val location = IntArray(2)
            source.getLocationInWindow(location)
            val inside = event.x >= location[0] && event.x < location[0] + source.width &&
                event.y >= location[1] && event.y < location[1] + source.height
            sensorWindowGesture = SensorWindowGesture(source, sensorLocalEvent(event, source), event.x, event.y, inside)
            consumeSensorWindowGesture = true
            if (inside) resetSensorCollapseTimer()
            return true
        }
        if (!consumeSensorWindowGesture) return super.dispatchTouchEvent(event)

        val gesture = sensorWindowGesture
        if (gesture != null) {
            val current = expandedSensorView === gesture.source && gesture.source.parent === bottomPanel &&
                tileManager.getAllTiles().any { it.id == gesture.source.tag }
            if (!current || action == MotionEvent.ACTION_CANCEL || event.pointerCount > 1 ||
                action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                // Остаток жеста остаётся поглощённым даже после удаления/закрытия исходной View.
                cancelSensorWindowGesture()
                if (!current && expandedSensorView === gesture.source) clearSensorExpansion()
            } else {
                fun record(x: Float, y: Float) {
                    val dx = x - gesture.startX
                    val dy = y - gesture.startY
                    if (dx * dx + dy * dy > sensorTouchSlop * sensorTouchSlop) gesture.moved = true
                }
                for (i in 0 until event.historySize) record(event.getHistoricalX(i), event.getHistoricalY(i))
                record(event.x, event.y)
                if (gesture.inside) resetSensorCollapseTimer()
                if (action == MotionEvent.ACTION_MOVE && gesture.inside && gesture.moved) {
                    // Не передаём DOWN дочерним click/long-click до распознавания движения.
                    // При прокрутке обычная диспетчеризация дерева сама выбирает вложенного получателя.
                    if (!gesture.forwarded) {
                        gesture.forwarded = true
                        gesture.source.dispatchTouchEvent(gesture.down)
                        clearSensorChildPresses(gesture.source)
                    }
                    val local = sensorLocalEvent(event, gesture.source)
                    try { gesture.source.dispatchTouchEvent(local) } finally { local.recycle() }
                    clearSensorChildPresses(gesture.source)
                }
                if (action == MotionEvent.ACTION_UP) {
                    if (gesture.forwarded) {
                        // UP нужен прокрутке для штатного завершения и fling, но не дочернему click.
                        clearSensorChildPresses(gesture.source)
                        val local = sensorLocalEvent(event, gesture.source)
                        try { gesture.source.dispatchTouchEvent(local) } finally { local.recycle() }
                        gesture.forwarded = false
                        clearSensorChildPresses(gesture.source)
                    }
                    val tap = !gesture.moved &&
                        event.eventTime - gesture.down.downTime < android.view.ViewConfiguration.getLongPressTimeout()
                    cancelSensorWindowGesture()
                    if (tap) collapseSensor()
                }
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cancelSensorWindowGesture()
            consumeSensorWindowGesture = false
        }
        return true
    }

    private fun sensorLocalEvent(event: MotionEvent, source: View): MotionEvent {
        val location = IntArray(2)
        source.getLocationInWindow(location)
        return MotionEvent.obtain(event).apply { offsetLocation(-location[0].toFloat(), -location[1].toFloat()) }
    }

    private fun clearSensorChildPresses(view: View) {
        view.cancelLongPress()
        view.isPressed = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) clearSensorChildPresses(view.getChildAt(i))
        } else {
            view.cancelPendingInputEvents()
        }
    }

    private fun cancelSensorWindowGesture() {
        val gesture = sensorWindowGesture ?: return
        sensorWindowGesture = null
        if (gesture.forwarded) {
            val cancel = MotionEvent.obtain(gesture.down).apply { action = MotionEvent.ACTION_CANCEL }
            try { gesture.source.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            clearSensorChildPresses(gesture.source)
        }
        gesture.down.recycle()
    }

    private fun findViewAt(x: Float, y: Float): View? {
        for (i in bottomPanel.childCount - 1 downTo 0) {
            val child = bottomPanel.getChildAt(i)
            if (child.isVisible &&
                x >= child.x && x <= child.x + child.width &&
                y >= child.y && y <= child.y + child.height
            ) return child
        }
        return null
    }

    private fun enterEditMode() {
        isEditMode = true
        // Пользователь нашёл редактирование: подсказка «как войти» больше не нужна.
        onboardingPrefs().edit { putBoolean(UserHints.PREF_EDIT_MODE_FOUND, true) }
        refreshBottomPanel()
        Toast.makeText(this, "Режим редактирования", Toast.LENGTH_SHORT).show()
    }

    private fun exitEditMode() {
        isEditMode = false
        refreshBottomPanel()
    }

    private fun openTileSettings(id: String) {
        openWithPinCheck {
            startActivityForResult(
                Intent(this, TileSettingsActivity::class.java).putExtra("tile_id", id),
                REQUEST_TILE_SETTINGS
            )
        }
    }

    // ==================== ОТРИСОВКА ВСЕГО ====================

    private fun setupBottomPanel() {
        refreshBottomPanel()
    }

    private fun refreshBottomPanel() {
        // Форматирование нового дерева должно учитывать уже изменённые настройки.
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        motionSensorId = prefs.getString("motion_sensor_id", null)
        screenTimeoutMinutes = prefs.getInt("screen_timeout_minutes", 5)
        tempUnit = prefs.getString("temp_unit", "C") ?: "C"
        clearPanelInteractions()
        bottomPanel.removeAllViews()

        val tiles = tileManager.getTilesByContainer("bottom_panel")

        // Сенсоры
        tileManager.getTilesByContainer("grid").forEach { tile ->
            val card = createSensorView(tile)
            placeTile(card, tile.x.toFloat(), tile.y.toFloat(), card.layoutParams.width, card.layoutParams.height)
            bottomPanel.addView(card)
        }

        // Кнопки
        tiles.forEach { tile ->
            val btn = createTileButton(tile)
            val sz = getButtonSize(tile)
            btn.layoutParams = FrameLayout.LayoutParams(sz, sz)
            placeTile(btn, tile.x.toFloat(), tile.y.toFloat(), sz, sz)
            bottomPanel.addView(btn)
        }

        // Кнопки управления (Настройки и +) только в режиме редактирования
        if (isEditMode) {
            val btnS = createSettingsButton().apply {
                layoutParams = FrameLayout.LayoutParams(DEFAULT_BUTTON_SIZE, DEFAULT_BUTTON_SIZE)
                x = (screenW - DEFAULT_BUTTON_SIZE - 20).toFloat()
                y = (screenH - DEFAULT_BUTTON_SIZE - 80).toFloat()
            }
            bottomPanel.addView(btnS)

            val btnA = createAddButton().apply {
                layoutParams = FrameLayout.LayoutParams(DEFAULT_BUTTON_SIZE, DEFAULT_BUTTON_SIZE)
                x = (screenW - DEFAULT_BUTTON_SIZE * 2 - 40).toFloat()
                y = (screenH - DEFAULT_BUTTON_SIZE - 80).toFloat()
            }
            bottomPanel.addView(btnA)
        }

        // Используем уже полученные значения, не ожидая следующего события или тика часов.
        updateSensorDisplay()
        updateClockViews()
        updatePanelHint()
    }

    private fun getButtonSize(tile: TileEntity): Int =
        TilePlacement.displaySize("bottom_panel", tile.config, screenW, screenH).width

    private fun placeTile(view: View, x: Float, y: Float, width: Int = view.width, height: Int = view.height) {
        val (safeX, safeY) = TilePlacement.position(x, y, width, height, screenW, screenH)
        view.x = safeX
        view.y = safeY
    }


// ==================== СЕНСОР (ВИДЖЕТ) ====================

    private fun createSensorView(tile: TileEntity): View {
        val (savedWidth, savedHeight) = TilePlacement.displaySize("grid", tile.config, screenW, screenH)

        val card = SensorCardFactory.createBase(this, tile.id, tile.type, savedWidth, savedHeight)
        if (tile.type == "battery") {
            // Устанавливаем заполнение из уже известных состояний, не дожидаясь нового события HA.
            updateBatteryWidget(tile, card)
        }
        val views = SensorCardFactory.addContent(
            this, card, tile.type, tile.title, tile.fontSize, savedHeight, isEditMode
        )
        val resizeHandle = views.resizeHandle

        // Перетаскивание и раскрытие
        setupSensorTouch(card, tile)

        resizeHandlers += SensorResizeHandler(
            card, resizeHandle, tile.type, screenW, screenH,
            onResizingChanged = { isResizing = it },
            saveSize = { width, height -> saveSensorSize(tile.id, width, height) }
        )

        return card
    }

    private fun saveSensorSize(tileId: String, width: Int, height: Int): Boolean {
        // Читаем актуальную плитку на UP, не сохраняя старую копию из начала жеста.
        val tiles = tileManager.loadTiles().toMutableList()
        val idx = tiles.indexOfFirst { it.id == tileId }
        if (idx < 0) return false
        val currentTile = tiles[idx]
        val config = try {
            JSONObject(currentTile.config)
        } catch (_: org.json.JSONException) {
            Toast.makeText(this, "Не удалось сохранить размер: повреждены настройки плитки", Toast.LENGTH_SHORT).show()
            return false
        }
        config.put("button_size", width)
        config.put("widget_height", height)
        tiles[idx] = currentTile.copy(config = config.toString())
        tileManager.saveTiles(tiles)
        return true
    }

    // ==================== РАСКРЫТИЕ СЕНСОРА ====================

    private fun expandSensor(tile: TileEntity, source: View) {
        val original = SensorExpansionSession.Bounds.of(source)

        val idsToShow = getExpandedSensorIds(tile)

        // Обновляем содержимое виджета (табличный вид)
        val container = source.findViewWithTag<LinearLayout>("sensor_text_container")
        if (container != null) {
            if (tile.type == "weather") {
                buildWeatherDetailedContent(tile, container)
            } else {
                buildSensorContent(tile, idsToShow, true, container)
            }
        }

        // Ограничиваем максимальные размеры (90% экрана)
        val maxW = (screenW * 0.9f).toInt()
        val maxH = (screenH * 0.8f).toInt()

        // Измеряем контент с ограничением
        container.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )
        
        val neededW = container.measuredWidth + 60
        val neededH = container.measuredHeight + 60

        // Вычисляем целевую позицию (стараемся центрировать относительно исходной точки)
        var targetX = original.x - (neededW - original.width) / 2f
        var targetY = original.y - (neededH - original.height) / 2f
        
        // Сохраняем прежние отступы от краёв экрана в px.
        if (targetX < 10f) targetX = 10f
        if (targetX + neededW > screenW - 10) targetX = (screenW - neededW - 10).toFloat()
        if (targetY < 10f) targetY = 10f
        if (targetY + neededH > screenH - 70) targetY = (screenH - neededH - 70).toFloat()

        sensorExpansion.expand(source,
            SensorExpansionSession.Bounds(neededW, neededH, targetX, targetY), ::collapseSensor)
    }

    // ==================== СВОРАЧИВАНИЕ СЕНСОРА ====================

    private fun resetSensorCollapseTimer() = sensorExpansion.touch()

    private fun collapseSensor() {
        val source = expandedSensorView ?: return
        cancelSensorWindowGesture()
        weatherForecastScroll.clear()
        val tile = tileManager.getAllTiles().find { it.id == source.tag as? String }
        if (tile == null) {
            clearSensorExpansion()
            return
        }
        val target = checkNotNull(sensorExpansion.original)
        val idsToShow = getCollapsedSensorIds(tile)

        val container = source.findViewWithTag<LinearLayout>("sensor_text_container")
        if (container != null) {
            if (tile.type == "weather") {
                // В начале сворачивания высота View ещё развёрнутая; шрифт нужен для исходной высоты.
                SensorCardFactory.buildCollapsedWeatherContent(this, container, target.height)
            } else {
                buildSensorContent(tile, idsToShow, false, container)
            }
        }

        sensorExpansion.collapse { view ->
            // Только живое дерево с актуальной плиткой снова получает ввод.
            if (view.parent === bottomPanel) {
                tileManager.getAllTiles().find { it.id == tile.id }?.let {
                    setupSensorTouch(view, it)
                }
            }
        }
        if (tile.type == "weather") {
            // Только после сброса режима раскрытия, иначе updateWeatherViews снова построит прогноз.
            updateWeatherViews()
        }
    }

    // ==================== ОБРАБОТЧИК КАСАНИЙ СЕНСОРА ====================

    private fun setupSensorTouch(card: View, tile: TileEntity) {
        val editDrag = bindEditDrag(card, tile.id)
        var startX = 0f
        var startY = 0f
        var hasMoved = false
        var gestureActive = false

        card.setOnTouchListener { view, event ->
            if (isResizing) return@setOnTouchListener true
            if (isEditMode) return@setOnTouchListener editDrag.onTouch(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    gestureActive = true
                    hasMoved = false
                    startX = event.rawX
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gestureActive) return@setOnTouchListener true
                    if (kotlin.math.abs(event.rawX - startX) > 10 ||
                        kotlin.math.abs(event.rawY - startY) > 10) hasMoved = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!gestureActive) return@setOnTouchListener true
                    gestureActive = false
                    view.performClick()
                    if (!hasMoved && tile.type != "clock") {
                        if (expandedSensorView != null && expandedSensorView != view) collapseSensor()
                        else expandSensor(tile, view)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    gestureActive = false
                    hasMoved = false
                    true
                }
                else -> false
            }
        }
    }

    // ==================== КНОПКИ ====================

    private fun createTileButton(tile: TileEntity): Button {
        val sz = getButtonSize(tile)
        val on = "#8033CC33".toColorInt()
        val off = "#424242".toColorInt()
        val initialColor = if (tile.type == "group") {
            if (TileButtonConfig.group(tile.config) is ButtonConfigResult.Invalid) off
            else if (getGroupState(tile.id)) on else off
        } else {
            val eid = buttonEntityId(tile)
            if (eid != null && singleStates[eid] == "on") on else off
        }

        return TileButtonFactory.create(this, tile.title, tile.id, sz, initialColor).apply {
            if (tile.type == "group") {
                setupGroupTouch(tile, this)
            } else {
                setupButtonTouch(tile, this, on, off)
            }
        }
    }

    private fun buttonEntityId(tile: TileEntity, reportError: Boolean = false): String? {
        return when (val result = TileButtonConfig.entityId(tile.config)) {
            is ButtonConfigResult.Valid -> result.value.also {
                if (it == null && reportError) {
                    Toast.makeText(this, "У кнопки не выбрано устройство.", Toast.LENGTH_SHORT).show()
                }
            }
            ButtonConfigResult.Invalid -> {
                if (reportError) showButtonConfigError()
                null
            }
        }
    }

    private fun groupForAction(tile: TileEntity): TileButtonConfig.Group? {
        return when (val result = TileButtonConfig.group(tile.config)) {
            is ButtonConfigResult.Valid -> {
                if (result.value.entityIds.isEmpty()) {
                    Toast.makeText(this, "В группе не выбраны устройства.", Toast.LENGTH_SHORT).show()
                    null
                } else result.value
            }
            ButtonConfigResult.Invalid -> {
                showButtonConfigError()
                null
            }
        }
    }

    private fun showButtonConfigError() {
        Toast.makeText(this, "Не удалось прочитать конфигурацию плитки. Откройте настройки.",
            Toast.LENGTH_SHORT).show()
    }

    private fun setupGroupTouch(tile: TileEntity, button: Button) {
        val editDrag = bindEditDrag(button, tile.id)
        var pressRunnable: Runnable? = null
        var startX = 0f
        var startY = 0f
        var hasMoved = false
        var gestureActive = false

        button.setOnTouchListener { view, event ->
            if (isEditMode) return@setOnTouchListener editDrag.onTouch(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pressRunnable?.let { handler.removeCallbacks(it) }
                    gestureActive = true
                    hasMoved = false
                    startX = event.rawX
                    startY = event.rawY
                    pressRunnable = Runnable {
                        if (!gestureActive) return@Runnable
                        if (expandedGroupId == tile.id) collapseChildButtons()
                        else expandChildButtons(tile, view as Button)
                    }.also { handler.postDelayed(it, 1000L) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gestureActive) return@setOnTouchListener true
                    if (kotlin.math.abs(event.rawX - startX) > 10 ||
                        kotlin.math.abs(event.rawY - startY) > 10) {
                        hasMoved = true
                        pressRunnable?.let { handler.removeCallbacks(it) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!gestureActive) return@setOnTouchListener true
                    gestureActive = false
                    view.performClick()
                    pressRunnable?.let { handler.removeCallbacks(it) }
                    pressRunnable = null
                    if (!hasMoved && expandedGroupId != tile.id) toggleGroup(tile.id)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressRunnable?.let { handler.removeCallbacks(it) }
                    pressRunnable = null
                    gestureActive = false
                    hasMoved = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtonTouch(
        tile: TileEntity,
        button: Button,
        activeColor: Int,
        inactiveColor: Int
    ) {
        button.setOnClickListener {
            if (!isEditMode) {
                val eid = buttonEntityId(tile, reportError = true)
                if (eid != null) {
                    val newState = viewModel.commands.toggleSingle(eid)
                    button.background?.setTint(if (newState == "on") activeColor else inactiveColor)
                }
            }
        }

        val editDrag = bindEditDrag(button, tile.id)
        button.setOnTouchListener { _, event ->
            if (isEditMode) editDrag.onTouch(event) else false
        }
    }

    // Повторное подключение после сворачивания заменяет обработчик той же View.
    private val editDragHandlers = mutableMapOf<View, TileEditDragHandler>()

    private fun bindEditDrag(view: View, tileId: String): TileEditDragHandler {
        editDragHandlers.remove(view)?.dispose()
        return TileEditDragHandler(view, handler, screenW, screenH,
            onTap = { openTileSettings(tileId) },
            onDrop = { x, y -> savePosition(tileId, x, y) }
        ).also { editDragHandlers[view] = it }
    }

    private fun savePosition(id: String, x: Int, y: Int) {
        val tiles = tileManager.loadTiles().toMutableList()
        val idx = tiles.indexOfFirst { it.id == id }
        if (idx >= 0) {
            tiles[idx] = tiles[idx].copy(x = x, y = y)
            tileManager.saveTiles(tiles)
        }
    }

    private fun createSettingsButton(): Button {
        return Button(this).apply {
            text = "⚙"
            alpha = 0.8f
            elevation = 8f
            background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null)
            background?.setTint("#424242".toColorInt())
            setTextColor("#FFFFFF".toColorInt())
            textSize = 28f
            gravity = Gravity.CENTER
            setOnClickListener {
                openWithPinCheck {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
            }
        }
    }

    private fun createAddButton(): Button {
        return Button(this).apply {
            text = "+"
            alpha = 0.8f
            elevation = 8f
            background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null)
            background?.setTint("#4CAF50".toColorInt())
            setTextColor("#FFFFFF".toColorInt())
            textSize = 32f
            gravity = Gravity.CENTER
            setOnClickListener {
                startActivityForResult(
                    Intent(this@MainActivity, TileSettingsActivity::class.java)
                        .putExtra("container", "grid"),
                    REQUEST_TILE_SETTINGS
                )
            }
        }
    }

    // ==================== ВЫЕЗЖАНИЕ ДОЧЕРНИХ ====================

    private fun expandChildButtons(tile: TileEntity, src: Button) {
        // Проверяем весь список до изменения интерфейса и создания обработчиков дочерних кнопок.
        val group = groupForAction(tile) ?: return
        val ids = group.entityIds
        collapseChildButtons()

        expandedGroupId = tile.id
        expandedSourceButton = src
        expandedTile = tile

        val bs = getButtonSize(tile)
        val loc = IntArray(2)
        src.getLocationOnScreen(loc)
        val cx = loc[0] + src.width / 2
        val top = loc[1]

        val cnt = ids.size
        val max = 5
        val fst = if (cnt <= max) cnt else (cnt + 1) / 2
        val snd = cnt - fst

        var s1 = cx - fst * (bs + 12) / 2
        var s2 = cx - snd * (bs + 12) / 2
        if (s1 < 0) s1 = 8
        if (s1 + fst * (bs + 12) > screenW) s1 = screenW - fst * (bs + 12) - 8
        if (s2 < 0) s2 = 8
        if (s2 + snd * (bs + 12) > screenW) s2 = screenW - snd * (bs + 12) - 8

        val rws = if (cnt <= max) 1 else 2
        val fy = top - (bs + 12) * rws - 12
        val sy = fy + bs + 12
        val afy = if (fy < 50) top + src.height + 12 else fy
        val asy = if (fy < 50) afy + bs + 12 else sy

        expandedChildButtons.clear()
        val rt = findViewById<FrameLayout>(android.R.id.content)

        for (i in 0 until cnt) {
            val eid = ids[i]
            val st = groupStates[tile.id]?.get(eid) ?: "off"
            val nm = group.childNames[i]

            val btn = Button(this).apply {
                text = nm
                tag = eid
                layoutParams = FrameLayout.LayoutParams(bs, bs)
                alpha = 0f; scaleX = 0.5f; scaleY = 0.5f; elevation = 12f
                background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null)
                background?.setTint(if (st == "on") "#8033CC33".toColorInt() else "#424242".toColorInt())
                setTextColor("#FFFFFF".toColorInt()); textSize = 14f
                gravity = Gravity.CENTER; isAllCaps = false
                setOnClickListener {
                    val n = viewModel.commands.toggleMember(tile.id, eid)
                    background?.setTint(if (n == "on") "#8033CC33".toColorInt() else "#424242".toColorInt())
                    updateGroupButtonAppearance(tile.id)
                    resetCollapseTimer()
                }
            }

            val r = if (i < fst) 0 else 1
            val c = if (r == 0) i else i - fst
            btn.x = (if (r == 0) s1 + c * (bs + 12) else s2 + c * (bs + 12)).toFloat()
            btn.y = (if (r == 0) afy else asy).toFloat()

            rt.addView(btn)
            expandedChildButtons.add(btn)

            btn.animate().alpha(0.8f).scaleX(1f).scaleY(1f)
                .setDuration(250).setStartDelay(i * 50L)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        dimOverlay.isVisible = true
        dimOverlay.animate().alpha(0.4f).setDuration(300).start()
        resetCollapseTimer()
    }

    private fun resetCollapseTimer() {
        collapseChildrenRunnable?.let { handler.removeCallbacks(it) }
        collapseChildrenRunnable = Runnable { collapseChildButtons() }
        handler.postDelayed(collapseChildrenRunnable!!, 10000L)
    }

    private fun collapseChildButtons() {
        expandedGroupId = null; expandedSourceButton = null; expandedTile = null
        val rt = findViewById<FrameLayout>(android.R.id.content)
        expandedChildButtons.forEachIndexed { i, b ->
            closingChildButtons.add(b)
            b.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f)
                .setDuration(200).setStartDelay(i * 30L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { rt.removeView(b); closingChildButtons.remove(b) }.start()
        }
        expandedChildButtons.clear()
        dimOverlay.animate().alpha(0f).setDuration(300)
            .withEndAction { dimOverlay.isVisible = false }.start()
        collapseChildrenRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun collapseAll() {
        collapseSensor()
        collapseChildButtons()
    }

    private fun clearSensorExpansion() {
        cancelSensorWindowGesture()
        weatherForecastScroll.clear()
        sensorExpansion.clear()
    }

    private fun clearPanelInteractions() {
        // Этот Handler принадлежит только Activity и обслуживает жесты старой панели.
        handler.removeCallbacksAndMessages(null)
        longPressRunnable = null
        collapseChildrenRunnable = null
        clearSensorExpansion()
        resizeHandlers.forEach { it.dispose() }
        resizeHandlers.clear()
        editDragHandlers.values.forEach { it.dispose() }
        editDragHandlers.clear()
        isResizing = false

        // Список раскрытых кнопок уже пуст при анимированном закрытии, поэтому
        // отдельно учитываем кнопки, которые ещё ожидают удаления из overlay.
        (expandedChildButtons + closingChildButtons).distinct().forEach { button ->
            button.animate().withEndAction(null).setListener(null).cancel()
            button.cancelPendingInputEvents()
            button.setOnClickListener(null)
            (button.parent as? ViewGroup)?.removeView(button)
        }
        expandedChildButtons.clear()
        closingChildButtons.clear()
        expandedGroupId = null
        expandedSourceButton = null
        expandedTile = null
        if (::dimOverlay.isInitialized) {
            dimOverlay.animate().withEndAction(null).setListener(null).cancel()
            dimOverlay.alpha = 0f
            dimOverlay.isVisible = false
        }
        if (::bottomPanel.isInitialized) {
            for (i in 0 until bottomPanel.childCount) {
                val child = bottomPanel.getChildAt(i)
                child.animate().withEndAction(null).setListener(null).cancel()
                child.cancelPendingInputEvents()
                child.setOnTouchListener(null)
                child.setOnClickListener(null)
            }
        }
    }

    // ==================== ГРУППЫ ====================

    private fun updateGroupState(gid: String, eid: String, st: String) {
        viewModel.updateGroupState(gid, eid, st)
        updateGroupButtonAppearance(gid)
    }

    private fun getGroupState(gid: String): Boolean {
        val tile = tileManager.getAllTiles().find { it.id == gid && it.type == "group" } ?: return false
        val result = TileButtonConfig.group(tile.config)
        if (result !is ButtonConfigResult.Valid) return false
        return viewModel.groupIsOn(gid, result.value.entityIds)
    }

    private fun updateGroupButtonAppearance(gid: String) {
        val c = if (getGroupState(gid)) "#8033CC33".toColorInt() else "#424242".toColorInt()
        for (i in 0 until bottomPanel.childCount) {
            val ch = bottomPanel.getChildAt(i)
            if (ch is Button && ch.tag == gid) { ch.background?.setTint(c); return }
        }
    }

    private fun toggleGroup(gid: String) {
        // Ни одна команда не отправляется до успешной проверки текущего состава.
        val tile = tileManager.getAllTiles().find { it.id == gid && it.type == "group" } ?: return
        val group = groupForAction(tile) ?: return
        viewModel.commands.toggleGroup(gid, group.entityIds)
    }

    // ==================== ОБЫЧНЫЕ КНОПКИ ====================

    private fun updateSingleButtonColor(eid: String) {
        val c = if (singleStates[eid] == "on") "#8033CC33".toColorInt() else "#424242".toColorInt()
        for (i in 0 until bottomPanel.childCount) {
            val ch = bottomPanel.getChildAt(i)
            if (ch is Button) {
                val tile = (ch.tag as? String)?.let { tileManager.getAllTiles().find { t -> t.id == it } }
                if (tile != null && tile.type != "group") {
                    if (buttonEntityId(tile) == eid) {
                        ch.background?.setTint(c)
                    }
                }
            }
        }
    }

    // ==================== ПЕРВЫЙ ЗАПУСК ====================

    private fun onboardingPrefs() = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)

    /**
     * На чистой установке создаём часы и погоду: им не нужны личные датчики.
     * Только один раз: удалённые пользователем плитки не возвращаются. Уже настроенная
     * панель прежней версии не меняется, а подсказки для новичка ей не показываются.
     */
    private fun createStarterTilesOnFirstLaunch() {
        val prefs = onboardingPrefs()
        if (prefs.getBoolean(PREF_STARTER_TILES_DONE, false)) return
        if (tileManager.loadTiles().isEmpty()) {
            val tiles = mutableListOf<TileEntity>()
            for ((type, title) in listOf("clock" to "Часы", "weather" to "Погода")) {
                val (x, y) = TilePlacement.findFreeSpot(
                    tiles, TilePlacement.WIDGET_WIDTH, TilePlacement.WIDGET_HEIGHT, screenW, screenH
                )
                tiles += TileEntity(UUID.randomUUID().toString(), type, "grid", title, x, y, 1, 1)
            }
            tileManager.saveTiles(tiles)
        } else {
            prefs.edit {
                putBoolean(PREF_WELCOME_SHOWN, true)
                putBoolean(UserHints.PREF_EDIT_MODE_FOUND, true)
            }
        }
        prefs.edit { putBoolean(PREF_STARTER_TILES_DONE, true) }
    }

    private fun showWelcomeOnFirstLaunch() {
        val prefs = onboardingPrefs()
        if (prefs.getBoolean(PREF_WELCOME_SHOWN, false)) return
        prefs.edit { putBoolean(PREF_WELCOME_SHOWN, true) }
        UserHints.showWelcome(this)
    }

    private fun setupPanelHint(container: View, text: TextView, close: View) {
        panelHint = container
        panelHintText = text
        close.setOnClickListener {
            val shown = shownPanelHint ?: return@setOnClickListener
            UserHints.hideWithUndo(container, shown, above = topBottomRowButton(), refresh = ::updatePanelHint)
        }
    }

    private fun updatePanelHint() {
        val container = panelHint ?: return
        val hint = when {
            isEditMode -> UserHints.Hint.EDIT_MODE
            !onboardingPrefs().getBoolean(UserHints.PREF_EDIT_MODE_FOUND, false) -> UserHints.Hint.HOW_TO_EDIT
            else -> null
        }?.takeUnless { UserHints.isHidden(this, it) }
        shownPanelHint = hint
        panelHintText?.text = when (hint) {
            UserHints.Hint.EDIT_MODE -> UserHints.EDIT_MODE
            UserHints.Hint.HOW_TO_EDIT -> UserHints.HOW_TO_EDIT
            else -> ""
        }
        container.isVisible = hint != null
        if (hint != null) placePanelHintAboveButtons(container)
    }

    private fun placePanelHintAboveButtons(container: View) {
        val params = container.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = resources.displayMetrics.density
        // До первого layout высоты панели ещё нет: берём экран, после layout пересчитаем.
        val margin = TilePlacement.hintBottomMargin(
            panelButtons().map { it.y to buttonHeight(it) }, panelHeight(),
            gap = (8 * density).toInt(), minMargin = (12 * density).toInt()
        )
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin
            container.layoutParams = params
        }
    }

    // До первого layout высоты панели ещё нет: берём экран, после layout пересчитаем.
    private fun panelHeight() = bottomPanel.height.takeIf { it > 0 } ?: screenH

    private fun buttonHeight(view: View) = view.layoutParams?.height?.takeIf { it > 0 } ?: view.height

    private fun panelButtons() = (0 until bottomPanel.childCount).map { bottomPanel.getChildAt(it) }
        .filter { it is Button && it.isVisible }

    /** Самая высокая кнопка нижнего ряда: над ней показываем Snackbar. */
    private fun topBottomRowButton(): View? {
        val half = panelHeight() / 2f
        return panelButtons().filter { it.y + buttonHeight(it) / 2f > half }.minByOrNull { it.y }
    }

    // ==================== ДАННЫЕ С ДАТЧИКОВ ====================

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClockViews()
            clockHandler.postDelayed(this, 10000L)
        }
    }

    private fun updateClockViews() {
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
        for (i in 0 until bottomPanel.childCount) {
            val v = bottomPanel.getChildAt(i)
            v.findViewWithTag<TextView>("clock_text")?.text = time
            v.findViewWithTag<TextView>("clock_date")?.text = date
        }
        // Тик часов не приносит новых данных подробного прогноза.
        updateWeatherViews(refreshExpanded = false)
    }

    private fun updateWeatherViews() {
        updateWeatherViews(refreshExpanded = true)
    }

    private fun updateWeatherViews(refreshExpanded: Boolean) {
        val useYandex = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getBoolean("use_yandex_weather", false)

        for (i in 0 until bottomPanel.childCount) {
            val v = bottomPanel.getChildAt(i)
            val tag = v.tag as? String ?: continue
            val tile = tileManager.getAllTiles().find { it.id == tag } ?: continue
            if (tile.type == "weather") {
                val container = v.findViewWithTag<LinearLayout>("sensor_text_container")
                if (container != null && expandedSensorView == v) {
                    if (refreshExpanded) buildWeatherDetailedContent(tile, container)
                    continue
                }

                if (useYandex) {
                    CompactWeatherRenderer.renderYandex(v, yandexWeatherManager.currentTemp,
                        yandexWeatherManager.getWeatherIcon(yandexWeatherManager.currentState),
                        yandexWeatherManager.translateCondition(yandexWeatherManager.currentState))
                } else {
                    CompactWeatherRenderer.renderHomeAssistant(v, tile, singleStates::get, entityAttributes::get)
                }
            }
        }
    }

    private fun buildWeatherDetailedContent(tile: TileEntity, container: LinearLayout) {
        val useYandex = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getBoolean("use_yandex_weather", false)
        val source = if (useYandex) "yandex" else "ha:${WeatherEntityResolver.resolve(tile.config)}"
        weatherForecastScroll.rebuild(container, tile.id, source) {
            if (useYandex) {
                buildYandexWeatherDetailedContent(tile, container)
            } else {
                HomeAssistantWeatherDetailsRenderer.render(
                    this,
                    tile,
                    container,
                    entityAttributes::get,
                    singleStates::get,
                    ::resetSensorCollapseTimer
                )
            }
        }
    }

    private fun buildYandexWeatherDetailedContent(tile: TileEntity, container: LinearLayout) {
        YandexWeatherDetailsRenderer.render(
            this,
            container,
            yandexWeatherManager,
            ::resetSensorCollapseTimer
        )
    }

    private fun updateSensorDisplay() {
        val sensorTiles = tileManager.getTilesByContainer("grid")
        for (i in 0 until bottomPanel.childCount) {
            val child = bottomPanel.getChildAt(i)
            val tag = child.tag as? String ?: continue
            val tile = sensorTiles.find { it.id == tag } ?: continue

            if (child is FrameLayout) {
                val isExpanded = (expandedSensorView == child)

                val idsToShow = if (isExpanded) {
                    getExpandedSensorIds(tile)
                } else {
                    getCollapsedSensorIds(tile)
                }

                // Погодное дерево обновляется отдельно, обычные строки не должны его перестраивать.
                val container = child.findViewWithTag<LinearLayout>("sensor_text_container")
                if (container != null && tile.type != "clock" && tile.type != "weather") {
                    buildSensorContent(tile, idsToShow, isExpanded, container)
                }

                if (tile.type == "clock") {
                    updateClockViews()
                } else if (tile.type == "battery") {
                    updateBatteryWidget(tile, child)
                } else {
                    updateNormalSensorWidget(tile, child)
                }
            }
        }
    }

    private fun updateBatteryWidget(tile: TileEntity, view: FrameLayout) {
        BatteryWidgetRenderer.render(tile, view, singleStates::get)
    }

    private fun updateNormalSensorWidget(tile: TileEntity, child: FrameLayout) {
        SensorBackgroundRenderer.render(tile, child, singleStates::get)
    }

    /**
     * Возвращает список entity_id для свёрнутого режима.
     * Если ничего не выбрано — возвращает стандартный набор.
     */
    private fun getCollapsedSensorIds(tile: TileEntity): List<String> {
        return SensorConfigResolver.getCollapsedSensorIds(tile)
    }

    /**
     * Возвращает список entity_id для развёрнутого режима.
     */
    private fun getExpandedSensorIds(tile: TileEntity): List<String> {
        return SensorConfigResolver.getExpandedSensorIds(tile)
    }

    /**
     * Собирает текст виджета из заголовка и значений датчиков
     * с учётом пользовательских названий и точности.
     */
    /**
     * Строит содержимое виджета: заголовок + строки параметров.
     * Название параметра слева, значение справа, выровнены по самой длинной строке.
     */
    private fun buildSensorContent(tile: TileEntity, entityIds: List<String>, isExpanded: Boolean, container: LinearLayout) {
        SensorContentRenderer.render(
            tile,
            entityIds,
            isExpanded,
            container,
            ::loadSensorConfigs,
            singleStates::get,
            ::formatSensorValue
        )
    }

    /**
     * Загружает конфигурации датчиков из TileEntity.
     */
    private fun loadSensorConfigs(tile: TileEntity, isExpanded: Boolean): List<SensorConfig> {
        return SensorConfigResolver.loadSensorConfigs(tile, isExpanded)
    }

    /**
     * Форматирует значение датчика с заданной точностью и конвертирует температуру, если нужно.
     */
    private fun formatSensorValue(value: String, decimals: Int, entityId: String? = null): String {
        val unit = if (entityId != null) {
            entityAttributes[entityId]?.optString("unit_of_measurement") ?: ""
        } else {
            ""
        }
        return SensorValueFormatter.formatSensorValue(value, decimals, unit, tempUnit)
    }

    private var panelEntityTiles: List<TileEntity>? = null
    private var panelEntityMotionSensor: String? = null
    private var panelEntityIds: Set<String> = emptySet()

    private fun isShownOnPanel(eid: String): Boolean {
        // TileManager возвращает тот же список, пока плитки не менялись.
        val tiles = tileManager.getAllTiles()
        if (tiles !== panelEntityTiles || motionSensorId != panelEntityMotionSensor) {
            panelEntityIds = PanelEntityIds.collect(tiles, listOfNotNull(motionSensorId))
            panelEntityTiles = tiles
            panelEntityMotionSensor = motionSensorId
        }
        return eid in panelEntityIds
    }

    private fun updateTilesForEntity(eid: String, st: String, attrs: JSONObject? = null) {
        viewModel.onEntityStateChanged(eid, st, attrs)
        renderEntityState(eid, st)
    }

    private fun renderEntityState(eid: String, st: String) {

        // Состояние храним для всех сущностей: плитка, добавленная позже, сразу покажет значение.
        // А перерисовка нужна только для тех, которые читает панель; HA шлёт события всего дома.
        if (!isShownOnPanel(eid)) return

        // Полное обновление погоды выполняется один раз ниже через updateWeatherViews.

        if (eid == motionSensorId) {
            if (st == "on" || st == "true" || st == "playing") {
                wakeScreen()
            }
        }

        // Цвет определяется фактическим источником кнопки независимо от домена HA.
        updateSingleButtonColor(eid)
        if (!eid.startsWith("weather.")) {
            // На плитке может быть любая сущность (binary_sensor, input_number, climate),
            // а не только sensor.*: посторонние события уже отсеяны выше.
            updateSensorDisplay()
        }
        updateWeatherViews()

        tileManager.getAllTiles().filter { it.type == "group" }.forEach { t ->
            val result = TileButtonConfig.group(t.config)
            if (result is ButtonConfigResult.Valid && eid in result.value.entityIds) {
                updateGroupState(t.id, eid, st); updateGroupButtonAppearance(t.id)
            }
        }
    }

    private fun updateForecastForEntity(eid: String, forecast: JSONArray?) {
        viewModel.onForecastChanged(eid, forecast)
        renderForecastState(eid)
    }

    private fun renderForecastState(eid: String) {
        updateWeatherViews()
    }

    private fun formatFloat(v: String, d: Int): String {
        return SensorValueFormatter.formatFloat(v, d)
    }

    // ==================== WEBSOCKET ====================

    private var haConfiguration: List<String>? = null
    private var haGeneration = 0

    // Единственное применение статуса к экрану; обновления приходят из StateFlow только между start/stop.
    private fun setHaStatus(status: String) {
        findViewById<TextView>(R.id.tvHaLiveStatus).text = status
        getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit {
            putString("ha_connection_status", status)
        }
    }

    private var haTokens: HaTokenAccess? = null
    private var haTokenRequest = 0
    private var connectedTokenRevision = -1L
    private fun tokens() = haTokens ?: HaTokenAccess.create(this).also { haTokens = it }

    private fun setupWebSocket() {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val local = prefs.getString("ha_local_host", "").orEmpty().trim()
        val remote = prefs.getString("ha_remote_host", "").orEmpty().trim()
        val request = ++haTokenRequest
        if (connectedTokenRevision == -1L && viewModel.getWebSocket() != null) {
            connectedTokenRevision = viewModel.tokenRevision
        }
        if (connectedTokenRevision != HaTokenAccess.revision) {
            haGeneration++
            viewModel.disconnectWebSocket()
            haConfiguration = null
        }
        if (webSocket == null) viewModel.publishStatus("Чтение токена HA...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { tokens().read() }
            if (request != haTokenRequest || isFinishing || isDestroyed || !HaTokenAccess.isCurrent(result)) return@launch
            if (prefs.getString("ha_local_host", "").orEmpty().trim() != local ||
                prefs.getString("ha_remote_host", "").orEmpty().trim() != remote) return@launch
            if (result.status != HaTokenAccess.Status.READY) {
                haGeneration++
                viewModel.disconnectWebSocket()
                haConfiguration = null
                viewModel.publishStatus(result.message)
                return@launch
            }
            connectWithHaToken(local, remote, result.value.orEmpty().trim())
        }
    }

    private fun connectWithHaToken(local: String, remote: String, token: String) {
        connectedTokenRevision = HaTokenAccess.revision
        val tokenRevision = connectedTokenRevision
        viewModel.tokenRevision = tokenRevision
        val configuration = listOf(local, remote, token)
        if (haConfiguration == configuration && webSocket != null) {
            // connect() restarts a stopped client and leaves active/backoff connections alone.
            viewModel.resumeWebSocket()
            return
        }
        haConfiguration = configuration
        val generation = ++haGeneration
        val hosts = listOf(local, remote).filter { it.isNotEmpty() }.distinct()
        if (token.isEmpty() || hosts.isEmpty()) {
            viewModel.disconnectWebSocket()
            viewModel.publishStatus("Ошибка: укажите адрес и токен Home Assistant")
            return
        }
        viewModel.connectWebSocket(
            host = hosts.first(),
            token = token,
            alternateHosts = hosts.drop(1),
            onStateChangedCallback = { entity, state, _ ->
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) renderEntityState(entity, state)
            },
            onForecastChangedCallback = { entity, _ ->
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) renderForecastState(entity)
            },
            onConnectedCallback = {
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) {
                    val active = viewModel.getWebSocket()?.getHost().orEmpty()
                    val network = if (local.isNotEmpty() && active == local) "Локальная сеть" else "Интернет"
                    viewModel.publishStatus("$network ($active)")
                    Toast.makeText(this, "Подключено: $network", Toast.LENGTH_SHORT).show()
                    subscribeToNeededEntities()
                    viewModel.getEntities()
                }
            },
            onEntitiesListCallback = {
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) cacheEntities(it)
            }
        )
    }

    private fun cacheEntities(e: List<HaEntity>) {
        getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit {
            putString("cached_entities", com.google.gson.Gson().toJson(e))
            putLong("cached_entities_time", System.currentTimeMillis())
        }
    }

    private fun subscribeToNeededEntities() {
        val useYandex = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getBoolean("use_yandex_weather", false)
        val plan = PanelSubscriptionPlan.create(tileManager.getAllTiles(), motionSensorId, useYandex)
        viewModel.subscribeEntities(plan.entityIds)
        // Передаём весь актуальный набор, включая пустой при выборе Яндекса.
        viewModel.syncForecastSubscriptions(plan.forecastIds)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TILE_SETTINGS && resultCode == RESULT_OK) {
            refreshBottomPanel()
            subscribeToNeededEntities()
        }
    }

    // ==================== ЭНЕРГОСБЕРЕЖЕНИЕ ====================

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        if (isDimmed) {
            wakeScreen()
        }
        // Запускаем таймер только если датчик движения выбран в настройках
        if (!motionSensorId.isNullOrEmpty()) {
            idleHandler.postDelayed(idleRunnable, screenTimeoutMinutes * 60 * 1000L)
        }
    }

    private fun dimScreen() {
        if (isDimmed) return
        isDimmed = true
        val params = window.attributes
        params.screenBrightness = 0.01f // Минимальная яркость
        window.attributes = params
    }

    private fun wakeScreen() {
        isDimmed = false
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE // Системное значение
        window.attributes = params
        resetIdleTimer()
    }
}
