package com.example.smarthomedashboard

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isNotEmpty
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
    private lateinit var btnSettings: ImageButton
    private lateinit var fabAddTile: FloatingActionButton
    private lateinit var gridAdapter: WidgetAdapter

    private lateinit var tileManager: TileManager
    private lateinit var mqttManager: MqttManager
    private var webSocket: HomeAssistantWebSocket? = null

    private var pzemVoltage = "—"
    private var pzemCurrent = "—"
    private var pzemPower = "—"
    private var pzemEnergy = "—"
    @Suppress("SpellCheckingInspection")
    private var pzemFrequency = "—"
    private var gridOnline = true
    private var techRoomTemp = "—"

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

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
        btnSettings = findViewById(R.id.btnSettings)
        fabAddTile = findViewById(R.id.fabAddTile)

        tileManager = TileManager(this)

        setupGrid()
        setupBottomPanel()
        setupMqtt()
        setupWebSocket()
        setupLongPressOnEmptySpace()

        btnSettings.setOnClickListener {
            openWithPinCheck { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
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
                // Сохраняем новый порядок плиток
                val tiles = tileManager.getTilesByContainer("grid").toMutableList()
                val moved = tiles.removeAt(from)
                tiles.add(to, moved)
                tiles.forEachIndexed { index, tile ->
                    val spanCount = 4
                    val newTile = tile.copy(x = index % spanCount, y = index / spanCount)
                    tileManager.updateTile(newTile)
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
                setBackgroundColor(Color.parseColor("#424242"))
                setTextColor(Color.WHITE)

                setOnClickListener {
                    val entityId = JSONObject(tile.config).optString("entity_id", "")
                    if (entityId.isNotEmpty()) {
                        webSocket?.callService("switch", "toggle", entityId)
                        Toast.makeText(this@MainActivity, "Переключаю: $entityId", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Не указан entity_id", Toast.LENGTH_SHORT).show()
                    }
                }

                setOnLongClickListener {
                    openWithPinCheck {
                        startActivity(Intent(this@MainActivity, TileSettingsActivity::class.java).putExtra("tile_id", tile.id))
                    }
                    true
                }
            }
            bottomPanel.addView(button)
        }
        if (bottomPanel.isNotEmpty() && bottomPanel.getChildAt(bottomPanel.childCount - 1) != btnSettings) {
            bottomPanel.removeView(btnSettings)
        }
        bottomPanel.addView(btnSettings)
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
                    config = """{"entity_id":"switch.sonoff_basic_1gs"}"""
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

    private fun setupLongPressOnEmptySpace() {
        widgetsGrid.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val child = widgetsGrid.findChildViewUnder(event.x, event.y)
                if (child == null) {
                    longPressRunnable = Runnable {
                        openWithPinCheck { showAddTileButton() }
                    }
                    handler.postDelayed(longPressRunnable!!, 3000)
                }
            } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                widgetsGrid.performClick()
            }
            false
        }

        fabAddTile.setOnClickListener {
            hideAddTileButton()
            startActivity(Intent(this, TileSettingsActivity::class.java).putExtra("container", "grid"))
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
            handler.postDelayed({ hideAddTileButton() }, 10000)
        }
    }

    private fun hideAddTileButton() {
        fabAddTile.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(200).withEndAction {
            fabAddTile.isVisible = false
        }.start()
    }

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
        when (entityId) {
            "sensor.pzem_energy_monitor_pzem_voltage" -> {
                pzemVoltage = formatFloat(state, 0); updateGridStatus(); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_power" -> {
                pzemPower = formatFloat(state, 0); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_current" -> {
                pzemCurrent = formatFloat(state, 2); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_energy" -> {
                pzemEnergy = formatFloat(state, 2); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_frequency" -> {
                pzemFrequency = formatFloat(state, 1); updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia" -> {
                techRoomTemp = formatFloat(state, 1); updateTemperatureWidget()
            }
        }
    }

    private fun formatFloat(value: String, decimals: Int): String {
        return value.toFloatOrNull()?.let { String.format("%.${decimals}f", it) } ?: "—"
    }

    private fun setupMqtt() {
        mqttManager = MqttManager(this)
        mqttManager.setOnVoltageUpdate { pzemVoltage = formatFloat(it, 0); updateGridStatus(); runOnUiThread { updatePzemWidget() } }
        mqttManager.setOnPowerUpdate { pzemPower = formatFloat(it, 0); runOnUiThread { updatePzemWidget() } }
        mqttManager.setOnCurrentUpdate { pzemCurrent = formatFloat(it, 2); runOnUiThread { updatePzemWidget() } }
        mqttManager.setOnEnergyUpdate { pzemEnergy = formatFloat(it, 2); runOnUiThread { updatePzemWidget() } }
        mqttManager.setOnFrequencyUpdate { pzemFrequency = formatFloat(it, 1); runOnUiThread { updatePzemWidget() } }
        mqttManager.setOnTemperatureUpdate { techRoomTemp = formatFloat(it, 1); runOnUiThread { updateTemperatureWidget() } }
        mqttManager.connect()
    }

    private fun setupWebSocket() {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val token = prefs.getString("ha_token", "") ?: ""
        if (token.isEmpty()) return

        val localHost = prefs.getString("ha_local_host", "192.168.1.253:8123") ?: "192.168.1.253:8123"
        val remoteHost = prefs.getString("ha_remote_host", "") ?: ""

        webSocket = HomeAssistantWebSocket(
            host = localHost,
            token = token,
            onStateChanged = { entityId, state, _ ->
                runOnUiThread { updateTilesForEntity(entityId, state) }
            },
            onConnected = {
                Log.d("MainActivity", "WebSocket connected to $localHost")
                subscribeToNeededEntities()
            },
            onDisconnected = {
                Log.d("MainActivity", "WebSocket disconnected")
                if (remoteHost.isNotEmpty()) {
                    webSocket = HomeAssistantWebSocket(
                        host = remoteHost,
                        token = token,
                        onStateChanged = { entityId, state, _ ->
                            runOnUiThread { updateTilesForEntity(entityId, state) }
                        },
                        onConnected = {
                            Log.d("MainActivity", "WebSocket connected to remote")
                            subscribeToNeededEntities()
                        },
                        onDisconnected = {
                            Log.d("MainActivity", "Remote disconnected")
                        },
                        onEntitiesList = null
                    )
                    webSocket?.connect()
                }
            },
            onEntitiesList = null
        )
        webSocket?.connect()
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
            }
        }

        // Добавляем стандартные PZEM и температуру, если они есть в виджетах
        if (tileManager.getTilesByContainer("grid").any { it.title == "⚡ Сеть" }) {
            entityIds.add("sensor.pzem_energy_monitor_pzem_voltage")
            entityIds.add("sensor.pzem_energy_monitor_pzem_power")
            entityIds.add("sensor.pzem_energy_monitor_pzem_current")
            entityIds.add("sensor.pzem_energy_monitor_pzem_energy")
            entityIds.add("sensor.pzem_energy_monitor_pzem_frequency")
        }
        if (tileManager.getTilesByContainer("grid").any { it.title == "🌡️ Температура" }) {
            entityIds.add("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia")
        }

        if (entityIds.isNotEmpty()) {
            webSocket?.subscribeEntities(entityIds.toList())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshGrid()
        refreshBottomPanel()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        mqttManager.disconnect()
        webSocket?.disconnect()
    }
}

fun TileEntity.toWidgetItem(): WidgetItem {
    return WidgetItem(
        title = title,
        value = "",
        backgroundColor = when (title) {
            "⚡ Сеть" -> "#8033CC33"
            "🌡️ Температура" -> "#803399CC"
            else -> "#80333333"
        },
        type = type,
        config = JSONObject(config).apply { put("id", id) }
    )
}