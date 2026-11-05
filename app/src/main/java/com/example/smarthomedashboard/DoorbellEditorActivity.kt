package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Редактор плитки «Домофон» (docs/doorbell.md): слева живой предпросмотр плитки, справа — источники видео,
 * датчики, калитка и свет, звук звонка этого планшета, раскрытие. Пароль RTSP — только в защищённое
 * хранилище при «Сохранить». Команд HA нет; запись — только «Сохранить» (под PIN).
 */
class DoorbellEditorActivity : PinProtectedActivity() {
    private lateinit var tileManager: TileManager
    internal lateinit var original: TileEntity
        private set
    internal var config = DoorbellConfig()
        private set
    internal var title = ""
        private set
    /** Новый пароль RTSP; null — не меняется, "" — удалить. */
    internal var password: String? = null
        private set
    private var savedPassword: Boolean? = null
    internal lateinit var preview: DoorbellCard
        private set
    private val kit = ModernEditorKit(this) { model }
    private var model = MainUiState()
    private var previewJob: Job? = null
    private val rows = mutableMapOf<String, TextView>()
    private lateinit var passwordStatus: TextView
    private lateinit var toneLabel: TextView

    private val pickTone = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Файл остаётся у владельца: сохраняем постоянный доступ, а не копию.
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        update(config.copy(ringTone = uri.toString()))
        refreshTone()
        testRing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tileManager = TileManager(this)
        val tile = intent.getStringExtra("tile_id")?.let { id -> tileManager.loadTiles().find { it.id == id } }
        if (tile == null || tile.type != DoorbellConfig.TYPE) {
            Toast.makeText(this, "Плитка не найдена", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        original = tile
        title = tile.title
        config = DoorbellConfig.from(tile.config)
        savedInstanceState?.getString(STATE_DRAFT)?.let { text ->
            try {
                val json = JSONObject(text)
                config = DoorbellConfig.fromJson(json.getJSONObject("config"))
                title = json.optString("title", title)
                // Пароль в состоянии экрана не сохраняется: после пересоздания его нужно ввести снова.
            } catch (_: Exception) {}
        }
        TileEditorPreviewSource.states?.value?.let { model = it }
        PanelAlerts.attach(applicationContext)
        buildUi()
        render()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        kit.loadCachedEntities()
        DoorbellHub.cameraPassword(this) { savedPassword = !it.isNullOrEmpty(); refreshPassword() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 14, 20))
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
        }
        root.addView(kit.label("Домофон — настройка", 22f, bold = true))
        root.addView(kit.hintBar(UserHints.Hint.DOORBELL_EDITOR,
            "Reolink: основной поток rtsp://IP:554/h264Preview_01_main, субпоток — …_sub. Прямой поток работает в " +
                "домашней сети; вне дома видео идёт через Home Assistant (камера HA). Звук и тихие часы — только этого " +
                "планшета. Изменения применяются кнопкой «Сохранить»."))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        preview = DoorbellCard(this, expanded = false)
        val previewBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val frame = FrameLayout(this).apply { tag = "door_editor_preview" }
        frame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewBox.addView(frame, LinearLayout.LayoutParams(kit.dp(420), (kit.dp(420) / config.aspect).toInt()).apply {
            topMargin = kit.dp(12)
        })
        previewBox.addView(kit.label("Предпросмотр — данные панели и видео, как на плитке.", 13f))
        previewBox.addView(kit.button("Проверить звук звонка", "door_test_ring") { testRing() })
        body.addView(previewBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(kit.dp(16), 0, 0, 0) }
        side.addView(header("Название"))
        side.addView(kit.input(title, DoorbellConfig.DEFAULT_TITLE).apply {
            tag = "door_title_input"
            addTextChangedListener(kit.watcher { title = it.trim(); render() })
        })

