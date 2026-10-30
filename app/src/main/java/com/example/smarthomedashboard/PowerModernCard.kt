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
 * Вид «Электропитание Modern»: компактный (на панели) и развёрнутый (поверх панели, в редакторе).
 * Цифры напряжения и мощности всегда белые; состояние передаёт оформление: фон, рамка, свечение,
 * иконка и статус. Только отображение уже полученных данных: без сети. Анимации — плавная смена
 * цвета и, только для аварийных правил, мягкая пульсация; обе освобождаются при отсоединении.
 */
internal class PowerModernCard(
    context: Context,
    val expanded: Boolean,
    private val editor: BatteryModernCard.Editor? = null
) : FrameLayout(context), ModernOverlayCard, ModernHideAware {
    private val scaled = mutableListOf<Pair<TextView, Float>>()
    private var scale = 1f
    /** Ширина кольца ореола снаружи тёмного тела, внутри собственных границ карточки (px). */
    internal var haloPx = haloFor(1f)
        private set
    private val density = resources.displayMetrics.density

    // ---- Оформление: только заранее созданные объекты, onDraw ничего не выделяет. ----
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(9, 14, 20) }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val fitPaint = Paint()
    private val frequencyBackground = android.graphics.drawable.GradientDrawable().apply { setColor(Color.argb(60, 20, 30, 38)) }
    /** Текущий отображаемый акцент (во время перехода — промежуточный). */
    var accent: Int = PowerModernConfig.NEUTRAL
        private set
    /** Уровень свечения 0.45–1; меняется только пульсацией. */
    internal var glowLevel = 1f
        private set
    var pulsing = false
        private set
    private var colorAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var targetAccent: Int? = null
    private var wantPulse = false
    /** Исходная плитка скрыта раскрытием (alpha = 0) или окно не показано: невидимых анимаций нет. */
    private var hiddenBySource = false
    private var windowVisible = true

    // ---- Содержимое ----
    private val icon = ImageView(context).apply { tag = "pm_icon" }
    private val title = text(if (expanded) 30f else 22f, Color.WHITE, bold = true).apply { tag = "pm_title"; maxLines = 1 }
    private val status = text(if (expanded) 22f else 16f, Color.WHITE, bold = true).apply { tag = "pm_status"; maxLines = 1 }
    private val voltage = text(MAIN, Color.WHITE, bold = true).apply { tag = "pm_voltage"; maxLines = 1 }
    private val voltageUnit = text(MAIN * UNIT_RATIO, UNIT_COLOR, bold = true).apply { tag = "pm_voltage_unit"; maxLines = 1 }
    private val power = text(MAIN, Color.WHITE, bold = true).apply { tag = "pm_power"; maxLines = 1 }
    private val powerUnit = text(MAIN * UNIT_RATIO, UNIT_COLOR, bold = true).apply { tag = "pm_power_unit"; maxLines = 1 }
    private val sine = ImageView(context).apply { setImageResource(R.drawable.ic_power_sine) }
    private val frequencyLabel = text(if (expanded) 20f else 15f, SECONDARY).apply { text = "Частота" }
    private val frequency = text(if (expanded) 30f else 24f, Color.WHITE, bold = true).apply { tag = "pm_frequency"; maxLines = 1 }
    private val frequencyBox = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        tag = "pm_frequency_box"
        background = frequencyBackground
    }
    private val numbers = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        tag = "pm_numbers"
    }
    private val voltageLine = line(voltage, voltageUnit)
    private val powerLine = line(power, powerUnit)
    private val divider = View(context)
    val scroll: ScrollView? = if (expanded) ScrollView(context).apply { tag = "pm_scroll" } else null
    private val extrasBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "pm_extras" }
    private val extraValues = mutableMapOf<String, TextView>()
    /** Иконки строк: меняются на месте, без пересоздания строк. */
    private val extraIcons = mutableMapOf<String, TextView>()
    private var structure: String? = null
    private var iconKey: String? = null

    init {
        tag = if (expanded) "pm_expanded" else "pm_compact"
        setWillNotDraw(false)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        // Ореол рисуется снаружи тела: обрезки по контуру нет, содержимое удерживает внутренний отступ.
        // Контур (тень от elevation) — по телу, а не по внешним границам с ореолом.
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
        names.addView(status)
        header.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        editable(header) { it.editHeader() }

        numbers.addView(voltageLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        numbers.addView(powerLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        editable(voltageLine) { it.editRole(PowerModernConfig.ROLE_VOLTAGE) }
        editable(powerLine) { it.editRole(PowerModernConfig.ROLE_POWER) }

        frequencyBox.addView(sine, LinearLayout.LayoutParams(1, 1))
        val freqText = LinearLayout(context).apply { orientation = if (expanded) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        freqText.addView(frequencyLabel)
        freqText.addView(frequency)
        frequencyBox.addView(freqText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        editable(frequencyBox) { it.editRole(PowerModernConfig.ROLE_FREQUENCY) }

        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (!expanded) left.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        left.addView(numbers, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        left.addView(frequencyBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (scroll == null) {
            addView(left, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.46f))
            row.addView(divider, LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT))
            scroll.addView(extrasBody, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.54f))
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        setAccentNow(PowerModernConfig.NEUTRAL)
        applyScale(1f)
    }

    /**
     * Показания обновляются сразу; цвет — по [visual] (уже подтверждённому трекером).
     * [animate] = false — сразу целевое оформление (новая View после пересоздания UI).
     */
    fun bind(tileTitle: String, config: PowerModernConfig, readings: BatteryReadings, visual: PowerVisual,
             animate: Boolean = true) {
        binds++
        set(title, tileTitle.ifBlank { PowerModernConfig.title(config.profile) })
        set(status, visual.status)
        if (iconKey != config.icon) {
            iconKey = config.icon
            icon.setImageResource(if (config.icon == PowerModernConfig.ICON_INVERTER) R.drawable.ic_power_inverter else R.drawable.ic_power_pylon)
            icon.setColorFilter(accent)
        }
        bindMain(config.row(PowerModernConfig.ROLE_VOLTAGE), readings, voltage, voltageUnit, "В",
            if (expanded) null else 0, expandedLimit = 1)
        bindMain(config.row(PowerModernConfig.ROLE_POWER), readings, power, powerUnit, "Вт", if (expanded) null else 0)
        val freqRow = config.row(PowerModernConfig.ROLE_FREQUENCY)
        val freqReading = readings.of(freqRow)
        // Компакт — 1 знак; развёрнуто — точность источника, но не больше 2 и без искусственных нулей.
        val freqDecimals = freqRow?.decimals ?: if (expanded) sourceDecimals(freqReading, limit = 2) else 1
        set(frequency, joined(value(freqRow, freqReading, freqDecimals, "Гц")))
        if (expanded) bindExtras(config, readings, visual)
        applyVisual(visual.color, visual.pulse, animate, config.transitionMs)
    }

    private fun bindMain(row: BatteryModernConfig.Row?, readings: BatteryReadings, number: TextView, unit: TextView,
                         defaultUnit: String, decimals: Int?, expandedLimit: Int? = null) {
        val reading = readings.of(row)
        val chosen = decimals ?: row?.decimals ?: expandedLimit?.let { sourceDecimals(reading, it) }
        val (text, shownUnit) = value(row, reading, chosen, defaultUnit)
        set(number, text)
        set(unit, shownUnit)
    }

    /** Знаков в данных источника, но не больше [limit] (без ложной точности); null — данных нет. */
    private fun sourceDecimals(reading: BatteryReading, limit: Int): Int? =
        (reading as? BatteryReading.Value)?.raw?.trim()?.replace(',', '.')?.substringAfter('.', "")?.length?.coerceAtMost(limit)

    /** Достоверное число — по точности; всё остальное — «—» с единицей, без подстановки нуля. */
    private fun value(row: BatteryModernConfig.Row?, reading: BatteryReading, decimals: Int?, defaultUnit: String): Pair<String, String> {
        val fallbackUnit = unitRu(row?.unit?.takeIf { it.isNotBlank() } ?: defaultUnit)
        if (row == null || reading !is BatteryReading.Value || reading.number == null) return "—" to fallbackUnit
        val text = BatteryFormat.text(row.copy(decimals = decimals ?: row.decimals), reading)
        return text.value to unitRu(text.unit.ifBlank { defaultUnit })
    }

    private fun joined(pair: Pair<String, String>) = listOf(pair.first, pair.second).filter { it.isNotBlank() }.joinToString(" ")

    private fun bindExtras(config: PowerModernConfig, readings: BatteryReadings, visual: PowerVisual) {
        val rows = config.extras.filter { editor != null || !it.hidden }
        val signature = rows.joinToString("|") { "${it.id}:${it.label}:${it.hidden}" }
        if (signature != structure) {
            structure = signature
            extraValues.clear()
            extraIcons.clear()
            extrasBody.removeAllViews()
            rows.forEachIndexed { index, row ->
                if (index > 0) extrasBody.addView(View(context).apply { setBackgroundColor(Color.argb(50, 255, 255, 255)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                extrasBody.addView(extraRow(row))
            }
            if (rows.isEmpty()) extrasBody.addView(text(22f, SECONDARY).apply { text = "Нет дополнительных показателей" })
            applyScale(scale)
        }
        rows.forEach { row ->
            extraIcons[row.id]?.let { set(it, BatteryIcons.glyph(row.icon)) }
            val view = extraValues[row.id] ?: return@forEach
            if (row.role == PowerModernConfig.ROLE_STATUS && !row.configured) {
                set(view, visual.status)
                view.setTextColor(if (visual.neutral) SECONDARY else accent)
            } else {
                val reading = readings.of(row)
                val text = BatteryFormat.text(row, reading)
                set(view, if (!row.configured && editor != null) "выбрать источник"
                    else listOf(text.value, if (reading is BatteryReading.Value) unitRu(text.unit) else "").filter { it.isNotBlank() }.joinToString(" "))
                view.setTextColor(Color.WHITE)
            }
        }
    }

    private fun extraRow(row: BatteryModernConfig.Row): View {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "pm_row_${row.id}"
            alpha = if (row.hidden) 0.45f else 1f
            val p = (10 * scale).toInt()
            setPadding(p, p, p, p)
        }
        // Иконка из общего набора Modern (как у АКБ), в цвете состояния плитки.
        val icon = text(24f, accent, bold = true).apply {
            tag = "pm_icon_${row.id}"
            gravity = Gravity.CENTER
            text = BatteryIcons.glyph(row.icon)
        }
        extraIcons[row.id] = icon
        line.addView(icon, LinearLayout.LayoutParams((40 * scale).toInt().coerceAtLeast(1), ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = (8 * scale).toInt() })
        line.addView(text(22f, SECONDARY).apply {
            text = if (row.hidden) "${row.label} (скрыто)" else row.label
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = text(24f, Color.WHITE, bold = true).apply {
            tag = "pm_value_${row.id}"
            gravity = Gravity.END
            maxLines = 1
        }
        extraValues[row.id] = value
        line.addView(value)
        editable(line) { it.editRow(row.id) }
        return line
    }

    // ---- Цвет и пульсация ----

    /** Цвет меняется плавно за [durationMs]; повторный вызов с тем же цветом ничего не перезапускает. */
    fun applyVisual(color: Int, pulse: Boolean, animate: Boolean, durationMs: Long = PowerModernConfig.DEFAULT_TRANSITION_MS) {
        if (targetAccent != color) {
            targetAccent = color
            colorAnimator?.cancel()
            colorAnimator = null
            if (!animate || durationMs <= 0 || accent == color || !visiblyShown()) setAccentNow(color)
            else {
                val from = accent
                colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, color).apply {
                    duration = durationMs
                    addUpdateListener { if (colorAnimator === it) setAccentNow(it.animatedValue as Int) }
                    start()
                }
            }
        }
        wantPulse = pulse
        updatePulse()
    }

    /** Анимации — только у действительно видимой карточки: окно, все родители и не скрыта раскрытием. */
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

    /** Приходит при смене видимости самой карточки или любого её предка. */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        visibilityChanged()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        visibilityChanged()
    }

    /** Скрытая карточка сразу получает целевой цвет; подтверждённое состояние и цифры не меняются. */
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
            // ~1 цикл в секунду: яркость рамки и свечения 45–100%, без изменения цифр и размеров.
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
        } else {
            stopPulse()
        }
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
        sine.setColorFilter(color)
        status.setTextColor(color)
        extraIcons.values.forEach { it.setTextColor(color) }
        divider.setBackgroundColor(Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)))
        frequencyBackground.setStroke(2, Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)))
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
        // Внешний ореол: три кольца в зарезервированном отступе, яркость убывает наружу и следует
        // пульсации. Только заранее созданные Paint/RectF, без размытия и без выделений памяти.
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
        // Мягкое свечение у внутренней кромки тела.
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
        val next = (if (expanded) minOf(w / 1100f, h / 640f) else minOf(w / 300f, h / 360f)).coerceIn(0.35f, 2.6f)
        if (next != scale) post { applyScale(next) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        fitNumbers()
    }

    /**
     * Напряжение и мощность — одного размера, максимально крупные: по высоте области чисел
     * и по ширине самой длинной строки. Размеры в px от размеров плитки, а не от системного
     * масштаба шрифта, поэтому крупный шрифт Android не обрезает цифры. Считается от базы.
     */
    private fun fitNumbers() {
        val areaW = numbers.width - numbers.paddingLeft - numbers.paddingRight
        val areaH = numbers.height - numbers.paddingTop - numbers.paddingBottom
        if (areaW <= 0 || areaH <= 0) return
        val full = MAIN * scale * if (expanded) 1.35f else 1f
        val byHeight = areaH / 2.25f
        var size = minOf(full, byHeight)
        fun width(number: TextView, size: Float): Float {
            fitPaint.set(number.paint)
            fitPaint.textSize = size
            val n = fitPaint.measureText(number.text.toString())
            fitPaint.textSize = size * UNIT_RATIO
            return n + size * 0.18f + fitPaint.measureText((if (number === voltage) voltageUnit else powerUnit).text.toString())
        }
        val needed = maxOf(width(voltage, size), width(power, size))
        if (needed > areaW) size = (size * areaW / needed).coerceAtLeast(full * 0.3f)
        while (size > full * 0.3f && maxOf(width(voltage, size), width(power, size)) > areaW) size -= 1f
        if (kotlin.math.abs(voltage.textSize - size) > 0.5f) post { applyMainSize(size) }
    }

    private fun applyMainSize(size: Float) {
        voltage.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        power.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        voltageUnit.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * UNIT_RATIO)
        powerUnit.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * UNIT_RATIO)
        val gap = (size * 0.18f).toInt()
        (voltageUnit.layoutParams as? LinearLayout.LayoutParams)?.let { it.leftMargin = gap; voltageUnit.layoutParams = it }
        (powerUnit.layoutParams as? LinearLayout.LayoutParams)?.let { it.leftMargin = gap; powerUnit.layoutParams = it }
    }

    private fun applyScale(next: Float) {
        scale = next
        haloPx = haloFor(next)
        scaled.forEach { (view, base) ->
            if (view !== voltage && view !== power && view !== voltageUnit && view !== powerUnit) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * next)
            }
        }
        // Содержимое — внутри тела: к прежнему отступу добавляется кольцо ореола.
        val pad = (18 * next).toInt() + haloPx.toInt()
        (getChildAt(0) as? ViewGroup)?.setPadding(pad, pad, pad, pad)
        val iconSide = ((if (expanded) 64 else 40) * next).toInt().coerceAtLeast(1)
        icon.layoutParams = (icon.layoutParams as LinearLayout.LayoutParams).apply {
            width = iconSide; height = iconSide; rightMargin = (12 * next).toInt()
        }
        val sineSide = ((if (expanded) 52 else 36) * next).toInt().coerceAtLeast(1)
        sine.layoutParams = (sine.layoutParams as LinearLayout.LayoutParams).apply {
            width = sineSide; height = sineSide; rightMargin = (12 * next).toInt()
        }
        val boxPad = (12 * next).toInt()
        frequencyBox.setPadding(boxPad, (8 * next).toInt(), boxPad, (8 * next).toInt())
        frequencyBackground.cornerRadius = 22f * next
        (frequencyBox.getChildAt(1) as? LinearLayout)?.let { (it.getChildAt(1).layoutParams as? LinearLayout.LayoutParams)
            ?.let { lp -> if (expanded) { lp.leftMargin = (16 * next).toInt(); it.getChildAt(1).layoutParams = lp } } }
        // Маленькая плитка: частота уступает место главным показателям.
        frequencyBox.visibility = if (!expanded && next < SMALL_SCALE) GONE else VISIBLE
        if (scroll != null) {
            (divider.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.leftMargin = (20 * next).toInt(); it.rightMargin = (20 * next).toInt(); divider.layoutParams = it
            }
        }
        setAccentNow(accent)
        invalidateOutline()
        requestLayout()
    }

    /** Компакт совпадает со скруглением плитки панели (32 px); раскрытая карточка масштабируется. */
    private fun radius() = if (expanded) 40f * scale.coerceAtLeast(0.6f) else 32f

    // ---- Жизненный цикл ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePulse()
    }

    override fun onDetachedFromWindow() {
        // Невидимая/удалённая плитка не держит бесконечную анимацию.
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
    /** Правило требует пульсации (сама анимация идёт только у видимой View в окне). */
    internal val pulseRequested get() = wantPulse
    internal val pulseAnimatorForTest: ValueAnimator? get() = pulseAnimator
    /** Число привязок данных: адресное обновление не трогает чужие плитки. */
    internal var binds = 0
        private set

    // ---- Вспомогательное ----

    private fun line(number: TextView, unit: TextView) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
        isBaselineAligned = true
        addView(number)
        addView(unit)
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

    private fun editable(view: View, action: (BatteryModernCard.Editor) -> Unit) {
        val target = editor ?: return
        view.isClickable = true
        view.setOnClickListener { action(target) }
    }

    companion object {
        /** Базовый размер цифр напряжения и мощности (компакт 300×360). */
        const val MAIN = 72f
        const val UNIT_RATIO = 0.46f
        /** Ниже этого масштаба частота скрывается. */
        const val SMALL_SCALE = 0.72f
        /** Базовая ширина ореола (px при масштабе 1): небольшой отступ внутри сохранённого размера плитки. */
        const val HALO = 8f

        fun haloFor(scale: Float) = HALO * scale.coerceIn(0.6f, 2f)

        /** Масштаб компактной карточки по размеру плитки — как в onSizeChanged. */
        fun compactScale(width: Int, height: Int) = minOf(width / 300f, height / 360f).coerceIn(0.35f, 2.6f)
        val SECONDARY = Color.rgb(170, 186, 196)
        val UNIT_COLOR = Color.argb(225, 255, 255, 255)

        /** Только отображение: единицы HA на русском (W → Вт и т. п.), без пересчёта в кВт/кВ. */
        fun unitRu(unit: String): String = when (unit.trim()) {
            "W" -> "Вт"
            "V" -> "В"
            "Hz" -> "Гц"
            "A" -> "А"
            "VA" -> "ВА"
            "var", "VAR", "VAr" -> "вар"
            "kWh" -> "кВт·ч"
            "Wh" -> "Вт·ч"
            else -> unit
        }

        /** Плитка панели: основа, как у остальных виджетов (тег, размер, ручка resize). */
        fun createTile(context: Context, tileId: String, width: Int, height: Int, isEditMode: Boolean): Pair<FrameLayout, ImageView> {
            val tile = FrameLayout(context).apply {
                layoutParams = LayoutParams(width, height)
                tag = tileId
                elevation = 8f
                // Ореол карточки выходит за тело, но остаётся в сохранённых границах плитки:
                // обрезки нет, тень — по телу. Размер, координаты и область касания прежние.
                clipToOutline = false
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val inset = haloFor(compactScale(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))).toInt()
                        outline.setRoundRect(inset, inset, view.width - inset, view.height - inset, 32f)
                    }
                }
            }
            tile.addView(PowerModernCard(context, expanded = false))
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
