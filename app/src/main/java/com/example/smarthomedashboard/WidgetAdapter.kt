package com.example.smarthomedashboard

import android.animation.Animator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class WidgetAdapter(
    private val context: Context,
    private val widgets: MutableList<WidgetItem>,
    private val overlayContainer: FrameLayout,
    private val recyclerView: RecyclerView,
    private val onDataUpdate: () -> WidgetData,
    private val onTileMoved: ((from: Int, to: Int) -> Unit)? = null
) : RecyclerView.Adapter<WidgetAdapter.WidgetViewHolder>() {

    private var expandedOverlay: View? = null
    private var sourceView: CardView? = null
    private var hiddenViewPosition = -1
    private val handler = Handler(Looper.getMainLooper())
    private var collapseRunnable: Runnable? = null

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
            return
        }

        if (position == hiddenViewPosition) {
            holder.cardView.visibility = View.INVISIBLE
            sourceView = holder.cardView
        } else {
            holder.cardView.visibility = View.VISIBLE
        }

        holder.titleText.text = widget.title

        val data = onDataUpdate()

        when (widget.title) {
            "⚡ Сеть" -> {
                holder.primaryText.text = context.getString(R.string.voltage_format, data.voltage)
                holder.secondaryText.text = context.getString(R.string.power_format, data.power)
                val bgColor = if (data.gridOnline) "#8033CC33".toColorInt() else "#80FF3333".toColorInt()
                holder.cardView.setCardBackgroundColor(bgColor)
            }
            else -> {
                // Для всех остальных виджетов
                holder.primaryText.text = if (widget.value.isEmpty() || widget.value == "—") "—" else widget.value
                holder.secondaryText.text = if (widget.type == "sensor") "—" else ""
                try {
                    holder.cardView.setCardBackgroundColor(widget.backgroundColor.toColorInt())
                } catch (_: Exception) {
                    holder.cardView.setCardBackgroundColor("#80333333".toColorInt())
                }
            }
        }

        holder.cardView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val longPressRunnable = Runnable {
                        val tileId = widget.config.optString("id", "")
                        Log.d("WidgetAdapter", "Long press on tile: $tileId")
                        val prefs = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
                        val lastAuthTime = prefs.getLong("last_auth_time", 0)
                        val currentTime = System.currentTimeMillis()

                        if (currentTime - lastAuthTime > 60 * 60 * 1000) {
                            PinDialog(context) {
                                prefs.edit { putLong("last_auth_time", System.currentTimeMillis()) }
                                val intent = Intent(context, TileSettingsActivity::class.java)
                                intent.putExtra("tile_id", tileId)
                                context.startActivity(intent)
                            }.show()
                        } else {
                            val intent = Intent(context, TileSettingsActivity::class.java)
                            intent.putExtra("tile_id", tileId)
                            context.startActivity(intent)
                        }
                    }
                    handler.postDelayed(longPressRunnable, 3000)
                    holder.cardView.tag = longPressRunnable
                    true
                }
                MotionEvent.ACTION_UP -> {
                    (holder.cardView.tag as? Runnable)?.let { handler.removeCallbacks(it) }
                    when (widget.type) {
                        "button" -> {
                            Log.d("WidgetAdapter", "Button clicked: ${widget.title}")
                        }
                        else -> {
                            Log.d("WidgetAdapter", "Click on widget: ${widget.title}")
                            if (expandedOverlay != null) {
                                collapseOverlay()
                            } else {
                                expandWidget(holder.cardView, position)
                            }
                        }
                    }
                    holder.cardView.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    (holder.cardView.tag as? Runnable)?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
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

        val overlay = CardView(source.context)
        overlay.alpha = 0.85f
        overlay.cardElevation = 20f
        overlay.radius = 16f

        val bgColor = when (widget.title) {
            "⚡ Сеть" -> if (data.gridOnline) "#8033CC33".toColorInt() else "#80FF3333".toColorInt()
            else -> "#80333333".toColorInt()
        }
        overlay.setCardBackgroundColor(bgColor)

        val content = LayoutInflater.from(source.context)
            .inflate(R.layout.widget_overlay_content, overlay, true)

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
            val scaleX = ObjectAnimator.ofFloat(overlay, "scaleX", 1.5f, 1f)
            val scaleY = ObjectAnimator.ofFloat(overlay, "scaleY", 1.5f, 1f)
            val transX = ObjectAnimator.ofFloat(overlay, "translationX", overlay.translationX, 0f)
            val transY = ObjectAnimator.ofFloat(overlay, "translationY", overlay.translationY, 0f)

            val animSet = android.animation.AnimatorSet()
            animSet.playTogether(scaleX, scaleY, transX, transY)
            animSet.duration = 300
            animSet.addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    overlayContainer.removeView(overlay)
                    sourceView?.visibility = View.VISIBLE
                    sourceView = null
                    hiddenViewPosition = -1
                    expandedOverlay = null
                }
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
            animSet.start()
        }
        collapseRunnable?.let { handler.removeCallbacks(it) }
        collapseRunnable = null
    }

    @Suppress("unused")
    fun updatePzemData(voltage: String, current: String, power: String, energy: String, frequency: String, gridOnline: Boolean) {
        val position = widgets.indexOfFirst { it.title == "⚡ Сеть" }
        if (position >= 0) {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position) as? WidgetViewHolder
            viewHolder?.let {
                it.primaryText.text = context.getString(R.string.voltage_format, voltage)
                it.secondaryText.text = context.getString(R.string.power_format, power)
                val bgColor = if (gridOnline) "#8033CC33".toColorInt() else "#80FF3333".toColorInt()
                try { it.cardView.setCardBackgroundColor(bgColor) } catch (_: Exception) {}
            }
        }
    }

    @Suppress("unused")
    fun updateTemperatureData(temp: String) {
        // Ищем виджет температуры по entity_id, а не по названию
        val position = widgets.indexOfFirst { it.entityId == "sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia" }
        if (position >= 0) {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position) as? WidgetViewHolder
            viewHolder?.primaryText?.text = context.getString(R.string.temperature_format, temp)
            widgets[position] = widgets[position].copy(value = temp)
            Log.d("WidgetAdapter", "Updated temperature widget with value: $temp")
        } else {
            Log.w("WidgetAdapter", "Temperature widget not found by entityId")
        }
    }

    fun updateWidgetByEntityId(entityId: String, value: String) {
        Log.d("WidgetAdapter", "updateWidgetByEntityId: looking for entityId=$entityId, value=$value")
        val position = widgets.indexOfFirst { it.entityId == entityId }
        if (position >= 0) {
            Log.d("WidgetAdapter", "Found widget at position $position: ${widgets[position].title}")
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position) as? WidgetViewHolder
            viewHolder?.let {
                it.primaryText.text = value
                widgets[position] = widgets[position].copy(value = value)
                Log.d("WidgetAdapter", "Updated widget ${widgets[position].title} with value $value")
            } ?: run {
                Log.w("WidgetAdapter", "ViewHolder is null for position $position, notifying item changed")
                widgets[position] = widgets[position].copy(value = value)
                notifyItemChanged(position)
            }
        } else {
            Log.w("WidgetAdapter", "No widget found with entityId=$entityId")
            Log.d("WidgetAdapter", "Available widgets: ${widgets.map { "${it.title} -> ${it.entityId}" }}")
        }
    }

    override fun getItemCount(): Int = widgets.size

    class WidgetViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: CardView = view.findViewById(R.id.widgetCard)
        val titleText: TextView = view.findViewById(R.id.widgetTitle)
        val primaryText: TextView = view.findViewById(R.id.widgetPrimary)
        val secondaryText: TextView = view.findViewById(R.id.widgetSecondary)
    }
}

data class WidgetItem(
    val title: String,
    val value: String,
    val entityId: String = "",
    val backgroundColor: String,
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