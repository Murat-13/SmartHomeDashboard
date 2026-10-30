package com.example.smarthomedashboard

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Общие части редакторов Modern-плиток: диалог строки, поиск сущностей по уже известным данным
 * панели (без подключения к HA), подсказки и вспомогательные Views. Черновик и запись — у экрана.
 */
internal class ModernEditorKit(
    private val activity: PinProtectedActivity,
    private val model: () -> MainUiState
) {
    private val dialogs = linkedSetOf<AlertDialog>()
    var cachedEntities: Map<String, String> = emptyMap()
        private set
    var openRowDialog: AlertDialog? = null
        private set
    var openPicker: AlertDialog? = null
        private set

    fun loadCachedEntities() {
        activity.lifecycleScope.launch {
            val cached = activity.getSharedPreferences("dashboard_prefs", android.content.Context.MODE_PRIVATE)
                .getString("cached_entities", "[]").orEmpty()
            cachedEntities = withContext(Dispatchers.Default) { parseCachedEntities(cached) }
        }
    }

    /**
     * Диалог одной строки: сущность, подпись, иконка, точность, единица, видимость, порядок.
     * [onApply] получает строку по «Готово»; удаление — только после подтверждения.
     */
    fun editRowDialog(
        row: BatteryModernConfig.Row,
        isNew: Boolean,
        onApply: (BatteryModernConfig.Row) -> Unit,
        onDelete: ((BatteryModernConfig.Row) -> Unit)?,
        onMove: ((BatteryModernConfig.Row, Int) -> Unit)?,
        allowHide: Boolean = true
    ) {
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
        val iconSpinner = Spinner(activity).apply {
            tag = "bm_row_icon"
            adapter = whiteAdapter(icons.map { "${it.value.first}  ${it.value.second}" })
            setSelection(icons.indexOfFirst { it.key == row.icon }.coerceAtLeast(0))
        }
        val precisions = listOf<Int?>(null, 0, 1, 2, 3, 4)
        val precisionSpinner = Spinner(activity).apply {
            tag = "bm_row_decimals"
            adapter = whiteAdapter(precisions.map { it?.let { d -> "$d знак(ов) после запятой" } ?: "Как в Home Assistant" })
            setSelection(precisions.indexOf(row.decimals).coerceAtLeast(0))
        }
        val unitInput = input(row.unit, "Пусто — единица из HA").apply { tag = "bm_row_unit" }
        val hidden = SwitchCompat(activity).apply {
            text = "Скрыть строку"
            setTextColor(Color.WHITE)
            isChecked = row.hidden
            tag = "bm_row_hidden"
            visibility = if (allowHide) View.VISIBLE else View.GONE
        }
        fun edited() = row.copy(
            entityId = entityId,
            label = labelInput.text.toString().trim(),
            icon = icons[iconSpinner.selectedItemPosition.coerceAtLeast(0)].key,
            decimals = precisions[precisionSpinner.selectedItemPosition.coerceAtLeast(0)],
            unit = unitInput.text.toString().trim(),
            hidden = allowHide && hidden.isChecked
        )
        val rowHint = hintBar(UserHints.Hint.BATTERY_MODERN_ROW,
            "Подпись — только название строки. Выберите сущность, которая действительно показывает это значение: " +
                "приложение не подбирает датчики по названию.")
        val content = column(rowHint, source, pick, clear, label("Подпись", 14f), labelInput, label("Иконка", 14f), iconSpinner,
            label("Точность", 14f), precisionSpinner, label("Единица измерения", 14f), unitInput, hidden)
        if (!isNew && onMove != null) {
            val moves = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            moves.addView(button("▲ Выше", "bm_row_up") { onMove(edited(), -1) })
            moves.addView(button("▼ Ниже", "bm_row_down") { onMove(edited(), 1) })
            content.addView(moves)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (isNew) "Новый показатель" else row.label.ifBlank { "Показатель" })
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Готово", null)
            .setNegativeButton("Отмена", null)
            .apply { if (!isNew && onDelete != null) setNeutralButton("Удалить", null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val next = edited()
                if (next.label.isBlank()) { labelInput.error = "Введите подпись"; return@setOnClickListener }
                onApply(next)
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                show(AlertDialog.Builder(activity).setTitle("Удалить «${row.label}»?")
                    .setMessage("Строка исчезнет из карточки после сохранения. Отменить можно кнопкой «Отмена» редактора.")
                    .setPositiveButton("Удалить") { _, _ ->
                        onDelete?.invoke(row)
                        dialog.dismiss()
                    }
                    .setNegativeButton("Оставить", null).create())
            }
        }
        openRowDialog = dialog
        show(dialog)
    }

    /** Поиск по уже известным сущностям HA: кеш списка и текущие состояния панели. */
    fun pickEntity(current: String, onPicked: (String) -> Unit) = pickEntityWhere(current, { true }, onPicked)

    /** То же, но только сущности, подходящие под [filter] (например, только weather.*). */
    fun pickEntityWhere(current: String, filter: (String) -> Boolean, onPicked: (String) -> Unit) {
        val all = entityChoices().filter { filter(it.first) }
        val search = input("", "Поиск по названию или entity_id").apply { tag = "bm_pick_search" }
        val list = ListView(activity).apply { tag = "bm_pick_list" }
        var shown = all
        val adapter = whiteAdapter(emptyList())
        list.adapter = adapter
        fun filter(query: String) {
            val q = query.trim().lowercase()
            shown = if (q.isEmpty()) all else all.filter { it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
            adapter.clear()
            adapter.addAll(shown.map { (id, name) ->
                val value = model().singleStates[id]?.let { "  ·  $it" }.orEmpty()
                (if (id == current) "✓ " else "") + "$name$value\n$id"
            })
        }
        search.addTextChangedListener(watcher { filter(it) })
        filter("")
        val empty = label(if (all.isEmpty()) "Список сущностей пуст: дождитесь подключения панели к Home Assistant." else "", 14f)
        val content = column(search, empty)
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        val dialog = AlertDialog.Builder(activity).setTitle("Сущность Home Assistant").setView(content)
            .setNegativeButton("Отмена", null).create()
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { onPicked(it.first) }
            dialog.dismiss()
        }
        openPicker = dialog
        show(dialog)
    }

    fun entityChoices(): List<Pair<String, String>> {
        val state = model()
        val names = LinkedHashMap<String, String>()
        cachedEntities.forEach { (id, name) -> names[id] = name }
        state.singleStates.keys.forEach { id ->
            if (id !in names) names[id] = state.entityAttributes[id]?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: id
        }
        return names.entries.map { it.key to it.value }.sortedBy { it.first }
    }

    fun entityName(id: String) = cachedEntities[id]
        ?: model().entityAttributes[id]?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: id

    fun show(dialog: AlertDialog) {
        if (activity.isFinishing || activity.isDestroyed) return
        dialogs.add(dialog)
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            if (openRowDialog === dialog) openRowDialog = null
            if (openPicker === dialog) openPicker = null
        }
        dialog.show()
    }

    fun dispose() {
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
    }

    fun hintBar(hint: UserHints.Hint, message: String): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = BatteryModernCard.rounded(Color.argb(60, 38, 198, 218), dp(12).toFloat(), BatteryModernCard.BORDER)
            setPadding(dp(12), dp(6), dp(4), dp(6))
            tag = "bm_hint_${hint.key}"
            visibility = if (UserHints.isHidden(activity, hint)) View.GONE else View.VISIBLE
        }
        bar.addView(label("ⓘ  $message", 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(activity).apply {
            text = "✕"
            contentDescription = "Закрыть подсказку"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            setOnClickListener {
                UserHints.setHidden(activity, hint, true)
                bar.visibility = View.GONE
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        return bar
    }

    fun label(text: String, size: Float, bold: Boolean = false) = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(if (bold) Color.WHITE else Color.rgb(200, 214, 222))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    fun input(value: String, hint: String) = EditText(activity).apply {
        setText(value)
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(120, 140, 150))
        isSingleLine = true
    }

    fun button(text: String, tag: String, action: () -> Unit) = Button(activity).apply {
        this.text = text
        this.tag = tag
        isAllCaps = false
        setOnClickListener { action() }
    }

    fun column(vararg views: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
        views.forEach { addView(it) }
    }

    fun whiteAdapter(items: List<String>) = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, ArrayList(items)) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getView(position, convertView, parent).also { (it as TextView).setTextColor(Color.WHITE) }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getDropDownView(position, convertView, parent).also { (it as TextView).setTextColor(Color.WHITE) }
    }

    fun watcher(after: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = after(s.toString())
    }

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        fun parseCachedEntities(json: String): Map<String, String> = try {
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
