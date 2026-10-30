package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Черновик без доступа к сети и хранилищу плиток. Список — из общего состояния HA и кеша имён. */
internal class ModernPowerEditor(context: Context, initial: LivePowerConfig, private val onChanged: () -> Unit) : LinearLayout(context) {
    private val toggle = Switch(context).apply { text = "Показывать мощность"; setTextColor(Color.WHITE); isChecked = initial.enabled }
    private val sensor = AutoCompleteTextView(context).apply {
        hint = "Сущность мощности Home Assistant"; setTextColor(Color.WHITE); setHintTextColor(0xFFB6C3D1.toInt()); setText(initial.entityId); threshold = 0
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        setSingleLine(); importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    private val choose = Button(context).apply { text = "Выбрать сенсор • поиск по имени или entity_id" }
    private var state: MainUiState? = null
    private var suggestions = emptyList<Pair<String, String>>()
    var changed = false
        private set
    val config get() = LivePowerConfig(toggle.isChecked, sensor.text.toString().trim())
    private val cachedNames by lazy { runCatching {
            val raw = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE).getString("cached_entities", "[]")
            Gson().fromJson<List<HaEntity>>(raw, object : TypeToken<List<HaEntity>>() {}.type)
                .filter { it.entityId.startsWith("sensor.") }.associate { it.entityId to it.name }
        }.getOrDefault(emptyMap()) }
    init {
        orientation = VERTICAL
        setPadding(8, 16, 8, 8)
        setBackgroundColor(0xFF0E1A26.toInt())
        addView(TextView(context).apply { text = "Потребляемая мощность"; setTextColor(Color.WHITE); textSize = 16f })
        addView(toggle); addView(sensor); addView(choose)
        sensor.visibility = if (initial.enabled) VISIBLE else GONE
        choose.visibility = sensor.visibility
        toggle.setOnCheckedChangeListener { _, checked ->
            sensor.visibility = if (checked) VISIBLE else GONE; choose.visibility = sensor.visibility
            changed = true; onChanged()
        }
        sensor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed = true; onChanged() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        sensor.setOnItemClickListener { _, _, position, _ ->
            val label = sensor.adapter.getItem(position).toString()
            suggestions.firstOrNull { it.second == label }?.let { sensor.setText(it.first, false) }
        }
        choose.setOnClickListener { sensor.requestFocus(); (sensor.adapter as? PowerSensorAdapter)?.filter?.filter("") { sensor.showDropDown() } }
        acceptState(TileEditorPreviewSource.states?.value)
    }
    fun acceptState(next: MainUiState?) {
        state = next
        val names = cachedNames
        val ids = (names.keys + next?.singleStates.orEmpty().keys).filter { it.startsWith("sensor.") }.sorted()
        val options = ids.filter { id -> next?.entityAttributes?.get(id)?.let { LivePowerConfig(true, id).error(it) == null } ?: true }
            .map { id -> id to "${names[id] ?: next?.entityAttributes?.get(id)?.optString("friendly_name") ?: id} · $id" }
        if (suggestions != options) {
            suggestions = options
            sensor.setAdapter(PowerSensorAdapter(context, options.map { it.second }))
        }
    }
    fun validate(): Boolean {
        val error = config.error(state?.entityAttributes?.get(config.entityId))
        sensor.error = error
        if (error != null) sensor.requestFocus()
        return error == null
    }
    fun powerText(): String? = config.text(state?.singleStates.orEmpty(), state?.entityAttributes.orEmpty(), state?.staleEntities.orEmpty())
}

/** Поиск по любой части имени и entity_id, включая середину slug. */
internal class PowerSensorAdapter(context: Context, private val all: List<String>) :
    ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, all.toMutableList()) {
    private val search = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString().orEmpty().trim()
            val matches = all.filter { it.contains(query, ignoreCase = true) }
            return FilterResults().apply { values = matches; count = matches.size }
        }
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            @Suppress("UNCHECKED_CAST") val matches = results?.values as? List<String> ?: emptyList()
            setNotifyOnChange(false); clear(); addAll(matches); notifyDataSetChanged()
        }
    }
    override fun getFilter(): Filter = search
}
