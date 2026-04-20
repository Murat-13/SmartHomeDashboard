package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import java.util.UUID

class TileSettingsActivity : AppCompatActivity() {

    private lateinit var tileManager: TileManager
    private lateinit var etTitle: EditText
    private lateinit var spinnerType: Spinner
    private lateinit var tvSelectedSensors: TextView
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button

    private var tileId: String? = null
    private val selectedSensors = mutableListOf<String>()
    private var container = "grid"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tile_settings)

        tileManager = TileManager(this)

        etTitle = findViewById(R.id.etTileTitle)
        spinnerType = findViewById(R.id.spinnerTileType)
        tvSelectedSensors = findViewById(R.id.tvSelectedSensors)
        btnSave = findViewById(R.id.btnSave)
        btnDelete = findViewById(R.id.btnDelete)

        container = intent.getStringExtra("container") ?: "grid"

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

        val tiles = tileManager.getTilesByContainer(container)
        val position = tiles.size
        val spanCount = 4

        val tile = TileEntity(
            id = tileId ?: UUID.randomUUID().toString(),
            type = type,
            container = container,
            title = title,
            x = position % spanCount,
            y = position / spanCount,
            width = 1,
            height = 1,
            config = "{}"
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
        if (selectedSensors.isEmpty()) {
            tvSelectedSensors.text = getString(R.string.none_selected)
        } else {
            tvSelectedSensors.text = "Выбрано: ${selectedSensors.size}"
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