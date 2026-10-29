package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
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

/**
 * Вид «АКБ Modern»: компактный (на панели) или развёрнутый (поверх панели и в редакторе).
 * Только отображение уже полученных данных: без сети, таймеров и постоянных анимаций.
 * В режиме редактора ([editor] != null) зоны и строки открывают настройку показателя.
 */
internal class BatteryModernCard(
    context: Context,
    val expanded: Boolean,
    private val editor: Editor? = null
) : FrameLayout(context), ModernOverlayCard {
    interface Editor {
        fun editRole(role: String)
        fun editRow(id: String)
        fun editHeader()
    }

    private val scaled = mutableListOf<Pair<TextView, Float>>()
    private val padded = mutableListOf<Pair<View, Float>>()
    private val verticalPadding = mutableMapOf<View, Float>()
    private val fitPaint = Paint()
    private val sized = mutableListOf<Triple<View, Float, Float>>()
    private var scale = 1f

    private val glyph = BatteryGlyphView(context)
    private val title = text(if (expanded) 46f else 34f, Color.WHITE, bold = true)
    private val subtitle = text(if (expanded) 24f else 20f, SECONDARY)
    private val socLabel = text(22f, SECONDARY).apply { text = "Заряд батареи" }
    private val soc = text(if (expanded) 110f else 96f, GREEN, bold = true).apply { tag = "bm_soc" }
    private val socUnit = text(if (expanded) 48f else 42f, GREEN, bold = true).apply { text = "%" }
    private val powerIcon = text(if (expanded) 64f else 52f, GREEN, bold = true).apply { text = "ϟ" }
    private val powerLabel = text(22f, SECONDARY).apply { text = if (expanded) "Текущая мощность" else "Мощность" }
    private val power = text(if (expanded) 66f else COMPACT_POWER, Color.WHITE, bold = true).apply {
        tag = "bm_power"
        maxLines = 1
    }
    private val powerUnit = text(if (expanded) 36f else 28f, SECONDARY)
    private val flow = text(22f, SECONDARY, bold = true).apply { tag = "bm_flow" }
    private val bar = BatterySegmentBar(context).apply { tag = "bm_bar" }
    private val metrics = listOf(
        Metric(BatteryModernConfig.ROLE_RUNTIME, "Время работы", "◷", ACCENT),
        Metric(BatteryModernConfig.ROLE_VOLTAGE, "Напряжение", "Ⓥ", BLUE),
        Metric(BatteryModernConfig.ROLE_TEMPERATURE, "Температура", "🌡︎", AMBER)
    )
    private val status = text(20f, Color.rgb(255, 196, 120), bold = true).apply {
        tag = "bm_status"
        visibility = GONE
    }
    val closeButton: TextView? = if (expanded && editor == null) text(34f, Color.WHITE).apply {
        text = "✕"
        tag = "bm_close"
        gravity = Gravity.CENTER
        background = rounded(Color.argb(40, 255, 255, 255), 999f, Color.argb(90, 255, 255, 255))
        contentDescription = "Закрыть"
    } else null
    /** Прокрутка списка показателей; в компактном виде отсутствует. */
    val scroll: ScrollView? = if (expanded) ScrollView(context).apply {
        tag = "bm_scroll"
        isVerticalScrollBarEnabled = true
        isFillViewport = false
    } else null
    private val sections = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val sectionBodies = mutableMapOf<BatteryModernConfig.Section, LinearLayout>()
    private val rowValues = mutableMapOf<String, TextView>()
    private var structure: String? = null
    private var lastFraction: Float? = Float.NaN

    private inner class Metric(val role: String, label: String, icon: String, color: Int) {
        val icon = text(if (expanded) 44f else 38f, color, bold = true).apply { this.text = icon }
        val label = text(if (expanded) 24f else 19f, SECONDARY).apply { this.text = label }
        val value = text(if (expanded) 38f else 30f, Color.WHITE, bold = true).apply { tag = "bm_metric_$role" }
    }

    init {
        tag = if (expanded) "bm_expanded" else "bm_compact"
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        background = cardBackground()
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 36f * scale)
            }
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pad(content, 22f)

        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(glyph)
        size(glyph, if (expanded) 84f else 70f, if (expanded) 56f else 46f)
        val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        names.addView(title)
        names.addView(subtitle)
        title.maxLines = 1
        subtitle.maxLines = 1
        header.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pad(names, 14f, horizontalOnly = true)
        editable(names) { it.editHeader() }
        closeButton?.let { header.addView(it); size(it, 64f, 64f) }

        val socBlock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (expanded) socBlock.addView(socLabel)
        val socLine = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        socLine.addView(soc)
        socLine.addView(socUnit)
        pencil(socLine)
        socBlock.addView(socLine)
        editable(socBlock) { it.editRole(BatteryModernConfig.ROLE_SOC) }

        val powerBlock = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(PANEL, 26f, BORDER)
        }
        pad(powerBlock, 18f)
        // В компактном виде место сверху и снизу отдано крупным цифрам мощности.
        if (!expanded) verticalPadding[powerBlock] = 10f
        powerBlock.addView(powerIcon)
        val powerText = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        powerText.addView(powerLabel)
        val powerLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // В компактном виде BOTTOM + базовая линия сдвигали крупные цифры вверх, на подпись.
            // TOP сохраняет общую базовую линию цифр и единицы без выхода за строку.
            gravity = if (expanded) Gravity.BOTTOM else Gravity.TOP
        }
        powerLine.addView(power)
        powerLine.addView(powerUnit)
        pad(powerUnit, 8f, horizontalOnly = true)
        powerText.addView(powerLine)
        powerText.addView(flow)
        powerBlock.addView(powerText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pad(powerText, 14f, horizontalOnly = true)
        pencil(powerBlock)
        editable(powerBlock) { it.editRole(BatteryModernConfig.ROLE_POWER) }

        if (expanded) {
            content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            socBlock.background = rounded(PANEL, 26f, BORDER)
            pad(socBlock, 18f)
            top.addView(socBlock, weighted())
            top.addView(gap(16f))
            top.addView(powerBlock, weighted())
            content.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            left.addView(header)
            left.addView(socBlock)
            top.addView(left, weighted())
            top.addView(gap(12f))
            top.addView(powerBlock, weighted())
            content.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        content.addView(spacer(12f))
        content.addView(bar)
        size(bar, -1f, if (expanded) 62f else 48f)
        content.addView(spacer(12f))

        val metricRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        metrics.forEachIndexed { index, metric ->
            if (index > 0) metricRow.addView(View(context).apply { setBackgroundColor(BORDER) }.also { size(it, 2f, 60f) })
            val cell = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            pad(cell, 10f, horizontalOnly = true)
            cell.addView(metric.icon)
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.addView(metric.label)
            column.addView(metric.value)
            metric.label.maxLines = 1
            metric.value.maxLines = 1
            pad(column, 10f, horizontalOnly = true)
            cell.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            pencil(cell)
            editable(cell) { it.editRole(metric.role) }
            metricRow.addView(cell, weighted())
        }
        content.addView(metricRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(status)

        scroll?.let { list ->
            content.addView(spacer(14f))
            BatteryModernConfig.Section.entries.forEachIndexed { index, section ->
                if (index > 0) sections.addView(gap(16f))
                val panel = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(PANEL, 24f, BORDER)
                }
                pad(panel, 16f)
                panel.addView(text(30f, Color.WHITE, bold = true).apply {
                    text = if (section == BatteryModernConfig.Section.SENSORS) "∿  ${section.title}" else "⚙︎  ${section.title}"
                })
                val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "bm_section_${section.key}" }
                panel.addView(body)
                sectionBodies[section] = body
                sections.addView(panel, weighted())
            }
            list.addView(sections, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        applyScale(1f)
    }

    /** Повторный вызов с теми же данными не меняет Views; структура списка пересоздаётся только при её изменении. */
    fun bind(tileTitle: String, config: BatteryModernConfig, readings: BatteryReadings, tempUnit: String) {
        set(title, tileTitle.ifBlank { "АКБ" })
        set(subtitle, config.subtitle)
        subtitle.visibility = if (config.subtitle.isBlank() && editor == null) GONE else VISIBLE
        if (config.subtitle.isBlank() && editor != null) set(subtitle, "Подпись: нажмите, чтобы задать")

        val socRow = config.row(BatteryModernConfig.ROLE_SOC)
        val socReading = readings.of(socRow)
        val fraction = BatteryFormat.socFraction(socReading)
        val socNumber = (socReading as? BatteryReading.Value)?.number
        set(soc, if (fraction != null && socNumber != null) BatteryFormat.decimal(socNumber, socRow?.decimals ?: 0) else "—")
        socUnit.visibility = if (fraction != null) VISIBLE else GONE
        val socColor = socColor(fraction)
        soc.setTextColor(socColor)
        socUnit.setTextColor(socColor)
        if (fraction != lastFraction) {
            lastFraction = fraction
            bar.setFraction(fraction)
            glyph.setFraction(fraction)
        }

        val powerRow = config.row(BatteryModernConfig.ROLE_POWER)
        val powerReading = readings.of(powerRow)
        // Свёрнутый вид — целые ватты; развёрнутый — прежняя точность строки. Данные не округляются.
        val powerText = if (!expanded && powerRow != null && powerReading is BatteryReading.Value) {
            BatteryFormat.compactPower(powerRow, powerReading)
        } else BatteryFormat.text(powerRow, powerReading, tempUnit)
        set(power, if (powerReading is BatteryReading.Value) powerText.value else "—")
        set(powerUnit, if (powerReading is BatteryReading.Value) powerText.unit else "")
        val direction = PowerFlow.of((powerReading as? BatteryReading.Value)?.number, config.invertPowerSign, config.idleThreshold)
        set(flow, "${direction.arrow} ${direction.label}")
        flow.setTextColor(direction.color)
        powerIcon.setTextColor(direction.color)

        metrics.forEach { metric ->
            val row = config.row(metric.role)
            val reading = readings.of(row)
            val value = BatteryFormat.text(row, reading, tempUnit)
            set(metric.value, if (reading is BatteryReading.Value) listOf(value.value, value.unit).filter { it.isNotBlank() }.joinToString(" ") else "—")
        }

        val message = readings.status
        set(status, message.orEmpty())
        status.visibility = if (message == null) GONE else VISIBLE

        if (expanded) bindRows(config, readings, tempUnit)
    }

    private fun bindRows(config: BatteryModernConfig, readings: BatteryReadings, tempUnit: String) {
        val rows = BatteryModernConfig.Section.entries.associateWith { section ->
            if (editor != null) config.rows.filter { it.section == section } else config.visibleRows(section)
        }
        val signature = rows.values.flatten().joinToString("|") { "${it.id}:${it.label}:${it.icon}:${it.hidden}" }
        if (signature != structure) {
            structure = signature
            rowValues.clear()
            rows.forEach { (section, list) ->
                val body = sectionBodies.getValue(section)
                body.removeAllViews()
                list.forEachIndexed { index, row ->
                    if (index > 0) body.addView(View(context).apply { setBackgroundColor(BORDER) }.also { size(it, -1f, 1.5f) })
                    body.addView(rowView(row))
                }
                if (list.isEmpty()) body.addView(text(22f, SECONDARY).apply { text = "Нет показателей" })
            }
            applyScale(scale)
        }
        rows.values.flatten().forEach { row ->
            val value = BatteryFormat.text(row, readings.of(row), tempUnit)
            val shown = if (!row.configured && editor != null) "выбрать источник"
                else listOf(value.value, value.unit).filter { it.isNotBlank() }.joinToString(" ")
            rowValues[row.id]?.let { set(it, shown) }
        }
    }

    private fun rowView(row: BatteryModernConfig.Row): View {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "bm_row_${row.id}"
            alpha = if (row.hidden) 0.45f else 1f
        }
        pad(line, 8f)
        line.addView(text(26f, ACCENT, bold = true).apply { text = BatteryIcons.glyph(row.icon); gravity = Gravity.CENTER }
            .also { size(it, 44f, -2f) })
        line.addView(text(22f, Color.WHITE).apply {
            text = if (row.hidden) "${row.label} (скрыто)" else row.label
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = text(22f, Color.WHITE).apply {
            gravity = Gravity.END
            tag = "bm_value_${row.id}"
            maxLines = 1
        }
        rowValues[row.id] = value
        line.addView(value)
        pencil(line)
        editable(line) { it.editRow(row.id) }
        return line
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val next = if (expanded) minOf(w / 1180f, h / 900f) else minOf(w / 540f, h / 390f)
        val clamped = next.coerceIn(0.35f, 2.2f)
        if (expanded) sections.orientation = if (w >= 760) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (clamped != scale) post { applyScale(clamped) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!expanded) fitPower()
    }

    /**
     * Цифры мощности: масштабный размер плитки, но не шире своей колонки вместе с единицей.
     * Размер считается от базового (не от текущего), поэтому повторный layout даёт тот же результат.
     */
    private fun fitPower() {
        val column = power.parent?.parent as? View ?: return
        val available = column.width - column.paddingLeft - column.paddingRight
        if (available <= 0) return
        val full = COMPACT_POWER * scale
        fitPaint.set(power.paint)
        fitPaint.textSize = full
        val needed = fitPaint.measureText(power.text.toString())
        val unit = powerUnit.text.toString()
        val room = available - (if (unit.isEmpty()) 0f else
            powerUnit.paint.measureText(unit) + powerUnit.paddingLeft + powerUnit.paddingRight)
        var size = if (needed <= room || needed <= 0f) full else (full * room / needed).coerceAtLeast(full * 0.4f)
        // Ширина текста не строго пропорциональна размеру шрифта: доводим до фактического измерения.
        while (size > full * 0.4f && fitPaint.apply { textSize = size }.measureText(power.text.toString()) > room) size -= 1f
        // Изменение шрифта запрашивает layout: не делаем это внутри текущего прохода.
        if (kotlin.math.abs(power.textSize - size) > 0.5f) post { power.setTextSize(TypedValue.COMPLEX_UNIT_PX, size) }
    }

    private fun applyScale(next: Float) {
        scale = next
        scaled.forEach { (view, base) -> view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * next) }
        padded.forEach { (view, base) ->
            val p = (base * next).toInt()
            val horizontal = view.getTag(R.id.battery_modern_horizontal_padding) == true
            val v = verticalPadding[view]?.let { (it * next).toInt() } ?: p
            view.setPadding(p, if (horizontal) view.paddingTop else v, p, if (horizontal) view.paddingBottom else v)
        }
        sized.forEach { (view, width, height) ->
            val params = view.layoutParams ?: return@forEach
            if (width > 0) params.width = (width * next).toInt().coerceAtLeast(1)
            else if (width == -1f) params.width = ViewGroup.LayoutParams.MATCH_PARENT
            if (height > 0) params.height = (height * next).toInt().coerceAtLeast(1)
            else if (height == -1f) params.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.layoutParams = params
        }
        invalidateOutline()
    }

    private fun text(base: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        scaled += this to base
    }

    private fun set(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    private fun pad(view: View, base: Float, horizontalOnly: Boolean = false) {
        if (horizontalOnly) view.setTag(R.id.battery_modern_horizontal_padding, true)
        padded += view to base
    }

    private fun size(view: View, width: Float, height: Float) {
        if (view.layoutParams == null) view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sized += Triple(view, width, height)
    }

    private fun spacer(height: Float) = View(context).also {
        it.layoutParams = LinearLayout.LayoutParams(1, 1)
        sized += Triple(it, 1f, height)
    }

    private fun gap(width: Float) = View(context).also {
        it.layoutParams = LinearLayout.LayoutParams(1, 1)
        sized += Triple(it, width, 1f)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun pencil(group: LinearLayout) {
        if (editor == null) return
        group.addView(text(24f, ACCENT).apply {
            text = "✎"
            gravity = Gravity.CENTER
            background = rounded(Color.argb(30, 255, 255, 255), 999f, BORDER)
        }.also { size(it, 44f, 44f) })
    }

    private fun editable(view: View, action: (Editor) -> Unit) {
        val target = editor ?: return
        view.isClickable = true
        view.setOnClickListener { action(target) }
    }

    /** Освобождает обработчики редактора; Views карточки больше не используются. */
    override fun dispose() {
        fun clear(view: View) {
            view.setOnClickListener(null)
            view.cancelPendingInputEvents()
            if (view is ViewGroup) for (i in 0 until view.childCount) clear(view.getChildAt(i))
        }
        clear(this)
    }

    companion object {
        /** Базовый размер цифр мощности компактного вида (было 50, +24%). */
        const val COMPACT_POWER = 62f
        val GREEN = Color.rgb(52, 232, 158)
        val ACCENT = Color.rgb(38, 198, 218)
        val BLUE = Color.rgb(41, 182, 246)
        val AMBER = Color.rgb(255, 193, 7)
        val SECONDARY = Color.rgb(159, 179, 191)
        val PANEL = Color.argb(150, 14, 30, 38)
        val BORDER = Color.argb(110, 52, 120, 128)

        fun socColor(fraction: Float?): Int = when {
            fraction == null -> Color.rgb(120, 144, 156)
            fraction > 0.2f -> GREEN
            fraction > 0.1f -> AMBER
            else -> Color.rgb(255, 82, 82)
        }

        fun rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            stroke?.let { setStroke(2, it) }
        }

        private fun cardBackground() = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(Color.argb(240, 12, 34, 38), Color.argb(240, 8, 18, 26))
        ).apply {
            cornerRadius = 36f
            setStroke(3, Color.argb(170, 38, 198, 160))
        }

        /** Плитка панели: та же основа, что у остальных виджетов (тег, размер, ручка resize). */
        fun createTile(context: Context, tileId: String, width: Int, height: Int, isEditMode: Boolean): Pair<FrameLayout, ImageView> {
            val tile = FrameLayout(context).apply {
                layoutParams = LayoutParams(width, height)
                tag = tileId
                elevation = 8f
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 32f)
                    }
                }
            }
            tile.addView(BatteryModernCard(context, expanded = false))
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

internal object BatteryIcons {
    /** Иконка — выбор владельца; ключи сохраняются в config строки. */
    val all = linkedMapOf(
        "battery" to ("▮" to "Батарея"),
        "charging" to ("⇡" to "Зарядка"),
        "power" to ("ϟ" to "Мощность"),
        "energy" to ("Σ" to "Энергия"),
        "voltage" to ("Ⓥ" to "Напряжение"),
        "temperature" to ("🌡︎" to "Температура"),
        "current" to ("≋" to "Ток"),
        "health" to ("♥︎" to "Здоровье"),
        "cycles" to ("↻" to "Циклы"),
        "runtime" to ("◷" to "Время"),
        "problem" to ("✓" to "Проблема"),
        "signal" to ("◉" to "Сигнал"),
        "balancer" to ("⚖︎" to "Балансир"),
        "mosfet" to ("⏻" to "Ключ (MOSFET)"),
        "cell" to ("▯" to "Ячейка"),
        "capacity" to ("▣" to "Ёмкость"),
        "cell_high" to ("▲" to "Ячейка макс."),
        "cell_low" to ("▼" to "Ячейка мин."),
        "custom" to ("•" to "Точка")
    )

    fun glyph(key: String) = all[key]?.first ?: "•"
}

/** Сегментная шкала заряда. Нет достоверного SOC — все сегменты нейтральные. */
internal class BatterySegmentBar(context: Context) : View(context) {
    var fraction: Float? = null
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setFraction(value: Float?) {
        if (value == fraction) return
        fraction = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val nub = h * 0.18f
        val body = w - nub - 2f
        val stroke = (h * 0.06f).coerceAtLeast(2f)
        val value = fraction
        val glow = if (value == null) Color.rgb(84, 110, 122) else BatteryModernCard.socColor(value)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = Color.argb(200, Color.red(glow), Color.green(glow), Color.blue(glow))
        rect.set(stroke, stroke, body, h - stroke)
        canvas.drawRoundRect(rect, h * 0.3f, h * 0.3f, paint)
        paint.style = Paint.Style.FILL
        rect.set(body + 2f, h * 0.3f, w, h * 0.7f)
        canvas.drawRoundRect(rect, nub / 2, nub / 2, paint)

        val inner = stroke * 2.5f
        val count = 40
        val gapWidth = ((body - inner * 2) / count) * 0.28f
        val segment = (body - inner * 2 - gapWidth * (count - 1)) / count
        val filled = if (value == null) 0 else Math.round(value * count)
        for (i in 0 until count) {
            val left = inner + i * (segment + gapWidth)
            paint.color = when {
                value == null -> Color.rgb(43, 58, 64)
                i < filled -> blend(Color.rgb(0, 229, 168), Color.rgb(166, 245, 91), i / count.toFloat(), value)
                else -> Color.rgb(28, 50, 56)
            }
            rect.set(left, inner, left + segment, h - inner)
            canvas.drawRoundRect(rect, segment / 3, segment / 3, paint)
        }
    }

    private fun blend(from: Int, to: Int, t: Float, value: Float): Int {
        if (value <= 0.2f) return BatteryModernCard.socColor(value)
        fun mix(a: Int, b: Int) = (a + (b - a) * t).toInt()
        return Color.rgb(mix(Color.red(from), Color.red(to)), mix(Color.green(from), Color.green(to)), mix(Color.blue(from), Color.blue(to)))
    }
}

/** Значок батареи в заголовке; заливка следует за достоверным SOC. */
internal class BatteryGlyphView(context: Context) : View(context) {
    private var fraction: Float? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setFraction(value: Float?) {
        if (value == fraction) return
        fraction = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val color = BatteryModernCard.socColor(fraction)
        val stroke = (h * 0.1f).coerceAtLeast(2f)
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        rect.set(stroke, stroke, w - w * 0.1f, h - stroke)
        canvas.drawRoundRect(rect, h * 0.2f, h * 0.2f, paint)
        paint.style = Paint.Style.FILL
        rect.set(w - w * 0.1f, h * 0.32f, w, h * 0.68f)
        canvas.drawRect(rect, paint)
        val value = fraction ?: return
        val inner = stroke * 2
        rect.set(inner, inner, inner + (w - w * 0.1f - inner * 2) * value, h - inner)
        canvas.drawRoundRect(rect, h * 0.1f, h * 0.1f, paint)
    }
}
