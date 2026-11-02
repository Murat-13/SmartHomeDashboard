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
    private var styleEditor: TileStyleEditor? = null
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
    /** Черновик сеанса после «Расширенных»/«Устройств»: в хранилище пишет только «Сохранить». */
    private var workingTile: TileEntity? = null
    /** Снимок формы при открытии: по нему уход спрашивает о несохранённых правках у любой плитки. */
    private var formBaseline = ""

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
                val isModern = typeAt(position) == BatteryModernConfig.TYPE || typeAt(position) == PowerModernConfig.TYPE ||
                    typeAt(position) == HomeClimateConfig.TYPE
                btnEditChildren.visibility = if (isGroup) View.VISIBLE else View.GONE
                btnSelectSensors.visibility = if (isGroup || isModern) View.GONE else View.VISIBLE
                btnClearSensors.visibility = if (isGroup || isModern) View.GONE else View.VISIBLE
                updateSelectedSensorsText()
                setSensorMode(position == 0)
                refreshTabs()
                if (isModern) btnAdvanced.visibility = View.GONE
                // Новая плитка профиля получает стандартное название, если пользователь его ещё не ввёл.
                if (tileId == null && (position == 7 || position == 8) && etTitle.text.isBlank()) {
                    etTitle.setText(PowerModernConfig.title(
                        if (position == 8) PowerModernConfig.PROFILE_INVERTER else PowerModernConfig.PROFILE_GRID))
                }
                // Показываем размер кнопки только для кнопок и групп
                val showSize = position == 1 || position == 2
                findViewById<View>(R.id.tvButtonSizeLabel).visibility = if (showSize) View.VISIBLE else View.GONE
                etButtonSize.visibility = if (showSize) View.VISIBLE else View.GONE
                styleEditor?.update()
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
        initializeStyleEditor(savedInstanceState)
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
            val draft = buildTile() ?: return@setOnClickListener
            workingTile = draft
            startActivityForResult(Intent(this, AdvancedWidgetSettingsActivity::class.java)
                .putExtra("tile_id", draft.id).putExtra(TileDraft.EXTRA, TileDraft.encode(draft)), REQUEST_ADVANCED_SETTINGS)
        }

        btnEditChildren.setOnClickListener {
            // Экран устройств работает со сохранённой группой: новую или только что ставшую
            // группой плитку сначала сохраняем.
            // Экран устройств работает с черновиком этой формы; ничего не записывается до «Сохранить».
            val draft = buildTile() ?: return@setOnClickListener
            workingTile = draft
            startActivityForResult(Intent(this, ChildButtonsEditorActivity::class.java)
                .putExtra("tile_id", draft.id).putExtra(TileDraft.EXTRA, TileDraft.encode(draft)), REQUEST_EDIT_CHILDREN)
        }

        btnSave.setOnClickListener { saveTile() }
        btnDelete.setOnClickListener {
            val dialog = ModernUi.dangerDialog(this, "Удалить плитку «${etTitle.text.toString().trim()}»?",
                "Устройства и настройки других плиток не изменятся.", "Удалить") { deleteTile() }
            if (isSensorEditorActive()) sensorEditor?.showDialog(dialog) else dialog.show()
        }

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            leaveEditor()
        }
        findViewById<Button>(R.id.btnSensorCancel).setOnClickListener { leaveEditor() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveEditor()
        })
        workingTile = TileDraft.decode(savedInstanceState?.getString(STATE_DRAFT))
        currentTab = savedInstanceState?.getInt(STATE_TAB, R.id.tabMain) ?: R.id.tabMain
        refreshTabs()
        formBaseline = savedInstanceState?.getString(STATE_BASELINE) ?: formSnapshot()
    }

    private fun formSnapshot() = listOf(etTitle.text.toString(), spinnerType.selectedItemPosition.toString(),
        selectedSensors.joinToString(","), etButtonSize.text.toString(), styleEditor?.style?.key.orEmpty(),
        styleEditor?.icon?.key.orEmpty()).joinToString("\u0001")

    /** Есть несохранённые правки обычной плитки (не сенсорного редактора). */
    private fun formDirty() = workingTile != null || sensorSelectionChanged || powerDraftChanged ||
        styleEditor?.powerEditor?.changed == true || formSnapshot() != formBaseline

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TILE_ID, tileId)
        workingTile?.let { outState.putString(STATE_DRAFT, TileDraft.encode(it)) }
        outState.putString(STATE_BASELINE, formBaseline)
        outState.putInt(STATE_TAB, currentTab)
        outState.putStringArrayList(STATE_SELECTED_SENSORS, ArrayList(selectedSensors))
        outState.putBoolean(STATE_SELECTION_CHANGED, sensorSelectionChanged)
        styleEditor?.let {
            outState.putString(STATE_STYLE, it.style.key)
            outState.putString(STATE_ICON, it.icon?.key)
            outState.putBoolean(STATE_ICON_CHANGED, it.iconChanged)
            outState.putString("modern_power_draft", JSONObject().also { json -> it.powerEditor.config.writeTo(json) }.toString())
            outState.putBoolean("modern_power_changed", it.powerEditor.changed || powerDraftChanged)
        }
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
            PowerModernConfig.TYPE -> powerIndex(tile.config)
            HomeClimateConfig.TYPE -> 9
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
        if (isSensorEditorActive()) {
            if (etTitle.text.toString().trim().isEmpty()) { buildTile(); return false }
            return saveSensorDraft()
        }
        val tile = buildTile() ?: return false
        lifecycleScope.launch {
            // Одна запись за весь сеанс, включая правки «Расширенных» и «Устройств».
            if (tileManager.loadTiles().none { it.id == tile.id }) tileManager.addTile(tile) else tileManager.updateTile(tile)
        }
        tileId = tile.id
        workingTile = null
        return true
    }

    /** Плитка по форме и черновику сеанса, без записи. null — ввод некорректен (ошибка уже показана). */
    private fun buildTile(): TileEntity? {
        val title = etTitle.text.toString().trim()
        if (title.isEmpty()) {
            etTitle.error = "Введите название"
            Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
            return null
        }

        val type = typeAt(spinnerType.selectedItemPosition)

        val container = when (type) {
            "button", "group" -> "bottom_panel"
            "clock", "sensor", "battery", "weather", BatteryModernConfig.TYPE, PowerModernConfig.TYPE, HomeClimateConfig.TYPE -> "grid"
            else -> "grid"
        }

        var oldTile: TileEntity? = workingTile
        val existingConfig = if (oldTile != null) {
            try { JSONObject(oldTile.config) } catch (e: Exception) { JSONObject() }
        } else if (tileId != null) {
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
                val previous = GroupChildren.fromConfig(existingConfig).items.associateBy { it.entityId }
                GroupChildren(selectedSensors.mapTo(mutableListOf()) { previous[it] ?: ChildButtonData(it, "") })
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
                return null
            }
            if (!unchanged) existingConfig.put("button_size", buttonSize)
            // Стиль записывается только у новой плитки или при фактической смене: старая запись без ключа остаётся Classic.
            styleEditor?.let {
                TileStyleEditor.writeIfChanged(existingConfig, it.style, isNew = oldTile == null)
                // Иконка — только если её меняли на этом экране; иначе config остаётся как был.
                ModernIcon.writeIfChanged(existingConfig, it.icon, it.iconChanged)
                if (type == "button") {
                    if (it.style == TileButtonStyle.MODERN && !it.powerEditor.validate()) return null
                    if (it.powerEditor.changed || powerDraftChanged) it.powerEditor.config.writeTo(existingConfig)
                }
            }
        }

        // Новая «АКБ Modern» получает готовый шаблон (без источников) и подходящий размер; существующие ключи не меняются.
        // «Электропитание Modern»: профиль выбранного пункта; у существующей плитки её настройки сохраняются.
        val config = when (type) {
            BatteryModernConfig.TYPE -> BatteryModernConfig.newTileConfig(existingConfig.toString())
            PowerModernConfig.TYPE -> PowerModernConfig.newTileConfig(
                if (spinnerType.selectedItemPosition == 8) PowerModernConfig.PROFILE_INVERTER else PowerModernConfig.PROFILE_GRID,
                existingConfig.toString())
            // «Часы · Погода · Климат»: размер 2:1 и пустой шаблон; сохранённые настройки не сбрасываются.
            HomeClimateConfig.TYPE -> HomeClimateConfig.newTileConfig(existingConfig.toString())
            else -> existingConfig.toString()
        }

        // При редактировании не сбрасываем параметры, которыми этот экран не управляет.
        val tile = oldTile?.copy(
            type = type,
            container = container,
            title = title,
            config = config
        ) ?: newTile(type, container, title, config)
        return tile
    }

    /** Пункт списка типов для профиля «Электропитание Modern»: 7 — Сеть · Вход, 8 — Инвертор · Выход. */
    private fun powerIndex(config: String) =
        if (PowerModernConfig.from(config).profile == PowerModernConfig.PROFILE_INVERTER) 8 else 7

    /** Порядок совпадает с массивом R.array.tile_types. */
    private fun typeAt(position: Int): String = when (position) {
        0 -> "sensor"
        1 -> "button"
        2 -> "group"
        3 -> "battery"
        4 -> "clock"
        5 -> "weather"
        6 -> BatteryModernConfig.TYPE
        7, 8 -> PowerModernConfig.TYPE
        9 -> HomeClimateConfig.TYPE
        else -> "sensor"
    }

    private fun newTile(type: String, container: String, title: String, config: String): TileEntity {
        // x/y — пиксели панели, а не номер ячейки: иначе все новые плитки ложатся в угол.
        val size = TilePlacement.size(container, config)
        val metrics = resources.displayMetrics
        // Новая плитка — на ту страницу, с которой её добавили; место ищется только среди её плиток.
        val page = intent.getIntExtra(EXTRA_PAGE, 0).coerceAtLeast(0)
        val (x, y) = TilePlacement.findFreeSpot(
            tileManager.getAllTiles().filter { it.page == page }, size.width, size.height, metrics.widthPixels, metrics.heightPixels
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
            config = config,
            page = page
        )
    }

    private fun saveTile() {
        if (!requireAdmin()) return
        // При ошибке остаёмся на экране: введённые данные не должны пропадать молча.
        if (!saveTileWithoutFinish()) return
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun deleteTile() {
        if (!requireAdmin()) return
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
            typeAt(spinnerType.selectedItemPosition) == HomeClimateConfig.TYPE ->
                "Погода и комнаты выбираются в редакторе плитки: в режиме редактирования нажмите на плитку."
            typeAt(spinnerType.selectedItemPosition) == BatteryModernConfig.TYPE ||
                typeAt(spinnerType.selectedItemPosition) == PowerModernConfig.TYPE ->
                "Датчики выбираются в редакторе АКБ Modern: в режиме редактирования нажмите на плитку."
            selectedSensors.isEmpty() -> getString(R.string.none_selected)
            else -> "Выбрано: ${selectedSensors.size} (${selectedSensors.joinToString(", ")})"
        }
        styleEditor?.update()
    }

    /** После экрана устройств группы источник правды — его черновик, а не старый выбор этого экрана. */
    private fun reloadGroupDevices(tile: TileEntity) {
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
            TileDraft.from(data)?.let { workingTile = it; reloadGroupDevices(it) }
        }
        if (requestCode == REQUEST_ADVANCED_SETTINGS && resultCode == Activity.RESULT_OK) {
            TileDraft.from(data)?.let { workingTile = it }
        }
    }

    override fun onStart() {
        super.onStart()
        previewJob?.cancel()
        TileEditorPreviewSource.states?.let { flow ->
            previewJob = lifecycleScope.launch {
                flow.collect {
                    if (isSensorEditorActive()) sensorEditor?.acceptState(it)
                    styleEditor?.acceptState(it)
                }
            }
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

    private var powerDraftChanged = false

    private fun initializeStyleEditor(saved: Bundle?) {
        val original = tileId?.let { id -> tileManager.loadTiles().find { it.id == id } }
        val initial = saved?.getString(STATE_STYLE)?.let { key -> TileButtonStyle.entries.find { it.key == key } }
            ?: original?.let { TileButtonStyle.of(it.config) }
            ?: TileButtonStyle.NEW_TILE
        val initialIcon = if (saved?.containsKey(STATE_ICON_CHANGED) == true) ModernIcon.fromKey(saved.getString(STATE_ICON))
            else original?.let { ModernIcon.fromConfig(it.config) }
        val editor = TileStyleEditor(this, initial, initialIcon, { etTitle.text.toString().trim() }, { typeAt(spinnerType.selectedItemPosition) },
            { etButtonSize.text.toString().toIntOrNull() ?: TilePlacement.BUTTON_SIZE },
            { selectedSensors.map { it to it } },
            initialPower = LivePowerConfig.read(saved?.getString("modern_power_draft") ?: original?.config ?: "{}"))
        powerDraftChanged = saved?.getBoolean("modern_power_changed") == true
        styleEditor = editor
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { editor.update() }
        }
        etTitle.addTextChangedListener(watcher)
        etButtonSize.addTextChangedListener(watcher)
        if (saved?.getBoolean(STATE_ICON_CHANGED) == true) editor.selectIcon(initialIcon)
        TileEditorPreviewSource.states?.value?.let(editor::acceptState) ?: editor.update()
    }

    private fun initializeSensorEditor(saved: Bundle?) {
        val original = tileId?.let { id -> tileManager.loadTiles().find { it.id == id } }
            ?: TileEntity("new-sensor-draft", "sensor", "grid", "", 0, 0, 1, 1)
        initialTitle = original.title
        initialType = if (original.type == PowerModernConfig.TYPE) powerIndex(original.config)
            else if (original.type == HomeClimateConfig.TYPE) 9
            else listOf("sensor", "button", "group", "battery", "clock", "weather", BatteryModernConfig.TYPE).indexOf(original.type).coerceAtLeast(0)
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
        listOf(R.id.legacySourcesLabel, R.id.legacySourceButtons, R.id.tvSelectedSensors, R.id.btnAdvanced).forEach {
            findViewById<View>(it).visibility = if (enabled) View.GONE else View.VISIBLE
        }
        // «Сохранить» и «Отмена» всегда в нижней панели — переносить кнопку больше не нужно.
    }

    private val tabSections = listOf(R.id.tabMain to "Основное", R.id.tabLook to "Вид", R.id.tabData to "Данные")
    private var currentTab = R.id.tabMain

    /** Вкладки только нужные типу: «Вид» — у кнопок и групп (стиль, иконка, предпросмотр). */
    private fun refreshTabs() {
        val tabs = findViewById<com.google.android.material.tabs.TabLayout?>(R.id.editorTabs) ?: return
        val look = findViewById<View?>(R.id.buttonStyleSection)?.visibility == View.VISIBLE
        val available = tabSections.filter { it.first != R.id.tabLook || look }
        if (available.none { it.first == currentTab }) currentTab = R.id.tabMain
        val same = tabs.tabCount == available.size && available.indices.all { tabs.getTabAt(it)?.tag == available[it].first }
        if (!same) {
            tabs.clearOnTabSelectedListeners()
            tabs.removeAllTabs()
            available.forEach { (id, label) -> tabs.addTab(tabs.newTab().setText(label).setTag(id), id == currentTab) }
            tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) = showTab(tab.tag as Int)
                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            })
        }
        showTab(currentTab)
    }

    private fun showTab(id: Int) {
        currentTab = id
        tabSections.forEach { (section, _) -> findViewById<View?>(section)?.visibility = if (section == id) View.VISIBLE else View.GONE }
        findViewById<View?>(R.id.editorScroll)?.scrollTo(0, 0)
    }

    private fun leaveEditor() {
        val sensor = isSensorEditorActive()
        val dirty = if (sensor) sensorEditor!!.draft.isDirty || etTitle.text.toString() != initialTitle ||
            spinnerType.selectedItemPosition != initialType else formDirty()
        if (!dirty) { finish(); return }
        val dialog = ModernUi.leaveDialog(this, if (sensor) "Показания и цветовые правила пока не записаны."
            else "Изменения плитки пока не записаны.", onSave = { saveTile() }, onDiscard = { finish() })
        if (sensor) sensorEditor?.showDialog(dialog) else dialog.show()
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
        const val EXTRA_PAGE = "page"
        private const val STATE_TILE_ID = "tile_settings_tile_id"
        private const val STATE_DRAFT = "tile_settings_draft"
        private const val STATE_BASELINE = "tile_settings_baseline"
        private const val STATE_TAB = "tile_settings_tab"
        private const val STATE_SELECTED_SENSORS = "tile_settings_selected_sensors"
        private const val STATE_SELECTION_CHANGED = "tile_settings_selection_changed"
        private const val STATE_STYLE = "tile_settings_button_style"
        private const val STATE_ICON = "tile_settings_modern_icon"
        private const val STATE_ICON_CHANGED = "tile_settings_modern_icon_changed"
        private const val REQUEST_SELECT_SENSORS = 1001
        private const val REQUEST_EDIT_CHILDREN = 1002
        private const val REQUEST_ADVANCED_SETTINGS = 1003
    }
}
