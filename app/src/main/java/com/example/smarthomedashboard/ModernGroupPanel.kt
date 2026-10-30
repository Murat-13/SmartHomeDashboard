package com.example.smarthomedashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Раскрытая Modern-группа: затемнение, яркая копия материнской плитки над ним и непрозрачная
 * панель дочерних кнопок. Команды не отправляет — только сообщает о нажатиях владельцу.
 * Свой Handler: очистка Handler панели дашборда не трогает таймер автосворачивания.
 */
internal class ModernGroupPanel(
    private val context: Context,
    private val root: () -> FrameLayout,
    private val host: Host
) {
    interface Host {
        fun childModel(tileId: String, entityId: String, name: String): ModernTileView.Model
        fun parentModel(tileId: String): ModernTileView.Model?
        fun childTapped(tileId: String, entityId: String, view: View)
        /** Пользователь или таймер закрыли панель (не очистка при перестроении). */
        fun closed(tileId: String)
    }

    data class Entry(val entityId: String, val name: String)

    private val handler = Handler(Looper.getMainLooper())
    private val density get() = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun dpi(value: Float) = (value * density).toInt()

    var tileId: String? = null
        private set
    val isOpen get() = tileId != null && !closing
    private var closing = false
    private var source: ModernTileView? = null
    private var dim: View? = null
    private var clone: ModernTileView? = null
    private var panel: FrameLayout? = null
    private var titleView: TextView? = null
    private var scroll: ScrollView? = null
    private var grid: Grid? = null
    private var emptyView: TextView? = null
    private var entries: List<Entry> = emptyList()
    internal val children = mutableListOf<ModernTileView>()
    private val collapse = Runnable { close() }

    /** Только для тестов: текущие View панели. */
    internal val panelView get() = panel
    internal val dimView get() = dim
    internal val parentCopy get() = clone
    internal val scrollView get() = scroll

    fun open(tileId: String, title: String, entries: List<Entry>, source: ModernTileView, animate: Boolean) {
        clear()
        val root = root()
        this.tileId = tileId
        this.source = source
        this.entries = entries
        closing = false

        dim = View(context).apply {
            setBackgroundColor(Color.BLACK)
            alpha = if (animate) 0f else DIM_ALPHA
            isClickable = true
            setOnClickListener { close() }
        }
        root.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bounds = boundsInRoot(source, root)
        clone = ModernTileView(context, ModernTileView.Kind.GROUP).apply {
            highlight = true
            host.parentModel(tileId)?.let { bind(it, animate = false) }
            x = bounds.left
            y = bounds.top
            setOnClickListener { close() }
        }
        root.addView(clone, FrameLayout.LayoutParams(bounds.width().toInt(), bounds.height().toInt()))

        buildPanel(title)
        root.addView(panel)
        layoutPanel(root)
        if (animate) animateOpen() else {
            clone?.thumbnailsHidden = true
        }
        resetTimer()
    }

    /** Новое дерево панели после перестроения: та же панель остаётся открытой, прокрутка сохраняется. */
    fun reattach(source: ModernTileView, title: String, entries: List<Entry>) {
        if (tileId == null || closing) return
        this.source = source
        titleView?.text = title
        if (entries != this.entries) {
            this.entries = entries
            fillGrid()
        }
        val root = root()
        val bounds = boundsInRoot(source, root)
        clone?.apply {
            x = bounds.left
            y = bounds.top
            layoutParams = FrameLayout.LayoutParams(bounds.width().toInt(), bounds.height().toInt())
        }
        refreshParent()
        layoutPanel(root)
    }

    /** Повторная раскладка при изменении размера окна без закрытия панели. */
    fun relayout() {
        val root = root()
        val source = source ?: return
        if (tileId == null || closing) return
        val bounds = boundsInRoot(source, root)
        clone?.apply { x = bounds.left; y = bounds.top }
        layoutPanel(root)
    }

    fun render(changed: Set<String>?) {
        if (tileId == null) return
        children.forEachIndexed { i, child ->
            val entry = entries.getOrNull(i) ?: return@forEachIndexed
            if (changed == null || entry.entityId in changed) child.bind(host.childModel(requireNotNull(tileId), entry.entityId, entry.name), animate = true)
        }
        refreshParent()
    }

    fun refreshParent() {
        val id = tileId ?: return
        host.parentModel(id)?.let { clone?.bind(it, animate = true) }
    }

    fun resetTimer() {
        handler.removeCallbacks(collapse)
        if (tileId != null && !closing) handler.postDelayed(collapse, AUTO_COLLAPSE_MS)
    }

    private fun boundsInRoot(view: View, root: View): RectF {
        val a = IntArray(2)
        val b = IntArray(2)
        view.getLocationInWindow(a)
        root.getLocationInWindow(b)
        val left = (a[0] - b[0]).toFloat()
        val top = (a[1] - b[1]).toFloat()
        val w = (view.width.takeIf { it > 0 } ?: view.layoutParams?.width ?: 0).toFloat()
        val h = (view.height.takeIf { it > 0 } ?: view.layoutParams?.height ?: 0).toFloat()
        return RectF(left, top, left + w, top + h)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPanel(title: String) {
        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(PANEL_BG)
                setStroke(dpi(1f), PANEL_BORDER)
                cornerRadius = dp(20f)
            }
            elevation = dp(24f)
            isClickable = true // Касание пустого места панели не закрывает её и не доходит до плиток.
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = FrameLayout(context)
        titleView = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dpi(60f), dpi(10f), dpi(60f), dpi(10f))
            minHeight = dpi(60f)
        }
        header.addView(titleView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val close = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(0xFFD5E2EF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1E3550.toInt())
                setStroke(dpi(1f), PANEL_BORDER)
            }
            contentDescription = "Закрыть"
            setOnClickListener { close() }
        }
        header.addView(close, FrameLayout.LayoutParams(dpi(48f), dpi(48f), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dpi(8f)
        })
        column.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(View(context).apply { setBackgroundColor(PANEL_BORDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1f)).apply {
                marginStart = dpi(PAD_DP); marginEnd = dpi(PAD_DP)
            })
        grid = Grid(context)
        scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = true
            scrollBarFadeDuration = 400
            overScrollMode = View.OVER_SCROLL_NEVER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                verticalScrollbarThumbDrawable = GradientDrawable().apply {
                    setColor(SCROLLBAR)
                    cornerRadius = dp(2f)
                    setSize(dpi(3f), dpi(24f))
                }
            }
            // Прокрутка — тоже взаимодействие: таймер автосворачивания перезапускается.
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) resetTimer()
                false
            }
            addView(grid)
        }
        emptyView = TextView(context).apply {
            text = "В группе пока нет устройств"
            setTextColor(0xFF9FB3C8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(dpi(24f), dpi(28f), dpi(24f), dpi(28f))
        }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel = card
        fillGrid()
    }

    private fun fillGrid() {
        val grid = grid ?: return
        children.forEach { it.animate().cancel(); it.setOnClickListener(null); it.setOnTouchListener(null) }
        grid.removeAllViews()
        children.clear()
        entries.forEach { entry ->
            val child = ModernTileView(context, ModernTileView.Kind.CHILD).apply {
                bind(host.childModel(requireNotNull(tileId), entry.entityId, entry.name), animate = false)
                setOnClickListener { view ->
                    if (closing) return@setOnClickListener
                    resetTimer()
                    tileId?.let { host.childTapped(it, entry.entityId, view) }
                }
            }
            ModernPress.attach(child)
            grid.addView(child)
            children += child
        }
        val empty = entries.isEmpty()
        emptyView?.visibility = if (empty) View.VISIBLE else View.GONE
        scroll?.visibility = if (empty) View.GONE else View.VISIBLE
    }

    /** Размер и место панели: над родителем с зазором, иначе ближайшее место в пределах экрана. */
    private fun layoutPanel(root: FrameLayout) {
        val panel = panel ?: return
        val grid = grid ?: return
        val source = source ?: return
        val rootW = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val rootH = root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val margin = dp(16f)
        val gap = dp(10f)
        val pad = dp(PAD_DP)
        val parent = boundsInRoot(source, root)
        val count = entries.size

        // Дочерние кнопки крупнее миниатюр и удобны для пальца, но не больше 1/3 экрана.
        val cellW = max(parent.width(), dp(108f)).coerceAtMost(min(dp(180f), rootW / 3f))
        val cellH = cellW * 0.8f
        val maxW = rootW - 2 * margin
        val fitCols = max(1, ((maxW - 2 * pad + gap) / (cellW + gap)).toInt())
        val wanted = if (count <= 3) max(1, count) else ceil(sqrt(count.toFloat())).toInt().coerceAtLeast(3)
        val cols = min(fitCols, wanted)
        val rows = if (count == 0) 0 else ceil(count / cols.toFloat()).toInt()
        grid.configure(cols, cellW.toInt(), cellH.toInt(), gap.toInt(), pad.toInt())

        val panelW = if (count == 0) min(maxW, dp(380f)) else min(maxW, cols * cellW + (cols - 1) * gap + 2 * pad)
        val headerH = dp(61f) + titleExtra(panelW)
        val contentH = if (count == 0) dp(84f) else rows * cellH + (rows - 1) * gap + 2 * pad
        val panelH = min(rootH - 2 * margin, headerH + contentH)

        val cx = parent.centerX()
        var x = (cx - panelW / 2).coerceIn(margin, rootW - margin - panelW)
        var y: Float
        val above = parent.top - gap - panelH
        val below = parent.bottom + gap
        when {
            above >= margin -> y = above
            below + panelH <= rootH - margin -> y = below
            else -> {
                // Сбоку от родителя, если там хватает места; иначе по центру экрана.
                val leftX = parent.left - gap - panelW
                val rightX = parent.right + gap
                y = (parent.centerY() - panelH / 2).coerceIn(margin, rootH - margin - panelH)
                x = when {
                    rightX + panelW <= rootW - margin -> rightX
                    leftX >= margin -> leftX
                    else -> { y = (rootH - panelH) / 2; (rootW - panelW) / 2 }
                }
            }
        }
        panel.layoutParams = FrameLayout.LayoutParams(panelW.toInt(), panelH.toInt())
        panel.x = x
        panel.y = y
    }

    /**
     * Заголовок до двух строк: шрифт 20sp уменьшается до 14sp, пока название не поместится.
     * Возвращает дополнительную высоту второй строки.
     */
    private fun titleExtra(panelW: Float): Float {
        val title = titleView ?: return 0f
        val text = title.text.toString()
        val available = (panelW - title.paddingLeft - title.paddingRight).toInt().coerceAtLeast(1)
        var lines = 1
        for (sp in 20 downTo 14) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
            lines = android.text.StaticLayout.Builder.obtain(text, 0, text.length, title.paint, available).build().lineCount
            if (lines <= 2) break
        }
        return if (lines > 1) title.lineHeight.toFloat() else 0f
    }

    /**
     * Карточки вылетают из своих миниатюр одновременно, 220 мс. Карточки вне видимой области
     * прокрутки, исчезнувший родитель и прочие сложные случаи — простое появление.
     */
    private fun animateOpen() {
        val panel = panel ?: return
        val dim = dim ?: return
        dim.animate().alpha(DIM_ALPHA).setDuration(OPEN_MS).start()
        fadeChrome(0f, 1f, OPEN_MS)
        clone?.thumbnailsHidden = true
        children.forEach { it.alpha = 0f }
        panel.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                panel.viewTreeObserver.removeOnPreDrawListener(this)
                flight(opening = true, end = null)
                return true
            }
        })
    }

    fun close() {
        val id = tileId ?: return
        if (closing) return
        closing = true
        handler.removeCallbacks(collapse)
        host.closed(id)
        val panel = panel
        if (panel == null || !panel.isAttachedToWindow) { clear(); return }
        dim?.animate()?.alpha(0f)?.setDuration(CLOSE_MS)?.start()
        fadeChrome(1f, 0f, CLOSE_MS)
        flight(opening = false) { clear() }
    }

    /**
     * Фон, заголовок и разделитель панели появляются синхронно с полётом карточек.
     * Альфа самой панели не меняется, иначе летящие карточки тоже были бы прозрачными.
     */
    private fun fadeChrome(from: Float, to: Float, duration: Long) {
        val panel = panel ?: return
        val column = panel.getChildAt(0) as? ViewGroup ?: return
        val chrome = listOfNotNull(column.getChildAt(0), column.getChildAt(1), emptyView?.takeIf { it.visibility == View.VISIBLE })
        val background = panel.background
        val saved = panel.elevation
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            addUpdateListener {
                val f = it.animatedValue as Float
                background?.alpha = (f * 255).toInt()
                chrome.forEach { view -> view.alpha = f }
                panel.elevation = saved * f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { panel.elevation = saved * to }
            })
            start()
        }
    }

    private fun flight(opening: Boolean, end: (() -> Unit)?) {
        val root = root()
        val clone = clone
        val scroll = scroll
        val thumb = RectF()
        // Нетрансформированная сетка: место карточки не зависит от её прерванной анимации.
        val gridBounds = grid?.let { boundsInRoot(it, root) }
        setClipping(false)
        var running = 0
        val done = {
            running--
            if (running <= 0) {
                setClipping(true)
                if (opening) children.forEach { if (it.alpha < 1f) it.animate().alpha(1f).setDuration(150).start() }
                else clone?.thumbnailsHidden = false
                end?.invoke()
            }
        }
        children.forEachIndexed { i, child ->
            val visible = scroll != null && child.top + child.height > scroll.scrollY && child.top < scroll.scrollY + scroll.height
            val hasThumb = clone != null && clone.thumbRect(i, thumb)
            child.animate().cancel()
            if (clone == null || gridBounds == null || !visible || !hasThumb || children.size > MAX_FLIGHTS) {
                // Упрощённый путь: без выдуманной траектории.
                if (opening) {
                    child.alpha = 0f
                    if (visible) { child.scaleX = 0.9f; child.scaleY = 0.9f; child.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(OPEN_MS).start() }
                } else child.alpha = 0f
                return@forEachIndexed
            }
            val cx = gridBounds.left + child.left + child.width / 2f
            val cy = gridBounds.top + child.top + child.height / 2f
            val tx = clone.x + thumb.centerX() - cx
            val ty = clone.y + thumb.centerY() - cy
            val sx = thumb.width() / max(1f, child.width.toFloat())
            val sy = thumb.height() / max(1f, child.height.toFloat())
            running++
            val listener = object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { child.animate().setListener(null); done() }
            }
            if (opening) {
                child.translationX = tx; child.translationY = ty
                child.scaleX = sx; child.scaleY = sy
                child.alpha = 0.5f
                child.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(OPEN_MS).setInterpolator(DecelerateInterpolator()).setListener(listener).start()
            } else {
                child.animate().translationX(tx).translationY(ty).scaleX(sx).scaleY(sy).alpha(0.3f)
                    .setDuration(CLOSE_MS).setInterpolator(DecelerateInterpolator()).setListener(listener).start()
            }
        }
        if (running == 0) {
            // Нечего анимировать по траектории: завершаем после общего появления/исчезновения.
            running = 1
            handler.postDelayed({ done() }, if (opening) OPEN_MS else CLOSE_MS)
        }
    }

    private fun setClipping(clip: Boolean) {
        listOfNotNull<ViewGroup>(panel, scroll, grid, panel?.getChildAt(0) as? ViewGroup).forEach {
            it.clipChildren = clip
        }
    }

    /** Немедленная очистка без анимации: перестроение панели, уничтожение экрана. */
    fun clear() {
        handler.removeCallbacksAndMessages(null)
        val root = (panel?.parent ?: dim?.parent ?: clone?.parent) as? ViewGroup
        children.forEach { it.animate().setListener(null).cancel(); it.setOnClickListener(null); it.setOnTouchListener(null) }
        listOfNotNull(dim, clone, panel).forEach { view ->
            view.animate().setListener(null).cancel()
            view.setOnClickListener(null)
            (view.parent as? ViewGroup ?: root)?.removeView(view)
        }
        children.clear()
        dim = null; clone = null; panel = null; scroll = null; grid = null; titleView = null; emptyView = null
        source = null
        tileId = null
        entries = emptyList()
        closing = false
    }

    /** Сетка дочерних кнопок с фиксированной ячейкой; высота по числу строк. */
    private class Grid(context: Context) : ViewGroup(context) {
        private var cols = 1
        private var cellW = 0
        private var cellH = 0
        private var gap = 0
        private var pad = 0

        fun configure(cols: Int, cellW: Int, cellH: Int, gap: Int, pad: Int) {
            if (this.cols == cols && this.cellW == cellW && this.cellH == cellH && this.gap == gap && this.pad == pad) return
            this.cols = cols; this.cellW = cellW; this.cellH = cellH; this.gap = gap; this.pad = pad
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val rows = if (childCount == 0) 0 else (childCount + cols - 1) / cols
            val spec = { size: Int -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) }
            for (i in 0 until childCount) getChildAt(i).measure(spec(cellW), spec(cellH))
            val width = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, if (rows == 0) 0 else 2 * pad + rows * cellH + (rows - 1) * gap)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val used = cols * cellW + (cols - 1) * gap
            val startX = ((r - l) - used) / 2
            for (i in 0 until childCount) {
                val row = i / cols
                val col = i % cols
                val x = startX + col * (cellW + gap)
                val y = pad + row * (cellH + gap)
                getChildAt(i).layout(x, y, x + cellW, y + cellH)
            }
        }
    }

    companion object {
        const val AUTO_COLLAPSE_MS = 10_000L
        const val OPEN_MS = 220L
        const val CLOSE_MS = 220L
        const val DIM_ALPHA = 0.35f
        private const val PAD_DP = 14f
        private const val MAX_FLIGHTS = 40
        const val PANEL_BG = 0xFF13263A.toInt()
        const val PANEL_BORDER = 0xFF3A5672.toInt()
        private const val SCROLLBAR = 0xFF5B7C9C.toInt()
    }
}

