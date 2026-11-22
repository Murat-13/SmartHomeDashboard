package com.example.smarthomedashboard

import android.annotation.SuppressLint
import android.content.Context
import android.widget.LinearLayout

/**
 * Кнопки проходов (калитки и ворота, docs/gate-tz.md) — одни и те же в раскрытой плитке домофона или камеры и в окне
 * звонка. По кнопке на действие прохода ([GateLogic.actions]): «Открыть» и «Закрыть» — удержанием 1 с, «Стоп» — сразу.
 * Подпись, подсказка и цвет — из [GateHub]: где проход сейчас и чем кончилась команда.
 */
@SuppressLint("ViewConstructor")
internal class GateButtons(context: Context, private val vertical: Boolean) : LinearLayout(context) {
    /** Нажали кнопку — исполнить (PIN проверяет хозяин экрана, [GatePin]). */
    var onAction: ((Gate, Gate.Action) -> Unit)? = null
    /** Любое касание — экран не сворачивать и не закрывать. */
    var onTouch: (() -> Unit)? = null

    /** Проходы плитки по id: сами проходы перечитываются из [GateHub] — правка в настройках видна сразу. */
    private var ids: List<String> = emptyList()
    private var gates: List<Gate> = emptyList()
    private val buttons = LinkedHashMap<Pair<String, Gate.Action>, HoldButton>()
    private var buttonWidth = 0
    private var buttonHeight = (64 * resources.displayMetrics.density).toInt()
    private var gap = (10 * resources.displayMetrics.density).toInt()
    private var textPx = 23f * resources.displayMetrics.density / 1.5f
    private val listener: () -> Unit = { rebuild() }

    init {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        tag = "gate_buttons"
        visibility = GONE
    }

    /** Кнопки и их подписи по проходам, которые показывает эта плитка. */
    fun bind(list: List<Gate>) {
        ids = list.map { it.id }
        rebuild()
    }

    val shown get() = buttons.values.toList()

    /** Размеры (раскрытая карточка масштабируется; в окне звонка — по ширине панели). [width] 0 — во всю ширину. */
    fun applySize(width: Int, height: Int, text: Float, spacing: Int) {
        buttonWidth = width; buttonHeight = height; textPx = text; gap = spacing
        buttons.values.forEachIndexed { i, b -> b.layoutParams = params(i); b.textPx = text }
        requestLayout()
    }

    /** В окне звонка много кнопок (ворота: открыть, закрыть, пешеходный) — каждая чуть ниже, чтобы окно поместилось. */
    private fun params(index: Int, count: Int = buttons.size) = if (vertical)
        LayoutParams(LayoutParams.MATCH_PARENT, if (count > 2) buttonHeight * 3 / 4 else buttonHeight).apply { if (index > 0) topMargin = gap }
    else LayoutParams(if (buttonWidth > 0) buttonWidth else LayoutParams.WRAP_CONTENT, buttonHeight).apply { if (index > 0) leftMargin = gap }

    private fun rebuild() {
        gates = ids.mapNotNull { GateHub.gate(it, context) }.filter { it.ready }
        val wanted = gates.flatMap { gate -> GateHub.actions(gate).map { gate.id to it } }
        if (wanted != buttons.keys.toList()) {
            removeAllViews()
            buttons.clear()
            wanted.forEachIndexed { i, key ->
                val gate = gates.first { it.id == key.first }
                val button = HoldButton(context, "", icon(gate, key.second)).apply {
                    tag = "gate_${gate.id}_${key.second.name.lowercase()}"
                    instant = GateLogic.instant(key.second)
                    idleAccent = accent(key.second)
                    textPx = this@GateButtons.textPx
                    onTouchStart = { onTouch?.invoke() }
                    onComplete = { press(key.first, key.second) }
                }
                buttons[key] = button
                addView(button, params(i, wanted.size))
            }
            visibility = if (buttons.isEmpty()) GONE else VISIBLE
        }
        refresh()
    }

    /** Значок по действию: открыть — открытый замок, закрыть — закрытый, «Стоп» — стоп, пешеходный — человек. */
    private fun icon(gate: Gate, action: Gate.Action) = when (action) {
        Gate.Action.CLOSE -> R.drawable.ic_gate_lock
        Gate.Action.STOP -> R.drawable.ic_gate_stop
        Gate.Action.PEDESTRIAN -> R.drawable.ic_door_person
        else -> gate.icon.drawable
    }

    /** Цвет в покое: открыть — зелёный (как звонок), закрыть — спокойный синий, «Стоп» — янтарный. */
    private fun accent(action: Gate.Action) = when (action) {
        Gate.Action.CLOSE -> DoorbellFrame.NEUTRAL
        Gate.Action.STOP -> DoorbellFrame.PERSON
        else -> null
    }

