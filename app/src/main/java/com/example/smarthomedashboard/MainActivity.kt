package com.example.smarthomedashboard

import android.annotation.SuppressLint
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
import androidx.activity.result.contract.ActivityResultContracts
import com.example.smarthomedashboard.data.ColorRule
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import org.json.JSONArray

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

    // ==================== СОСТОЯНИЯ ====================
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var collapseChildrenRunnable: Runnable? = null

    private val groupStates = mutableMapOf<String, MutableMap<String, String>>()
    private val singleStates = mutableMapOf<String, String>()
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
        private const val DEFAULT_WIDGET_W = 220
        private const val DEFAULT_WIDGET_H = 180
        private const val DEFAULT_BUTTON_SIZE = 160
    }

    private val tileSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            refreshBottomPanel()
            subscribeToNeededEntities()
        }
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
        subscribeToNeededEntities()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        webSocket?.disconnect()
    }

    // ==================== ПОИСК СВОБОДНОГО МЕСТА ====================

    @Suppress("SameParameterValue")
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

    // ==================== PIN ====================

    private fun openWithPinCheck(action: () -> Unit) {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val lastAuth = prefs.getLong("last_auth_time", 0L)
        if (System.currentTimeMillis() - lastAuth > 60 * 60 * 1000) {
            PinDialog(this) {
                prefs.edit { putLong("last_auth_time", System.currentTimeMillis()) }
                action()
            }.show()
        } else action()
    }

    // ==================== РЕЖИМ РЕДАКТИРОВАНИЯ ====================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val hit = findViewAt(event.x, event.y)
            if (hit == null || hit == dimOverlay) {
                if (isEditMode) exitEditMode()
                else {
                    longPressRunnable = Runnable { openWithPinCheck { enterEditMode() } }
                    handler.postDelayed(longPressRunnable!!, 1000L)
                }
            }
        } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            longPressRunnable?.let { handler.removeCallbacks(it) }
        }
        return super.onTouchEvent(event)
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
            tileSettingsLauncher.launch(
                Intent(this, TileSettingsActivity::class.java).putExtra("tile_id", id)
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

        // Кнопка настроек
        val btnS = createSettingsButton().apply {
            layoutParams = FrameLayout.LayoutParams(DEFAULT_BUTTON_SIZE, DEFAULT_BUTTON_SIZE)
            x = (screenW - DEFAULT_BUTTON_SIZE - 20).toFloat()
            y = (screenH - DEFAULT_BUTTON_SIZE - 80).toFloat()
        }
        bottomPanel.addView(btnS)

        // Кнопка +
        if (isEditMode) {
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

        // Динамические цвета
        applyColorRules(tile, card)

        // Изменение размера
        var resizeStartX = 0f
        var resizeStartY = 0f
        @SuppressLint("ClickableViewAccessibility")
        resizeHandle.setOnTouchListener { v, event ->
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
                    card.requestLayout()
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
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
            buildSensorContent(tile, idsToShow, true, container)
        }

        // Измеряем новый размер виджета
        container.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val neededW = container.measuredWidth + 40
        val neededH = container.measuredHeight + 40

        // Вычисляем целевую позицию — к центру, но в пределах экрана
        var targetX = savedOriginalX - (neededW - savedOriginalWidth) / 2f
        var targetY = savedOriginalY - (neededH - savedOriginalHeight) / 2f
        if (targetX < 0) targetX = 8f
        if (targetX + neededW > screenW) targetX = (screenW - neededW - 8).toFloat()
        if (targetY < 0) targetY = 8f
        if (targetY + neededH > screenH - 60) targetY = (screenH - neededH - 60).toFloat()

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
        @SuppressLint("ClickableViewAccessibility")
        source.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
                collapseSensor()
            }
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

    @SuppressLint("ClickableViewAccessibility")
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
                        val runnable = Runnable {
                            isDragging = true
                            view.alpha = 0.6f
                            view.elevation = 20f
                        }
                        dragRunnable = runnable
                        handler.postDelayed(runnable, 500L)
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
            applyColorRules(tile, this)

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

    @SuppressLint("ClickableViewAccessibility")
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
                        val runnable = Runnable {
                            isDragging = true
                            view.alpha = 0.6f; view.elevation = 20f
                        }
                        pressRunnable = runnable
                        handler.postDelayed(runnable, 500L)
                    } else {
                        val runnable = Runnable {
                            if (expandedGroupId == tile.id) collapseChildButtons()
                            else expandChildButtons(tile, view as Button)
                        }
                        pressRunnable = runnable
                        handler.postDelayed(runnable, 1000L)
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

    @SuppressLint("ClickableViewAccessibility")
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
                    val runnable = Runnable {
                        isDragging = true
                        view.alpha = 0.6f; view.elevation = 20f
                    }
                    dragRunnable = runnable
                    handler.postDelayed(runnable, 500L)
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
                tileSettingsLauncher.launch(
                    Intent(this@MainActivity, TileSettingsActivity::class.java)
                        .putExtra("container", "grid")
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
        val tile = tileManager.getAllTiles().find { it.id == gid } ?: return
        for (i in 0 until bottomPanel.childCount) {
            val ch = bottomPanel.getChildAt(i)
            if (ch.tag == gid) {
                applyColorRules(tile, ch)
                return
            }
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
        for (i in 0 until bottomPanel.childCount) {
            val ch = bottomPanel.getChildAt(i)
            if (ch is Button) {
                val tile = (ch.tag as? String)?.let { tileManager.getAllTiles().find { t -> t.id == it } }
                if (tile != null && tile.type == "button") {
                    try {
                        val mainEid = JSONObject(tile.config).optString("entity_id", "")
                        if (mainEid == eid || tile.colorRules.contains(eid)) {
                            applyColorRules(tile, ch)
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

    private fun updatePzemWidget() { updateSensorDisplay() }
    private fun updateTemperatureWidget() { updateSensorDisplay() }

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
                if (container != null) {
                    buildSensorContent(tile, idsToShow, isExpanded, container)
                }

                // Динамические цвета (заменяет хардкод "⚡ Сеть")
                applyColorRules(tile, child)
            }
        }
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
        // По умолчанию
        return when (tile.title) {
            "⚡ Сеть" -> listOf(
                "sensor.pzem_energy_monitor_pzem_voltage",
                "sensor.pzem_energy_monitor_pzem_power"
            )
            "🌡️ Температура" -> listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia")
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
        return when (tile.title) {
            "⚡ Сеть" -> listOf(
                "sensor.pzem_energy_monitor_pzem_voltage",
                "sensor.pzem_energy_monitor_pzem_power",
                "sensor.pzem_energy_monitor_pzem_current",
                "sensor.pzem_energy_monitor_pzem_frequency",
                "sensor.pzem_energy_monitor_pzem_energy"
            )
            "🌡️ Температура" -> listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia")
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
    @SuppressLint("SetTextI18n")
    private fun buildSensorContent(tile: TileEntity, entityIds: List<String>, isExpanded: Boolean, container: android.widget.LinearLayout) {
        val sensorConfigs = loadSensorConfigs(tile, isExpanded)

        val rows = mutableListOf<Pair<String, String>>()
        var maxNameLen = 0

        for (eid in entityIds) {
            val rawValue = singleStates[eid] ?: "—"
            val config = sensorConfigs.find { it.entityId == eid }
            val displayName = config?.displayName ?: eid.substringAfterLast("_").replace("_", " ")
            val decimals = config?.decimals ?: 0
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
            val rowText = "$paddedName  $value"
            val rowView = android.widget.TextView(container.context).apply {
                text = rowText
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

    private fun updateTilesForEntity(eid: String, st: String) {
        singleStates[eid] = st

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

        if (eid.startsWith("switch.") || eid.startsWith("light.")) updateSingleButtonColor(eid)

        // Проверка правил цвета для всех плиток, использующих этот eid
        for (i in 0 until bottomPanel.childCount) {
            val view = bottomPanel.getChildAt(i)
            val tid = view.tag as? String ?: continue
            val tile = tileManager.getAllTiles().find { it.id == tid } ?: continue
            if (tile.colorRules.contains(eid)) {
                applyColorRules(tile, view)
            }
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

    // ==================== ДИНАМИЧЕСКИЕ ЦВЕТА ====================

    private fun applyColorRules(tile: TileEntity, view: View) {
        val rulesJson = tile.colorRules
        val rules = try {
            if (rulesJson.isEmpty() || rulesJson == "[]") emptyList()
            else {
                val arr = JSONArray(rulesJson)
                List(arr.length()) { i ->
                    val obj = arr.getJSONObject(i)
                    ColorRule(
                        obj.getString("entity_id"),
                        obj.getString("condition"),
                        obj.getString("value"),
                        obj.getString("color_hex")
                    )
                }
            }
        } catch (_: Exception) { emptyList() }

        var matchedColor: Int? = null
        for (rule in rules) {
            val currentState = singleStates[rule.entityId] ?: continue
            if (checkCondition(currentState, rule.condition, rule.value)) {
                matchedColor = try { Color.parseColor(rule.colorHex) } catch (_: Exception) { null }
                if (matchedColor != null) break
            }
        }

        if (matchedColor != null) {
            if (view is Button) view.background?.setTint(matchedColor)
            else view.setBackgroundColor(matchedColor)
        } else {
            // Дефолтные цвета, если правила не сработали
            val on = "#8033CC33".toColorInt()
            val off = "#424242".toColorInt()
            when (tile.type) {
                "sensor" -> view.setBackgroundColor("#80333333".toColorInt())
                "button" -> {
                    val eid = JSONObject(tile.config).optString("entity_id", "")
                    view.background?.setTint(if (singleStates[eid] == "on") on else off)
                }
                "group" -> view.background?.setTint(if (getGroupState(tile.id)) on else off)
            }
        }
    }

    private fun checkCondition(current: String, op: String, target: String): Boolean {
        val curNum = current.toDoubleOrNull()
        val tarNum = target.toDoubleOrNull()
        return if (curNum != null && tarNum != null) {
            when (op) {
                ">" -> curNum > tarNum
                "<" -> curNum < tarNum
                "==" -> curNum == tarNum
                "!=" -> curNum != tarNum
                ">=" -> curNum >= tarNum
                "<=" -> curNum <= tarNum
                else -> false
            }
        } else {
            when (op) {
                "==" -> current.equals(target, ignoreCase = true)
                "!=" -> !current.equals(target, ignoreCase = true)
                else -> false
            }
        }
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
            { e, s, _ -> runOnUiThread { updateTilesForEntity(e, s) } },
            { handler.postDelayed({ subscribeToNeededEntities() }, 2000L) },
            {
                if (rh.isNotEmpty()) {
                    webSocket = HomeAssistantWebSocket(
                        rh, tkn,
                        { e, s, _ -> runOnUiThread { updateTilesForEntity(e, s) } },
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
        tileManager.getAllTiles().forEach { t ->
            try {
                val c = JSONObject(t.config)
                c.optString("entity_id", "").takeIf { it.isNotEmpty() }?.let { ids.add(it) }
                c.optJSONArray("entity_ids")?.let {
                    for (i in 0 until it.length()) ids.add(it.getString(i))
                }
                // Датчики в виджете
                val coll = JSONArray(t.collapsedSensorIds)
                for (i in 0 until coll.length()) ids.add(coll.getString(i))

                // Сущности из правил цвета
                val rules = JSONArray(t.colorRules)
                for (i in 0 until rules.length()) {
                    ids.add(rules.getJSONObject(i).getString("entity_id"))
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
        if (ids.isNotEmpty()) webSocket?.subscribeEntities(ids.toList())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK) {
            refreshBottomPanel()
        }
    }
}