package com.example.smarthomedashboard

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.widget.SwitchCompat
import com.example.smarthomedashboard.data.ColorRule
import java.lang.ref.WeakReference
import org.json.JSONObject

/** Только слабая ссылка на владельца уже полученных показаний: редактор не открывает сеть. */
internal object TileEditorPreviewSource {
    private var owner = WeakReference<MainViewModel>(null)
    fun attach(model: MainViewModel) { owner = WeakReference(model) }
    val states get() = owner.get()?.uiState
}

/** UI одного черновика. Не пишет prefs, не посылает команды и не создаёт таймеров. */
internal class SensorTileEditor(
    private val activity: PinProtectedActivity,
    val draft: SensorTileDraft,
    private val root: LinearLayout,
    private val title: () -> String,
    private val friendlyName: (String) -> String,
    private val pick: (List<String>, Boolean) -> Unit
) {
    data class PickTarget(val mode: String, val index: Int = -1)
    internal data class RowDrag(val mode: String, val index: Int)
    var pendingPick: PickTarget? = null
    private val dialogs = linkedSetOf<AlertDialog>()
    private var states = MainUiState()
    private var expandedPreview = false
    private val readings = mutableListOf<Pair<String, TextView>>()
    private val compactRows = column()
    private val detailedRows = column()
    private val ruleRows = column()
    private val preview = FrameLayout(activity)
    private val previewContent = column()
    private val previewTitle = text(18, true)
    private val previewStatus = text(14)
    private val matchStatus = text(14)
    private val rulesHeading = text(20, true)
    private val rulesShortcut = button("Цвет по условиям", "jump-rules") { jumpTo(rulesHeading) }
    private val errorText = text(14).apply { setTextColor(Color.rgb(255, 160, 160)) }
    private val followSwitch = SwitchCompat(activity)
    private val addDetailed = button("Добавить подробные показания", "add-detail") { requestPick(PickTarget("detail")) }
    private val simulationSwitch = SwitchCompat(activity)
    private val simulationBody = column()
    private val simulationSource = button("Выбрать источник проверки", "simulation-source") { chooseSimulationSource() }
    private val simulationValue = input()
    private var simulationEntity: String? = null

    init {
        root.orientation = LinearLayout.VERTICAL
        root.addView(errorText)
        val shortcuts = LinearLayout(activity)
        shortcuts.addView(button("Показания", "jump-readings") { jumpTo(compactRows) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        shortcuts.addView(rulesShortcut, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(shortcuts)
        section("Предпросмотр")
        root.addView(previewStatus)
        val modes = LinearLayout(activity)
        modes.addView(button("На плитке", "preview-main") { expandedPreview = false; updatePreview() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        modes.addView(button("При раскрытии", "preview-detail") { expandedPreview = true; updatePreview() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(modes)
        preview.tag = "sensor-preview"
        previewContent.addView(previewTitle)
        preview.addView(previewContent, FrameLayout.LayoutParams(-1, -2))
        previewContent.setPadding(dp(16), dp(16), dp(16), dp(16))
        root.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(matchStatus)
        section("Показания на плитке")
        root.addView(text(14).apply { text = "Нажмите строку для настройки. Удерживайте для переноса или используйте стрелки." })
        root.addView(compactRows)
        root.addView(button("Добавить показания", "add-main") { requestPick(PickTarget("main")) })
        section("Подробный вид")
        followSwitch.text = "При раскрытии — тот же список"
        followSwitch.setTextColor(Color.WHITE)
        followSwitch.minHeight = dp(48)
        followSwitch.tag = "follow-main"
        followSwitch.isChecked = draft.followsCompact
        followSwitch.setOnCheckedChangeListener { _, checked -> draft.followsCompact = checked; refreshLists() }
        root.addView(followSwitch)
        root.addView(detailedRows)
        root.addView(addDetailed)
        section("Оформление")
        root.addView(text(14).apply { text = "Размер текста" })
        root.addView(input().apply {
            tag = "font-size"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(draft.fontSizeText)
            contentDescription = "Размер текста плитки"
            minHeight = dp(48)
            watch { draft.fontSizeText = it; updatePreview() }
        })
        rulesHeading.setPadding(0, dp(24), 0, dp(8))
        root.addView(rulesHeading)
        root.addView(text(14).apply { text = "Можно выбрать любой источник, даже скрытый на плитке. Если подходят несколько правил, применяется нижнее." })
        root.addView(ruleRows)
        root.addView(button("Добавить цветовое правило", "add-rule") {
            draft.rules += SensorTileDraft.Rule(ColorRule("", "", "", "#8033CC33"))
            refreshLists()
            requestPick(PickTarget("new-rule", draft.rules.lastIndex))
        })
        simulationSwitch.text = "Проверить цвет на пробном значении"
        simulationSwitch.setTextColor(Color.WHITE)
        simulationSwitch.minHeight = dp(48)
        simulationSwitch.tag = "simulate"
        root.addView(simulationSwitch)
        simulationBody.addView(text(14).apply { text = "Меняет только предпросмотр. Команды устройствам не отправляются." })
        simulationBody.addView(simulationSource)
        simulationValue.apply {
            tag = "simulation-value"
            hint = "Например: 230 V, -5.5 или unavailable"
            inputType = InputType.TYPE_CLASS_TEXT
            minHeight = dp(48)
            watch { updatePreview() }
        }
        simulationBody.addView(simulationValue)
        root.addView(simulationBody)
        simulationSwitch.setOnCheckedChangeListener { _, checked ->
            simulationBody.visibility = if (checked) View.VISIBLE else View.GONE
            updatePreview()
        }
        simulationBody.visibility = View.GONE
        refreshLists()
    }

    private fun jumpTo(view: View) {
        var parent: android.view.ViewParent? = root.parent
        while (parent != null && parent !is ScrollView) parent = parent.parent
        val scroll = parent as? ScrollView ?: return
        val bounds = android.graphics.Rect()
        view.getDrawingRect(bounds)
        scroll.offsetDescendantRectToMyCoords(view, bounds)
        scroll.smoothScrollTo(0, bounds.top)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun input() = EditText(activity).apply {
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(189, 189, 189))
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(144, 202, 249))
        minHeight = dp(48)
        setSingleLine(true)
    }
    private fun text(size: Int, bold: Boolean = false) = TextView(activity).apply {
        setTextColor(Color.WHITE)
        textSize = size.toFloat()
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun section(label: String) { root.addView(text(20, true).apply { text = label; setPadding(0, dp(24), 0, dp(8)) }) }
    private fun button(label: String, key: String, action: () -> Unit) = Button(activity).apply {
        text = label
        tag = key
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(55, 65, 81))
        minHeight = dp(48)
        contentDescription = label
        setOnClickListener { action() }
    }
    private fun EditText.watch(changed: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { changed(s?.toString().orEmpty()) }
    })

    private fun requestPick(target: PickTarget) {
        pendingPick = target
        val selected = when (target.mode) {
            // Это добавление строк; снятие галочки в таком диалоге не должно молча удалять старую строку.
            "main", "detail" -> emptyList()
            "replace-main" -> listOfNotNull(draft.compact.getOrNull(target.index)?.value?.entityId)
            "replace-detail" -> listOfNotNull(draft.detailed.getOrNull(target.index)?.value?.entityId)
            else -> listOfNotNull(draft.rules.getOrNull(target.index)?.value?.entityId?.takeIf { it.isNotBlank() })
        }
        pick(selected, target.mode in setOf("rule", "new-rule") || target.mode.startsWith("replace-"))
    }

    fun pickerResult(ids: List<String>?) {
        val target = pendingPick ?: return
        pendingPick = null
        if (target.mode == "new-rule" && ids.isNullOrEmpty()) {
            if (draft.rules.getOrNull(target.index)?.value?.entityId?.isBlank() == true) draft.rules.removeAt(target.index)
        }
        if (ids != null) when (target.mode) {
            "main", "detail" -> draft.add(ids, target.mode == "detail", ::defaultName)
            "replace-main", "replace-detail" -> ids.firstOrNull()?.let { id ->
                val list = if (target.mode == "replace-main") draft.compact else draft.detailed
                list.getOrNull(target.index)?.let { list[target.index] = it.copy(value = it.value.copy(entityId = id)) }
            }
            "rule", "new-rule" -> ids.firstOrNull()?.let { id ->
                draft.rules.getOrNull(target.index)?.let { draft.rules[target.index] = it.copy(value = it.value.copy(entityId = id)) }
            }
        }
        refreshLists()
    }

    private fun sourceName(id: String): String = states.entityAttributes[id]?.takeUnless { it.isNull("friendly_name") }
        ?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: friendlyName(id)

    private fun defaultName(id: String): String {
        val attrs = states.entityAttributes[id]
        val name = sourceName(id)
        val unit = attrs?.takeUnless { it.isNull("unit_of_measurement") }?.optString("unit_of_measurement").orEmpty()
        return if (unit.isBlank()) name else "$name ($unit)"
    }

    fun acceptState(value: MainUiState) {
        val previous = states
        states = value
        val dependencies = (draft.compact + draft.detailed).map { it.value.entityId } + draft.rules.map { it.value.entityId }
        if (previous.singleStates.isEmpty() != value.singleStates.isEmpty() || dependencies.any {
                previous.singleStates[it] != value.singleStates[it] || previous.entityAttributes[it] !== value.entityAttributes[it]
            }) updatePreview()
    }

    fun saveUiState(): String = JSONObject().put("expanded", expandedPreview)
        .put("simulate", simulationSwitch.isChecked).put("entity", simulationEntity)
        .put("value", simulationValue.text.toString()).toString()

    fun restoreUiState(text: String) {
        val state = JSONObject(text)
        expandedPreview = state.optBoolean("expanded")
        simulationEntity = state.opt("entity") as? String
        simulationValue.setText(state.optString("value"))
        simulationSwitch.isChecked = state.optBoolean("simulate")
        updatePreview()
    }

    private fun refreshLists() {
        readings.clear()
        buildRows(compactRows, false)
        buildRows(detailedRows, true)
        detailedRows.visibility = if (draft.followsCompact) View.GONE else View.VISIBLE
        addDetailed.visibility = detailedRows.visibility
        buildRules()
        updatePreview()
    }

    private fun buildRows(container: LinearLayout, expanded: Boolean) {
        container.removeAllViews()
        val list = if (expanded) draft.detailed else draft.compact
        val mode = if (expanded) "detail" else "main"
        if (list.isEmpty()) container.addView(text(14).apply { text = if (expanded) "Пустой подробный список использует показания основной плитки." else "Пока нет показаний. Добавьте первый датчик." })
        list.forEachIndexed { index, item ->
            val line = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            val label = column().apply {
                addView(text(16, true).apply { text = item.value.displayName.ifEmpty { friendlyName(item.value.entityId) } })
                addView(text(14).also { readings += item.value.entityId to it })
                minimumHeight = dp(56)
                tag = "edit-$mode-$index"
                contentDescription = "Настроить ${item.value.displayName}"
                isFocusable = true
                setOnClickListener { editRow(index, expanded) }
            }
            line.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            arrows(line, mode, index, list.size) { next -> list.add(next, list.removeAt(index)); refreshLists() }
            line.addView(button("×", "remove-$mode-$index") { list.removeAt(index); refreshLists() }.apply { contentDescription = "Убрать показание ${item.value.displayName}" }, LinearLayout.LayoutParams(dp(48), dp(48)))
            line.tag = "row-$mode-$index"
            label.setOnLongClickListener { source ->
                val data = android.content.ClipData("Показание", arrayOf("application/x-smarthomedashboard-row"), android.content.ClipData.Item(""))
                source.startDragAndDrop(data, View.DragShadowBuilder(source), RowDrag(mode, index), 0)
            }
            label.contentDescription = "Настроить ${item.value.displayName}. Удерживайте для переноса."
            line.setOnDragListener { _, event ->
                val drag = event.localState as? RowDrag
                if (drag == null || drag.mode != mode || drag.index !in list.indices) false
                else when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED -> true
                    android.view.DragEvent.ACTION_DROP -> {
                        if (drag.index != index) {
                            list.add(index, list.removeAt(drag.index))
                            refreshLists()
                        }
                        true
                    }
                    else -> true
                }
            }
            container.addView(line)
        }
    }

    private fun arrows(line: LinearLayout, mode: String, index: Int, size: Int, move: (Int) -> Unit) {
        listOf("↑" to -1, "↓" to 1).forEach { (label, step) ->
            line.addView(button(label, "move-$mode-$index-$step") { move(index + step) }.apply {
                setPadding(0, 0, 0, 0)
                isEnabled = index + step in 0 until size
                contentDescription = if (step < 0) "Переместить выше" else "Переместить ниже"
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
    }

    private fun editRow(index: Int, expanded: Boolean) {
        val list = if (expanded) draft.detailed else draft.compact
        val item = list[index]
        val fields = column().apply { setPadding(dp(20), 0, dp(20), 0) }
        fields.addView(text(14).apply { text = "Источник: ${item.value.entityId}" })
        fields.addView(text(14).apply { text = "Подпись" })
        val name = input().apply { setText(item.value.displayName); minHeight = dp(48); tag = "row-name" }
        fields.addView(name)
        fields.addView(text(14).apply { text = "Знаков после запятой: 0–10" })
        val decimals = input().apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(item.value.decimals.toString()); minHeight = dp(48); tag = "row-decimals" }
        fields.addView(decimals)
        val dialog = AlertDialog.Builder(activity).setTitle("Показание").setView(fields)
            .setNegativeButton("Отмена", null).setNeutralButton("Заменить источник", null)
            .setPositiveButton("Применить", null).create()
        dialog.setOnShowListener {
            fun applyRow(): Boolean {
                val precision = decimals.text.toString().toIntOrNull()
                if (precision == null || (precision != item.value.decimals && precision !in 0..10)) {
                    decimals.error = "Введите число от 0 до 10"
                    return false
                }
                list[index] = item.copy(value = item.value.copy(displayName = name.text.toString(), decimals = precision))
                dialog.dismiss()
                refreshLists()
                return true
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { applyRow() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (applyRow()) requestPick(PickTarget(if (expanded) "replace-detail" else "replace-main", index))
            }
        }
        showDialog(dialog)
    }

    private fun buildRules() {
        ruleRows.removeAllViews()
        rulesHeading.text = "Цвет по условиям (${draft.rules.size})"
        rulesShortcut.text = "Цвет (${draft.rules.size})"
        draft.rules.forEachIndexed { index, item ->
            val box = column().apply {
                setPadding(dp(12), dp(8), dp(12), dp(12))
                background = GradientDrawable().apply { setColor(Color.rgb(42, 42, 42)); cornerRadius = dp(12).toFloat() }
            }
            val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(text(16, true).apply { text = "Правило ${index + 1}" }, LinearLayout.LayoutParams(0, -2, 1f))
            arrows(heading, "rule", index, draft.rules.size) { next -> draft.rules.add(next, draft.rules.removeAt(index)); refreshLists() }
            heading.addView(button("×", "remove-rule-$index") { draft.rules.removeAt(index); refreshLists() }.apply { contentDescription = "Удалить правило ${index + 1}" }, LinearLayout.LayoutParams(dp(48), dp(48)))
            box.addView(heading)
            box.addView(button(item.value.entityId.takeIf { it.isNotBlank() }?.let(::sourceName) ?: "Выбрать источник", "rule-source-$index") { requestPick(PickTarget("rule", index)) })
            val bounds = LinearLayout(activity)
            fun boundary(label: String, from: Boolean) {
                val field = input().apply {
                    hint = label
                    tag = "rule-${if (from) "from" else "to"}-$index"
                    inputType = InputType.TYPE_CLASS_TEXT
                    minHeight = dp(48)
                    contentDescription = "${label}, правило ${index + 1}"
                    setText(if (from) item.value.from.orEmpty() else item.value.to.orEmpty())
                    watch { value ->
                        val current = draft.rules[index]
                        draft.rules[index] = current.copy(value = if (from) current.value.copy(from = value) else current.value.copy(to = value))
                        updatePreview()
                    }
                }
                val column = column().apply {
                    addView(text(14).apply { text = label })
                    addView(field)
                }
                bounds.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            }
            boundary("От / текстовое состояние", true)
            boundary("До / пусто", false)
            box.addView(bounds)
            box.addView(button("Изменить цвет", "rule-color-$index") { chooseColor(index) }.apply {
                val swatch = GradientDrawable().apply {
                    setColor(try { Color.parseColor(item.value.colorHex) } catch (_: IllegalArgumentException) { Color.GRAY })
                    setStroke(dp(1), Color.WHITE)
                    cornerRadius = dp(4).toFloat()
                    setBounds(0, 0, dp(32), dp(32))
                }
                setCompoundDrawables(swatch, null, null, null)
                compoundDrawablePadding = dp(12)
            })
            ruleRows.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }

    private fun chooseColor(index: Int) {
        val field = input().apply { setText(draft.rules[index].value.colorHex); minHeight = dp(48); hint = "#RRGGBB или #AARRGGBB"; tag = "color-hex" }
        val content = column().apply { setPadding(dp(16), 0, dp(16), 0) }
        content.addView(text(14).apply { text = "Сохранённая прозрачность поддерживается. Можно ввести свой цвет." })
        content.addView(field)
        val palette = GridLayout(activity).apply { columnCount = 4 }
        listOf("#804CAF50", "#80F44336", "#802196F3", "#80FFEB3B", "#809C27B0", "#80FF9800", "#8000BCD4", "#80000000",
            "#CC4CAF50", "#CCF44336", "#CC2196F3", "#CCFFEB3B", "#CC9C27B0", "#CCFF9800", "#CC00BCD4", "#CCFFFFFF",
            "#E64CAF50", "#E6F44336", "#E62196F3", "#E6FFEB3B", "#E69C27B0", "#E6FF9800", "#E600BCD4", "#E6FFFFFF",
            "#FF4CAF50", "#FFF44336", "#FF2196F3", "#FFFFEB3B", "#FF9C27B0", "#FFFF9800", "#FF00BCD4", "#FFFFFFFF").forEach { hex ->
            palette.addView(button("", "palette-$hex") { field.setText(hex) }.apply { backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(hex)); contentDescription = "Цвет $hex" }, GridLayout.LayoutParams().apply { width = dp(52); height = dp(48); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        content.addView(palette)
        val dialog = AlertDialog.Builder(activity).setTitle("Цвет правила").setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("Отмена", null).setPositiveButton("Применить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hex = field.text.toString().trim()
                if (!hex.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) field.error = "Введите #RRGGBB или #AARRGGBB"
                else {
                    draft.rules[index] = draft.rules[index].copy(value = draft.rules[index].value.copy(colorHex = hex))
                    dialog.dismiss(); refreshLists()
                }
            }
        }
        showDialog(dialog)
    }

    private fun chooseSimulationSource() {
        val ids = draft.rules.map { it.value.entityId }.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) { Toast.makeText(activity, "Сначала выберите источник цветового правила", Toast.LENGTH_SHORT).show(); return }
        showDialog(AlertDialog.Builder(activity).setTitle("Источник пробного значения")
            .setItems(ids.map(::sourceName).toTypedArray()) { _, index -> simulationEntity = ids[index]; updatePreview() }.create())
    }

    fun showDialog(dialog: AlertDialog) {
        if (activity.isFinishing || activity.isDestroyed) return
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
    }

    fun dispose() {
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
    }

    fun validationError(includeEmpty: Boolean = true): String? = draft.loadError ?: when {
        includeEmpty && draft.original.id == "new-sensor-draft" && draft.compact.isEmpty() &&
            (draft.followsCompact || draft.detailed.isEmpty()) -> "Добавьте хотя бы одно показание перед сохранением."
        draft.rules.any { it.raw == null && it.value.entityId.isBlank() } -> "Выберите источник нового цветового правила или удалите его."
        draft.fontSizeText.toIntOrNull().let { it == null || (draft.fontSizeText != draft.original.fontSize.toString() && it !in 1..200) } -> "Размер текста: от 1 до 200"
        else -> null
    }

    fun updatePreview() {
        errorText.text = validationError(includeEmpty = false).orEmpty()
        val tile = try { draft.applyTo(draft.original.copy(title = title().ifBlank { "Название плитки" })) } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
        val source = simulationEntity?.takeIf { id -> draft.rules.any { it.value.entityId == id } }
            ?: draft.rules.firstOrNull { it.value.entityId.isNotBlank() }?.value?.entityId
        simulationSource.text = source?.let(::sourceName) ?: "Выбрать источник проверки"
        draft.rules.forEachIndexed { i, rule ->
            val label = rule.value.entityId.takeIf { it.isNotBlank() }?.let(::sourceName) ?: "Выбрать источник"
            root.findViewWithTag<Button>("rule-source-$i")?.let { if (it.text.toString() != label) it.text = label }
        }
        listOf("preview-main" to !expandedPreview, "preview-detail" to expandedPreview).forEach { (tag, selected) ->
            root.findViewWithTag<Button>(tag)?.apply {
                isSelected = selected
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (selected) Color.rgb(21, 101, 192) else Color.rgb(55, 65, 81))
            }
        }
        fun state(id: String): String? = if (simulationSwitch.isChecked && id == source) simulationValue.text.toString() else states.singleStates[id]
        if (previewTitle.text.toString() != tile.title) previewTitle.text = tile.title
        val fontPixels = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, tile.fontSize.toFloat(), activity.resources.displayMetrics)
        if (previewTitle.textSize != fontPixels) previewTitle.textSize = tile.fontSize.toFloat()
        val ids = if (expandedPreview) SensorConfigResolver.getExpandedSensorIds(tile) else SensorConfigResolver.getCollapsedSensorIds(tile)
        try {
            SensorContentRenderer.render(tile, ids, expandedPreview, previewContent, SensorConfigResolver::loadSensorConfigs, ::state) { value, decimals, id ->
                SensorValueFormatter.formatSensorValue(value, decimals, states.entityAttributes[id]?.optString("unit_of_measurement").orEmpty(), activity.getSharedPreferences("dashboard_prefs", 0).getString("temp_unit", "C") ?: "C")
            }
        } catch (_: java.util.IllegalFormatException) {
            errorText.text = "Проверьте точность показаний: формат строки не поддерживается. Настройки не изменены."
        }
        SensorBackgroundRenderer.render(tile, preview, ::state)
        val matched = draft.rules.indexOfLast { r -> r.value.entityId.isNotBlank() && r.value.colorHex.isNotBlank() && ColorRuleMatcher.matches(state(r.value.entityId).orEmpty(), r.value.from.orEmpty(), r.value.to.orEmpty()) }
        matchStatus.text = if (matched >= 0) "Сработало правило ${matched + 1}" else "Ни одно цветовое правило не сработало"
        previewStatus.text = when {
            simulationSwitch.isChecked -> "Предпросмотр с пробным значением"
            states.singleStates.isEmpty() -> "Данных пока нет. После подключения появятся показания."
            else -> "Последние данные панели · ${if (expandedPreview) "подробный вид" else "основной вид"}"
        }
        readings.forEach { (id, view) ->
            val value = state(id) ?: "Нет данных"
            if (view.text.toString() != value) view.text = value
        }
    }
}
