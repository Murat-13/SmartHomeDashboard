package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Редактор «АКБ Modern»: предпросмотр развёрнутой карточки, где каждая зона и строка
 * открывает настройку показателя. Данные — только уже полученные ViewModel панели
 * (без нового подключения к HA и без команд устройствам). Запись — только «Сохранить».
 */
class BatteryModernEditorActivity : PinProtectedActivity(), BatteryModernCard.Editor {
    private lateinit var tileManager: TileManager
    internal lateinit var draft: BatteryModernDraft
        private set
    internal lateinit var preview: BatteryModernCard
        private set
    private var model = MainUiState()
    private var previewJob: Job? = null
    private var cachedEntities: Map<String, String> = emptyMap()
    private val dialogs = linkedSetOf<AlertDialog>()
    private lateinit var invertSwitch: SwitchCompat
    private lateinit var hideSwitch: SwitchCompat
    private lateinit var thresholdInput: EditText
    private var syncingControls = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tileManager = TileManager(this)
        val tile = intent.getStringExtra("tile_id")?.let { id -> tileManager.loadTiles().find { it.id == id } }
        if (tile == null || tile.type != BatteryModernConfig.TYPE) {
            Toast.makeText(this, "Плитка не найдена", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        draft = savedInstanceState?.getString(STATE_DRAFT)?.let {
            try { BatteryModernDraft.restore(it, tile) } catch (_: Exception) { null }
        } ?: BatteryModernDraft(tile)
        TileEditorPreviewSource.states?.value?.let { model = it }
        buildUi()
        render()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        lifecycleScope.launch {
            val cached = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getString("cached_entities", "[]").orEmpty()
            cachedEntities = withContext(Dispatchers.Default) { parseCachedEntities(cached) }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 16, 20))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(label("АКБ Modern — настройка", 22f, bold = true))

        val hint = hintBar(UserHints.Hint.BATTERY_MODERN_EDITOR,
            "Нажмите на любую зону или строку, чтобы выбрать сущность Home Assistant, подпись, иконку, " +
                "точность и единицу. Изменения применяются только кнопкой «Сохранить».")
        root.addView(hint)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        preview = BatteryModernCard(this, expanded = true, editor = this)
        val previewFrame = FrameLayout(this).apply { tag = "bm_editor_preview" }
        previewFrame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(previewFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.2f))

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        invertSwitch = SwitchCompat(this).apply {
            text = "Инвертировать знак мощности\n(по умолчанию: минус — разряд, плюс — заряд)"
            setTextColor(Color.WHITE)
            tag = "bm_invert"
            setOnCheckedChangeListener { _, checked -> if (!syncingControls) { draft.setInvertPowerSign(checked); render() } }
        }
        side.addView(invertSwitch)
        side.addView(label("Порог «Ожидание» (в единицах датчика мощности)", 14f))
        thresholdInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE)
            tag = "bm_threshold"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (syncingControls) return
                    error = if (draft.setIdleThreshold(s.toString())) null else "Введите неотрицательное число"
                    render()
                }
            })
        }
        side.addView(thresholdInput)
        hideSwitch = SwitchCompat(this).apply {
            text = "Скрывать строки без источника\n(иначе показывать «—»)"
            setTextColor(Color.WHITE)
            tag = "bm_hide_unconfigured"
            setOnCheckedChangeListener { _, checked -> if (!syncingControls) { draft.setHideUnconfigured(checked); render() } }
        }
        side.addView(hideSwitch)
        BatteryModernConfig.Section.entries.forEach { section ->
            side.addView(button("+ Строка в «${section.title}»", "bm_add_${section.key}") {
                // Новая строка попадает в черновик только по «Готово» диалога.
                editRowDialog(draft.newRow(section), isNew = true)
            })
        }
        side.addView(button("Название, тип, удаление плитки…", "bm_tile_settings") { openTileSettings() })
        val sideScroll = ScrollView(this).apply { addView(side) }
        body.addView(sideScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        footer.addView(button("Отмена", "bm_cancel") { leave() })
        footer.addView(button("Сохранить", "bm_save") { save() })
        root.addView(footer)
        setContentView(root)
    }

    private fun render() {
        syncingControls = true
        invertSwitch.isChecked = draft.config.invertPowerSign
        hideSwitch.isChecked = draft.config.hideUnconfigured
        if (BatteryFormat.number(thresholdInput.text.toString()) != draft.config.idleThreshold) {
            thresholdInput.setText(BatteryFormat.decimal(draft.config.idleThreshold, 0, java.util.Locale.ROOT))
        }
        syncingControls = false
        preview.bind(draft.title, draft.config, BatteryReadings.from(model), tempUnit())
    }

    private fun tempUnit() = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getString("temp_unit", "C") ?: "C"

    override fun onStart() {
        super.onStart()
        previewJob?.cancel()
        TileEditorPreviewSource.states?.let { flow ->
            previewJob = lifecycleScope.launch {
                flow.collect {
                    model = it
                    if (::preview.isInitialized) render()
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
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
        if (::preview.isInitialized) preview.dispose()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::draft.isInitialized) outState.putString(STATE_DRAFT, draft.saveState())
        super.onSaveInstanceState(outState)
    }

    // ---- BatteryModernCard.Editor ----

    override fun editRole(role: String) = editRowDialog(draft.rowForRole(role), isNew = draft.config.row(role) == null)

    override fun editRow(id: String) {
        draft.row(id)?.let { editRowDialog(it, isNew = false) }
    }

    override fun editHeader() {
        val titleInput = input(draft.title, "Название").apply { tag = "bm_header_title" }
        val subtitleInput = input(draft.config.subtitle, "Подпись, например «Литий • 48 В»").apply { tag = "bm_header_subtitle" }
        val content = column(label("Название", 14f), titleInput, label("Подпись под названием", 14f), subtitleInput)
        show(AlertDialog.Builder(this).setTitle("Заголовок").setView(content)
            .setPositiveButton("Готово") { _, _ ->
                draft.setHeader(titleInput.text.toString(), subtitleInput.text.toString().trim())
                render()
            }
            .setNegativeButton("Отмена", null).create())
    }

    internal var openRowDialog: AlertDialog? = null
        private set

    /** Изменения строки попадают в черновик по «Готово»; в prefs — только по «Сохранить». */
    internal fun editRowDialog(row: BatteryModernConfig.Row, isNew: Boolean) {
        var entityId = row.entityId
        val source = label("", 15f).apply { tag = "bm_row_source" }
        fun showSource() {
            source.text = if (entityId.isBlank()) "Источник не выбран" else "${entityName(entityId)}\n$entityId"
        }
        showSource()
        val pick = button("Выбрать сущность HA", "bm_row_pick") {
            pickEntity(entityId) { picked -> entityId = picked; showSource() }
        }
        val clear = button("Очистить источник", "bm_row_clear") { entityId = ""; showSource() }
        val labelInput = input(row.label, "Подпись").apply { tag = "bm_row_label" }
        val icons = BatteryIcons.all.entries.toList()
        val iconSpinner = Spinner(this).apply {
            tag = "bm_row_icon"
            adapter = whiteAdapter(icons.map { "${it.value.first}  ${it.value.second}" })
            setSelection(icons.indexOfFirst { it.key == row.icon }.coerceAtLeast(0))
        }
        val precisions = listOf<Int?>(null, 0, 1, 2, 3, 4)
        val precisionSpinner = Spinner(this).apply {
            tag = "bm_row_decimals"
            adapter = whiteAdapter(precisions.map { it?.let { d -> "$d знак(ов) после запятой" } ?: "Как в Home Assistant" })
            setSelection(precisions.indexOf(row.decimals).coerceAtLeast(0))
        }
        val unitInput = input(row.unit, "Пусто — единица из HA").apply { tag = "bm_row_unit" }
        val hidden = SwitchCompat(this).apply {
            text = "Скрыть строку"
            setTextColor(Color.WHITE)
            isChecked = row.hidden
            tag = "bm_row_hidden"
        }
        fun edited() = row.copy(
            entityId = entityId,
            label = labelInput.text.toString().trim(),
            icon = icons[iconSpinner.selectedItemPosition.coerceAtLeast(0)].key,
            decimals = precisions[precisionSpinner.selectedItemPosition.coerceAtLeast(0)],
            unit = unitInput.text.toString().trim(),
            hidden = hidden.isChecked
        )
        val rowHint = hintBar(UserHints.Hint.BATTERY_MODERN_ROW,
            "Подпись — только название строки. Выберите сущность, которая действительно показывает это значение: " +
                "приложение не подбирает датчики по названию.")
        val content = column(rowHint, source, pick, clear, label("Подпись", 14f), labelInput, label("Иконка", 14f), iconSpinner,
            label("Точность", 14f), precisionSpinner, label("Единица измерения", 14f), unitInput, hidden)
        if (!isNew) {
            val moves = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            moves.addView(button("▲ Выше", "bm_row_up") { draft.update(edited()); draft.move(row.id, -1); render() })
            moves.addView(button("▼ Ниже", "bm_row_down") { draft.update(edited()); draft.move(row.id, 1); render() })
            content.addView(moves)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNew) "Новый показатель" else row.label.ifBlank { "Показатель" })
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Готово", null)
            .setNegativeButton("Отмена", null)
            .apply { if (!isNew) setNeutralButton("Удалить", null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val next = edited()
                if (next.label.isBlank()) { labelInput.error = "Введите подпись"; return@setOnClickListener }
                draft.update(next)
                render()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                show(AlertDialog.Builder(this).setTitle("Удалить «${row.label}»?")
                    .setMessage("Строка исчезнет из карточки после сохранения. Отменить можно кнопкой «Отмена» редактора.")
                    .setPositiveButton("Удалить") { _, _ ->
                        draft.delete(row.id)
                        render()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Оставить", null).create())
            }
        }
        openRowDialog = dialog
        show(dialog)
    }

    internal var openPicker: AlertDialog? = null
        private set

    /** Поиск по уже известным сущностям HA: кеш списка и текущие состояния панели. */
    internal fun pickEntity(current: String, onPicked: (String) -> Unit) {
        val all = entityChoices()
        val search = input("", "Поиск по названию или entity_id").apply { tag = "bm_pick_search" }
        val list = ListView(this).apply { tag = "bm_pick_list" }
        var shown = all
        val adapter = whiteAdapter(emptyList())
        list.adapter = adapter
        fun filter(query: String) {
            val q = query.trim().lowercase()
            shown = if (q.isEmpty()) all else all.filter { it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
            adapter.clear()
            adapter.addAll(shown.map { (id, name) ->
                val value = model.singleStates[id]?.let { "  ·  $it" }.orEmpty()
                (if (id == current) "✓ " else "") + "$name$value\n$id"
            })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter(s.toString())
        })
        filter("")
        val empty = label(if (all.isEmpty()) "Список сущностей пуст: дождитесь подключения панели к Home Assistant." else "", 14f)
        val content = column(search, empty)
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        val dialog = AlertDialog.Builder(this).setTitle("Сущность Home Assistant").setView(content)
            .setNegativeButton("Отмена", null).create()
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { onPicked(it.first) }
            dialog.dismiss()
        }
        openPicker = dialog
        show(dialog)
    }

    internal fun entityChoices(): List<Pair<String, String>> {
        val names = LinkedHashMap<String, String>()
        cachedEntities.forEach { (id, name) -> names[id] = name }
        model.singleStates.keys.forEach { id ->
            if (id !in names) names[id] = model.entityAttributes[id]?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: id
        }
        return names.entries.map { it.key to it.value }.sortedBy { it.first }
    }

    private fun entityName(id: String) = cachedEntities[id]
        ?: model.entityAttributes[id]?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: id

    // ---- Сохранение / отмена ----

    internal fun save(): Boolean {
        draft.validationError()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); return false }
        val latest = tileManager.loadTiles().find { it.id == draft.original.id }
        if (latest == null || latest != draft.original) {
            Toast.makeText(this, "Плитка изменена другим экраном. Закройте редактор и откройте её заново.", Toast.LENGTH_LONG).show()
            return false
        }
        tileManager.updateTile(draft.applyTo(latest))
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
        return true
    }

    internal fun leave() {
        if (!::draft.isInitialized || !draft.isDirty) { finish(); return }
        show(AlertDialog.Builder(this).setTitle("Сохранить изменения?")
            .setMessage("Настройки АКБ Modern пока не записаны.")
            .setPositiveButton("Сохранить") { _, _ -> save() }
            .setNegativeButton("Не сохранять") { _, _ -> finish() }
            .setNeutralButton("Продолжить редактирование", null).create())
    }

    private fun openTileSettings() {
        if (draft.isDirty) {
            Toast.makeText(this, "Сначала сохраните или отмените изменения АКБ Modern", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, TileSettingsActivity::class.java).putExtra("tile_id", draft.original.id))
        setResult(Activity.RESULT_OK)
        finish()
    }

    // ---- Вспомогательные Views ----

    private fun show(dialog: AlertDialog) {
        if (isFinishing || isDestroyed) return
        dialogs.add(dialog)
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            if (openRowDialog === dialog) openRowDialog = null
            if (openPicker === dialog) openPicker = null
        }
        dialog.show()
    }

    private fun hintBar(hint: UserHints.Hint, message: String): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = BatteryModernCard.rounded(Color.argb(60, 38, 198, 218), dp(12).toFloat(), BatteryModernCard.BORDER)
            setPadding(dp(12), dp(6), dp(4), dp(6))
            tag = "bm_hint_${hint.key}"
            visibility = if (UserHints.isHidden(this@BatteryModernEditorActivity, hint)) View.GONE else View.VISIBLE
        }
        bar.addView(label("ⓘ  $message", 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "✕"
            contentDescription = "Закрыть подсказку"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            setOnClickListener {
                UserHints.setHidden(this@BatteryModernEditorActivity, hint, true)
                bar.visibility = View.GONE
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        return bar
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(if (bold) Color.WHITE else Color.rgb(200, 214, 222))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun input(value: String, hint: String) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(120, 140, 150))
        isSingleLine = true
    }

    private fun button(text: String, tag: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        this.tag = tag
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
        views.forEach { addView(it) }
    }

    private fun whiteAdapter(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList(items)) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getView(position, convertView, parent).also { (it as TextView).setTextColor(Color.WHITE) }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getDropDownView(position, convertView, parent).also { (it as TextView).setTextColor(Color.WHITE) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_DRAFT = "battery_modern_draft"

        internal fun parseCachedEntities(json: String): Map<String, String> = try {
            val array = JSONArray(json)
            buildMap {
                repeat(array.length()) {
                    val obj = array.optJSONObject(it) ?: return@repeat
                    val id = obj.optString("entityId")
                    if (id.isNotBlank()) put(id, obj.optString("name").ifBlank { id })
                }
            }
        } catch (_: Exception) { emptyMap() }
    }
}
