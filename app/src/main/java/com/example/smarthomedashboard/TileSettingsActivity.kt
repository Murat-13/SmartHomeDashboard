package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class TileSettingsActivity : PinProtectedActivity() {

    private lateinit var tileManager: TileManager
    private lateinit var etTitle: EditText
    private lateinit var spinnerType: Spinner
    private lateinit var tvSelectedSensors: TextView
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button
    private lateinit var btnAdvanced: Button
    private lateinit var btnSelectSensors: Button
    private lateinit var btnClearSensors: Button
    private lateinit var btnEditChildren: Button
    private lateinit var etButtonSize: EditText

    private var previewJob: Job? = null
    private var sensorEditor: SensorTileEditor? = null
    private val sensorPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        sensorEditor?.pickerResult(if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra("selected_sensors") else null)
    }
    private var editorRestoreError = false
    private var saveParent: LinearLayout? = null
    private var savePosition = 0
    private var saveParameters: android.view.ViewGroup.LayoutParams? = null
    private var initialTitle = ""
    private var initialType = 0
    private var entityNames: Map<String, String> = emptyMap()

    private var tileId: String? = null
    private val selectedSensors = mutableListOf<String>()
    // Пустой список после загрузки не равнозначен подтверждённой пользователем очистке.
    private var sensorSelectionChanged = false

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
        btnClearSensors = findViewById(R.id.btnClearSensors)
        btnEditChildren = findViewById(R.id.btnEditChildren)
        etButtonSize = findViewById(R.id.etButtonSize)

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, resources.getStringArray(R.array.tile_types)) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also { (it as TextView).setTextColor(android.graphics.Color.WHITE) }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerType.adapter = adapter

        val typeHint = findViewById<View>(R.id.tileTypeHint)
        fun updateTypeHint() {
            findViewById<TextView>(R.id.tvTileTypeHint).text = UserHints.tileType(typeAt(spinnerType.selectedItemPosition))
            typeHint.visibility = if (UserHints.isHidden(this, UserHints.Hint.TILE_TYPE)) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.btnTileTypeHintClose).setOnClickListener {
            UserHints.hideWithUndo(typeHint, UserHints.Hint.TILE_TYPE, refresh = ::updateTypeHint)
        }

        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateTypeHint()
                // У группы устройства и имена кнопок редактируются в одном месте — на экране устройств группы.
                val isGroup = position == 2
                // Источники «АКБ Modern» выбираются по каждому показателю в её редакторе.
                val isModern = typeAt(position) == BatteryModernConfig.TYPE
                btnEditChildren.visibility = if (isGroup) View.VISIBLE else View.GONE
                btnSelectSensors.visibility = if (isGroup || isModern) View.GONE else View.VISIBLE
                btnClearSensors.visibility = if (isGroup || isModern) View.GONE else View.VISIBLE
                updateSelectedSensorsText()
                setSensorMode(position == 0)
                if (isModern) btnAdvanced.visibility = View.GONE
                // Показываем размер кнопки только для кнопок и групп
                val showSize = position == 1 || position == 2
                findViewById<View>(R.id.tvButtonSizeLabel).visibility = if (showSize) View.VISIBLE else View.GONE
                etButtonSize.visibility = if (showSize) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        tileId = savedInstanceState?.getString(STATE_TILE_ID) ?: intent.getStringExtra("tile_id")
        if (tileId != null) {
            loadTileData()
            btnDelete.visibility = View.VISIBLE
        }

        restoreSensorSelection(savedInstanceState)
        initializeSensorEditor(savedInstanceState)
        updateTypeHint()
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).navigationIcon?.setTint(android.graphics.Color.WHITE)

        btnSelectSensors.setOnClickListener {
            val intent = Intent(this, SensorPickerActivity::class.java)
            intent.putStringArrayListExtra("selected_sensors", ArrayList(selectedSensors))
            startActivityForResult(intent, REQUEST_SELECT_SENSORS)
        }

        btnClearSensors.setOnClickListener { clearSelectedSensors() }

        btnAdvanced.setOnClickListener {
            if (isSensorEditorActive()) return@setOnClickListener
            if (tileId == null && !saveTileWithoutFinish()) return@setOnClickListener
            tileId?.let { id ->
                val intent = Intent(this, AdvancedWidgetSettingsActivity::class.java)
                intent.putExtra("tile_id", id)
                startActivityForResult(intent, REQUEST_ADVANCED_SETTINGS)
            }
        }

        btnEditChildren.setOnClickListener {
            // Экран устройств работает со сохранённой группой: новую или только что ставшую
            // группой плитку сначала сохраняем.
            val savedType = tileId?.let { id -> tileManager.loadTiles().find { it.id == id }?.type }
            if (savedType != "group" && !saveTileWithoutFinish()) return@setOnClickListener
            tileId?.let { id ->
                val intent = Intent(this, ChildButtonsEditorActivity::class.java)
                intent.putExtra("tile_id", id)
                startActivityForResult(intent, REQUEST_EDIT_CHILDREN)
            }
        }

        btnSave.setOnClickListener { saveTile() }
        btnDelete.setOnClickListener {
            if (isSensorEditorActive()) sensorEditor?.showDialog(AlertDialog.Builder(this)
                .setTitle("Удалить плитку?").setMessage("Устройства и цветовые правила других плиток не изменятся.")
                .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ -> deleteTile() }.create())
            else deleteTile()
        }

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            leaveEditor()
        }
        findViewById<Button>(R.id.btnSensorCancel).setOnClickListener { leaveEditor() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveEditor()
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TILE_ID, tileId)
        outState.putStringArrayList(STATE_SELECTED_SENSORS, ArrayList(selectedSensors))
        outState.putBoolean(STATE_SELECTION_CHANGED, sensorSelectionChanged)
        sensorEditor?.let { editor ->
            outState.putString("sensor_draft", editor.draft.saveState())
            outState.putString("sensor_preview", editor.saveUiState())
            outState.putString("sensor_pick_mode", editor.pendingPick?.mode)
            outState.putInt("sensor_pick_index", editor.pendingPick?.index ?: -1)
        }
        super.onSaveInstanceState(outState)
    }

    private fun restoreSensorSelection(savedInstanceState: Bundle?) {
        if (savedInstanceState?.containsKey(STATE_SELECTED_SENSORS) != true) return
        selectedSensors.clear()
        selectedSensors.addAll(savedInstanceState.getStringArrayList(STATE_SELECTED_SENSORS).orEmpty())
        sensorSelectionChanged = savedInstanceState.getBoolean(STATE_SELECTION_CHANGED, false)
        updateSelectedSensorsText()
    }

    private fun clearSelectedSensors() {
        selectedSensors.clear()
        sensorSelectionChanged = true
        updateSelectedSensorsText()
        Toast.makeText(this, "Выбор очищен", Toast.LENGTH_SHORT).show()
    }

    private fun loadTileData() {
        val tiles = tileManager.loadTiles()
        val tile = tiles.find { it.id == tileId } ?: return

        etTitle.setText(tile.title)

        val typeIndex = when (tile.type) {
            "sensor" -> 0
            "button" -> 1
            "group" -> 2
            "battery" -> 3
            "clock" -> 4
            "weather" -> 5
            BatteryModernConfig.TYPE -> 6
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

            // Загружаем размер кнопки
            val buttonSize = configJson.optInt("button_size", 160)
            etButtonSize.setText(buttonSize.toString())
        } catch (e: Exception) {
            Log.e("TileSettings", "Не удалось прочитать конфигурацию плитки")
        }

        updateSelectedSensorsText()
    }

    /** Возвращает false, если ввод некорректен и ничего не сохранено. */
    private fun saveTileWithoutFinish(): Boolean {
        val title = etTitle.text.toString().trim()
        if (title.isEmpty()) {
            etTitle.error = "Введите название"
            Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
            return false
        }

        if (isSensorEditorActive()) return saveSensorDraft()
        val type = typeAt(spinnerType.selectedItemPosition)

        val container = when (type) {
            "button", "group" -> "bottom_panel"
            "clock", "sensor", "battery", "weather", BatteryModernConfig.TYPE -> "grid"
            else -> "grid"
        }

        var oldTile: TileEntity? = null
        val existingConfig = if (tileId != null) {
            try {
                val tile = tileManager.loadTiles().find { it.id == tileId }
                oldTile = tile
                if (tile != null) JSONObject(tile.config) else JSONObject()
            } catch (e: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }

        // Общий выбор управляет только этими двумя ключами. Без явного изменения
        // сохраняем старый формат как есть, включая совместимые конфигурации с обоими полями.
        if (sensorSelectionChanged) {
            if (selectedSensors.isEmpty()) {
                existingConfig.remove("entity_id")
                existingConfig.remove("entity_ids")
            } else if (selectedSensors.size == 1 && type != "group") {
                existingConfig.put("entity_id", selectedSensors.first())
                existingConfig.remove("entity_ids")
            } else if (type == "group") {
                // Имя кнопки едет вместе со своим устройством: удаление или перестановка
                // не сдвигают имена. Новые устройства получают номер кнопки.
                val previous = GroupChildren.fromConfig(existingConfig).items.associate { it.entityId to it.name }
                GroupChildren(selectedSensors.mapTo(mutableListOf()) { ChildButtonData(it, previous[it].orEmpty()) })
                    .writeTo(existingConfig)
            } else {
                existingConfig.put("entity_ids", JSONArray(selectedSensors))
                existingConfig.remove("entity_id")
            }
        }

        // Поле размера доступно только кнопкам и группам. Скрытое значение
        // не должно менять сохранённый размер датчика, батареи, часов или погоды.
        if (type == "button" || type == "group") {
            val input = etButtonSize.text.toString()
            val buttonSize = if (input.isEmpty()) TilePlacement.BUTTON_SIZE else input.toIntOrNull()
            // Старый чрезмерный размер не мешает изменить название: панель ограничит только View.
            // Новое недопустимое значение нельзя записать даже вместе с другими полями формы.
            val unchanged = oldTile != null && existingConfig.has("button_size") &&
                input == existingConfig.optInt("button_size", TilePlacement.BUTTON_SIZE).toString()
            val metrics = resources.displayMetrics
            val maximum = TilePlacement.maxButtonSize(metrics.widthPixels, metrics.heightPixels)
            if (!unchanged && (buttonSize == null || buttonSize !in 1..maximum)) {
                etButtonSize.error = "Размер должен быть от 1 до $maximum px"
                return false
            }
            if (!unchanged) existingConfig.put("button_size", buttonSize)
        }

        // Новая «АКБ Modern» получает готовый шаблон (без источников) и подходящий размер; существующие ключи не меняются.
        val config = if (type == BatteryModernConfig.TYPE) BatteryModernConfig.newTileConfig(existingConfig.toString())
            else existingConfig.toString()

        // При редактировании не сбрасываем параметры, которыми этот экран не управляет.
        val tile = oldTile?.copy(
            type = type,
            container = container,
            title = title,
            config = config
        ) ?: newTile(type, container, title, config)

        lifecycleScope.launch {
            if (tileId == null) {
                tileManager.addTile(tile)
                tileId = tile.id
            } else {
                tileManager.updateTile(tile)
            }
        }
        return true
    }

    /** Порядок совпадает с массивом R.array.tile_types. */
    private fun typeAt(position: Int): String = when (position) {
        0 -> "sensor"
        1 -> "button"
        2 -> "group"
        3 -> "battery"
        4 -> "clock"
        5 -> "weather"
        6 -> BatteryModernConfig.TYPE
        else -> "sensor"
    }

    private fun newTile(type: String, container: String, title: String, config: String): TileEntity {
        // x/y — пиксели панели, а не номер ячейки: иначе все новые плитки ложатся в угол.
        val size = TilePlacement.size(container, config)
        val metrics = resources.displayMetrics
        val (x, y) = TilePlacement.findFreeSpot(
            tileManager.getAllTiles(), size.width, size.height, metrics.widthPixels, metrics.heightPixels
        )
        return TileEntity(
            id = tileId ?: UUID.randomUUID().toString(),
            type = type,
            container = container,
            title = title,
            x = x,
            y = y,
            width = 1,
            height = 1,
            config = config
        )
    }

    private fun saveTile() {
        // При ошибке остаёмся на экране: введённые данные не должны пропадать молча.
        if (!saveTileWithoutFinish()) return
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
        val isGroup = typeAt(spinnerType.selectedItemPosition) == "group"
        tvSelectedSensors.text = when {
            isGroup && selectedSensors.isEmpty() -> "В группе пока нет устройств"
            isGroup -> "Устройств в группе: ${selectedSensors.size}"
            typeAt(spinnerType.selectedItemPosition) == BatteryModernConfig.TYPE ->
                "Датчики выбираются в редакторе АКБ Modern: в режиме редактирования нажмите на плитку."
            selectedSensors.isEmpty() -> getString(R.string.none_selected)
            else -> "Выбрано: ${selectedSensors.size} (${selectedSensors.joinToString(", ")})"
        }
    }

    /** После экрана устройств группы источник правды — сохранённая плитка, а не старый выбор этого экрана. */
    private fun reloadGroupDevices() {
        val tile = tileId?.let { id -> tileManager.loadTiles().find { it.id == id } } ?: return
        val ids = try { GroupChildren.fromConfig(JSONObject(tile.config)).items.map { it.entityId } } catch (_: Exception) { return }
        selectedSensors.clear()
        selectedSensors.addAll(ids)
        sensorSelectionChanged = false
        updateSelectedSensorsText()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SELECT_SENSORS && resultCode == Activity.RESULT_OK) {
            data?.getStringArrayListExtra("selected_sensors")?.let {
                // Подтверждённый пустой результат означает очистку даже для старого
                // неоднозначного config. Неизменённый непустой выбор не нормализуем.
                sensorSelectionChanged = sensorSelectionChanged || it.isEmpty() || selectedSensors != it
                selectedSensors.clear()
                selectedSensors.addAll(it)
                updateSelectedSensorsText()
            }
        }
        if (requestCode == REQUEST_EDIT_CHILDREN && resultCode == Activity.RESULT_OK) {
            reloadGroupDevices()
            // Устройства уже сохранены: панель должна обновиться, даже если здесь нажмут «назад».
            setResult(Activity.RESULT_OK)
        }
        if (requestCode == REQUEST_ADVANCED_SETTINGS) {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        previewJob?.cancel()
        TileEditorPreviewSource.states?.let { flow ->
            previewJob = lifecycleScope.launch { flow.collect { if (isSensorEditorActive()) sensorEditor?.acceptState(it) } }
        }
    }

    override fun onStop() {
        previewJob?.cancel()
        previewJob = null
        super.onStop()
    }

    override fun onDestroy() {
        sensorEditor?.dispose()
        super.onDestroy()
    }

    private fun isSensorEditorActive() = sensorEditor != null && spinnerType.selectedItemPosition == 0

    private fun initializeSensorEditor(saved: Bundle?) {
        val original = tileId?.let { id -> tileManager.loadTiles().find { it.id == id } }
            ?: TileEntity("new-sensor-draft", "sensor", "grid", "", 0, 0, 1, 1)
        initialTitle = original.title
        initialType = listOf("sensor", "button", "group", "battery", "clock", "weather", BatteryModernConfig.TYPE).indexOf(original.type).coerceAtLeast(0)
        val draft = try {
            saved?.getString("sensor_draft")?.let(SensorTileDraft::restore) ?: SensorTileDraft(original)
        } catch (_: Exception) {
            editorRestoreError = true
            Toast.makeText(this, "Черновик не восстановлен. Сохранение заблокировано, исходная плитка не изменена.", Toast.LENGTH_LONG).show()
            SensorTileDraft(original)
        }
        val root = findViewById<LinearLayout>(R.id.sensorEditorContainer)
        sensorEditor = SensorTileEditor(this, draft, root, { etTitle.text.toString() },
            { id -> entityNames[id] ?: id.substringAfter('.').replace('_', ' ') }) { selected, single ->
            val intent = Intent(this, SensorPickerActivity::class.java)
                .putStringArrayListExtra("selected_sensors", ArrayList(selected))
                .putExtra(SensorPickerActivity.EXTRA_SINGLE_CHOICE, single)
            sensorPicker.launch(intent)
        }
        saved?.getString("sensor_preview")?.let { state ->
            try { sensorEditor?.restoreUiState(state) } catch (_: org.json.JSONException) { editorRestoreError = true }
        }
        saved?.getString("sensor_pick_mode")?.let { mode ->
            sensorEditor?.pendingPick = SensorTileEditor.PickTarget(mode, saved.getInt("sensor_pick_index", -1))
        }
        etTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { sensorEditor?.updatePreview() }
        })
        saveParent = btnSave.parent as LinearLayout
        savePosition = saveParent!!.indexOfChild(btnSave)
        saveParameters = btnSave.layoutParams
        setSensorMode(spinnerType.selectedItemPosition == 0)
        lifecycleScope.launch {
            val cached = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getString("cached_entities", "[]").orEmpty()
            entityNames = withContext(Dispatchers.Default) {
                try {
                    val array = JSONArray(cached)
                    buildMap {
                        repeat(array.length()) {
                            val obj = array.optJSONObject(it) ?: return@repeat
                            val id = obj.optString("entityId")
                            val name = obj.optString("name")
                            if (id.isNotBlank() && name.isNotBlank()) put(id, name)
                        }
                    }
                } catch (_: org.json.JSONException) { emptyMap() }
            }
        }
    }

    private fun setSensorMode(enabled: Boolean) {
        if (sensorEditor == null) return
        val visible = if (enabled) View.VISIBLE else View.GONE
        if (enabled) TileEditorPreviewSource.states?.value?.let { sensorEditor?.acceptState(it) }
        findViewById<View>(R.id.sensorEditorContainer).visibility = visible
        findViewById<View>(R.id.sensorEditorFooter).visibility = visible
        listOf(R.id.legacySourcesLabel, R.id.legacySourceButtons, R.id.tvSelectedSensors, R.id.btnAdvanced).forEach {
            findViewById<View>(it).visibility = if (enabled) View.GONE else View.VISIBLE
        }
        val footer = findViewById<LinearLayout>(R.id.sensorEditorFooter)
        if (enabled && btnSave.parent !== footer) {
            (btnSave.parent as android.view.ViewGroup).removeView(btnSave)
            footer.addView(btnSave, LinearLayout.LayoutParams(0, (56 * resources.displayMetrics.density).toInt(), 1f))
        } else if (!enabled && btnSave.parent !== saveParent) {
            (btnSave.parent as android.view.ViewGroup).removeView(btnSave)
            saveParent?.addView(btnSave, savePosition, saveParameters)
        }
    }

    private fun leaveEditor() {
        val dirty = isSensorEditorActive() && (sensorEditor!!.draft.isDirty || etTitle.text.toString() != initialTitle || spinnerType.selectedItemPosition != initialType)
        if (!dirty) { finish(); return }
        sensorEditor?.showDialog(AlertDialog.Builder(this).setTitle("Сохранить изменения?")
            .setMessage("Показания и цветовые правила пока не записаны.")
            .setPositiveButton("Сохранить") { _, _ -> saveTile() }
            .setNegativeButton("Не сохранять") { _, _ -> finish() }
            .setNeutralButton("Продолжить редактирование", null).create())
    }

    private fun saveSensorDraft(): Boolean {
        val editor = requireNotNull(sensorEditor)
        val problem = if (editorRestoreError) "Черновик не восстановлен. Закройте редактор без сохранения." else editor.validationError()
        if (problem != null) { Toast.makeText(this, problem, Toast.LENGTH_LONG).show(); return false }
        val latest = tileId?.let { id -> tileManager.loadTiles().find { it.id == id } }
        if (tileId != null && latest != editor.draft.original) {
            Toast.makeText(this, "Плитка изменена другим экраном. Закройте редактор и откройте её заново.", Toast.LENGTH_LONG).show()
            return false
        }
        val changed = editor.draft.applyTo(editor.draft.original.copy(title = etTitle.text.toString().trim(), type = "sensor", container = "grid"))
        val tile = latest?.let { changed } ?: newTile("sensor", "grid", changed.title, changed.config)
            .copy(fontSize = changed.fontSize, colorRules = changed.colorRules, collapsedSensorIds = changed.collapsedSensorIds)
        // Один вызов заменяет всю плитку, без промежуточных записей из вложенных экранов.
        if (latest == null) { tileManager.addTile(tile); tileId = tile.id } else tileManager.updateTile(tile)
        return true
    }

    companion object {
        private const val STATE_TILE_ID = "tile_settings_tile_id"
        private const val STATE_SELECTED_SENSORS = "tile_settings_selected_sensors"
        private const val STATE_SELECTION_CHANGED = "tile_settings_selection_changed"
        private const val REQUEST_SELECT_SENSORS = 1001
        private const val REQUEST_EDIT_CHILDREN = 1002
        private const val REQUEST_ADVANCED_SETTINGS = 1003
    }
}
