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
    private val listener: () -> Unit = { renderStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GateHub.attach(applicationContext)
        val all = GateHub.gates(this)
        original = intent.getStringExtra(EXTRA_GATE)?.let { id -> all.firstOrNull { it.id == id } }
        draft = savedInstanceState?.getString(STATE_DRAFT)?.let { json -> Gate.fromJson(org.json.JSONObject(json)) }
            ?: original ?: Gate(GateStore.newId(all), if (all.any { it.name == "Калитка" }) "Ворота" else "Калитка", Gate.Kind.RELAY,
                icon = if (all.any { it.name == "Калитка" }) Gate.Icon.GATE else Gate.Icon.WICKET)
        setContentView(build())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        kit.loadCachedEntities()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DRAFT, draft.toJson().toString())
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
        renderStatus()
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
            !GateLogic.fits(draft.kind, Gate.ROLE_OPEN, draft.open) -> "Выберите устройство — без него кнопок не будет."
            status.position == GateStatus.Position.UNKNOWN -> "Сейчас: положение неизвестно" +
                if (draft.sensor.isBlank()) " — без датчика будет только «команда принята» или «сработало»." else "."
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
        if (!draft.ready) { Toast.makeText(this, "Сначала выберите устройство «Открыть»", Toast.LENGTH_SHORT).show(); return }
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

    internal val isDirty get() = draft != (original ?: Gate(draft.id, "", draft.kind))

    internal fun save(): Boolean {
        if (draft.name.isBlank()) { Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show(); return false }
        if (!draft.ready) { Toast.makeText(this, "Выберите устройство «Открыть» для этого типа", Toast.LENGTH_SHORT).show(); return false }
        if (!requireAdmin()) return false
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
    }
}
