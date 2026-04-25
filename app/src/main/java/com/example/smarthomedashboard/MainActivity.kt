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

        val textView = android.widget.TextView(this).apply {
            tag = "sensor_text"
            text = tile.title
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        card.addView(textView)

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
                        .coerceIn(100, screenH - card.y.toInt() - 80)
                    card.layoutParams.width = newW
                    card.layoutParams.height = newH
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

    // ==================== РАСКРЫТИЕ / СВОРАЧИВАНИЕ СЕНСОРА ====================

    private fun expandSensor(tile: TileEntity, source: View) {
        savedOriginalWidth = source.layoutParams.width
        savedOriginalHeight = source.layoutParams.height
        savedOriginalX = source.x
        savedOriginalY = source.y

        val contentText = when (tile.title) {
            "⚡ Сеть" -> "⚡ Сеть\n\n$pzemVoltage V\n$pzemPower W\n$pzemCurrent A\n$pzemFrequency Hz\n$pzemEnergy kWh"
            "🌡️ Температура" -> "🌡️ Температура\n\n${techRoomTemp}°C"
            else -> "${tile.title}\n\n—"
        }

        val measureText = android.widget.TextView(this).apply {
            text = contentText
            textSize = 20f
            setPadding(32, 32, 32, 32)
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        val textWidth = (screenW * 0.85).toInt()
        measureText.layoutParams = FrameLayout.LayoutParams(textWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
        measureText.measure(
            View.MeasureSpec.makeMeasureSpec(textWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val neededW = measureText.measuredWidth + 80
        val neededH = measureText.measuredHeight + 80

        var targetX = savedOriginalX - (neededW - savedOriginalWidth) / 2f
        var targetY = savedOriginalY - (neededH - savedOriginalHeight) / 2f
        if (targetX < 0) targetX = 8f
        if (targetX + neededW > screenW) targetX = (screenW - neededW - 8).toFloat()
        if (targetY < 0) targetY = 8f
        if (targetY + neededH > screenH - 60) targetY = (screenH - neededH - 60).toFloat()

        val tv = source.findViewWithTag<android.widget.TextView>("sensor_text")
        tv?.text = contentText
        tv?.textSize = 20f
        tv?.setShadowLayer(4f, 2f, 2f, Color.BLACK)

        source.animate()
            .x(targetX)
            .y(targetY)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                source.layoutParams.width = neededW
                source.layoutParams.height = neededH
                source.requestLayout()
            }
            .start()

        expandedSensorView = source
        expandedSensorSource = source

        source.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) collapseSensor()
            true
        }

        sensorCollapseRunnable = Runnable { collapseSensor() }
        handler.postDelayed(sensorCollapseRunnable!!, 15000L)
    }

    private fun collapseSensor() {
        val source = expandedSensorView ?: return
        val tv = source.findViewWithTag<android.widget.TextView>("sensor_text")
        val tile = tileManager.getAllTiles().find { it.id == source.tag as? String } ?: return

        tv?.text = tile.title
        tv?.textSize = 16f
        tv?.setShadowLayer(0f, 0f, 0f, 0)

        source.animate()
            .x(savedOriginalX)
            .y(savedOriginalY)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                source.layoutParams.width = savedOriginalWidth
                source.layoutParams.height = savedOriginalHeight
                source.requestLayout()
            }
            .start()

        setupSensorTouch(source, tile)

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
                        handler.postDelayed(dragRunnable!!, 500L)
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
                        isEditMode && isDragging && hasMoved -> {
                            view.alpha = 1.0f; view.elevation = 8f
                            savePosition(tile.id, view.x.toInt(), view.y.toInt())
                        }
                        isEditMode && !hasMoved -> openTileSettings(tile.id)
                        !isEditMode && !hasMoved -> expandSensor(tile, view)
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
                        handler.postDelayed(pressRunnable!!, 500L)
                    } else {
                        pressRunnable = Runnable {
                            if (expandedGroupId == tile.id) collapseChildButtons()
                            else expandChildButtons(tile, view as Button)
                        }
                        handler.postDelayed(pressRunnable!!, 1000L)
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
                    handler.postDelayed(dragRunnable!!, 500L)
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
                TileEntity(UUID.randomUUID().toString(), "sensor", "bottom_panel",
                    "⚡ Сеть", x1, y1, 1, 1,
                    config = """{"button_size":$DEFAULT_WIDGET_W}""")
            )
            val (x2, y2) = findFreeSpot(DEFAULT_WIDGET_W, DEFAULT_WIDGET_H)
            tileManager.addTile(
                TileEntity(UUID.randomUUID().toString(), "sensor", "bottom_panel",
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

            if (child is FrameLayout && child.childCount > 0) {
                val tv = child.getChildAt(0) as? android.widget.TextView ?: continue
                when (tile.title) {
                    "⚡ Сеть" -> {
                        val isOnline = (pzemVoltage.toFloatOrNull() ?: 0f) > 10f
                        tv.text = "⚡ Сеть\n${pzemVoltage}V"
                        child.setBackgroundColor(
                            if (isOnline) "#8033CC33".toColorInt() else "#80FF3333".toColorInt()
                        )
                    }
                    "🌡️ Температура" -> {
                        tv.text = "🌡️ Температура\n${techRoomTemp}°C"
                    }
                }
            }
        }
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

        if (eid.startsWith("switch.")) updateSingleButtonColor(eid)

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
        if (requestCode == REQUEST_TILE_SETTINGS && resultCode == Activity.RESULT_OK) {
            refreshBottomPanel()
            subscribeToNeededEntities()
        }
    }
}