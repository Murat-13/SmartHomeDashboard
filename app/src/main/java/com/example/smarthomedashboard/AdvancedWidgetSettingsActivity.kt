package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class AdvancedWidgetSettingsActivity : AppCompatActivity() {

    private lateinit var tileManager: TileManager
    private var tileId: String? = null
    private var tile: TileEntity? = null

    // UI элементы
    private lateinit var ivSelectedIcon: ImageView
    private lateinit var tvFontSize: TextView
    private lateinit var tvCollapsedHint: TextView
    private lateinit var tvExpandedHint: TextView
    private lateinit var rvCollapsedSensors: RecyclerView
    private lateinit var rvExpandedSensors: RecyclerView
    private lateinit var rvColorRules: RecyclerView
    private lateinit var spinnerDataSource: Spinner

    // Адаптеры
    private lateinit var collapsedAdapter: DraggableSensorAdapter
    private lateinit var expandedAdapter: DraggableSensorAdapter
    private lateinit var colorRulesAdapter: ColorRuleAdapter

    // Данные
    private var selectedIcon: String = ""
    private var fontSize: Int = 16
    private val collapsedSensors = mutableListOf<SensorItem>()
    private val expandedSensors = mutableListOf<SensorItem>()
    private val colorRules = mutableListOf<ColorRule>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_widget_settings)

        tileManager = TileManager(this)
        tileId = intent.getStringExtra("tile_id")

        initViews()
        loadTileData()
        setupRecyclerViews()
        setupClickListeners()
    }

    private fun initViews() {
        ivSelectedIcon = findViewById(R.id.ivSelectedIcon)
        tvFontSize = findViewById(R.id.tvFontSize)
        tvCollapsedHint = findViewById(R.id.tvCollapsedHint)
        tvExpandedHint = findViewById(R.id.tvExpandedHint)
        rvCollapsedSensors = findViewById(R.id.rvCollapsedSensors)
        rvExpandedSensors = findViewById(R.id.rvExpandedSensors)
        rvColorRules = findViewById(R.id.rvColorRules)
        spinnerDataSource = findViewById(R.id.spinnerDataSource)

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadTileData() {
        tileId?.let { id ->
            tile = tileManager.loadTiles().find { it.id == id }
        }

        tile?.let { t ->
            // Загружаем сохранённые настройки
            try {
                val appearance = JSONObject(t.appearance)
                selectedIcon = appearance.optString("icon", "")
                fontSize = appearance.optInt("fontSize", 16)
                tvFontSize.text = fontSize.toString()

                val collapsedIds = JSONArray(t.collapsedSensorIds)
                for (i in 0 until collapsedIds.length()) {
                    collapsedSensors.add(SensorItem(collapsedIds.getString(i), "Датчик", true))
                }

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

                val sourceIndex = when (t.sourceType) {
                    "mqtt" -> 1
                    else -> 0
                }
                spinnerDataSource.setSelection(sourceIndex)

            } catch (e: Exception) {
                // Значения по умолчанию
            }

            // Загружаем выбранные датчики из config
            try {
                val config = JSONObject(t.config)
                val ids = config.optJSONArray("entity_ids")
                if (ids != null) {
                    for (i in 0 until ids.length()) {
                        val entityId = ids.getString(i)
                        if (collapsedSensors.none { it.entityId == entityId } &&
                            expandedSensors.none { it.entityId == entityId }) {
                            expandedSensors.add(SensorItem(entityId, entityId, true))
                        }
                    }
                } else {
                    val single = config.optString("entity_id", "")
                    if (single.isNotEmpty() && collapsedSensors.isEmpty() && expandedSensors.isEmpty()) {
                        expandedSensors.add(SensorItem(single, single, true))
                    }
                }
            } catch (e: Exception) {
                // Игнорируем
            }
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

        // Развёрнутые датчики
        expandedAdapter = DraggableSensorAdapter(expandedSensors) { sensor, isChecked ->
            sensor.visible = isChecked
        }
        rvExpandedSensors.layoutManager = LinearLayoutManager(this)
        rvExpandedSensors.adapter = expandedAdapter

        // Drag-and-drop для обоих списков
        val touchHelper = ItemTouchHelper(DragCallback(collapsedAdapter))
        touchHelper.attachToRecyclerView(rvCollapsedSensors)

        val touchHelper2 = ItemTouchHelper(DragCallback(expandedAdapter))
        touchHelper2.attachToRecyclerView(rvExpandedSensors)

        // Цветовые правила
        colorRulesAdapter = ColorRuleAdapter(colorRules, this)
        rvColorRules.layoutManager = LinearLayoutManager(this)
        rvColorRules.adapter = colorRulesAdapter
    }

    private fun setupClickListeners() {
        // Выбор иконки
        findViewById<Button>(R.id.btnSelectIcon).setOnClickListener {
            // TODO: Открыть выбор Material Symbols
            Toast.makeText(this, "Выбор иконки (в разработке)", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnUploadIcon).setOnClickListener {
            // TODO: Загрузка из галереи
            Toast.makeText(this, "Загрузка из галереи (в разработке)", Toast.LENGTH_SHORT).show()
        }

        // Размер шрифта
        findViewById<Button>(R.id.btnFontSizeMinus).setOnClickListener {
            if (fontSize > 8) {
                fontSize--
                tvFontSize.text = fontSize.toString()
            }
        }

        findViewById<Button>(R.id.btnFontSizePlus).setOnClickListener {
            if (fontSize < 48) {
                fontSize++
                tvFontSize.text = fontSize.toString()
            }
        }

        // Выбор датчиков для свёрнутого вида
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

        // Выбор датчиков для развёрнутого вида
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

        // Добавить цветовое правило
        findViewById<Button>(R.id.btnAddColorRule).setOnClickListener {
            colorRules.add(ColorRule("", ">", 0f, "#4CAF50", "#FFFFFF"))
            colorRulesAdapter.notifyItemInserted(colorRules.size - 1)
        }

        // Сохранить
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings()
        }

        // Отмена
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()
        }
    }

    private fun openSensorPicker(onSelected: (List<String>) -> Unit) {
        val intent = Intent(this, SensorPickerActivity::class.java)
        startActivityForResult(intent, REQUEST_SELECT_SENSORS)
        // Временно сохраняем колбэк
        tempSensorCallback = onSelected
    }

    private var tempSensorCallback: ((List<String>) -> Unit)? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SELECT_SENSORS && resultCode == Activity.RESULT_OK) {
            data?.getStringArrayListExtra("selected_sensors")?.let { selected ->
                tempSensorCallback?.invoke(selected)
                tempSensorCallback = null
            }
        }
    }

    private fun saveSettings() {
        tileId?.let { id ->
            val tiles = tileManager.loadTiles().toMutableList()
            val index = tiles.indexOfFirst { it.id == id }

            if (index >= 0) {
                val oldTile = tiles[index]

                // Формируем appearance
                val appearance = JSONObject().apply {
                    put("icon", selectedIcon)
                    put("fontSize", fontSize)
                }

                // Формируем collapsedSensorIds
                val collapsedArray = JSONArray()
                collapsedSensors.filter { it.visible }.forEach { collapsedArray.put(it.entityId) }

                // Формируем conditions
                val conditionsArray = JSONArray()
                colorRules.forEach { rule ->
                    if (rule.sensor.isNotEmpty()) {
                        conditionsArray.put(JSONObject().apply {
                            put("entity_id", rule.sensor)
                            put("operator", rule.operator)
                            put("value", rule.value)
                            put("bg_color", rule.bgColor)
                            put("text_color", rule.textColor)
                        })
                    }
                }

                // Обновляем config — добавляем все датчики
                val allSensors = mutableSetOf<String>()
                collapsedSensors.filter { it.visible }.forEach { allSensors.add(it.entityId) }
                expandedSensors.filter { it.visible }.forEach { allSensors.add(it.entityId) }

                val config = JSONObject(oldTile.config)
                if (allSensors.size == 1) {
                    config.put("entity_id", allSensors.first())
                    config.remove("entity_ids")
                } else if (allSensors.size > 1) {
                    config.put("entity_ids", JSONArray(allSensors.toList()))
                    config.remove("entity_id")
                }

                val updatedTile = oldTile.copy(
                    appearance = appearance.toString(),
                    collapsedSensorIds = collapsedArray.toString(),
                    conditions = conditionsArray.toString(),
                    fontSize = fontSize,
                    icon = selectedIcon,
                    sourceType = when (spinnerDataSource.selectedItemPosition) {
                        1 -> "mqtt"
                        else -> "auto"
                    },
                    config = config.toString()
                )

                tiles[index] = updatedTile
                tileManager.saveTiles(tiles)

                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val REQUEST_SELECT_SENSORS = 2001
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
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        return makeMovementFlags(dragFlags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        adapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
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

        // TODO: Заполнить спиннер датчиками из кэша

        holder.etValue.setText(rule.value.toString())
        holder.vColorPreview.setBackgroundColor(Color.parseColor(rule.bgColor))

        holder.btnPickBgColor.setOnClickListener {
            // TODO: ColorPicker
        }

        holder.btnPickTextColor.setOnClickListener {
            // TODO: ColorPicker
        }

        holder.btnDeleteRule.setOnClickListener {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val spinnerSensor: Spinner = view.findViewById(R.id.spinnerSensor)
        val spinnerOperator: Spinner = view.findViewById(R.id.spinnerOperator)
        val etValue: EditText = view.findViewById(R.id.etValue)
        val vColorPreview: View = view.findViewById(R.id.vColorPreview)
        val btnPickBgColor: Button = view.findViewById(R.id.btnPickBgColor)
        val btnPickTextColor: Button = view.findViewById(R.id.btnPickTextColor)
        val btnDeleteRule: Button = view.findViewById(R.id.btnDeleteRule)
    }
}