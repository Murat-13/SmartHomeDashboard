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
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.launch
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
    private var webSocket: HomeAssistantWebSocket? = null

    private var pzemVoltage = "—"
    private var pzemCurrent = "—"
    private var pzemPower = "—"
    private var pzemEnergy = "—"
    private var pzemFrequency = "—"
    private var gridOnline = true
    private var techRoomTemp = "—"

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
    private var isEditMode = false

    private val expandedChildButtons = mutableListOf<Button>()
    private var expandedGroupId: String? = null
    private var expandedSourceButton: Button? = null
    private var expandedTile: TileEntity? = null

    private var expandedSensorView: View? = null
    private var expandedSensorSource: View? = null
    private var sensorCollapseRunnable: Runnable? = null
    private var savedOriginalWidth = 0
    private var savedOriginalHeight = 0
    private var savedOriginalX = 0f
    private var savedOriginalY = 0f
    private var isResizing = false

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

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_main)
        bottomPanel = findViewById(R.id.bottomPanel)
        overlayContainer = findViewById(R.id.overlayContainer)
        dimOverlay = findViewById(R.id.dimOverlay)
        dimOverlay.setOnClickListener { collapseAll() }

        tileManager = TileManager(this)

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
        refreshBottomPanel()
        
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        motionSensorId = prefs.getString("motion_sensor_id", null)
        screenTimeoutMinutes = prefs.getInt("screen_timeout_minutes", 5)
        
        subscribeToNeededEntities()
        checkKioskMode()
        resetIdleTimer()
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
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
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        webSocket?.disconnect()
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
    private fun openWithPinCheck(action: () -> Unit) {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val savedPin = prefs.getString("admin_pin", "")
        
        // Если PIN не задан в настройках, пропускаем без проверки
        if (savedPin.isNullOrEmpty()) {
            action()
            return
        }

        val lastAuth = prefs.getLong("last_auth_time", 0L)
        // Получаем время сессии из настроек (в минутах), по умолчанию 60
        val sessionMinutes = prefs.getInt("pin_session_minutes", 60)
        val sessionMillis = sessionMinutes.toLong() * 60 * 1000

        // Проверяем, не истекла ли сессия
        if (System.currentTimeMillis() - lastAuth > sessionMillis) {
            PinDialog(this) {
                // При успешном вводе обновляем время последней авторизации
                prefs.edit { putLong("last_auth_time", System.currentTimeMillis()) }
                action()
            }.show()
        } else {
            // Сессия еще активна
            action()
        }
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
        return super.dispatchTouchEvent(event)
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

        // Основной контейнер виджета
        val card = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(savedWidth, savedHeight)
            tag = tile.id
            alpha = 0.85f
            elevation = 8f
            setBackgroundColor("#80333333".toColorInt())
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 32f)
                }
            }
        }

        // Слой наполнения для батареи
        if (tile.type == "battery") {
            val progressFill = View(this).apply {
                tag = "battery_progress_fill"
                layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
                setBackgroundColor("#8033CC33".toColorInt())
            }
            card.addView(progressFill)
        }

        // Контейнер для текста
        val textContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(8, 8, 8, 8)
            tag = "sensor_text_container"
        }

        if (tile.type == "clock") {
            val clockView = android.widget.TextView(this).apply {
                tag = "clock_text"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_PX, savedHeight * 0.4f)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            textContainer.addView(clockView)

            val dateView = android.widget.TextView(this).apply {
                tag = "clock_date"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, savedHeight * 0.15f)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            textContainer.addView(dateView)

            val now = Date()
            clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
        } else if (tile.type == "weather") {
            val weatherIcon = android.widget.TextView(this).apply {
                tag = "weather_icon"
                text = "☀️"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_PX, savedHeight * 0.35f)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            textContainer.addView(weatherIcon)

            val tempView = android.widget.TextView(this).apply {
                tag = "weather_temp"
                text = "—°C"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_PX, savedHeight * 0.25f)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            textContainer.addView(tempView)

            val descView = android.widget.TextView(this).apply {
                tag = "weather_desc"
                text = "Загрузка..."
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_PX, savedHeight * 0.12f)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            textContainer.addView(descView)
        } else {
            // Заголовок виджета
            val titleView = android.widget.TextView(this).apply {
                text = tile.title
                textSize = tile.fontSize.toFloat()
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                tag = "sensor_title"
            }
            textContainer.addView(titleView)
        }

        card.addView(textContainer)

        // Уголок изменения размера
        val resizeHandle = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_resize)
            layoutParams = FrameLayout.LayoutParams(40, 40).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
            visibility = if (isEditMode) View.VISIBLE else View.GONE
            setPadding(8, 8, 8, 8)
        }
        card.addView(resizeHandle)

        // Перетаскивание и раскрытие
        setupSensorTouch(card, tile)

        // Изменение размера
        var resizeStartX = 0f
        var resizeStartY = 0f
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isResizing = true
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newW = (card.width + event.rawX - resizeStartX).toInt()
                        .coerceIn(120, screenW - card.x.toInt() - 20)
                    val newH = (card.height + event.rawY - resizeStartY).toInt()
                        .coerceIn(120, screenH - card.y.toInt() - 80)
                    card.layoutParams.width = newW
                    card.layoutParams.height = newH
                    if (tile.type == "clock") {
                        card.findViewWithTag<android.widget.TextView>("clock_text")?.let {
                            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, newH * 0.4f)
                        }
                        card.findViewWithTag<android.widget.TextView>("clock_date")?.let {
                            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, newH * 0.15f)
                        }
                    } else if (tile.type == "weather") {
                        card.findViewWithTag<android.widget.TextView>("weather_icon")?.let {
                            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, newH * 0.35f)
                        }
                        card.findViewWithTag<android.widget.TextView>("weather_temp")?.let {
                            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, newH * 0.25f)
                        }
                        card.findViewWithTag<android.widget.TextView>("weather_desc")?.let {
                            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, newH * 0.12f)
                        }
                    }
                    card.requestLayout()
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isResizing = false
                    val config = JSONObject(tile.config)
                    config.put("button_size", card.width)
                    config.put("widget_height", card.height)
                    val updatedTile = tile.copy(config = config.toString())
                    val tiles = tileManager.loadTiles().toMutableList()
                    val idx = tiles.indexOfFirst { it.id == tile.id }
                    if (idx >= 0) {
                        tiles[idx] = updatedTile
                        tileManager.saveTiles(tiles)
                    }
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
        val container = source.findViewWithTag<android.widget.LinearLayout>("sensor_text_container")
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

        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
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
        animator.start()

        // Сохраняем ссылку для сворачивания
        expandedSensorView = source
        expandedSensorSource = source

        // Тап по раскрытому виджету сворачивает его
        source.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) collapseSensor()
            true
        }

        // Автосворачивание через 15 секунд
        sensorCollapseRunnable = Runnable { collapseSensor() }
        handler.postDelayed(sensorCollapseRunnable!!, 15000L)
    }

    // ==================== СВОРАЧИВАНИЕ СЕНСОРА ====================

    private fun collapseSensor() {
        val source = expandedSensorView ?: return
        val tile = tileManager.getAllTiles().find { it.id == source.tag as? String } ?: return
        val idsToShow = getCollapsedSensorIds(tile)

        // Обновляем содержимое виджета (табличный вид)
        val container = source.findViewWithTag<android.widget.LinearLayout>("sensor_text_container")
        if (container != null) {
            buildSensorContent(tile, idsToShow, false, container)
        }

        // Возвращаем elevation на исходный уровень
        source.elevation = 8f

        // Анимируем сжатие до исходного размера и позиции
        val startW = source.layoutParams.width
        val startH = source.layoutParams.height
        val startX = source.x
        val startY = source.y

        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                source.layoutParams.width = (startW + (savedOriginalWidth - startW) * fraction).toInt()
                source.layoutParams.height = (startH + (savedOriginalHeight - startH) * fraction).toInt()
                source.x = startX + (savedOriginalX - startX) * fraction
                source.y = startY + (savedOriginalY - startY) * fraction
                source.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    setupSensorTouch(source, tile)
                }
            })
        }
        animator.start()

        // Сбрасываем состояние
        expandedSensorView = null
        expandedSensorSource = null
        sensorCollapseRunnable?.let { handler.removeCallbacks(it) }
    }

    // ==================== ОБРАБОТЧИК КАСАНИЙ СЕНСОРА ====================

    private fun setupSensorTouch(card: View, tile: TileEntity) {
        var dragRunnable: Runnable? = null
        var startX = 0f; var startY = 0f
        var viewStartX = 0f; var viewStartY = 0f
        var isDragging = false
        var hasMoved = false

        card.setOnTouchListener { view, event ->
            if (isResizing) return@setOnTouchListener true

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    hasMoved = false
                    startX = event.rawX; startY = event.rawY
                    viewStartX = view.x; viewStartY = view.y
                    isDragging = false
                    if (isEditMode) {
                        dragRunnable = Runnable {
                            isDragging = true
                            view.alpha = 0.6f
                            view.elevation = 20f
                        }
                        dragRunnable?.let { handler.postDelayed(it, 500L) }
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
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
                    view.performClick()
                    dragRunnable?.let { handler.removeCallbacks(it) }

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

                else -> false
            }
        }
    }

    // ==================== КНОПКИ ====================

    private fun createTileButton(tile: TileEntity): Button {
        val sz = getButtonSize(tile)
        val on = "#8033CC33".toColorInt()
        val off = "#424242".toColorInt()

        return Button(this).apply {
            text = tile.title
            layoutParams = FrameLayout.LayoutParams(sz, sz)
            tag = tile.id
            alpha = 0.8f
            elevation = 8f
            background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null)

            if (tile.type == "group") {
                background?.setTint(if (getGroupState(tile.id)) on else off)
            } else {
                val eid = JSONObject(tile.config).optString("entity_id", "")
                background?.setTint(if (singleStates[eid] == "on") on else off)
            }

            setTextColor("#FFFFFF".toColorInt())
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            isAllCaps = false

            if (tile.type == "group") {
                setupGroupTouch(tile, this)
            } else {
                setupButtonTouch(tile, this, on, off)
            }
        }
    }

    private fun setupGroupTouch(tile: TileEntity, button: Button) {
        var pressRunnable: Runnable? = null
        var startX = 0f; var startY = 0f
        var hasMoved = false; var isDragging = false
        var viewStartX = 0f; var viewStartY = 0f

        button.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    hasMoved = false; isDragging = false
                    startX = event.rawX; startY = event.rawY
                    viewStartX = view.x; viewStartY = view.y

                    if (isEditMode) {
                        pressRunnable = Runnable {
                            isDragging = true
                            view.alpha = 0.6f; view.elevation = 20f
                        }
                        pressRunnable?.let { handler.postDelayed(it, 500L) }
                    } else {
                        pressRunnable = Runnable {
                            if (expandedGroupId == tile.id) collapseChildButtons()
                            else expandChildButtons(tile, view as Button)
                        }
                        pressRunnable?.let { handler.postDelayed(it, 1000L) }
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
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
                    view.performClick()
                    pressRunnable?.let { handler.removeCallbacks(it) }

                    if (isEditMode && isDragging && hasMoved) {
                        view.alpha = 1.0f; view.elevation = 8f
                        savePosition(tile.id, view.x.toInt(), view.y.toInt())
                    } else if (isEditMode && !hasMoved) {
                        openTileSettings(tile.id)
                    } else if (!isEditMode && !hasMoved && expandedGroupId != tile.id) {
                        toggleGroup(tile.id, tile)
                    }
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
                val eid = JSONObject(tile.config).optString("entity_id", "")
                if (eid.isNotEmpty()) {
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

        button.setOnTouchListener { view, event ->
            if (!isEditMode) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    hasMoved = false
                    startX = event.rawX; startY = event.rawY
                    viewStartX = view.x; viewStartY = view.y
                    isDragging = false
                    dragRunnable = Runnable {
                        isDragging = true
                        view.alpha = 0.6f; view.elevation = 20f
                    }
                    dragRunnable?.let { handler.postDelayed(it, 500L) }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
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
                    view.performClick()
                    dragRunnable?.let { handler.removeCallbacks(it) }
                    if (isDragging && hasMoved) {
                        view.alpha = 1.0f; view.elevation = 8f
                        savePosition(tile.id, view.x.toInt(), view.y.toInt())
                    } else if (!hasMoved) {
                        openTileSettings(tile.id)
                    }
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
        collapseChildButtons()

        val cfg = JSONObject(tile.config)
        val ids = cfg.optJSONArray("entity_ids") ?: return
        expandedGroupId = tile.id
        expandedSourceButton = src
        expandedTile = tile

        val bs = getButtonSize(tile)
        val loc = IntArray(2)
        src.getLocationOnScreen(loc)
        val cx = loc[0] + src.width / 2
        val top = loc[1]

        val cnt = ids.length()
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
            val eid = ids.getString(i)
            val st = groupStates[tile.id]?.get(eid) ?: "off"
            val nms = cfg.optJSONArray("child_names")
            val nm = if (nms != null && i < nms.length()) nms.getString(i) else (i + 1).toString()

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
            b.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f)
                .setDuration(200).setStartDelay(i * 30L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { rt.removeView(b) }.start()
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

    // ==================== ГРУППЫ ====================

    private fun updateGroupState(gid: String, eid: String, st: String) {
        if (!groupStates.containsKey(gid)) groupStates[gid] = mutableMapOf()
        groupStates[gid]?.put(eid, st)
        updateGroupButtonAppearance(gid)
    }

    private fun getGroupState(gid: String) = groupStates[gid]?.values?.any { it == "on" } ?: false

    private fun updateGroupButtonAppearance(gid: String) {
        val c = if (getGroupState(gid)) "#8033CC33".toColorInt() else "#424242".toColorInt()
        for (i in 0 until bottomPanel.childCount) {
            val ch = bottomPanel.getChildAt(i)
            if (ch is Button && ch.tag == gid) { ch.background?.setTint(c); return }
        }
    }

    private fun toggleGroup(gid: String, tile: TileEntity) {
        try {
            val ids = JSONObject(tile.config).optJSONArray("entity_ids") ?: return
            val ts = if (getGroupState(gid)) "turn_off" else "turn_on"
            for (i in 0 until ids.length()) {
                webSocket?.callService(ids.getString(i).substringBefore("."), ts, ids.getString(i))
            }
        } catch (_: Exception) {}
    }

    // ==================== ОБЫЧНЫЕ КНОПКИ ====================

    private fun updateSingleButtonColor(eid: String) {
        val c = if (singleStates[eid] == "on") "#8033CC33".toColorInt() else "#424242".toColorInt()
        for (i in 0 until bottomPanel.childCount) {
            val ch = bottomPanel.getChildAt(i)
            if (ch is Button) {
                val tile = (ch.tag as? String)?.let { tileManager.getAllTiles().find { t -> t.id == it } }
                if (tile != null && tile.type != "group") {
                    try {
                        if (JSONObject(tile.config).optString("entity_id", "") == eid) {
                            ch.background?.setTint(c)
                        }
                    } catch (_: Exception) {}
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
            v.findViewWithTag<android.widget.TextView>("clock_text")?.text = time
            v.findViewWithTag<android.widget.TextView>("clock_date")?.text = date
        }
        updateWeatherViews()
    }

    private fun updateWeatherViews() {
        for (i in 0 until bottomPanel.childCount) {
            val v = bottomPanel.getChildAt(i)
            val tag = v.tag as? String ?: continue
            val tile = tileManager.getAllTiles().find { it.id == tag } ?: continue
            if (tile.type == "weather") {
                val container = v.findViewWithTag<android.widget.LinearLayout>("sensor_text_container")
                if (container != null && expandedSensorView == v) {
                    buildWeatherDetailedContent(tile, container)
                    continue
                }
            }

            val entityId = try {
                val config = JSONObject(tile.config)
                val arr = config.optJSONArray("entity_ids")
                if (arr != null && arr.length() > 0) arr.getString(0)
                else config.optString("entity_id", "weather.home")
            } catch (_: Exception) { "weather.home" }

            val state = singleStates[entityId]?.lowercase() ?: "unknown"
            
            // Пытаемся достать температуру из атрибутов (для weather.*) или из состояния (для sensor.*)
            val attrs = entityAttributes[entityId]
            val temp = if (entityId.startsWith("weather.")) {
                if (attrs != null && attrs.has("temperature")) {
                    attrs.opt("temperature")?.toString() ?: "—"
                } else "—"
            } else {
                singleStates[entityId] ?: "—"
            }
            
            v.findViewWithTag<android.widget.TextView>("weather_temp")?.text = if (temp != "—") "$temp°C" else temp
            
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
            v.findViewWithTag<android.widget.TextView>("weather_icon")?.text = icon
            v.findViewWithTag<android.widget.TextView>("weather_desc")?.text = tile.title
        }
    }

    private fun buildWeatherDetailedContent(tile: TileEntity, container: android.widget.LinearLayout) {
        container.removeAllViews()
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.gravity = Gravity.TOP
        container.setPadding(30, 30, 30, 30)

        val entityId = try {
            val config = JSONObject(tile.config)
            val arr = config.optJSONArray("entity_ids")
            if (arr != null && arr.length() > 0) arr.getString(0)
            else config.optString("entity_id", "weather.home")
        } catch (_: Exception) { "weather.home" }

        val attrs = entityAttributes[entityId] ?: JSONObject()
        val state = singleStates[entityId]?.lowercase() ?: "unknown"

        // 1. Заголовок и текущая погода
        val topRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        
        val bigIcon = android.widget.TextView(this).apply {
            textSize = 48f
            text = when {
                state.contains("sunny") || state.contains("clear") -> "☀️"
                state.contains("partlycloudy") -> "⛅"
                state.contains("cloudy") -> "☁️"
                state.contains("rain") || state.contains("pouring") -> "🌧️"
                state.contains("snow") -> "❄️"
                state.contains("lightning") || state.contains("thunder") -> "⚡"
                state.contains("fog") || state.contains("mist") -> "🌫️"
                else -> "🌡️"
            }
        }
        topRow.addView(bigIcon)

        val mainInfo = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(30, 0, 0, 0)
        }
        mainInfo.addView(android.widget.TextView(this).apply {
            text = tile.title
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        mainInfo.addView(android.widget.TextView(this).apply {
            val stRus = when {
                state.contains("sunny") || state.contains("clear") -> "Ясно"
                state.contains("partlycloudy") -> "Переменная облачность"
                state.contains("cloudy") -> "Облачно"
                state.contains("rain") || state.contains("pouring") -> "Дождь"
                state.contains("snow") -> "Снег"
                state.contains("lightning") || state.contains("thunder") -> "Гроза"
                state.contains("fog") || state.contains("mist") -> "Туман"
                else -> state.replaceFirstChar { it.uppercase() }
            }
            text = stRus
            setTextColor(Color.LTGRAY)
            textSize = 16f
        })
        topRow.addView(mainInfo)

        val spacer = View(this).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, 1, 1f) }
        topRow.addView(spacer)

        val bigTemp = android.widget.TextView(this).apply {
            val t = attrs.opt("temperature")?.toString() ?: "—"
            text = "$t°C"
            setTextColor(Color.WHITE)
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
        }
        topRow.addView(bigTemp)
        container.addView(topRow)

        // 2. Доп. параметры (Влажность, Давление, Ветер)
        val detailsGrid = android.widget.GridLayout(this).apply {
            columnCount = 2
            setPadding(0, 30, 0, 20)
        }
        
        val addDetail = { icon: String, label: String, value: String ->
            val row = android.widget.LinearLayout(this).apply {
                setPadding(0, 10, 40, 10)
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(android.widget.TextView(this).apply { text = icon; textSize = 18f })
            val txt = android.widget.LinearLayout(this).apply { 
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(15, 0, 0, 0)
            }
            txt.addView(android.widget.TextView(this).apply { text = label; setTextColor(Color.LTGRAY); textSize = 12f })
            txt.addView(android.widget.TextView(this).apply { text = value; setTextColor(Color.WHITE); textSize = 14f })
            row.addView(txt)
            detailsGrid.addView(row)
        }

        addDetail("💧", "Влажность", "${attrs.optString("humidity", "—")}%")
        addDetail("⏲️", "Давление", "${attrs.optString("pressure", "—")} hPa")
        addDetail("🌬️", "Ветер", "${attrs.optString("wind_speed", "—")} км/ч")
        addDetail("🧭", "Направление", attrs.optString("wind_bearing", "—"))

        container.addView(detailsGrid)

        // 3. Прогноз
        val forecastLabel = android.widget.TextView(this).apply {
            text = "Прогноз:"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 10, 0, 10)
        }
        container.addView(forecastLabel)

        val forecastScroll = android.widget.HorizontalScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isFillViewport = true
        }
        val forecastRow = android.widget.LinearLayout(this).apply { 
            orientation = android.widget.LinearLayout.HORIZONTAL 
        }
        
        var forecastArr = attrs.optJSONArray("forecast")
        if (forecastArr == null) forecastArr = attrs.optJSONArray("forecast_daily")
        if (forecastArr == null) forecastArr = attrs.optJSONArray("forecast_hourly")
        
        // Отладочный лог: выведем все ключи атрибутов, если прогноз не найден
        if (forecastArr == null) {
            val keys = mutableListOf<String>()
            val it = attrs.keys()
            while (it.hasNext()) keys.add(it.next())
            Log.d("WeatherDetailed", "Forecast not found. Available keys: ${keys.joinToString()}")
        } else {
            Log.d("WeatherDetailed", "Forecast array length: ${forecastArr.length()}")
        }
        
        if (forecastArr != null && forecastArr.length() > 0) {
            for (i in 0 until forecastArr.length().coerceAtMost(14)) {
                val f = forecastArr.getJSONObject(i)
                val day = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(20, 15, 20, 15)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor("#25FFFFFF".toColorInt())
                        cornerRadius = 16f
                    }
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, 15, 0)
                    layoutParams = lp
                }
                
                val dt = f.optString("datetime", f.optString("time", ""))
                val dateLabel = try {
                    if (dt.contains("T")) {
                        val sdfIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                        val outSdf = SimpleDateFormat("E d", Locale.getDefault())
                        outSdf.format(sdfIn.parse(dt) ?: Date()).uppercase()
                    } else if (dt.length >= 10) {
                        val sdfIn = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        val outSdf = SimpleDateFormat("E d", Locale.getDefault())
                        outSdf.format(sdfIn.parse(dt.substring(0, 10)) ?: Date()).uppercase()
                    } else {
                        dt.takeLast(5)
                    }
                } catch(_: Exception) { dt.takeLast(5) }

                day.addView(android.widget.TextView(this).apply { 
                    text = dateLabel
                    setTextColor(Color.LTGRAY)
                    textSize = 10f 
                })
                
                day.addView(android.widget.TextView(this).apply { 
                    val fst = f.optString("condition", "").lowercase()
                    text = when {
                        fst.contains("sun") || fst.contains("clear") -> "☀️"
                        fst.contains("partlycloudy") -> "⛅"
                        fst.contains("cloudy") -> "☁️"
                        fst.contains("rain") || fst.contains("pouring") -> "🌧️"
                        fst.contains("snow") -> "❄️"
                        fst.contains("lightning") || fst.contains("thunder") -> "⚡"
                        fst.contains("fog") || fst.contains("mist") -> "🌫️"
                        else -> "🌡️"
                    }
                    textSize = 24f
                    setPadding(0, 8, 0, 8)
                })

                val tempHigh = f.optString("temperature", f.optString("temp", "—"))
                val tempLow = f.optString("templow", "")
                
                day.addView(android.widget.TextView(this).apply { 
                    text = if (tempLow.isNotEmpty()) "$tempHigh°/$tempLow°" else "$tempHigh°"
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 13f
                })
                forecastRow.addView(day)
            }
        } else {
            forecastRow.addView(android.widget.TextView(this).apply {
                text = "Прогноз временно недоступен\n(Проверьте атрибуты в HA)"
                setTextColor(Color.GRAY)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(40, 40, 40, 40)
            })
        }
        forecastScroll.addView(forecastRow)
        container.addView(forecastScroll)
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

                // Обновляем содержимое виджета (табличный вид)
                val container = child.findViewWithTag<android.widget.LinearLayout>("sensor_text_container")
                if (container != null && tile.type != "clock") {
                    if (tile.type == "weather" && isExpanded) {
                        buildWeatherDetailedContent(tile, container)
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
        val progressFill = view.findViewWithTag<View>("battery_progress_fill") ?: return
        
        // Берем первый выбранный датчик как основной источник SOC %
        val socEntityId = try {
            val config = JSONObject(tile.config)
            val arr = config.optJSONArray("entity_ids")
            if (arr != null && arr.length() > 0) arr.getString(0)
            else config.optString("entity_id", "")
        } catch (_: Exception) { "" }

        val socValueStr = singleStates[socEntityId] ?: "0"
        val soc = socValueStr.replace("%", "").trim().toFloatOrNull() ?: 0f
        
        // Расчет ширины
        val percent = (soc / 100f).coerceIn(0f, 1f)
        val targetWidth = (view.width * percent).toInt()
        
        // Анимация изменения ширины
        if (progressFill.width != targetWidth) {
            val anim = android.animation.ValueAnimator.ofInt(progressFill.width, targetWidth)
            anim.addUpdateListener {
                val lp = progressFill.layoutParams
                lp.width = it.animatedValue as Int
                progressFill.layoutParams = lp
            }
            anim.duration = 500
            anim.start()
        }

        // Цвет в зависимости от заряда
        val color = when {
            soc > 20f -> "#8033CC33" // Зеленый
            soc > 10f -> "#80FFCC00" // Желтый
            else -> "#80FF3333"      // Красный
        }
        progressFill.setBackgroundColor(color.toColorInt())
        
        // Фон самой карточки остается темным
        view.setBackgroundColor("#80333333".toColorInt())
    }

    private fun updateNormalSensorWidget(tile: TileEntity, child: FrameLayout) {
        // Применяем правила цвета (колонки От и До)
        var finalColor: String? = null
        try {
            val rulesJson = tile.colorRules
            if (rulesJson.isNotEmpty() && rulesJson != "[]") {
                val rulesArr = JSONArray(rulesJson)
                // Проходим по всем правилам. Последнее сработавшее перекроет предыдущие.
                for (i in 0 until rulesArr.length()) {
                    val obj = rulesArr.getJSONObject(i)
                    val rEntityId = obj.optString("entity_id")
                    val rFromStr = obj.optString("from", "")
                    val rToStr = obj.optString("to", "")
                    val rColor = obj.optString("color_hex", "")

                    if (rEntityId.isNotEmpty() && rColor.isNotEmpty()) {
                        val currentVal = singleStates[rEntityId] ?: ""
                        if (checkRuleMatch(currentVal, rFromStr, rToStr)) {
                            finalColor = rColor
                        }
                    }
                }
            }

            if (finalColor != null) {
                child.setBackgroundColor(finalColor.toColorInt())
            } else {
                // Стандартная логика для сети или дефолтный фон
                if (tile.title.lowercase().contains("сеть")) {
                    val isOnline = (pzemVoltage.toFloatOrNull() ?: 0f) > 10f
                    child.setBackgroundColor(
                        if (isOnline) "#8033CC33".toColorInt() else "#80FF3333".toColorInt()
                    )
                } else {
                    child.setBackgroundColor("#80333333".toColorInt())
                }
            }
        } catch (_: Exception) {
            child.setBackgroundColor("#80333333".toColorInt())
        }
    }

    /**
     * Проверяет, подходит ли значение под диапазон.
     * Если оба числа — математическое сравнение.
     * Если нет — строковое.
     */
    private fun checkRuleMatch(value: String, from: String, to: String): Boolean {
        if (value.isEmpty()) return false

        // Очищаем значение от единиц измерения для попытки парсинга числа
        val cleanValue = value.replace(Regex("[^0-9.-]"), "")
        val vNum = cleanValue.toDoubleOrNull()
        val fNum = from.toDoubleOrNull()
        val tNum = to.toDoubleOrNull()

        // Если значение — число, и хотя бы одна граница — число
        if (vNum != null && (fNum != null || tNum != null)) {
            val fromOk = if (from.isEmpty()) true else (fNum != null && vNum >= fNum)
            val toOk = if (to.isEmpty()) true else (tNum != null && vNum <= tNum)
            return fromOk && toOk
        }

        // Иначе сравниваем как строки
        val fromOk = if (from.isEmpty()) true else value.equals(from, ignoreCase = true) || value >= from
        val toOk = if (to.isEmpty()) true else value.equals(to, ignoreCase = true) || value <= to
        return fromOk && toOk
    }

    /**
     * Возвращает список entity_id для свёрнутого режима.
     * Если ничего не выбрано — возвращает стандартный набор.
     */
    private fun getCollapsedSensorIds(tile: TileEntity): List<String> {
        try {
            val arr = JSONArray(tile.collapsedSensorIds)
            if (arr.length() > 0) {
                val ids = mutableListOf<String>()
                for (i in 0 until arr.length()) ids.add(arr.getString(i))
                return ids
            }
        } catch (_: Exception) {}
        // По умолчанию (ищем по вхождению слова)
        val title = tile.title.lowercase()
        return when {
            title.contains("сеть") -> listOf(
                "sensor.pzem_energy_monitor_pzem_voltage",
                "sensor.pzem_energy_monitor_pzem_power"
            )
            title.contains("температура") -> listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia")
            else -> emptyList()
        }
    }

    /**
     * Возвращает список entity_id для развёрнутого режима.
     */
    private fun getExpandedSensorIds(tile: TileEntity): List<String> {
        try {
            val config = JSONObject(tile.config)
            val arr = config.optJSONArray("entity_ids")
            if (arr != null && arr.length() > 0) {
                val ids = mutableListOf<String>()
                for (i in 0 until arr.length()) ids.add(arr.getString(i))
                return ids
            }
        } catch (_: Exception) {}
        // По умолчанию
        val title = tile.title.lowercase()
        return when {
            title.contains("сеть") -> listOf(
                "sensor.pzem_energy_monitor_pzem_voltage",
                "sensor.pzem_energy_monitor_pzem_power",
                "sensor.pzem_energy_monitor_pzem_current",
                "sensor.pzem_energy_monitor_pzem_frequency",
                "sensor.pzem_energy_monitor_pzem_energy"
            )
            title.contains("температура") -> listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia")
            else -> emptyList()
        }
    }

    /**
     * Собирает текст виджета из заголовка и значений датчиков
     * с учётом пользовательских названий и точности.
     */
    /**
     * Строит содержимое виджета: заголовок + строки параметров.
     * Название параметра слева, значение справа, выровнены по самой длинной строке.
     */
    private fun buildSensorContent(tile: TileEntity, entityIds: List<String>, isExpanded: Boolean, container: android.widget.LinearLayout) {
        val sensorConfigs = loadSensorConfigs(tile, isExpanded)

        val rows = mutableListOf<Pair<String, String>>()
        var maxNameLen = 0

        for (eid in entityIds) {
            val rawValue = singleStates[eid] ?: "—"
            val config = sensorConfigs.find { it.entityId == eid }
            val displayName = config?.displayName ?: eid.substringAfterLast("_").replace("_", " ")
            
            // Определяем точность по умолчанию
            val defaultDecimals = if (eid.contains("temp") || eid.contains("freq")) 1 else if (eid.contains("current") || eid.contains("energy")) 2 else 0
            val decimals = config?.decimals ?: defaultDecimals

            val formattedValue = formatSensorValue(rawValue, decimals)
            rows.add(displayName to formattedValue)
            if (displayName.length > maxNameLen) maxNameLen = displayName.length
        }

        // Удаляем старые строки параметров (оставляем только заголовок)
        while (container.childCount > 1) {
            container.removeViewAt(1)
        }

        // Добавляем строки параметров
        val fontSize = tile.fontSize.toFloat()
        for ((name, value) in rows) {
            val paddedName = name.padEnd(maxNameLen, ' ')
            val rowView = android.widget.TextView(container.context).apply {
                text = "$paddedName  $value"
                textSize = fontSize
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
            }
            container.addView(rowView)
        }
    }

    /**
     * Загружает конфигурации датчиков из TileEntity.
     */
    private fun loadSensorConfigs(tile: TileEntity, isExpanded: Boolean): List<SensorConfig> {
        try {
            val config = JSONObject(tile.config)
            val key = if (isExpanded) "expanded_sensor_configs" else "collapsed_sensor_configs"
            val arr = config.optJSONArray(key)
            if (arr != null && arr.length() > 0) {
                val list = mutableListOf<SensorConfig>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(SensorConfig(
                        entityId = obj.getString("entity_id"),
                        displayName = obj.optString("display_name", obj.getString("entity_id").substringAfterLast("_")),
                        decimals = obj.optInt("decimals", 0)
                    ))
                }
                return list
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    /**
     * Форматирует значение датчика с заданной точностью.
     */
    private fun formatSensorValue(value: String, decimals: Int): String {
        val floatVal = value.toFloatOrNull() ?: return value
        return String.format("%.${decimals}f", floatVal)
    }

    private fun updateGridStatus() {
        gridOnline = (pzemVoltage.toFloatOrNull() ?: 0f) > 10f
    }

    private fun updateTilesForEntity(eid: String, st: String, attrs: JSONObject? = null) {
        if (st != "unknown") {
            singleStates[eid] = st
        }
        
        if (attrs != null && attrs.length() > 0) {
            val existing = entityAttributes[eid] ?: JSONObject()
            val keys = attrs.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                existing.put(key, attrs.get(key))
            }
            entityAttributes[eid] = existing
            
            // Если пришли новые атрибуты (например, прогноз), и этот виджет сейчас раскрыт — обновим его
            if (expandedSensorView != null && (expandedTile?.id == eid || eid.startsWith("weather."))) {
                val tag = expandedSensorView?.tag as? String
                val tile = tileManager.getAllTiles().find { it.id == tag }
                if (tile != null && tile.type == "weather") {
                    val container = expandedSensorView?.findViewWithTag<android.widget.LinearLayout>("sensor_text_container")
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
                pzemVoltage = formatFloat(st, 0); updateGridStatus(); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_power" -> {
                pzemPower = formatFloat(st, 0); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_current" -> {
                pzemCurrent = formatFloat(st, 2); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_energy" -> {
                pzemEnergy = formatFloat(st, 2); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_frequency" -> {
                pzemFrequency = formatFloat(st, 1); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia" -> {
                techRoomTemp = formatFloat(st, 1); updateTemperatureWidget()
            }
        }

        if (eid.startsWith("switch.")) updateSingleButtonColor(eid)
        if (eid.startsWith("weather.")) updateWeatherViews()
        if (eid.startsWith("sensor.")) {
            updateSensorDisplay()
            updateWeatherViews()
        }

        tileManager.getAllTiles().filter { it.type == "group" }.forEach { t ->
            try {
                val ids = JSONObject(t.config).optJSONArray("entity_ids") ?: return@forEach
                for (i in 0 until ids.length()) {
                    if (ids.getString(i) == eid) {
                        updateGroupState(t.id, eid, st); updateGroupButtonAppearance(t.id); break
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun formatFloat(v: String, d: Int): String {
        return v.toFloatOrNull()?.let { String.format("%.${d}f", it) } ?: "—"
    }

    // ==================== WEBSOCKET ====================

    private fun setupWebSocket() {
        val p = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val tkn = p.getString("ha_token", "") ?: ""
        if (tkn.isEmpty()) return

        val lh = p.getString("ha_local_host", "192.168.1.253:8123") ?: "192.168.1.253:8123"
        val rh = p.getString("ha_remote_host", "") ?: ""

        webSocket = HomeAssistantWebSocket(
            lh, tkn,
            { e, s, a -> runOnUiThread { updateTilesForEntity(e, s, a) } },
            { handler.postDelayed({ subscribeToNeededEntities() }, 2000L) },
            {
                if (rh.isNotEmpty()) {
                    webSocket = HomeAssistantWebSocket(
                        rh, tkn,
                        { e, s, a -> runOnUiThread { updateTilesForEntity(e, s, a) } },
                        { handler.postDelayed({ subscribeToNeededEntities() }, 2000L) }, {}, null
                    )
                    webSocket?.connect()
                }
            },
            { cacheEntities(it) }
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
        
        motionSensorId?.let { ids.add(it) }
        tileManager.getAllTiles().forEach { t ->
            try {
                val c = JSONObject(t.config)
                val eid = c.optString("entity_id", "").takeIf { it.isNotEmpty() }
                if (eid != null) {
                    ids.add(eid)
                    if (eid.startsWith("weather.")) weatherIds.add(eid)
                }
                
                c.optJSONArray("entity_ids")?.let {
                    for (i in 0 until it.length()) {
                        val subEid = it.getString(i)
                        ids.add(subEid)
                        if (subEid.startsWith("weather.")) weatherIds.add(subEid)
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
        
        if (ids.isNotEmpty()) webSocket?.subscribeEntities(ids.toList())
        
        // Подписываемся на прогнозы для всех погодных сущностей
        weatherIds.forEach { eid ->
            handler.postDelayed({
                webSocket?.subscribeForecast(eid)
            }, 3000L)
        }
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
