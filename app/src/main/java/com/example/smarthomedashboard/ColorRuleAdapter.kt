package com.example.smarthomedashboard

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView
import com.example.smarthomedashboard.data.ColorRule

class ColorRuleAdapter(
    private val items: MutableList<ColorRule>,
    private val onSelectEntity: (Int) -> Unit
) : RecyclerView.Adapter<ColorRuleAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val btnSelectEntity: TextView = view.findViewById(R.id.btnSelectEntity)
        val btnDeleteRule: Button = view.findViewById(R.id.btnDeleteRule)
        val etFrom: EditText = view.findViewById(R.id.etFrom)
        val etTo: EditText = view.findViewById(R.id.etTo)
        val vColorPreview: View = view.findViewById(R.id.vColorPreview)
        
        var fromWatcher: TextWatcher? = null
        var toWatcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_color_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.btnSelectEntity.text = item.entityId.ifEmpty { "Выбрать датчик" }
        holder.btnSelectEntity.setOnClickListener { onSelectEntity(holder.bindingAdapterPosition) }

        holder.fromWatcher?.let { holder.etFrom.removeTextChangedListener(it) }
        holder.toWatcher?.let { holder.etTo.removeTextChangedListener(it) }

        holder.etFrom.setText(item.from ?: "")
        holder.etTo.setText(item.to ?: "")

        updateColorPreview(holder.vColorPreview, item.colorHex)

        holder.btnDeleteRule.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                items.removeAt(pos)
                notifyItemRemoved(pos)
            }
        }

        holder.fromWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                    items[pos] = items[pos].copy(from = s.toString())
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        
        holder.toWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                    items[pos] = items[pos].copy(to = s.toString())
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        holder.etFrom.addTextChangedListener(holder.fromWatcher)
        holder.etTo.addTextChangedListener(holder.toWatcher)

        holder.vColorPreview.setOnClickListener {
            showColorPickerDialog(holder.itemView.context, item.colorHex) { selectedColor ->
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items[pos] = items[pos].copy(colorHex = selectedColor)
                    updateColorPreview(holder.vColorPreview, selectedColor)
                }
            }
        }
    }

    private fun updateColorPreview(view: View, colorHex: String) {
        try {
            val color = colorHex.toColorInt()
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                setStroke(2, Color.WHITE)
                cornerRadius = 8f
            }
            view.background = drawable
        } catch (_: Exception) {
            view.setBackgroundColor(Color.GRAY)
        }
    }

    private fun showColorPickerDialog(context: android.content.Context, currentColor: String, onColorSelected: (String) -> Unit) {
        val colors = arrayOf(
            "#804CAF50", "#80F44336", "#802196F3", "#80FFEB3B", "#809C27B0", "#80FF9800", "#8000BCD4", "#80000000",
            "#CC4CAF50", "#CCF44336", "#CC2196F3", "#CCFFEB3B", "#CC9C27B0", "#CCFF9800", "#CC00BCD4", "#CCFFFFFF",
            "#E64CAF50", "#E6F44336", "#E62196F3", "#E6FFEB3B", "#E69C27B0", "#E6FF9800", "#E600BCD4", "#E6FFFFFF",
            "#FF4CAF50", "#FFF44336", "#FF2196F3", "#FFFFEB3B", "#FF9C27B0", "#FFFF9800", "#FF00BCD4", "#FFFFFFFF"
        )

        val gridLayout = GridLayout(context).apply {
            columnCount = 8
            setPadding(20, 20, 20, 20)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Выберите цвет")
            .setView(gridLayout)
            .setNegativeButton("Отмена", null)
            .create()

        for (color in colors) {
            val colorView = View(context).apply {
                val size = 90
                val params = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(8, 8, 8, 8)
                }
                layoutParams = params
                
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(color.toColorInt())
                    setStroke(if (color.equals(currentColor, ignoreCase = true)) 4 else 1, Color.WHITE)
                    cornerRadius = 6f
                }
                background = drawable

                setOnClickListener {
                    onColorSelected(color)
                    dialog.dismiss()
                }
            }
            gridLayout.addView(colorView)
        }

        dialog.show()
    }

    override fun getItemCount(): Int = items.size
}