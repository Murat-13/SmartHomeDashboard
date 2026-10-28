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
import android.animation.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import android.util.Log
import android.util.TypedValue
import android.graphics.Typeface

class MainActivity : AppCompatActivity() {

    // ==================== UI ====================
    private lateinit var bottomPanel: FrameLayout
    private lateinit var overlayContainer: FrameLayout
    private lateinit var dimOverlay: View

    // ==================== ДАННЫЕ ====================
    private lateinit var tileManager: TileManager
    private lateinit var yandexWeatherManager: YandexWeatherManager
    private var mqttRemovalMigration: MqttRemovalMigration? = null
    private var webSocket: HomeAssistantWebSocket? = null

    private var pzemVoltage = "—"
    private var pzemCurrent = "—"
    private var pzemPower = "—"
    private var pzemEnergy = "—"
    private var pzemFrequency = "—"
    private var gridOnline = true
    private var techRoomTemp = "—"

    private var tempUnit = "C"
    private var motionSensorId: String? = null
    private var screenTimeoutMinutes = 5
    private var isDimmed = false
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { dimScreen() }

    // ==================== СОСТОЯНИЯ ====================
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var collapseChildrenRunnable: Runnable? = null

    private val groupStates = mutableMapOf<String, MutableMap<String, String>>()
    private val singleStates = mutableMapOf<String, String>()
    private val entityAttributes = mutableMapOf<String, JSONObject>()
    // Наличие ключа означает отдельное forecast-обновление; null — прогноз недоступен.
    private val syntheticForecasts = mutableMapOf<String, JSONArray?>()
    private var isEditMode = false

    private val expandedChildButtons = mutableListOf<Button>()
    private val closingChildButtons = mutableSetOf<Button>()
    private var expandedGroupId: String? = null
    private var expandedSourceButton: Button? = null
    private var expandedTile: TileEntity? = null

