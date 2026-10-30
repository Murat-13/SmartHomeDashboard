package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Единый редактор плитки «Часы · Погода · Климат дома»: разделы «Часы», «Погода», «Комнаты»,
 * предпросмотр свёрнутой плитки по данным панели. Запись — только «Сохранить»; «Отмена» и «Назад»
 * плитку не меняют. Команд Home Assistant нет.
 */
class HomeClimateEditorActivity : PinProtectedActivity() {
    private lateinit var tileManager: TileManager
    internal lateinit var draft: HomeClimateDraft
        private set
    internal lateinit var preview: HomeClimateCompactView
        private set
    private var model = MainUiState()
    private var previewJob: Job? = null
    private val kit = ModernEditorKit(this) { model }
    private lateinit var content: LinearLayout
    internal var section = SECTION_CLOCK
        private set
    private val tabs = mutableMapOf<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tileManager = TileManager(this)
        val tile = intent.getStringExtra("tile_id")?.let { id -> tileManager.loadTiles().find { it.id == id } }
        if (tile == null || tile.type != HomeClimateConfig.TYPE) {
            Toast.makeText(this, "Плитка не найдена", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        draft = savedInstanceState?.getString(STATE_DRAFT)?.let {
            try { HomeClimateDraft.restore(it, tile) } catch (_: Exception) { null }
        } ?: HomeClimateDraft(tile)
        section = savedInstanceState?.getString(STATE_SECTION) ?: SECTION_CLOCK
        TileEditorPreviewSource.states?.value?.let { model = it }
        buildUi()
        showSection(section)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        kit.loadCachedEntities()
        savedInstanceState?.getString(STATE_ROOM)?.let { raw ->
            runCatching {
                val json = org.json.JSONObject(raw)
                val room = HomeClimateConfig.fromJson(org.json.JSONObject().put("rooms", org.json.JSONArray().put(json))).rooms.single()
                roomDialog(room, json.optBoolean("is_new"))
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 14, 20))
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header.addView(kit.label("Часы · Погода · Климат — настройка", 22f, bold = true))
        header.addView(kit.hintBar(UserHints.Hint.HOME_CLIMATE_EDITOR,
            "Выберите погодную сущность, добавьте комнаты с датчиками температуры (и, по желанию, влажности) и отметьте " +
                "звёздочкой до 5 комнат для свёрнутого вида. Изменения применяются только кнопкой «Сохранить»."))

        preview = HomeClimateCompactView(this).apply { tag = "hc_editor_preview" }
        val previewFrame = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val screenHeight = resources.displayMetrics.heightPixels
                val width = minOf(MeasureSpec.getSize(widthMeasureSpec), kit.dp(440), (screenHeight * 0.38f).toInt())
                preview.layoutParams = LayoutParams(width, width / 2, Gravity.CENTER_HORIZONTAL)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width / 2 + kit.dp(8), MeasureSpec.EXACTLY))
            }
        }
        previewFrame.addView(preview)
        header.addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val headerScroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                    (MeasureSpec.getSize(heightMeasureSpec) * if (MeasureSpec.getSize(heightMeasureSpec) / resources.displayMetrics.density < 420f) 0.32f else 0.45f).toInt(), MeasureSpec.AT_MOST))
            }
        }.apply { addView(header) }
        root.addView(headerScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(SECTION_CLOCK to "Часы", SECTION_WEATHER to "Погода", SECTION_ROOMS to "Комнаты").forEach { (key, label) ->
            val button = kit.button(label, "hc_tab_$key") { showSection(key) }.apply { isAllCaps = false; setTextColor(Color.WHITE) }
            tabs[key] = button
            tabRow.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabRow)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "hc_section" }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        footer.addView(kit.button("Тип и размер…", "hc_tile_settings") { openTileSettings() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(kit.button("Отмена", "hc_cancel") { leave() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(kit.button("Сохранить", "hc_save") { save() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(footer)
        setContentView(root)
    }

    internal fun showSection(key: String) {
        section = key
        tabs.forEach { (tabKey, button) ->
            button.backgroundTintList = ColorStateList.valueOf(if (tabKey == key) 0xFF1E88E5.toInt() else 0xFF2A3442.toInt())
        }
        renderSection()
        renderPreview()
    }

    private fun renderPreview() {
        if (::preview.isInitialized) preview.bind(HomeClimateSnapshot.from(draft.title, draft.config, model))
    }

    /** Любое изменение черновика: предпросмотр сразу, раздел перестраивается. */
    private fun changed(rebuild: Boolean = true) {
        renderPreview()
        if (rebuild) renderSection()
    }

    private fun renderSection() {
        content.removeAllViews()
        when (section) {
            SECTION_WEATHER -> renderWeather()
            SECTION_ROOMS -> renderRooms()
            else -> renderClock()
        }
    }

    // ---- Часы ----

    private fun renderClock() {
        val clock = draft.config.clock
        content.addView(kit.label("Название плитки", 14f))
        content.addView(kit.input(draft.title, "Название").apply {
            tag = "hc_title"
            addTextChangedListener(kit.watcher { draft.setTitle(it); changed(rebuild = false) })
        })
        content.addView(kit.label("Формат времени", 14f))
        content.addView(choice("hc_time_format", listOf<Pair<Boolean?, String>>(null to "Как в системе", true to "24 часа", false to "12 часов"),
            clock.use24h) { draft.setClock(draft.config.clock.copy(use24h = it)); changed() })
        content.addView(kit.label("Формат даты", 14f))
        content.addView(Spinner(this).apply {
            tag = "hc_date_format"
            adapter = kit.whiteAdapter(HomeClimateConfig.DATE_FORMATS.map { it.second })
            setSelection(HomeClimateConfig.DATE_FORMATS.indexOfFirst { it.first == clock.dateFormat }.coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val format = HomeClimateConfig.DATE_FORMATS[position].first
                    if (format != draft.config.clock.dateFormat) { draft.setClock(draft.config.clock.copy(dateFormat = format)); changed(rebuild = false) }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        })
        content.addView(switch("Показывать секунды (если помещаются)", "hc_seconds", clock.seconds) {
            draft.setClock(draft.config.clock.copy(seconds = it)); changed(rebuild = false)
        })
        content.addView(kit.label("Часовой пояс", 14f))
        content.addView(kit.button(clock.timeZone ?: "Авто (из системы: ${TimeZone.getDefault().id})", "hc_time_zone") { pickTimeZone() })
        content.addView(kit.label("Время берётся из системных часов Android, не из Home Assistant.", 13f))
    }

    private fun pickTimeZone() {
        val all = listOf<String?>(null) + TimeZone.getAvailableIDs().filter { it.contains('/') && !it.startsWith("Etc/") }.sorted()
        val search = kit.input("", "Поиск, например Moscow").apply { tag = "hc_tz_search" }
        val list = ListView(this)
        val adapter = kit.whiteAdapter(emptyList())
        list.adapter = adapter
        var shown = all
        fun filter(query: String) {
            val q = query.trim().lowercase()
            shown = if (q.isEmpty()) all else all.filter { it == null || it.lowercase().contains(q) }
            adapter.clear()
            adapter.addAll(shown.map { it ?: "Авто (из системы)" })
        }
        search.addTextChangedListener(kit.watcher { filter(it) })
        filter("")
        val box = kit.column(search)
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kit.dp(360)))
        val dialog = AlertDialog.Builder(this).setTitle("Часовой пояс").setView(box).setNegativeButton("Отмена", null).create()
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            draft.setClock(draft.config.clock.copy(timeZone = shown.getOrNull(position)))
            changed()
            dialog.dismiss()
        }
        kit.show(dialog)
    }

    // ---- Погода ----

    private fun renderWeather() {
        val weather = draft.config.weather
        content.addView(kit.label("Источник погоды", 14f))
        content.addView(choice("hc_weather_source", listOf(HomeClimateConfig.SOURCE_HA to "Home Assistant", HomeClimateConfig.SOURCE_YANDEX to "Яндекс"), weather.source) {
            draft.setWeather(draft.config.weather.copy(source = it)); changed()
        })
        if (weather.source == HomeClimateConfig.SOURCE_YANDEX) {
            content.addView(kit.label("Используются сохранённые ключ и координаты Яндекса из общих настроек. Погода загрузится после сохранения плитки.", 13f))
        } else {
            content.addView(kit.label("Сущность погоды Home Assistant", 14f))
            val entityLabel = if (weather.entityId.isBlank()) "Выбрать weather.*…" else "${kit.entityName(weather.entityId)}\n${weather.entityId}"
            content.addView(kit.button(entityLabel, "hc_weather_entity") {
                kit.pickEntityWhere(weather.entityId, { it.startsWith("weather.") }) {
                    draft.setWeather(draft.config.weather.copy(entityId = it)); changed()
                }
            })
            val manual = kit.input(weather.entityId, "или введите entity_id, например weather.home").apply { tag = "hc_weather_manual" }
            val error = kit.label("", 13f).apply { setTextColor(Color.rgb(255, 120, 120)) }
            manual.addTextChangedListener(kit.watcher { value ->
                draft.setWeather(draft.config.weather.copy(entityId = value.trim())); changed(rebuild = false)
            })
            content.addView(manual)
            content.addView(kit.button("Применить entity_id", "hc_weather_apply") {
                val id = manual.text.toString().trim()
                if (id.isNotEmpty() && !HomeClimateConfig.validEntity(id, "weather")) { error.text = "Нужна сущность вида weather.…"; return@button }
                draft.setWeather(draft.config.weather.copy(entityId = id)); changed()
            })
            content.addView(error)
            if (weather.entityId.isNotBlank() && model.singleStates.isNotEmpty() && weather.entityId !in model.singleStates) {
                content.addView(kit.label("⚠ Сущность пока не найдена среди данных Home Assistant панели.", 13f).apply {
                    setTextColor(Color.rgb(255, 205, 120))
                })
            }
        }
        content.addView(kit.label("Единицы температуры", 14f))
        content.addView(choice("hc_unit", listOf(HomeClimateConfig.UNIT_AUTO to (if (weather.source == HomeClimateConfig.SOURCE_YANDEX) "Как в Яндексе" else "Как в Home Assistant"),
            HomeClimateConfig.UNIT_C to "°C", HomeClimateConfig.UNIT_F to "°F"), weather.unit) {
            draft.setWeather(draft.config.weather.copy(unit = it)); changed()
        })
        content.addView(kit.label("Показывать в развёрнутом виде", 14f, bold = true))
        fun option(label: String, tag: String, value: Boolean, apply: (HomeClimateConfig.Weather, Boolean) -> HomeClimateConfig.Weather) =
            content.addView(check(label, tag, value) { draft.setWeather(apply(draft.config.weather, it)); changed(rebuild = false) })
        option("Ощущаемая температура", "hc_show_feels", weather.showFeelsLike) { w, v -> w.copy(showFeelsLike = v) }
        option("Влажность", "hc_show_humidity", weather.showHumidity) { w, v -> w.copy(showHumidity = v) }
        option("Ветер", "hc_show_wind", weather.showWind) { w, v -> w.copy(showWind = v) }
        option("Осадки", "hc_show_precipitation", weather.showPrecipitation) { w, v -> w.copy(showPrecipitation = v) }
        option("Давление", "hc_show_pressure", weather.showPressure) { w, v -> w.copy(showPressure = v) }
        option("Почасовой прогноз", "hc_show_hourly", weather.showHourly) { w, v -> w.copy(showHourly = v) }
        option("Прогноз на 5 дней", "hc_show_daily", weather.showDaily) { w, v -> w.copy(showDaily = v) }
        content.addView(kit.label("Показатели и прогнозы показываются только при наличии данных у выбранной интеграции; " +
            "недоступные не заполняются примерными значениями.", 13f))
    }

    // ---- Комнаты ----

    private fun renderRooms() {
        val config = draft.config
        content.addView(kit.label("Избранные для свёрнутого вида (${config.favorites.size} из ${HomeClimateConfig.MAX_FAVORITES})", 16f, bold = true))
        if (config.favoriteRooms.isEmpty()) content.addView(kit.label("Отметьте комнату звёздочкой ниже.", 13f))
        config.favoriteRooms.forEachIndexed { index, room ->
            content.addView(row("hc_fav_${room.id}", "★ ${room.name.ifBlank { "Без названия" }}", null,
                up = if (index > 0) ({ draft.moveFavorite(room.id, -1); changed() }) else null,
                down = if (index < config.favoriteRooms.size - 1) ({ draft.moveFavorite(room.id, 1); changed() }) else null))
        }
        content.addView(kit.label("Все комнаты (${config.rooms.size})", 16f, bold = true).apply { setPadding(0, kit.dp(12), 0, 0) })
        config.rooms.forEachIndexed { index, room ->
            val subtitle = listOf(room.temperatureEntity.ifBlank { "датчик температуры не выбран" }, room.humidityEntity)
                .filter { it.isNotBlank() }.joinToString(" · ")
            val line = row("hc_room_${room.id}", (if (config.isFavorite(room.id)) "★ " else "☆ ") + room.name.ifBlank { "Без названия" }, subtitle,
                up = if (index > 0) ({ draft.moveRoom(room.id, -1); changed() }) else null,
                down = if (index < config.rooms.size - 1) ({ draft.moveRoom(room.id, 1); changed() }) else null)
            line.addView(kit.button(if (config.isFavorite(room.id)) "★" else "☆", "hc_star_${room.id}") { toggleFavorite(room.id) }, 0)
            line.setOnClickListener { roomDialog(room, isNew = false) }
            content.addView(line)
        }
        content.addView(kit.button("+ Добавить комнату", "hc_room_add") { roomDialog(draft.newRoom(), isNew = true) })
    }

    internal fun toggleFavorite(id: String) {
        when (draft.toggleFavorite(id)) {
            HomeClimateConfig.FavoriteChange.LIMIT -> Toast.makeText(this,
                "В свёрнутом виде можно показать не больше ${HomeClimateConfig.MAX_FAVORITES} комнат. Сначала снимите звёздочку с другой.",
                Toast.LENGTH_LONG).show()
            else -> changed()
        }
    }

    private var pendingRoomState: (() -> String)? = null
    internal var openRoomDialog: AlertDialog? = null
        private set

    /** Комната применяется к черновику по «Готово»; удаление — после подтверждения. */
    internal fun roomDialog(room: HomeClimateConfig.Room, isNew: Boolean) {
        var temperature = room.temperatureEntity
        var humidity = room.humidityEntity
        val name = kit.input(room.name, "Название, например Спальня").apply { tag = "hc_room_name" }
        val picker = ModernIconPicker(this) { }.apply {
            select(room.modernIcon)
            setNoneLabel("Без иконки", "—")
        }
        val tempButton = Button(this)
        val humidityButton = Button(this)
        val warning = kit.label("", 13f).apply { setTextColor(Color.rgb(255, 205, 120)) }
        val error = kit.label("", 13f).apply { setTextColor(Color.rgb(255, 120, 120)); tag = "hc_room_error" }
        fun unitWarning(id: String, expected: String): String? {
            val unit = model.entityAttributes[id]?.optString("unit_of_measurement").orEmpty()
            val ok = when (expected) {
                "temperature" -> unit.isEmpty() || unit.contains("C") || unit.contains("F")
                else -> unit.isEmpty() || unit.contains("%")
            }
            return if (ok) null else "Единица «$unit» у $id не похожа на ${if (expected == "temperature") "°C/°F" else "%"}"
        }
        fun refresh() {
            tempButton.text = if (temperature.isBlank()) "Датчик температуры (обязательно)…" else "🌡 ${kit.entityName(temperature)}\n$temperature"
            humidityButton.text = if (humidity.isBlank()) "Датчик влажности (необязательно)…" else "💧 ${kit.entityName(humidity)}\n$humidity"
            warning.text = listOfNotNull(temperature.takeIf { it.isNotBlank() }?.let { unitWarning(it, "temperature") },
                humidity.takeIf { it.isNotBlank() }?.let { unitWarning(it, "humidity") }).joinToString("\n")
        }
        tempButton.tag = "hc_room_temperature"
        humidityButton.tag = "hc_room_humidity"
        tempButton.isAllCaps = false
        humidityButton.isAllCaps = false
        tempButton.setOnClickListener { kit.pickEntityWhere(temperature, { it.startsWith("sensor.") }) { temperature = it; refresh() } }
        humidityButton.setOnClickListener { kit.pickEntityWhere(humidity, { it.startsWith("sensor.") }) { humidity = it; refresh() } }
        val manual = kit.input("", "или введите entity_id датчика температуры").apply { tag = "hc_room_temperature_manual" }
        val manualApply = kit.button("Применить", "hc_room_temperature_apply") {
            manual.text.toString().trim().takeIf { it.isNotEmpty() }?.let { temperature = it; refresh() }
        }
        manual.addTextChangedListener(kit.watcher { temperature = it.trim(); refresh() })
        val humidityManual = kit.input(humidity, "или введите entity_id датчика влажности").apply { tag = "hc_room_humidity_manual" }
        humidityManual.addTextChangedListener(kit.watcher { humidity = it.trim(); refresh() })
        val clearHumidity = kit.button("Убрать датчик влажности", "hc_room_humidity_clear") { humidity = ""; humidityManual.setText(""); refresh() }
        refresh()
        val body = kit.column(kit.label("Название", 14f), name, kit.label("Иконка", 14f), picker,
            kit.label("Температура", 14f), tempButton, manual, manualApply,
            kit.label("Влажность", 14f), humidityButton, humidityManual, clearHumidity, warning, error)
        val dialog = AlertDialog.Builder(this).setTitle(if (isNew) "Новая комната" else "Комната")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Готово", null)
            .setNegativeButton("Отмена", null)
            .apply { if (!isNew) setNeutralButton("Удалить", null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val problem = when {
                    name.text.isBlank() -> "Введите название комнаты"
                    !HomeClimateConfig.validEntity(temperature, "sensor") -> "Выберите sensor.* температуры"
                    humidity.isNotBlank() && !HomeClimateConfig.validEntity(humidity, "sensor") -> "Выберите sensor.* влажности"
                    else -> HomeClimateConfig.roomUnitError(temperature, model.entityAttributes[temperature])
                        ?: HomeClimateConfig.roomUnitError(humidity, model.entityAttributes[humidity], humidity = true)
                }
                if (problem != null) { error.text = problem; return@setOnClickListener }
                draft.putRoom(room.copy(name = name.text.toString().trim(), icon = picker.selected?.key,
                    temperatureEntity = temperature, humidityEntity = humidity))
                changed()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                kit.show(AlertDialog.Builder(this).setTitle("Удалить комнату «${room.name}»?")
                    .setMessage("Датчики в Home Assistant не изменятся.")
                    .setPositiveButton("Удалить") { _, _ -> draft.deleteRoom(room.id); changed(); dialog.dismiss() }
                    .setNegativeButton("Оставить", null).create())
            }
        }
        pendingRoomState = {
            org.json.JSONObject().put("id", room.id).put("name", name.text.toString())
                .put("icon", picker.selected?.key).put("temperature", temperature).put("humidity", humidity)
                .put("is_new", isNew).toString()
        }
        openRoomDialog = dialog
        kit.show(dialog)
    }

    // ---- Элементы ----

    private fun <T> choice(tag: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            this.tag = tag
            options.forEachIndexed { index, (value, label) ->
                addView(kit.button(label, "${tag}_$index") { if (value != selected) onPick(value) }.apply {
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(if (value == selected) 0xFF1E88E5.toInt() else 0xFF2A3442.toInt())
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun switch(label: String, tag: String, value: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(this).apply {
        text = label
        this.tag = tag
        setTextColor(Color.WHITE)
        isChecked = value
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun check(label: String, tag: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        this.tag = tag
        setTextColor(Color.WHITE)
        isChecked = value
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun row(tag: String, title: String, subtitle: String?, up: (() -> Unit)?, down: (() -> Unit)?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        this.tag = tag
        isClickable = true
        setPadding(0, kit.dp(4), 0, kit.dp(4))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(kit.label(title, 16f))
        subtitle?.let { texts.addView(kit.label(it, 12f).apply { setTextColor(0xFF9FB3C8.toInt()) }) }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(kit.button("▲", "${tag}_up") { up?.invoke() }.apply { isEnabled = up != null })
        addView(kit.button("▼", "${tag}_down") { down?.invoke() }.apply { isEnabled = down != null })
    }

    // ---- Жизненный цикл ----

    override fun onStart() {
        super.onStart()
        previewJob?.cancel()
        TileEditorPreviewSource.states?.let { flow ->
            previewJob = lifecycleScope.launch {
                flow.collect {
                    model = it
                    renderPreview()
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
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::draft.isInitialized) outState.putString(STATE_DRAFT, draft.saveState())
        if (openRoomDialog?.isShowing == true) outState.putString(STATE_ROOM, pendingRoomState?.invoke())
        outState.putString(STATE_SECTION, section)
        super.onSaveInstanceState(outState)
    }

    // ---- Сохранение / отмена ----

    internal fun save(): Boolean {
        draft.validationError()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); return false }
        draft.config.rooms.forEach { room ->
            val error = HomeClimateConfig.roomUnitError(room.temperatureEntity, model.entityAttributes[room.temperatureEntity])
                ?: HomeClimateConfig.roomUnitError(room.humidityEntity, model.entityAttributes[room.humidityEntity], humidity = true)
            if (error != null) { Toast.makeText(this, error, Toast.LENGTH_LONG).show(); return false }
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
        private const val STATE_ROOM = "home_climate_room_draft"
        private const val STATE_DRAFT = "home_climate_draft"
        private const val STATE_SECTION = "home_climate_section"
        const val SECTION_CLOCK = "clock"
        const val SECTION_WEATHER = "weather"
        const val SECTION_ROOMS = "rooms"
    }
}
