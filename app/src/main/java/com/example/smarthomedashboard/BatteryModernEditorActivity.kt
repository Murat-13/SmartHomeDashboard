package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private val kit = ModernEditorKit(this) { model }
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
        kit.loadCachedEntities()
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
        kit.dispose()
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

    internal val openRowDialog get() = kit.openRowDialog
    internal val openPicker get() = kit.openPicker

    /** Изменения строки попадают в черновик по «Готово»; в prefs — только по «Сохранить». */
    internal fun editRowDialog(row: BatteryModernConfig.Row, isNew: Boolean) = kit.editRowDialog(row, isNew,
        onApply = { draft.update(it); render() },
        onDelete = { draft.delete(it.id); render() },
        onMove = { edited, delta -> draft.update(edited); draft.move(edited.id, delta); render() })

    internal fun pickEntity(current: String, onPicked: (String) -> Unit) = kit.pickEntity(current, onPicked)

    internal fun entityChoices(): List<Pair<String, String>> = kit.entityChoices()

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

    // ---- Вспомогательные Views (общие с другими Modern-редакторами) ----

    private fun show(dialog: AlertDialog) = kit.show(dialog)
    private fun hintBar(hint: UserHints.Hint, message: String) = kit.hintBar(hint, message)
    private fun label(text: String, size: Float, bold: Boolean = false) = kit.label(text, size, bold)
    private fun input(value: String, hint: String) = kit.input(value, hint)
    private fun button(text: String, tag: String, action: () -> Unit) = kit.button(text, tag, action)
    private fun column(vararg views: View) = kit.column(*views)
    private fun dp(value: Int) = kit.dp(value)

    companion object {
        private const val STATE_DRAFT = "battery_modern_draft"

        internal fun parseCachedEntities(json: String): Map<String, String> = ModernEditorKit.parseCachedEntities(json)
    }
}
