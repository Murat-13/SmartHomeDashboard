package com.example.smarthomedashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var widgetsGrid: RecyclerView
    private lateinit var bottomPanel: LinearLayout
    private lateinit var bottomScrollView: HorizontalScrollView
    private lateinit var overlayContainer: FrameLayout
    private lateinit var dimOverlay: View
    private lateinit var fabAddTile: FloatingActionButton
    private lateinit var gridAdapter: WidgetAdapter

    private lateinit var tileManager: TileManager
    private var webSocket: HomeAssistantWebSocket? = null

    private var pzemVoltage = "—"
    private var pzemCurrent = "—"
    private var pzemPower = "—"
    private var pzemEnergy = "—"
    private var pzemFrequency = "—"
    private var gridOnline = true
    private var techRoomTemp = "—"

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var collapseChildrenRunnable: Runnable? = null
    private var groupLongPressRunnable: Runnable? = null

    private val groupStates = mutableMapOf<String, MutableMap<String, String>>()
    private val singleStates = mutableMapOf<String, String>()
    private var isEditMode = false

    private var expandedChildButtons = mutableListOf<Button>()
    private var expandedGroupId: String? = null
    private var expandedSourceButton: Button? = null
    private var expandedTile: TileEntity? = null

    private val scrollResetRunnable = Runnable {
        bottomScrollView.smoothScrollTo(0, 0)
    }

    companion object {
        private const val REQUEST_TILE_SETTINGS = 100
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.activity_main)

        widgetsGrid = findViewById(R.id.widgetsGrid)
        bottomPanel = findViewById(R.id.bottomPanel)
        bottomScrollView = findViewById(R.id.bottomScrollView)
        overlayContainer = findViewById(R.id.overlayContainer)
        dimOverlay = findViewById(R.id.dimOverlay)
        fabAddTile = findViewById(R.id.fabAddTile)

        dimOverlay.setOnClickListener {
            collapseChildButtons()
        }

        tileManager = TileManager(this)

        setupGrid()
        setupBottomPanel()
        setupWebSocket()
        setupLongPressOnEmptySpace()
    }

    private fun openWithPinCheck(action: () -> Unit) {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val lastAuthTime = prefs.getLong("last_auth_time", 0L)
        if (System.currentTimeMillis() - lastAuthTime > 60 * 60 * 1000) {
            PinDialog(this) {
                prefs.edit { putLong("last_auth_time", System.currentTimeMillis()) }
                action()
            }.show()
        } else {
            action()
        }
    }

    private fun setupGrid() {
        widgetsGrid.layoutManager = GridLayoutManager(this, 4)
        if (tileManager.getTilesByContainer("grid").isEmpty()) {
            createDefaultGridTiles()
        }
        refreshGrid()
    }

    private fun refreshGrid() {
        val items = tileManager.getTilesByContainer("grid").map { it.toWidgetItem() }.toMutableList()
        gridAdapter = WidgetAdapter(
            context = this,
            widgets = items,
            overlayContainer = overlayContainer,
            recyclerView = widgetsGrid,
            onDataUpdate = {
                WidgetData(pzemVoltage, pzemCurrent, pzemPower, pzemEnergy, pzemFrequency, gridOnline)
            },
            onTileMoved = { from, to ->
                val tiles = tileManager.getTilesByContainer("grid").toMutableList()
                val moved = tiles.removeAt(from)
                tiles.add(to, moved)
                tiles.forEachIndexed { index, tile ->
                    val newTile = tile.copy(x = index % 4, y = index / 4)
                    tileManager.updateTile(newTile)
                }
            },
            isEditMode = isEditMode,
            onTileClick = { tileId ->
                if (isEditMode) {
                    openTileSettings(tileId)
                }
            },
            onTileLongClick = { _ -> }
        )
        widgetsGrid.adapter = gridAdapter
    }

    private fun setupBottomPanel() {
        refreshBottomPanel()
    }

    private fun refreshBottomPanel() {
        bottomPanel.removeAllViews()
        val tiles = tileManager.getTilesByContainer("bottom_panel")
        if (tiles.isEmpty()) {
            createDefaultButtonTiles()
            refreshBottomPanel()
            return
        }

        val buttonSize = 160
        val tilesList = tiles.toList()
        val screenWidth = resources.displayMetrics.widthPixels
        val maxButtonsPerRow = (screenWidth / (buttonSize + 12)) - 1

        val rows = mutableListOf<LinearLayout>()
        rows.add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        tilesList.forEach { tile ->
            val button = createTileButton(tile, buttonSize)
            var added = false
            for (row in rows) {
                if (row.childCount < maxButtonsPerRow) {
                    row.addView(button)
                    added = true
                    break
                }
            }
            if (!added) {
                val newRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                newRow.addView(button)
                rows.add(newRow)
            }
        }

        rows.forEach { row ->
            if (row.childCount > 0) {
                bottomPanel.addView(row)
            }
        }

        val btnSettings = createSettingsButton(buttonSize)
        val lastRow = rows.lastOrNull() ?: rows.first()

        if (lastRow.childCount < maxButtonsPerRow) {
            lastRow.addView(btnSettings)
        } else {
            val newRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            newRow.addView(btnSettings)
            bottomPanel.addView(newRow)
        }

        resetScrollWithDelay()
    }

    private fun createTileButton(tile: TileEntity, size: Int): Button {
        return Button(this).apply {
            text = tile.title
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(6, 6, 6, 6)
            }
            tag = tile.id
            alpha = 0.8f
            elevation = 8f
            background = resources.getDrawable(R.drawable.bg_button_rounded, null)

            if (tile.type == "group") {
                val isAnyOn = getGroupState(tile.id)
                background.setTint(if (isAnyOn) "#8033CC33".toColorInt() else "#80333333".toColorInt())
            } else {
                val entityId = JSONObject(tile.config).optString("entity_id", "")
                val state = singleStates[entityId] ?: "off"
                val isOn = state == "on"
                background.setTint(if (isOn) "#8033CC33".toColorInt() else "#424242".toColorInt())
            }
            setTextColor("#FFFFFF".toColorInt())
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            isAllCaps = false

            // === ОБРАБОТКА НАЖАТИЙ ДЛЯ ГРУПП ===
            if (tile.type == "group") {
                // Короткое нажатие — toggle всей группы
                setOnClickListener {
                    if (isEditMode) {
                        openTileSettings(tile.id)
                    } else {
                        if (expandedGroupId == tile.id) {
                            // Если дочерние раскрыты — сворачиваем
                            collapseChildButtons()
                        }
                        toggleGroup(tile.id, tile)
                    }
                }

                // Долгое нажатие (1 сек) — раскрытие дочерних
                setOnLongClickListener {
                    if (isEditMode) {
                        openTileSettings(tile.id)
                    } else {
                        if (expandedGroupId == tile.id) {
                            collapseChildButtons()
                        } else {
                            expandChildButtons(tile, this)
                        }
                    }
                    true
                }

                // Touch listener для отслеживания длительности нажатия
                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            groupLongPressRunnable = Runnable {
                                if (!isEditMode) {
                                    if (expandedGroupId == tile.id) {
                                        collapseChildButtons()
                                    } else {
                                        expandChildButtons(tile, this@apply)
                                    }
                                }
                            }
                            handler.postDelayed(groupLongPressRunnable!!, 1000L)
                            false
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            groupLongPressRunnable?.let { handler.removeCallbacks(it) }
                            false
                        }
                        else -> false
                    }
                }
            } else {
                // === ОБЫЧНЫЕ КНОПКИ ===
                setOnClickListener {
                    if (isEditMode) {
                        openTileSettings(tile.id)
                    } else {
                        val entityId = JSONObject(tile.config).optString("entity_id", "")
                        if (entityId.isNotEmpty()) {
                            val domain = entityId.substringBefore(".")
                            val currentState = singleStates[entityId] ?: "off"
                            val targetState = if (currentState == "on") "turn_off" else "turn_on"
                            webSocket?.callService(domain, targetState, entityId)
                            val newState = if (currentState == "on") "off" else "on"
                            singleStates[entityId] = newState
                            val isOn = newState == "on"
                            background.setTint(if (isOn) "#8033CC33".toColorInt() else "#424242".toColorInt())
                        }
                    }
                }

                setOnLongClickListener {
                    if (isEditMode) {
                        openTileSettings(tile.id)
                    }
                    true
                }
            }
        }
    }

    private fun createSettingsButton(size: Int): Button {
        return Button(this).apply {
            text = "⚙"
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(6, 6, 6, 6)
            }
            alpha = 0.8f
            elevation = 8f
            background = resources.getDrawable(R.drawable.bg_button_rounded, null)
            background.setTint("#424242".toColorInt())
            setTextColor("#FFFFFF".toColorInt())
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setOnClickListener {
                openWithPinCheck { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            }
        }
    }

    // ==================== ВЫЕЗЖАНИЕ ДОЧЕРНИХ КНОПОК ====================

    private fun expandChildButtons(tile: TileEntity, sourceButton: Button) {
        collapseChildButtons()

        val config = JSONObject(tile.config)
        val entityIds = config.optJSONArray("entity_ids")
        if (entityIds == null || entityIds.length() == 0) {
            Toast.makeText(this, "В группе нет устройств", Toast.LENGTH_SHORT).show()
            return
        }

        expandedGroupId = tile.id
        expandedSourceButton = sourceButton
        expandedTile = tile

        val buttonSize = 160
        val sourceLocation = IntArray(2)
        sourceButton.getLocationOnScreen(sourceLocation)

        val sourceCenterX = sourceLocation[0] + sourceButton.width / 2
        val sourceTop = sourceLocation[1]
        val screenWidth = resources.displayMetrics.widthPixels

        val childCount = entityIds.length()
        val maxPerRow = 5
        val itemsInFirstRow = if (childCount <= maxPerRow) childCount else (childCount + 1) / 2
        val itemsInSecondRow = childCount - itemsInFirstRow

        val totalWidthFirstRow = itemsInFirstRow * (buttonSize + 12)
        val totalWidthSecondRow = itemsInSecondRow * (buttonSize + 12)

        var firstRowStartX = sourceCenterX - totalWidthFirstRow / 2
        var secondRowStartX = sourceCenterX - totalWidthSecondRow / 2

        if (firstRowStartX < 0) firstRowStartX = 8
        if (firstRowStartX + totalWidthFirstRow > screenWidth) firstRowStartX = screenWidth - totalWidthFirstRow - 8
        if (secondRowStartX < 0) secondRowStartX = 8
        if (secondRowStartX + totalWidthSecondRow > screenWidth) secondRowStartX = screenWidth - totalWidthSecondRow - 8

        val rowsNeeded = if (childCount <= maxPerRow) 1 else 2
        val firstRowY = sourceTop - (buttonSize + 12) * rowsNeeded - 12
        val secondRowY = firstRowY + buttonSize + 12

        val actualFirstRowY = if (firstRowY < 50) sourceTop + sourceButton.height + 12 else firstRowY
        val actualSecondRowY = if (firstRowY < 50) actualFirstRowY + buttonSize + 12 else secondRowY

        expandedChildButtons.clear()

        val rootLayout = findViewById<FrameLayout>(android.R.id.content)

        for (i in 0 until childCount) {
            val entityId = entityIds.getString(i)
            val state = groupStates[tile.id]?.get(entityId) ?: "off"
            val isOn = state == "on"
            val names = config.optJSONArray("child_names")
            val name = if (names != null && i < names.length()) {
                names.getString(i)
            } else {
                (i + 1).toString()
            }

            val childButton = Button(this).apply {
                text = name
                layoutParams = FrameLayout.LayoutParams(buttonSize, buttonSize)
                alpha = 0f
                scaleX = 0.5f
                scaleY = 0.5f
                elevation = 12f
                background = resources.getDrawable(R.drawable.bg_button_rounded, null)
                background.setTint(if (isOn) "#8033CC33".toColorInt() else "#80333333".toColorInt())
                setTextColor("#FFFFFF".toColorInt())
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                isAllCaps = false
                tag = entityId

                setOnClickListener {
                    val domain = entityId.substringBefore(".")
                    val currentState = groupStates[tile.id]?.get(entityId) ?: "off"
                    val targetState = if (currentState == "on") "turn_off" else "turn_on"
                    webSocket?.callService(domain, targetState, entityId)
                    val newState = if (currentState == "on") "off" else "on"
                    background.setTint(if (newState == "on") "#8033CC33".toColorInt() else "#80333333".toColorInt())
                    updateGroupState(tile.id, entityId, newState)

                    // Сброс таймера сворачивания при касании
                    resetCollapseTimer()
                }
            }

            val row = if (i < itemsInFirstRow) 0 else 1
            val col = if (row == 0) i else i - itemsInFirstRow
            val x = if (row == 0) firstRowStartX + col * (buttonSize + 12) else secondRowStartX + col * (buttonSize + 12)
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
                .withEndAction {
                    rootLayout.removeView(button)
                }
                .start()
        }
        expandedChildButtons.clear()

        dimOverlay.animate().alpha(0f).setDuration(300).withEndAction {
            dimOverlay.isVisible = false
        }.start()

        collapseChildrenRunnable?.let { handler.removeCallbacks(it) }
    }

    // ==================== ГРУППОВЫЕ КНОПКИ (СОСТОЯНИЯ) ====================

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
        for (i in 0 until bottomPanel.childCount) {
            val childLayout = bottomPanel.getChildAt(i)
            if (childLayout is LinearLayout) {
                for (j in 0 until childLayout.childCount) {
                    val child = childLayout.getChildAt(j)
                    if (child is Button && child.tag == groupId) {
                        child.background.setTint(if (isAnyOn) "#8033CC33".toColorInt() else "#80333333".toColorInt())
                        return
                    }
                }
            }
        }
    }

    private fun toggleGroup(groupId: String, tile: TileEntity) {
        try {
            val config = JSONObject(tile.config)
            val entityIds = config.optJSONArray("entity_ids")

            if (entityIds != null && entityIds.length() > 0) {
                val isAnyOn = getGroupState(groupId)
                val targetState = if (isAnyOn) "turn_off" else "turn_on"

                for (i in 0 until entityIds.length()) {
                    val entityId = entityIds.getString(i)
                    val domain = entityId.substringBefore(".")
                    webSocket?.callService(domain, targetState, entityId)
                }
            }
        } catch (_: Exception) {
            Log.e("MainActivity", "Error toggling group")
        }
    }

    // ==================== ОБЫЧНЫЕ КНОПКИ ====================

    private fun updateSingleButtonColor(entityId: String) {
        val state = singleStates[entityId] ?: "off"
        val isOn = state == "on"
        val targetColor = if (isOn) "#8033CC33".toColorInt() else "#424242".toColorInt()

        for (i in 0 until bottomPanel.childCount) {
            val childLayout = bottomPanel.getChildAt(i)
            if (childLayout is LinearLayout) {
                for (j in 0 until childLayout.childCount) {
                    val child = childLayout.getChildAt(j)
                    if (child is Button) {
                        val tileId = child.tag as? String
                        val tile = tileId?.let { tileManager.getAllTiles().find { t -> t.id == it } }
                        if (tile != null && tile.type != "group") {
                            try {
                                val config = JSONObject(tile.config)
                                val btnEntityId = config.optString("entity_id", "")
                                if (btnEntityId == entityId) {
                                    child.background.setTint(targetColor)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    // ==================== РЕЖИМ РЕДАКТИРОВАНИЯ ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLongPressOnEmptySpace() {
        widgetsGrid.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val child = widgetsGrid.findChildViewUnder(event.x, event.y)
                if (child == null) {
                    if (isEditMode) {
                        exitEditMode()
                    } else {
                        longPressRunnable = Runnable {
                            openWithPinCheck { enterEditMode() }
                        }
                        handler.postDelayed(longPressRunnable!!, 1000L)
                    }
                }
            } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                widgetsGrid.performClick()
            }
            false
        }

        fabAddTile.setOnClickListener {
            hideAddTileButton()
            startActivityForResult(
                Intent(this, TileSettingsActivity::class.java).putExtra("container", "grid"),
                REQUEST_TILE_SETTINGS
            )
        }
    }

    private fun enterEditMode() {
        isEditMode = true
        gridAdapter.setEditMode(true)
        showAddTileButton()
        Toast.makeText(this, "Режим редактирования. Нажмите на плитку для настройки.", Toast.LENGTH_LONG).show()
    }

    private fun exitEditMode() {
        isEditMode = false
        gridAdapter.setEditMode(false)
        hideAddTileButton()
    }

    private fun showAddTileButton() {
        widgetsGrid.post {
            val tiles = tileManager.getTilesByContainer("grid")
            val position = tiles.size
            val spanCount = 4
            val row = position / spanCount
            val col = position % spanCount

            val firstView = widgetsGrid.getChildAt(0)
            if (firstView != null) {
                val tileWidth = firstView.width
                val tileHeight = firstView.height
                val x = col * tileWidth + tileWidth / 2f - fabAddTile.width / 2
                val y = row * tileHeight + tileHeight / 2f - fabAddTile.height / 2
                fabAddTile.x = x
                fabAddTile.y = y
            }

            fabAddTile.isVisible = true
            fabAddTile.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start()
        }
    }

    private fun hideAddTileButton() {
        fabAddTile.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(200).withEndAction {
            fabAddTile.isVisible = false
        }.start()
    }

    private fun openTileSettings(tileId: String) {
        openWithPinCheck {
            startActivityForResult(
                Intent(this, TileSettingsActivity::class.java).putExtra("tile_id", tileId),
                REQUEST_TILE_SETTINGS
            )
        }
    }

    private fun resetScrollWithDelay() {
        handler.removeCallbacks(scrollResetRunnable)
        handler.postDelayed(scrollResetRunnable, 10000L)
    }

    private fun createDefaultGridTiles() {
        lifecycleScope.launch {
            tileManager.addTile(TileEntity(UUID.randomUUID().toString(), "sensor", "grid", "⚡ Сеть", 0, 0, 1, 1, config = "{}"))
            tileManager.addTile(TileEntity(UUID.randomUUID().toString(), "sensor", "grid", "🌡️ Температура", 1, 0, 1, 1, config = "{}"))
        }
    }

    private fun createDefaultButtonTiles() {
        lifecycleScope.launch {
            tileManager.addTile(TileEntity(UUID.randomUUID().toString(), "button", "bottom_panel", "💡 Свет", 0, 0, 1, 1, config = """{"entity_id":"switch.sonoff_100288c9c3_1"}"""))
            tileManager.addTile(TileEntity(UUID.randomUUID().toString(), "button", "bottom_panel", "🔥 Бойлер", 1, 0, 1, 1, config = """{"entity_id":"switch.boiler"}"""))
        }
    }

    // ==================== ДАННЫЕ С ДАТЧИКОВ ====================

    private fun updatePzemWidget() {
        gridAdapter.updatePzemData(pzemVoltage, pzemCurrent, pzemPower, pzemEnergy, pzemFrequency, gridOnline)
    }

    private fun updateTemperatureWidget() {
        gridAdapter.updateTemperatureData(techRoomTemp)
    }

    private fun updateGridStatus() {
        gridOnline = (pzemVoltage.toFloatOrNull() ?: 0f) > 10f
    }

    private fun updateTilesForEntity(entityId: String, state: String) {
        singleStates[entityId] = state

        when (entityId) {
            "sensor.pzem_energy_monitor_pzem_voltage" -> {
                pzemVoltage = formatFloat(state, 0)
                updateGridStatus()
                updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_power" -> {
                pzemPower = formatFloat(state, 0)
                updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_current" -> {
                pzemCurrent = formatFloat(state, 2)
                updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_energy" -> {
                pzemEnergy = formatFloat(state, 2)
                updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_frequency" -> {
                pzemFrequency = formatFloat(state, 1)
                updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia" -> {
                techRoomTemp = formatFloat(state, 1)
                updateTemperatureWidget()
            }
            else -> {
                gridAdapter.updateWidgetByEntityId(entityId, formatFloat(state, 1))
            }
        }

        if (entityId.startsWith("switch.")) {
            updateSingleButtonColor(entityId)
        }

        tileManager.getAllTiles().filter { it.type == "group" }.forEach { tile ->
            try {
                val config = JSONObject(tile.config)
                val entityIds = config.optJSONArray("entity_ids")
                if (entityIds != null) {
                    for (i in 0 until entityIds.length()) {
                        if (entityIds.getString(i) == entityId) {
                            updateGroupState(tile.id, entityId, state)
                            updateGroupButtonAppearance(tile.id)
                            break
                        }
                    }
                }
            } catch (_: Exception) {}
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

        val localHost = prefs.getString("ha_local_host", "192.168.1.253:8123") ?: "192.168.1.253:8123"
        val remoteHost = prefs.getString("ha_remote_host", "") ?: ""

        webSocket = HomeAssistantWebSocket(
            host = localHost,
            token = token,
            onStateChanged = { entityId, state, _ -> runOnUiThread { updateTilesForEntity(entityId, state) } },
            onConnected = { handler.postDelayed({ subscribeToNeededEntities() }, 2000L) },
            onDisconnected = {
                if (remoteHost.isNotEmpty()) {
                    webSocket = HomeAssistantWebSocket(remoteHost, token, { e, s, _ -> runOnUiThread { updateTilesForEntity(e, s) } }, { handler.postDelayed({ subscribeToNeededEntities() }, 2000L) }, {}, null)
                    webSocket?.connect()
                }
            },
            onEntitiesList = { cacheEntities(it) }
        )
        webSocket?.connect()
    }

    private fun cacheEntities(entities: List<HaEntity>) {
        getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit {
            putString("cached_entities", com.google.gson.Gson().toJson(entities))
            putLong("cached_entities_time", System.currentTimeMillis())
        }
    }

    private fun subscribeToNeededEntities() {
        val entityIds = mutableSetOf<String>()
        tileManager.getAllTiles().forEach { tile ->
            try {
                val config = JSONObject(tile.config)
                config.optString("entity_id", "").takeIf { it.isNotEmpty() }?.let { entityIds.add(it) }
                config.optJSONArray("entity_ids")?.let { arr ->
                    for (i in 0 until arr.length()) entityIds.add(arr.getString(i))
                }
            } catch (_: Exception) {}
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

    override fun onResume() {
        super.onResume()
        refreshGrid()
        refreshBottomPanel()
        subscribeToNeededEntities()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(scrollResetRunnable)
        collapseChildrenRunnable?.let { handler.removeCallbacks(it) }
        groupLongPressRunnable?.let { handler.removeCallbacks(it) }
        webSocket?.disconnect()
    }
}

fun TileEntity.toWidgetItem(): WidgetItem {
    val configJson = JSONObject(config)
    return WidgetItem(
        title = title,
        value = "",
        entityId = configJson.optString("entity_id", ""),
        backgroundColor = if (title == "⚡ Сеть") "#8033CC33" else "#80333333",
        type = type,
        config = configJson.apply { put("id", id) }
    )
}