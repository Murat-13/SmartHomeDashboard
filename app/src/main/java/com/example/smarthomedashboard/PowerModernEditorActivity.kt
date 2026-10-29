package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Редактор «Электропитание Modern»: показатели выбираются касанием по предпросмотру развёрнутой
 * карточки, цветовые правила — в таблице, результат проверяется тестовым напряжением.
 * Данные — только уже полученные ViewModel панели; команд Home Assistant и записи нет до «Сохранить».
 */
class PowerModernEditorActivity : PinProtectedActivity(), BatteryModernCard.Editor {
    private lateinit var tileManager: TileManager
    internal lateinit var draft: PowerModernDraft
        private set
    internal lateinit var preview: PowerModernCard
        private set
    internal lateinit var testCard: PowerModernCard
        private set
    private var model = MainUiState()
    private var previewJob: Job? = null
    private val kit = ModernEditorKit(this) { model }
    private lateinit var rulesList: LinearLayout
    private lateinit var testInput: android.widget.EditText
    internal lateinit var testResult: TextView
        private set
    /** Тестовое напряжение предпросмотра; только локально, в HA не отправляется. */
    internal var testVoltage: Double? = 228.0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tileManager = TileManager(this)
        val tile = intent.getStringExtra("tile_id")?.let { id -> tileManager.loadTiles().find { it.id == id } }
        if (tile == null || tile.type != PowerModernConfig.TYPE) {
            Toast.makeText(this, "Плитка не найдена", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        draft = savedInstanceState?.getString(STATE_DRAFT)?.let {
            try { PowerModernDraft.restore(it, tile) } catch (_: Exception) { null }
        } ?: PowerModernDraft(tile)
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
            setBackgroundColor(Color.rgb(9, 14, 20))
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
        }
        root.addView(kit.label("Электропитание Modern — настройка", 22f, bold = true))
        root.addView(kit.hintBar(UserHints.Hint.POWER_MODERN_EDITOR,
            "Нажмите на напряжение, мощность, частоту или строку справа, чтобы выбрать сущность Home Assistant. " +
                "Цвета и подписи задаются правилами напряжения. Изменения применяются только кнопкой «Сохранить»."))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        preview = PowerModernCard(this, expanded = true, editor = this)
        val previewFrame = FrameLayout(this).apply { tag = "pm_editor_preview" }
        previewFrame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(previewFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), 0, 0, 0)
        }
        side.addView(kit.label("Проверка цвета (без Home Assistant)", 16f, bold = true))
        testInput = kit.input("228", "Тестовое напряжение, В").apply {
            tag = "pm_test_input"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            addTextChangedListener(kit.watcher { text ->
                testVoltage = BatteryFormat.number(text)
                renderTest()
            })
        }
        side.addView(testInput)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(listOf(0, 145, 180, 195), listOf(228, 245, 255, 285)).forEach { rowValues ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowValues.forEach { v ->
                row.addView(kit.button("$v", "pm_test_$v") { testInput.setText(v.toString()) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            chips.addView(row)
        }
        side.addView(chips)
        testCard = PowerModernCard(this, expanded = false).apply { tag = "pm_test_card" }
        side.addView(FrameLayout(this).apply {
            addView(testCard, FrameLayout.LayoutParams(kit.dp(200), kit.dp(240), Gravity.CENTER_HORIZONTAL))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kit.dp(252)))
        testResult = kit.label("", 14f).apply { tag = "pm_test_result" }
        side.addView(testResult)

        side.addView(kit.label("Цветовые правила напряжения", 16f, bold = true))
        side.addView(kit.label("Цифры всегда белые; правило задаёт фон, рамку, свечение, иконку и статус. " +
            "Это визуальные предупреждения, а не оценка электробезопасности.", 13f))
        rulesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "pm_rules" }
        side.addView(rulesList)
        side.addView(kit.button("+ Правило", "pm_rule_add") {
            ruleDialog(null, PowerModernConfig.Rule(0.0, true, null, false, PowerModernConfig.YELLOW, "Новое правило", false))
        })
        side.addView(kit.button("Восстановить стандартные правила", "pm_rules_restore") {
            kit.show(AlertDialog.Builder(this).setTitle("Восстановить стандартные правила?")
                .setMessage("Таблица правил этой плитки вернётся к шаблону профиля. Источники данных не меняются.")
                .setPositiveButton("Восстановить") { _, _ -> draft.restoreDefaultRules(); render() }
                .setNegativeButton("Отмена", null).create())
        })
        side.addView(kit.button("+ Дополнительный показатель", "pm_add_row") {
            // Новая строка попадает в черновик только по «Готово» диалога.
            editRowDialog(draft.newRow(), isNew = true)
        })
        side.addView(kit.button("Тип, размер, удаление плитки…", "pm_tile_settings") { openTileSettings() })
        body.addView(ScrollView(this).apply { addView(side) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        footer.addView(kit.button("Отмена", "pm_cancel") { leave() })
        footer.addView(kit.button("Сохранить", "pm_save") { save() })
        root.addView(footer)
        setContentView(root)
    }

    /** Предпросмотр развёрнутой карточки по данным панели: сразу диапазон, без задержки подтверждения. */
    internal fun render() {
        val readings = BatteryReadings.from(model)
        val config = draft.config
        val voltage = readings.of(config.row(PowerModernConfig.ROLE_VOLTAGE))
        val number = (voltage as? BatteryReading.Value)?.number
        val visual = if (readings.status == null && number != null) PowerVisual.preview(config, number)
            else PowerVisual.of(config, voltage, readings, null)
        preview.bind(draft.title, config, readings, visual, animate = false)
        renderRules()
        renderTest()
    }

    /** Тестовое значение подставляется вместо источника напряжения только в этой карточке. */
    internal fun renderTest() {
        if (!::testCard.isInitialized) return
        val config = draft.config
        val value = testVoltage
        val test = config.withRow(config.row(PowerModernConfig.ROLE_VOLTAGE)?.copy(entityId = PREVIEW_ENTITY, decimals = null)
            ?: PowerModernConfig.templateRows().first().copy(entityId = PREVIEW_ENTITY))
        val readings = BatteryReadings(HaSession(connected = true, synced = true),
            { id -> if (id == PREVIEW_ENTITY) value?.toString() else model.singleStates[id] },
            { id -> id != PREVIEW_ENTITY && id in model.staleEntities },
            { id -> if (id == PREVIEW_ENTITY) org.json.JSONObject().put("unit_of_measurement", "V") else model.entityAttributes[id] })
        val visual = if (value == null) PowerVisual(PowerModernConfig.NEUTRAL, "Введите число", pulse = false, neutral = true)
            else PowerVisual.preview(config, value)
        testCard.bind(draft.title, test, readings, visual, animate = true)
        testResult.text = if (value == null) "Введите тестовое напряжение"
            else "${BatteryFormat.decimal(value, if (value % 1.0 == 0.0) 0 else 1)} В → ${visual.status}" +
                (if (visual.neutral) " (нейтральный цвет)" else "") + if (visual.pulse) ", мягкая пульсация" else ", без пульсации"
    }

    private fun renderRules() {
        rulesList.removeAllViews()
        draft.config.rules.forEachIndexed { index, rule ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                tag = "pm_rule_$index"
                setPadding(0, kit.dp(4), 0, kit.dp(4))
                isClickable = true
                setOnClickListener { ruleDialog(index, rule) }
            }
            line.addView(View(this).apply {
                background = GradientDrawable().apply { setColor(rule.color); cornerRadius = kit.dp(6).toFloat() }
            }, LinearLayout.LayoutParams(kit.dp(22), kit.dp(22)).apply { rightMargin = kit.dp(10) })
            line.addView(kit.label("${rule.rangeText()} · ${rule.label}${if (rule.pulse) " · пульсация" else ""}", 14f),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rulesList.addView(line)
        }
        val check = draft.config.validate()
        (check.errors + check.gaps).forEach { message ->
            rulesList.addView(kit.label("⚠ $message", 13f).apply {
                setTextColor(if (message in check.errors) Color.rgb(255, 120, 120) else Color.rgb(255, 205, 120))
            })
        }
    }

    internal var openRuleDialog: AlertDialog? = null
        private set

    /** [index] = null — новое правило. Применяется к черновику по «Готово». */
    internal fun ruleDialog(index: Int?, rule: PowerModernConfig.Rule) {
        fun number(value: Double) = BatteryFormat.decimal(value, if (value % 1.0 == 0.0) 0 else 1, java.util.Locale.ROOT)
        val from = kit.input(number(rule.from), "От, В").apply {
            tag = "pm_rule_from"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val fromInclusive = CheckBox(this).apply { text = "включая нижнюю границу"; setTextColor(Color.WHITE); isChecked = rule.fromInclusive; tag = "pm_rule_from_incl" }
        val to = kit.input(rule.to?.let(::number).orEmpty(), "До, В (пусто — без верхней границы)").apply {
            tag = "pm_rule_to"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val toInclusive = CheckBox(this).apply { text = "включая верхнюю границу"; setTextColor(Color.WHITE); isChecked = rule.toInclusive; tag = "pm_rule_to_incl" }
        val label = kit.input(rule.label, "Текст статуса").apply { tag = "pm_rule_label" }
        val pulse = SwitchCompat(this).apply { text = "Мягкая пульсация"; setTextColor(Color.WHITE); isChecked = rule.pulse; tag = "pm_rule_pulse" }
        var color = rule.color
        val swatch = View(this)
        val hex = kit.input(String.format("#%06X", rule.color and 0xFFFFFF), "#RRGGBB").apply { tag = "pm_rule_color" }
        fun showColor() {
            swatch.background = GradientDrawable().apply { setColor(color); cornerRadius = kit.dp(8).toFloat() }
        }
        showColor()
        hex.addTextChangedListener(kit.watcher { text -> PowerModernConfig.parseColor(text)?.let { color = it or 0xFF000000.toInt(); showColor() } })
        val palette = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PALETTE.forEach { (name, value) ->
            palette.addView(View(this).apply {
                contentDescription = name
                background = GradientDrawable().apply { setColor(value); cornerRadius = kit.dp(8).toFloat() }
                setOnClickListener { hex.setText(String.format("#%06X", value and 0xFFFFFF)) }
            }, LinearLayout.LayoutParams(kit.dp(36), kit.dp(36)).apply { rightMargin = kit.dp(8) })
        }
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        colorRow.addView(swatch, LinearLayout.LayoutParams(kit.dp(36), kit.dp(36)).apply { rightMargin = kit.dp(12) })
        colorRow.addView(hex, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val error = kit.label("", 13f).apply { setTextColor(Color.rgb(255, 120, 120)); tag = "pm_rule_error" }
        val content = kit.column(kit.label("Нижняя граница", 14f), from, fromInclusive, kit.label("Верхняя граница", 14f), to, toInclusive,
            kit.label("Цвет", 14f), colorRow, palette, kit.label("Статус", 14f), label, pulse, error)
        if (index != null) {
            val moves = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            moves.addView(kit.button("▲ Выше", "pm_rule_up") { draft.moveRule(index, -1); render(); openRuleDialog?.dismiss() })
            moves.addView(kit.button("▼ Ниже", "pm_rule_down") { draft.moveRule(index, 1); render(); openRuleDialog?.dismiss() })
            content.addView(moves)
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (index == null) "Новое правило" else "Правило ${index + 1}")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Готово", null)
            .setNegativeButton("Отмена", null)
            .apply { if (index != null) setNeutralButton("Удалить", null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = BatteryFormat.number(from.text.toString())
                val bText = to.text.toString().trim()
                val b = if (bText.isEmpty()) null else BatteryFormat.number(bText)
                val problem = when {
                    a == null -> "Нижняя граница должна быть числом"
                    bText.isNotEmpty() && b == null -> "Верхняя граница должна быть числом или пустой"
                    b != null && (b < a || (b == a && !(fromInclusive.isChecked && toInclusive.isChecked))) ->
                        "Верхняя граница меньше нижней или диапазон пуст"
                    label.text.isBlank() -> "Введите текст статуса"
                    else -> null
                }
                if (problem != null) { error.text = problem; return@setOnClickListener }
                val next = PowerModernConfig.Rule(a!!, fromInclusive.isChecked, b, toInclusive.isChecked && b != null,
                    color, label.text.toString().trim(), pulse.isChecked)
                if (index == null) draft.addRule(next) else draft.updateRule(index, next)
                render()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                kit.show(AlertDialog.Builder(this).setTitle("Удалить правило «${rule.label}»?")
                    .setMessage("Напряжение этого диапазона станет нейтральным, пока не задано другое правило.")
                    .setPositiveButton("Удалить") { _, _ -> draft.deleteRule(index!!); render(); dialog.dismiss() }
                    .setNegativeButton("Оставить", null).create())
            }
        }
        openRuleDialog = dialog
        kit.show(dialog)
    }

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
        if (::testCard.isInitialized) testCard.dispose()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::draft.isInitialized) outState.putString(STATE_DRAFT, draft.saveState())
        super.onSaveInstanceState(outState)
    }

    // ---- BatteryModernCard.Editor: касание показателя на предпросмотре ----

    override fun editRole(role: String) {
        val row = draft.rowForRole(role)
        kit.editRowDialog(row, isNew = false, onApply = { draft.update(it); render() }, onDelete = null, onMove = null, allowHide = false)
    }

    override fun editRow(id: String) {
        draft.row(id)?.let { editRowDialog(it, isNew = false) }
    }

    override fun editHeader() {
        val titleInput = kit.input(draft.title, "Название").apply { tag = "pm_header_title" }
        kit.show(AlertDialog.Builder(this).setTitle("Название линии").setView(kit.column(kit.label("Название", 14f), titleInput))
            .setPositiveButton("Готово") { _, _ -> draft.setTitle(titleInput.text.toString().trim()); render() }
            .setNegativeButton("Отмена", null).create())
    }

    internal val openRowDialog get() = kit.openRowDialog
    internal val openPicker get() = kit.openPicker

    internal fun editRowDialog(row: BatteryModernConfig.Row, isNew: Boolean) = kit.editRowDialog(row, isNew,
        onApply = { draft.update(it); render() },
        onDelete = { draft.delete(it.id); render() },
        onMove = { edited, delta -> draft.update(edited); draft.move(edited.id, delta); render() })

    internal fun entityChoices() = kit.entityChoices()

    // ---- Сохранение / отмена ----

    internal fun save(confirmedGaps: Boolean = false): Boolean {
        draft.validationError()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); return false }
        val gaps = draft.config.validate().gaps
        if (gaps.isNotEmpty() && !confirmedGaps) {
            // Непокрытое напряжение будет нейтральным («Вне заданных диапазонов»), а не «Нормой».
            kit.show(AlertDialog.Builder(this).setTitle("Есть непокрытые диапазоны")
                .setMessage(gaps.joinToString("\n") + "\n\nВ этих диапазонах плитка будет нейтральной. Сохранить?")
                .setPositiveButton("Сохранить") { _, _ -> save(confirmedGaps = true) }
                .setNegativeButton("Исправить", null).create())
            return false
        }
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
        kit.show(AlertDialog.Builder(this).setTitle("Сохранить изменения?")
            .setMessage("Настройки «${draft.title}» пока не записаны.")
            .setPositiveButton("Сохранить") { _, _ -> save() }
            .setNegativeButton("Не сохранять") { _, _ -> finish() }
            .setNeutralButton("Продолжить редактирование", null).create())
    }

    private fun openTileSettings() {
        if (draft.isDirty) {
            Toast.makeText(this, "Сначала сохраните или отмените изменения", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, TileSettingsActivity::class.java).putExtra("tile_id", draft.original.id))
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        private const val STATE_DRAFT = "power_modern_draft"
        /** Внутренний id тестового значения предпросмотра; не является сущностью HA. */
        internal const val PREVIEW_ENTITY = "preview.test_voltage"
        val PALETTE = listOf(
            "Красный" to PowerModernConfig.RED, "Оранжевый" to PowerModernConfig.ORANGE,
            "Жёлтый" to PowerModernConfig.YELLOW, "Изумрудный" to PowerModernConfig.EMERALD,
            "Бирюзовый" to PowerModernConfig.CYAN, "Синий" to 0xFF3B82F6.toInt(),
            "Фиолетовый" to 0xFFA855F7.toInt(), "Серый" to PowerModernConfig.NEUTRAL
        )
    }
}
