package com.example.smarthomedashboard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // ==================== UI ====================
    private lateinit var widgetsGrid: RecyclerView
    private lateinit var bottomPanel: FrameLayout
    private lateinit var overlayContainer: FrameLayout
    private lateinit var dimOverlay: View
    private lateinit var gridAdapter: WidgetAdapter

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
    private var groupLongPressRunnable: Runnable? = null

    private val groupStates = mutableMapOf<String, MutableMap<String, String>>()
    private val singleStates = mutableMapOf<String, String>()
    private var isEditMode = false

    private val expandedChildButtons = mutableListOf<Button>()
    private var expandedGroupId: String? = null
    private var expandedSourceButton: Button? = null
    private var expandedTile: TileEntity? = null

    // ==================== КОНСТАНТЫ ====================
    companion object {
        private const val REQUEST_TILE_SETTINGS = 100
    }

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_main)

        widgetsGrid = findViewById(R.id.widgetsGrid)
        bottomPanel = findViewById(R.id.bottomPanel)
        overlayContainer = findViewById(R.id.overlayContainer)
        dimOverlay = findViewById(R.id.dimOverlay)
        dimOverlay.setOnClickListener { collapseAll() }

        tileManager = TileManager(this)

        setupGrid()
        setupBottomPanel()
        setupWebSocket()
        setupLongPressOnEmptySpace()
    }

    override fun onResume() {
        super.onResume()
        refreshGrid()
        refreshBottomPanel()
        subscribeToNeededEntities()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        webSocket?.disconnect()
    }

    private fun openWithPinCheck(action: () -> Unit) {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val lastAuth = prefs.getLong("last_auth_time", 0L)
        if (System.currentTimeMillis() - lastAuth > 60 * 60 * 1000) {
            PinDialog(this) {
                prefs.edit { putLong("last_auth_time", System.currentTimeMillis()) }
                action()
            }.show()
        } else {
            action()
        }
    }

    // ==================== СЕТКА СЕНСОРОВ ====================

    private fun setupGrid() {
        widgetsGrid.layoutManager = GridLayoutManager(this, 4)
        if (tileManager.getTilesByContainer("grid").isEmpty()) {
            createDefaultGridTiles()
        }
        refreshGrid()
    }

    private fun refreshGrid() {
        val items = tileManager.getTilesByContainer("grid")
            .map { it.toWidgetItem() }
            .toMutableList()

        gridAdapter = WidgetAdapter(
            context = this,
            widgets = items,
            overlayContainer = overlayContainer,
            recyclerView = widgetsGrid,
            onDataUpdate = {
                WidgetData(
                    pzemVoltage, pzemCurrent, pzemPower,
                    pzemEnergy, pzemFrequency, gridOnline
                )
            },
            isEditMode = isEditMode,
            onTileClick = { tileId -> if (isEditMode) openTileSettings(tileId) }
        )
        widgetsGrid.adapter = gridAdapter
    }

    // ==================== КНОПКИ ====================

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

        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels

        tiles.forEach { tile ->
            val button = createTileButton(tile)
            val size = getButtonSize(tile)
            button.layoutParams = FrameLayout.LayoutParams(size, size)
            button.x = tile.x.toFloat().coerceIn(0f, (sw - size).toFloat())
            button.y = tile.y.toFloat().coerceIn(0f, (sh - size - 60).toFloat())
            bottomPanel.addView(button)
        }

        val btnSettings = createSettingsButton()
        btnSettings.layoutParams = FrameLayout.LayoutParams(160, 160)
        btnSettings.x = (sw - 180).toFloat()
        btnSettings.y = (sh - 240).toFloat()
        bottomPanel.addView(btnSettings)

        if (isEditMode) {
            val btnAdd = createAddButton()
            btnAdd.layoutParams = FrameLayout.LayoutParams(160, 160)
            btnAdd.x = (sw - 360).toFloat()
            btnAdd.y = (sh - 240).toFloat()
            bottomPanel.addView(btnAdd)
        }
    }

    private fun getButtonSize(tile: TileEntity): Int {
        return try {
            JSONObject(tile.config).optInt("button_size", 160)
        } catch (_: Exception) {
            160
        }
    }

    // ==================== СОЗДАНИЕ КНОПОК ====================

    private fun createTileButton(tile: TileEntity): Button {
        val buttonSize = getButtonSize(tile)
        val activeColor = "#8033CC33".toColorInt()
        val inactiveColor = "#424242".toColorInt()

        return Button(this).apply {
            text = tile.title
            layoutParams = FrameLayout.LayoutParams(buttonSize, buttonSize)
            tag = tile.id
            alpha = 0.8f
            elevation = 8f
            background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null)

            // Цвет
            if (tile.type == "group") {
                background?.setTint(if (getGroupState(tile.id)) activeColor else inactiveColor)
            } else {
                val eid = JSONObject(tile.config).optString("entity_id", "")
                background?.setTint(if (singleStates[eid] == "on") activeColor else inactiveColor)
            }

            setTextColor("#FFFFFF".toColorInt())
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            isAllCaps = false

            // ==================== ГРУППА ====================
            if (tile.type == "group") {
                var pressRunnable: Runnable? = null
                var startX = 0f
                var startY = 0f
                var hasMoved = false
                var dragging = false
                var viewStartX = 0f
                var viewStartY = 0f

                setOnTouchListener { view, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            hasMoved = false
                            dragging = false
                            startX = event.rawX
                            startY = event.rawY
                            viewStartX = view.x
                            viewStartY = view.y

                            if (isEditMode) {
                                pressRunnable = Runnable {
                                    dragging = true
                                    view.alpha = 0.6f
                                    view.elevation = 20f
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
                            if (Math.abs(event.rawX - startX) > 10 ||
                                Math.abs(event.rawY - startY) > 10
                            ) {
                                hasMoved = true
                                pressRunnable?.let { handler.removeCallbacks(it) }
                            }
                            if (isEditMode && dragging) {
                                view.x = (viewStartX + event.rawX - startX)
                                    .coerceIn(0f, (resources.displayMetrics.widthPixels - view.width).toFloat())
                                view.y = (viewStartY + event.rawY - startY)
                                    .coerceIn(0f, (resources.displayMetrics.heightPixels - view.height - 60).toFloat())
                            }
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            pressRunnable?.let { handler.removeCallbacks(it) }

                            if (isEditMode && dragging && hasMoved) {
                                view.alpha = 1.0f
                                view.elevation = 8f
                                saveButtonPosition(tile.id, view.x.toInt(), view.y.toInt())
                                dragging = false
                            } else if (isEditMode && !hasMoved) {
                                openTileSettings(tile.id)
                            } else if (!isEditMode && !hasMoved) {
                                if (expandedGroupId != tile.id) {
                                    toggleGroup(tile.id, tile)
                                }
                            }
                            true
                        }

                        else -> false
                    }
                }
            }

            // ==================== ОБЫЧНАЯ КНОПКА ====================
            if (tile.type != "group") {
                setOnClickListener {
                    if (!isEditMode) {
                        val eid = JSONObject(tile.config).optString("entity_id", "")
                        if (eid.isNotEmpty()) {
                            val domain = eid.substringBefore(".")
                            val cs = singleStates[eid] ?: "off"
                            val ts = if (cs == "on") "turn_off" else "turn_on"
                            webSocket?.callService(domain, ts, eid)
                            val ns = if (cs == "on") "off" else "on"
                            singleStates[eid] = ns
                            background?.setTint(if (ns == "on") activeColor else inactiveColor)
                        }
                    }
                }

                var dragStartX = 0f
                var dragStartY = 0f
                var viewStartX = 0f
                var viewStartY = 0f
                var isDragging = false
                var hasMoved = false
                var dragRunnable: Runnable? = null

                setOnTouchListener { view, event ->
                    if (!isEditMode) return@setOnTouchListener false

                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            hasMoved = false
                            dragStartX = event.rawX
                            dragStartY = event.rawY
                            viewStartX = view.x
                            viewStartY = view.y
                            isDragging = false
                            dragRunnable = Runnable {
                                isDragging = true
                                view.alpha = 0.6f
                                view.elevation = 20f
                            }
                            handler.postDelayed(dragRunnable!!, 500L)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (Math.abs(event.rawX - dragStartX) > 10 ||
                                Math.abs(event.rawY - dragStartY) > 10
                            ) {
                                hasMoved = true
                                dragRunnable?.let { handler.removeCallbacks(it) }
                            }
                            if (isDragging) {
                                view.x = (viewStartX + event.rawX - dragStartX)
                                    .coerceIn(0f, (resources.displayMetrics.widthPixels - view.width).toFloat())
                                view.y = (viewStartY + event.rawY - dragStartY)
                                    .coerceIn(0f, (resources.displayMetrics.heightPixels - view.height - 60).toFloat())
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            dragRunnable?.let { handler.removeCallbacks(it) }
                            if (isDragging && hasMoved) {
                                view.alpha = 1.0f
                                view.elevation = 8f
                                saveButtonPosition(tile.id, view.x.toInt(), view.y.toInt())
                                isDragging = false
                            } else if (!hasMoved) {
                                openTileSettings(tile.id)
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
        }
    }

    private fun saveButtonPosition(id: String, x: Int, y: Int) {
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

    // ==================== ВЫЕЗЖАНИЕ ДОЧЕРНИХ КНОПОК ====================

    private fun expandChildButtons(tile: TileEntity, sourceButton: Button) {
        collapseChildButtons()

        val config = JSONObject(tile.config)
        val entityIds = config.optJSONArray("entity_ids") ?: return

        expandedGroupId = tile.id
        expandedSourceButton = sourceButton
        expandedTile = tile

        val buttonSize = getButtonSize(tile)
        val screenWidth = resources.displayMetrics.widthPixels

        val sourceLocation = IntArray(2)
        sourceButton.getLocationOnScreen(sourceLocation)
        val centerX = sourceLocation[0] + sourceButton.width / 2
        val top = sourceLocation[1]

        val childCount = entityIds.length()
        val maxPerRow = 5
        val firstRowCount = if (childCount <= maxPerRow) childCount else (childCount + 1) / 2
        val secondRowCount = childCount - firstRowCount

        var firstRowStart = centerX - firstRowCount * (buttonSize + 12) / 2
        var secondRowStart = centerX - secondRowCount * (buttonSize + 12) / 2
        if (firstRowStart < 0) firstRowStart = 8
        if (firstRowStart + firstRowCount * (buttonSize + 12) > screenWidth)
            firstRowStart = screenWidth - firstRowCount * (buttonSize + 12) - 8
        if (secondRowStart < 0) secondRowStart = 8
        if (secondRowStart + secondRowCount * (buttonSize + 12) > screenWidth)
            secondRowStart = screenWidth - secondRowCount * (buttonSize + 12) - 8

        val rowsNeeded = if (childCount <= maxPerRow) 1 else 2
        val firstRowY = top - (buttonSize + 12) * rowsNeeded - 12
        val secondRowY = firstRowY + buttonSize + 12
        val actualFirstRowY = if (firstRowY < 50) top + sourceButton.height + 12 else firstRowY
        val actualSecondRowY = if (firstRowY < 50) actualFirstRowY + buttonSize + 12 else secondRowY

        expandedChildButtons.clear()
        val rootLayout = findViewById<FrameLayout>(android.R.id.content)

        for (i in 0 until childCount) {
            val entityId = entityIds.getString(i)
            val state = groupStates[tile.id]?.get(entityId) ?: "off"
            val names = config.optJSONArray("child_names")
            val name = if (names != null && i < names.length()) names.getString(i) else (i + 1).toString()

            val childButton = Button(this).apply {
                text = name
                tag = entityId
                layoutParams = FrameLayout.LayoutParams(buttonSize, buttonSize)
                alpha = 0f
                scaleX = 0.5f
                scaleY = 0.5f
                elevation = 12f
                background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null)
                background?.setTint(if (state == "on") "#8033CC33".toColorInt() else "#424242".toColorInt())
                setTextColor("#FFFFFF".toColorInt())
                textSize = 14f
                gravity = Gravity.CENTER
                isAllCaps = false

                setOnClickListener {
                    val domain = entityId.substringBefore(".")
                    val currentState = groupStates[tile.id]?.get(entityId) ?: "off"
                    val targetState = if (currentState == "on") "turn_off" else "turn_on"
                    webSocket?.callService(domain, targetState, entityId)
                    val newState = if (currentState == "on") "off" else "on"
                    background?.setTint(
                        if (newState == "on") "#8033CC33".toColorInt() else "#424242".toColorInt()
                    )
                    updateGroupState(tile.id, entityId, newState)
                    resetCollapseTimer()
                }
            }

            val row = if (i < firstRowCount) 0 else 1
            val col = if (row == 0) i else i - firstRowCount
            val x = if (row == 0)
                firstRowStart + col * (buttonSize + 12)
            else
                secondRowStart + col * (buttonSize + 12)
            val y = if (row == 0) actualFirstRowY else actualSecondRowY

            childButton.x = x.toFloat()
            childButton.y = y.toFloat()

            rootLayout.addView(childButton)
            expandedChildButtons.add(childButton)

            childButton.animate()
                .alpha(0.8f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(250)
                .setStartDelay(i * 50L)
                .setInterpolator(DecelerateInterpolator())
                .start()
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
        expandedGroupId = null
        expandedSourceButton = null
        expandedTile = null

        val rootLayout = findViewById<FrameLayout>(android.R.id.content)
        expandedChildButtons.forEachIndexed { index, button ->
            button.animate()
                .alpha(0f)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setDuration(200)
                .setStartDelay(index * 30L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { rootLayout.removeView(button) }
                .start()
        }
        expandedChildButtons.clear()

        dimOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { dimOverlay.isVisible = false }
            .start()

        collapseChildrenRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun collapseAll() {
        collapseChildButtons()
    }

    // ==================== ГРУППЫ ====================

    private fun updateGroupState(groupId: String, entityId: String, state: String) {
        if (!groupStates.containsKey(groupId)) {
            groupStates[groupId] = mutableMapOf()
        }
        groupStates[groupId]?.put(entityId, state)
        updateGroupButtonAppearance(groupId)
    }

    private fun getGroupState(groupId: String): Boolean {
        return groupStates[groupId]?.values?.any { it == "on" } ?: false
    }

    private fun updateGroupButtonAppearance(groupId: String) {
        val isAnyOn = getGroupState(groupId)
        val targetColor = if (isAnyOn) "#8033CC33".toColorInt() else "#424242".toColorInt()

        for (i in 0 until bottomPanel.childCount) {
            val child = bottomPanel.getChildAt(i)
            if (child is Button && child.tag == groupId) {
                child.background?.setTint(targetColor)
                return
            }
        }
    }

    private fun toggleGroup(groupId: String, tile: TileEntity) {
        try {
            val config = JSONObject(tile.config)
            val entityIds = config.optJSONArray("entity_ids") ?: return
            val targetState = if (getGroupState(groupId)) "turn_off" else "turn_on"

            for (i in 0 until entityIds.length()) {
                val entityId = entityIds.getString(i)
                val domain = entityId.substringBefore(".")
                webSocket?.callService(domain, targetState, entityId)
            }
        } catch (_: Exception) { }
    }

    // ==================== ОБЫЧНЫЕ КНОПКИ ====================

    private fun updateSingleButtonColor(entityId: String) {
        val state = singleStates[entityId] ?: "off"
        val isOn = state == "on"
        val targetColor = if (isOn) "#8033CC33".toColorInt() else "#424242".toColorInt()

        for (i in 0 until bottomPanel.childCount) {
            val child = bottomPanel.getChildAt(i)
            if (child is Button) {
                val tileId = child.tag as? String
                val tile = tileId?.let { tileManager.getAllTiles().find { t -> t.id == it } }
                if (tile != null && tile.type != "group") {
                    try {
                        val config = JSONObject(tile.config)
                        if (config.optString("entity_id", "") == entityId) {
                            child.background?.setTint(targetColor)
                        }
                    } catch (_: Exception) { }
                }
            }
        }
    }

    // ==================== РЕЖИМ РЕДАКТИРОВАНИЯ ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLongPressOnEmptySpace() {
        widgetsGrid.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (widgetsGrid.findChildViewUnder(event.x, event.y) == null) {
                    if (isEditMode) exitEditMode()
                    else {
                        longPressRunnable = Runnable { openWithPinCheck { enterEditMode() } }
                        handler.postDelayed(longPressRunnable!!, 1000L)
                    }
                }
            } else if (event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL
            ) {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                widgetsGrid.performClick()
            }
            false
        }
    }

    private fun enterEditMode() {
        isEditMode = true
        gridAdapter.setEditMode(true)
        refreshBottomPanel()
        Toast.makeText(this, "Режим редактирования", Toast.LENGTH_SHORT).show()
    }

    private fun exitEditMode() {
        isEditMode = false
        gridAdapter.setEditMode(false)
        refreshBottomPanel()
    }

    private fun openTileSettings(tileId: String) {
        openWithPinCheck {
            startActivityForResult(
                Intent(this, TileSettingsActivity::class.java).putExtra("tile_id", tileId),
                REQUEST_TILE_SETTINGS
            )
        }
    }

    // ==================== ДАННЫЕ ПО УМОЛЧАНИЮ ====================

    private fun createDefaultGridTiles() {
        lifecycleScope.launch {
            tileManager.addTile(
                TileEntity(UUID.randomUUID().toString(), "sensor", "grid",
                    "⚡ Сеть", 0, 0, 1, 1, config = "{}")
            )
            tileManager.addTile(
                TileEntity(UUID.randomUUID().toString(), "sensor", "grid",
                    "🌡️ Температура", 1, 0, 1, 1, config = "{}")
            )
        }
    }

    private fun createDefaultButtonTiles() {
        val screenH = resources.displayMetrics.heightPixels
        lifecycleScope.launch {
            tileManager.addTile(
                TileEntity(UUID.randomUUID().toString(), "button", "bottom_panel",
                    "💡 Свет", 20, screenH - 250, 1, 1,
                    config = """{"entity_id":"switch.sonoff_100288c9c3_1","button_size":160}""")
            )
            tileManager.addTile(
                TileEntity(UUID.randomUUID().toString(), "button", "bottom_panel",
                    "🔥 Бойлер", 200, screenH - 250, 1, 1,
                    config = """{"entity_id":"switch.boiler","button_size":160}""")
            )
        }
    }

    // ==================== ОБНОВЛЕНИЕ ДАННЫХ ====================

    private fun updatePzemWidget() {
        gridAdapter.updatePzemData(
            pzemVoltage, pzemCurrent, pzemPower,
            pzemEnergy, pzemFrequency, gridOnline
        )
    }

    private fun updateTemperatureWidget() {
        gridAdapter.updateTemperatureData(techRoomTemp)
        gridAdapter.updateWidgetByEntityId(
            "sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia",
            techRoomTemp
        )
    }

    private fun updateGridStatus() {
        gridOnline = (pzemVoltage.toFloatOrNull() ?: 0f) > 10f
    }

    private fun updateTilesForEntity(entityId: String, state: String) {
        singleStates[entityId] = state
        val formatted = formatFloat(state, 1)

        when (entityId) {
            "sensor.pzem_energy_monitor_pzem_voltage" -> {
                pzemVoltage = formatFloat(state, 0)
                updateGridStatus()
                updatePzemWidget()
                gridAdapter.updateWidgetByEntityId(entityId, pzemVoltage)
            }
            "sensor.pzem_energy_monitor_pzem_power" -> {
                pzemPower = formatFloat(state, 0)
                updatePzemWidget()
                gridAdapter.updateWidgetByEntityId(entityId, pzemPower)
            }
            "sensor.pzem_energy_monitor_pzem_current" -> {
                pzemCurrent = formatFloat(state, 2)
                updatePzemWidget()
                gridAdapter.updateWidgetByEntityId(entityId, pzemCurrent)
            }
            "sensor.pzem_energy_monitor_pzem_energy" -> {
                pzemEnergy = formatFloat(state, 2)
                updatePzemWidget()
                gridAdapter.updateWidgetByEntityId(entityId, pzemEnergy)
            }
            "sensor.pzem_energy_monitor_pzem_frequency" -> {
                pzemFrequency = formatFloat(state, 1)
                updatePzemWidget()
                gridAdapter.updateWidgetByEntityId(entityId, pzemFrequency)
            }
            "sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia" -> {
                techRoomTemp = formatFloat(state, 1)
                updateTemperatureWidget()
                gridAdapter.updateWidgetByEntityId(entityId, techRoomTemp)
            }
            else -> {
                gridAdapter.updateWidgetByEntityId(entityId, formatted)
            }
        }

        if (entityId.startsWith("switch.")) {
            updateSingleButtonColor(entityId)
        }

        tileManager.getAllTiles()
            .filter { it.type == "group" }
            .forEach { tile ->
                try {
                    val config = JSONObject(tile.config)
                    val entityIds = config.optJSONArray("entity_ids") ?: return@forEach
                    for (i in 0 until entityIds.length()) {
                        if (entityIds.getString(i) == entityId) {
                            updateGroupState(tile.id, entityId, state)
                            updateGroupButtonAppearance(tile.id)
                            break
                        }
                    }
                } catch (_: Exception) { }
            }
    }

    private fun formatFloat(value: String, decimals: Int): String {
        return value.toFloatOrNull()?.let { String.format("%.${decimals}f", it) } ?: "—"
    }

    // ==================== WEBSOCKET ====================

    private fun setupWebSocket() {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val token = prefs.getString("ha_token", "") ?: ""
        if (token.isEmpty()) return

        val localHost = prefs.getString("ha_local_host", "192.168.1.253:8123")
            ?: "192.168.1.253:8123"
        val remoteHost = prefs.getString("ha_remote_host", "") ?: ""

        webSocket = HomeAssistantWebSocket(
            host = localHost,
            token = token,
            onStateChanged = { entityId, state, _ ->
                runOnUiThread { updateTilesForEntity(entityId, state) }
            },
            onConnected = { handler.postDelayed({ subscribeToNeededEntities() }, 2000L) },
            onDisconnected = {
                if (remoteHost.isNotEmpty()) {
                    webSocket = HomeAssistantWebSocket(
                        host = remoteHost, token = token,
                        onStateChanged = { e, s, _ -> runOnUiThread { updateTilesForEntity(e, s) } },
                        onConnected = { handler.postDelayed({ subscribeToNeededEntities() }, 2000L) },
                        onDisconnected = {}, onEntitiesList = null
                    )
                    webSocket?.connect()
                }
            },
            onEntitiesList = { entities -> cacheEntities(entities) }
        )
        webSocket?.connect()
    }

    private fun cacheEntities(entities: List<HaEntity>) {
        val json = com.google.gson.Gson().toJson(entities)
        getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit {
            putString("cached_entities", json)
            putLong("cached_entities_time", System.currentTimeMillis())
        }
    }

    private fun subscribeToNeededEntities() {
        val entityIds = mutableSetOf<String>()
        tileManager.getAllTiles().forEach { tile ->
            try {
                val config = JSONObject(tile.config)
                config.optString("entity_id", "").takeIf { it.isNotEmpty() }
                    ?.let { entityIds.add(it) }
                config.optJSONArray("entity_ids")?.let { arr ->
                    for (i in 0 until arr.length()) entityIds.add(arr.getString(i))
                }
            } catch (_: Exception) { }
        }
        if (tileManager.getAllTiles().any { it.title == "⚡ Сеть" }) {
            entityIds.add("sensor.pzem_energy_monitor_pzem_voltage")
            entityIds.add("sensor.pzem_energy_monitor_pzem_power")
            entityIds.add("sensor.pzem_energy_monitor_pzem_current")
            entityIds.add("sensor.pzem_energy_monitor_pzem_energy")
            entityIds.add("sensor.pzem_energy_monitor_pzem_frequency")
        }
        if (entityIds.isNotEmpty()) webSocket?.subscribeEntities(entityIds.toList())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TILE_SETTINGS && resultCode == Activity.RESULT_OK) {
            refreshGrid()
            refreshBottomPanel()
            subscribeToNeededEntities()
        }
    }
}

fun TileEntity.toWidgetItem(): WidgetItem {
    val configJson = JSONObject(config)
    var entityId = configJson.optString("entity_id", "")
    if (entityId.isEmpty()) {
        val arr = configJson.optJSONArray("entity_ids")
        if (arr != null && arr.length() > 0) entityId = arr.getString(0)
    }
    return WidgetItem(
        title = title, value = "", entityId = entityId,
        backgroundColor = if (title == "⚡ Сеть") "#8033CC33" else "#80333333",
        type = type, config = configJson.apply { put("id", id) }
    )
}