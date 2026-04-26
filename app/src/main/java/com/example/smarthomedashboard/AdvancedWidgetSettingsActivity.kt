package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONArray
import org.json.JSONObject

class AdvancedWidgetSettingsActivity : AppCompatActivity() {

    private lateinit var tileManager: TileManager
    private var tileId: String? = null

    // UI элементы
    private lateinit var tvCollapsedHint: TextView
    private lateinit var tvExpandedHint: TextView
    private lateinit var rvCollapsedSensors: RecyclerView
    private lateinit var rvExpandedSensors: RecyclerView
    private lateinit var etFontSize: EditText
    private lateinit var spinnerDataSource: Spinner

    // Адаптеры
    private lateinit var collapsedAdapter: SensorConfigAdapter
    private lateinit var expandedAdapter: SensorConfigAdapter

    // Данные
    private val collapsedConfigs = mutableListOf<SensorConfig>()
    private val expandedConfigs = mutableListOf<SensorConfig>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_widget_settings)

        tileManager = TileManager(this)
        tileId = intent.getStringExtra("tile_id")

        // Инициализация UI
        tvCollapsedHint = findViewById(R.id.tvCollapsedHint)
        tvExpandedHint = findViewById(R.id.tvExpandedHint)
        rvCollapsedSensors = findViewById(R.id.rvCollapsedSensors)
        rvExpandedSensors = findViewById(R.id.rvExpandedSensors)
        etFontSize = findViewById(R.id.etFontSize)
        spinnerDataSource = findViewById(R.id.spinnerDataSource)

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        loadTileData()
        setupRecyclerViews()
        setupClickListeners()
    }

    // ==================== ЗАГРУЗКА ДАННЫХ ====================

    private fun loadTileData() {
        val tile = tileId?.let { tileManager.loadTiles().find { t -> t.id == it } } ?: return

        etFontSize.setText(tile.fontSize.toString())
        spinnerDataSource.setSelection(if (tile.sourceType == "mqtt") 1 else 0)

        try {
            val config = JSONObject(tile.config)

            // Свёрнутые датчики
            val collapsedArr = config.optJSONArray("collapsed_sensor_configs")
            if (collapsedArr != null) {
                for (i in 0 until collapsedArr.length()) {
                    val obj = collapsedArr.getJSONObject(i)
                    collapsedConfigs.add(SensorConfig(
                        entityId = obj.getString("entity_id"),
                        displayName = obj.optString("display_name", ""),
                        decimals = obj.optInt("decimals", 0)
                    ))
                }
            }

            // Развёрнутые датчики
            val expandedArr = config.optJSONArray("expanded_sensor_configs")
            if (expandedArr != null) {
                for (i in 0 until expandedArr.length()) {
                    val obj = expandedArr.getJSONObject(i)
                    expandedConfigs.add(SensorConfig(
                        entityId = obj.getString("entity_id"),
                        displayName = obj.optString("display_name", ""),
                        decimals = obj.optInt("decimals", 0)
                    ))
                }
            }
        } catch (_: Exception) {}

        updateHints()
    }

    private fun updateHints() {
        tvCollapsedHint.visibility = if (collapsedConfigs.isEmpty()) View.VISIBLE else View.GONE
        rvCollapsedSensors.visibility = if (collapsedConfigs.isEmpty()) View.GONE else View.VISIBLE
        tvExpandedHint.visibility = if (expandedConfigs.isEmpty()) View.VISIBLE else View.GONE
        rvExpandedSensors.visibility = if (expandedConfigs.isEmpty()) View.GONE else View.VISIBLE
    }

    // ==================== НАСТРОЙКА СПИСКОВ ====================

    private fun setupRecyclerViews() {
        collapsedAdapter = SensorConfigAdapter(collapsedConfigs)
        rvCollapsedSensors.layoutManager = LinearLayoutManager(this)
        rvCollapsedSensors.adapter = collapsedAdapter
        ItemTouchHelper(DragCallback(collapsedAdapter)).attachToRecyclerView(rvCollapsedSensors)

        expandedAdapter = SensorConfigAdapter(expandedConfigs)
        rvExpandedSensors.layoutManager = LinearLayoutManager(this)
        rvExpandedSensors.adapter = expandedAdapter
        ItemTouchHelper(DragCallback(expandedAdapter)).attachToRecyclerView(rvExpandedSensors)
    }

    // ==================== ОБРАБОТЧИКИ НАЖАТИЙ ====================

    private fun setupClickListeners() {
        tvCollapsedHint.setOnClickListener {
            openSensorPicker { selected ->
                selected.forEach { entityId ->
                    if (collapsedConfigs.none { it.entityId == entityId }) {
                        val defaultName = entityId.substringAfterLast("_").replace("_", " ")
                        collapsedConfigs.add(SensorConfig(entityId, defaultName, 0))
                    }
                }
                collapsedAdapter.notifyDataSetChanged()
                updateHints()
            }
        }

        tvExpandedHint.setOnClickListener {
            openSensorPicker { selected ->
                selected.forEach { entityId ->
                    if (expandedConfigs.none { it.entityId == entityId }) {
                        val defaultName = entityId.substringAfterLast("_").replace("_", " ")
                        expandedConfigs.add(SensorConfig(entityId, defaultName, 0))
                    }
                }
                expandedAdapter.notifyDataSetChanged()
                updateHints()
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
    }

    // ==================== ВЫБОР ДАТЧИКОВ ====================

    private var tempSensorCallback: ((List<String>) -> Unit)? = null

    private val selectSensorsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra("selected_sensors")?.let { selected ->
                tempSensorCallback?.invoke(selected)
                tempSensorCallback = null
            }
        }
    }

    private fun openSensorPicker(onSelected: (List<String>) -> Unit) {
        tempSensorCallback = onSelected
        val intent = Intent(this, SensorPickerActivity::class.java)
        selectSensorsLauncher.launch(intent)
    }

    // ==================== СОХРАНЕНИЕ ====================

    private fun saveSettings() {
        tileId?.let { id ->
            val tiles = tileManager.loadTiles().toMutableList()
            val index = tiles.indexOfFirst { it.id == id }
            if (index >= 0) {
                val oldTile = tiles[index]

                val fontSize = etFontSize.text.toString().toIntOrNull() ?: 16
                val sourceType = if (spinnerDataSource.selectedItemPosition == 1) "mqtt" else "auto"

                val config = JSONObject(oldTile.config)

                // Сохраняем свёрнутые конфиги
                val collapsedList = collapsedAdapter.getConfigs()
                val collapsedArr = JSONArray()
                collapsedList.forEach { sc ->
                    collapsedArr.put(JSONObject().apply {
                        put("entity_id", sc.entityId)
                        put("display_name", sc.displayName)
                        put("decimals", sc.decimals)
                    })
                }
                config.put("collapsed_sensor_configs", collapsedArr)

                // Сохраняем развёрнутые конфиги
                val expandedList = expandedAdapter.getConfigs()
                val expandedArr = JSONArray()
                expandedList.forEach { sc ->
                    expandedArr.put(JSONObject().apply {
                        put("entity_id", sc.entityId)
                        put("display_name", sc.displayName)
                        put("decimals", sc.decimals)
                    })
                }
                config.put("expanded_sensor_configs", expandedArr)

                tiles[index] = oldTile.copy(
                    fontSize = fontSize,
                    sourceType = sourceType,
                    config = config.toString()
                )

                tileManager.saveTiles(tiles)
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }
}

// ==================== АДАПТЕР КОНФИГОВ ДАТЧИКОВ ====================

class SensorConfigAdapter(
    private val items: MutableList<SensorConfig>
) : RecyclerView.Adapter<SensorConfigAdapter.ViewHolder>() {

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_draggable_sensor, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvSensorName.text = item.entityId.substringAfterLast("_").replace("_", " ")
        holder.tvSensorId.text = item.entityId
        holder.etDisplayName.setText(item.displayName)

        val decimalsOptions = arrayOf("0 (целые)", "1 (десятые)", "2 (сотые)")
        val adapter = ArrayAdapter(holder.itemView.context, android.R.layout.simple_spinner_item, decimalsOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        holder.spinnerDecimals.adapter = adapter
        holder.spinnerDecimals.setSelection(item.decimals.coerceIn(0, 2))
    }

    override fun getItemCount(): Int = items.size

    fun onItemMove(from: Int, to: Int) {
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun getConfigs(): List<SensorConfig> {
        val rv = recyclerView ?: return items.toList()
        return items.mapIndexed { index, config ->
            val holder = rv.findViewHolderForAdapterPosition(index) as? ViewHolder
            val displayName = holder?.etDisplayName?.text?.toString() ?: config.displayName
            val decimals = holder?.spinnerDecimals?.selectedItemPosition ?: config.decimals
            config.copy(displayName = displayName, decimals = decimals)
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSensorName: TextView = view.findViewById(R.id.tvSensorName)
        val tvSensorId: TextView = view.findViewById(R.id.tvSensorId)
        val etDisplayName: EditText = view.findViewById(R.id.etDisplayName)
        val spinnerDecimals: Spinner = view.findViewById(R.id.spinnerDecimals)
    }
}

// ==================== DRAG-AND-DROP ====================

class DragCallback(private val adapter: SensorConfigAdapter) : ItemTouchHelper.Callback() {
    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
    }

    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
        val fromPos = viewHolder.bindingAdapterPosition
        val toPos = target.bindingAdapterPosition
        if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
        adapter.onItemMove(fromPos, toPos)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
    override fun isLongPressDragEnabled(): Boolean = true
}