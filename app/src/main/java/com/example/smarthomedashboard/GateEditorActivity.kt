package com.example.smarthomedashboard

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import android.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * Один проход — калитка или ворота (docs/gate-tz.md): название, тип (под тип — нужные устройства и понятные подсказки),
 * датчик положения, ожидание подтверждения и «Проверить». Пишется только по «Сохранить» (как другие редакторы).
 */
class GateEditorActivity : PinProtectedActivity() {
    private val kit = ModernEditorKit(this) { TileEditorPreviewSource.states?.value ?: MainUiState() }
    private var original: Gate? = null
    internal var draft = Gate(id = "", name = "Калитка", kind = Gate.Kind.RELAY)
        private set
    private lateinit var column: LinearLayout
    private lateinit var devices: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var testResult: TextView
    private val kindCards = LinkedHashMap<Gate.Kind, LinearLayout>()
    private lateinit var link: LinearLayout
    private lateinit var open: LinearLayout
    private lateinit var phone: LinearLayout
    /** Поля связи напрямую сохраняются, даже если на время выбрать «только Home Assistant». */
    private var directDraft = Gate.Direct()
    /** Новый пароль устройства (null — не меняли). Пишется в зашифрованное хранилище только по «Сохранить». */
    private var newPassword: String? = null
    private var probeResult: TextView? = null
    private val listener: () -> Unit = { renderStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GateHub.attach(applicationContext)
        val all = GateHub.gates(this)
        original = intent.getStringExtra(EXTRA_GATE)?.let { id -> all.firstOrNull { it.id == id } }
        draft = savedInstanceState?.getString(STATE_DRAFT)?.let { json -> Gate.fromJson(org.json.JSONObject(json)) }
            ?: original ?: Gate(GateStore.newId(all), if (all.any { it.name == "Калитка" }) "Ворота" else "Калитка", Gate.Kind.RELAY,
                icon = if (all.any { it.name == "Калитка" }) Gate.Icon.GATE else Gate.Icon.WICKET)
        directDraft = draft.direct ?: Gate.Direct()
        newPassword = savedInstanceState?.getString(STATE_PASSWORD)
        newPassword?.let { GateHub.passwordOverride[draft.id] = it }
        setContentView(build())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        kit.loadCachedEntities()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DRAFT, draft.toJson().toString())
        newPassword?.let { outState.putString(STATE_PASSWORD, it) }
    }

    override fun onResume() {
        super.onResume()
        GateHub.listen(listener)
        renderStatus()
    }

    override fun onPause() {
        GateHub.unlisten(listener)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) GateHub.passwordOverride.remove(draft.id)
        kit.dispose()
        super.onDestroy()
    }

    private fun build(): View {
        val pad = resources.getDimensionPixelSize(R.dimen.modern_space_l)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        column.addView(ModernUi.header(this, if (original == null) "Новый проход" else "Проход"))
        column.addView(ModernUi.secondary(this, "Калитка, ворота или дверь, которую можно открыть с панели. Кнопки появятся у тех " +
            "плиток домофона и камеры, где проход отмечен (в редакторе плитки — «Калитки и ворота»)."))

        val name = ModernUi.input(this, draft.name, "Например: Калитка").apply { tag = "gate_name" }
        name.addTextChangedListener(kit.watcher { draft = draft.copy(name = it.trim()) })
        // Значок прохода пока один на все (Gate.Icon) — выбора на экране нет, чтобы не обещать того, чего нет.
        column.addView(gap()); column.addView(ModernUi.section(this, "Название", name))

        val kinds = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Gate.Kind.entries.forEach { kind -> kinds.addView(kindCard(kind)) }
        column.addView(gap()); column.addView(ModernUi.section(this, "Тип", kinds))

        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(gap()); column.addView(ModernUi.section(this, "Устройства", devices))

        link = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "gate_link" }
        column.addView(gap()); column.addView(ModernUi.section(this, "Связь", link))

        val confirm = ModernUi.input(this, draft.confirmSeconds.toString(), "15").apply {
            tag = "gate_confirm"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        confirm.addTextChangedListener(kit.watcher { text ->
            text.toIntOrNull()?.takeIf { it in Gate.CONFIRM_RANGE }?.let { draft = draft.copy(confirmSeconds = it) }
        })
        statusLine = ModernUi.body(this, "").apply { tag = "gate_status" }
        column.addView(gap()); column.addView(ModernUi.section(this, "Подтверждение",
            entityRow(Gate.ROLE_SENSOR, "Датчик положения (рекомендуется)",
                "binary_sensor двери или калитки: «вкл» — открыто. Только датчик честно скажет, что калитку открыли") { draft.sensor },
            ModernUi.body(this, "Ждать подтверждения, с (${Gate.CONFIRM_RANGE.first}–${Gate.CONFIRM_RANGE.last}; у ворот — время хода)"), confirm,
            statusLine))

        open = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "gate_open_section" }
        column.addView(gap()); column.addView(ModernUi.section(this, "Если оставили открытым", open))
        phone = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(gap()); column.addView(ModernUi.section(this, "Телефон", phone))

        testResult = ModernUi.caption(this, "").apply { tag = "gate_test_result" }
        column.addView(gap()); column.addView(ModernUi.section(this, "Проверка",
            ModernUi.secondary(this, "Отправит команду «Открыть» — калитка откроется по-настоящему."),
            ModernUi.secondaryButton(this, "Проверить: открыть") { test() }.apply { tag = "gate_test" }, testResult))

        renderKind()
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@GateEditorActivity, R.color.modern_background))
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@GateEditorActivity, R.color.modern_background))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footer())
        }
    }

    private fun kindCard(kind: Gate.Kind): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(14), kit.dp(10), kit.dp(14), kit.dp(10))
            tag = "gate_kind_${kind.key}"
            isClickable = true
            contentDescription = kind.title
            setOnClickListener { select(kind) }
            addView(kit.label(kind.title, 16f, bold = true))
            addView(kit.label(kind.hint, 13f))
        }
        kindCards[kind] = card
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = kit.dp(6) }
        }
    }

    private fun select(kind: Gate.Kind) {
        if (kind == draft.kind) return
        // Сущности прошлого типа к новому не подходят — сбрасываются (датчик положения остаётся).
        draft = draft.copy(kind = kind, open = draft.open.takeIf { GateLogic.fits(kind, Gate.ROLE_OPEN, it) }.orEmpty(),
            close = "", pedestrian = "", lockButton = false,
            confirmSeconds = if (kind == Gate.Kind.COVER || kind == Gate.Kind.STEP) maxOf(draft.confirmSeconds, 45) else draft.confirmSeconds)
        renderKind()
    }

    private fun renderKind() {
        kindCards.forEach { (kind, card) ->
            val on = kind == draft.kind
            card.background = BatteryModernCard.rounded(if (on) Color.argb(90, 95, 168, 211) else Color.argb(40, 30, 44, 56),
                kit.dp(12).toFloat(), Color.argb(if (on) 220 else 70, 95, 168, 211))
            card.isSelected = on
        }
        devices.removeAllViews()
        if (draft.directOnly) devices.addView(ModernUi.secondary(this, "Связь — напрямую: устройства Home Assistant необязательны " +
            "(датчик положения из Home Assistant пригодится, когда он на связи)."))
        when (draft.kind) {
            Gate.Kind.RELAY -> devices.addView(entityRow(Gate.ROLE_OPEN, "Реле или кнопка «Открыть»", "switch, button или script") { draft.open })
            Gate.Kind.LOCK -> {
                devices.addView(entityRow(Gate.ROLE_OPEN, "Замок", "lock") { draft.open })
                devices.addView(ModernUi.switch(this, "Кнопка «Запереть»", draft.lockButton) { draft = draft.copy(lockButton = it) }
                    .apply { tag = "gate_lock_button" })
            }
            Gate.Kind.COVER -> {
                devices.addView(entityRow(Gate.ROLE_OPEN, "Привод ворот", "cover: «Открыть», «Закрыть», «Стоп»") { draft.open })
                devices.addView(entityRow(Gate.ROLE_PEDESTRIAN, "Пешеходный проход (необязательно)",
                    "реле, кнопка или сценарий частичного открытия") { draft.pedestrian })
            }
            Gate.Kind.STEP -> devices.addView(entityRow(Gate.ROLE_OPEN, "Реле «шаг»", "switch, button или script; подпись кнопки — по датчику положения") { draft.open })
            Gate.Kind.SCRIPT -> {
                devices.addView(entityRow(Gate.ROLE_OPEN, "Сценарий «Открыть»", "script, automation или button") { draft.open })
                devices.addView(entityRow(Gate.ROLE_CLOSE, "Сценарий «Закрыть» (необязательно)", "script, automation или button") { draft.close })
            }
        }
        renderLink()
        renderOpen()
        renderStatus()
    }

    // ---- «Оставили открытым», «Закрыть само» и кнопка в уведомлении (решения 5 и 8) ----

    private fun renderOpen() {
        if (!::open.isInitialized) return
        open.removeAllViews()
        open.addView(field("Напомнить, если открыто дольше, мин (0 — не напоминать)", draft.remindMinutes.toString(),
            Gate.DEFAULT_REMIND.toString(), "gate_remind", number = true) { v ->
            v.toIntOrNull()?.takeIf { it == 0 || it in Gate.MINUTES_RANGE }?.let { m -> draft = draft.copy(remindMinutes = m) }
        })
        open.addView(ModernUi.secondary(this, "Голосом на панели и красным на плитке; уведомлением — на телефон, выбранный для " +
            "уведомлений о звонке. Нужен датчик положения (или замок, привод, вход Shelly), иначе неизвестно, открыто ли."))
        val kind = GateLogic.autoCloseKind(draft)
        if (kind == null) {
            open.addView(ModernUi.secondary(this, when (draft.kind) {
                Gate.Kind.RELAY -> "«Закрыть само» не нужно: защёлку закрывает доводчик двери."
                Gate.Kind.STEP -> "«Закрыть само» у привода «шаг» нет: одна кнопка вслепую может открыть вместо закрытия."
                else -> "«Закрыть само» — только с датчиком положения: иначе не проверить, закрылось ли."
            }).apply { tag = "gate_auto_close_unavailable" })
            if (draft.autoClose) draft = draft.copy(autoClose = false)
        } else {
            open.addView(ModernUi.switch(this, if (draft.kind == Gate.Kind.LOCK) "Запереть само" else "Закрыть само", draft.autoClose) { on ->
                if (!on) { draft = draft.copy(autoClose = false); renderOpen(); return@switch }
                // Включение — только с предупреждением: приложение не устройство безопасности (решение 8, EN 12453).
                kit.show(ModernUi.dangerDialog(this, if (draft.kind == Gate.Kind.LOCK) "Запирать само?" else "Закрывать ворота само?",
                    if (draft.kind == Gate.Kind.LOCK) "Замок запрётся, когда открыт дольше заданного времени и дверь закрыта." else
                        "Ворота закроются, если открыты дольше заданного времени и камера прохода 30 секунд никого не видит. " +
                        "Это допустимо только с фотоэлементами и ограничением усилия на самом приводе (EN 12453) — " +
                        "приложение не устройство безопасности. Надёжнее — автозакрытие в самом приводе. Без камеры, где " +
                        "отмечен этот проход, ворота само не закроются.", "Включить") {
                    draft = draft.copy(autoClose = true); renderOpen()
                }.apply { setOnCancelListener { renderOpen() } })
                renderOpen()
            }.apply { tag = "gate_auto_close" })
            if (draft.autoClose) {
                open.addView(field("Через, мин", draft.autoCloseMinutes.toString(), Gate.DEFAULT_REMIND.toString(), "gate_auto_close_minutes",
                    number = true) { v -> v.toIntOrNull()?.takeIf { it in Gate.MINUTES_RANGE }?.let { m -> draft = draft.copy(autoCloseMinutes = m) } })
                open.addView(ModernUi.secondary(this, "Одна попытка; не закрылось — напоминание «не удалось закрыть». Исполняет " +
                    "планшет, на котором выбран телефон для уведомлений о звонке."))
            }
        }
        phone.removeAllViews()
        phone.addView(ModernUi.switch(this, "Кнопка «Открыть» в уведомлении о звонке", draft.phone) { on -> draft = draft.copy(phone = on) }
            .apply { tag = "gate_phone" })
        phone.addView(ModernUi.secondary(this, "На телефоне, выбранном у плитки домофона («Когда никто не подошёл»). Кнопка " +
            "работает, пока окно звонка открыто, и один раз." + if (draft.kind == Gate.Kind.COVER || draft.kind == Gate.Kind.STEP)
            " Ворот с телефона не видно — открывайте, только если уверены, что проезд свободен." else ""))
    }

    // ---- Связь: Home Assistant, напрямую или Home Assistant с запасным путём (этап 4) ----

    private enum class LinkMode(val key: String, val title: String, val hint: String) {
        HA("ha", "Через Home Assistant", "как другие устройства панели"),
        FALLBACK("fallback", "Home Assistant, без него — напрямую", "если Home Assistant недоступен, панель откроет сама"),
        DIRECT("direct", "Только напрямую", "Shelly или свой HTTP-запрос в домашней сети, без Home Assistant")
    }

    private val linkMode get() = when {
        draft.link == Gate.Link.DIRECT -> LinkMode.DIRECT
        draft.direct != null -> LinkMode.FALLBACK
        else -> LinkMode.HA
    }

    private fun setLinkMode(mode: LinkMode) {
        draft = when (mode) {
            LinkMode.HA -> draft.copy(link = Gate.Link.HA, direct = null)
            LinkMode.FALLBACK -> draft.copy(link = Gate.Link.HA, direct = directDraft)
            LinkMode.DIRECT -> draft.copy(link = Gate.Link.DIRECT, direct = directDraft)
        }
        renderKind()
    }

    private fun setDirect(change: (Gate.Direct) -> Gate.Direct) {
        directDraft = change(directDraft)
        if (draft.direct != null) draft = draft.copy(direct = directDraft)
        renderStatus()
    }

    private fun choice(title: String, hint: String, on: Boolean, tag: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(kit.dp(14), kit.dp(10), kit.dp(14), kit.dp(10))
        this.tag = tag
        isClickable = true
        isSelected = on
        contentDescription = title
        setOnClickListener { onClick() }
        addView(kit.label(title, 15f, bold = true))
        if (hint.isNotBlank()) addView(kit.label(hint, 13f))
        background = BatteryModernCard.rounded(if (on) Color.argb(90, 95, 168, 211) else Color.argb(40, 30, 44, 56),
            kit.dp(12).toFloat(), Color.argb(if (on) 220 else 70, 95, 168, 211))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = kit.dp(6) }
    }

    private fun field(label: String, value: String, hint: String, tag: String, number: Boolean = false, password: Boolean = false,
                      onChange: (String) -> Unit): View {
        val input = ModernUi.input(this, value, hint, password).apply {
            this.tag = tag
            if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        input.addTextChangedListener(kit.watcher(onChange))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, kit.dp(8), 0, 0)
            addView(ModernUi.body(this@GateEditorActivity, label))
            addView(input)
        }
    }

    private fun renderLink() {
        link.removeAllViews()
        probeResult = null
        if (!draft.directPossible) {
            link.addView(ModernUi.secondary(this, "Замок — только через Home Assistant: у умных замков нет общего способа связи напрямую."))
            return
        }
        LinkMode.entries.forEach { mode ->
            link.addView(choice(mode.title, mode.hint, mode == linkMode, "gate_link_${mode.key}") { if (mode != linkMode) setLinkMode(mode) })
        }
        if (linkMode == LinkMode.HA) return
        val d = directDraft
        val protocols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Gate.Direct.Protocol.entries.forEach { p ->
            protocols.addView(choice(p.title, "", p == d.protocol, "gate_proto_${p.key}") {
                if (p != directDraft.protocol) { setDirect { it.copy(protocol = p) }; renderKind() }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    topMargin = kit.dp(10); if (p.ordinal > 0) marginStart = kit.dp(8)
                }
            })
        }
        link.addView(protocols)
        when (d.protocol) {
            Gate.Direct.Protocol.SHELLY -> {
                link.addView(field("Адрес устройства в домашней сети", d.host, "например 192.168.1.50", "gate_direct_host") { v ->
                    setDirect { it.copy(host = v.trim()) }
                })
                val two = draft.kind == Gate.Kind.COVER
                link.addView(field(if (two) "Номер привода (обычно 0)" else "Номер реле (0 — первое, 1 — второе)", d.channel.toString(), "0",
                    "gate_direct_channel", number = true) { v -> v.toIntOrNull()?.takeIf { it in 0..3 }?.let { c -> setDirect { it.copy(channel = c) } } })
                link.addView(field("Датчик положения на входе Shelly (номер входа; пусто — нет)", d.input.takeIf { it >= 0 }?.toString().orEmpty(),
                    "нет", "gate_direct_input", number = true) { v ->
                    setDirect { it.copy(input = v.toIntOrNull()?.takeIf { n -> n in 0..3 } ?: -1) }
                })
                if (!two) link.addView(field("Импульс, с (${Gate.Direct.PULSE_RANGE.first}–${Gate.Direct.PULSE_RANGE.last}; " +
                    "реле выключится само, даже если панель пропадёт)", d.pulseSeconds.toString(), Gate.Direct.DEFAULT_PULSE.toString(),
                    "gate_direct_pulse", number = true) { v ->
                    v.toIntOrNull()?.takeIf { it in Gate.Direct.PULSE_RANGE }?.let { p -> setDirect { it.copy(pulseSeconds = p) } }
                })
                link.addView(passwordField("Пароль устройства (пользователь admin; пусто — без пароля)"))
                val result = ModernUi.caption(this, "").apply { tag = "gate_probe_result" }
                probeResult = result
                link.addView(ModernUi.secondaryButton(this, "Найти устройство") { probe() }.apply { tag = "gate_probe" })
                link.addView(result)
            }
            Gate.Direct.Protocol.HTTP -> {
                link.addView(ModernUi.secondary(this, "Адрес, который открывает калитку: его даёт инструкция контроллера. Логин и пароль, " +
                    "если нужны, — в адресе (http://логин:пароль@…) или полем ниже."))
                link.addView(field("Адрес «Открыть»", d.openUrl, "http://…", "gate_direct_open") { v -> setDirect { it.copy(openUrl = v.trim()) } })
                if (draft.kind == Gate.Kind.COVER || draft.kind == Gate.Kind.SCRIPT) {
                    link.addView(field("Адрес «Закрыть» (необязательно)", d.closeUrl, "http://…", "gate_direct_close") { v ->
                        setDirect { it.copy(closeUrl = v.trim()) }
                    })
                }
                link.addView(ModernUi.switch(this, "Отправлять POST (иначе GET)", d.post) { on -> setDirect { it.copy(post = on) } }
                    .apply { tag = "gate_direct_post" })
                link.addView(passwordField("Пароль (необязательно)"))
            }
        }
    }

    private fun passwordField(label: String) = field(label, newPassword.orEmpty(),
        if (newPassword == null && original != null) "не меняется" else "", "gate_direct_password", password = true) { v ->
        newPassword = v
        GateHub.passwordOverride[draft.id] = v
    }

    /** «Найти устройство»: что за Shelly по этому адресу и подходит ли пароль. Только чтение — ничего не переключает. */
    private fun probe() {
        val host = directDraft.host
        val result = probeResult ?: return
        if (host.isBlank()) { result.text = "Введите адрес устройства."; return }
        result.text = "Ищу…"
        val id = draft.id
        Thread {
            val password = GateHub.passwordOverride[id] ?: CameraPasswords.blocking(applicationContext, GateDirect.secretKey(id))
            val info = GateDirect.probe(host, password)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                probeResult?.text = when {
                    info == null -> "Не нашлось: по этому адресу не отвечает Shelly второго поколения (Plus, Pro). Проверьте адрес и сеть."
                    info.gen < 2 -> "Это Shelly первого поколения — пока не поддерживается."
                    !info.authOk -> "Найдено: ${info.model}. Пароль не подходит."
                    else -> "Найдено: ${info.model}" + if (info.authNeeded) " · пароль подходит." else " · без пароля."
                }
            }
        }.apply { isDaemon = true; name = "gate-probe" }.start()
    }

    private val rowTexts = HashMap<String, TextView>()

    private fun entityRow(role: String, label: String, hint: String, value: () -> String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = kit.dp(56)
            background = BatteryModernCard.rounded(Color.argb(50, 30, 44, 56), kit.dp(12).toFloat(), Color.argb(80, 95, 168, 211))
            setPadding(kit.dp(12), kit.dp(6), kit.dp(4), kit.dp(6))
            tag = "gate_row_$role"
            isClickable = true
            contentDescription = label
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(kit.label(label, 15f, bold = true))
        val current = kit.label(describe(value(), hint), 13f)
        rowTexts[role] = current
        texts.addView(current)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(kit.button("✕", "gate_clear_$role") { set(role, ""); current.text = describe("", hint) }.apply {
            contentDescription = "Очистить: $label"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(kit.dp(48), kit.dp(48)))
        row.setOnClickListener {
            kit.pickEntityWhere(value(), { GateLogic.fits(draft.kind, role, it) }) { picked ->
                set(role, picked)
                current.text = describe(picked, hint)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, kit.dp(4), 0, kit.dp(4))
            addView(row)
        }
    }

    private fun describe(id: String, hint: String) = if (id.isBlank()) "не выбрано · $hint" else "${kit.entityName(id) ?: id} · $id"

    internal fun set(role: String, id: String) {
        draft = when (role) {
            Gate.ROLE_OPEN -> draft.copy(open = id)
            Gate.ROLE_CLOSE -> draft.copy(close = id)
            Gate.ROLE_PEDESTRIAN -> draft.copy(pedestrian = id)
            Gate.ROLE_SENSOR -> draft.copy(sensor = id)
            else -> draft
        }
        renderStatus()
    }

    private fun renderStatus() {
        if (!::statusLine.isInitialized) return
        val status = GateHub.status(draft)
        statusLine.text = when {
            draft.directOnly && !draft.hasDirect -> if (directDraft.protocol == Gate.Direct.Protocol.SHELLY)
                "Введите адрес устройства — без него кнопок не будет." else "Введите адрес «Открыть» (http://…) — без него кнопок не будет."
            !draft.directOnly && !GateLogic.fits(draft.kind, Gate.ROLE_OPEN, draft.open) -> "Выберите устройство — без него кнопок не будет."
            status.position == GateStatus.Position.UNREACHABLE -> "Сейчас: устройство не отвечает."
            status.position == GateStatus.Position.UNKNOWN -> "Сейчас: положение неизвестно" +
                if (!draft.hasPositionSensor) " — без датчика будет только «команда принята» или «сработало»." else "."
            else -> "Сейчас: " + GateLogic.statusText(status)
        }
        val op = GateHub.op(draft.id)
        testResult.text = when (op?.phase) {
            null -> ""
            GateHub.Phase.SENDING -> "Отправляю команду…"
            GateHub.Phase.WAITING -> "Команда принята, жду подтверждения…"
            GateHub.Phase.DONE -> "Готово: " + GateLogic.statusText(GateHub.status(draft)).ifBlank { "подтверждено" }
            GateHub.Phase.REACTED -> "Реле сработало; положение неизвестно — проверьте калитку."
            GateHub.Phase.UNCONFIRMED -> "Команда принята, но подтверждения нет. Проверьте калитку и, если нужно, датчик положения."
            GateHub.Phase.FAILED -> "Не удалось: " + op.detail.ifBlank { "попробуйте ещё раз" }
        }
    }

    private fun test() {
        if (!draft.ready) { Toast.makeText(this, if (draft.directOnly) "Сначала заполните связь напрямую" else
            "Сначала выберите устройство «Открыть»", Toast.LENGTH_SHORT).show(); return }
        kit.show(ModernUi.dangerDialog(this, "Открыть «${draft.name}» по-настоящему?",
            "Команда уйдёт на устройство. Убедитесь, что это безопасно.", "Открыть") {
            if (!requireAdmin()) return@dangerDialog
            GateHub.dismiss(draft.id)
            if (!GateHub.run(draft, Gate.Action.OPEN)) renderStatus()
        })
    }

    private fun footer(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(kit.dp(16), kit.dp(8), kit.dp(16), kit.dp(8))
        setBackgroundColor(ModernUi.color(this@GateEditorActivity, R.color.modern_surface))
        if (original != null) addView(ModernUi.dangerButton(this@GateEditorActivity, "Удалить") { confirmDelete() }.apply { tag = "gate_delete" })
        addView(View(this@GateEditorActivity), LinearLayout.LayoutParams(0, 1, 1f))
        addView(ModernUi.secondaryButton(this@GateEditorActivity, "Отмена") { leave() }.apply { tag = "gate_cancel" })
        addView(ModernUi.primaryButton(this@GateEditorActivity, "Сохранить") { save() }.apply { tag = "gate_save" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = kit.dp(12) })
    }

    internal val isDirty get() = newPassword != null || draft != (original ?: Gate(draft.id, "", draft.kind))

    internal fun save(): Boolean {
        if (draft.name.isBlank()) { Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show(); return false }
        if (!draft.ready) { Toast.makeText(this, if (draft.directOnly) "Заполните связь напрямую" else
            "Выберите устройство «Открыть» для этого типа", Toast.LENGTH_SHORT).show(); return false }
        if (!requireAdmin()) return false
        newPassword?.let { CameraPasswords.save(applicationContext, GateDirect.secretKey(draft.id), it) { } }
        val all = GateHub.gates(this)
        if (original == null && all.size >= Gate.MAX) { Toast.makeText(this, "Не больше ${Gate.MAX} проходов", Toast.LENGTH_SHORT).show(); return false }
        GateStore.save(this, if (all.any { it.id == draft.id }) all.map { if (it.id == draft.id) draft else it } else all + draft)
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
        return true
    }

    private fun confirmDelete() {
        val gate = original ?: return
        kit.show(ModernUi.dangerDialog(this, "Удалить «${gate.name}»?",
            "Кнопки этого прохода исчезнут со всех плиток. Устройства в Home Assistant не изменятся.", "Удалить") {
            if (!requireAdmin()) return@dangerDialog
            GateStore.save(this, GateHub.gates(this).filter { it.id != gate.id })
            CameraPasswords.save(applicationContext, GateDirect.secretKey(gate.id), "") { }
            setResult(RESULT_OK)
            finish()
        })
    }

    internal fun leave() {
        if (!isDirty) { finish(); return }
        kit.show(ModernUi.leaveDialog(this, "Настройки «${draft.name.ifBlank { "проход" }}» пока не записаны.", { save() }, { finish() }))
    }

    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, resources.getDimensionPixelSize(R.dimen.modern_space_m)) }

    internal val nameField: EditText get() = column.findViewWithTag("gate_name")
    internal val openPicker: AlertDialog? get() = kit.openPicker

    companion object {
        const val EXTRA_GATE = "gate_id"
        private const val STATE_DRAFT = "gate_draft"
        private const val STATE_PASSWORD = "gate_password"
    }
}
