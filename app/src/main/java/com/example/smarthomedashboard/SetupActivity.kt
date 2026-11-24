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
 * Мастер первой настройки (docs/setup-tz.md): начало → Home Assistant → вход ([HaLoginActivity]) → защита → готово.
 * Плитки, камеры и проверка планшета — шаги следующих этапов. Каждый шаг можно пропустить. На уже настроенной панели не
 * открывается.
 */
class SetupActivity : AppCompatActivity() {
    internal enum class Step(val title: String) { START("Начало"), SERVER("Home Assistant"), PROTECT("Защита"), DONE("Готово") }

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

    /** Поиск и проверка адреса (в тестах — подмена). */
    internal var newDiscovery: (Context, (List<HaFound>) -> Unit) -> HaDiscovery = { context, onChange -> HaDiscovery(context, onChange) }
    internal var checkServer: (String) -> HaServer.Check = HaServer::check

    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            serverName = result.data?.getStringExtra(HaLoginActivity.EXTRA_NAME).orEmpty().ifBlank { serverName }
            show(Step.PROTECT)
        }
    }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreFrom { contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }
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
        show(step)
    }

    private fun restoreState(state: Bundle) {
        step = Step.entries.getOrElse(state.getInt(STATE_STEP)) { Step.START }
        serverName = state.getString(STATE_NAME).orEmpty()
        addressText = state.getString(STATE_ADDRESS).orEmpty()
        externalText = state.getString(STATE_EXTERNAL).orEmpty()
        restored = state.getBoolean(STATE_RESTORED)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        readInputs()
        outState.putInt(STATE_STEP, step.ordinal)
        outState.putString(STATE_NAME, serverName)
        outState.putString(STATE_ADDRESS, addressText)
        outState.putString(STATE_EXTERNAL, externalText)
        outState.putBoolean(STATE_RESTORED, restored)
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
        checking++
        step = next
        remember()
        counter.text = stepText(next)
        content.removeAllViews()
        footer.removeAllViews()
        serverStatus = null
        when (next) { Step.START -> start(); Step.SERVER -> server(); Step.PROTECT -> protect(); Step.DONE -> done() }
        (content.parent as? ScrollView)?.scrollTo(0, 0)
    }

    private fun stepText(next: Step) = "Шаг ${next.ordinal + 1} из ${Step.entries.size} · ${next.title}"

    private fun back() = when (step) {
        Step.START -> finish()
        Step.SERVER -> show(Step.START)
        Step.PROTECT -> show(Step.SERVER)
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
        content.addView(ModernUi.secondaryButton(this, "Восстановить из файла копии") { openFile.launch(arrayOf("application/json", "application/octet-stream")) }
            .apply { tag = "setup_restore" })
        content.addView(ModernUi.caption(this, "Файл копии хранит плитки и настройки. Токен, пароли и PIN в нём не хранятся — " +
            "после восстановления нужно войти в Home Assistant."))
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
            restored = true
            addressText = outcome.local?.ifEmpty { null } ?: outcome.remote.orEmpty()
            externalText = if (outcome.local.isNullOrEmpty()) "" else outcome.remote.orEmpty()
            show(Step.SERVER)
        }
    }

    // ---- Home Assistant ----

    private fun server() {
        if (restored) {
            content.addView(ModernUi.body(this, "Плитки и настройки из файла восстановлены. Осталось войти в Home Assistant."))
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
            ModernUi.secondaryButton(this, "Пропустить") { show(Step.PROTECT) }.apply { tag = "setup_server_skip" },
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
        if (!::content.isInitialized || step != Step.SERVER) return
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
            val external = externalText.ifEmpty { chosen?.external.orEmpty() }
            target = if (private) HaConnector.Target(saved, external, chosen?.uuid.orEmpty())
                else HaConnector.Target("", saved, chosen?.uuid.orEmpty())
            serverName = chosen?.name.orEmpty()
            login.launch(HaLoginActivity.intent(this@SetupActivity, target, serverName))
        }
    }

    private fun redirected(address: String, origin: String) =
        try { HaServer.origin(address) != origin } catch (_: IllegalArgumentException) { false }

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
        buttons(ModernUi.secondaryButton(this, "Назад") { show(Step.SERVER) },
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
        val connected = prefs().getBoolean(HaConnector.KEY_DONE, false)
        content.addView(ModernUi.title(this, "Готово"))
        content.addView(ModernUi.statusRow(this, if (connected) ModernUi.Status.OK else ModernUi.Status.WARNING,
            if (connected) "Home Assistant: подключён" + (if (serverName.isBlank()) "" else " · $serverName")
            else "Home Assistant: не подключён. Настройки → «Соединения»"))
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
        if (prefs().getBoolean(HaConnector.KEY_DONE, false) && step >= Step.PROTECT) {
            putString(KEY_STEP, step.name); putLong(KEY_STEP_AT, System.currentTimeMillis())
        } else { remove(KEY_STEP); remove(KEY_STEP_AT) }
    }

    private var resumed = false

    override fun onResume() {
        super.onResume()
        resumed = true
        if (step == Step.SERVER) startDiscovery()
        refreshPin()
    }

    override fun onPause() {
        resumed = false
        stopDiscovery()
        super.onPause()
    }

    /** Диалоги PIN закрылись — состояние могло измениться. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::content.isInitialized) refreshPin()
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }

    companion object {
        private const val STATE_STEP = "setup_step"
        private const val STATE_NAME = "setup_name"
        private const val STATE_ADDRESS = "setup_address"
        private const val STATE_EXTERNAL = "setup_external"
        private const val STATE_RESTORED = "setup_restored"
        private const val KEY_STEP = "setup_step"
        private const val KEY_STEP_AT = "setup_step_at"
        private const val RESUME_MS = 60 * 60_000L
        private const val NOTHING_MS = 8_000L

        /** Шаг прерванного мастера после входа (не старше часа); null — мастер не прерывался. */
        internal fun resumeStep(context: Context): Step? {
            val prefs = context.getSharedPreferences(HaConnector.PREFS, Context.MODE_PRIVATE)
            val age = System.currentTimeMillis() - prefs.getLong(KEY_STEP_AT, 0)
            if (age !in 0..RESUME_MS) return null
            return Step.entries.firstOrNull { it.name == prefs.getString(KEY_STEP, null) && it >= Step.PROTECT }
        }
    }
}
