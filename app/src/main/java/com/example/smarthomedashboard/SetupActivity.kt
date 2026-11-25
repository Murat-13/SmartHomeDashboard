package com.example.smarthomedashboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException

/**
 * Мастер первой настройки (docs/setup-tz.md): начало → Home Assistant → вход ([HaLoginActivity]) → камеры → комнаты →
 * защита → готово. Камеры — раньше комнат, чтобы раскладка сразу учла их. Проверка планшета — шаг следующего этапа. Каждый
 * шаг можно пропустить. На уже настроенной панели не открывается.
 */
class SetupActivity : AppCompatActivity() {
    internal enum class Step(val title: String) { START("Начало"), SERVER("Home Assistant"), CAMERAS("Камеры"), ROOMS("Комнаты"), TABLET("Планшет"), PROTECT("Защита"), DONE("Готово") }

    internal var step = Step.START
        private set
    private var target = HaConnector.Target("", "")
    private var serverName = ""
    private var restored = false
    private lateinit var content: LinearLayout
    private lateinit var counter: TextView
    private lateinit var footer: LinearLayout

    // Шаг «Home Assistant».
    private var discovery: HaDiscovery? = null
    private var found: List<HaFound> = emptyList()
    private var picked: HaFound? = null
    private var scanning = false
    private var addressText = ""
    private var externalText = ""
    private var serverStatus: LinearLayout? = null
    private var checking = 0

    // Шаг «Камеры».
    private var cameraSearch: CameraDiscovery? = null
    private var foundCameras: List<CameraFound> = emptyList()
    private var cameraHost = ""
    internal val cameras = mutableListOf<StarterPlan.Direct>()
    private var camerasStatus: LinearLayout? = null

    // Шаг «Комнаты». Дом читается один раз: и для камер, и для комнат.
    @Volatile private var home: HaHome? = null
    private var proposal: StarterPlan.Proposal? = null
    private var chosen: Set<String>? = null
    private val expanded = HashSet<String>()
    private var reading = 0
    private var haTokens: HaTokenAccess? = null
    private fun tokens() = haTokens ?: HaTokenAccess.create(this).also { haTokens = it }

