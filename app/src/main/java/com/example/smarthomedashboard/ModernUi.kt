package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Общие элементы Modern для настроек и редакторов, собираемых кодом. Значения — из ресурсов
 * `modern_*` (те же, что у XML-стилей `Widget.Modern.*`). Главная панель их не использует.
 * Это не второй набор компонентов: [ModernEditorKit] остаётся для поиска сущностей и диалогов
 * строк, а здесь только внешний вид.
 */
internal object ModernUi {
    enum class Status(@param:ColorRes val color: Int) {
        OK(R.color.modern_success),
        WARNING(R.color.modern_warning),
        ERROR(R.color.modern_error),
        PROGRESS(R.color.modern_accent),
        UNKNOWN(R.color.modern_neutral),
    }

    fun color(context: Context, @ColorRes id: Int) = ContextCompat.getColor(context, id)
    private fun px(context: Context, dimen: Int) = context.resources.getDimensionPixelSize(dimen)

    private fun text(context: Context, value: CharSequence, @StyleRes appearance: Int) = TextView(context).apply {
        text = value
        setTextAppearance(appearance)
    }

    fun header(context: Context, value: CharSequence) = text(context, value, R.style.TextAppearance_Modern_Header)
    fun title(context: Context, value: CharSequence) = text(context, value, R.style.TextAppearance_Modern_Title)
    fun body(context: Context, value: CharSequence) = text(context, value, R.style.TextAppearance_Modern_Body)
    fun secondary(context: Context, value: CharSequence) = text(context, value, R.style.TextAppearance_Modern_Secondary)
    fun caption(context: Context, value: CharSequence) = text(context, value, R.style.TextAppearance_Modern_Caption)

