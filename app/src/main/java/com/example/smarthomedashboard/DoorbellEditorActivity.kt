package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
        if (tile == null || !DoorbellConfig.isVideo(tile.type)) {
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
        CameraPasswords.read(this, secretKey) { savedPassword = !it.isNullOrEmpty(); refreshPassword() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 14, 20))
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
        }
        root.addView(kit.label(if (camera) "Камера — настройка" else "Домофон — настройка", 22f, bold = true))
        if (camera) root.addView(kit.label("Введите адрес камеры, логин и пароль и нажмите «Найти потоки» — ссылки на видео " +
            "приложение найдёт само (ONVIF). Изменения применяются кнопкой «Сохранить».", 13f))
        else root.addView(kit.hintBar(UserHints.Hint.DOORBELL_EDITOR,
            "Reolink: основной поток rtsp://IP:554/h264Preview_01_main, субпоток — …_sub. Прямой поток работает в " +
                "домашней сети; вне дома видео идёт через Home Assistant (камера HA). Звук и тихие часы — только этого " +
                "планшета. Изменения применяются кнопкой «Сохранить»."))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        preview = DoorbellCard(this, expanded = false, camera = camera).apply { tileId = original.id }
        val previewBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val frame = FrameLayout(this).apply { tag = "door_editor_preview" }
        frame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewBox.addView(frame, LinearLayout.LayoutParams(kit.dp(420), (kit.dp(420) / config.aspect).toInt()).apply {
            topMargin = kit.dp(12)
        })
        previewBox.addView(kit.label("Предпросмотр — данные панели и видео, как на плитке.", 13f))
        if (!camera) previewBox.addView(kit.button("Проверить звук звонка", "door_test_ring") { testRing() })
        body.addView(previewBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(kit.dp(16), 0, 0, 0) }
        side.addView(header("Название"))
        side.addView(kit.input(title, DoorbellConfig.DEFAULT_TITLE).apply {
            tag = "door_title_input"
            addTextChangedListener(kit.watcher { title = it.trim(); render() })
        })

        side.addView(header("Видео"))
        if (camera) {
            buildOnvif(side)
            side.addView(entityRow("light", "Прожектор (Home Assistant)", "у Reolink не нужно — находится сам") { config.light })
        }
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
        buildDiagnostics(side)

        side.addView(header(if (camera) "Датчики событий (Home Assistant, по желанию)" else "Датчики домофона"))
        if (!camera) side.addView(entityRow("visitor", "Кнопка звонка", "binary_sensor … visitor") { config.visitor })
        side.addView(entityRow("person", "Человек", "значок и янтарная рамка") { config.person })
        side.addView(entityRow("animal", "Животное", "значок") { config.animal })
        side.addView(entityRow("vehicle", "Машина", "значок") { config.vehicle })
        side.addView(entityRow("motion", "Движение", "значок") { config.motion })

        if (!camera) buildDoorbellOnly(side)
        buildGates(side)
        side.addView(header("Раскрытие по касанию"))
        side.addView(switch("door_auto_collapse", "Сворачивать автоматически", config.autoCollapse) { update(config.copy(autoCollapse = it)) })
        side.addView(number("door_collapse_seconds", "Через, секунд", config.collapseSeconds, 5..3600) { update(config.copy(collapseSeconds = it)) })

        if (!camera) {
            buildTalk(side)
            buildDirect(side)
        } else {
            buildTalk(side)
            buildCameraEvents(side)
            buildDirect(side)
            buildGuard(side)
        }
        buildRecording(side)

        side.addView(kit.button("Тип, размер, удаление плитки…", "door_tile_settings") { openTileSettings() })
        val scroll = ScrollView(this).apply { addView(side); tag = "door_side_scroll" }
        body.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f))
        // Из «Камеры: есть советы» (боковая панель, Настройки) — сразу к разделу «Диагностика видео».
        if (intent.getBooleanExtra(EXTRA_DIAGNOSTICS, false)) scroll.post { scroll.scrollTo(0, diagHeader.top) }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(kit.footer("door", { leave() }, { save() }))
        setContentView(root)
    }

    /** Только у домофона: калитка, свет, готовые ответы, звук звонка. */
    private fun buildDoorbellOnly(side: LinearLayout) {
        side.addView(header("Свет, ответы"))
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
    }

    // ---- Диагностика видео (docs/diagnostics-tz.md, часть 1) ----

    private lateinit var diagHeader: View
    private lateinit var diagSummary: TextView
    private lateinit var diagList: LinearLayout
    private lateinit var diagRun: Button
    private var diagResult: CameraDiagnosis.Result? = null
    private var diagRunning = false
    @Volatile private var diagCancelled = false

    private fun buildDiagnostics(side: LinearLayout) {
        diagHeader = header("Диагностика видео")
        side.addView(diagHeader)
        side.addView(kit.label("Что мешает видео — камера, сеть или планшет — и что с этим сделать. «Проверить сейчас» " +
            "смотрит оба потока $diagSeconds с и читает настройки камеры по ONVIF; камере ничего не отправляется.", 13f))
        diagSummary = kit.label("", 15f, bold = true).apply { tag = "door_diag_summary" }
        side.addView(diagSummary)
        diagRun = kit.button("Проверить сейчас", "door_diag_run") { runDiagnostics() }
        side.addView(diagRun)
        diagList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "door_diag_list" }
        side.addView(diagList)
        side.addView(kit.button("Скопировать отчёт", "door_diag_copy") { copyDiagnostics() })
        showDiagnostics(CameraDiagnosis.passive(this, CameraDiagnosis.target(config, null)), checked = false)
    }

    /** [checked] — итог «Проверить сейчас»; иначе — что видно без проверки (поток записи, показы видео). */
    private fun showDiagnostics(result: CameraDiagnosis.Result, checked: Boolean) {
        diagResult = result
        val summary = CameraDiagnostics.summary(result.checks)
        diagSummary.text = when {
            checked -> summary ?: "Проверить не удалось: камера не ответила"
            summary != null -> "Пока видно: $summary"
            else -> "Нажмите «Проверить сейчас»"
        }
        diagList.removeAllViews()
        val order = listOf(CameraDiagnostics.Level.BAD, CameraDiagnostics.Level.IMPROVE, CameraDiagnostics.Level.OK, CameraDiagnostics.Level.UNKNOWN)
        for (c in result.checks.sortedBy { order.indexOf(it.level) }) {
            if (!checked && c.level == CameraDiagnostics.Level.UNKNOWN) continue
            diagList.addView(kit.label("${c.level.mark} ${c.title}: ${c.detail}", 14f).apply {
                tag = "door_diag_${c.id}"
                setTextColor(when (c.level) {
                    CameraDiagnostics.Level.BAD -> Color.rgb(255, 120, 110)
                    CameraDiagnostics.Level.IMPROVE -> Color.rgb(255, 196, 90)
                    CameraDiagnostics.Level.OK -> Color.rgb(140, 214, 160)
                    CameraDiagnostics.Level.UNKNOWN -> Color.rgb(140, 150, 160)
                })
            })
            if (c.advice.isNotBlank() && (c.level == CameraDiagnostics.Level.BAD || c.level == CameraDiagnostics.Level.IMPROVE)) {
                diagList.addView(kit.label("Что сделать: ${c.advice}", 13f).apply { setPadding(kit.dp(18), 0, 0, kit.dp(6)) })
            }
        }
    }

    private fun runDiagnostics() {
        if (diagRunning) return
        diagRunning = true
        diagRun.isEnabled = false
        diagRun.text = "Проверяю… $diagSeconds с"
        val context = applicationContext
        val start = { pass: String? ->
            val target = CameraDiagnosis.target(config, pass, original.id)
            Thread({
                val result = try {
                    CameraDiagnosis.full(context, target, diagSeconds, { diagCancelled }) { left ->
                        runOnUiThread { if (!isDestroyed && diagRunning) diagRun.text = "Проверяю… $left с" }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("DoorbellEditor", "Диагностика: ${e.javaClass.simpleName}")
                    null
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    diagRunning = false
                    diagRun.isEnabled = true
                    diagRun.text = "Проверить сейчас"
                    if (result != null) showDiagnostics(result, checked = true) else diagSummary.text = "Проверка не удалась"
                }
            }, "diag-run").apply { isDaemon = true; start() }
        }
        val typed = password
        if (!typed.isNullOrEmpty()) start(typed) else CameraPasswords.read(this, secretKey) { saved -> start(saved) }
    }

    private fun copyDiagnostics() {
        val result = diagResult ?: CameraDiagnosis.passive(this, CameraDiagnosis.target(config, null))
        val text = CameraDiagnosis.report(this, result)
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Диагностика камеры", text))
        Toast.makeText(this, "Отчёт скопирован — без адресов и паролей", Toast.LENGTH_SHORT).show()
    }

    // ---- Камера: события по ONVIF (docs/camera-tz.md, часть 2) ----

    private fun buildCameraEvents(side: LinearLayout) {
        side.addView(header("События с камеры (ONVIF)"))
        side.addView(kit.label("Человек, машина, животное, движение — прямо с камеры по ONVIF, без Home Assistant: значки на плитке, " +
            "запись событий в HD, охрана. Работает, пока открыта панель этого планшета.", 13f))
        side.addView(switch("door_onvif_events", "Получать события камеры на этом планшете", OnvifEvents.enabled(this, original.id)) {
            OnvifEvents.setEnabled(this, original.id, it)
        })
        side.addView(kit.label(OnvifEvents.status(original.id)?.text ?: "Состояние появится после «Сохранить» и открытия панели", 13f)
            .apply { tag = "door_onvif_status" })
    }

    // ---- Камера: адрес, ONVIF (docs/camera-tz.md) ----

    /** Плитка «Камера» (пароль — свой у плитки) или домофон (прежний слот пароля). */
    private val camera get() = original.type == DoorbellConfig.CAMERA_TYPE
    private val secretKey get() = if (camera) original.id else ""

    private fun buildOnvif(side: LinearLayout) {
        side.addView(text("host", "Адрес камеры (IP в домашней сети)", config.host, "192.168.1.60") { update(config.copy(host = it)) })
        side.addView(number("door_onvif_port", "Порт ONVIF (0 — найти самому)", config.onvifPort, 0..65535) { update(config.copy(onvifPort = it)) })
        val result = kit.label(if (config.model.isNotBlank()) "Камера: ${config.model}" else "", 13f).apply { tag = "door_onvif_result" }
        side.addView(kit.button("Найти потоки (ONVIF)", "door_onvif_find") { findStreams(result) })
        side.addView(result)
        side.addView(kit.label("Логин и пароль — ниже, в полях «Логин камеры» и «Пароль камеры». Ссылки на потоки после " +
            "поиска можно поправить вручную.", 13f))
    }

    /** ONVIF: модель и потоки камеры по адресу, логину и паролю (новому из поля или сохранённому). */
    private fun findStreams(result: TextView) {
        val host = config.host.trim()
        if (host.isBlank()) { result.text = "Сначала введите адрес камеры"; return }
        result.text = "Ищу камеру…"
        val user = config.rtspUser.ifBlank { "admin" }
        val typed = password
        val start = { pass: String ->
            Thread({
                val text = try {
                    val info = Onvif.probe(host, config.onvifPort, user, pass)
                    runOnUiThread { applyOnvif(info) }
                    "Найдено: ${info.title}" + (info.main?.let { " · основной ${it.width}×${it.height}" } ?: "") +
                        (info.sub?.takeIf { it != info.main }?.let { " · субпоток ${it.width}×${it.height}" } ?: "")
                } catch (e: Onvif.OnvifException) { e.message.orEmpty() } catch (e: Exception) {
                    "Не удалось: ${e.javaClass.simpleName}"
                }
                runOnUiThread { if (!isDestroyed) result.text = text }
            }, "onvif-probe").apply { isDaemon = true; start() }
        }
        if (!typed.isNullOrEmpty()) start(typed)
        else CameraPasswords.read(this, secretKey) { saved -> start(saved.orEmpty()) }
    }

    private fun applyOnvif(info: Onvif.Info) {
        if (isDestroyed) return
        val main = info.main?.uri.orEmpty()
        val sub = info.sub?.uri.orEmpty()
        update(config.copy(rtspMain = main, rtspSub = sub, onvifPort = info.port, model = info.title,
            rtspUser = config.rtspUser.ifBlank { "admin" }))
        (window.decorView.findViewWithTag<android.widget.EditText>("door_rtsp_main"))?.setText(main)
        (window.decorView.findViewWithTag<android.widget.EditText>("door_rtsp_sub"))?.setText(sub)
        (window.decorView.findViewWithTag<android.widget.EditText>("door_onvif_port"))?.setText(info.port.toString())
    }

    // ---- Разговор через домофон ----

    private fun buildTalk(side: LinearLayout) {
        side.addView(header(if (camera) "Разговор через динамик камеры" else "Разговор через домофон"))
        side.addView(kit.label((if (camera) "Если у камеры есть динамик: в раскрытой плитке" else "В окне звонка и в раскрытом домофоне") +
            " — кнопка разговора. Нужен прямой поток (RTSP) и включённый ONVIF в настройках камеры. При первом " +
            "нажатии планшет спросит разрешение на микрофон.", 13f))
        side.addView(switch("door_talk", if (camera) "Разговор через камеру" else "Разговор через домофон", config.talk) { update(config.copy(talk = it)) })
        val modeHint = kit.label(talkModeHint(config.talkCall), 13f)
        side.addView(choice("door_talk_mode", "Режим разговора", listOf("Дуплекс (как телефон)", "Симплекс (как рация)"),
            if (config.talkCall) 0 else 1) { index ->
            update(config.copy(talkCall = index == 0))
            modeHint.text = talkModeHint(index == 0)
        })
        side.addView(modeHint)
        val result = kit.label("", 13f).apply { tag = "door_talk_result" }
        side.addView(kit.button("Проверить звук в домофоне", "door_talk_check") {
            if (config.talkRtsp.isBlank()) { result.text = "Сначала укажите прямой поток (RTSP)"; return@button }
            result.text = "Отправляю «динь-дон» в домофон…"
            TalkCheck.chime(this, config.talkRtsp, config.rtspUser, secretKey) { if (!isDestroyed) result.text = it }
        })
        side.addView(result)
        if (!camera) buildPhrases(side)
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
        buildAway(side)
    }

    // ---- Когда никто не подошёл: уведомление на телефон и автоответ (docs/ring-away-tz.md) ----

    private lateinit var awayBox: LinearLayout

    private fun buildAway(side: LinearLayout) {
        side.addView(header("Когда никто не подошёл"))
        side.addView(kit.label("Настройки только этого планшета — включайте на одном, иначе уведомление и фраза придут " +
            "с каждого. Уведомление идёт через Home Assistant в приложение HA на телефоне; снимок в нём — с камеры в HA " +
            "(поле «Камера» выше), без неё — только текст. Автоответ: если к звонку не подошли, домофон сам скажет " +
            "выбранную фразу своим голосом; переключается и кнопкой «Автоответ» в раскрытом домофоне.", 13f))
        awayBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "door_away" }
        side.addView(awayBox)
        renderAway()
        buildGuard(side)
    }

    // ---- Охрана (docs/guard-tz.md) ----

    private fun buildGuard(side: LinearLayout) {
        side.addView(header("Охрана"))
        side.addView(kit.label("Человек стоит " + (if (camera) "в кадре" else "у входа") + " дольше заданного времени — уведомление " +
            "на телефон и ★ у ролика (не удалится и скопируется в папку копий). Прошёл мимо — тревоги нет. Включается по " +
            "расписанию и кнопкой «Охрана» в раскрытой плитке. Настройки только этого планшета.", 13f))
        // У камеры нет раздела «Когда никто не подошёл» — телефон выбирается здесь.
        if (camera) {
            val phone = kit.button("Уведомление на телефон: " + DoorbellAway.settings(this, original.id).notifyService.ifEmpty { "не отправлять" },
                "door_guard_phone") {}
            phone.setOnClickListener { pickPhone { phone.text = "Уведомление на телефон: " +
                DoorbellAway.settings(this, original.id).notifyService.ifEmpty { "не отправлять" } } }
            side.addView(phone)
        }
        val g = DoorbellGuard.settings(this, original.id)
        fun update(change: (DoorbellGuard.Settings) -> DoorbellGuard.Settings) =
            DoorbellGuard.save(this, original.id, change(DoorbellGuard.settings(this, original.id)))
        side.addView(switch("door_guard_switch", "Охрана на этом планшете", g.enabled) { on -> update { it.copy(enabled = on) } })
        side.addView(switch("door_guard_schedule", "Включать по расписанию", g.schedule) { on -> update { it.copy(schedule = on) } })
        side.addView(timeField("door_guard_start", "Начало (часы:минуты)", g.startMin) { m -> update { it.copy(startMin = m, offUntil = 0L) } })
        side.addView(timeField("door_guard_end", "Конец (часы:минуты; раньше начала — через полночь)", g.endMin) { m -> update { it.copy(endMin = m, offUntil = 0L) } })
        side.addView(number("door_guard_seconds", "Сколько секунд человек стоит у входа (${DoorbellGuard.SECONDS_RANGE.first}–" +
            "${DoorbellGuard.SECONDS_RANGE.last})", g.seconds, DoorbellGuard.SECONDS_RANGE) { v -> update { it.copy(seconds = v) } })
        side.addView(switch("door_guard_star", "Отмечать ролик ★", g.star) { on -> update { it.copy(star = on) } })
    }

    /** Поле времени «ЧЧ:ММ»: сохраняется, только когда введено верное время. */
    private fun timeField(tag: String, label: String, minute: Int, onChange: (Int) -> Unit) = kit.column(
        kit.label(label, 15f),
        kit.input(DoorbellGuard.time(minute), "00:00").apply {
            this.tag = tag
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            addTextChangedListener(kit.watcher { text -> DoorbellGuard.parseTime(text)?.let(onChange) })
        }
    ).apply { setPadding(0, 0, 0, 0) }

    private fun renderAway() {
        awayBox.removeAllViews()
        val s = DoorbellAway.settings(this, original.id)
        val phone = s.notifyService.ifEmpty { "не отправлять" }
        awayBox.addView(kit.button("Уведомление на телефон: $phone", "door_notify_pick") { pickPhone() })
        val result = kit.label("", 13f).apply { tag = "door_notify_result" }
        if (s.notifyService.isNotEmpty()) awayBox.addView(kit.button("Отправить проверочное уведомление", "door_notify_test") {
            result.text = "Отправляю…"
            DoorbellAway.sender(s.notifyService.substringBefore('.'), s.notifyService.substringAfter('.'),
                DoorbellAway.notification(title, config.camera, System.currentTimeMillis(), original.id) +
                    ("message" to "Проверка: так будет выглядеть уведомление о звонке")) { ok ->
                if (!isDestroyed) result.text = if (ok) "Home Assistant принял уведомление — проверьте телефон" else
                    "Не отправлено: нет связи с Home Assistant или служба не найдена"
            }
        })
        awayBox.addView(result)
        val phrases = DoorbellPhrases.ready(this, original.id)
        if (phrases.isEmpty()) {
            awayBox.addView(kit.label("Для автоответа запишите фразу выше.", 13f))
            return
        }
        awayBox.addView(switch("door_autoreply_switch", "Автоответ своей фразой", s.autoReply) {
            DoorbellAway.save(this, original.id, DoorbellAway.settings(this, original.id).copy(autoReply = it))
        })
        val chosen = DoorbellAway.phrase(this, original.id)
        awayBox.addView(kit.button("Фраза: ${chosen?.title ?: "выберите"}", "door_autoreply_phrase") {
            AlertDialog.Builder(this).setTitle("Фраза для автоответа").setItems(phrases.map { it.title }.toTypedArray()) { _, i ->
                DoorbellAway.save(this, original.id, DoorbellAway.settings(this, original.id).copy(phraseId = phrases[i].id))
                renderAway()
            }.setNegativeButton("Отмена", null).show()
        })
        awayBox.addView(number("door_autoreply_delay", "Через сколько секунд без ответа (${DoorbellAway.DELAY_RANGE.first}–" +
            "${DoorbellAway.DELAY_RANGE.last})", s.delaySeconds, DoorbellAway.DELAY_RANGE) {
            DoorbellAway.save(this, original.id, DoorbellAway.settings(this, original.id).copy(delaySeconds = it))
        })
    }

    /** Список телефонов — службы `notify.mobile_app_…` из Home Assistant. */
    private fun pickPhone(done: () -> Unit = {}) {
        val ws = DoorbellHub.connection?.invoke()
        if (ws == null) { Toast.makeText(this, "Нет связи с Home Assistant", Toast.LENGTH_SHORT).show(); return }
        ws.read("get_services", timeoutMs = 15_000L) { services ->
            if (isDestroyed) return@read
            val phones = DoorbellAway.phoneServices(services)
            if (services == null) { Toast.makeText(this, "Home Assistant не ответил", Toast.LENGTH_SHORT).show(); return@read }
            if (phones.isEmpty()) {
                Toast.makeText(this, "В Home Assistant нет телефонов: установите приложение Home Assistant и войдите", Toast.LENGTH_LONG).show()
                return@read
            }
            val items = listOf("Не отправлять") + phones
            AlertDialog.Builder(this).setTitle("Уведомление о звонке — на какой телефон").setItems(items.toTypedArray()) { _, i ->
                DoorbellAway.save(this, original.id, DoorbellAway.settings(this, original.id).copy(notifyService = if (i == 0) "" else items[i]))
                if (::awayBox.isInitialized) renderAway()
                done()
            }.setNegativeButton("Отмена", null).show()
        }
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
                renderAway()
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
        renderAway()
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
        side.addView(header(if (camera) "Вебхук Reolink (быстрее ONVIF)" else "Прямые события с камеры"))
        side.addView(kit.label(if (camera) "Только для камер Reolink: события приходят на планшет вебхуком сразу. Приложение само " +
            "пропишет вебхук в камере. События по ONVIF при этом тоже работают — срабатывает то, что пришло первым."
            else "Звонок приходит прямо с камеры по домашней сети — без Home Assistant и интернета, быстрее. " +
            "Приложение само пропишет в камере вебхук на этот планшет (нужны адрес RTSP, логин и пароль камеры). " +
            "Звонок через Home Assistant тоже остаётся: звонит тот, кто сообщил первым. Сейчас — камеры Reolink. " +
            "Совет: закрепите за планшетом постоянный адрес в настройках роутера.", 13f))
        side.addView(switch("direct_here", if (camera) "Получать события вебхуком" else "Получать звонок напрямую с камеры", directHere) { directHere = it })
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
        side.addView(header(if (camera) "Запись с камеры" else "Запись с домофона"))
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
            recording = recording.copy(keepContinuousDays = it); refreshRecordingStatus() })
        side.addView(number("rec_keep_events", "Хранить события, дней (1–365)", recording.keepEventDays, 1..365) {
            recording = recording.copy(keepEventDays = it) })
        side.addView(switch("rec_voice", "Сказать голосом, если запись остановилась", recording.voiceProblems) {
            recording = recording.copy(voiceProblems = it) })
        side.addView(header("Место для записей — этот планшет"))
        side.addView(number("rec_limit", "Лимит записей, ГБ (0 — авто: до 20 ГБ, не больше половины места)", limitGb, 0..4096) {
            limitGb = it; refreshRecordingStatus() })
        side.addView(number("rec_reserve", "Всегда оставлять свободными, ГБ (1–10)", reserveGb, 1..10) {
            reserveGb = it; refreshRecordingStatus() })
        val hasSd = RecVolumes.dirs(this).getOrNull(1) != null
        side.addView(switch("rec_sd", if (hasSd) "Писать на SD-карту" else "Писать на SD-карту (карта не найдена)", useSd) {
            useSd = it; rescanSpace() })
        side.addView(choice("rec_archive_pin", "PIN для архива записей (тот же, что у настроек)",
            RecordingDevice.ArchivePin.entries.map { it.title }, RecordingDevice.ArchivePin.entries.indexOf(archivePin)) {
            archivePin = RecordingDevice.ArchivePin.entries[it] })
        recordingStatus = kit.label("", 13f).apply { tag = "rec_status" }
        side.addView(recordingStatus)
        side.addView(kit.button("Открыть архив записей", "rec_open_archive") { ArchiveActivity.open(this, original.id) })
        refreshRecordingStatus()
        RecordingStatus.listen(statusListener)
        // Где камера — на фоновом потоке (разрешение имени); место под записи — тоже (обход папок).
        val url = config.rtspSub.ifBlank { config.rtspMain }
        Thread {
            val local = if (url.isBlank()) null else RecLocality.isLocal(url)
            runOnUiThread { cameraLocal = local; refreshRecordingStatus() }
        }.apply { isDaemon = true }.start()
        rescanSpace()
    }

    private lateinit var localityLabel: TextView
    private var cameraLocal: Boolean? = null
    /** Записи и свободное место носителя, куда пойдёт запись при выбранном «Писать на SD-карту». */
    private var space: RecVolumes.Snapshot? = null

    private fun rescanSpace() {
        val sd = useSd
        Thread {
            val snapshot = try { RecVolumes.snapshot(applicationContext, sd) } catch (_: Exception) { null }
            runOnUiThread { if (sd == useSd) { space = snapshot; refreshRecordingStatus() } }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Занятое место и оценка — та же, что в архиве ([RecRetention.outlookText]): по всем камерам и роликам событий
     * носителя, при лимите, остатке и сроке хранения из полей редактора (ещё не сохранённых).
     */
    internal fun estimateText(): String {
        val s = space ?: return ""
        val o = RecVolumes.outlook(s, limitGb, reserveGb)
        val head = "Занято записями: ${RecRetention.size(o.usedBytes)} · свободно: ${RecRetention.size(s.freeBytes)} · " +
            "лимит: ${RecRetention.size(o.limitBytes)}"
        return "$head\n${RecRetention.outlookText(o, recording.keepContinuousDays)}"
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

    // ---- Калитки и ворота (docs/gate-tz.md): какие проходы показывает эта плитка ----

    private lateinit var gatesBox: LinearLayout
    /** Проходы до перехода в «Управление замком»: созданные там новые сразу отмечаются у этой плитки. */
    private var gatesBefore: Set<String>? = null

    override fun onResume() {
        super.onResume()
        // Вернулись из «Управление замком»: список проходов мог измениться.
        renderGates()
    }

    private fun buildGates(side: LinearLayout) {
        side.addView(header("Калитки и ворота"))
        side.addView(kit.label("Кнопки отмеченных проходов — в раскрытой плитке (и без звонка)" +
            (if (camera) "." else " и в окне звонка.") + " Сами проходы настраиваются в «Управление замком».", 13f))
        gatesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "door_gates" }
        side.addView(gatesBox)
        side.addView(kit.button("Управление замком…", "door_gates_manage") {
            gatesBefore = GateHub.gates(this).map { it.id }.toSet()
            startActivity(Intent(this, GatesActivity::class.java))
        })
        renderGates()
    }

    private fun renderGates() {
        if (!::gatesBox.isInitialized) return
        val all = GateHub.gates(this)
        // Созданные только что в «Управление замком» — сразу у этой плитки (за тем туда и ходили).
        gatesBefore?.let { before ->
            val created = all.map { it.id }.filter { it !in before }
            if (created.isNotEmpty()) update(config.copy(gates = (config.gateIds(original.id) + created).distinct()))
            gatesBefore = null
        }
        gatesBox.removeAllViews()
        if (all.isEmpty()) {
            gatesBox.addView(kit.label("Проходов пока нет. «Управление замком…» — добавить калитку или ворота.", 13f))
            return
        }
        val shown = config.gateIds(original.id)
        all.forEach { gate ->
            gatesBox.addView(switch("door_gate_${gate.id}", "${gate.name} · ${gate.kind.title}" +
                if (gate.ready) "" else " (не настроен)", gate.id in shown) { on ->
                val current = config.gateIds(original.id)
                // Порядок кнопок — как в общем списке проходов.
                val next = all.map { it.id }.filter { if (it == gate.id) on else it in current }
                update(config.copy(gates = next))
            })
        }
    }

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

    /** Пояснение к режиму разговора под выбором. */
    private fun talkModeHint(duplex: Boolean) = if (duplex)
        "«Ответить» — говорите оба сразу, как по телефону; «Завершить» — конец (сам — через 2 минуты тишины). Эхо гасит планшет."
    else
        "Удерживайте кнопку и говорите — звук с улицы на это время приглушён; отпустили — слушаете улицу. Эха нет."

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
        if (next.rtspMain != config.rtspMain || next.rtspSub != config.rtspSub || next.rtspUser != config.rtspUser) {
            if (previewVideo == null) previewVideo = config
            settle.removeCallbacks(settlePreview)
            settle.postDelayed(settlePreview, PREVIEW_SETTLE_MS)
        }
        config = next
        render()
    }

    /**
     * Адреса видео, на которых сейчас стоит предпросмотр, пока новые ещё вводят. Иначе плеер запускался на каждой
     * букве: к недописанному адресу, к чужому узлу, к несуществующему пути — с ошибками в журнале и «Нет сигнала»
     * в предпросмотре (стенд камер 21.11.2026). Новые адреса берутся через [PREVIEW_SETTLE_MS] после последней правки.
     */
    private var previewVideo: DoorbellConfig? = null
    private val settle = android.os.Handler(android.os.Looper.getMainLooper())
    private val settlePreview = Runnable { previewVideo = null; render() }

    internal val isDirty get() = title != original.title || config != DoorbellConfig.from(original.config) || password != null ||
        recording != RecordingConfig.from(original.config) || recordingDeviceDirty ||
        directHere != DirectEvents.enabled(this, original.id) || directPort != DirectEvents.port(this)

    private val recordingDeviceDirty get() = ::device.isInitialized && (recordHere != device.enabled(original.id) ||
        limitGb != device.limitGb || reserveGb != device.reserveGb || useSd != device.useSdCard || archivePin != device.archivePin)

    internal fun render() {
        if (!::preview.isInitialized) return
        val readings = BatteryReadings.from(model)
        val snapshot = DoorbellModel.snapshot(config, readings)
        val shown = previewVideo?.let { config.copy(rtspMain = it.rtspMain, rtspSub = it.rtspSub, rtspUser = it.rtspUser) } ?: config
        preview.bind(title, shown, snapshot, offline = false, missed = DoorbellHub.missed(original.id),
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
        diagCancelled = true
        settle.removeCallbacks(settlePreview)
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
        tileManager.updateTile(latest.copy(title = title.ifBlank { if (camera) DoorbellConfig.CAMERA_TITLE else DoorbellConfig.DEFAULT_TITLE },
            config = json.toString()))
        device.setEnabled(original.id, recordHere)
        device.limitGb = limitGb; device.reserveGb = reserveGb; device.useSdCard = useSd; device.archivePin = archivePin
        val secret = password
        val saved = config
        // Прямые события: порт и флаг — после пароля (включение входит в камеру с ним). Выключение убирает вебхук.
        val applyDirect = {
            DirectEvents.setPort(this, directPort)
            DirectEvents.setEnabled(this, original.id, saved, directHere, secretKey)
        }
        if (secret == null) { RecorderService.sync(this); applyDirect(); finishSaved(); return true }
        CameraPasswords.save(this, secretKey, secret) { ok ->
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
        /** Пауза во вводе адреса потока, после которой предпросмотр подключается к новому адресу. */
        internal const val PREVIEW_SETTLE_MS = 900L
        /** Открыть сразу на разделе «Диагностика видео». */
        const val EXTRA_DIAGNOSTICS = "diagnostics"
        /** Сколько секунд «Проверить сейчас» смотрит потоки камеры (в тестах — меньше). */
        internal var diagSeconds = 30
    }
}