    private var expandedSensorView: View? = null
    private var expandedSensorSource: View? = null
    private var sensorCollapseRunnable: Runnable? = null
    private val sensorAnimations = mutableMapOf<View, ValueAnimator>()
    private val weatherForecastScroll = WeatherForecastScrollState()
    private var savedOriginalWidth = 0
    private var savedOriginalHeight = 0
    private var savedOriginalX = 0f
    private var savedOriginalY = 0f
    private var isResizing = false
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
        private const val DEFAULT_WIDGET_W = 220
        private const val DEFAULT_WIDGET_H = 180
        private const val DEFAULT_BUTTON_SIZE = 160
    }

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupPanelWindow()
        setContentView(R.layout.activity_main)
        bottomPanel = findViewById(R.id.bottomPanel)
        overlayContainer = findViewById(R.id.overlayContainer)
        dimOverlay = findViewById(R.id.dimOverlay)
        dimOverlay.setOnClickListener { collapseAll() }

        tileManager = TileManager(this)
        yandexWeatherManager = YandexWeatherManager(this)
        yandexWeatherManager.loadFromCache()

        // Создаём плитки по умолчанию, если их нет
        if (tileManager.getAllTiles().isEmpty()) {
            createDefaultGridTiles()
            createDefaultButtonTiles()
        }

        setupBottomPanel()
        setupWebSocket()
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
        
        // Bridge выполняет только локальный IO. Сетевой запрос по-прежнему разрешён лишь выбранному Яндексу.
        yandexWeatherManager.fetchWeather(lifecycleScope, object : YandexWeatherManager.WeatherCallback {
                override fun onWeatherUpdated() {
                    runOnUiThread { updateWeatherViews() }
                }
                override fun onError(error: String) {
                    Log.e("MainActivity", "Yandex Weather Error: $error")
                }
            })
        
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
        if (::yandexWeatherManager.isInitialized) yandexWeatherManager.cancelPendingRequests()
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
        if (::yandexWeatherManager.isInitialized) yandexWeatherManager.cancelPendingRequests()
        super.onDestroy()
        clearPanelInteractions()
        idleHandler.removeCallbacks(idleRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        haTokenRequest++
        haGeneration++
        webSocket?.disconnect()
        webSocket = null
    }

    // ==================== ПОИСК СВОБОДНОГО МЕСТА ====================

    private fun findFreeSpot(width: Int, height: Int): Pair<Int, Int> {
        val step = 20
        var y = 40
        while (y + height < screenH - 100) {
            var x = 20
            while (x + width < screenW - 20) {
                if (isSpotFree(x, y, width, height)) return Pair(x, y)
                x += step
            }
            y += step
        }
        return Pair(20, screenH - height - 80)
    }

    private fun isSpotFree(x: Int, y: Int, width: Int, height: Int): Boolean {
        for (i in 0 until bottomPanel.childCount) {
            val child = bottomPanel.getChildAt(i)
            if (child == dimOverlay) continue
            if (child.isVisible &&
                x < child.x + child.width && x + width > child.x &&
                y < child.y + child.height && y + height > child.y
            ) return false
        }
        return true
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

        var tiles = tileManager.getTilesByContainer("bottom_panel")
        if (tiles.isEmpty()) {
            createDefaultButtonTiles()
            tiles = tileManager.getTilesByContainer("bottom_panel")
        }

        // Сенсоры
        tileManager.getTilesByContainer("grid").forEach { tile ->
            val card = createSensorView(tile)
            card.x = tile.x.toFloat().coerceIn(0f, (screenW - DEFAULT_WIDGET_W).toFloat())
            card.y = tile.y.toFloat().coerceIn(0f, (screenH - DEFAULT_WIDGET_H - 60).toFloat())
            bottomPanel.addView(card)
        }

        // Кнопки
        tiles.forEach { tile ->
            val btn = createTileButton(tile)
            val sz = getButtonSize(tile)
            btn.layoutParams = FrameLayout.LayoutParams(sz, sz)
            btn.x = tile.x.toFloat().coerceIn(0f, (screenW - sz).toFloat())
            btn.y = tile.y.toFloat().coerceIn(0f, (screenH - sz - 60).toFloat())
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
    }

    private fun getButtonSize(tile: TileEntity): Int {
        return try {
            JSONObject(tile.config).optInt("button_size", DEFAULT_BUTTON_SIZE)
        } catch (_: Exception) {
            DEFAULT_BUTTON_SIZE
        }
    }


// ==================== СЕНСОР (ВИДЖЕТ) ====================

    private fun createSensorView(tile: TileEntity): View {
        val savedWidth = try {
            JSONObject(tile.config).optInt("button_size", DEFAULT_WIDGET_W)
        } catch (_: Exception) { DEFAULT_WIDGET_W }
        val savedHeight = try {
            JSONObject(tile.config).optInt("widget_height", (DEFAULT_WIDGET_W * 0.82).toInt())
        } catch (_: Exception) { (DEFAULT_WIDGET_W * 0.82).toInt() }

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

        // Снимок берём для каждого жеста: предыдущий resize мог уже изменить размеры.
        var resizeStartX = 0f
        var resizeStartY = 0f
        var originalWidth = savedWidth
        var originalHeight = savedHeight
        var originalX = 0f
        var originalY = 0f
        var resizeActive = false
        val textScales = when (tile.type) {
            "clock" -> listOf("clock_text" to 0.4f, "clock_date" to 0.15f)
            "weather" -> listOf("weather_icon" to 0.35f, "weather_temp" to 0.25f, "weather_desc" to 0.12f)
            else -> emptyList()
        }
        var originalTextSizes = emptyList<Pair<TextView, Float>>()

        fun applyResize(width: Int, height: Int) {
            card.layoutParams.width = width
            card.layoutParams.height = height
            textScales.forEach { (tag, scale) ->
                card.findViewWithTag<TextView>(tag)?.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * scale)
            }
            // Существующий layout-listener батареи пересчитает заполнение по новой ширине.
            card.requestLayout()
        }

        fun restoreResize() {
            applyResize(originalWidth, originalHeight)
            card.x = originalX
            card.y = originalY
            originalTextSizes.forEach { (view, size) ->
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            }
        }

        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isResizing = true
                    resizeActive = true
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    originalWidth = card.layoutParams.width
                    originalHeight = card.layoutParams.height
                    originalX = card.x
                    originalY = card.y
                    originalTextSizes = textScales.mapNotNull { (tag, _) ->
                        card.findViewWithTag<TextView>(tag)?.let { it to it.textSize }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!resizeActive) return@setOnTouchListener true
                    // У края места может быть меньше минимума. Сохраняем минимум 120 px,
                    // не передавая в coerceIn пустой диапазон и не перемещая карточку.
                    val maxW = (screenW - card.x.toInt() - 20).coerceAtLeast(120)
                    val maxH = (screenH - card.y.toInt() - 80).coerceAtLeast(120)
                    // layout может ещё не выполниться после предыдущего MOVE.
                    val newW = (card.layoutParams.width + event.rawX - resizeStartX).toInt().coerceIn(120, maxW)
                    val newH = (card.layoutParams.height + event.rawY - resizeStartY).toInt().coerceIn(120, maxH)
                    applyResize(newW, newH)
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isResizing = false
                    if (!resizeActive) return@setOnTouchListener true
                    resizeActive = false
                    // Меняем только размеры актуальной плитки, сохраняя остальные поля.
                    val tiles = tileManager.loadTiles().toMutableList()
                    val idx = tiles.indexOfFirst { it.id == tile.id }
                    if (idx < 0) {
                        restoreResize()
                        originalTextSizes = emptyList()
                        return@setOnTouchListener true
                    }
                    val currentTile = tiles[idx]
                    val config = try {
                        JSONObject(currentTile.config)
                    } catch (_: org.json.JSONException) {
                        restoreResize()
                        originalTextSizes = emptyList()
                        Toast.makeText(this, "Не удалось сохранить размер: повреждены настройки плитки", Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    config.put("button_size", card.layoutParams.width)
                    config.put("widget_height", card.layoutParams.height)
                    tiles[idx] = currentTile.copy(config = config.toString())
                    tileManager.saveTiles(tiles)
                    originalTextSizes = emptyList()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isResizing = false
                    if (resizeActive) restoreResize()
                    resizeActive = false
                    originalTextSizes = emptyList()
                    true
                }
                else -> false
            }
        }

        return card
    }

    // ==================== РАСКРЫТИЕ СЕНСОРА ====================

    private fun expandSensor(tile: TileEntity, source: View) {
        // Сохраняем исходные размеры и позицию
        savedOriginalWidth = source.layoutParams.width
        savedOriginalHeight = source.layoutParams.height
        savedOriginalX = source.x
        savedOriginalY = source.y

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
        var targetX = savedOriginalX - (neededW - savedOriginalWidth) / 2f
        var targetY = savedOriginalY - (neededH - savedOriginalHeight) / 2f
        
        // Корректируем, чтобы не выходило за края экрана (отступ 10dp)
        if (targetX < 10f) targetX = 10f
        if (targetX + neededW > screenW - 10) targetX = (screenW - neededW - 10).toFloat()
        if (targetY < 10f) targetY = 10f
        if (targetY + neededH > screenH - 70) targetY = (screenH - neededH - 70).toFloat()

        // Поднимаем виджет на передний план
        source.bringToFront()
        source.elevation = 20f

        // Анимируем размер и позицию одновременно
        val startW = source.layoutParams.width
        val startH = source.layoutParams.height
        val startX = source.x
        val startY = source.y

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                source.layoutParams.width = (startW + (neededW - startW) * fraction).toInt()
                source.layoutParams.height = (startH + (neededH - startH) * fraction).toInt()
                source.x = startX + (targetX - startX) * fraction
                source.y = startY + (targetY - startY) * fraction
                source.requestLayout()
            }
        }
        startSensorAnimation(source, animator)

        // Сохраняем ссылку для сворачивания
        expandedSensorView = source
        expandedSensorSource = source

        // Закрывающий тап распознаётся только диспетчером окна; здесь остаётся прокрутка детей.
        source.setOnTouchListener(null)

        // Автосворачивание через 30 секунд
        sensorCollapseRunnable = Runnable { collapseSensor() }
        handler.postDelayed(sensorCollapseRunnable!!, 30000L)
    }

    // ==================== СВОРАЧИВАНИЕ СЕНСОРА ====================

    private fun resetSensorCollapseTimer() {
        sensorCollapseRunnable?.let {
            handler.removeCallbacks(it)
            handler.postDelayed(it, 30000L)
        }
    }

    private fun collapseSensor() {
        val source = expandedSensorView ?: return
        cancelSensorWindowGesture()
        weatherForecastScroll.clear()
        val tile = tileManager.getAllTiles().find { it.id == source.tag as? String }
        if (tile == null) {
            clearSensorExpansion()
            return
        }
        // Следующее раскрытие может заменить общие поля до конца этих 250 ms.
        val targetWidth = savedOriginalWidth
        val targetHeight = savedOriginalHeight
        val targetX = savedOriginalX
        val targetY = savedOriginalY
        val idsToShow = getCollapsedSensorIds(tile)

        val container = source.findViewWithTag<LinearLayout>("sensor_text_container")
        if (container != null) {
            if (tile.type == "weather") {
                // В начале сворачивания высота View ещё развёрнутая; шрифт нужен для исходной высоты.
                SensorCardFactory.buildCollapsedWeatherContent(this, container, targetHeight)
            } else {
                buildSensorContent(tile, idsToShow, false, container)
            }
        }

        // Возвращаем elevation на исходный уровень
        source.elevation = 8f

        // Анимируем сжатие до исходного размера и позиции
        val startW = source.layoutParams.width
        val startH = source.layoutParams.height
        val startX = source.x
        val startY = source.y

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                source.layoutParams.width = (startW + (targetWidth - startW) * fraction).toInt()
                source.layoutParams.height = (startH + (targetHeight - startH) * fraction).toInt()
                source.x = startX + (targetX - startX) * fraction
                source.y = startY + (targetY - startY) * fraction
                source.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Только актуальный переход вправе подключить ввод живой карточки.
                    if (sensorAnimations[source] === animation && source.parent === bottomPanel) {
                        tileManager.getAllTiles().find { it.id == tile.id }?.let {
                            setupSensorTouch(source, it)
                        }
                    }
                }
            })
        }
        startSensorAnimation(source, animator)

        // Сбрасываем состояние
        expandedSensorView = null
        expandedSensorSource = null
        sensorCollapseRunnable?.let { handler.removeCallbacks(it) }
        sensorCollapseRunnable = null
        if (tile.type == "weather") {
            // Только после сброса режима раскрытия, иначе updateWeatherViews снова построит прогноз.
            updateWeatherViews()
        }
    }

    // ==================== ОБРАБОТЧИК КАСАНИЙ СЕНСОРА ====================

    private fun setupSensorTouch(card: View, tile: TileEntity) {
        var dragRunnable: Runnable? = null
        var startX = 0f; var startY = 0f
        var viewStartX = 0f; var viewStartY = 0f
        var isDragging = false
        var hasMoved = false

        var gestureActive = false
        var viewStartAlpha = card.alpha
        var viewStartElevation = card.elevation

        card.setOnTouchListener { view, event ->
            if (isResizing) return@setOnTouchListener true

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragRunnable?.let { handler.removeCallbacks(it) }
                    dragRunnable = null
                    gestureActive = true
                    viewStartAlpha = view.alpha
                    viewStartElevation = view.elevation
                    hasMoved = false
                    startX = event.rawX; startY = event.rawY
                    viewStartX = view.x; viewStartY = view.y
                    isDragging = false
                    if (isEditMode) {
                        dragRunnable = Runnable {
                            if (!gestureActive) return@Runnable
                            isDragging = true
                            view.alpha = 0.6f
                            view.elevation = 20f
                        }
                        dragRunnable?.let { handler.postDelayed(it, 500L) }
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!gestureActive) return@setOnTouchListener true
                    if (kotlin.math.abs(event.rawX - startX) > 10 ||
                        kotlin.math.abs(event.rawY - startY) > 10
                    ) {
                        hasMoved = true
                        dragRunnable?.let { handler.removeCallbacks(it) }
                    }
                    if (isEditMode && isDragging) {
                        view.x = (viewStartX + event.rawX - startX)
                            .coerceIn(0f, (screenW - view.width).toFloat())
                        view.y = (viewStartY + event.rawY - startY)
                            .coerceIn(0f, (screenH - view.height - 60).toFloat())
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!gestureActive) return@setOnTouchListener true
                    gestureActive = false
                    view.performClick()
                    dragRunnable?.let { handler.removeCallbacks(it) }
                    dragRunnable = null

                    when {
                        // Перетаскивание в режиме редактирования
                        isEditMode && isDragging && hasMoved -> {
                            view.alpha = 1.0f; view.elevation = 8f
                            savePosition(tile.id, view.x.toInt(), view.y.toInt())
                        }
                        // Тап в режиме редактирования → настройки
                        isEditMode && !hasMoved -> openTileSettings(tile.id)
                        // Тап в обычном режиме
                        !isEditMode && !hasMoved -> {
                            if (tile.type == "clock") {
                                // Для часов раскрытие не требуется
                                return@setOnTouchListener true
                            }
                            // Если другой виджет уже раскрыт — только сворачиваем его
                            if (expandedSensorView != null && expandedSensorView != view) {
                                collapseSensor()
                            } else {
                                // Иначе раскрываем/сворачиваем этот виджет
                                expandSensor(tile, view)
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragRunnable?.let { handler.removeCallbacks(it) }
                    dragRunnable = null
                    // Отмена не сохраняет жест: возвращаем его геометрию и фактический вид.
                    if (gestureActive) {
                        view.x = viewStartX
                        view.y = viewStartY
                        view.alpha = viewStartAlpha
                        view.elevation = viewStartElevation
                    }
                    gestureActive = false
                    isDragging = false
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
        var pressRunnable: Runnable? = null
        var startX = 0f; var startY = 0f
        var hasMoved = false; var isDragging = false
        var viewStartX = 0f; var viewStartY = 0f

        var gestureActive = false
        var viewStartAlpha = button.alpha
        var viewStartElevation = button.elevation

        button.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pressRunnable?.let { handler.removeCallbacks(it) }
                    pressRunnable = null
                    gestureActive = true
                    viewStartAlpha = view.alpha
                    viewStartElevation = view.elevation
                    hasMoved = false; isDragging = false
                    startX = event.rawX; startY = event.rawY
                    viewStartX = view.x; viewStartY = view.y

                    if (isEditMode) {
                        pressRunnable = Runnable {
                            if (!gestureActive) return@Runnable
                            isDragging = true
                            view.alpha = 0.6f; view.elevation = 20f
                        }
                        pressRunnable?.let { handler.postDelayed(it, 500L) }
                    } else {
                        pressRunnable = Runnable {
                            if (!gestureActive) return@Runnable
                            if (expandedGroupId == tile.id) collapseChildButtons()
                            else expandChildButtons(tile, view as Button)
                        }
                        pressRunnable?.let { handler.postDelayed(it, 1000L) }
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!gestureActive) return@setOnTouchListener true
                    if (kotlin.math.abs(event.rawX - startX) > 10 ||
                        kotlin.math.abs(event.rawY - startY) > 10
                    ) {
                        hasMoved = true
                        pressRunnable?.let { handler.removeCallbacks(it) }
                    }
                    if (isEditMode && isDragging) {
                        view.x = (viewStartX + event.rawX - startX)
                            .coerceIn(0f, (screenW - view.width).toFloat())
                        view.y = (viewStartY + event.rawY - startY)
                            .coerceIn(0f, (screenH - view.height - 60).toFloat())
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!gestureActive) return@setOnTouchListener true
                    gestureActive = false
                    view.performClick()
                    pressRunnable?.let { handler.removeCallbacks(it) }
                    pressRunnable = null

                    if (isEditMode && isDragging && hasMoved) {
                        view.alpha = 1.0f; view.elevation = 8f
                        savePosition(tile.id, view.x.toInt(), view.y.toInt())
                    } else if (isEditMode && !hasMoved) {
                        openTileSettings(tile.id)
                    } else if (!isEditMode && !hasMoved && expandedGroupId != tile.id) {
                        toggleGroup(tile.id)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    pressRunnable?.let { handler.removeCallbacks(it) }
                    pressRunnable = null
                    // Отмена не сохраняет жест: возвращаем его геометрию и фактический вид.
                    if (gestureActive) {
                        view.x = viewStartX
                        view.y = viewStartY
                        view.alpha = viewStartAlpha
                        view.elevation = viewStartElevation
                    }
                    gestureActive = false
                    isDragging = false
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
                    val domain = eid.substringBefore(".")
                    val current = singleStates[eid] ?: "off"
                    val target = if (current == "on") "turn_off" else "turn_on"
                    webSocket?.callService(domain, target, eid)
                    val newState = if (current == "on") "off" else "on"
                    singleStates[eid] = newState
                    button.background?.setTint(if (newState == "on") activeColor else inactiveColor)
                }
            }
        }

        var dragRunnable: Runnable? = null
        var startX = 0f; var startY = 0f
        var viewStartX = 0f; var viewStartY = 0f
        var isDragging = false; var hasMoved = false

        var gestureActive = false
        var viewStartAlpha = button.alpha
        var viewStartElevation = button.elevation

        button.setOnTouchListener { view, event ->
            if (!isEditMode) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragRunnable?.let { handler.removeCallbacks(it) }
                    dragRunnable = null
                    gestureActive = true
                    viewStartAlpha = view.alpha
                    viewStartElevation = view.elevation
                    hasMoved = false
                    startX = event.rawX; startY = event.rawY
                    viewStartX = view.x; viewStartY = view.y
                    isDragging = false
                    dragRunnable = Runnable {
                        if (!gestureActive) return@Runnable
                        isDragging = true
                        view.alpha = 0.6f; view.elevation = 20f
                    }
                    dragRunnable?.let { handler.postDelayed(it, 500L) }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!gestureActive) return@setOnTouchListener true
                    if (kotlin.math.abs(event.rawX - startX) > 10 ||
                        kotlin.math.abs(event.rawY - startY) > 10
                    ) {
                        hasMoved = true
                        dragRunnable?.let { handler.removeCallbacks(it) }
                    }
                    if (isDragging) {
                        view.x = (viewStartX + event.rawX - startX)
                            .coerceIn(0f, (screenW - view.width).toFloat())
                        view.y = (viewStartY + event.rawY - startY)
                            .coerceIn(0f, (screenH - view.height - 60).toFloat())
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!gestureActive) return@setOnTouchListener true
                    gestureActive = false
                    view.performClick()
                    dragRunnable?.let { handler.removeCallbacks(it) }
                    dragRunnable = null
                    if (isDragging && hasMoved) {
                        view.alpha = 1.0f; view.elevation = 8f
                        savePosition(tile.id, view.x.toInt(), view.y.toInt())
                    } else if (!hasMoved) {
                        openTileSettings(tile.id)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragRunnable?.let { handler.removeCallbacks(it) }
                    dragRunnable = null
                    // Отмена не сохраняет жест: возвращаем его геометрию и фактический вид.
                    if (gestureActive) {
                        view.x = viewStartX
                        view.y = viewStartY
                        view.alpha = viewStartAlpha
                        view.elevation = viewStartElevation
                    }
                    gestureActive = false
                    isDragging = false
                    hasMoved = false
                    true
                }

                else -> false
            }
        }
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
                    val d = eid.substringBefore(".")
                    val c = groupStates[tile.id]?.get(eid) ?: "off"
                    val t = if (c == "on") "turn_off" else "turn_on"
                    webSocket?.callService(d, t, eid)
                    val n = if (c == "on") "off" else "on"
                    background?.setTint(if (n == "on") "#8033CC33".toColorInt() else "#424242".toColorInt())
                    updateGroupState(tile.id, eid, n)
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

    private fun startSensorAnimation(source: View, animator: ValueAnimator) {
        // На одной View кадры пишет только один переход. cancel вызывает onAnimationEnd,
        // поэтому сначала обезвреживаем старые callbacks; обратный ход идёт от текущей геометрии.
        sensorAnimations.remove(source)?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        sensorAnimations[source] = animator
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (sensorAnimations[source] === animator) sensorAnimations.remove(source)
            }
        })
        animator.start()
    }

    private fun clearSensorExpansion() {
        cancelSensorWindowGesture()
        weatherForecastScroll.clear()
        sensorCollapseRunnable?.let { handler.removeCallbacks(it) }
        sensorCollapseRunnable = null
        // cancel вызывает onAnimationEnd: сначала убираем callbacks старого дерева.
        sensorAnimations.values.toList().forEach {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        sensorAnimations.clear()
        expandedSensorView?.setOnTouchListener(null)
        expandedSensorSource?.setOnTouchListener(null)
        expandedSensorView = null
        expandedSensorSource = null
        savedOriginalWidth = 0
        savedOriginalHeight = 0
        savedOriginalX = 0f
        savedOriginalY = 0f
    }

    private fun clearPanelInteractions() {
        // Этот Handler принадлежит только Activity и обслуживает жесты старой панели.
        handler.removeCallbacksAndMessages(null)
        longPressRunnable = null
        collapseChildrenRunnable = null
        clearSensorExpansion()
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
        if (!groupStates.containsKey(gid)) groupStates[gid] = mutableMapOf()
        groupStates[gid]?.put(eid, st)
        updateGroupButtonAppearance(gid)
    }

    private fun getGroupState(gid: String): Boolean {
        val tile = tileManager.getAllTiles().find { it.id == gid && it.type == "group" } ?: return false
        val result = TileButtonConfig.group(tile.config)
        if (result !is ButtonConfigResult.Valid) return false
        val ids = result.value.entityIds
        val states = groupStates.getOrPut(gid) { mutableMapOf() }
        // Удалённые участники больше не определяют цвет и команду группы.
        states.keys.retainAll(ids.toSet())
        // Оставляем известные (в том числе оптимистические) состояния участников.
        // Для добавленных берём уже полученное состояние, не ожидая нового события HA.
        ids.forEach { eid ->
            if (eid !in states) singleStates[eid]?.let { states[eid] = it }
        }
        return ids.any { states[it] == "on" }
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
        val ts = if (getGroupState(gid)) "turn_off" else "turn_on"
        for (eid in group.entityIds) {
            webSocket?.callService(eid.substringBefore("."), ts, eid)
        }
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

    // ==================== СОЗДАНИЕ ПО УМОЛЧАНИЮ ====================

    private fun createDefaultGridTiles() {
        lifecycleScope.launch {
            val (x1, y1) = findFreeSpot(DEFAULT_WIDGET_W, DEFAULT_WIDGET_H)
            tileManager.addTile(
                TileEntity(UUID.randomUUID().toString(), "sensor", "grid",
                    "⚡ Сеть", x1, y1, 1, 1,
                    config = """{"button_size":$DEFAULT_WIDGET_W}""")
            )
            val (x2, y2) = findFreeSpot(DEFAULT_WIDGET_W, DEFAULT_WIDGET_H)
            tileManager.addTile(
                TileEntity(UUID.randomUUID().toString(), "sensor", "grid",
                    "🌡️ Температура", x2, y2, 1, 1,
                    config = """{"button_size":$DEFAULT_WIDGET_W}""")
            )
        }
    }

    private fun createDefaultButtonTiles() {
        val sh = resources.displayMetrics.heightPixels
        lifecycleScope.launch {
            tileManager.addTile(
                TileEntity(UUID.randomUUID().toString(), "button", "bottom_panel",
                    "💡 Свет", 20, sh - DEFAULT_BUTTON_SIZE - 90, 1, 1,
                    config = """{"entity_id":"switch.sonoff_100288c9c3_1","button_size":160}""")
            )
            tileManager.addTile(
                TileEntity(UUID.randomUUID().toString(), "button", "bottom_panel",
                    "🔥 Бойлер", 200, sh - DEFAULT_BUTTON_SIZE - 90, 1, 1,
                    config = """{"entity_id":"switch.boiler","button_size":160}""")
            )
        }
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
                    v.findViewWithTag<TextView>("weather_temp")?.text = "${yandexWeatherManager.currentTemp}°C"
                    v.findViewWithTag<TextView>("weather_icon")?.text = yandexWeatherManager.getWeatherIcon(yandexWeatherManager.currentState)
                    v.findViewWithTag<TextView>("weather_desc")?.text = yandexWeatherManager.translateCondition(yandexWeatherManager.currentState)
                } else {
                    val entityId = WeatherEntityResolver.resolve(tile.config)

                    val state = singleStates[entityId]?.lowercase() ?: "unknown"
                    val attrs = entityAttributes[entityId]
                    val temp = if (entityId.startsWith("weather.")) {
                        if (attrs != null && attrs.has("temperature")) {
                            attrs.opt("temperature")?.toString() ?: "—"
                        } else "—"
                    } else {
                        singleStates[entityId] ?: "—"
                    }
                    
                    v.findViewWithTag<TextView>("weather_temp")?.text = if (temp != "—") "$temp°C" else temp
                    
                    val icon = when {
                        state.contains("sunny") || state.contains("clear") -> "☀️"
                        state.contains("partlycloudy") -> "⛅"
                        state.contains("cloudy") -> "☁️"
                        state.contains("rain") || state.contains("pouring") -> "🌧️"
                        state.contains("snow") -> "❄️"
                        state.contains("lightning") || state.contains("thunder") -> "⚡"
                        state.contains("fog") || state.contains("mist") -> "🌫️"
                        else -> "🌡️"
                    }
                    v.findViewWithTag<TextView>("weather_icon")?.text = icon
                    v.findViewWithTag<TextView>("weather_desc")?.text = tile.title
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
    private fun updatePzemWidget() {
        updateSensorDisplay()
    }
    private fun updateTemperatureWidget() {
        updateSensorDisplay()
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
                if (container != null && tile.type != "clock") {
                    if (tile.type == "weather") {
                        if (isExpanded) buildWeatherDetailedContent(tile, container)
                    } else {
                        buildSensorContent(tile, idsToShow, isExpanded, container)
                    }
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
        SensorBackgroundRenderer.render(tile, child, singleStates::get) { pzemVoltage }
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

    private fun formatFloat(v: String, d: Int, eid: String? = null): String {
        return formatSensorValue(v, d, eid)
    }

    private fun updateGridStatus() {
        gridOnline = (pzemVoltage.toFloatOrNull() ?: 0f) > 10f
    }

    private fun updateTilesForEntity(eid: String, st: String, attrs: JSONObject? = null) {
        // Это полный HA-снимок: unknown/unavailable — настоящие состояния.
        singleStates[eid] = st
        val complete = copyAttributes(attrs)
        if (complete.has("forecast")) {
            // Явный HA-forecast новее частичного и далее живёт как обычный HA-атрибут.
            syntheticForecasts.remove(eid)
        } else if (syntheticForecasts.containsKey(eid)) {
            complete.put("forecast", syntheticForecasts[eid] ?: JSONArray())
        }
        entityAttributes[eid] = complete

        if (attrs != null && attrs.length() > 0) {
            
            // Если пришли новые атрибуты (например, прогноз), и этот виджет сейчас раскрыт — обновим его
            if (expandedSensorView != null && (expandedTile?.id == eid || eid.startsWith("weather."))) {
                val tag = expandedSensorView?.tag as? String
                val tile = tileManager.getAllTiles().find { it.id == tag }
                if (tile != null && tile.type == "weather") {
                    val container = expandedSensorView?.findViewWithTag<LinearLayout>("sensor_text_container")
                    if (container != null) {
                        buildWeatherDetailedContent(tile, container)
                    }
                }
            }
        }

        if (eid == motionSensorId) {
            if (st == "on" || st == "true" || st == "playing") {
                wakeScreen()
            }
        }

        when (eid) {
            "sensor.pzem_energy_monitor_pzem_voltage" -> {
                pzemVoltage = formatFloat(st, 0, eid); updateGridStatus(); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_power" -> {
                pzemPower = formatFloat(st, 0, eid); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_current" -> {
                pzemCurrent = formatFloat(st, 2, eid); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_energy" -> {
                pzemEnergy = formatFloat(st, 2, eid); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_frequency" -> {
                pzemFrequency = formatFloat(st, 1, eid); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia" -> {
                techRoomTemp = formatFloat(st, 1, eid); updateTemperatureWidget()
            }
        }

        // Цвет определяется фактическим источником кнопки независимо от домена HA.
        updateSingleButtonColor(eid)
        if (eid.startsWith("weather.")) updateWeatherViews()
        if (eid.startsWith("sensor.")) {
            updateSensorDisplay()
            updateWeatherViews()
        }

        tileManager.getAllTiles().filter { it.type == "group" }.forEach { t ->
            val result = TileButtonConfig.group(t.config)
            if (result is ButtonConfigResult.Valid && eid in result.value.entityIds) {
                updateGroupState(t.id, eid, st); updateGroupButtonAppearance(t.id)
            }
        }
    }

    private fun copyAttributes(source: JSONObject?): JSONObject {
        val copy = JSONObject()
        source?.let { json -> json.keys().forEach { key -> copy.put(key, json.get(key)) } }
        return copy
    }

    private fun updateForecastForEntity(eid: String, forecast: JSONArray?) {
        syntheticForecasts[eid] = forecast
        val combined = copyAttributes(entityAttributes[eid])
        // Пустой массив также блокирует старые forecast_daily/hourly в renderer.
        // Исходные HA-атрибуты и реальное состояние при этом не меняются.
        combined.put("forecast", forecast ?: JSONArray())
        entityAttributes[eid] = combined
        updateWeatherViews()
    }

    private fun formatFloat(v: String, d: Int): String {
        return SensorValueFormatter.formatFloat(v, d)
    }

    // ==================== WEBSOCKET ====================

    private var haConfiguration: List<String>? = null
    private var haGeneration = 0

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
        if (connectedTokenRevision != HaTokenAccess.revision) {
            haGeneration++
            webSocket?.disconnect()
            webSocket = null
            haConfiguration = null
        }
        if (webSocket == null) setHaStatus("Чтение токена HA...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { tokens().read() }
            if (request != haTokenRequest || isFinishing || isDestroyed || !HaTokenAccess.isCurrent(result)) return@launch
            if (prefs.getString("ha_local_host", "").orEmpty().trim() != local ||
                prefs.getString("ha_remote_host", "").orEmpty().trim() != remote) return@launch
            if (result.status != HaTokenAccess.Status.READY) {
                haGeneration++
                webSocket?.disconnect()
                webSocket = null
                haConfiguration = null
                setHaStatus(result.message)
                return@launch
            }
            connectWithHaToken(local, remote, result.value.orEmpty().trim())
        }
    }

    private fun connectWithHaToken(local: String, remote: String, token: String) {
        connectedTokenRevision = HaTokenAccess.revision
        val tokenRevision = connectedTokenRevision
        val configuration = listOf(local, remote, token)
        if (haConfiguration == configuration && webSocket != null) {
            // connect() restarts a stopped client and leaves active/backoff connections alone.
            webSocket?.connect()
            return
        }
        haConfiguration = configuration
        val generation = ++haGeneration
        webSocket?.disconnect()
        webSocket = null
        val hosts = listOf(local, remote).filter { it.isNotEmpty() }.distinct()
        if (token.isEmpty() || hosts.isEmpty()) {
            setHaStatus("Ошибка: укажите адрес и токен Home Assistant")
            return
        }
        webSocket = HomeAssistantWebSocket(
            host = hosts.first(),
            token = token,
            alternateHosts = hosts.drop(1),
            onStateChanged = { entity, state, attributes ->
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) updateTilesForEntity(entity, state, attributes)
            },
            onForecastChanged = { entity, forecast ->
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) updateForecastForEntity(entity, forecast)
            },
            onConnected = {
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) {
                    val active = webSocket?.getHost().orEmpty()
                    val network = if (local.isNotEmpty() && active == local) "Локальная сеть" else "Интернет"
                    setHaStatus("$network ($active)")
                    Toast.makeText(this, "Подключено: $network", Toast.LENGTH_SHORT).show()
                    subscribeToNeededEntities()
                    webSocket?.getEntities()
                }
            },
            onDisconnected = {},
            onEntitiesList = { if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) cacheEntities(it) },
            onError = { if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) setHaStatus("Ошибка: $it") },
            onStatus = { if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) setHaStatus(it) }
        )
        webSocket?.connect()
    }

    private fun cacheEntities(e: List<HaEntity>) {
        getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit {
            putString("cached_entities", com.google.gson.Gson().toJson(e))
            putLong("cached_entities_time", System.currentTimeMillis())
        }
    }

    private fun subscribeToNeededEntities() {
        val ids = mutableSetOf<String>()
        val weatherIds = mutableSetOf<String>()
        
        val useYandex = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getBoolean("use_yandex_weather", false)
        motionSensorId?.let { ids.add(it) }
        tileManager.getAllTiles().forEach { t ->
            if (t.type == "weather" && !useYandex) {
                val source = WeatherEntityResolver.resolve(t.config)
                if (source.startsWith("weather.")) weatherIds.add(source)
            }
            if (t.type == "button" || t.type == "group") {
                // Повреждённый список не должен давать частичный набор источников для подписок.
                val result = TileButtonConfig.subscriptionIds(t.config)
                if (result is ButtonConfigResult.Valid) {
                    result.value.forEach { eid ->
                        ids.add(eid)
                    }
                }
                return@forEach
            }
            try {
                val c = JSONObject(t.config)
                val eid = c.optString("entity_id", "").takeIf { it.isNotEmpty() }
                if (eid != null) {
                    ids.add(eid)
                }
                
                c.optJSONArray("entity_ids")?.let {
                    for (i in 0 until it.length()) {
                        val subEid = it.getString(i)
                        ids.add(subEid)
                    }
                }
            } catch (_: Exception) {}
        }
        
        if (tileManager.getAllTiles().any { it.title == "⚡ Сеть" }) {
            ids.add("sensor.pzem_energy_monitor_pzem_voltage")
            ids.add("sensor.pzem_energy_monitor_pzem_power")
            ids.add("sensor.pzem_energy_monitor_pzem_current")
            ids.add("sensor.pzem_energy_monitor_pzem_energy")
            ids.add("sensor.pzem_energy_monitor_pzem_frequency")
        }
        if (tileManager.getAllTiles().any { it.title == "🌡️ Температура" }) {
            ids.add("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia")
        }
        
        webSocket?.subscribeEntities(ids.toList())

        // Передаём весь актуальный набор, включая пустой при выборе Яндекса.
        webSocket?.syncForecastSubscriptions(weatherIds)
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