    /** Карточка раздела: необязательный заголовок и содержимое столбцом с умеренными отступами. */
    fun section(context: Context, heading: CharSequence?, vararg children: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.modern_bg_section)
        val pad = px(context, R.dimen.modern_space_l)
        setPadding(pad, pad, pad, pad)
        val gap = px(context, R.dimen.modern_space_s)
        heading?.let { addView(title(context, it)) }
        children.forEach { child ->
            addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = gap })
        }
    }

    fun input(context: Context, value: String, hint: String, password: Boolean = false) = EditText(context).apply {
        setText(value)
        this.hint = hint
        background = ContextCompat.getDrawable(context, R.drawable.modern_bg_input)
        setTextAppearance(R.style.TextAppearance_Modern_Body)
        setHintTextColor(color(context, R.color.modern_text_hint))
        // Одна строка задаётся до минимума: setSingleLine сбрасывает минимальную высоту TextView.
        isSingleLine = true
        if (password) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // Для пароля Android ставит моноширинный шрифт; подсказка должна выглядеть как соседние поля.
            typeface = android.graphics.Typeface.DEFAULT
        }
        minHeight = px(context, R.dimen.modern_touch_target)
        val pad = px(context, R.dimen.modern_space_m)
        setPadding(pad, 0, pad, 0)
    }

    private fun button(context: Context, label: CharSequence, @ColorRes content: Int, filled: Boolean, onClick: () -> Unit) =
        MaterialButton(context).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.modern_text_body))
            cornerRadius = px(context, R.dimen.modern_radius_control)
            minHeight = px(context, R.dimen.modern_touch_target)
            minimumHeight = minHeight
            if (filled) {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.modern_button_primary_bg)
                setTextColor(color(context, R.color.modern_on_accent))
            } else {
                backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                strokeColor = ColorStateList.valueOf(color(context, content))
                strokeWidth = (context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                setTextColor(color(context, content))
                rippleColor = ColorStateList.valueOf(color(context,
                    if (content == R.color.modern_danger) R.color.modern_danger_soft else R.color.modern_accent_soft))
            }
            setOnClickListener { onClick() }
        }

    /** Главное действие экрана («Сохранить»). */
    fun primaryButton(context: Context, label: CharSequence, onClick: () -> Unit) =
        button(context, label, R.color.modern_on_accent, filled = true, onClick)

    fun secondaryButton(context: Context, label: CharSequence, onClick: () -> Unit) =
        button(context, label, R.color.modern_accent, filled = false, onClick)

    /** Только удаление и рискованные действия. */
    fun dangerButton(context: Context, label: CharSequence, onClick: () -> Unit) =
        button(context, label, R.color.modern_danger, filled = false, onClick)

    fun switch(context: Context, label: CharSequence, checked: Boolean, onChange: (Boolean) -> Unit) = MaterialSwitch(context).apply {
        text = label
        setTextAppearance(R.style.TextAppearance_Modern_Body)
        thumbTintList = ContextCompat.getColorStateList(context, R.color.modern_switch_thumb)
        trackTintList = ContextCompat.getColorStateList(context, R.color.modern_switch_track)
        minHeight = px(context, R.dimen.modern_touch_target)
        isChecked = checked
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    /** Строка статуса: цветная точка и текст. Обновляется через [setStatus]. */
    fun statusRow(context: Context, status: Status, message: CharSequence) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(context, R.dimen.modern_touch_target)
        val size = px(context, R.dimen.modern_status_dot)
        addView(View(context).apply { background = ContextCompat.getDrawable(context, R.drawable.modern_status_dot)?.mutate() },
            LinearLayout.LayoutParams(size, size).apply { marginEnd = px(context, R.dimen.modern_space_m) })
        addView(body(context, ""), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setStatus(this, status, message)
    }

    fun setStatus(row: LinearLayout, status: Status, message: CharSequence) {
        row.getChildAt(0).background?.setTint(color(row.context, status.color))
        (row.getChildAt(1) as TextView).text = message
        row.tag = status
    }

    /** Пункт левого меню настроек; выбранный — `isSelected = true`. */
    fun navItem(context: Context, label: CharSequence, icon: Int? = null) = TextView(context).apply {
        text = label
        background = ContextCompat.getDrawable(context, R.drawable.modern_bg_nav_item)
        setTextColor(ContextCompat.getColorStateList(context, R.color.modern_nav_text))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.modern_text_body))
        minHeight = px(context, R.dimen.modern_touch_target)
        gravity = Gravity.CENTER_VERTICAL
        val pad = px(context, R.dimen.modern_space_m)
        setPadding(pad, 0, pad, 0)
        compoundDrawablePadding = pad
        icon?.let { res ->
            ContextCompat.getDrawable(context, res)?.mutate()?.let { drawable ->
                drawable.setTintList(ContextCompat.getColorStateList(context, R.color.modern_nav_text))
                setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
            }
        }
        isClickable = true
        isFocusable = true
    }

    /** Уход с несохранёнными изменениями: «Сохранить / Не сохранять / Продолжить редактирование». */
    fun leaveDialog(activity: Activity, message: CharSequence?, onSave: () -> Unit, onDiscard: () -> Unit): AlertDialog =
        AlertDialog.Builder(activity).setTitle("Сохранить изменения?")
            .apply { message?.let { setMessage(it) } }
            .setPositiveButton("Сохранить") { _, _ -> onSave() }
            .setNegativeButton("Не сохранять") { _, _ -> onDiscard() }
            .setNeutralButton("Продолжить редактирование", null)
            .create()

    /** Подтверждение опасного действия; кнопка действия окрашивается в цвет опасности. */
    fun dangerDialog(activity: Activity, heading: CharSequence, message: CharSequence?, action: CharSequence,
                     onConfirm: () -> Unit): AlertDialog =
        AlertDialog.Builder(activity).setTitle(heading)
            .apply { message?.let { setMessage(it) } }
            .setPositiveButton(action) { _, _ -> onConfirm() }
            .setNegativeButton("Отмена", null)
            .create().apply {
                setOnShowListener { getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(color(activity, R.color.modern_danger)) }
            }

    /**
     * Анимации переходов: стандартная кривая Material и системные длительности.
     * ValueAnimator сам учитывает «Масштаб анимации» системы (в том числе отключённые анимации).
     */
    object Motion {
        val standard = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        fun short(context: Context) = context.resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
        fun medium(context: Context) = context.resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
    }
}
