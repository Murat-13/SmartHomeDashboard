package com.example.smarthomedashboard

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isEmpty

/**
 * Плитка «Инвертор» (docs/inverter-modern.md): компактная — квадрат со схемой потоков крестом и статусом;
 * раскрытая — схема крупно слева, справа подробности узлов и смена приоритета источника.
 * Оформление — как у «Электропитание Modern» (тёмное тело, оттенок, ореол, свечение, рамка в цвете состояния,
 * плавная смена цвета, пульсация при аварии); анимации только у видимой карточки и освобождаются при отсоединении.
 * Только отображение: команды отдаются через [onPriority], проверку PIN и подтверждение делает панель.
 */
internal class InverterModernCard(
    context: Context,
    val expanded: Boolean,
    private val editor: BatteryModernCard.Editor? = null
) : FrameLayout(context), ModernOverlayCard, ModernHideAware, ModernInteractiveArea {
    private val scaled = mutableListOf<Pair<TextView, Float>>()
    private var scale = 1f
    internal var haloPx = PowerModernCard.haloFor(1f)
        private set
    private val density = resources.displayMetrics.density

    // ---- Оформление: только заранее созданные объекты, onDraw ничего не выделяет. ----
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(9, 14, 20) }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    var accent: Int = PowerModernConfig.NEUTRAL
        private set
    internal var glowLevel = 1f
        private set
    var pulsing = false
        private set
    private var colorAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var targetAccent: Int? = null
    private var wantPulse = false
    private var hiddenBySource = false
    private var windowVisible = true

    // ---- Содержимое ----
    private val icon = ImageView(context).apply { tag = "inv_icon"; setImageResource(R.drawable.ic_power_inverter) }
    private val title = text(if (expanded) 30f else 22f, Color.WHITE, bold = true).apply { tag = "inv_title"; maxLines = 1 }
    private val status = text(if (expanded) 22f else 17f, Color.WHITE, bold = true).apply {
        tag = "inv_status"
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (!expanded) gravity = Gravity.CENTER
    }
    val flow = InverterFlowView(context).apply { tag = "inv_flow" }
    private val divider = View(context)
    val scroll: ScrollView? = if (expanded) FitScrollView(context).apply { tag = "inv_scroll"; isVerticalScrollBarEnabled = false } else null
    private val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "inv_details" }
    private val values = mutableMapOf<String, TextView>()
    private val groupIcons = mutableListOf<TextView>()
    private var structure: String? = null

    // ---- Приоритет источника ----
    /** Выбор варианта приоритета (панель спрашивает подтверждение и PIN). null — только просмотр. */
    var onPriority: ((option: String) -> Unit)? = null
    private val priorityBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "inv_priority" }
    private val priorityCurrent = text(22f, Color.WHITE, bold = true).apply { tag = "inv_priority_current" }
    private val priorityChips = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "inv_priority_chips" }
    private val chipBackgrounds = mutableListOf<Pair<GradientDrawable, Boolean>>()
    private var priorityKey: String? = null

    init {
        tag = if (expanded) "inv_expanded" else "inv_compact"
        setWillNotDraw(false)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        clipToOutline = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val inset = haloPx.toInt()
                outline.setRoundRect(inset, inset, view.width - inset, view.height - inset, radius())
            }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(icon, LinearLayout.LayoutParams(1, 1))
        val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        names.addView(title)
        if (expanded) names.addView(status)
        header.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        editable(header) { it.editHeader() }

        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        left.addView(flow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (!expanded) left.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (scroll == null) {
            addView(left, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f))
            row.addView(divider, LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT))
            val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            right.addView(details)
            priorityBox.addView(text(20f, PowerModernCard.SECONDARY).apply { text = "Приоритет источника" })
            priorityBox.addView(priorityCurrent)
            priorityBox.addView(priorityChips)
            right.addView(priorityBox)
            scroll.addView(right, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f))
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        setAccentNow(PowerModernConfig.NEUTRAL)
        applyScale(1f)
    }

    /**
     * Показания — сразу; цвет — по [look] (уже подтверждённому трекером панели).
     * [pendingPriority] — вариант, отправленный в HA и ещё не подтверждённый фактом.
     */
    fun bind(tileTitle: String, config: InverterModernConfig, readings: BatteryReadings, snapshot: InverterModel.Snapshot,
             look: InverterModel.Look, animate: Boolean = true, pendingPriority: String? = null) {
        binds++
        set(title, tileTitle.ifBlank { InverterModernConfig.DEFAULT_TITLE })
        // Статус не обгоняет цвет: пока новое состояние подтверждается, подпись — от подтверждённого.
        set(status, if (look == snapshot.look) snapshot.status else statusFor(look, snapshot))
        flow.bind(snapshot)
        if (expanded) {
            bindDetails(config, readings)
            bindPriority(config, readings, pendingPriority)
        }
        applyVisual(look.color, look.pulse, animate)
    }

    private fun statusFor(look: InverterModel.Look, snapshot: InverterModel.Snapshot) = when (look) {
        InverterModel.Look.NO_DATA -> "Нет данных"
        InverterModel.Look.FAULT -> "Авария"
        InverterModel.Look.WARNING -> "Предупреждение"
        else -> InverterModel.sources(snapshot.solar, snapshot.grid, snapshot.battery, snapshot.load, snapshot.mode)
    }

    // ---- Раскрытая: подробности узлов ----

    /** Строки по узлам. На панели — только настроенные; в редакторе — все (касание — выбор источника). */
    private fun bindDetails(config: InverterModernConfig, readings: BatteryReadings) {
        val rows = config.rows.filter { editor != null || it.entityId.isNotBlank() }
            .filter { it.role != InverterModernConfig.ROLE_PRIORITY }
        val signature = rows.joinToString("|") { "${it.id}:${it.label}" }
        if (signature != structure) {
            structure = signature
            values.clear()
            groupIcons.clear()
            details.removeAllViews()
            rows.groupBy { InverterModernConfig.node(it) }.forEach { (node, group) ->
                details.addView(groupHeader(node))
                group.forEach { details.addView(detailRow(it)) }
            }
            if (rows.isEmpty()) details.addView(text(22f, PowerModernCard.SECONDARY).apply {
                text = "Показатели не выбраны"
                tag = "inv_empty"
            })
            applyScale(scale)
        }
        rows.forEach { row ->
            val view = values[row.id] ?: return@forEach
            if (row.entityId.isBlank()) {
                set(view, "выбрать источник")
                view.setTextColor(PowerModernCard.SECONDARY)
                return@forEach
            }
            val reading = readings.of(row)
            val text = when {
                row.role == InverterModernConfig.ROLE_MODE && reading is BatteryReading.Value ->
                    InverterModel.parseMode(reading.raw).let { if (it == InverterModel.Mode.UNKNOWN) reading.raw else InverterModel.modeLabel(it) }
                (row.role == InverterModernConfig.ROLE_FAULT || row.role == InverterModernConfig.ROLE_WARNING) &&
                    reading is BatteryReading.Value -> if (InverterModel.isActiveFlag(reading.raw)) reading.raw else "нет"
                else -> BatteryFormat.text(row, reading).let { t ->
                    listOf(t.value, if (reading is BatteryReading.Value) PowerModernCard.unitRu(t.unit) else "")
                        .filter { it.isNotBlank() }.joinToString(" ")
                }
            }
            set(view, text)
            view.setTextColor(Color.WHITE)
        }
    }

    private fun groupHeader(node: InverterModernConfig.Node): View {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "inv_group_${node.name.lowercase()}"
            val p = (10 * scale).toInt()
            setPadding(p, (16 * scale).toInt(), p, (4 * scale).toInt())
        }
        val mark = text(18f, nodeColor(node), bold = true).apply { text = "●" }
        groupIcons += mark
        line.addView(mark, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = (10 * scale).toInt() })
        line.addView(text(22f, Color.WHITE, bold = true).apply { text = node.title })
        return line
    }

    private fun nodeColor(node: InverterModernConfig.Node) = when (node) {
        InverterModernConfig.Node.SOLAR -> InverterFlowView.SOLAR
        InverterModernConfig.Node.GRID -> InverterFlowView.GRID
        InverterModernConfig.Node.BATTERY -> InverterFlowView.BATTERY
        InverterModernConfig.Node.LOAD -> InverterFlowView.HOME
        InverterModernConfig.Node.INVERTER -> accent
    }

    private fun detailRow(row: BatteryModernConfig.Row): View {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "inv_row_${row.id}"
            val p = (8 * scale).toInt()
            setPadding((34 * scale).toInt(), p, (10 * scale).toInt(), p)
        }
        line.addView(text(21f, PowerModernCard.SECONDARY).apply { text = row.label; maxLines = 1 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = text(23f, Color.WHITE, bold = true).apply {
            tag = "inv_value_${row.id}"
            gravity = Gravity.END
            maxLines = 1
        }
        values[row.id] = value
        line.addView(value)
        editable(line) { it.editRow(row.id) }
        return line
    }

    // ---- Раскрытая: приоритет источника ----

    private fun bindPriority(config: InverterModernConfig, readings: BatteryReadings, pending: String?) {
        val row = config.row(InverterModernConfig.ROLE_PRIORITY)
        if (row == null || row.entityId.isBlank()) {
            priorityBox.visibility = if (editor != null) VISIBLE else GONE
            set(priorityCurrent, "выбрать источник")
            priorityCurrent.setTextColor(PowerModernCard.SECONDARY)
            editable(priorityBox) { it.editRow(InverterModernConfig.ROLE_PRIORITY) }
            if (priorityKey != "") { priorityKey = ""; priorityChips.removeAllViews(); chipBackgrounds.clear() }
            return
        }
        priorityBox.visibility = VISIBLE
        editable(priorityBox) { it.editRow(row.id) }
        val reading = readings.of(row)
        val current = (reading as? BatteryReading.Value)?.raw
        val options = (reading as? BatteryReading.Value)?.attributes?.optJSONArray("options")
            ?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }
            .orEmpty()
        set(priorityCurrent, when {
            pending != null -> "Переключение: ${InverterModel.priorityLabel(pending)}…"
            current != null -> InverterModel.priorityLabel(current)
            else -> BatteryFormat.text(row, reading).value
        })
        priorityCurrent.setTextColor(if (pending != null) InverterModel.AMBER else Color.WHITE)
        // Варианты меняются только при смене списка/текущего/ожидания: строим заново (их единицы).
        val key = "${options.joinToString("\u0001")}|$current|$pending|${onPriority != null && editor == null}"
        if (key == priorityKey) return
        priorityKey = key
        priorityChips.removeAllViews()
        chipBackgrounds.clear()
        // Управление — только с панели и при достоверном состоянии (иначе неясно, что меняем).
        val enabled = editor == null && onPriority != null && pending == null && reading is BatteryReading.Value
        options.forEach { option ->
            val selected = option == current
            val background = GradientDrawable().apply { cornerRadius = 18f * scale }
            chipBackgrounds += background to selected
            val chip = text(21f, Color.WHITE, bold = selected).apply {
                tag = "inv_option_$option"
                text = InverterModel.priorityLabel(option).let { if (it == option) it else "$it  ·  $option" }
                this.background = background
                val p = (12 * scale).toInt()
                setPadding(p, p, p, p)
                minHeight = (48 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL
                alpha = if (enabled || selected) 1f else 0.55f
                contentDescription = "Приоритет: ${InverterModel.priorityLabel(option)}" + if (selected) ", выбран" else ""
                if (enabled && !selected) setOnClickListener { onPriority?.invoke(option) }
            }
            priorityChips.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (8 * scale).toInt() })
        }
        if (options.isEmpty() && reading is BatteryReading.Value) {
            priorityChips.addView(text(19f, PowerModernCard.SECONDARY).apply {
                text = "HA не сообщил вариантов (нужна сущность select)"
            })
        }
        applyScale(scale)
    }

    private fun paintChips() {
        val c = accent
        chipBackgrounds.forEach { (background, selected) ->
            background.setColor(if (selected) Color.argb(70, Color.red(c), Color.green(c), Color.blue(c)) else Color.argb(50, 20, 30, 38))
            background.setStroke((1.5f * density).toInt().coerceAtLeast(1),
                Color.argb(if (selected) 220 else 70, Color.red(c), Color.green(c), Color.blue(c)))
        }
    }

    // ---- Цвет и пульсация (как у PowerModernCard) ----

    fun applyVisual(color: Int, pulse: Boolean, animate: Boolean, durationMs: Long = PowerModernConfig.DEFAULT_TRANSITION_MS) {
        if (targetAccent != color) {
            targetAccent = color
            colorAnimator?.cancel()
            colorAnimator = null
            if (!animate || durationMs <= 0 || accent == color || !visiblyShown()) setAccentNow(color)
            else {
                colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), accent, color).apply {
                    duration = durationMs
                    addUpdateListener { if (colorAnimator === it) setAccentNow(it.animatedValue as Int) }
                    start()
                }
            }
        }
        wantPulse = pulse
        updatePulse()
    }

    private fun visiblyShown() = isAttachedToWindow && isShown && windowVisible && !hiddenBySource

    override fun setHiddenBySource(hidden: Boolean) {
        if (hiddenBySource == hidden) return
        hiddenBySource = hidden
        visibilityChanged()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibilityChanged()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        visibilityChanged()
    }

    private fun visibilityChanged() {
        if (!visiblyShown()) colorAnimator?.let { animator ->
            colorAnimator = null
            animator.removeAllUpdateListeners()
            animator.cancel()
            targetAccent?.let { setAccentNow(it) }
        }
        updatePulse()
    }

    private fun updatePulse() {
        val run = wantPulse && visiblyShown()
        if (run == (pulseAnimator != null)) return
        if (run) {
            pulseAnimator = ValueAnimator.ofFloat(1f, 0.45f).apply {
                duration = 500
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    if (pulseAnimator === it) {
                        glowLevel = it.animatedValue as Float
                        invalidate()
                    }
                }
                start()
            }
        } else stopPulse()
        pulsing = pulseAnimator != null
    }

    private fun stopPulse() {
        pulseAnimator?.let {
            pulseAnimator = null
            it.removeAllUpdateListeners()
            it.cancel()
        }
        pulsing = false
        if (glowLevel != 1f) {
            glowLevel = 1f
            invalidate()
        }
    }

    private fun setAccentNow(color: Int) {
        accent = color
        icon.setColorFilter(color)
        status.setTextColor(color)
        flow.accent = color
        divider.setBackgroundColor(Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)))
        paintChips()
        updateShader()
        invalidate()
    }

    private fun updateShader() {
        if (height <= 0) return
        val c = accent
        tintPaint.shader = LinearGradient(haloPx, haloPx, width * 0.35f, height - haloPx,
            Color.argb(92, Color.red(c), Color.green(c), Color.blue(c)),
            Color.argb(22, Color.red(c), Color.green(c), Color.blue(c)), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val r = radius()
        val c = accent
        val halo = haloPx
        if (halo >= 1f) {
            val band = halo / 3f
            glowPaint.strokeWidth = band
            for (i in 1..3) {
                val offset = band * (i - 0.5f)
                glowPaint.color = Color.argb((70 * glowLevel * (4 - i) / 3f).toInt().coerceIn(0, 255),
                    Color.red(c), Color.green(c), Color.blue(c))
                rect.set(halo - offset, halo - offset, w - halo + offset, h - halo + offset)
                canvas.drawRoundRect(rect, r + offset, r + offset, glowPaint)
            }
        }
        rect.set(halo, halo, w - halo, h - halo)
        canvas.drawRoundRect(rect, r, r, basePaint)
        canvas.drawRoundRect(rect, r, r, tintPaint)
        for (i in 3 downTo 1) {
            val stroke = 2.2f * i * scale * density
            glowPaint.strokeWidth = stroke
            glowPaint.color = Color.argb((26 * glowLevel * (4 - i)).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
            rect.set(halo + stroke / 2, halo + stroke / 2, w - halo - stroke / 2, h - halo - stroke / 2)
            canvas.drawRoundRect(rect, r, r, glowPaint)
        }
        val border = (1.4f * scale * density).coerceAtLeast(1.5f)
        borderPaint.strokeWidth = border
        borderPaint.color = Color.argb((235 * glowLevel).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
        rect.set(halo + border / 2, halo + border / 2, w - halo - border / 2, h - halo - border / 2)
        canvas.drawRoundRect(rect, r, r, borderPaint)
    }

    // ---- Адаптивность ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        updateShader()
        val next = scaleFor(w, h)
        if (next != scale) post { applyScale(next) }
    }

    private fun applyScale(next: Float) {
        scale = next
        haloPx = PowerModernCard.haloFor(next)
        scaled.forEach { (view, base) -> view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * next) }
        val pad = (if (expanded) 18 else 14) * next
        (getChildAt(0) as? ViewGroup)?.setPadding((pad + haloPx).toInt(), (pad + haloPx).toInt(),
            (pad + haloPx).toInt(), (pad * 0.8f + haloPx).toInt())
        val iconSide = ((if (expanded) 64 else 34) * next).toInt().coerceAtLeast(1)
        icon.layoutParams = (icon.layoutParams as LinearLayout.LayoutParams).apply {
            width = iconSide; height = iconSide; rightMargin = (10 * next).toInt()
        }
        if (scroll != null) {
            (divider.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.leftMargin = (20 * next).toInt(); it.rightMargin = (20 * next).toInt(); divider.layoutParams = it
            }
            priorityBox.setPadding((10 * next).toInt(), (22 * next).toInt(), (10 * next).toInt(), (10 * next).toInt())
            chipBackgrounds.forEach { it.first.cornerRadius = 18f * next }
        }
        setAccentNow(accent)
        invalidateOutline()
        requestLayout()
    }

    /** Компакт — от квадрата 340; раскрытая — от 1100×700. */
    private fun scaleFor(w: Int, h: Int) = (if (expanded) minOf(w / 1100f, h / 700f) else minOf(w, h) / 340f)
        .coerceIn(0.35f, 2.6f)

    /** Касания вариантов приоритета — свои: раскрытие не сворачивается от них. */
    override fun interactiveAt(windowX: Float, windowY: Float): Boolean {
        if (priorityChips.isEmpty() || !priorityChips.isShown) return false
        // Только видимая часть: варианты, прокрученные за край ScrollView, не перехватывают касания.
        if (!priorityChips.getGlobalVisibleRect(visibleChips)) return false
        return visibleChips.contains(windowX.toInt(), windowY.toInt())
    }
    private val visibleChips = android.graphics.Rect()

    /** Карточка сворачивается или освобождается: панель закрывает своё подтверждение смены приоритета. */
    var onClosing: (() -> Unit)? = null

    override fun collapsing() {
        onClosing?.invoke()
    }

    private fun radius() = if (expanded) 40f * scale.coerceAtLeast(0.6f) else 32f

    // ---- Жизненный цикл ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePulse()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        colorAnimator?.let { animator ->
            colorAnimator = null
            animator.removeAllUpdateListeners()
            animator.cancel()
            targetAccent?.let { setAccentNow(it) }
        }
        super.onDetachedFromWindow()
    }

    override fun dispose() {
        onClosing?.invoke()
        onClosing = null
        onPriority = null
        stopPulse()
        wantPulse = false
        colorAnimator?.let { it.removeAllUpdateListeners(); it.cancel() }
        colorAnimator = null
        fun clear(view: View) {
            view.setOnClickListener(null)
            view.cancelPendingInputEvents()
            if (view is ViewGroup) for (i in 0 until view.childCount) clear(view.getChildAt(i))
        }
        clear(this)
    }

    internal val isColorAnimating get() = colorAnimator?.isRunning == true
    internal val pulseRequested get() = wantPulse
    internal var binds = 0
        private set

    // ---- Вспомогательное ----

    private fun text(base: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        scaled += this to base
    }

    private fun set(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    private fun editable(view: View, action: (BatteryModernCard.Editor) -> Unit) {
        val target = editor ?: return
        view.isClickable = true
        view.setOnClickListener { action(target) }
    }

    companion object {
        /** Плитка панели: основа, как у «Электропитание Modern» (тег, размер, ручка resize). */
        fun createTile(context: Context, tileId: String, width: Int, height: Int, isEditMode: Boolean): Pair<FrameLayout, ImageView> {
            val tile = FrameLayout(context).apply {
                layoutParams = LayoutParams(width, height)
                tag = tileId
                elevation = 8f
                clipToOutline = false
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val side = minOf(view.width, view.height).coerceAtLeast(1)
                        val inset = PowerModernCard.haloFor((side / 340f).coerceIn(0.35f, 2.6f)).toInt()
                        outline.setRoundRect(inset, inset, view.width - inset, view.height - inset, 32f)
                    }
                }
            }
            tile.addView(InverterModernCard(context, expanded = false))
            val handle = ImageView(context).apply {
                setImageResource(R.drawable.ic_resize)
                layoutParams = LayoutParams(40, 40).apply { gravity = Gravity.BOTTOM or Gravity.END }
                visibility = if (isEditMode) VISIBLE else GONE
                setPadding(8, 8, 8, 8)
            }
            tile.addView(handle)
            return tile to handle
        }
    }
}
