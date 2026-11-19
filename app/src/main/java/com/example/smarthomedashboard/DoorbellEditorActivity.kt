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
    /** Запись с камеры (docs/recording.md): настройки камеры — в плитке, «записывать здесь» и место — этого планшета. */
    internal var recording = RecordingConfig()
        private set
    private lateinit var device: RecordingDevice
    internal var recordHere = false
        private set
    internal var limitGb = 0
        private set
    internal var reserveGb = 2
        private set
    internal var useSd = false
        private set
    internal var archivePin = RecordingDevice.ArchivePin.OFF
        private set
    private lateinit var recordingStatus: TextView
    private val statusListener: () -> Unit = { refreshRecordingStatus() }
    /** Прямые события с камеры — флаг и порт этого планшета (docs/direct-events-tz.md). */
    internal var directHere = false
    internal var directPort = CameraHookServer.DEFAULT_PORT
    private lateinit var directStatus: TextView
    private val directListener: () -> Unit = { refreshDirectStatus() }

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
        recording = RecordingConfig.from(tile.config)
        device = RecordingDevice(this)
        recordHere = device.enabled(tile.id)
        limitGb = device.limitGb
        reserveGb = device.reserveGb
        useSd = device.useSdCard
        archivePin = device.archivePin
        directHere = DirectEvents.enabled(this, tile.id)
        directPort = DirectEvents.port(this)
        savedInstanceState?.getString(STATE_DRAFT)?.let { text ->
            try {
                val json = JSONObject(text)
                config = DoorbellConfig.fromJson(json.getJSONObject("config"))
                title = json.optString("title", title)
                json.optJSONObject("recording")?.let { recording = RecordingConfig.from(JSONObject().put(RecordingConfig.KEY, it).toString()) }
                if (json.has("record_here")) recordHere = json.optBoolean("record_here")
                limitGb = json.optInt("limit_gb", limitGb); reserveGb = json.optInt("reserve_gb", reserveGb)
                useSd = json.optBoolean("use_sd", useSd)
                archivePin = RecordingDevice.ArchivePin.of(json.optString("archive_pin", archivePin.key))
                if (json.has("direct_here")) directHere = json.optBoolean("direct_here")
                directPort = json.optInt("direct_port", directPort)
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

        buildTalk(side)
        buildDirect(side)
        buildRecording(side)

        side.addView(kit.button("Тип, размер, удаление плитки…", "door_tile_settings") { openTileSettings() })
        body.addView(ScrollView(this).apply { addView(side) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(kit.footer("door", { leave() }, { save() }))
        setContentView(root)
    }

    // ---- Разговор через домофон ----

    private fun buildTalk(side: LinearLayout) {
        side.addView(header("Разговор через домофон"))
        side.addView(kit.label("В окне звонка и в раскрытом домофоне — кнопка «Говорить»: удерживайте и говорите, голос " +
            "идёт в динамик домофона прямо по домашней сети. Нужен прямой поток (RTSP) и включённый ONVIF в настройках " +
            "камеры. При первом нажатии планшет спросит разрешение на микрофон.", 13f))
        side.addView(switch("door_talk", "Разговор через домофон", config.talk) { update(config.copy(talk = it)) })
        val result = kit.label("", 13f).apply { tag = "door_talk_result" }
        side.addView(kit.button("Проверить звук в домофоне", "door_talk_check") {
            if (config.talkRtsp.isBlank()) { result.text = "Сначала укажите прямой поток (RTSP)"; return@button }
            result.text = "Отправляю «динь-дон» в домофон…"
            TalkCheck.chime(this, config.talkRtsp, config.rtspUser) { if (!isDestroyed) result.text = it }
        })
        side.addView(result)
        buildPhrases(side)
    }

    // ---- Быстрые фразы своим голосом ----

    private lateinit var phraseBox: LinearLayout
    private var recorder: MicCapture? = null
    private var recorded: ShortArray? = null
    private var recordedCount = 0
    private var player: android.media.AudioTrack? = null

    private fun buildPhrases(side: LinearLayout) {
        side.addView(header("Быстрые фразы своим голосом"))
        side.addView(kit.label("Запишите короткие фразы («Оставьте у калитки», «Сейчас выйду»). В раскрытом домофоне и в окне " +
            "звонка («Ответить») фраза звучит в динамике домофона одним касанием. Фразы хранятся только на этом планшете " +
            "и в резервную копию не попадают. До ${DoorbellPhrases.MAX} фраз по ${DoorbellPhrases.MAX_SECONDS} секунд.", 13f))
        phraseBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "door_phrases_editor" }
        side.addView(phraseBox)
        side.addView(kit.button("+ Добавить фразу", "door_phrase_add") { addPhrase() })
        renderPhrases()
    }

    private fun renderPhrases() {
        phraseBox.removeAllViews()
        DoorbellPhrases.list(this, original.id).forEach { phrase ->
            val has = DoorbellPhrases.has(this, original.id, phrase.id)
            phraseBox.addView(kit.label(phrase.title + if (has) "" else " — голос не записан", 15f, bold = true))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(recordButton(phrase), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            row.addView(kit.button("▶ Прослушать", "door_phrase_play_${phrase.id}") { listen(phrase) }.apply { isEnabled = has },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(kit.button("✕", "door_phrase_delete_${phrase.id}") { deletePhrase(phrase) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            phraseBox.addView(row)
        }
    }

    private fun addPhrase() {
        val list = DoorbellPhrases.list(this, original.id)
        if (list.size >= DoorbellPhrases.MAX) { Toast.makeText(this, "Не больше ${DoorbellPhrases.MAX} фраз", Toast.LENGTH_SHORT).show(); return }
        val input = android.widget.EditText(this).apply { hint = "Например: Оставьте у калитки"; setSingleLine(); tag = "door_phrase_title" }
        AlertDialog.Builder(this).setTitle("Новая фраза").setView(input)
            .setPositiveButton("Добавить") { _, _ ->
                val title = input.text.toString().trim().take(40).ifBlank { "Фраза ${list.size + 1}" }
                DoorbellPhrases.setList(this, original.id, list + DoorbellPhrases.Phrase(DoorbellPhrases.newId(), title))
                renderPhrases()
                Toast.makeText(this, "Удерживайте «🎙 Записать» и говорите", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun deletePhrase(phrase: DoorbellPhrases.Phrase) {
        AlertDialog.Builder(this).setTitle("Удалить фразу «${phrase.title}»?")
            .setPositiveButton("Удалить") { _, _ ->
                DoorbellPhrases.delete(this, original.id, phrase.id)
                DoorbellPhrases.setList(this, original.id, DoorbellPhrases.list(this, original.id).filter { it.id != phrase.id })
                renderPhrases()
            }
            .setNegativeButton("Отмена", null).show()
    }

    /** «🎙 Записать»: удержание — запись с микрофона планшета, отпустили — сохранить (тишина по краям срезается). */
    @android.annotation.SuppressLint("ClickableViewAccessibility") // удержание, а не нажатие; подпись — contentDescription
    private fun recordButton(phrase: DoorbellPhrases.Phrase): View = kit.button("🎙 Записать (удерживайте)", "door_phrase_record_${phrase.id}") {}.apply {
        contentDescription = "Записать фразу: удерживайте и говорите"
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    startRecording(v as TextView)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> stopRecording(phrase, v as TextView)
            }
            true
        }
    }

    private fun startRecording(button: TextView) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), DoorbellTalk.REQUEST_MIC)
            Toast.makeText(this, "Разрешите микрофон и нажмите ещё раз", Toast.LENGTH_SHORT).show()
            return
        }
        val buffer = ShortArray(8_000 * DoorbellPhrases.MAX_SECONDS)
        recorded = buffer
        recordedCount = 0
        val mic = MicCapture({ pcm, n ->
            synchronized(buffer) {
                val take = minOf(n, buffer.size - recordedCount)
                if (take > 0) { System.arraycopy(pcm, 0, buffer, recordedCount, take); recordedCount += take }
            }
        }, {})
        if (!mic.start(this)) { Toast.makeText(this, "Микрофон недоступен", Toast.LENGTH_SHORT).show(); return }
        recorder = mic
        button.text = "● Говорите…"
    }

    private fun stopRecording(phrase: DoorbellPhrases.Phrase, button: TextView) {
        val mic = recorder ?: return
        recorder = null
        mic.stop()
        button.text = "🎙 Записать (удерживайте)"
        val buffer = recorded ?: return
        val pcm = synchronized(buffer) { DoorbellPhrases.trimAndNormalize(buffer, recordedCount) }
        recorded = null
        if (pcm.size < 8_000 / 4) { Toast.makeText(this, "Голоса не слышно — удерживайте кнопку, пока говорите", Toast.LENGTH_SHORT).show(); return }
        val ok = DoorbellPhrases.save(this, original.id, phrase.id, pcm, pcm.size)
        Toast.makeText(this, if (ok) "Записано: ${"%.1f".format(pcm.size / 8_000f)} с" else "Не удалось сохранить", Toast.LENGTH_SHORT).show()
        renderPhrases()
    }

    /** Прослушать на планшете (в домофон не уходит). */
    private fun listen(phrase: DoorbellPhrases.Phrase) {
        val pcm = DoorbellPhrases.load(this, original.id, phrase.id) ?: return
        player?.release()
        player = try {
            @Suppress("DEPRECATION")
            android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, 8_000, android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, pcm.size * 2, android.media.AudioTrack.MODE_STATIC).apply {
                write(pcm, 0, pcm.size)
                play()
            }
        } catch (_: Exception) { null }
    }

    // ---- Прямые события с камеры ----

    private fun buildDirect(side: LinearLayout) {
        side.addView(header("Прямые события с камеры"))
        side.addView(kit.label("Звонок приходит прямо с камеры по домашней сети — без Home Assistant и интернета, быстрее. " +
            "Приложение само пропишет в камере вебхук на этот планшет (нужны адрес RTSP, логин и пароль камеры). " +
            "Звонок через Home Assistant тоже остаётся: звонит тот, кто сообщил первым. Сейчас — камеры Reolink. " +
            "Совет: закрепите за планшетом постоянный адрес в настройках роутера.", 13f))
        side.addView(switch("direct_here", "Получать звонок напрямую с камеры", directHere) { directHere = it })
        side.addView(number("direct_port", "Порт приёмника на планшете", directPort, 1024..65535) { directPort = it })
        directStatus = kit.label("", 13f).apply { tag = "direct_status" }
        side.addView(directStatus)
        side.addView(kit.button("Проверить сейчас", "direct_check") {
            if (DirectEvents.enabled(this, original.id)) DirectEvents.check(original.id)
            else Toast.makeText(this, "Сначала включите и сохраните", Toast.LENGTH_SHORT).show()
        })
        DirectEvents.addListener(directListener)
        refreshDirectStatus()
    }

    private fun refreshDirectStatus() {
        if (!::directStatus.isInitialized) return
        val status = DirectEvents.status(this, original.id)
        directStatus.text = status.text
        directStatus.setTextColor(when (status.level) {
            DirectEvents.Level.OK -> Color.rgb(110, 220, 140)
            DirectEvents.Level.WARN -> Color.rgb(255, 200, 90)
            DirectEvents.Level.ERROR -> Color.rgb(255, 120, 110)
            else -> Color.rgb(170, 180, 190)
        })
    }

    // ---- Запись с камеры ----

    private fun buildRecording(side: LinearLayout) {
        side.addView(header("Запись с домофона"))
        side.addView(kit.label("Запись идёт прямо на планшет: круглосуточно — субпоток, события — основной поток в HD. " +
            "Нужен прямой поток (RTSP). События берутся из датчиков этой плитки в Home Assistant (звонок, человек, машина…), " +
            "а при включённых «Прямых событиях с камеры» — ещё и сразу с камеры, в том числе без Home Assistant.", 13f))
        side.addView(switch("rec_here", "Записывать на этом планшете", recordHere) { recordHere = it })
        side.addView(choice("rec_mode", "Что записывать", RecordingConfig.Mode.entries.map { it.title },
            RecordingConfig.Mode.entries.indexOf(recording.mode)) { recording = recording.copy(mode = RecordingConfig.Mode.entries[it]) })
        side.addView(choice("rec_remote_mode", "Если камера видна только издалека (VPN, интернет)", RecordingConfig.Mode.entries.map { it.title },
            RecordingConfig.Mode.entries.indexOf(recording.remoteMode)) { recording = recording.copy(remoteMode = RecordingConfig.Mode.entries[it]) })
        localityLabel = kit.label("Камера не в сети планшета — запись тратит трафик: круглосуточно ~3–6 ГБ в сутки на камеру.", 13f)
            .apply { tag = "rec_locality" }
        side.addView(localityLabel)
        side.addView(switch("rec_audio_continuous", "Звук в круглосуточной записи", recording.audioContinuous) {
            recording = recording.copy(audioContinuous = it) })
        side.addView(switch("rec_audio_events", "Звук в записи событий", recording.audioEvents) { recording = recording.copy(audioEvents = it) })
        side.addView(kit.label("События, которые пишутся в HD", 15f, bold = true))
        for ((key, title) in RecordingConfig.TRIGGERS) {
            side.addView(switch("rec_trigger_$key", title, key in recording.eventTriggers) { on ->
                recording = recording.copy(eventTriggers = if (on) recording.eventTriggers + key else recording.eventTriggers - key) })
        }
        side.addView(number("rec_pre", "Запись до события, секунд (0–30)", recording.preSeconds, 0..30) {
            recording = recording.copy(preSeconds = it) })
        side.addView(number("rec_post", "Дописывать после события, секунд (5–120)", recording.postSeconds, 5..120) {
            recording = recording.copy(postSeconds = it) })
        side.addView(number("rec_max_clip", "Ролик не длиннее, минут (1–30)", recording.maxClipMinutes, 1..30) {
            recording = recording.copy(maxClipMinutes = it) })
        side.addView(number("rec_keep_continuous", "Хранить круглосуточную запись, дней (1–90)", recording.keepContinuousDays, 1..90) {
            recording = recording.copy(keepContinuousDays = it) })
        side.addView(number("rec_keep_events", "Хранить события, дней (1–365)", recording.keepEventDays, 1..365) {
            recording = recording.copy(keepEventDays = it) })
        side.addView(switch("rec_voice", "Сказать голосом, если запись остановилась", recording.voiceProblems) {
            recording = recording.copy(voiceProblems = it) })
        side.addView(header("Место для записей — этот планшет"))
        side.addView(number("rec_limit", "Лимит записей, ГБ (0 — авто: до 20 ГБ, не больше половины места)", limitGb, 0..4096) {
            limitGb = it; refreshRecordingStatus() })
        side.addView(number("rec_reserve", "Всегда оставлять свободными, ГБ (1–10)", reserveGb, 1..10) { reserveGb = it })
        val hasSd = try { getExternalFilesDirs(null).size > 1 && getExternalFilesDirs(null)[1] != null } catch (_: Exception) { false }
        side.addView(switch("rec_sd", if (hasSd) "Писать на SD-карту" else "Писать на SD-карту (карта не найдена)", useSd) { useSd = it })
        side.addView(choice("rec_archive_pin", "PIN для архива записей (тот же, что у настроек)",
            RecordingDevice.ArchivePin.entries.map { it.title }, RecordingDevice.ArchivePin.entries.indexOf(archivePin)) {
            archivePin = RecordingDevice.ArchivePin.entries[it] })
        recordingStatus = kit.label("", 13f).apply { tag = "rec_status" }
        side.addView(recordingStatus)
        side.addView(kit.button("Открыть архив записей", "rec_open_archive") { ArchiveActivity.open(this, original.id) })
        refreshRecordingStatus()
        RecordingStatus.listen(statusListener)
        // Занятое место, средний объём записи и где камера — на фоновом потоке (обход папок, разрешение имени).
        val root = getExternalFilesDir(null)
        val camera = RecLayout.cameraKey(original.id)
        val url = config.rtspSub.ifBlank { config.rtspMain }
        Thread {
            val files = root?.let { r -> RecLayout.scan(r) }.orEmpty()
            val used = files.sumOf { it.bytes }
            val free = root?.let { try { android.os.StatFs(it.path).availableBytes } catch (_: Exception) { 0L } } ?: 0L
            val perDay = RecRetention.bytesPerDay(files, camera, System.currentTimeMillis())
            val local = if (url.isBlank()) null else RecLocality.isLocal(url)
            runOnUiThread {
                usedBytes = used; freeBytes = free; bytesPerDay = perDay; cameraLocal = local
                refreshRecordingStatus()
            }
        }.apply { isDaemon = true }.start()
    }

    private lateinit var localityLabel: TextView
    private var usedBytes = -1L
    private var freeBytes = 0L
    private var bytesPerDay: Long? = null
    private var cameraLocal: Boolean? = null

    /** «≈ N дней» круглосуточной записи в текущий лимит при среднем объёме последних суток. */
    internal fun estimateText(): String {
        if (usedBytes < 0) return ""
        val limit = RecRetention.limit(limitGb, freeBytes, usedBytes)
        val head = "Занято записями: %.1f ГБ · свободно: %.1f ГБ · лимит: %.1f ГБ".format(usedBytes / 1e9, freeBytes / 1e9, limit / 1e9)
        val perDay = bytesPerDay ?: return "$head\nОценка дней появится после получаса записи."
        val days = limit.toDouble() / perDay
        return "$head\nПри текущем объёме (~%.1f ГБ в сутки) круглосуточной записи хватит примерно на %s.".format(perDay / 1e9, daysText(days))
    }

    private fun daysText(days: Double): String = when {
        days < 1 -> "%.0f ч".format(days * 24)
        else -> {
            val n = kotlin.math.floor(days).toInt()
            val word = when { n % 100 in 11..14 -> "дней"; n % 10 == 1 -> "день"; n % 10 in 2..4 -> "дня"; else -> "дней" }
            "$n $word"
        }
    }

    private fun refreshRecordingStatus() {
        if (!::recordingStatus.isInitialized) return
        val status = RecordingStatus.of(original.id)
        val state = when (status?.state) {
            CameraRecorder.State.RECORDING -> "Идёт запись"
            CameraRecorder.State.CONNECTING -> "Подключение к камере…"
            CameraRecorder.State.RETRYING -> "Нет связи с камерой: ${status.detail}. Повтор…"
            CameraRecorder.State.NO_SPACE, CameraRecorder.State.NO_STORAGE -> status.detail
            CameraRecorder.State.STOPPED, null -> if (RecordingStatus.of(original.id + RecorderService.HD) != null) ""
                else if (device.enabled(original.id)) "Запись не идёт" else "Запись на этом планшете выключена"
        }
        val hd = RecordingStatus.of(original.id + RecorderService.HD)?.let { s ->
            when (s.state) {
                CameraRecorder.State.RECORDING -> "События в HD: ${s.detail}"
                CameraRecorder.State.CONNECTING -> "События в HD: подключение к основному потоку…"
                CameraRecorder.State.RETRYING -> "События в HD: нет связи с камерой (${s.detail}). Повтор…"
                CameraRecorder.State.NO_SPACE, CameraRecorder.State.NO_STORAGE -> "События в HD: ${s.detail}"
                CameraRecorder.State.STOPPED -> ""
            }
        }.orEmpty()
        val sd = if (RecordingStatus.sdMissing(original.id)) "SD-карта не найдена — запись идёт в память планшета" else ""
        recordingStatus.text = listOf(state, hd, sd, estimateText()).filter { it.isNotBlank() }.joinToString("\n")
        if (::localityLabel.isInitialized) {
            val where = when (cameraLocal) {
                true -> "Сейчас камера в сети этого планшета — действует «Что записывать»."
                false -> "Сейчас камера видна только издалека — действует режим для удалённой камеры."
                null -> ""
            }
            localityLabel.text = listOf(where, "Издалека запись тратит трафик: круглосуточно ~3–6 ГБ в сутки на камеру.")
                .filter { it.isNotBlank() }.joinToString("\n")
        }
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

    internal val isDirty get() = title != original.title || config != DoorbellConfig.from(original.config) || password != null ||
        recording != RecordingConfig.from(original.config) || recordingDeviceDirty ||
        directHere != DirectEvents.enabled(this, original.id) || directPort != DirectEvents.port(this)

    private val recordingDeviceDirty get() = ::device.isInitialized && (recordHere != device.enabled(original.id) ||
        limitGb != device.limitGb || reserveGb != device.reserveGb || useSd != device.useSdCard || archivePin != device.archivePin)

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
        RecordingStatus.unlisten(statusListener)
        DirectEvents.removeListener(directListener)
        kit.dispose()
        if (::preview.isInitialized) preview.dispose()
        recorder?.stop()
        recorder = null
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::original.isInitialized) outState.putString(STATE_DRAFT, JSONObject().put("config", config.toJson()).put("title", title)
            .put("recording", recording.toJson()).put("record_here", recordHere).put("limit_gb", limitGb)
            .put("reserve_gb", reserveGb).put("use_sd", useSd).put("archive_pin", archivePin.key)
            .put("direct_here", directHere).put("direct_port", directPort).toString())
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
        val json = JSONObject(recording.writeTo(config.writeTo(latest.config))).put("button_size", w).put("widget_height", h)
        tileManager.updateTile(latest.copy(title = title.ifBlank { DoorbellConfig.DEFAULT_TITLE }, config = json.toString()))
        device.setEnabled(original.id, recordHere)
        device.limitGb = limitGb; device.reserveGb = reserveGb; device.useSdCard = useSd; device.archivePin = archivePin
        val secret = password
        val saved = config
        // Прямые события: порт и флаг — после пароля (включение входит в камеру с ним). Выключение убирает вебхук.
        val applyDirect = {
            DirectEvents.setPort(this, directPort)
            DirectEvents.setEnabled(this, original.id, saved, directHere)
        }
        if (secret == null) { RecorderService.sync(this); applyDirect(); finishSaved(); return true }
        DoorbellHub.savePassword(this, secret) { ok ->
            if (!ok) Toast.makeText(this, "Настройки сохранены, но пароль камеры записать не удалось", Toast.LENGTH_LONG).show()
            // Новый пароль — запись переподключится с ним.
            RecorderService.sync(this)
            applyDirect()
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
