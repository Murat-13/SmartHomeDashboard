package com.example.smarthomedashboard

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.edit
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class WidgetAdapter(
    private val context: Context,
    private val widgets: MutableList<WidgetItem>,
    private val overlayContainer: FrameLayout,
    private val recyclerView: RecyclerView,
    private val onDataUpdate: () -> WidgetData,
    private var isEditMode: Boolean = false,
    private val onTileClick: ((tileId: String) -> Unit)? = null,
    private val onTileLongClick: ((tileId: String) -> Unit)? = null,
    private val onTileResized: ((tileId: String, newWidth: Int, newHeight: Int) -> Unit)? = null,
    private val onTileDragEnd: ((tileId: String, newX: Int, newY: Int) -> Unit)? = null
) : RecyclerView.Adapter<WidgetAdapter.WidgetViewHolder>() {

    private var expandedOverlay: View? = null
    private var sourceView: CardView? = null
    private var hiddenViewPosition = -1
    private val handler = Handler(Looper.getMainLooper())
    private var collapseRunnable: Runnable? = null

    private var isDragging = false
    private var isResizing = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var draggedPosition = -1

    fun setEditMode(enabled: Boolean) {
        isEditMode = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WidgetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_widget, parent, false)
        return WidgetViewHolder(view)
    }

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    override fun onBindViewHolder(holder: WidgetViewHolder, position: Int) {
        val widget = widgets[position]

        if (widget.title.isEmpty()) {
            holder.cardView.visibility = View.INVISIBLE
            holder.resizeHandle.visibility = View.GONE
            return
        }

        if (position == hiddenViewPosition) {
            holder.cardView.visibility = View.INVISIBLE
            sourceView = holder.cardView
        } else {
            holder.cardView.visibility = View.VISIBLE
        }

        holder.titleText.text = widget.title
        holder.resizeHandle.visibility = if (isEditMode) View.VISIBLE else View.GONE

        val data = onDataUpdate()

        when (widget.title) {
            "⚡ Сеть" -> {
                holder.primaryText.text = context.getString(R.string.voltage_format, data.voltage)
                holder.secondaryText.text = context.getString(R.string.power_format, data.power)
                val bgColor = if (data.gridOnline) Color.parseColor("#8033CC33") else Color.parseColor("#80FF3333")
                holder.cardView.setCardBackgroundColor(bgColor)
            }
            "🌡️ Температура" -> {
                holder.primaryText.text = if (widget.value.isEmpty() || widget.value == "—") "—" else widget.value
                holder.secondaryText.text = ""
            }
            else -> {
                holder.primaryText.text = if (widget.value.isEmpty() || widget.value == "—") "—" else widget.value
                holder.secondaryText.text = if (widget.type == "sensor") "" else ""
            }
        }

        holder.cardView.setOnClickListener {
            if (!isDragging && !isResizing) {
                if (isEditMode) {
                    onTileClick?.invoke(widget.config.optString("id", ""))
                } else {
                    when (widget.type) {
                        "button" -> {}
                        else -> {
                            if (expandedOverlay != null) {
                                collapseOverlay()
                            } else {
                                expandWidget(holder.cardView, position)
                            }
                        }
                    }
                }
            }
        }

        holder.cardView.setOnLongClickListener {
            if (!isEditMode) {
                onTileLongClick?.invoke(widget.config.optString("id", ""))
            }
            true
        }

        if (isEditMode) {
            holder.cardView.setOnTouchListener { _, event ->
                handleDragResizeTouch(event, holder, position)
            }
            holder.resizeHandle.setOnTouchListener { _, event ->
                isResizing = true
                val result = handleDragResizeTouch(event, holder, position)
                if (event.action == MotionEvent.ACTION_UP) isResizing = false
                result
            }
        } else {
            holder.cardView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val longPressRunnable = Runnable {
                        val tileId = widget.config.optString("id", "")
                        val prefs = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
                        val lastAuthTime = prefs.getLong("last_auth_time", 0)
                        if (System.currentTimeMillis() - lastAuthTime > 60 * 60 * 1000) {
                            PinDialog(context) {
                                prefs.edit { putLong("last_auth_time", System.currentTimeMillis()) }
                                context.startActivity(Intent(context, TileSettingsActivity::class.java).putExtra("tile_id", tileId))
                            }.show()
                        } else {
                            context.startActivity(Intent(context, TileSettingsActivity::class.java).putExtra("tile_id", tileId))
                        }
                    }
                    handler.postDelayed(longPressRunnable, 3000)
                    holder.cardView.tag = longPressRunnable
                } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    (holder.cardView.tag as? Runnable)?.let { handler.removeCallbacks(it) }
                }
                false
            }
        }
    }

    private fun handleDragResizeTouch(event: MotionEvent, holder: WidgetViewHolder, position: Int): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                dragStartX = holder.cardView.translationX
                dragStartY = holder.cardView.translationY
                draggedPosition = position
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY

                if (kotlin.math.abs(deltaX) > 10 || kotlin.math.abs(deltaY) > 10) {
                    if (isResizing) {
                        val newWidth = (holder.cardView.width + deltaX.toInt()).coerceIn(100, 600)
                        val newHeight = (holder.cardView.height + deltaY.toInt()).coerceIn(100, 600)
                        holder.cardView.layoutParams.width = newWidth
                        holder.cardView.layoutParams.height = newHeight
                        holder.cardView.requestLayout()
                    } else {
                        isDragging = true
                        holder.cardView.translationX = dragStartX + deltaX
                        holder.cardView.translationY = dragStartY + deltaY
                        holder.cardView.alpha = 0.7f
                        holder.cardView.elevation = 16f
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    holder.cardView.alpha = 1.0f
                    holder.cardView.elevation = 4f
                    holder.cardView.translationX = 0f
                    holder.cardView.translationY = 0f
                    val tileWidth = recyclerView.width / 4
                    val newCol = ((holder.cardView.x + holder.cardView.translationX) / tileWidth).toInt().coerceIn(0, 3)
                    val newRow = ((holder.cardView.y + holder.cardView.translationY) / tileWidth).toInt().coerceIn(0, Int.MAX_VALUE)
                    val tileId = widgets[position].config.optString("id", "")
                    onTileDragEnd?.invoke(tileId, newCol, newRow)
                    isDragging = false
                }
                if (isResizing) {
                    val tileId = widgets[position].config.optString("id", "")
                    val tileWidth = recyclerView.width / 4
                    val newWidthInCols = (holder.cardView.width / tileWidth).toInt().coerceIn(1, 4)
                    val newHeightInRows = (holder.cardView.height / tileWidth).toInt().coerceIn(1, 3)
                    onTileResized?.invoke(tileId, newWidthInCols, newHeightInRows)
                    isResizing = false
                }
                true
            }
            else -> false
        }
    }

    @SuppressLint("SetTextI18n")
    private fun expandWidget(source: CardView, position: Int) {
        val widget = widgets[position]
        if (widget.type != "sensor") return

        sourceView = source
        hiddenViewPosition = position
        source.visibility = View.INVISIBLE

        val data = onDataUpdate()

        // Используем правильный цвет для сети
        val bgColor = when (widget.title) {
            "⚡ Сеть" -> if (data.gridOnline) Color.parseColor("#8033CC33") else Color.parseColor("#80FF3333")
            else -> Color.parseColor("#80333333")
        }

        val overlay = CardView(source.context).apply {
            alpha = 0.85f
            cardElevation = 20f
            radius = 16f
            setCardBackgroundColor(bgColor)
        }

        val content = LayoutInflater.from(source.context).inflate(R.layout.widget_overlay_content, overlay, true)

        when (widget.title) {
            "⚡ Сеть" -> {
                content.findViewById<TextView>(R.id.overlayTitle).setText(R.string.widget_network_title)
                content.findViewById<TextView>(R.id.overlayVoltage).text = context.getString(R.string.voltage_format, data.voltage)
                content.findViewById<TextView>(R.id.overlayCurrent).text = context.getString(R.string.current_label, data.current)
                content.findViewById<TextView>(R.id.overlayPower).text = context.getString(R.string.power_label, data.power)
                content.findViewById<TextView>(R.id.overlayFrequency).text = context.getString(R.string.frequency_label, data.frequency)
                content.findViewById<TextView>(R.id.overlayEnergy).text = context.getString(R.string.energy_label, data.energy)
                content.findViewById<TextView>(R.id.overlayCurrent).visibility = View.VISIBLE
                content.findViewById<TextView>(R.id.overlayPower).visibility = View.VISIBLE
                content.findViewById<TextView>(R.id.overlayFrequency).visibility = View.VISIBLE
                content.findViewById<TextView>(R.id.overlayEnergy).visibility = View.VISIBLE
            }
            else -> {
                content.findViewById<TextView>(R.id.overlayTitle).text = widget.title
                content.findViewById<TextView>(R.id.overlayVoltage).text = if (widget.value.isEmpty() || widget.value == "—") "—" else widget.value
                content.findViewById<TextView>(R.id.overlayCurrent).visibility = View.GONE
                content.findViewById<TextView>(R.id.overlayPower).visibility = View.GONE
                content.findViewById<TextView>(R.id.overlayFrequency).visibility = View.GONE
                content.findViewById<TextView>(R.id.overlayEnergy).visibility = View.GONE
            }
        }

        val location = IntArray(2)
        source.getLocationOnScreen(location)

        overlay.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = location[0]
            topMargin = location[1]
        }

        overlayContainer.addView(overlay)
        expandedOverlay = overlay

        val screenWidth = overlayContainer.width
        val screenHeight = overlayContainer.height
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val widgetCenterX = location[0] + source.width / 2f
        val widgetCenterY = location[1] + source.height / 2f

        val deltaX = centerX - widgetCenterX
        val deltaY = centerY - widgetCenterY

        overlay.pivotX = source.width / 2f
        overlay.pivotY = source.height / 2f

        val scaleX = ObjectAnimator.ofFloat(overlay, "scaleX", 1f, 1.5f)
        val scaleY = ObjectAnimator.ofFloat(overlay, "scaleY", 1f, 1.5f)
        val transX = ObjectAnimator.ofFloat(overlay, "translationX", 0f, deltaX)
        val transY = ObjectAnimator.ofFloat(overlay, "translationY", 0f, deltaY)

        val animSet = android.animation.AnimatorSet()
        animSet.playTogether(scaleX, scaleY, transX, transY)
        animSet.duration = 300
        animSet.start()

        overlay.setOnClickListener { collapseOverlay() }

        val prefs = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
        val timeoutSeconds = prefs.getInt("expand_timeout", 10)

        collapseRunnable?.let { handler.removeCallbacks(it) }
        collapseRunnable = Runnable { collapseOverlay() }
        handler.postDelayed(collapseRunnable!!, timeoutSeconds * 1000L)
    }

    private fun collapseOverlay() {
        expandedOverlay?.let { overlay ->
            overlayContainer.removeView(overlay)
            sourceView?.visibility = View.VISIBLE
            sourceView = null
            hiddenViewPosition = -1
            expandedOverlay = null
        }
        collapseRunnable?.let { handler.removeCallbacks(it) }
        collapseRunnable = null
    }

    fun updatePzemData(voltage: String, current: String, power: String, energy: String, frequency: String, gridOnline: Boolean) {
        val position = widgets.indexOfFirst { it.title == "⚡ Сеть" }
        if (position >= 0) {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position) as? WidgetViewHolder
            viewHolder?.let {
                it.primaryText.text = context.getString(R.string.voltage_format, voltage)
                it.secondaryText.text = context.getString(R.string.power_format, power)
                val bgColor = if (gridOnline) Color.parseColor("#8033CC33") else Color.parseColor("#80FF3333")
                try { it.cardView.setCardBackgroundColor(bgColor) } catch (_: Exception) {}
            }
        }
    }

    fun updateTemperatureData(temp: String) {
        val position = widgets.indexOfFirst { it.title == "🌡️ Температура" }
        if (position >= 0) {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position) as? WidgetViewHolder
            viewHolder?.primaryText?.text = context.getString(R.string.temperature_format, temp)
            widgets[position] = widgets[position].copy(value = temp)
        }
        updateWidgetByEntityId("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia", temp)
    }

    fun updateWidgetByEntityId(entityId: String, value: String) {
        val position = widgets.indexOfFirst { it.entityId == entityId }
        if (position >= 0) {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position) as? WidgetViewHolder
            viewHolder?.let {
                it.primaryText.text = value
                widgets[position] = widgets[position].copy(value = value)
            } ?: run {
                widgets[position] = widgets[position].copy(value = value)
                notifyItemChanged(position)
            }
        }
    }

    override fun getItemCount(): Int = widgets.size

    class WidgetViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: CardView = view.findViewById(R.id.widgetCard)
        val titleText: TextView = view.findViewById(R.id.widgetTitle)
        val primaryText: TextView = view.findViewById(R.id.widgetPrimary)
        val secondaryText: TextView = view.findViewById(R.id.widgetSecondary)
        val resizeHandle: ImageView = view.findViewById(R.id.resizeHandle)
    }
}

data class WidgetItem(
    val title: String,
    val value: String,
    val entityId: String = "",
    val backgroundColor: String = "#80333333",
    val type: String = "sensor",
    val config: JSONObject = JSONObject()
)

data class WidgetData(
    val voltage: String,
    val current: String,
    val power: String,
    val energy: String,
    val frequency: String,
    val gridOnline: Boolean
)