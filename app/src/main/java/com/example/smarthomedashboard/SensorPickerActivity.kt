package com.example.smarthomedashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

data class HaSensor(
    val entityId: String,
    val name: String,
    val state: String = "",
    val selected: Boolean = false
)

class SensorPickerActivity : AppCompatActivity() {

    private lateinit var rvSensors: RecyclerView
    private lateinit var btnSave: Button
    private lateinit var adapter: SensorListAdapter

    private var webSocket: HomeAssistantWebSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensor_picker)

        rvSensors = findViewById(R.id.rvSensors)
        btnSave = findViewById(R.id.btnSave)

        val selectedIds = intent.getStringArrayListExtra("selected_sensors") ?: arrayListOf()

        setupRecyclerView(selectedIds)
        setupWebSocket(selectedIds)

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        btnSave.setOnClickListener {
            val selected = adapter.getSelectedSensors()
            intent.putStringArrayListExtra("selected_sensors", ArrayList(selected))
            setResult(RESULT_OK, intent)
            finish()
        }
    }

    private fun setupRecyclerView(selectedIds: List<String>) {
        rvSensors.layoutManager = LinearLayoutManager(this)
        adapter = SensorListAdapter(selectedIds) { sensor ->
            adapter.updateSensorSelection(sensor.entityId, !sensor.selected)
        }
        rvSensors.adapter = adapter
    }

    private fun setupWebSocket(selectedIds: List<String>) {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val host = prefs.getString("ha_local_host", "192.168.1.253:8123") ?: "192.168.1.253:8123"
        val token = prefs.getString("ha_token", "") ?: ""

        webSocket = HomeAssistantWebSocket(
            host = host,
            token = token,
            onStateChanged = { _, _ -> },
            onConnected = {
                runOnUiThread {
                    loadTestSensors(selectedIds)
                }
            },
            onDisconnected = {}
        )
        webSocket?.connect()
    }

    private fun loadTestSensors(selectedIds: List<String>) {
        val testSensors = listOf(
            HaSensor("sensor.pzem_energy_monitor_pzem_voltage", "PZEM Voltage", selected = selectedIds.contains("sensor.pzem_energy_monitor_pzem_voltage")),
            HaSensor("sensor.pzem_energy_monitor_pzem_current", "PZEM Current", selected = selectedIds.contains("sensor.pzem_energy_monitor_pzem_current")),
            HaSensor("sensor.pzem_energy_monitor_pzem_power", "PZEM Power", selected = selectedIds.contains("sensor.pzem_energy_monitor_pzem_power")),
            HaSensor("sensor.pzem_energy_monitor_pzem_energy", "PZEM Energy", selected = selectedIds.contains("sensor.pzem_energy_monitor_pzem_energy")),
            HaSensor("sensor.pzem_energy_monitor_pzem_frequency", "PZEM Frequency", selected = selectedIds.contains("sensor.pzem_energy_monitor_pzem_frequency")),
            HaSensor("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia", "Температура техпомещения", selected = selectedIds.contains("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia")),
            HaSensor("switch.sonoff_basic_1gs", "SONOFF BASIC-1GS", selected = selectedIds.contains("switch.sonoff_basic_1gs")),
        )
        adapter.submitList(testSensors)
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.disconnect()
    }
}

class SensorListAdapter(
    initialSelectedIds: List<String>,
    private val onItemClick: (HaSensor) -> Unit
) : ListAdapter<HaSensor, SensorListAdapter.ViewHolder>(SensorDiffCallback()) {

    private val selectedSensors = initialSelectedIds.toMutableSet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sensor, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sensor = getItem(position)
        val isSelected = selectedSensors.contains(sensor.entityId)

        holder.tvName.text = sensor.name
        holder.tvId.text = sensor.entityId
        holder.cbSelected.isChecked = isSelected

        holder.itemView.setOnClickListener {
            onItemClick(sensor)
        }
    }

    fun updateSensorSelection(entityId: String, selected: Boolean) {
        if (selected) {
            selectedSensors.add(entityId)
        } else {
            selectedSensors.remove(entityId)
        }
        val position = currentList.indexOfFirst { it.entityId == entityId }
        if (position >= 0) {
            notifyItemChanged(position)
        }
    }

    fun getSelectedSensors(): List<String> = selectedSensors.toList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvSensorName)
        val tvId: TextView = view.findViewById(R.id.tvSensorId)
        val cbSelected: CheckBox = view.findViewById(R.id.cbSelected)
    }
}

class SensorDiffCallback : DiffUtil.ItemCallback<HaSensor>() {
    override fun areItemsTheSame(oldItem: HaSensor, newItem: HaSensor): Boolean {
        return oldItem.entityId == newItem.entityId
    }

    override fun areContentsTheSame(oldItem: HaSensor, newItem: HaSensor): Boolean {
        return oldItem.entityId == newItem.entityId && oldItem.selected == newItem.selected
    }
}