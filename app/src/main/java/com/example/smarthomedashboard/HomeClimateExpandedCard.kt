package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

/**
 * Развёрнутая плитка «Часы · Погода · Климат дома» — показывается общим раскрытием Modern
 * ([BatteryModernExpansion]) поверх размытой панели. Порядок блоков по ТЗ: часы и текущая погода,
 * почасовой прогноз (горизонтальная прокрутка), прогноз на 5 дней, компактный список всех комнат
 * (вертикальная прокрутка, RecyclerView). Обновления данных меняют View на месте: прокрутка не сбрасывается.
 */
internal class HomeClimateExpandedCard(context: Context, private val now: () -> Date = { Date() }) : FrameLayout(context), ModernOverlayCard {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()
    private var snapshot: HomeClimateSnapshot? = null
    private var tickMinute = -1L
    private val tick = Runnable {
        renderClock()
        val minute = System.currentTimeMillis() / 60_000L
        if (minute != tickMinute) {
            tickMinute = minute
            snapshot?.let { renderHourly(it); renderDaily(it) }
        }
        scheduleTick()
    }
    private lateinit var top: LinearLayout
    private lateinit var clockWeather: LinearLayout
    private lateinit var headerScroller: ScrollView
    private var headerLimit = Int.MAX_VALUE
    private var narrowLayout: Boolean? = null

