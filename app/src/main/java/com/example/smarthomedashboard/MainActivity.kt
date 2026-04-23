package com.example.smarthomedashboard

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
    private lateinit var overlayContainer: FrameLayout
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

    private val groupStates = mutableMapOf<String, MutableMap<String, String>>()
    private val singleStates = mutableMapOf<String, String>()
    private var isEditMode = false

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
        overlayContainer = findViewById(R.id.overlayContainer)
        fabAddTile = findViewById(R.id.fabAddTile)

        tileManager = TileManager(this)

        setupGrid()
        setupBottomPanel()
        setupWebSocket()
        setupLongPressOnEmptySpace()
    }

    private fun openWithPinCheck(action: () -> Unit) {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val lastAuthTime = prefs.getLong("last_auth_time", 0)
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
                    val spanCount = 4
                    val newTile = tile.copy(x = index % spanCount, y = index / spanCount)
                    tileManager.updateTile(newTile)
                }
            },
            isEditMode = isEditMode,
            onTileClick = { tileId ->
                if (isEditMode) {
                    openTileSettings(tileId)
                }
            },
            onTileLongClick = { tileId ->
                val tile = tileManager.getAllTiles().find { it.id == tileId }
                if (tile?.type == "group" && !isEditMode) {
                    showGroupDialog(tile)
                }
            }
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
        tiles.forEach { tile ->
            val button = Button(this).apply {
                text = tile.title
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(6, 6, 6, 6)
                }
                tag = tile.id

                if (tile.type == "group") {
                    val isAnyOn = getGroupState(tile.id)
                    setBackgroundColor(if (isAnyOn) "#8033CC33".toColorInt() else "#80333333".toColorInt())
                } else {
                    val entityId = JSONObject(tile.config).optString("entity_id", "")
                    val state = singleStates[entityId] ?: "off"
                    val isOn = state == "on"
                    setBackgroundColor(if (isOn) "#8033CC33".toColorInt() else "#424242".toColorInt())
                }
                setTextColor("#FFFFFF".toColorInt())

                setOnClickListener {
                    if (isEditMode) {
                        openTileSettings(tile.id)
                    } else {
                        if (tile.type == "group") {
                            toggleGroup(tile.id, tile)
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
                                setBackgroundColor(if (isOn) "#8033CC33".toColorInt() else "#424242".toColorInt())
                            }
                        }
                    }
                }

                setOnLongClickListener {
                    if (!isEditMode && tile.type == "group") {
                        showGroupDialog(tile)
                    }
                    true
                }
            }
            bottomPanel.addView(button)
        }
    }

    private fun openTileSettings(tileId: String) {
        openWithPinCheck {
            startActivityForResult(
                Intent(this, TileSettingsActivity::class.java).putExtra("tile_id", tileId),
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

    private fun createDefaultGridTiles() {
        lifecycleScope.launch {
            tileManager.addTile(
                TileEntity(
                    id = UUID.randomUUID().toString(),
                    type = "sensor",
                    container = "grid",
                    title = "⚡ Сеть",
                    x = 0,
                    y = 0,
                    width = 1,
                    height = 1,
                    config = "{}"
                )
            )
            tileManager.addTile(
                TileEntity(
                    id = UUID.randomUUID().toString(),
                    type = "sensor",
                    container = "grid",
                    title = "🌡️ Температура",
                    x = 1,
                    y = 0,
                    width = 1,
                    height = 1,
                    config = "{}"
                )
            )
        }
    }

    private fun createDefaultButtonTiles() {
        lifecycleScope.launch {
            tileManager.addTile(
                TileEntity(
                    id = UUID.randomUUID().toString(),
                    type = "button",
                    container = "bottom_panel",
                    title = "💡 Свет",
                    x = 0,
                    y = 0,
                    width = 1,
                    height = 1,
                    config = """{"entity_id":"switch.sonoff_100288c9c3_1"}"""
                )
            )
            tileManager.addTile(
                TileEntity(
                    id = UUID.randomUUID().toString(),
                    type = "button",
                    container = "bottom_panel",
                    title = "🔥 Бойлер",
                    x = 1,
                    y = 0,
                    width = 1,
                    height = 1,
                    config = """{"entity_id":"switch.boiler"}"""
                )
            )
        }
    }

    // ==================== ГРУППОВЫЕ КНОПКИ ====================

    private fun updateGroupState(groupId: String, entityId: String, state: String) {
        if (!groupStates.containsKey(groupId)) {
            groupStates[groupId] = mutableMapOf()
        }
        groupStates[groupId]?.put(entityId, state)
        updateGroupButtonAppearance(groupId)
    }

    private fun getGroupState(groupId: String): Boolean {
        val states = groupStates[groupId] ?: return false
        return states.values.any { it == "on" }
    }

    private fun updateGroupButtonAppearance(groupId: String) {
        val isAnyOn = getGroupState(groupId)
        for (i in 0 until bottomPanel.childCount) {
            val child = bottomPanel.getChildAt(i)
            if (child is Button && child.tag == groupId) {
                child.setBackgroundColor(if (isAnyOn) "#8033CC33".toColorInt() else "#80333333".toColorInt())
                break
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
                Toast.makeText(this, "Группа: ${tile.title}", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Log.e("MainActivity", "Error toggling group")
        }
    }

    private fun showGroupDialog(tile: TileEntity) {
        try {
            val config = JSONObject(tile.config)
            val entityIds = config.optJSONArray("entity_ids")

            if (entityIds == null || entityIds.length() == 0) {
                Toast.makeText(this, "В группе нет устройств", Toast.LENGTH_SHORT).show()
                return
            }

            val items = mutableListOf<GroupButtonItem>()
            for (i in 0 until entityIds.length()) {
                val entityId = entityIds.getString(i)
                val state = groupStates[tile.id]?.get(entityId) ?: "off"
                val name = entityId.substringAfter(".").replace("_", " ").capitalizeWords()
                items.add(GroupButtonItem(entityId, name, "", state, true))
            }

            val dialog = Dialog(this)
            dialog.setContentView(R.layout.dialog_group_buttons)

            val tvTitle = dialog.findViewById<TextView>(R.id.tvGroupTitle)
            val rvButtons = dialog.findViewById<RecyclerView>(R.id.rvGroupButtons)
            val btnClose = dialog.findViewById<Button>(R.id.btnCloseGroup)

            tvTitle.text = tile.title
            rvButtons.layoutManager = GridLayoutManager(this, 2)

            var adapter: GroupButtonsAdapter? = null

            adapter = GroupButtonsAdapter(items) { item ->
                val domain = item.entityId.substringBefore(".")
                val service = if (item.state == "on") "turn_off" else "turn_on"
                webSocket?.callService(domain, service, item.entityId)

                val newState = if (item.state == "on") "off" else "on"
                item.state = newState

                updateGroupState(tile.id, item.entityId, newState)

                val position = items.indexOf(item)
                if (position >= 0) {
                    adapter?.notifyItemChanged(position)
                }
            }

            rvButtons.adapter = adapter

            btnClose.setOnClickListener { dialog.dismiss() }
            dialog.show()

        } catch (_: Exception) {
            Log.e("MainActivity", "Error showing group dialog")
        }
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    // ==================== ОБЫЧНЫЕ КНОПКИ ====================

    private fun updateSingleButtonColor(entityId: String) {
        val state = singleStates[entityId] ?: "off"
        val isOn = state == "on"
        val targetColor = if (isOn) "#8033CC33".toColorInt() else "#424242".toColorInt()

        Log.d("MainActivity", "updateSingleButtonColor: entityId=$entityId, state=$state, isOn=$isOn")

        var found = false
        for (i in 0 until bottomPanel.childCount) {
            val child = bottomPanel.getChildAt(i)
            if (child is Button) {
                val tileId = child.tag as? String
                val tile = tileId?.let { tileManager.getAllTiles().find { t -> t.id == it } }

                if (tile != null && tile.type != "group") {
                    try {
                        val config = JSONObject(tile.config)
                        val btnEntityId = config.optString("entity_id", "")
                        Log.d("MainActivity", "Checking button: $btnEntityId")
                        if (btnEntityId == entityId) {
                            child.setBackgroundColor(targetColor)
                            found = true
                            Log.d("MainActivity", "Button color updated for: $entityId")
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        if (!found) {
            Log.w("MainActivity", "Button not found for entityId: $entityId")
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
                        handler.postDelayed(longPressRunnable!!, 1000)
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

    // ==================== ДАННЫЕ С ДАТЧИКОВ ====================

    private fun updatePzemWidget() {
        gridAdapter.updatePzemData(pzemVoltage, pzemCurrent, pzemPower, pzemEnergy, pzemFrequency, gridOnline)
    }

    private fun updateTemperatureWidget() {
        gridAdapter.updateTemperatureData(techRoomTemp)
    }

    private fun updateGridStatus() {
        gridOnline = (pzemVoltage.toFloatOrNull() ?: 0f) > 10f
        Log.d("MainActivity", "Grid online: $gridOnline (voltage: $pzemVoltage)")
    }

    private fun updateTilesForEntity(entityId: String, state: String) {
        Log.d("MainActivity", "=== EVENT RECEIVED: $entityId = $state ===")
        Log.d("MainActivity", "=== UPDATE START ===")
        Log.d("MainActivity", "entityId: '$entityId'")
        Log.d("MainActivity", "state: '$state'")
        Log.d("MainActivity", "singleStates BEFORE: ${singleStates.keys}")
        Log.d("MainActivity", "is switch: ${entityId.startsWith("switch.")}")

        // Сохраняем состояние для всех сущностей
        singleStates[entityId] = state
        Log.d("MainActivity", "singleStates AFTER: $singleStates")

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
                val formatted = formatFloat(state, 1)
                gridAdapter.updateWidgetByEntityId(entityId, formatted)
            }
        }

        // Обновляем цвет для всех switch-устройств
        if (entityId.startsWith("switch.")) {
            Log.d("MainActivity", "Calling updateSingleButtonColor for: $entityId")
            updateSingleButtonColor(entityId)
        }

        // Обновление групп
        val allTiles = tileManager.getAllTiles()
        allTiles.filter { it.type == "group" }.forEach { tile ->
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

        Log.d("MainActivity", "=== UPDATE END ===")
    }

    private fun formatFloat(value: String, decimals: Int): String {
        return value.toFloatOrNull()?.let { String.format("%.${decimals}f", it) } ?: "—"
    }

    // ==================== WEBSOCKET ====================

    private fun setupWebSocket() {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val token = prefs.getString("ha_token", "") ?: ""
        if (token.isEmpty()) {
            Log.w("MainActivity", "Token is empty, WebSocket not started")
            return
        }

        val localHost = prefs.getString("ha_local_host", "192.168.1.253:8123") ?: "192.168.1.253:8123"
        val remoteHost = prefs.getString("ha_remote_host", "") ?: ""

        webSocket = HomeAssistantWebSocket(
            host = localHost,
            token = token,
            onStateChanged = { entityId, state, _ ->
                runOnUiThread { updateTilesForEntity(entityId, state) }
            },
            onConnected = {
                handler.postDelayed({
                    subscribeToNeededEntities()
                }, 2000)
            },
            onDisconnected = {
                if (remoteHost.isNotEmpty()) {
                    webSocket = HomeAssistantWebSocket(
                        host = remoteHost,
                        token = token,
                        onStateChanged = { entityId, state, _ ->
                            runOnUiThread { updateTilesForEntity(entityId, state) }
                        },
                        onConnected = {
                            handler.postDelayed({
                                subscribeToNeededEntities()
                            }, 2000)
                        },
                        onDisconnected = {},
                        onEntitiesList = null
                    )
                    webSocket?.connect()
                }
            },
            onEntitiesList = { entities ->
                cacheEntities(entities)
            }
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
        val allTiles = tileManager.getAllTiles()
        val entityIds = mutableSetOf<String>()

        allTiles.forEach { tile ->
            try {
                val config = JSONObject(tile.config)
                val single = config.optString("entity_id", "")
                if (single.isNotEmpty()) entityIds.add(single)

                val arr = config.optJSONArray("entity_ids")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        entityIds.add(arr.getString(i))
                    }
                }
            } catch (_: Exception) {
                Log.e("MainActivity", "Error parsing tile config")
            }
        }

        if (allTiles.any { it.title == "⚡ Сеть" }) {
            entityIds.add("sensor.pzem_energy_monitor_pzem_voltage")
            entityIds.add("sensor.pzem_energy_monitor_pzem_power")
            entityIds.add("sensor.pzem_energy_monitor_pzem_current")
            entityIds.add("sensor.pzem_energy_monitor_pzem_energy")
            entityIds.add("sensor.pzem_energy_monitor_pzem_frequency")
        }

        if (entityIds.isNotEmpty()) {
            webSocket?.subscribeEntities(entityIds.toList())
        }
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
        webSocket?.disconnect()
    }
}

fun TileEntity.toWidgetItem(): WidgetItem {
    val configJson = JSONObject(config)
    val entityId = configJson.optString("entity_id", "")

    return WidgetItem(
        title = title,
        value = "",
        entityId = entityId,
        backgroundColor = when (title) {
            "⚡ Сеть" -> "#8033CC33"
            else -> "#80333333"
        },
        type = type,
        config = configJson.apply { put("id", id) }
    )
}