    /** Прочитать дом (рабочий поток); в тестах — подмена. */
    internal var readHome: () -> HaHome = {
        val stored = tokens().read()
        if (stored.status != HaTokenAccess.Status.READY) throw HaLogin.LoginException(stored.message)
        HaHome.read(listOf(prefs().getString(ConfigBackup.HA_LOCAL, ""), prefs().getString(ConfigBackup.HA_REMOTE, ""))
            .mapNotNull { address -> address?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { HaServer.origin(it) }.getOrNull() } },
            stored.value.orEmpty())
    }

    internal var newCameraSearch: (Context, (List<CameraFound>) -> Unit, () -> Unit, (() -> HaHome)?) -> CameraDiscovery =
        { context, onChange, done, home -> CameraDiscovery(context, onChange, done, home) }

    /** Поиск и проверка адреса (в тестах — подмена). */
    internal var newDiscovery: (Context, (List<HaFound>) -> Unit) -> HaDiscovery = { context, onChange -> HaDiscovery(context, onChange) }
    /** Состояние PIN (в тестах — подмена). */
    internal var pinAccess: (Context) -> PinAccess = { AndroidPinAccess.create(it) }
    internal var checkServer: (String) -> HaServer.Check = HaServer::check

    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            serverName = result.data?.getStringExtra(HaLoginActivity.EXTRA_NAME).orEmpty().ifBlank { serverName }
            // Вошли — возможно, в другой Home Assistant: комнаты читаются заново.
            proposal = null
            home = null
            chosen = null
            expanded.clear()
            show(afterLogin())
        }
    }
    private val cameraSetup = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) addCamera(data)
    }
    private val copyIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) copied(data)
    }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreFrom { contentResolver.openInputStream(uri)?.let(ConfigImport::readText) }
    }
    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            try {
                contentResolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                prefs().edit { putString(AutoBackup.PREF_TREE, tree.toString()) }
            } catch (_: SecurityException) { note("Нет доступа к этой папке") }
            if (step == Step.PROTECT) show(Step.PROTECT)
        }
    }

    private fun prefs() = getSharedPreferences(HaConnector.PREFS, MODE_PRIVATE)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(build())
        target = HaConnector.Target(prefs().getString(ConfigBackup.HA_LOCAL, "").orEmpty(), prefs().getString(ConfigBackup.HA_REMOTE, "").orEmpty())
        addressText = target.local.ifEmpty { target.external }
        externalText = if (target.local.isEmpty()) "" else target.external
        if (savedInstanceState != null) restoreState(savedInstanceState) else step = resumeStep(this) ?: Step.START
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        if (savedInstanceState == null && intent.getBooleanExtra(CopyInActivity.EXTRA_COPIED, false)) copied(intent) else show(step)
    }

    private fun restoreState(state: Bundle) {
        step = Step.entries.getOrElse(state.getInt(STATE_STEP)) { Step.START }
        serverName = state.getString(STATE_NAME).orEmpty()
        addressText = state.getString(STATE_ADDRESS).orEmpty()
        externalText = state.getString(STATE_EXTERNAL).orEmpty()
        restored = state.getBoolean(STATE_RESTORED)
        chosen = state.getStringArrayList(STATE_CHOSEN)?.toSet()
        state.getStringArrayList(STATE_EXPANDED)?.let { expanded.addAll(it) }
        cameraHost = state.getString(STATE_CAMERA_HOST).orEmpty()
        state.getStringArrayList(STATE_CAMERAS)?.mapNotNull { text -> runCatching { StarterPlan.Direct.fromJson(org.json.JSONObject(text)) }.getOrNull() }
            ?.let { cameras.addAll(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        readInputs()
        outState.putInt(STATE_STEP, step.ordinal)
        outState.putString(STATE_NAME, serverName)
        outState.putString(STATE_ADDRESS, addressText)
        outState.putString(STATE_EXTERNAL, externalText)
        outState.putBoolean(STATE_RESTORED, restored)
        chosen?.let { outState.putStringArrayList(STATE_CHOSEN, ArrayList(it)) }
        outState.putStringArrayList(STATE_EXPANDED, ArrayList(expanded))
        outState.putString(STATE_CAMERA_HOST, cameraHost)
        outState.putStringArrayList(STATE_CAMERAS, ArrayList(cameras.map { it.toJson().toString() }))
    }

    private fun build(): View {
        val pad = resources.getDimensionPixelSize(R.dimen.modern_space_l)
        val column = MaxWidthLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            maxWidth = dp(820)
            setPadding(pad, pad, pad, pad)
        }
        column.addView(ModernUi.header(this, getString(R.string.app_name)))
        counter = ModernUi.secondary(this, "").apply { tag = "setup_step" }
        column.addView(counter)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "setup_content" }
        column.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        column.addView(footer)
        return android.widget.FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@SetupActivity, R.color.modern_background))
            addView(column, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun show(next: Step) {
        readInputs()
        stopDiscovery()
        stopCameraSearch()
        checking++
        reading++
        step = next
        remember()
        counter.text = stepText(next)
        content.removeAllViews()
        footer.removeAllViews()
        serverStatus = null
        camerasStatus = null
        when (next) { Step.START -> start(); Step.SERVER -> server(); Step.CAMERAS -> cameras(); Step.ROOMS -> rooms(); Step.TABLET -> tablet(); Step.PROTECT -> protect(); Step.DONE -> done() }
        (content.parent as? ScrollView)?.scrollTo(0, 0)
    }

    private fun stepText(next: Step) = "Шаг ${next.ordinal + 1} из ${Step.entries.size} · ${next.title}"

    private fun back() = when (step) {
        Step.START -> finish()
        Step.SERVER -> show(Step.START)
        Step.CAMERAS -> show(Step.SERVER)
        Step.ROOMS -> show(Step.CAMERAS)
        Step.TABLET -> show(if (!StarterTiles.canPropose(this)) Step.SERVER else if (connected()) Step.ROOMS else Step.CAMERAS)
        Step.PROTECT -> show(Step.TABLET)
        Step.DONE -> show(Step.PROTECT)
    }

    private fun gap(size: Int = 12) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(size)) }
    private fun note(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    private fun buttons(vararg views: TextView) = views.forEachIndexed { i, v ->
        // Узкий экран: надпись уменьшается, а не рвётся посреди слова.
        v.maxLines = 1
        v.setPadding(dp(6), v.paddingTop, dp(6), v.paddingBottom)
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(v, 10, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        footer.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
    }

    // ---- Начало ----

    private fun start() {
        content.addView(ModernUi.title(this, "Панель умного дома"))
        content.addView(ModernUi.body(this, "Подключим её к вашему Home Assistant. Это займёт несколько минут; любой шаг можно пропустить " +
            "и сделать позже в Настройках."))
        content.addView(gap(20))
        content.addView(ModernUi.primaryButton(this, "Новая панель") { show(Step.SERVER) }.apply { tag = "setup_new" })
        content.addView(gap())
        content.addView(ModernUi.secondaryButton(this, "Скопировать с другого планшета") { copyIn.launch(Intent(this, CopyInActivity::class.java)) }
            .apply { tag = "setup_copy" })
        content.addView(gap())
        content.addView(ModernUi.secondaryButton(this, "Восстановить из файла копии") {
            // Панель могла быть уже настроена в обход мастера: с включённым PIN — только с ним (аудит 25.11.2026, SEC-05).
            PinSecurityUi.authorize(this, onSuccess = { openFile.launch(arrayOf("application/json", "application/octet-stream")) }, access = pinAccess(this))
        }
            .apply { tag = "setup_restore" })
        content.addView(ModernUi.caption(this, "Копия — это плитки и настройки. Токен, пароли и PIN в ней не хранятся — после восстановления нужно " +
            "войти в Home Assistant."))
        buttons(ModernUi.secondaryButton(this, "Пропустить настройку") { openPanel() }.apply { tag = "setup_skip" })
    }

    /** Файл копии: то же применение, что в Настройках; адрес из файла — только заготовка для проверки. */
    internal fun restoreFrom(read: () -> String?) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching(read).getOrNull() }
            if (isFinishing || isDestroyed) return@launch
            fun refused() = note("Не удалось прочитать файл: проверьте, что это файл настроек панели")
            val outcome = try {
                ConfigImport.apply(this@SetupActivity, ConfigImport.parse(text ?: throw JSONException("Файл не прочитан")))
            } catch (_: JSONException) { refused(); return@launch } catch (_: IllegalArgumentException) { refused(); return@launch }
            restoredWith(outcome.local, outcome.remote)
        }
    }

    /** Скопировано с другого планшета ([CopyInActivity]): настройки уже применены, осталось войти. */
    private fun copied(data: Intent) = restoredWith(data.getStringExtra(CopyInActivity.EXTRA_LOCAL)?.ifEmpty { null },
        data.getStringExtra(CopyInActivity.EXTRA_REMOTE)?.ifEmpty { null })

    private fun restoredWith(local: String?, remote: String?) {
        restored = true
        addressText = local?.ifEmpty { null } ?: remote.orEmpty()
        externalText = if (local.isNullOrEmpty()) "" else remote.orEmpty()
        show(Step.SERVER)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(CopyInActivity.EXTRA_COPIED, false)) copied(intent)
    }

    // ---- Home Assistant ----

    private fun server() {
        if (restored) {
            content.addView(ModernUi.body(this, "Плитки и настройки восстановлены. Осталось войти в Home Assistant."))
            content.addView(gap())
        }
        content.addView(ModernUi.title(this, "Найдите свой Home Assistant"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "setup_found" }
        content.addView(list)
        val status = ModernUi.statusRow(this, ModernUi.Status.PROGRESS, "Ищу в домашней сети…").apply { id = R.id.setupServerStatus }
        serverStatus = status
        content.addView(status)
        content.addView(ModernUi.secondaryButton(this, "Искать дальше") { scan() }.apply { tag = "setup_scan" })
        content.addView(gap(20))
        content.addView(ModernUi.title(this, "Или введите адрес"))
        content.addView(ModernUi.secondary(this, UserHints.SETUP_ADDRESS))
        content.addView(gap(8))
        content.addView(ModernUi.input(this, addressText, getString(R.string.local_host_hint)).apply {
            tag = "setup_address"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnFocusChangeListener { _, focused -> if (focused) pick(null) }
        })
        content.addView(gap(8))
        content.addView(ModernUi.input(this, externalText, "Внешний адрес (необязательно), например https://…").apply {
            tag = "setup_external"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        })
        buttons(ModernUi.secondaryButton(this, "Назад") { show(Step.START) },
            ModernUi.secondaryButton(this, "Пропустить") { show(afterLogin()) }.apply { tag = "setup_server_skip" },
            ModernUi.primaryButton(this, "Дальше") { checkAndLogin() }.apply { tag = "setup_next" })
        renderFound()
        startDiscovery()
    }

    private fun startDiscovery() {
        if (discovery != null || step != Step.SERVER || !resumed) return
        discovery = newDiscovery(this) { items ->
            found = items
            if (picked == null && addressText.isBlank()) picked = items.firstOrNull { !it.onboarding }
            renderFound()
        }.also { it.start() }
        content.postDelayed(nothingYet, NOTHING_MS)
    }

    private val nothingYet = Runnable {
        if (step == Step.SERVER && found.isEmpty() && !scanning) ModernUi.setStatus(serverStatus ?: return@Runnable, ModernUi.Status.WARNING,
            "Пока не нашёл. Проверьте, что планшет и Home Assistant в одной сети (не гостевой), нажмите «Искать дальше» или введите адрес")
    }

    private fun stopDiscovery() {
        content.removeCallbacks(nothingYet)
        discovery?.stop()
        discovery = null
        scanning = false
    }

    private fun scan() {
        if (scanning) return
        startDiscovery()
        val current = discovery ?: return
        scanning = true
        serverStatus?.let { ModernUi.setStatus(it, ModernUi.Status.PROGRESS, "Проверяю соседние адреса…") }
        current.scan { checked ->
            if (discovery !== current) return@scan
            scanning = false
            val row = serverStatus ?: return@scan
            if (checked == 0) ModernUi.setStatus(row, ModernUi.Status.WARNING, "Планшет не в домашней сети (нужен Wi-Fi или провод)")
            else if (found.isEmpty()) ModernUi.setStatus(row, ModernUi.Status.WARNING, "Не нашёл. Введите адрес Home Assistant")
            else renderFound()
        }
    }

    private fun renderFound() {
        if (step != Step.SERVER) return
        val list = content.findViewWithTag<LinearLayout>("setup_found") ?: return
        list.removeAllViews()
        found.forEachIndexed { i, item ->
            val details = listOf(item.version.takeIf { it.isNotBlank() }?.let { "Home Assistant $it" }, item.address,
                if (item.onboarding) "ещё не настроен" else null).filterNotNull().joinToString(" · ")
            list.addView(ModernUi.section(this, item.title, ModernUi.secondary(this, details)).apply {
                tag = "setup_found_$i"
                isSelected = item.address == picked?.address
                background = BatteryModernCard.rounded(android.graphics.Color.argb(if (isSelected) 90 else 40, 95, 168, 211),
                    12 * resources.displayMetrics.density, android.graphics.Color.argb(if (isSelected) 220 else 60, 95, 168, 211))
                isClickable = true
                isFocusable = true
                setOnClickListener { pick(item) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        if (found.isNotEmpty() && !scanning) serverStatus?.let { ModernUi.setStatus(it, ModernUi.Status.OK, "Найдено: ${found.size}") }
    }

    private fun pick(item: HaFound?) {
        picked = item
        if (item != null) {
            content.findViewWithTag<EditText>("setup_address")?.apply { setText(""); clearFocus() }
            addressText = ""
        }
        renderFound()
    }

    private fun readInputs() {
        if (!::content.isInitialized) return
        if (step == Step.CAMERAS) content.findViewWithTag<EditText>("setup_camera_host")?.let { cameraHost = it.text.toString().trim() }
        if (step != Step.SERVER) return
        content.findViewWithTag<EditText>("setup_address")?.let { addressText = it.text.toString().trim() }
        content.findViewWithTag<EditText>("setup_external")?.let { externalText = it.text.toString().trim() }
    }

    /** Проверить, что по адресу Home Assistant, и открыть вход. Адреса сохраняются только после успешного входа. */
    internal fun checkAndLogin() {
        readInputs()
        val chosen = picked.takeIf { addressText.isEmpty() }
        val address = chosen?.address ?: addressText
        val row = serverStatus ?: return
        if (address.isEmpty()) {
            ModernUi.setStatus(row, ModernUi.Status.WARNING, "Выберите найденный Home Assistant или введите адрес")
            return
        }
        HomeAssistantAddress.transportWarning(listOf(address, externalText))?.let {
            ModernUi.setStatus(row, ModernUi.Status.ERROR, HomeAssistantAddress.INSECURE_REMOTE_REFUSED)
            return
        }
        val run = ++checking
        ModernUi.setStatus(row, ModernUi.Status.PROGRESS, "Проверяю адрес…")
        val check = checkServer
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { check(address) }
            if (run != checking || isFinishing || isDestroyed || step != Step.SERVER) return@launch
            if (result.state != HaServer.State.READY) {
                ModernUi.setStatus(row, ModernUi.Status.ERROR, result.message)
                return@launch
            }
            ModernUi.setStatus(row, ModernUi.Status.OK, result.message)
            // Адрес, введённый человеком, сохраняется как введён; отдельно — куда перенаправил обратный прокси.
            val saved = chosen?.address ?: if (redirected(address, result.origin)) result.origin else address
            val private = HomeAssistantAddress.isPrivateNetwork(saved)
            // Внешний адрес — только введённый человеком или полученный после входа: в объявлении по сети его мог подменить кто
            // угодно, а туда уходил бы токен (аудит 25.11.2026, SEC-01).
            val external = externalText
            target = if (private) HaConnector.Target(saved, external, chosen?.uuid.orEmpty())
                else HaConnector.Target("", saved, chosen?.uuid.orEmpty())
            serverName = chosen?.name.orEmpty()
            login.launch(HaLoginActivity.intent(this@SetupActivity, target, serverName))
        }
    }

    private fun redirected(address: String, origin: String) =
        try { HaServer.origin(address) != origin } catch (_: IllegalArgumentException) { false }

    // ---- Камеры ----

    private fun cameras() {
        content.addView(ModernUi.title(this, "Камеры и домофоны"))
        content.addView(ModernUi.body(this, "Ищу камеры в домашней сети и в Home Assistant — без паролей, ничего в них не меняя."))
        val mine = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "setup_cameras_added" }
        content.addView(mine)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "setup_cameras" }
        content.addView(list)
        val status = ModernUi.statusRow(this, ModernUi.Status.PROGRESS, "Ищу…").apply { id = R.id.setupCamerasStatus }
        camerasStatus = status
        content.addView(status)
        content.addView(ModernUi.secondaryButton(this, "Искать ещё раз") { searchCameras() }.apply { tag = "setup_cameras_again" })
        content.addView(gap(20))
        content.addView(ModernUi.title(this, "Или введите адрес камеры"))
        content.addView(ModernUi.input(this, cameraHost, "IP в домашней сети, например 192.168.1.60").apply {
            tag = "setup_camera_host"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        })
        content.addView(gap(8))
        content.addView(ModernUi.secondaryButton(this, "Настроить камеру по адресу") { typedCamera() }.apply { tag = "setup_camera_typed" })
        buttons(ModernUi.secondaryButton(this, "Назад") { show(Step.SERVER) },
            ModernUi.primaryButton(this, "Дальше") { camerasDone() }.apply { tag = "setup_next" })
        renderCameras()
        if (resumed) searchCameras()
    }

    private fun searchCameras() {
        stopCameraSearch()
        camerasStatus?.let { ModernUi.setStatus(it, ModernUi.Status.PROGRESS, "Ищу…") }
        val read = readHome
        cameraSearch = newCameraSearch(this, { items ->
            foundCameras = items
            renderCameras()
        }, {
            if (step == Step.CAMERAS) camerasStatus?.let {
                if (foundCameras.isEmpty()) ModernUi.setStatus(it, ModernUi.Status.WARNING, "Не нашёл. Часть камер не отвечает на поиск (выключены ONVIF и RTSP, другая сеть) — введите адрес")
                else ModernUi.setStatus(it, ModernUi.Status.OK, "Найдено: ${foundCameras.size}")
            }
        }, if (connected()) ({ (home ?: read()).also { home = it } }) else null).also { it.start() }
    }

    private fun stopCameraSearch() { cameraSearch?.stop(); cameraSearch = null }

    private fun renderCameras() {
        if (step != Step.CAMERAS) return
        val mine = content.findViewWithTag<LinearLayout>("setup_cameras_added") ?: return
        val list = content.findViewWithTag<LinearLayout>("setup_cameras") ?: return
        mine.removeAllViews()
        cameras.forEach { camera ->
            mine.addView(ModernUi.section(this, camera.title, ModernUi.secondary(this, (if (camera.doorbell) "Домофон" else "Камера") + " · добавлено"),
                ModernUi.secondaryButton(this, "Убрать") { removeCamera(camera) }.apply { tag = "setup_camera_remove_${camera.host}" }), roomParams())
        }
        list.removeAllViews()
        foundCameras.filter { item -> cameras.none { it.host == item.host } }.forEach { item ->
            val details = listOf(item.brand.title, item.model.takeUnless { it == item.title }.orEmpty(), item.host).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            list.addView(ModernUi.section(this, item.title, ModernUi.secondary(this, details),
                ModernUi.primaryButton(this, "Настроить") { openCamera(item) }.apply { tag = "setup_camera_${item.host}" }), roomParams())
        }
    }

    private fun typedCamera() {
        readInputs()
        val host = cameraHost.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')
        if (host.isEmpty()) { camerasStatus?.let { ModernUi.setStatus(it, ModernUi.Status.WARNING, "Введите адрес камеры") }; return }
        if (!HomeAssistantAddress.isPrivateNetwork(host)) {
            camerasStatus?.let { ModernUi.setStatus(it, ModernUi.Status.ERROR, "Нужен адрес в домашней сети: пароль камеры через интернет не отправляется") }
            return
        }
        openCamera(foundCameras.firstOrNull { it.host == host } ?: CameraFound(host))
    }

    private fun openCamera(item: CameraFound) =
        cameraSetup.launch(CameraSetupActivity.intent(this, item, cameras.any { it.doorbell && it.host != item.host }))

    private fun addCamera(data: Intent) {
        val id = data.getStringExtra(CameraSetupActivity.EXTRA_ID).orEmpty()
        val host = data.getStringExtra(CameraSetupActivity.EXTRA_HOST).orEmpty()
        val config = try { DoorbellConfig.fromJson(org.json.JSONObject(data.getStringExtra(CameraSetupActivity.EXTRA_CONFIG).orEmpty())) } catch (_: JSONException) { return }
        if (id.isEmpty()) return
        val doorbell = data.getBooleanExtra(CameraSetupActivity.EXTRA_DOORBELL, false)
        val device = foundCameras.firstOrNull { it.host == host }?.haDevice.orEmpty()
        cameras.removeAll { it.host == host }
        cameras += StarterPlan.Direct(id, doorbell, data.getStringExtra(CameraSetupActivity.EXTRA_TITLE).orEmpty(), host, config, device)
        camerasChanged()
    }

    private fun removeCamera(camera: StarterPlan.Direct) {
        cameras.remove(camera)
        if (!camera.doorbell) CameraPasswords.save(this, camera.id, "") {}
        camerasChanged()
    }

    /** Камеры изменились — предложение и выбор плиток считаются заново. */
    private fun camerasChanged() {
        proposal = null
        chosen = null
        renderCameras()
    }

    private fun camerasDone() {
        readInputs()
        if (connected()) { show(Step.ROOMS); return }
        // Без Home Assistant комнат нет: на панели — часы и добавленные камеры.
        if (cameras.isNotEmpty()) createPlan(emptyProposal(), setOf(StarterPlan.CLIMATE))
        show(Step.TABLET)
    }

    /** Добавленные камеры с тем, что о них знает Home Assistant (датчики событий, камера для видео вне дома). */
    private fun directCameras(): List<StarterPlan.Direct> {
        val known = home ?: return cameras
        return cameras.map { c ->
            val device = c.haDevice.ifEmpty { foundCameras.firstOrNull { it.host == c.host }?.haDevice.orEmpty() }
            StarterPlan.Direct(c.id, c.doorbell, c.title, c.host, StarterPlan.withHome(c.config, known, device, c.doorbell), device)
        }
    }

    private fun emptyProposal() = StarterPlan.Proposal(emptyList(), "", 0)

    private fun createPlan(p: StarterPlan.Proposal, selected: Set<String>) {
        if (!StarterTiles.apply(this, currentPlan(p, selected))) note("Панель уже изменили — плитки не созданы")
    }

    // ---- Комнаты ----

    private fun connected() = prefs().getBoolean(HaConnector.KEY_DONE, false)

    /** Панель уже с плитками (восстановлена из файла) — предлагать нечего. */
    private fun afterLogin() = if (StarterTiles.canPropose(this)) Step.CAMERAS else Step.TABLET

    private fun rooms() {
        content.addView(ModernUi.title(this, "Что показать на панели"))
        content.addView(ModernUi.body(this, "Отмечено то, что нашлось в комнатах Home Assistant. Снимите лишнее — потом всё можно " +
            "изменить. Ничего не переключается: создаются только плитки."))
        val status = ModernUi.statusRow(this, ModernUi.Status.PROGRESS, "Читаю комнаты и устройства…").apply { id = R.id.setupRoomsStatus }
        content.addView(status)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "setup_rooms" }
        content.addView(list)
        buttons(ModernUi.secondaryButton(this, "Назад") { show(Step.CAMERAS) },
            ModernUi.secondaryButton(this, "Пропустить") {
                // Комнаты не нужны, но добавленные камеры остаются.
                if (cameras.isNotEmpty()) createPlan(emptyProposal(), setOf(StarterPlan.CLIMATE))
                show(Step.TABLET)
            }.apply { tag = "setup_rooms_skip" },
            ModernUi.primaryButton(this, "Создать плитки") { createTiles() }.apply { tag = "setup_rooms_next"; isEnabled = false })
        proposal?.let { renderRooms(it) } ?: loadRooms()
    }

    private fun loadRooms() {
        val run = ++reading
        val read = readHome
        lifecycleScope.launch {
            val known = cameras.mapTo(HashSet()) { it.haDevice }.apply { remove("") }
            val result = withContext(Dispatchers.IO) { runCatching { StarterPlan.propose((home ?: read()).also { home = it }, known) } }
            if (run != reading || isFinishing || isDestroyed || step != Step.ROOMS) return@launch
            result.onSuccess { proposal = it; renderRooms(it) }.onFailure { e ->
                content.findViewById<LinearLayout>(R.id.setupRoomsStatus)?.let { ModernUi.setStatus(it, ModernUi.Status.ERROR,
                    (e as? HaLogin.LoginException)?.message ?: "Не удалось прочитать комнаты") }
                val list = content.findViewWithTag<LinearLayout>("setup_rooms") ?: return@launch
                list.removeAllViews()
                list.addView(ModernUi.secondaryButton(this@SetupActivity, "Повторить") { show(Step.ROOMS) }.apply { tag = "setup_rooms_again" })
            }
        }
    }

    /** Размер горизонтальной панели — и в книжном положении: раскладка всегда строится горизонтальной. */
    private fun panelSize(): Pair<Int, Int> {
        val (w, h) = SystemBarInsets.legacyWindowSize(this)
        if (w >= h) return w to h
        // Полоса навигации в горизонтали снизу: запас на неё, чтобы нижний ряд не ушёл под неё.
        val full = resources.displayMetrics.heightPixels
        return h to w - (full - h).coerceAtLeast(0)
    }

    private fun currentPlan(p: StarterPlan.Proposal, selected: Set<String>): StarterPlan.Plan {
        val (w, h) = panelSize()
        return StarterPlan.layout(p, selected, w, h, resources.displayMetrics.density, GateStore.load(this), directCameras())
    }

    private fun renderRooms(p: StarterPlan.Proposal) {
        val list = content.findViewWithTag<LinearLayout>("setup_rooms") ?: return
        val (w, h) = panelSize()
        val selected = chosen ?: StarterPlan.defaults(p, w, h, resources.displayMetrics.density, cameras).also { chosen = it }
        list.removeAllViews()
        val preview = StarterPreview(this).apply { tag = "setup_preview" }
        val plan = currentPlan(p, selected)
        preview.show(plan, w, h)
        list.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        val warm = p.rooms.count { it.temperature.isNotEmpty() }
        val climate = listOfNotNull("часы", if (p.weather.isNotEmpty()) "погода" else null, if (warm > 0) "температура комнат: $warm" else null).joinToString(", ")
        list.addView(ModernUi.section(this, null, check("Часы · Погода · Климат", climate, StarterPlan.CLIMATE in selected, "setup_item_climate") {
            toggle(setOf(StarterPlan.CLIMATE), it)
        }), roomParams())
        p.rooms.filter { it.items.isNotEmpty() }.forEach { room -> list.addView(roomView(room, selected), roomParams()) }
        val status = content.findViewById<LinearLayout>(R.id.setupRoomsStatus)
        val missing = if (p.skipped > 0) " Недоступны сейчас и не предложены: ${p.skipped}." else ""
        ModernUi.setStatus(status, if (plan.tiles.isEmpty()) ModernUi.Status.UNKNOWN else ModernUi.Status.OK,
            if (p.isEmpty) "В Home Assistant пока не нашлось устройств — будут только часы." else "Плиток: ${plan.tiles.size} · страниц: ${plan.pages}.$missing")
        footer.findViewWithTag<View>("setup_rooms_next")?.isEnabled = true
    }

    private fun roomParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }

    private fun roomView(room: StarterPlan.Room, selected: Set<String>): View {
        val mine = room.items.count { it.entity in selected }
        val open = room.id in expanded
        val kinds = room.items.groupBy { it.kind }.entries.joinToString(", ") { (kind, all) -> "${kindName(kind)} ${all.size}" }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(check(room.name, "$kinds · отмечено: $mine", mine > 0, "setup_room_${room.id}") { on ->
                toggle(room.items.mapTo(HashSet()) { it.entity }, on)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ModernUi.secondaryButton(this@SetupActivity, if (open) "Свернуть" else "Выбрать") {
                if (!expanded.remove(room.id)) expanded.add(room.id)
                proposal?.let { renderRooms(it) }
            }.apply { tag = "setup_room_open_${room.id}" })
        }
        val section = ModernUi.section(this, null, line)
        if (open) room.items.forEach { item ->
            section.addView(check(item.name, kindName(item.kind), item.entity in selected, "setup_item_${item.entity}") { toggle(setOf(item.entity), it) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
        }
        return section
    }

    private fun kindName(kind: StarterPlan.Kind) = when (kind) {
        StarterPlan.Kind.LIGHT -> "свет"
        StarterPlan.Kind.SWITCH -> "розетки и выключатели"
        StarterPlan.Kind.FAN -> "вентиляторы"
        StarterPlan.Kind.GATE -> "калитки, ворота, замки"
        StarterPlan.Kind.CAMERA -> "камеры"
    }

    private fun check(title: String, details: String, on: Boolean, tag: String, change: (Boolean) -> Unit) = android.widget.CheckBox(this).apply {
        val text = android.text.SpannableString("$title\n$details")
        text.setSpan(android.text.style.RelativeSizeSpan(0.85f), title.length, text.length, 0)
        text.setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(this@SetupActivity, R.color.modern_text_secondary)), title.length, text.length, 0)
        this.text = text
        setTextAppearance(R.style.TextAppearance_Modern_Body)
        minHeight = resources.getDimensionPixelSize(R.dimen.modern_touch_target)
        isChecked = on
        this.tag = tag
        setOnCheckedChangeListener { _, value -> change(value) }
    }

    private fun toggle(entities: Set<String>, on: Boolean) {
        val p = proposal ?: return
        chosen = (chosen ?: emptySet()).let { if (on) it + entities else it - entities }
        // После касания список перестраивается: число страниц и схема считаются заново, место прокрутки сохраняется.
        content.post { if (step == Step.ROOMS && !isDestroyed) renderRooms(p) }
    }

    private fun createTiles() {
        val p = proposal ?: return
        val selected = chosen ?: return
        val plan = currentPlan(p, selected)
        val done = if (plan.tiles.isEmpty()) { if (StarterTiles.untouched(this)) StarterTiles.undo(this); true } else StarterTiles.apply(this, plan)
        if (!done) note("Панель уже изменили — плитки не созданы")
        show(Step.TABLET)
    }

    // ---- Планшет ----

    private var tabletChecks: List<TabletCheck.Item> = emptyList()
    private var tabletRun = 0

    private fun tablet() {
        content.addView(ModernUi.title(this, "Планшет на стене"))
        content.addView(ModernUi.body(this, "Чтобы панель работала круглосуточно и возвращалась сама. Необязательно: этот же список — в Настройках → " +
            "«Устройство» → «Планшет»."))
        content.addView(ModernUi.statusRow(this, ModernUi.Status.PROGRESS, "Проверяю…").apply { id = R.id.tabletSummary })
        content.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "tablet_checks" })
        buttons(ModernUi.secondaryButton(this, "Назад") { back() },
            ModernUi.primaryButton(this, "Дальше") { show(Step.PROTECT) }.apply { tag = "setup_next" })
        if (tabletChecks.isNotEmpty()) showTablet()
        if (resumed) readTablet()
    }

    /** Факты читаются в фоне; после экранов Android — заново (onResume). */
    private fun readTablet() {
        val run = ++tabletRun
        Thread({
            val items = try { TabletCheck.evaluate(TabletCheck.read(this)) } catch (_: RuntimeException) { emptyList() }
            runOnUiThread {
                if (run != tabletRun || isFinishing || isDestroyed) return@runOnUiThread
                tabletChecks = items
                showTablet()
            }
        }, "setup-tablet").apply { isDaemon = true; start() }
    }

    private fun showTablet() {
        if (step != Step.TABLET) return
        val list = content.findViewWithTag<LinearLayout>("tablet_checks") ?: return
        TabletCheckView.summary(tabletChecks).let { (status, text) -> ModernUi.setStatus(content.findViewById(R.id.tabletSummary), status, text) }
        TabletCheckView.render(list, tabletChecks) { action -> TabletActions.run(this, action) { readTablet() } }
    }

    // ---- Защита ----

    private fun protect() {
        content.addView(ModernUi.title(this, "Защита панели"))
        content.addView(ModernUi.body(this, "Необязательно: всё это есть и в Настройках."))
        content.addView(gap())
        val pin = ModernUi.statusRow(this, ModernUi.Status.UNKNOWN, "PIN: проверяю…").apply { id = R.id.setupPinStatus }
        content.addView(ModernUi.section(this, "PIN для настроек",
            ModernUi.secondary(this, "Чтобы никто случайно не изменил панель: настройки и правка плиток — только с PIN из 6 цифр."),
            pin, ModernUi.secondaryButton(this, "Задать PIN") { PinSecurityUi.manage(this) }.apply { tag = "setup_pin" }))
        content.addView(gap())
        val folder = prefs().getString(AutoBackup.PREF_TREE, null) != null
        content.addView(ModernUi.section(this, "Резервная копия",
            ModernUi.secondary(this, "Копия настроек создаётся каждую ночь. В выбранной папке она переживёт удаление приложения."),
            ModernUi.statusRow(this, if (folder) ModernUi.Status.OK else ModernUi.Status.UNKNOWN,
                if (folder) "Папка выбрана" else "Пока внутри приложения"),
            ModernUi.secondaryButton(this, "Выбрать папку") { openFolder.launch(null) }.apply { tag = "setup_folder" }))
        buttons(ModernUi.secondaryButton(this, "Назад") { back() },
            ModernUi.primaryButton(this, "Дальше") { show(Step.DONE) }.apply { tag = "setup_next" })
        refreshPin()
    }

    private fun refreshPin() {
        if (step != Step.PROTECT) return
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { AndroidPinAccess.create(this@SetupActivity).status() }.status
            if (isDestroyed) return@launch
            val enabled = state == PinAccess.Status.SUCCESS || state == PinAccess.Status.WAIT
            content.findViewById<LinearLayout>(R.id.setupPinStatus)?.let {
                when {
                    enabled -> ModernUi.setStatus(it, ModernUi.Status.OK, "PIN включён")
                    state == PinAccess.Status.DISABLED -> ModernUi.setStatus(it, ModernUi.Status.UNKNOWN, "PIN не задан")
                    else -> ModernUi.setStatus(it, ModernUi.Status.ERROR, "PIN: не удалось проверить")
                }
            }
            content.findViewWithTag<TextView>("setup_pin")?.text = if (enabled) "Изменить PIN" else "Задать PIN"
        }
    }

    // ---- Готово ----

    private fun done() {
        val connected = connected()
        content.addView(ModernUi.title(this, "Готово"))
        content.addView(ModernUi.statusRow(this, if (connected) ModernUi.Status.OK else ModernUi.Status.WARNING,
            if (connected) "Home Assistant: подключён" + (if (serverName.isBlank()) "" else " · $serverName")
            else "Home Assistant: не подключён. Настройки → «Соединения»"))
        val count = com.example.smarthomedashboard.data.TileManager(this).loadTiles().size
        content.addView(ModernUi.statusRow(this, if (count > 0) ModernUi.Status.OK else ModernUi.Status.UNKNOWN,
            if (count > 0) "Плиток на панели: $count" else "Плитки: часы и погода, остальное добавите сами"))
        val video = com.example.smarthomedashboard.data.TileManager(this).loadTiles().count { DoorbellConfig.isVideo(it.type) }
        if (restored && video > 0) content.addView(ModernUi.statusRow(this, ModernUi.Status.WARNING,
            "Пароли камер в копии не хранятся: введите их в редакторах плиток (камер: $video)"))
        if (StarterTiles.untouched(this)) content.addView(ModernUi.secondaryButton(this, "Убрать предложенные плитки") {
            if (StarterTiles.undo(this)) { cameras.clear(); camerasChanged() }
            show(Step.DONE)
        }.apply { tag = "setup_undo" })
        val folder = prefs().getString(AutoBackup.PREF_TREE, null) != null
        content.addView(ModernUi.statusRow(this, if (folder) ModernUi.Status.OK else ModernUi.Status.UNKNOWN,
            if (folder) "Копии: в выбранной папке" else "Копии: внутри приложения"))
        content.addView(gap())
        content.addView(ModernUi.body(this, "Добавить плитку: проведите двумя пальцами слева направо — откроется боковая панель — и нажмите «+ Добавить».\n\n" +
            "Адрес, вход и всё остальное — там же, в «⚙ Настройки»."))
        buttons(ModernUi.secondaryButton(this, "Назад") { show(Step.PROTECT) },
            ModernUi.primaryButton(this, "Открыть панель") { openPanel() }.apply { tag = "setup_finish" })
    }

    private fun openPanel() {
        prefs().edit { remove(KEY_STEP); remove(KEY_STEP_AT) }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /** Прерванный мастер продолжается с того же шага — но не дольше часа: настроенная панель на стене важнее. */
    private fun remember() = prefs().edit {
        if (connected() && step >= Step.CAMERAS) {
            putString(KEY_STEP, step.name); putLong(KEY_STEP_AT, System.currentTimeMillis())
        } else { remove(KEY_STEP); remove(KEY_STEP_AT) }
    }

    private var resumed = false

    override fun onResume() {
        super.onResume()
        resumed = true
        if (step == Step.SERVER) startDiscovery()
        if (step == Step.CAMERAS && foundCameras.isEmpty()) searchCameras()
        refreshPin()
        if (step == Step.TABLET) readTablet()
        BootReturn.panelShown(this)
    }

    override fun onPause() {
        resumed = false
        stopDiscovery()
        stopCameraSearch()
        super.onPause()
    }

    /** Диалоги PIN закрылись — состояние могло измениться. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::content.isInitialized) refreshPin()
    }

    override fun onDestroy() {
        stopDiscovery()
        stopCameraSearch()
        // Логин прошлой камеры — только на время мастера.
        if (isFinishing) CameraSetupActivity.last = null
        super.onDestroy()
    }

    companion object {
        private const val STATE_STEP = "setup_step"
        private const val STATE_NAME = "setup_name"
        private const val STATE_ADDRESS = "setup_address"
        private const val STATE_EXTERNAL = "setup_external"
        private const val STATE_RESTORED = "setup_restored"
        private const val STATE_CHOSEN = "setup_chosen"
        private const val STATE_EXPANDED = "setup_expanded"
        private const val STATE_CAMERAS = "setup_cameras"
        private const val STATE_CAMERA_HOST = "setup_camera_host"
        private const val KEY_STEP = "setup_step"
        private const val KEY_STEP_AT = "setup_step_at"
        private const val RESUME_MS = 60 * 60_000L
        private const val NOTHING_MS = 8_000L

        /** Шаг прерванного мастера после входа (не старше часа); null — мастер не прерывался. */
        internal fun resumeStep(context: Context): Step? {
            val prefs = context.getSharedPreferences(HaConnector.PREFS, Context.MODE_PRIVATE)
            val age = System.currentTimeMillis() - prefs.getLong(KEY_STEP_AT, 0)
            if (age !in 0..RESUME_MS) return null
            return Step.entries.firstOrNull { it.name == prefs.getString(KEY_STEP, null) && it >= Step.CAMERAS }
        }
    }
}
