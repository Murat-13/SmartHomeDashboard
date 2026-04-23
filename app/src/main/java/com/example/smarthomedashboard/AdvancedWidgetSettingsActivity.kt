package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

class AdvancedWidgetSettingsActivity : AppCompatActivity() {

    private lateinit var tileManager: TileManager
    private var tileId: String? = null
    private var tile: TileEntity? = null

    private lateinit var tvCollapsedHint: TextView
    private lateinit var tvExpandedHint: TextView
    private lateinit var rvCollapsedSensors: RecyclerView
    private lateinit var rvExpandedSensors: RecyclerView
    private lateinit var spinnerDataSource: Spinner
    private lateinit var etFontSize: EditText
    private lateinit var btnAddColorRule: Button
    private lateinit var rvColorRules: RecyclerView

    private lateinit var collapsedAdapter: DraggableSensorAdapter
    private lateinit var expandedAdapter: DraggableSensorAdapter
    private lateinit var colorRulesAdapter: ColorRuleAdapter

    private val collapsedSensors = mutableListOf<SensorItem>()
    private val expandedSensors = mutableListOf<SensorItem>()
    private val colorRules = mutableListOf<ColorRule>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_widget_settings)

        tileManager = TileManager(this)
        tileId = intent.getStringExtra("tile_id")
        tile = tileId?.let { tileManager.loadTiles().find { it.id == tileId } }

        tvCollapsedHint = findViewById(R.id.tvCollapsedHint)
        tvExpandedHint = findViewById(R.id.tvExpandedHint)
        rvCollapsedSensors = findViewById(R.id.rvCollapsedSensors)
        rvExpandedSensors = findViewById(R.id.rvExpandedSensors)
        spinnerDataSource = findViewById(R.id.spinnerDataSource)
        etFontSize = findViewById(R.id.etFontSize)
        btnAddColorRule = findViewById(R.id.btnAddColorRule)
        rvColorRules = findViewById(R.id.rvColorRules)

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        loadTileData()
        setupRecyclerViews()
        setupClickListeners()
    }

    private fun loadTileData() {
        tile?.let { t ->
            try {
                val config = JSONObject(t.config)

                // Размер шрифта
                etFontSize.setText(t.fontSize.toString())

                // Источник данных
                val sourceIndex = when (t.sourceType) {
                    "mqtt" -> 1
                    else -> 0
                }
                spinnerDataSource.setSelection(sourceIndex)

                // Свёрнутые датчики
                val collapsedIds = JSONArray(t.collapsedSensorIds)
                for (i in 0 until collapsedIds.length()) {
                    collapsedSensors.add(SensorItem(collapsedIds.getString(i), "Датчик", true))
                }

                // Развёрнутые датчики
                val entityIds = config.optJSONArray("entity_ids")
                if (entityIds != null) {
                    for (i in 0 until entityIds.length()) {
                        val id = entityIds.getString(i)
                        if (collapsedSensors.none { it.entityId == id }) {
                            expandedSensors.add(SensorItem(id, id, true))
                        }
                    }
                } else {
                    val single = config.optString("entity_id", "")
                    if (single.isNotEmpty() && collapsedSensors.none { it.entityId == single }) {
                        expandedSensors.add(SensorItem(single, single, true))
                    }
                }

                // Цветовые правила
                val conditions = JSONArray(t.conditions)
                for (i in 0 until conditions.length()) {
                    val obj = conditions.getJSONObject(i)
                    colorRules.add(ColorRule(
                        sensor = obj.optString("entity_id", ""),
                        operator = obj.optString("operator", ">"),
                        value = obj.optDouble("value", 0.0).toFloat(),
                        bgColor = obj.optString("bg_color", "#4CAF50"),
                        textColor = obj.optString("text_color", "#FFFFFF")
                    ))
                }
            } catch (_: Exception) {}
        }
        updateHints()
    }

    private fun updateHints() {
        tvCollapsedHint.visibility = if (collapsedSensors.isEmpty()) View.VISIBLE else View.GONE
        rvCollapsedSensors.visibility = if (collapsedSensors.isEmpty()) View.GONE else View.VISIBLE
        tvExpandedHint.visibility = if (expandedSensors.isEmpty()) View.VISIBLE else View.GONE
        rvExpandedSensors.visibility = if (expandedSensors.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setupRecyclerViews() {
        // Свёрнутые датчики
        collapsedAdapter = DraggableSensorAdapter(collapsedSensors) { sensor, isChecked ->
            sensor.visible = isChecked
        }
        rvCollapsedSensors.layoutManager = LinearLayoutManager(this)
        rvCollapsedSensors.adapter = collapsedAdapter

        val touchHelper1 = ItemTouchHelper(DragCallback(collapsedAdapter))
        touchHelper1.attachToRecyclerView(rvCollapsedSensors)

        // Развёрнутые датчики
        expandedAdapter = DraggableSensorAdapter(expandedSensors) { sensor, isChecked ->
            sensor.visible = isChecked
        }
        rvExpandedSensors.layoutManager = LinearLayoutManager(this)
        rvExpandedSensors.adapter = expandedAdapter

        val touchHelper2 = ItemTouchHelper(DragCallback(expandedAdapter))
        touchHelper2.attachToRecyclerView(rvExpandedSensors)

        // Цветовые правила
        colorRulesAdapter = ColorRuleAdapter(colorRules, this)
        rvColorRules.layoutManager = LinearLayoutManager(this)
        rvColorRules.adapter = colorRulesAdapter
    }

    private fun setupClickListeners() {
        tvCollapsedHint.setOnClickListener {
            openSensorPicker { selected ->
                selected.forEach { entityId ->
                    if (collapsedSensors.none { it.entityId == entityId }) {
                        collapsedSensors.add(SensorItem(entityId, entityId, true))
                    }
                }
                collapsedAdapter.notifyDataSetChanged()
                updateHints()
            }
        }

        tvExpandedHint.setOnClickListener {
            openSensorPicker { selected ->
                selected.forEach { entityId ->
                    if (expandedSensors.none { it.entityId == entityId }) {
                        expandedSensors.add(SensorItem(entityId, entityId, true))
                    }
                }
                expandedAdapter.notifyDataSetChanged()
                updateHints()
            }
        }

        btnAddColorRule.setOnClickListener {
            colorRules.add(ColorRule("", ">", 0f, "#4CAF50", "#FFFFFF"))
            colorRulesAdapter.notifyItemInserted(colorRules.size - 1)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings()
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()
        }
    }

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

    private fun saveSettings() {
        tileId?.let { id ->
            val tiles = tileManager.loadTiles().toMutableList()
            val index = tiles.indexOfFirst { it.id == id }

            if (index >= 0) {
                val oldTile = tiles[index]

                val fontSize = etFontSize.text.toString().toIntOrNull() ?: 16
                val sourceType = when (spinnerDataSource.selectedItemPosition) {
                    1 -> "mqtt"
                    else -> "auto"
                }

                val collapsedArray = JSONArray()
                collapsedSensors.filter { it.visible }.forEach { collapsedArray.put(it.entityId) }

                val conditionsArray = JSONArray()
                colorRules.filter { it.sensor.isNotEmpty() }.forEach { rule ->
                    conditionsArray.put(JSONObject().apply {
                        put("entity_id", rule.sensor)
                        put("operator", rule.operator)
                        put("value", rule.value.toDouble())
                        put("bg_color", rule.bgColor)
                        put("text_color", rule.textColor)
                    })
                }

                val config = JSONObject(oldTile.config)
                val allSensors = mutableSetOf<String>()
                collapsedSensors.filter { it.visible }.forEach { allSensors.add(it.entityId) }
                expandedSensors.filter { it.visible }.forEach { allSensors.add(it.entityId) }

                if (allSensors.size == 1) {
                    config.put("entity_id", allSensors.first())
                    config.remove("entity_ids")
                } else if (allSensors.size > 1) {
                    config.put("entity_ids", JSONArray(allSensors.toList()))
                    config.remove("entity_id")
                }

                tiles[index] = oldTile.copy(
                    collapsedSensorIds = collapsedArray.toString(),
                    conditions = conditionsArray.toString(),
                    fontSize = fontSize,
                    sourceType = sourceType,
                    config = config.toString()
                )

                tileManager.saveTiles(tiles)
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}

// Вспомогательные классы

data class SensorItem(
    val entityId: String,
    val displayName: String,
    var visible: Boolean
)

data class ColorRule(
    val sensor: String,
    val operator: String,
    val value: Float,
    val bgColor: String,
    val textColor: String
)

class DraggableSensorAdapter(
    private val items: MutableList<SensorItem>,
    private val onCheckedChange: (SensorItem, Boolean) -> Unit
) : RecyclerView.Adapter<DraggableSensorAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_draggable_sensor, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvSensorName.text = item.displayName
        holder.tvSensorId.text = item.entityId
        holder.tvDisplayName.text = item.displayName.take(10)

        holder.cbVisible.setOnCheckedChangeListener(null)
        holder.cbVisible.isChecked = item.visible
        holder.cbVisible.setOnCheckedChangeListener { _, isChecked ->
            onCheckedChange(item, isChecked)
        }
    }

    override fun getItemCount(): Int = items.size

    fun onItemMove(from: Int, to: Int) {
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSensorName: TextView = view.findViewById(R.id.tvSensorName)
        val tvSensorId: TextView = view.findViewById(R.id.tvSensorId)
        val tvDisplayName: TextView = view.findViewById(R.id.tvDisplayName)
        val cbVisible: CheckBox = view.findViewById(R.id.cbVisible)
    }
}

class DragCallback(private val adapter: DraggableSensorAdapter) : ItemTouchHelper.Callback() {
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

class ColorRuleAdapter(
    private val items: MutableList<ColorRule>,
    private val context: AdvancedWidgetSettingsActivity
) : RecyclerView.Adapter<ColorRuleAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rule = items[position]
        holder.etValue.setText(rule.value.toString())
        holder.btnDeleteRule.setOnClickListener {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val etValue: EditText = view.findViewById(R.id.etValue)
        val btnDeleteRule: Button = view.findViewById(R.id.btnDeleteRule)
    }
}