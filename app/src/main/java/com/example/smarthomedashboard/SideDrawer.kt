package com.example.smarthomedashboard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * Левая системная панель поверх плиток: статусы, настройки, «+», редактирование, выход из админ-режима.
 * Плитки не сдвигаются; за панелью — затемнение, тап по нему только закрывает панель.
 * Открывается жестом двумя пальцами (его распознаёт [TwoFingerSwipe]) или тапом по кромке.
 */
internal class SideDrawer(private val root: FrameLayout, private val host: Host) {
    interface Host {
        fun settings()
        fun addTile()
        fun edit()
        fun endAdmin()
        fun adminActive(): Boolean
        /** Открыт диалог (PIN и т. п.): автозакрытие ждёт. */
        fun busy(): Boolean
        fun stateChanged(open: Boolean) {}
        /** Подпись текущей страницы, например «Страница 2 из 3»; null — не показывать. */
        fun pageText(): String? = null
    }

    private val context = root.context
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val width = dp(200)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { if (host.busy()) scheduleAutoClose() else close() }

    /** Сдвиг панели: -width — закрыта, 0 — открыта. */
    var offset = -width.toFloat()
        private set
    val isOpen get() = offset > -width + 0.5f || runner.running
    private var shown = false

    private val scrim = View(context).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
        visibility = View.GONE
        isClickable = true
        contentDescription = "Закрыть панель"
        setOnClickListener { close() }
    }

    val haRow = ModernUi.statusRow(context, ModernUi.Status.UNKNOWN, "HA: нет данных")
    val routeRow = ModernUi.statusRow(context, ModernUi.Status.UNKNOWN, "Адрес: —")
    val internetRow = ModernUi.statusRow(context, ModernUi.Status.UNKNOWN, "Интернет: нет данных")
    val backupRow = ModernUi.statusRow(context, ModernUi.Status.UNKNOWN, "Бэкап: не настроен")
    /** Видна, только когда обновление скачано и проверено. */
    val updateRow = ModernUi.statusRow(context, ModernUi.Status.PROGRESS, "").apply { visibility = View.GONE }
    private val pageLabel = ModernUi.caption(context, "")
    private val endAdmin = ModernUi.secondaryButton(context, "Завершить админ-режим") { host.endAdmin(); refreshAdmin() }

    val panel: View = ScrollView(context).apply {
        background = GradientDrawable().apply {
            setColor(ModernUi.color(context, R.color.modern_surface))
            cornerRadii = floatArrayOf(0f, 0f, dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), 0f, 0f)
            setStroke(1, ModernUi.color(context, R.color.modern_border))
        }
        elevation = dp(16).toFloat()
        translationX = offset
        visibility = View.INVISIBLE
        isClickable = true // касания внутри панели не уходят плиткам
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            addView(ModernUi.caption(context, "Состояние"))
            listOf(haRow, routeRow, internetRow, backupRow, updateRow).forEach { addView(it) }
            fun action(view: View) = addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(View(context), LinearLayout.LayoutParams(1, dp(8)))
            action(ModernUi.secondaryButton(context, "⚙  Настройки") { close(); host.settings() })
            action(ModernUi.secondaryButton(context, "+  Добавить") { close(); host.addTile() })
            action(ModernUi.secondaryButton(context, "Редактировать панель") { close(); host.edit() })
            action(endAdmin)
            addView(pageLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        }
        addView(column)
    }

    /** Едва заметная кромка у левого края; нажимается зона шире полоски (см. [edgeTap]). */
    private val handle = View(context).apply {
        background = GradientDrawable().apply {
            setColor(ModernUi.color(context, R.color.modern_accent))
            cornerRadius = dp(3).toFloat()
        }
        alpha = 0.35f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val runner = SpringMotion.Runner(::apply) { settled() }

    init {
        root.addView(handle, FrameLayout.LayoutParams(dp(5), dp(72), Gravity.START or Gravity.CENTER_VERTICAL)
            .apply { marginStart = dp(1) })
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
    }

    fun open() = settle(0f, 0f)
    fun close() = settle(-width.toFloat(), 0f)

    /** Жест открытия: панель следует за пальцами; дальше открытого положения — с сопротивлением. */
    fun dragOpen(dx: Float) {
        runner.cancel()
        prepare()
        apply(-width + dx)
    }

    /** Отпускание: больше половины пути или быстрый рывок — до конца, иначе назад. */
    fun release(velocityX: Float) {
        val target = when {
            velocityX > FLING -> 0f
            velocityX < -FLING -> -width.toFloat()
            offset > -width / 2f -> 0f
            else -> -width.toFloat()
        }
        settle(target, velocityX)
    }

    private fun settle(target: Float, velocity: Float) {
        prepare()
        if (target == 0f) { refreshAdmin(); refreshPage() }
        runner.start(offset, target, velocity)
    }

    private fun prepare() {
        handler.removeCallbacks(autoClose)
        if (shown) return
        shown = true
        scrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        handle.visibility = View.INVISIBLE
        host.stateChanged(true)
    }

    private fun apply(value: Float) {
        // Сопротивление: за открытым положением панель уходит лишь на треть движения пальца.
        offset = if (value > 0f) value / 3f else value.coerceAtLeast(-width.toFloat())
        panel.translationX = offset
        scrim.alpha = SCRIM * (1f + offset / width).coerceIn(0f, 1f)
    }

    private fun settled() {
        if (offset <= -width + 0.5f) {
            shown = false
            scrim.visibility = View.GONE
            panel.visibility = View.INVISIBLE
            handle.visibility = View.VISIBLE
            handler.removeCallbacks(autoClose)
            host.stateChanged(false)
        } else scheduleAutoClose()
    }

    private fun scheduleAutoClose() {
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    fun refreshPage() {
        val text = host.pageText()
        pageLabel.text = text.orEmpty()
        pageLabel.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    fun refreshAdmin() {
        endAdmin.visibility = if (host.adminActive()) View.VISIBLE else View.GONE
    }

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartOffset = 0f
    private var dragging = false

    /**
     * Касания при открытой панели: любое касание продлевает 10 секунд; свайп одним пальцем справа налево
     * тянет панель закрываться. true — событие забрано (тогда кнопки панели получают отмену).
     */
    fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                runner.cancel()
                handler.removeCallbacks(autoClose)
                dragStartX = event.rawX
                dragStartY = event.rawY
                dragStartOffset = offset
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartX
                if (!dragging && dx < -slop * 2 && abs(dx) > abs(event.rawY - dragStartY)) {
                    dragging = true
                    cancelChildren(event)
                }
                if (dragging) {
                    apply((dragStartOffset + dx).coerceAtMost(0f))
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    settle(if (offset < -width / 3f) -width.toFloat() else 0f, 0f)
                    return true
                }
                if (!runner.running && offset > -width + 0.5f) scheduleAutoClose()
            }
        }
        return dragging
    }

    private fun cancelChildren(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        try { root.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
    }

    private var edgeArmed = false

    /**
     * Тап по кромке (зона ~40dp у левого края по центру), если под пальцем нет плитки. Касание
     * забирается целиком, чтобы не запускать долгое нажатие фона.
     */
    fun edgeTap(event: MotionEvent, overTile: (Float, Float) -> Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val centerY = root.height / 2f
                edgeArmed = event.x <= dp(40) && abs(event.y - centerY) <= dp(80) && !overTile(event.x, event.y)
                if (edgeArmed) { dragStartX = event.rawX; dragStartY = event.rawY }
                return edgeArmed
            }
            MotionEvent.ACTION_MOVE -> if (edgeArmed) {
                // Жест вправо от кромки тоже открывает панель.
                if (event.rawX - dragStartX > slop * 2) { edgeArmed = false; open() }
                return true
            }
            MotionEvent.ACTION_UP -> if (edgeArmed) {
                edgeArmed = false
                if (abs(event.rawY - dragStartY) < slop * 2) open()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> if (edgeArmed) {
                edgeArmed = false
                return true
            }
        }
        return false
    }

    fun dispose() {
        runner.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val AUTO_CLOSE_MS = 10_000L
        private const val SCRIM = 0.3f
        private const val FLING = 1000f
    }
}