        side.addView(header("Видео"))
        side.addView(entityRow("camera", "Камера Home Assistant", "снимки и видео вне дома") { config.camera })
        side.addView(text("rtsp_main", "Прямой поток (основной)", config.rtspMain, "rtsp://192.168.1.50:554/h264Preview_01_main") {
            update(config.copy(rtspMain = it)) })
        side.addView(text("rtsp_sub", "Прямой поток для плитки (субпоток)", config.rtspSub, "rtsp://…/h264Preview_01_sub") {
            update(config.copy(rtspSub = it)) })
        side.addView(text("rtsp_user", "Логин камеры", config.rtspUser, "admin") { update(config.copy(rtspUser = it)) })
        side.addView(kit.label("Пароль камеры", 15f))
        side.addView(kit.input("", "Новый пароль (хранится зашифрованно)").apply {
            tag = "door_password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            addTextChangedListener(kit.watcher { password = it.takeIf { v -> v.isNotEmpty() }; refreshPassword() })
        })
        passwordStatus = kit.label("", 13f).apply { tag = "door_password_status" }
        side.addView(passwordStatus)
        side.addView(kit.button("Удалить сохранённый пароль", "door_password_clear") { password = ""; refreshPassword() })
        side.addView(choice("door_mode", "Плитка показывает", DoorbellConfig.VideoMode.entries.map { it.title },
            DoorbellConfig.VideoMode.entries.indexOf(config.mode)) { update(config.copy(mode = DoorbellConfig.VideoMode.entries[it])) })
        side.addView(kit.label("«Снимок раз в минуту» — для удалённого планшета: почти не тратит интернет.", 13f))
        side.addView(choice("door_aspect", "Пропорция картинки", listOf("4:3 (Reolink 2K+)", "16:9", "По видео"),
            when { kotlin.math.abs(config.aspect - 4f / 3f) < 0.01f -> 0; kotlin.math.abs(config.aspect - 16f / 9f) < 0.01f -> 1; else -> 2 }) {
            val aspect = when (it) { 0 -> 4f / 3f; 1 -> 16f / 9f; else -> preview.video.videoAspect }
            update(config.copy(aspect = aspect))
        })

        side.addView(header("Датчики домофона"))
        side.addView(entityRow("visitor", "Кнопка звонка", "binary_sensor … visitor") { config.visitor })
        side.addView(entityRow("person", "Человек", "значок и янтарная рамка") { config.person })
        side.addView(entityRow("animal", "Животное", "значок") { config.animal })
        side.addView(entityRow("vehicle", "Машина", "значок") { config.vehicle })
        side.addView(entityRow("motion", "Движение", "значок") { config.motion })

        side.addView(header("Калитка, свет, ответы"))
        side.addView(entityRow("lock", "Замок калитки", "кнопки «Открыть» нет, пока не выбран") { config.lock })
        side.addView(entityRow("light", "Свет у входа", "light или switch") { config.light })
        side.addView(switch("door_auto_light", "Включать свет при звонке после заката", config.autoLight) { update(config.copy(autoLight = it)) })
        side.addView(number("door_light_off", "Гасить через, мин после закрытия окна (0 — не гасить)", config.lightOffMinutes, 0..120) {
            update(config.copy(lightOffMinutes = it)) })
        side.addView(entityRow("quick_reply", "Готовые фразы домофона", "select … quick reply") { config.quickReply })

