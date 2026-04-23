package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class TileSettingsActivity : AppCompatActivity() {

    private lateinit var tileManager: TileManager
    private lateinit var etTitle: EditText
    private lateinit var spinnerType: Spinner
    private lateinit var tvSelectedSensors: TextView
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button
    private lateinit var btnAdvanced: Button
    private lateinit var btnSelectSensors: Button
    private lateinit var btnEditChildren: Button

    private var tileId: String? = null
    private val selectedSensors = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tile_settings)

        tileManager = TileManager(this)

        etTitle = findViewById(R.id.etTileTitle)
        spinnerType = findViewById(R.id.spinnerTileType)
        tvSelectedSensors = findViewById(R.id.tvSelectedSensors)
        btnSave = findViewById(R.id.btnSave)
        btnDelete = findViewById(R.id.btnDelete)
        btnAdvanced = findViewById(R.id.btnAdvanced)
        btnSelectSensors = findViewById(R.id.btnSelectSensors)
        btnEditChildren = findViewById(R.id.btnEditChildren)

        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.tile_types,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerType.adapter = adapter

        spinnerType.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Показываем кнопку "Редактировать группу" только для типа "Группа"
                val isGroup = position == 2 && tileId != null
                btnEditChildren.visibility = if (isGroup) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        tileId = intent.getStringExtra("tile_id")
        if (tileId != null) {
            loadTileData()
            btnDelete.visibility = View.VISIBLE
        }

        btnSelectSensors.setOnClickListener {
            val intent = Intent(this, SensorPickerActivity::class.java)
            intent.putStringArrayListExtra("selected_sensors", ArrayList(selectedSensors))
            startActivityForResult(intent, REQUEST_SELECT_SENSORS)
        }

        btnAdvanced.setOnClickListener {
            if (tileId == null) {
                saveTileWithoutFinish()
            }
            tileId?.let { id ->
                val intent = Intent(this, AdvancedWidgetSettingsActivity::class.java)
                intent.putExtra("tile_id", id)
                startActivity(intent)
            }
        }

        btnEditChildren.setOnClickListener {
            tileId?.let { id ->
                val intent = Intent(this, ChildButtonsEditorActivity::class.java)
                intent.putExtra("tile_id", id)
                startActivityForResult(intent, REQUEST_EDIT_CHILDREN)
            }
        }

        btnSave.setOnClickListener {
            saveTile()
        }

        btnDelete.setOnClickListener {
            deleteTile()
        }

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadTileData() {
        val tiles = tileManager.loadTiles()
        val tile = tiles.find { it.id == tileId } ?: return

        etTitle.setText(tile.title)

        val typeIndex = when (tile.type) {
            "sensor" -> 0
            "button" -> 1
            "group" -> 2
            else -> 0
        }
        spinnerType.setSelection(typeIndex)

        try {
            val configJson = JSONObject(tile.config)
            val arr = configJson.optJSONArray("entity_ids")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val id = arr.getString(i)
                    if (!selectedSensors.contains(id)) selectedSensors.add(id)
                }
            } else {
                val single = configJson.optString("entity_id", "")
                if (single.isNotEmpty() && !selectedSensors.contains(single)) selectedSensors.add(single)
            }
        } catch (e: Exception) {
            Log.e("TileSettings", "Error loading config: ${e.message}")
        }

        updateSelectedSensorsText()
    }

    private fun saveTileWithoutFinish() {
        val title = etTitle.text.toString().trim()
        if (title.isEmpty()) {
            Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
            return
        }

        val type = when (spinnerType.selectedItemPosition) {
            0 -> "sensor"
            1 -> "button"
            2 -> "group"
            else -> "sensor"
        }

        val container = if (type == "button" || type == "group") "bottom_panel" else "grid"

        val tiles = tileManager.getTilesByContainer(container)
        val position = tiles.size
        val spanCount = if (container == "grid") 4 else 6

        val existingConfig = if (tileId != null) {
            try {
                val tile = tileManager.loadTiles().find { it.id == tileId }
                if (tile != null) JSONObject(tile.config) else JSONObject()
            } catch (e: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }

        if (selectedSensors.size == 1) {
            existingConfig.put("entity_id", selectedSensors.first())
            existingConfig.remove("entity_ids")
        } else if (selectedSensors.size > 1) {
            existingConfig.put("entity_ids", JSONArray(selectedSensors))
            existingConfig.remove("entity_id")

            // Если создаём группу — создаём child_names по умолчанию
            if (type == "group" && !existingConfig.has("child_names")) {
                val names = JSONArray()
                selectedSensors.forEachIndexed { index, _ ->
                    names.put((index + 1).toString())
                }
                existingConfig.put("child_names", names)
            }
        }

        val config = existingConfig.toString()

        val tile = TileEntity(
            id = tileId ?: UUID.randomUUID().toString(),
            type = type,
            container = container,
            title = title,
            x = position % spanCount,
            y = position / spanCount,
            width = 1,
            height = 1,
            config = config
        )

        lifecycleScope.launch {
            if (tileId == null) {
                tileManager.addTile(tile)
                tileId = tile.id
            } else {
                tileManager.updateTile(tile)
            }
        }
    }

    private fun saveTile() {
        saveTileWithoutFinish()
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun deleteTile() {
        tileId?.let { id ->
            lifecycleScope.launch {
                tileManager.deleteTile(id)
                Toast.makeText(this@TileSettingsActivity, "Удалено", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }

    private fun updateSelectedSensorsText() {
        tvSelectedSensors.text = if (selectedSensors.isEmpty()) {
            getString(R.string.none_selected)
        } else {
            "Выбрано: ${selectedSensors.size} (${selectedSensors.joinToString(", ")})"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SELECT_SENSORS && resultCode == Activity.RESULT_OK) {
            data?.getStringArrayListExtra("selected_sensors")?.let {
                selectedSensors.clear()
                selectedSensors.addAll(it)
                updateSelectedSensorsText()
            }
        }
        if (requestCode == REQUEST_EDIT_CHILDREN && resultCode == Activity.RESULT_OK) {
            // Обновляем данные после редактирования дочерних кнопок
            loadTileData()
        }
    }

    companion object {
        private const val REQUEST_SELECT_SENSORS = 1001
        private const val REQUEST_EDIT_CHILDREN = 1002
    }
}