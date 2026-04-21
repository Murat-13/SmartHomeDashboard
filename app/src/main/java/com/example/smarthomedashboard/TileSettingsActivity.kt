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

        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.tile_types,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerType.adapter = adapter

        tileId = intent.getStringExtra("tile_id")
        if (tileId != null) {
            loadTileData()
            btnDelete.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.btnSelectSensors).setOnClickListener {
            val intent = Intent(this, SensorPickerActivity::class.java)
            intent.putStringArrayListExtra("selected_sensors", ArrayList(selectedSensors))
            startActivityForResult(intent, REQUEST_SELECT_SENSORS)
        }

        btnAdvanced.setOnClickListener {
            val intent = Intent(this, AdvancedWidgetSettingsActivity::class.java)
            intent.putExtra("tile_id", tileId)
            startActivity(intent)
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
                    selectedSensors.add(arr.getString(i))
                }
            } else {
                val single = configJson.optString("entity_id", "")
                if (single.isNotEmpty()) selectedSensors.add(single)
            }
        } catch (_: Exception) {
        }

        updateSelectedSensorsText()
    }

    private fun saveTile() {
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

        val configJson = JSONObject()
        if (selectedSensors.size == 1) {
            configJson.put("entity_id", selectedSensors.first())
        } else if (selectedSensors.size > 1) {
            configJson.put("entity_ids", JSONArray(selectedSensors))
        }
        val config = configJson.toString()

        Log.d("TileSettings", "Saving tile: title=$title, type=$type, config=$config")

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
            } else {
                tileManager.updateTile(tile)
            }
            Toast.makeText(this@TileSettingsActivity, "Сохранено", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK)
            finish()
        }
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
            "Выбрано: ${selectedSensors.size}"
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
    }

    companion object {
        private const val REQUEST_SELECT_SENSORS = 1001
    }
}