        side.addView(header("Звук звонка — этот планшет"))
        toneLabel = kit.label("", 15f).apply { tag = "door_tone_label" }
        side.addView(toneLabel)
        val toneButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toneButtons.addView(kit.button("Встроенная", "door_tone_default") { update(config.copy(ringTone = "")); refreshTone(); testRing() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toneButtons.addView(kit.button("Выбрать файл…", "door_tone_pick") { pickTone.launch(arrayOf("audio/*")) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        side.addView(toneButtons)
        refreshTone()
        side.addView(slider("door_volume", config.ringVolume, 100, { "Громкость звонка: $it%" }) { update(config.copy(ringVolume = it)); testRing() })
        side.addView(switch("door_voice", "Голос «Звонят в калитку» после мелодии", config.ringVoice) { update(config.copy(ringVoice = it)) })
        side.addView(text("quiet_start", "Тихие часы — начало", VoiceAlertSettings.minuteText(config.quietStartMin), "23:00") { value ->
            VoiceAlertSettings.parseMinute(value)?.let { update(config.copy(quietStartMin = it)) } })
        side.addView(text("quiet_end", "Тихие часы — конец", VoiceAlertSettings.minuteText(config.quietEndMin), "07:00") { value ->
            VoiceAlertSettings.parseMinute(value)?.let { update(config.copy(quietEndMin = it)) } })
        side.addView(slider("door_quiet_percent", config.quietPercent, 100, { "Громкость в тихие часы: $it% от обычной" }) {
            update(config.copy(quietPercent = it))
            playSound(config.ringVolume / 100f * it / 100f)
        })
        side.addView(slider("door_night_brightness", config.nightBrightness, 100, { "Яркость окна звонка ночью: ${it.coerceAtLeast(5)}%" }) {
            update(config.copy(nightBrightness = it.coerceAtLeast(5))) })
        side.addView(switch("door_offline_sound", "Сигнал, если домофон пропал из сети", config.offlineSound) { update(config.copy(offlineSound = it)) })

        side.addView(header("Раскрытие по касанию"))
        side.addView(switch("door_auto_collapse", "Сворачивать автоматически", config.autoCollapse) { update(config.copy(autoCollapse = it)) })
        side.addView(number("door_collapse_seconds", "Через, секунд", config.collapseSeconds, 5..3600) { update(config.copy(collapseSeconds = it)) })

        side.addView(kit.button("Тип, размер, удаление плитки…", "door_tile_settings") { openTileSettings() })
        body.addView(ScrollView(this).apply { addView(side) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(kit.footer("door", { leave() }, { save() }))
        setContentView(root)
    }

    // ---- Строки редактора ----

    private fun header(text: String) = kit.label(text, 18f, bold = true).apply { setPadding(0, kit.dp(16), 0, kit.dp(4)) }

    private fun entityRow(role: String, label: String, hint: String, value: () -> String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = kit.dp(56)
            background = BatteryModernCard.rounded(Color.argb(50, 30, 44, 56), kit.dp(12).toFloat(), Color.argb(80, 95, 168, 211))
            setPadding(kit.dp(12), kit.dp(6), kit.dp(4), kit.dp(6))
            tag = "door_row_$role"
            isClickable = true
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(kit.label(label, 15f, bold = true))
        val current = kit.label("", 13f)
        rows[role] = current
        texts.addView(current)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(kit.button("✕", "door_clear_$role") { set(role, "") }.apply {
            contentDescription = "Очистить: $label"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(kit.dp(48), kit.dp(48)))
        row.setOnClickListener {
            kit.pickEntityWhere(value(), { DoorbellModel.fits(role, it) }) { picked -> set(role, picked) }
        }
        current.text = describe(value(), hint)
        current.tag = hint
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, kit.dp(4), 0, kit.dp(4))
            addView(row)
        }
    }

    private fun describe(id: String, hint: String) = if (id.isBlank()) "не выбрано · $hint" else "${kit.entityName(id)} · $id"

    private fun set(role: String, id: String) {
        update(when (role) {
            "camera" -> config.copy(camera = id)
            "visitor" -> config.copy(visitor = id)
            "person" -> config.copy(person = id)
            "animal" -> config.copy(animal = id)
            "vehicle" -> config.copy(vehicle = id)
            "motion" -> config.copy(motion = id)
            "lock" -> config.copy(lock = id)
            "light" -> config.copy(light = id)
            "quick_reply" -> config.copy(quickReply = id)
            else -> config
        })
        rows[role]?.let { it.text = describe(id, it.tag as? String ?: "") }
    }

    private fun text(key: String, label: String, value: String, hint: String, onChange: (String) -> Unit) = kit.column(
        kit.label(label, 15f),
        kit.input(value, hint).apply {
            tag = "door_$key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            addTextChangedListener(kit.watcher { onChange(it.trim()) })
        }
    ).apply { setPadding(0, 0, 0, 0) }

    private fun number(tag: String, label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) = kit.column(
        kit.label(label, 15f),
        kit.input(value.toString(), range.first.toString()).apply {
            this.tag = tag
            inputType = InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(kit.watcher { text -> text.toIntOrNull()?.takeIf { it in range }?.let(onChange) })
        }
    ).apply { setPadding(0, 0, 0, 0) }

    private fun switch(tag: String, label: String, checked: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(this).apply {
        this.tag = tag
        text = label
        setTextColor(Color.WHITE)
        isChecked = checked
        minHeight = kit.dp(48)
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun choice(tag: String, label: String, items: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(kit.label(label, 15f))
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.tag = tag }
        val buttons = items.mapIndexed { index, item -> kit.button(item, "${tag}_$index") {}.apply { isAllCaps = false } }
        fun paint(active: Int) = buttons.forEachIndexed { i, b ->
            b.background = BatteryModernCard.rounded(if (i == active) Color.argb(90, 95, 168, 211) else Color.argb(40, 30, 44, 56),
                kit.dp(10).toFloat(), Color.argb(if (i == active) 220 else 70, 95, 168, 211))
            b.setTextColor(Color.WHITE)
        }
        buttons.forEachIndexed { i, b ->
            b.setOnClickListener { paint(i); onPick(i) }
            line.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = kit.dp(6) })
        }
        paint(selected)
        box.addView(line)
        return box
    }

    private fun slider(tag: String, value: Int, max: Int, text: (Int) -> String, onRelease: (Int) -> Unit): View {
        val label = kit.label(text(value), 15f)
        val bar = SeekBar(this).apply {
            this.tag = tag
            this.max = max
            progress = value
            minimumHeight = kit.dp(48)
            progressTintList = ContextCompat.getColorStateList(this@DoorbellEditorActivity, R.color.modern_accent)
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { label.text = text(progress) }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) = onRelease(seekBar.progress)
            })
        }
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(bar) }
    }

    private fun refreshPassword() {
        if (!::passwordStatus.isInitialized) return
        passwordStatus.text = when (password) {
            null -> when (savedPassword) { true -> "Пароль сохранён"; false -> "Пароль не задан"; null -> "Проверяю…" }
            "" -> "Сохранённый пароль будет удалён при «Сохранить»"
            else -> "Новый пароль будет сохранён при «Сохранить»"
        }
    }

    private fun refreshTone() {
        if (!::toneLabel.isInitialized) return
        toneLabel.text = "Мелодия: " + if (config.ringTone.isBlank()) "встроенная «динь-дон»" else
            (Uri.parse(config.ringTone).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "свой файл")
    }

    private fun testRing() = playSound(config.ringVolume / 100f)

    private fun playSound(volume: Float) {
        val tone = config.ringTone.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: VoiceAnnouncer.resource(this, R.raw.doorbell_ding_dong)
        PanelAlerts.sound(if (config.ringVoice) DoorbellHub.RING_PHRASE else null, tone, volume, "doorbell_preview")
    }

    // ---- Черновик и предпросмотр ----

    private fun update(next: DoorbellConfig) {
        if (next == config) return
        config = next
        render()
    }

    internal val isDirty get() = title != original.title || config != DoorbellConfig.from(original.config) || password != null

    internal fun render() {
        if (!::preview.isInitialized) return
        val readings = BatteryReadings.from(model)
        val snapshot = DoorbellModel.snapshot(config, readings)
        preview.bind(title, config, snapshot, offline = false, missed = DoorbellHub.missed(original.id),
            lastRing = DoorbellHub.lastRing(original.id), now = System.currentTimeMillis(), animate = false)
        (preview.parent as? View)?.let { frame ->
            val lp = frame.layoutParams
            val height = (kit.dp(420) / config.aspect).toInt()
            if (lp.height != height) { lp.height = height; frame.layoutParams = lp }
        }
    }

    override fun onStart() {
        super.onStart()
        previewJob?.cancel()
        TileEditorPreviewSource.states?.let { flow ->
            previewJob = lifecycleScope.launch { flow.collect { model = it; render() } }
        }
    }

    override fun onStop() {
        previewJob?.cancel()
        previewJob = null
        PanelAlerts.cancel("doorbell_preview")
        super.onStop()
    }

    override fun onDestroy() {
        kit.dispose()
        if (::preview.isInitialized) preview.dispose()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::original.isInitialized) outState.putString(STATE_DRAFT, JSONObject().put("config", config.toJson()).put("title", title).toString())
        super.onSaveInstanceState(outState)
    }

    // ---- Сохранение ----

    internal fun save(): Boolean {
        if (!requireAdmin()) return false
        val latest = tileManager.loadTiles().find { it.id == original.id }
        if (latest == null || latest != original) {
            Toast.makeText(this, "Плитка изменена другим экраном. Закройте редактор и откройте её заново.", Toast.LENGTH_LONG).show()
            return false
        }
        // Логин и пароль, вставленные в адрес, переносятся в поле логина и защищённое хранилище.
        val (main, mainUser, mainPass) = DoorbellConfig.splitCredentials(config.rtspMain)
        val (sub, subUser, subPass) = DoorbellConfig.splitCredentials(config.rtspSub)
        if (main != config.rtspMain || sub != config.rtspSub) {
            config = config.copy(rtspMain = main, rtspSub = sub, rtspUser = config.rtspUser.ifBlank { mainUser ?: subUser.orEmpty() })
            if (password.isNullOrEmpty()) (mainPass ?: subPass)?.let { password = it }
        }
        val (w, h) = DoorbellConfig.fit(
            JSONObject(latest.config).optInt("button_size", DoorbellConfig.DEFAULT_WIDTH),
            JSONObject(latest.config).optInt("widget_height", (DoorbellConfig.DEFAULT_WIDTH / DoorbellConfig.DEFAULT_ASPECT).toInt()),
            config.aspect)
        val json = JSONObject(config.writeTo(latest.config)).put("button_size", w).put("widget_height", h)
        tileManager.updateTile(latest.copy(title = title.ifBlank { DoorbellConfig.DEFAULT_TITLE }, config = json.toString()))
        val secret = password
        if (secret == null) { finishSaved(); return true }
        DoorbellHub.savePassword(this, secret) { ok ->
            if (!ok) Toast.makeText(this, "Настройки сохранены, но пароль камеры записать не удалось", Toast.LENGTH_LONG).show()
            finishSaved()
        }
        return true
    }

    private fun finishSaved() {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    internal fun leave() {
        if (!::original.isInitialized || !isDirty) { finish(); return }
        kit.show(AlertDialog.Builder(this).setTitle("Сохранить изменения?")
            .setMessage("Настройки «${title.ifBlank { DoorbellConfig.DEFAULT_TITLE }}» пока не записаны.")
            .setPositiveButton("Сохранить") { _, _ -> save() }
            .setNegativeButton("Не сохранять") { _, _ -> finish() }
            .setNeutralButton("Продолжить редактирование", null).create())
    }

    private fun openTileSettings() {
        if (isDirty) {
            Toast.makeText(this, "Сначала сохраните или отмените изменения", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, TileSettingsActivity::class.java).putExtra("tile_id", original.id))
        setResult(Activity.RESULT_OK)
        finish()
    }

    internal val openPicker get() = kit.openPicker

    companion object {
        private const val STATE_DRAFT = "doorbell_draft"
    }
}
