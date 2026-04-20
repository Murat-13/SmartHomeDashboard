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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
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
    private lateinit var overlayContainer: FrameLayout
    private lateinit var btnLight: Button
    private lateinit var btnBoiler: Button
    private lateinit var btnHeater: Button
    private lateinit var btnPump: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var fabAddTile: FloatingActionButton
    private lateinit var adapter: WidgetAdapter

    private lateinit var tileManager: TileManager
    private lateinit var mqttManager: MqttManager
    private var webSocket: HomeAssistantWebSocket? = null

    private var pzemVoltage = "—"
    private var pzemCurrent = "—"
    private var pzemPower = "—"
    private var pzemEnergy = "—"
    private var pzemFrequency = "—"
    private var gridOnline = true
    private var techRoomTemp = "—"

    private val entityStates = mutableMapOf<String, String>()
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
        overlayContainer = findViewById(R.id.overlayContainer)
        btnLight = findViewById(R.id.btnLight)
        btnBoiler = findViewById(R.id.btnBoiler)
        btnHeater = findViewById(R.id.btnHeater)
        btnPump = findViewById(R.id.btnPump)
        btnSettings = findViewById(R.id.btnSettings)
        fabAddTile = findViewById(R.id.fabAddTile)

        tileManager = TileManager(this)

        setupWidgetsGrid()
        setupButtons()
        setupMqtt()
        setupWebSocket()
        setupLongPressForAddTile()
    }

    private fun setupWidgetsGrid() {
        widgetsGrid.layoutManager = GridLayoutManager(this, 4)

        val gridTiles = tileManager.getTilesByContainer("grid")

        if (gridTiles.isEmpty()) {
            createDefaultTiles()
        }

        refreshTiles()
    }

    private fun refreshTiles() {
        val tiles = tileManager.getTilesByContainer("grid")
        val widgetItems = tiles.map { it.toWidgetItem() }.toMutableList()

        if (!::adapter.isInitialized) {
            adapter = WidgetAdapter(
                this,
                widgetItems,
                overlayContainer,
                widgetsGrid
            ) {
                WidgetData(pzemVoltage, pzemCurrent, pzemPower, pzemEnergy, pzemFrequency, gridOnline)
            }
            widgetsGrid.adapter = adapter
        } else {
            adapter.updateTiles(widgetItems)
        }
    }

    private fun createDefaultTiles() {
        lifecycleScope.launch {
            tileManager.addTile(
                TileEntity(
                    id = UUID.randomUUID().toString(),
                    type = "sensor",
                    container = "grid",
                    title = "⚡ Сеть",
                    x = 0, y = 0, width = 1, height = 1,
                    appearance = "{}",
                    conditions = "{}",
                    config = "{}"
                )
            )
            tileManager.addTile(
                TileEntity(
                    id = UUID.randomUUID().toString(),
                    type = "sensor",
                    container = "grid",
                    title = "🌡️ Температура",
                    x = 1, y = 0, width = 1, height = 1,
                    appearance = "{}",
                    conditions = "{}",
                    config = "{}"
                )
            )
        }
    }

    private fun setupLongPressForAddTile() {
        val container = findViewById<FrameLayout>(R.id.overlayContainer)

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressRunnable = Runnable {
                        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
                        val lastAuthTime = prefs.getLong("last_auth_time", 0)
                        val currentTime = System.currentTimeMillis()

                        if (currentTime - lastAuthTime > 60 * 60 * 1000) {
                            PinDialog(this@MainActivity) {
                                prefs.edit { putLong("last_auth_time", System.currentTimeMillis()) }
                                showAddTileButton()
                            }.show()
                        } else {
                            showAddTileButton()
                        }
                    }
                    handler.postDelayed(longPressRunnable!!, 3000)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    if (fabAddTile.visibility != View.VISIBLE) {
                        adapter.collapseExpandedWidget()
                    }
                    true
                }
                else -> false
            }
        }

        container.setOnClickListener {
            if (fabAddTile.visibility == View.VISIBLE) {
                hideAddTileButton()
            }
        }

        fabAddTile.setOnClickListener {
            hideAddTileButton()
            val intent = Intent(this, TileSettingsActivity::class.java)
            intent.putExtra("container", "grid")
            startActivity(intent)
        }
    }

    private fun showAddTileButton() {
        val tiles = tileManager.getTilesByContainer("grid")
        val position = tiles.size

        val spanCount = 4
        val row = position / spanCount
        val col = position % spanCount

        widgetsGrid.post {
            val firstView = widgetsGrid.getChildAt(0)
            if (firstView != null) {
                val tileWidth = firstView.width
                val tileHeight = firstView.height

                val x = col * tileWidth + tileWidth / 2f
                val y = row * tileHeight + tileHeight / 2f

                fabAddTile.x = x - fabAddTile.width / 2
                fabAddTile.y = y - fabAddTile.height / 2
            }

            fabAddTile.visibility = View.VISIBLE
            fabAddTile.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(200)
                .start()

            handler.postDelayed({
                hideAddTileButton()
            }, 10000)
        }
    }

    private fun hideAddTileButton() {
        fabAddTile.animate()
            .scaleX(0f)
            .scaleY(0f)
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                fabAddTile.visibility = View.GONE
            }
            .start()
    }

    private fun updatePzemWidget() {
        adapter.updatePzemData(
            pzemVoltage, pzemCurrent, pzemPower,
            pzemEnergy, pzemFrequency, gridOnline
        )
    }

    private fun updateTemperatureWidget() {
        adapter.updateTemperatureData(techRoomTemp)
    }

    private fun updateGridStatus() {
        val voltage = pzemVoltage.toFloatOrNull() ?: 0f
        gridOnline = voltage > 10f
    }

    private fun updateTilesForEntity(entityId: String, state: String) {
        when (entityId) {
            "sensor.pzem_energy_monitor_pzem_voltage" -> {
                pzemVoltage = state.toFloatOrNull()?.let { "%.0f".format(it) } ?: "—"
                updateGridStatus()
                updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_power" -> {
                pzemPower = state.toFloatOrNull()?.let { "%.0f".format(it) } ?: "—"
                updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_current" -> {
                pzemCurrent = state.toFloatOrNull()?.let { "%.2f".format(it) } ?: "—"
                updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_energy" -> {
                pzemEnergy = state.toFloatOrNull()?.let { "%.2f".format(it) } ?: "—"
                updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_pzem_frequency" -> {
                pzemFrequency = state.toFloatOrNull()?.let { "%.1f".format(it) } ?: "—"
                updatePzemWidget()
            }
            "sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia" -> {
                techRoomTemp = state.toFloatOrNull()?.let { "%.1f".format(it) } ?: "—"
                updateTemperatureWidget()
            }
        }
    }

    private fun setupMqtt() {
        mqttManager = MqttManager(this)

        mqttManager.setOnVoltageUpdate { voltage ->
            pzemVoltage = voltage.toFloatOrNull()?.let { "%.0f".format(it) } ?: "—"
            updateGridStatus()
            runOnUiThread { updatePzemWidget() }
        }

        mqttManager.setOnPowerUpdate { power ->
            pzemPower = power.toFloatOrNull()?.let { "%.0f".format(it) } ?: "—"
            runOnUiThread { updatePzemWidget() }
        }

        mqttManager.setOnCurrentUpdate { current ->
            pzemCurrent = current.toFloatOrNull()?.let { "%.2f".format(it) } ?: "—"
            runOnUiThread { updatePzemWidget() }
        }

        mqttManager.setOnEnergyUpdate { energy ->
            pzemEnergy = energy.toFloatOrNull()?.let { "%.2f".format(it) } ?: "—"
            runOnUiThread { updatePzemWidget() }
        }

        mqttManager.setOnFrequencyUpdate { frequency ->
            pzemFrequency = frequency.toFloatOrNull()?.let { "%.1f".format(it) } ?: "—"
            runOnUiThread { updatePzemWidget() }
        }

        mqttManager.connect()
    }

    private fun setupWebSocket() {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val localHost = prefs.getString("ha_local_host", "192.168.1.253:8123") ?: "192.168.1.253:8123"
        val remoteHost = prefs.getString("ha_remote_host", "") ?: ""
        val token = prefs.getString("ha_token", "") ?: ""

        if (token.isEmpty()) {
            Toast.makeText(this, "Токен HA не настроен", Toast.LENGTH_SHORT).show()
            return
        }

        var host = localHost

        webSocket = HomeAssistantWebSocket(
            host = host,
            token = token,
            onStateChanged = { entityId, state ->
                Log.d("MainActivity", "State changed: $entityId = $state")
                entityStates[entityId] = state
                runOnUiThread { updateTilesForEntity(entityId, state) }
            },
            onConnected = { Log.d("MainActivity", "WebSocket connected to $host") },
            onDisconnected = {
                Log.d("MainActivity", "WebSocket disconnected")
                if (host == localHost && remoteHost.isNotEmpty()) {
                    host = remoteHost
                    webSocket = HomeAssistantWebSocket(
                        host = host,
                        token = token,
                        onStateChanged = { entityId, state ->
                            entityStates[entityId] = state
                            runOnUiThread { updateTilesForEntity(entityId, state) }
                        },
                        onConnected = { Log.d("MainActivity", "Connected to remote") },
                        onDisconnected = { Log.d("MainActivity", "Remote disconnected") }
                    )
                    webSocket?.connect()
                }
            }
        )
        webSocket?.connect()
    }

    private fun setupButtons() {
        btnLight.setOnClickListener {
            val isOn = btnLight.currentTextColor != Color.GREEN
            btnLight.setTextColor(if (isOn) Color.GREEN else Color.WHITE)
        }

        btnBoiler.setOnClickListener {
            val isOn = btnBoiler.currentTextColor != Color.GREEN
            btnBoiler.setTextColor(if (isOn) Color.GREEN else Color.WHITE)
        }

        btnHeater.setOnClickListener {
            val isOn = btnHeater.currentTextColor != Color.GREEN
            btnHeater.setTextColor(if (isOn) Color.GREEN else Color.WHITE)
        }

        btnPump.setOnClickListener {
            val isOn = btnPump.currentTextColor != Color.GREEN
            btnPump.setTextColor(if (isOn) Color.GREEN else Color.WHITE)
        }

        btnSettings.setOnClickListener {
            val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
            val lastAuthTime = prefs.getLong("last_auth_time", 0)
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastAuthTime > 60 * 60 * 1000) {
                PinDialog(this) {
                    prefs.edit { putLong("last_auth_time", System.currentTimeMillis()) }
                    startActivity(Intent(this, SettingsActivity::class.java))
                }.show()
            } else {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            adapter.collapseExpandedWidget()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        mqttManager.reconnect()
        refreshTiles()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        mqttManager.disconnect()
        webSocket?.disconnect()
    }
}

fun TileEntity.toWidgetItem(): WidgetItem {
    val configJson = try {
        JSONObject(config)
    } catch (_: Exception) {
        JSONObject()
    }
    configJson.put("id", id)
    return WidgetItem(
        title = title,
        value = "",
        backgroundColor = when (title) {
            "⚡ Сеть" -> "#8033CC33"
            "🌡️ Температура" -> "#803399CC"
            else -> "#80333333"
        },
        type = type,
        config = configJson
    )
}