    // ---- Часы и текущая погода ----
    private val time = text(56f, TIME, bold = true).apply { tag = "hc_time"; maxLines = 1 }
    private val date = text(18f, DATE).apply { tag = "hc_date"; maxLines = 1 }
    private val glyph = GlyphView(context)
    private val temperature = text(40f, TIME, bold = true).apply { tag = "hc_temp"; maxLines = 1 }
    private val condition = text(17f, DATE).apply { tag = "hc_condition"; maxLines = 1 }
    private val feelsLike = text(15f, MUTED).apply { tag = "hc_feels"; maxLines = 1 }
    private val details = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; tag = "hc_details" }
    private val status = text(14f, WARN).apply { tag = "hc_status"; visibility = GONE }

    // ---- Прогнозы ----
    private val hourlyTitle = section("Почасовой прогноз")
    private val hourlyNote = text(14f, MUTED).apply { tag = "hc_hourly_note"; visibility = GONE }
    private val hourlyRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; tag = "hc_hourly_row" }
    val hourlyScroll = HorizontalScrollView(context).apply {
        tag = "hc_hourly_scroll"
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(hourlyRow)
    }
    private val dailyTitle = section("Прогноз на 5 дней")
    private val dailyNote = text(14f, MUTED).apply { tag = "hc_daily_note"; visibility = GONE }
    private val dailyRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; tag = "hc_daily_row" }

    // ---- Комнаты ----
    private val roomsTitle = section("Температура в помещениях")
    private val roomsNote = text(14f, MUTED).apply { tag = "hc_rooms_note"; visibility = GONE }
    private val roomsHeader = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; tag = "hc_rooms_header" }
    private val roomsAdapter = RoomsAdapter()
    val roomsList = RecyclerView(context).apply {
        tag = "hc_rooms_list"
        layoutManager = LinearLayoutManager(context)
        adapter = roomsAdapter
        itemAnimator = null
        isVerticalScrollBarEnabled = true
        overScrollMode = OVER_SCROLL_NEVER
    }

    private var hourlyKey: Any? = null
    private var dailyKey: Any? = null
    private var detailsKey: Any? = null

    init {
        tag = "hc_expanded"
        background = GradientDrawable().apply {
            colors = intArrayOf(0xFF0C1E3A.toInt(), 0xFF07142A.toInt())
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            cornerRadius = dp(28f).toFloat()
            setStroke(dp(2f), NEON)
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(18f), dp(22f), dp(18f))
        }

        top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        clockWeather = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val clockBox = box().apply {
            gravity = Gravity.CENTER
            addView(time)
            addView(date)
        }
        clockWeather.addView(clockBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f))
        val weatherBox = box().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(glyph, LinearLayout.LayoutParams(dp(78f), dp(78f)).apply { rightMargin = dp(14f) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(temperature)
                addView(condition)
                addView(feelsLike)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        clockWeather.addView(weatherBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f).apply { leftMargin = dp(12f) })
        top.addView(clockWeather, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.5f))
        top.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.1f).apply { leftMargin = dp(12f) })
        val summary = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        summary.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132f)))
        summary.addView(status)

        summary.addView(hourlyTitle)
        summary.addView(hourlyNote)
        summary.addView(hourlyScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        summary.addView(dailyTitle)
        summary.addView(dailyNote)
        summary.addView(dailyRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // На низких экранах верхние разделы прокручиваются, оставляя место списку комнат.
        headerScroller = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                    minOf(MeasureSpec.getSize(heightMeasureSpec), headerLimit), MeasureSpec.AT_MOST))
            }
        }.apply { tag = "hc_summary_scroll"; addView(summary); isFillViewport = false }
        column.addView(headerScroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(roomsTitle)
        roomsHeader.addView(text(14f, MUTED).apply { text = "Комната"; maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.2f))
        roomsHeader.addView(text(14f, MUTED).apply { text = "Температура"; maxLines = 1; gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        roomsHeader.addView(text(14f, MUTED).apply { text = "Влажность"; maxLines = 1; gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        roomsHeader.setPadding(dp(12f), dp(2f), dp(12f), dp(4f))
        column.addView(roomsHeader)
        column.addView(roomsNote)
        column.addView(roomsList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(2f) })
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Крестик — подсказка: закрывает, как и любой тап по раскрытой карточке (общая механика).
        addView(text(26f, DATE).apply { text = "✕"; gravity = Gravity.CENTER; contentDescription = "Закрыть" },
            LayoutParams(dp(44f), dp(44f), Gravity.TOP or Gravity.END).apply { topMargin = dp(8f); rightMargin = dp(10f) })
    }

    fun bind(next: HomeClimateSnapshot) {
        snapshot = next
        renderClock()
        val current = next.current
        val weather = next.config.weather
        glyph.glyph = current.glyph
        glyph.dim = !current.available
        temperature.text = current.temperature?.let { HomeClimateData.number(it.value, 1) + " " + it.unit } ?: HomeClimateData.DASH
        temperature.setTextColor(if (current.temperature != null) TEMP else MUTED)
        condition.text = current.status ?: HomeClimateData.conditionText(current.condition)
        feelsLike.text = current.feelsLike?.let { "Ощущается как " + HomeClimateData.number(it.value, 0) + "°" }.orEmpty()
        feelsLike.visibility = if (weather.showFeelsLike && current.feelsLike != null) VISIBLE else GONE
        status.text = next.offlineStatus?.let { "$it — показания и прогнозы могут быть неактуальны" }.orEmpty()
        status.visibility = if (next.offlineStatus != null) VISIBLE else GONE

        renderDetails(next)
        renderHourly(next)
        renderDaily(next)
        roomsAdapter.submit(next.rooms)
        roomsNote.text = "Комнаты не добавлены: откройте редактор плитки (режим редактирования → тап по плитке)"
        roomsNote.visibility = if (next.rooms.isEmpty()) VISIBLE else GONE
        roomsHeader.visibility = if (next.rooms.isEmpty()) GONE else VISIBLE
        // Колонка влажности не нужна, если ни у одной комнаты нет датчика.
        roomsHeader.getChildAt(2).visibility = if (next.rooms.any { it.humidity != null }) VISIBLE else INVISIBLE
        scheduleTick()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val narrow = MeasureSpec.getSize(widthMeasureSpec) / density < 760f
        if (narrowLayout != narrow) {
            narrowLayout = narrow
            top.orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            top.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (narrow) 222f else 132f))
            clockWeather.layoutParams = if (narrow) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(124f))
                else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.5f)
            details.layoutParams = if (narrow) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88f)).apply { topMargin = dp(8f) }
                else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.1f).apply { leftMargin = dp(12f) }
        }
        headerLimit = (MeasureSpec.getSize(heightMeasureSpec) * if (MeasureSpec.getSize(heightMeasureSpec) / density < 420f) 0.5f else 0.62f).toInt().coerceAtLeast(1)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    val boundSnapshot get() = snapshot

    private fun renderDetails(snap: HomeClimateSnapshot) {
        val weather = snap.config.weather
        val current = snap.current
        val items = buildList {
            if (weather.showHumidity && current.humidity != null) add(Triple(ModernIcon.WATER, "Влажность", current.humidity to null))
            if (weather.showWind && current.wind != null) add(Triple(null, "Ветер", current.wind to current.windDirection))
            if (weather.showPrecipitation && current.precipitation != null) add(Triple(ModernIcon.WATER, "Осадки", current.precipitation to null))
            if (weather.showPressure && current.pressure != null) add(Triple(null, "Давление", current.pressure to current.pressureUnit))
        }
        val key = items.map { it.second to it.third }
        if (key == detailsKey) return
        detailsKey = key
        details.removeAllViews()
        items.forEachIndexed { index, (_, label, values) ->
            val tile = box().apply {
                gravity = Gravity.CENTER
                addView(text(14f, MUTED).apply { text = label; gravity = Gravity.CENTER; maxLines = 1 })
                addView(text(24f, TIME, bold = true).apply { text = values.first; gravity = Gravity.CENTER; maxLines = 1 })
                values.second?.let { addView(text(13f, MUTED).apply { text = it; gravity = Gravity.CENTER; maxLines = 1 }) }
            }
            details.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (index > 0) leftMargin = dp(8f)
            })
        }
    }

    private fun note(state: HomeClimateSnapshot.ForecastState, unsupported: String): String = when (state) {
        HomeClimateSnapshot.ForecastState.NOT_CONFIGURED -> "Погодная сущность не выбрана"
        HomeClimateSnapshot.ForecastState.WAITING -> "Ожидание данных Home Assistant…"
        HomeClimateSnapshot.ForecastState.UNSUPPORTED -> unsupported
        HomeClimateSnapshot.ForecastState.EMPTY -> "Нет данных прогноза"
        else -> ""
    }

    private fun renderHourly(snap: HomeClimateSnapshot) {
        val source = snap.hourly
        val currentItems = source.items.filter { it.time.time > System.currentTimeMillis() - 3_600_000L }
        val forecasts = if (source.state == HomeClimateSnapshot.ForecastState.READY && currentItems.isEmpty())
            HomeClimateSnapshot.Forecasts(HomeClimateSnapshot.ForecastState.EMPTY) else source.copy(items = currentItems)
        val shown = forecasts.state != HomeClimateSnapshot.ForecastState.HIDDEN
        hourlyTitle.visibility = if (shown) VISIBLE else GONE
        val ready = forecasts.state == HomeClimateSnapshot.ForecastState.READY
        hourlyScroll.visibility = if (shown && ready) VISIBLE else GONE
        hourlyNote.visibility = if (shown && !ready) VISIBLE else GONE
        hourlyNote.text = note(forecasts.state, "Эта погодная интеграция не предоставляет почасовой прогноз")
        val clock = snap.config.clock
        val system24 = DateFormat.is24HourFormat(context)
        val key = listOf(forecasts.items, clock, system24)
        if (key == hourlyKey) return
        // Новый набор часов перестраивает строку на месте; горизонтальная прокрутка сохраняется.
        hourlyKey = key
        hourlyRow.removeAllViews()
        forecasts.items.forEachIndexed { index, item ->
            val card = forecastCard(selected = index == 0).apply {
                addView(text(14f, DATE).apply {
                    text = HomeClimateData.hourLabel(item.time, clock, system24)
                    gravity = Gravity.CENTER; maxLines = 1
                })
                addView(GlyphView(context).apply { glyph = item.glyph }, LinearLayout.LayoutParams(dp(34f), dp(34f)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4f); bottomMargin = dp(4f)
                })
                addView(text(18f, TIME, bold = true).apply {
                    text = item.temperature?.short() ?: HomeClimateData.DASH; gravity = Gravity.CENTER; maxLines = 1
                })
                item.precipitationProbability?.takeIf { it > 0 }?.let { p ->
                    addView(text(12f, RAIN).apply { text = HomeClimateData.number(p, 0) + " %"; gravity = Gravity.CENTER; maxLines = 1 })
                }
            }
            hourlyRow.addView(card, LinearLayout.LayoutParams(dp(70f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) leftMargin = dp(8f)
            })
        }
    }

    private fun renderDaily(snap: HomeClimateSnapshot) {
        val source = snap.daily
        val zone = HomeClimateData.zone(snap.config.clock)
        val today = java.util.Calendar.getInstance(zone)
        fun day(date: Date) = java.util.Calendar.getInstance(zone).apply { time = date }.let {
            it.get(java.util.Calendar.YEAR) * 1000 + it.get(java.util.Calendar.DAY_OF_YEAR)
        }
        val currentItems = source.items.filter { day(it.time) >= day(today.time) }
        val forecasts = if (source.state == HomeClimateSnapshot.ForecastState.READY && currentItems.isEmpty())
            HomeClimateSnapshot.Forecasts(HomeClimateSnapshot.ForecastState.EMPTY) else source.copy(items = currentItems)
        val shown = forecasts.state != HomeClimateSnapshot.ForecastState.HIDDEN
        dailyTitle.visibility = if (shown) VISIBLE else GONE
        val ready = forecasts.state == HomeClimateSnapshot.ForecastState.READY
        dailyRow.visibility = if (shown && ready) VISIBLE else GONE
        dailyNote.visibility = if (shown && !ready) VISIBLE else GONE
        dailyNote.text = note(forecasts.state, "Эта погодная интеграция не предоставляет прогноз по дням")
        val key = listOf(forecasts.items, snap.config.clock)
        if (key == dailyKey) return
        dailyKey = key
        dailyRow.removeAllViews()
        forecasts.items.forEachIndexed { index, item ->
            val card = forecastCard(selected = false).apply {
                addView(text(15f, DATE).apply { text = HomeClimateData.dayLabel(item.time, snap.config.clock); gravity = Gravity.CENTER; maxLines = 1 })
                addView(GlyphView(context).apply { glyph = item.glyph }, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4f); bottomMargin = dp(4f)
                })
                val high = item.temperature?.short() ?: HomeClimateData.DASH
                val low = item.low?.short()
                addView(text(18f, TIME, bold = true).apply {
                    text = if (low != null) "$high / $low" else high
                    gravity = Gravity.CENTER; maxLines = 1
                })
            }
            dailyRow.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) leftMargin = dp(8f)
            })
        }
    }

    private fun renderClock() {
        val clock = snapshot?.config?.clock ?: return
        val now = now()
        val system24 = DateFormat.is24HourFormat(context)
        time.text = HomeClimateData.time(clock, system24, now) + (HomeClimateData.amPm(clock, system24, now)?.let { " $it" } ?: "")
        date.text = HomeClimateData.date(clock, now)
    }

    private fun scheduleTick() {
        removeCallbacks(tick)
        val clock = snapshot?.config?.clock ?: return
        if (!isAttachedToWindow || windowVisibility != VISIBLE || !isShown) return
        postDelayed(tick, HomeClimateCompactView.delayToNextTick(clock.seconds))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderClock()
        scheduleTick()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (snapshot != null) { renderClock(); scheduleTick() }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun dispose() {
        removeCallbacks(tick)
        roomsList.adapter = null
    }

    // ---- Вспомогательные View ----

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, minOf(10, sp.toInt() - 1), sp.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
    }

    private fun section(title: String) = text(18f, TIME, bold = true).apply {
        text = title
        setPadding(dp(2f), dp(12f), 0, dp(6f))
    }

    private fun box() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        background = GradientDrawable().apply {
            setColor(BOX)
            cornerRadius = dp(16f).toFloat()
            setStroke(dp(1f), BOX_BORDER)
        }
    }

    private fun forecastCard(selected: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
        background = GradientDrawable().apply {
            setColor(BOX)
            cornerRadius = dp(14f).toFloat()
            setStroke(dp(if (selected) 2f else 1f), if (selected) NEON else BOX_BORDER)
        }
    }

    /** Погодный значок общего контурного семейства. */
    private class GlyphView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var glyph: WeatherGlyph = WeatherGlyph.UNKNOWN
            set(value) { if (field != value) { field = value; invalidate() } }
        var dim = false
            set(value) { if (field != value) { field = value; invalidate() } }

        override fun onDraw(canvas: Canvas) {
            val size = minOf(width, height).toFloat()
            glyph.draw(canvas, (width - size) / 2, (height - size) / 2, size, paint, dim)
        }
    }

    /** Иконка комнаты из набора Modern. */
    private class IconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var icon: ModernIcon? = null
            set(value) { if (field != value) { field = value; invalidate() } }
        var color = NEON
            set(value) { if (field != value) { field = value; invalidate() } }

        override fun onDraw(canvas: Canvas) {
            val current = icon ?: return
            val size = minOf(width, height).toFloat()
            paint.color = color
            current.draw(canvas, (width - size) / 2, (height - size) / 2, size, paint)
        }
    }

    /** Компактные строки «Комната | Температура | Влажность»; число комнат не ограничено. */
    private inner class RoomsAdapter : RecyclerView.Adapter<RoomsAdapter.Holder>() {
        private var lines: List<HomeClimateSnapshot.RoomLine> = emptyList()

        fun submit(next: List<HomeClimateSnapshot.RoomLine>) {
            if (next == lines) return
            val sameRooms = next.size == lines.size && next.indices.all { next[it].room == lines[it].room }
            val old = lines
            lines = next
            // Новые показания тех же комнат — адресные изменения строк, прокрутка не сбрасывается.
            if (sameRooms) next.indices.filter { next[it] != old[it] }.forEach(::notifyItemChanged)
            else notifyDataSetChanged()
        }

        override fun getItemCount() = lines.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = IconView(parent.context)
            val name = text(17f, NAME).apply { maxLines = 1 }
            val temp = text(17f, TEMP, bold = true).apply { gravity = Gravity.END; maxLines = 1 }
            val humidity = text(17f, DATE).apply { gravity = Gravity.END; maxLines = 1 }
            row.addView(icon, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { rightMargin = dp(12f) })
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.2f))
            row.addView(temp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(humidity, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return Holder(row, icon, name, temp, humidity)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val line = lines[position]
            holder.icon.icon = line.room.modernIcon
            holder.icon.color = HomeClimateCompactView.ROOM_COLORS[position % HomeClimateCompactView.ROOM_COLORS.size]
            holder.name.text = line.room.name
            holder.temp.text = line.temperature?.full() ?: HomeClimateData.DASH
            holder.temp.setTextColor(if (line.temperature != null) TEMP else MUTED)
            // Без датчика влажности — пусто (не «0 %»); датчик недоступен — «—».
            holder.humidity.text = line.humidity.orEmpty()
            holder.itemView.setBackgroundColor(if (position % 2 == 0) STRIPE else Color.TRANSPARENT)
        }

        inner class Holder(view: View, val icon: IconView, val name: TextView, val temp: TextView, val humidity: TextView) :
            RecyclerView.ViewHolder(view)
    }

    companion object {
        private const val NEON = 0xFF22D3F5.toInt()
        private const val TIME = 0xFFF4F8FF.toInt()
        private const val DATE = 0xFFC6D4E8.toInt()
        private const val TEMP = 0xFF5EEBFF.toInt()
        private const val MUTED = 0xFF7F93AA.toInt()
        private const val NAME = 0xFFDCE6F2.toInt()
        private const val WARN = 0xFFFFC46B.toInt()
        private const val RAIN = 0xFF5DB8FF.toInt()
        private const val BOX = 0x8010274A.toInt()
        private const val BOX_BORDER = 0xFF1F4C78.toInt()
        private const val STRIPE = 0x2210274A
    }
}