/** Лёгкое сжатие Modern-плитки при касании; не мешает клику и перетаскиванию. */
internal object ModernPress {
    @SuppressLint("ClickableViewAccessibility")
    fun attach(view: View, enabled: () -> Boolean = { true }) {
        view.setOnTouchListener { v, event -> handle(v, event, enabled()); false }
    }

    fun handle(view: View, event: MotionEvent, enabled: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (enabled) {
                view.setTag(R.id.modern_press_started, android.os.SystemClock.uptimeMillis())
                view.animate().scaleX(PRESSED).scaleY(PRESSED).setDuration(PRESS_MS).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release(view)
        }
    }

    fun release(view: View) {
        val started = view.getTag(R.id.modern_press_started) as? Long ?: return
        view.setTag(R.id.modern_press_started, null)
        // Быстрый тап: сжатие доходит до конца, затем мягкое восстановление до исходного размера.
        val left = (PRESS_MS - (android.os.SystemClock.uptimeMillis() - started)).coerceIn(0L, PRESS_MS)
        view.animate().scaleX(PRESSED).scaleY(PRESSED).setDuration(left).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(RELEASE_MS).start()
        }.start()
    }

    private const val PRESSED = 0.965f
    private const val PRESS_MS = 100L
    private const val RELEASE_MS = 180L

    fun haptic(view: View) {
        if (ModernHaptics.enabled(view.context)) view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }
}
