package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Плитка «Проход» на панели (docs/gate-tz.md, решение 3): кнопка со статусом в стиле Modern — значок, имя, под ним
 * состояние. Удержание 1 с — открыть (главное действие прохода); касание — раскрыть: все кнопки и положение.
 * Одна плитка — один проход из общего списка «Калитки и ворота».
 */
internal object GateTileConfig {
    const val TYPE = "gate_modern"
    const val KEY = "gate_modern"
    const val DEFAULT_WIDTH = 300
    const val DEFAULT_HEIGHT = 150

    private fun json(config: String?) = try { JSONObject(config ?: "{}") } catch (_: Exception) { JSONObject() }

    /** Какой проход показывает плитка ("" — не выбран). */
    fun gateId(config: String?): String = json(config).optJSONObject(KEY)?.optString("gate").orEmpty()

    fun withGate(config: String?, gateId: String): String = json(config).put(KEY, JSONObject().put("gate", gateId)).toString()

    /** Новая плитка: размер кнопки и первый проход из списка (если есть); посторонние ключи сохраняются. */
    fun newTileConfig(existing: String = "{}", firstGate: String? = null): String {
        val json = json(existing)
        if (!json.has("button_size")) json.put("button_size", DEFAULT_WIDTH)
        if (!json.has("widget_height")) json.put("widget_height", DEFAULT_HEIGHT)
        if (!json.has(KEY)) json.put(KEY, JSONObject().put("gate", firstGate.orEmpty()))
        return json.toString()
    }
}

/** Плитка «Проход»: свёрнутая — одна большая кнопка; раскрытая — имя, положение и все кнопки прохода. */
internal class GateTileCard(context: Context, val expanded: Boolean) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    /** Свёрнутая: удержание — главное действие, касание — раскрыть. */
    val button = HoldButton(context, "", R.drawable.ic_door_unlock).apply {
        tag = "gate_tile_button"
        autoSize = true
    }
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); tag = "gate_tile_title"
    }
    private val status = TextView(context).apply { setTextColor(Color.argb(220, 255, 255, 255)); tag = "gate_tile_status" }
    val buttons = GateButtons(context, vertical = true)
    var gateId: String = ""
        private set
    /** Удержание на свёрнутой плитке: (проход, действие). */
    var onAction: ((Gate, Gate.Action) -> Unit)? = null
        set(value) { field = value; buttons.onAction = value }
    var onTap: (() -> Unit)? = null
        set(value) { field = value; button.onTap = value }
    var onTouch: (() -> Unit)? = null
        set(value) { field = value; buttons.onTouch = value }
    private val listener: () -> Unit = { render() }

    init {
        tag = if (expanded) "gate_expanded" else "gate_compact"
        if (expanded) {
            background = BatteryModernCard.rounded(Color.argb(235, 16, 28, 40), 28 * density, Color.argb(110, 95, 168, 211))
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (24 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            column.addView(title)
            column.addView(status, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * density).toInt(); bottomMargin = (16 * density).toInt()
            })
            column.addView(buttons, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        } else {
            addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            button.onComplete = { gate()?.let { g -> primary(g)?.let { onAction?.invoke(g, it) } } }
        }
    }

    private fun gate() = GateHub.gate(gateId, context)?.takeIf { it.ready }

    /** Главное действие плитки: «Открыть» (или «шаг» у привода с одной кнопкой). */
    private fun primary(gate: Gate) = GateHub.actions(gate).let { list ->
        list.firstOrNull { it == Gate.Action.OPEN } ?: list.firstOrNull { it == Gate.Action.STEP } ?: list.firstOrNull()
    }

    fun bind(gateId: String, editMode: Boolean = false) {
        this.gateId = gateId
        button.passThrough = editMode
        gate()?.let { buttons.bind(listOf(it)) } ?: buttons.bind(emptyList())
        render()
    }

    /** Размер текста и кнопок — от размера карточки (на всех экранах одно правило). */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!expanded || w <= 0) return
        val unit = minOf(w, h * 2) / 600f
        title.textSize = (30 * unit).coerceIn(18f, 40f)
        status.textSize = (20 * unit).coerceIn(13f, 26f)
        buttons.applySize(0, (h * 0.16f).toInt().coerceAtLeast((56 * density).toInt()), (12 * density).toInt())
    }

    fun render() {
        val gate = gate()
        if (gate == null) {
            button.label = "Выберите проход"
            button.stateCaption = null
            button.stateHint = "в настройках плитки"
            button.isEnabled = true
            title.text = "Проход не выбран"
            status.text = ""
            return
        }
        val now = GateHub.status(gate)
        val left = GateWatch.isLeftOpen(gate.id)
        title.text = gate.name
        status.text = statusLine(gate, now, left)
        if (expanded) return
        val action = primary(gate) ?: Gate.Action.OPEN
        GateButtons.paint(button, gate, action, primary = true, roomy = true)
        // Плитка — кнопка со статусом: крупно имя прохода, под ним — где он сейчас («Закрыта», «Открываются…»).
        if (button.stateCaption == null) button.label = gate.name
        if (button.state == DoorbellRingWindow.Gate.IDLE) button.stateHint = statusLine(gate, now, left).ifBlank { "удерживайте — открыть" }
        button.idleAccent = when {
            left -> DoorbellFrame.OFFLINE
            now.open -> DoorbellFrame.RING
            else -> DoorbellFrame.NEUTRAL
        }
    }

    private fun statusLine(gate: Gate, now: GateStatus, left: Boolean): String {
        val text = GateLogic.statusText(now).replaceFirstChar { it.uppercase() }
        val percent = now.percent?.takeIf { gate.kind == Gate.Kind.COVER && now.position != GateStatus.Position.CLOSED }?.let { " · $it %" }.orEmpty()
        return when {
            left && now.open -> "Открыто слишком долго$percent"
            text.isBlank() -> ""
            else -> text + percent
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GateHub.listen(listener)
        render()
    }

    override fun onDetachedFromWindow() {
        GateHub.unlisten(listener)
        super.onDetachedFromWindow()
    }

    companion object {
        fun createTile(context: Context, tileId: String, width: Int, height: Int, isEditMode: Boolean): Pair<FrameLayout, ImageView> {
            val tile = FrameLayout(context).apply {
                layoutParams = LayoutParams(width, height)
                tag = tileId
            }
            tile.addView(GateTileCard(context, expanded = false), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val handle = ImageView(context).apply {
                setImageResource(R.drawable.ic_resize)
                layoutParams = LayoutParams(40, 40).apply { gravity = Gravity.BOTTOM or Gravity.END }
                visibility = if (isEditMode) View.VISIBLE else View.GONE
                setPadding(8, 8, 8, 8)
            }
            tile.addView(handle)
            return tile to handle
        }
    }
}
