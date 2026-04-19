package com.example.smarthomedashboard

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var widgetsGrid: RecyclerView
    private lateinit var overlayContainer: FrameLayout
    private lateinit var btnLight: Button
    private lateinit var btnBoiler: Button
    private lateinit var btnHeater: Button
    private lateinit var btnPump: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var adapter: WidgetAdapter

    private lateinit var mqttManager: MqttManager

    private var pzemVoltage = "—"
    private var pzemCurrent = "—"
    private var pzemPower = "—"
    private var pzemEnergy = "—"
    private var pzemFrequency = "—"
    private var gridOnline = true

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

        setupWidgetsGrid()
        setupButtons()
        setupMqtt()
    }

    private fun setupWidgetsGrid() {
        widgetsGrid.layoutManager = GridLayoutManager(this, 4)

        val testWidgets = listOf(
            WidgetItem("⚡ Сеть", "— W", "#8033CC33"),
            WidgetItem("🔋 BMS", "78%", "#803399CC"),
            WidgetItem("🌤️ Погода", "22°C", "#80FF9933"),
            WidgetItem("📷 Камера 1", "● LIVE", "#80336699"),
            WidgetItem("📷 Камера 2", "● LIVE", "#80336699"),
            WidgetItem("", "", "#00000000")
        )

        adapter = WidgetAdapter(this, testWidgets, overlayContainer) {
            WidgetData(pzemVoltage, pzemCurrent, pzemPower, pzemEnergy, pzemFrequency, gridOnline)
        }
        widgetsGrid.adapter = adapter
    }

    private fun updatePzemWidget() {
        adapter.notifyDataSetChanged()
        adapter.updateOverlayData(WidgetData(pzemVoltage, pzemCurrent, pzemPower, pzemEnergy, pzemFrequency, gridOnline))
    }

    private fun updateGridStatus() {
        val voltage = pzemVoltage.toFloatOrNull() ?: 0f
        gridOnline = voltage > 10f
    }

    private fun setupMqtt() {
        mqttManager = MqttManager(this)

        mqttManager.setOnVoltageUpdate { voltage ->
            pzemVoltage = voltage.toFloatOrNull()?.let { "%.0f".format(it) } ?: "—"
            updateGridStatus()
            Log.d("MainActivity", "Voltage: $pzemVoltage V, Grid: $gridOnline")
            runOnUiThread { updatePzemWidget() }
        }

        mqttManager.setOnPowerUpdate { power ->
            pzemPower = power.toFloatOrNull()?.let { "%.0f".format(it) } ?: "—"
            Log.d("MainActivity", "Power: $pzemPower W")
            runOnUiThread { updatePzemWidget() }
        }

        mqttManager.setOnCurrentUpdate { current ->
            pzemCurrent = current.toFloatOrNull()?.let { "%.2f".format(it) } ?: "—"
            Log.d("MainActivity", "Current: $pzemCurrent A")
            runOnUiThread { updatePzemWidget() }
        }

        mqttManager.setOnEnergyUpdate { energy ->
            pzemEnergy = energy.toFloatOrNull()?.let { "%.2f".format(it) } ?: "—"
            Log.d("MainActivity", "Energy: $pzemEnergy kWh")
            runOnUiThread { updatePzemWidget() }
        }

        mqttManager.setOnFrequencyUpdate { frequency ->
            pzemFrequency = frequency.toFloatOrNull()?.let { "%.1f".format(it) } ?: "—"
            Log.d("MainActivity", "Frequency: $pzemFrequency Hz")
            runOnUiThread { updatePzemWidget() }
        }

        mqttManager.connect()
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
        // Переподключаем MQTT при возврате из настроек (если настройки изменились)
        mqttManager.reconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        mqttManager.disconnect()
    }
}