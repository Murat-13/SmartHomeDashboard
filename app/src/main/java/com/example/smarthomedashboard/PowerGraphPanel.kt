package com.example.smarthomedashboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Блок «История мощности» в раскрытой карточке «Электропитание Modern» и в полноэкранном графике
 * (docs/power-graph.md): вкладки периода, Сейчас/Мин/Макс/Средняя, полотно [PowerGraphView], «Сейчас».
 * Данные — только из локальной истории ([PowerHistoryRecorder]); сети и команд HA блок не касается.
 * Выбранная вкладка запоминается для каждой карточки отдельно.
 */
internal class PowerGraphPanel(
    context: Context,
    private val recorder: PowerHistoryRecorder?,
    private val tileId: String,
    val state: PowerGraphState,
    private val prefs: SharedPreferences?,
    private val fullscreen: Boolean = false,
    private val onExpand: (() -> Unit)? = null,
    private val onTouch: () -> Unit = {}
) : LinearLayout(context) {
    var clock: () -> Long = System::currentTimeMillis
        set(value) { field = value; graph.clock = value }
    private val handler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    val graph = PowerGraphView(context, state).apply { tag = "pg_graph" }
    private val pills = GraphPeriod.entries.associateWith { pill(it) }
    private val nowValue = statValue("pg_now")
    private val minValue = statValue("pg_min")
    private val maxValue = statValue("pg_max")
    private val avgValue = statValue("pg_avg")
    private val nowButton = TextView(context).apply {
        tag = "pg_live"
        text = "◉ Сейчас"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        minHeight = dp(48)
        background = GradientDrawable().apply {
            setColor(Color.argb(200, 20, 110, 140)); cornerRadius = dp(20).toFloat(); setStroke(dp(1), PowerGraphView.POSITIVE)
        }
        setOnClickListener { onTouch(); state.reset(clock()); state.cursor = null; changed(reloadData = true) }
    }
    private val hint = TextView(context).apply {
        text = "Свайп — история · 2 пальца — масштаб · касание — значение"
        setTextColor(Color.argb(160, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    private var entityId = ""
    private var loaded: LongRange? = null
    private var loadedSpan = 0L
    private var request = 0
    private var statsRequest = 0
    private var statsWindow: LongRange? = null
    private var attached = false

    private val listener = PowerHistoryRecorder.Listener { id, sample ->
        if (id != entityId) return@Listener
        if (sample == null) { loaded = null; changed(reloadData = true); return@Listener }
        append(sample)
    }
    private val reloadTask = Runnable { load() }
    private var statsPending = false
    private val statsTask = Runnable { statsPending = false; loadStats() }

    init {
        orientation = VERTICAL
        tag = if (fullscreen) "pg_fullscreen_panel" else "pg_panel"
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = "История мощности"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (fullscreen) 22f else 18f)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            tag = if (fullscreen) "pg_close" else "pg_expand"
            text = if (fullscreen) "✕" else "⛶"
            contentDescription = if (fullscreen) "Закрыть полноэкранный график" else "Развернуть график"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.argb(120, 20, 60, 80)); cornerRadius = dp(10).toFloat(); setStroke(dp(1), PowerGraphView.POSITIVE) }
            visibility = if (onExpand == null) GONE else VISIBLE
            setOnClickListener { onTouch(); onExpand?.invoke() }
        }, LayoutParams(dp(48), dp(48)))
        addView(header)

        val pillRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        pills.values.forEach { pillRow.addView(it, LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }) }
        addView(pillRow)

        val stats = LinearLayout(context).apply { orientation = HORIZONTAL }
        listOf("Сейчас" to nowValue, "Мин" to minValue, "Макс" to maxValue, "Средняя" to avgValue).forEach { (name, value) ->
            stats.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply { setColor(Color.argb(110, 5, 20, 34)); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.argb(70, 255, 255, 255)) }
                addView(TextView(context).apply { text = name; setTextColor(Color.argb(170, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) })
                addView(value)
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) })
        }
        addView(stats)
        addView(graph, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        val footer = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        footer.addView(hint, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(nowButton)
        addView(footer)

        graph.onInteraction = onTouch
        graph.onViewportChanged = { changed(reloadData = false) }
        graph.onCursorChanged = {}
        if (state.span == 0L) state.reset(clock())
        renderControls()
    }

    /** Состояние просмотра менялось в другом блоке (полноэкранный график): перечитать. */
    fun refresh() {
        loaded = null
        changed(reloadData = true)
    }

    /** Источник графика карточки; пусто — подсказка вместо графика. */
    fun bind(source: String) {
        if (source == entityId) return
        entityId = source
        loaded = null
        state.cursor = null
        changed(reloadData = true)
    }

    private fun pill(period: GraphPeriod) = TextView(context).apply {
        tag = "pg_period_${period.key}"
        text = period.label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setOnClickListener {
            onTouch()
            if (state.period != period) {
                state.select(period, clock())
                prefs?.edit()?.putString(prefKey(tileId), period.key)?.apply()
                changed(reloadData = true)
            }
        }
    }

    private fun statValue(id: String) = TextView(context).apply {
        tag = id
        text = "—"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        maxLines = 1
    }

    private fun renderControls() {
        pills.forEach { (period, view) ->
            val active = period == state.period
            view.setTextColor(if (active) Color.WHITE else Color.argb(200, 255, 255, 255))
            view.background = GradientDrawable().apply {
                setColor(if (active) Color.rgb(29, 140, 190) else Color.argb(90, 10, 40, 60))
                cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.argb(if (active) 255 else 110, 79, 209, 245))
            }
            view.isSelected = active
        }
        nowButton.visibility = if (state.live) INVISIBLE else VISIBLE
        val latest = recorder?.latest(entityId)
        setStat(nowValue, latest)
    }

    private fun setStat(view: TextView, watts: Double?) {
        view.text = PowerHistory.formatWatts(watts)
        view.setTextColor(when { watts == null -> Color.WHITE; watts < 0 -> PowerGraphView.NEGATIVE; watts > 0 -> PowerGraphView.POSITIVE; else -> Color.WHITE })
    }

    /** Участок/период/источник изменился: элементы управления сразу, данные — без лишних запросов. */
    private fun changed(reloadData: Boolean) {
        renderControls()
        val now = clock()
        val view = state.viewport(now)
        val need = reloadData || loaded == null || view.first < loaded!!.first || view.last > loaded!!.last ||
            state.span * 2 < loadedSpan || state.span > loadedSpan * 2
        if (need) {
            handler.removeCallbacks(reloadTask)
            if (reloadData) load() else handler.postDelayed(reloadTask, RELOAD_DEBOUNCE_MS)
        }
        if (state.statsWindow(now) != statsWindow) loadStats()
        graph.invalidate()
    }

    private fun load() {
        val history = recorder
        if (entityId.isEmpty() || history == null) {
            graph.series = PowerSeries(emptyList(), emptyList())
            graph.message = if (entityId.isEmpty()) "Выберите источник графика в настройках карточки" else "История недоступна"
            return
        }
        val now = clock()
        val view = state.viewport(now)
        val margin = state.span / 2
        val range = (view.first - margin) until (view.last + 1 + margin)
        val id = ++request
        val span = state.span
        history.series(entityId, range.first, range.last + 1, (graph.width.coerceAtLeast(600) * 4)) { series ->
            if (id != request) return@series
            loaded = range
            loadedSpan = span
            graph.series = series
            graph.message = if (series.isEmpty) "Нет данных за этот участок — история копится с момента установки" else null
        }
        loadStats()
    }

    private fun loadStats() {
        val history = recorder ?: return
        if (entityId.isEmpty()) { listOf(minValue, maxValue, avgValue).forEach { setStat(it, null) }; return }
        val now = clock()
        val window = state.statsWindow(now)
        statsWindow = window
        val id = ++statsRequest
        history.stats(entityId, window.first, window.last + 1) { stats ->
            if (id != statsRequest) return@stats
            setStat(minValue, stats.min)
            setStat(maxValue, stats.max)
            setStat(avgValue, stats.avg)
        }
    }

    /** Новое значение источника: дописывается в загруженные данные; прошлый участок не сдвигается. */
    private fun append(sample: PowerSample) {
        setStat(nowValue, sample.watts)
        val range = loaded ?: return
        if (sample.ts > range.last + 1 + state.span) return
        val current = graph.series
        if (current.samples.lastOrNull()?.let { it.ts >= sample.ts } == true) return
        graph.series = current.copy(samples = current.samples + sample)
        if (state.live) {
            // Статистика текущего периода пересчитывается не чаще раза в несколько секунд.
            if (!statsPending) { statsPending = true; handler.postDelayed(statsTask, STATS_THROTTLE_MS) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        recorder?.addListener(listener)
        changed(reloadData = true)
    }

    override fun onDetachedFromWindow() {
        attached = false
        recorder?.removeListener(listener)
        handler.removeCallbacks(reloadTask)
        handler.removeCallbacks(statsTask)
        statsPending = false
        super.onDetachedFromWindow()
    }

    companion object {
        const val RELOAD_DEBOUNCE_MS = 120L
        const val STATS_THROTTLE_MS = 5_000L
        fun prefKey(tileId: String) = "power_graph_period_$tileId"

        /** Сохранённая вкладка карточки; при первом использовании — `1ч`. */
        fun savedPeriod(prefs: SharedPreferences?, tileId: String) = GraphPeriod.of(prefs?.getString(prefKey(tileId), null))
    }
}
