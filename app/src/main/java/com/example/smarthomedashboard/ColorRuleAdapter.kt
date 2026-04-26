package com.example.smarthomedashboard

import android.app.AlertDialog
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smarthomedashboard.data.ColorRule

class ColorRuleAdapter(
    private val items: MutableList<ColorRule>,
    private val onSelectEntity: (position: Int) -> Unit
) : RecyclerView.Adapter<ColorRuleAdapter.ViewHolder>() {

    // Пресеты цветов для выбора
    private val colorPresets = listOf(
        "#FF5252", // Красный (Тревога)
        "#FFB300", // Желтый (Предупреждение)
        "#4CAF50", // Зеленый (ОК)
        "#2196F3", // Синий (Инфо)
        "#9E9E9E", // Серый (Выключено)
        "#FF851B"  // Оранжевый
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // Отображение сущности
        val displayName = if (item.entityId.isEmpty()) "Нажмите для выбора" 
                          else item.entityId.substringAfterLast(".").replace("_", " ")
        holder.btnSelectEntity.text = displayName

        holder.btnSelectEntity.setOnClickListener {
            onSelectEntity(position)
        }

        // Настройка оператора
        val operators = holder.itemView.context.resources.getStringArray(R.array.operators)
        val opIndex = operators.indexOf(item.condition).coerceAtLeast(0)
        holder.spinnerOperator.setSelection(opIndex)

        holder.spinnerOperator.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (position < items.size) {
                    items[position] = items[position].copy(condition = operators[pos])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Настройка значения
        holder.etValue.setText(item.value)
        holder.etValue.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && position < items.size) {
                items[position] = items[position].copy(value = holder.etValue.text.toString())
            }
        }

        // Превью и выбор цвета
        try {
            holder.vColorPreview.setBackgroundColor(Color.parseColor(item.colorHex))
        } catch (_: Exception) {
            holder.vColorPreview.setBackgroundColor(Color.GRAY)
        }

        holder.vColorPreview.setOnClickListener {
            showColorPickerDialog(holder.itemView.context) { selectedColor ->
                if (position < items.size) {
                    items[position] = items[position].copy(colorHex = selectedColor)
                    notifyItemChanged(position)
                }
            }
        }

        // Удаление
        holder.btnDeleteRule.setOnClickListener {
            if (position < items.size) {
                items.removeAt(position)
                notifyItemRemoved(position)
                notifyItemRangeChanged(position, items.size)
            }
        }
    }

    private fun showColorPickerDialog(context: android.content.Context, onColorSelected: (String) -> Unit) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Выберите цвет")
        
        // Создаем сетку цветов
        val grid = android.widget.GridLayout(context).apply {
            columnCount = 3
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            setPadding(20, 20, 20, 20)
        }

        colorPresets.forEach { colorStr ->
            val colorView = View(context).apply {
                val size = 120
                layoutParams = android.view.ViewGroup.MarginLayoutParams(size, size).apply {
                    setMargins(10, 10, 10, 10)
                }
                setBackgroundColor(Color.parseColor(colorStr))
                setOnClickListener {
                    onColorSelected(colorStr)
                }
            }
            grid.addView(colorView)
        }

        val dialog = builder.setView(grid).create()
        
        // Закрываем при выборе
        grid.children.forEach { view ->
            val oldClick = view.performClick() // placeholder logic
            view.setOnClickListener {
                onColorSelected(colorPresets[grid.indexOfChild(view)])
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // Добавим кнопку выбора сущности в item_color_rule (нужно будет поправить XML или использовать существующий)
        // В текущем XML нет btnSelectEntity, используем TextView как кнопку или добавим её
        val btnSelectEntity: TextView = view.findViewById(R.id.btnSelectEntity) ?: view.findViewById(R.id.vColorPreview) // Временный хак для компиляции
        val spinnerOperator: Spinner = view.findViewById(R.id.spinnerOperator)
        val etValue: EditText = view.findViewById(R.id.etValue)
        val vColorPreview: View = view.findViewById(R.id.vColorPreview)
        val btnDeleteRule: Button = view.findViewById(R.id.btnDeleteRule)
    }

    // Хелпер для получения View в GridLayout
    private val android.view.ViewGroup.children: Sequence<View>
        get() = object : Sequence<View> {
            override fun iterator() = object : Iterator<View> {
                private var index = 0
                override fun hasNext() = index < childCount
                override fun next() = getChildAt(index++)
            }
        }
}