    /** Строка шире места: кнопки делят его поровну (у ворот бывает четыре кнопки). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        if (!vertical && buttonWidth > 0 && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && buttons.isNotEmpty()) {
            val n = buttons.size
            val each = minOf(buttonWidth, (available - gap * (n - 1)) / n).coerceAtLeast(1)
            buttons.values.forEach { b -> (b.layoutParams as LayoutParams).let { if (it.width != each) { it.width = each } } }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun press(gateId: String, action: Gate.Action) {
        val gate = gates.firstOrNull { it.id == gateId } ?: return
        val op = GateHub.op(gateId)
        // «Подтверждения нет» снимается касанием; повтор — только новым удержанием (импульсное реле не дёргаем дважды).
        if (op != null && !op.busy && op.action == action && op.phase == GateHub.Phase.UNCONFIRMED) { GateHub.dismiss(gateId); return }
        onAction?.invoke(gate, action)
    }

    private fun refresh() {
        for ((key, button) in buttons) {
            val gate = gates.firstOrNull { it.id == key.first } ?: continue
            val action = key.second
            val status = GateHub.status(gate)
            val op = GateHub.op(gate.id)?.takeIf { it.action == action }
            button.label = GateLogic.label(gate, action, status)
            button.isEnabled = status.position != GateStatus.Position.OFFLINE
            button.state = when (op?.phase) {
                GateHub.Phase.SENDING -> DoorbellRingWindow.Gate.SENDING
                GateHub.Phase.WAITING -> DoorbellRingWindow.Gate.WAITING
                GateHub.Phase.DONE, GateHub.Phase.REACTED -> DoorbellRingWindow.Gate.OPENED
                GateHub.Phase.UNCONFIRMED -> DoorbellRingWindow.Gate.UNCONFIRMED
                GateHub.Phase.FAILED -> DoorbellRingWindow.Gate.FAILED
                null -> DoorbellRingWindow.Gate.IDLE
            }
            button.stateCaption = op?.let { caption(gate, it, status) }
            button.stateHint = hint(action, op, status, primary = GateHub.actions(gate).firstOrNull() == action)
        }
    }

    private fun caption(gate: Gate, op: GateHub.Op, status: GateStatus): String = when (op.phase) {
        GateHub.Phase.SENDING -> "Отправляю команду…"
        GateHub.Phase.WAITING -> when (op.action) {
            Gate.Action.OPEN, Gate.Action.PEDESTRIAN -> "Открываю…"
            Gate.Action.CLOSE -> if (gate.kind == Gate.Kind.LOCK) "Запираю…" else "Закрываю…"
            Gate.Action.STOP -> "Останавливаю…"
            Gate.Action.STEP -> "Жду ответа…"
        }
        GateHub.Phase.DONE -> when (op.action) {
            Gate.Action.STOP -> "Остановлено"
            else -> GateLogic.statusText(status).ifBlank { "Готово" }.replaceFirstChar { it.uppercase() }
        }
        GateHub.Phase.REACTED -> "Сработало"
        GateHub.Phase.UNCONFIRMED -> "Подтверждения нет"
        GateHub.Phase.FAILED -> "Не удалось"
    }

    private fun hint(action: Gate.Action, op: GateHub.Op?, status: GateStatus, primary: Boolean): String? = when (op?.phase) {
        GateHub.Phase.REACTED -> "реле сработало, положение неизвестно — проверьте"
        GateHub.Phase.UNCONFIRMED -> "команда принята · коснитесь, чтобы снять"
        GateHub.Phase.FAILED -> op.detail.ifBlank { "попробуйте ещё раз" } + if (GateLogic.instant(action)) "" else " · удерживайте, чтобы повторить"
        GateHub.Phase.SENDING, GateHub.Phase.WAITING, GateHub.Phase.DONE -> null
        // Где проход и «удерживайте» — только на главной кнопке прохода: на каждой кнопке это лишний шум.
        null -> if (!primary) "" else {
            val where = GateLogic.statusText(status).takeIf { it.isNotBlank() }
            when {
                status.position == GateStatus.Position.OFFLINE -> where
                where == null -> if (GateLogic.instant(action)) "" else "удерживайте 1 секунду"
                // В строке раскрытой плитки места мало: только где проход («держите…» видно при нажатии).
                !vertical || GateLogic.instant(action) -> where
                else -> "$where · удерживайте"
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GateHub.listen(listener)
        rebuild()
    }

    override fun onDetachedFromWindow() {
        GateHub.unlisten(listener)
        super.onDetachedFromWindow()
    }
}
