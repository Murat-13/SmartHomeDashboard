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
 * Мастер первой настройки (docs/setup-tz.md): начало → Home Assistant → вход ([HaLoginActivity]) → комнаты → защита →
 * готово. Камеры и проверка планшета — шаги следующих этапов. Каждый шаг можно пропустить. На уже настроенной панели не
 * открывается.
 */
class SetupActivity : AppCompatActivity() {
    internal enum class Step(val title: String) { START("Начало"), SERVER("Home Assistant"), ROOMS("Комнаты"), PROTECT("Защита"), DONE("Готово") }

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

    // Шаг «Комнаты».
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

    /** Поиск и проверка адреса (в тестах — подмена). */
    internal var newDiscovery: (Context, (List<HaFound>) -> Unit) -> HaDiscovery = { context, onChange -> HaDiscovery(context, onChange) }
    internal var checkServer: (String) -> HaServer.Check = HaServer::check

    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            serverName = result.data?.getStringExtra(HaLoginActivity.EXTRA_NAME).orEmpty().ifBlank { serverName }
            // Вошли — возможно, в другой Home Assistant: комнаты читаются заново.
            proposal = null
            chosen = null
            expanded.clear()
            show(afterLogin())
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
        chosen = state.getStringArrayList(STATE_CHOSEN)?.toSet()
        state.getStringArrayList(STATE_EXPANDED)?.let { expanded.addAll(it) }
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
        reading++
        step = next
        remember()
        counter.text = stepText(next)
        content.removeAllViews()
        footer.removeAllViews()
        serverStatus = null
        when (next) { Step.START -> start(); Step.SERVER -> server(); Step.ROOMS -> rooms(); Step.PROTECT -> protect(); Step.DONE -> done() }
        (content.parent as? ScrollView)?.scrollTo(0, 0)
    }

    private fun stepText(next: Step) = "Шаг ${next.ordinal + 1} из ${Step.entries.size} · ${next.title}"

    private fun back() = when (step) {
        Step.START -> finish()
        Step.SERVER -> show(Step.START)
        Step.ROOMS -> show(Step.SERVER)
        Step.PROTECT -> show(if (connected() && afterLogin() == Step.ROOMS) Step.ROOMS else Step.SERVER)
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
            ModernUi.secondaryButton(this, "Пропустить") { show(if (connected()) afterLogin() else Step.PROTECT) }.apply { tag = "setup_server_skip" },
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

    // ---- Комнаты ----

    private fun connected() = prefs().getBoolean(HaConnector.KEY_DONE, false)

    /** Панель уже с плитками (восстановлена из файла) — предлагать нечего. */
    private fun afterLogin() = if (StarterTiles.canPropose(this)) Step.ROOMS else Step.PROTECT

    private fun rooms() {
        content.addView(ModernUi.title(this, "Что показать на панели"))
        content.addView(ModernUi.body(this, "Отмечено то, что нашлось в комнатах Home Assistant. Снимите лишнее — потом всё можно " +
            "изменить. Ничего не переключается: создаются только плитки."))
        val status = ModernUi.statusRow(this, ModernUi.Status.PROGRESS, "Читаю комнаты и устройства…").apply { id = R.id.setupRoomsStatus }
        content.addView(status)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "setup_rooms" }
        content.addView(list)
        buttons(ModernUi.secondaryButton(this, "Назад") { show(Step.SERVER) },
            ModernUi.secondaryButton(this, "Пропустить") { show(Step.PROTECT) }.apply { tag = "setup_rooms_skip" },
            ModernUi.primaryButton(this, "Создать плитки") { createTiles() }.apply { tag = "setup_rooms_next"; isEnabled = false })
        proposal?.let { renderRooms(it) } ?: loadRooms()
    }

    private fun loadRooms() {
        val run = ++reading
        val read = readHome
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { StarterPlan.propose(read()) } }
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
        return StarterPlan.layout(p, selected, w, h, resources.displayMetrics.density, GateStore.load(this))
    }

    private fun renderRooms(p: StarterPlan.Proposal) {
        val list = content.findViewWithTag<LinearLayout>("setup_rooms") ?: return
        val (w, h) = panelSize()
        val selected = chosen ?: StarterPlan.defaults(p, w, h, resources.displayMetrics.density).also { chosen = it }
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
        show(Step.PROTECT)
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
        if (StarterTiles.untouched(this)) content.addView(ModernUi.secondaryButton(this, "Убрать предложенные плитки") {
            StarterTiles.undo(this)
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
        if (connected() && step >= Step.ROOMS) {
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
        private const val STATE_CHOSEN = "setup_chosen"
        private const val STATE_EXPANDED = "setup_expanded"
        private const val KEY_STEP = "setup_step"
        private const val KEY_STEP_AT = "setup_step_at"
        private const val RESUME_MS = 60 * 60_000L
        private const val NOTHING_MS = 8_000L

        /** Шаг прерванного мастера после входа (не старше часа); null — мастер не прерывался. */
        internal fun resumeStep(context: Context): Step? {
            val prefs = context.getSharedPreferences(HaConnector.PREFS, Context.MODE_PRIVATE)
            val age = System.currentTimeMillis() - prefs.getLong(KEY_STEP_AT, 0)
            if (age !in 0..RESUME_MS) return null
            return Step.entries.firstOrNull { it.name == prefs.getString(KEY_STEP, null) && it >= Step.ROOMS }
        }
    }
}
