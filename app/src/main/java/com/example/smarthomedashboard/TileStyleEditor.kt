package com.example.smarthomedashboard

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * Переключатель Classic/Modern вверху редактора кнопки и группы с предпросмотром.
 * Только черновик экрана: запись в плитку — по «Сохранить», отмена/назад ничего не меняет.
 * Предпросмотр показывает фактические состояния HA и не отправляет команд.
 */
internal class TileStyleEditor(
    private val activity: Activity,
    initial: TileButtonStyle,
    initialIcon: ModernIcon?,
    private val title: () -> String,
    private val type: () -> String,
    private val sizePx: () -> Int,
    private val entities: () -> List<Pair<String, String>>
) {
    var style: TileButtonStyle = initial
        private set
    /** Выбранная иконка Modern; null — прежнее поведение (автоподбор у кнопки, без иконки у группы). */
    var icon: ModernIcon? = initialIcon
        private set
    /** Пользователь менял иконку на этом экране: только тогда ключ записывается при сохранении. */
    var iconChanged = false
        private set
    private val section: View = activity.findViewById(R.id.buttonStyleSection)
    private val classic: Button = activity.findViewById(R.id.btnStyleClassic)
    private val modern: Button = activity.findViewById(R.id.btnStyleModern)
    private val preview: FrameLayout = activity.findViewById(R.id.buttonStylePreview)
    private val iconLabel: android.widget.TextView? = activity.findViewById(R.id.tvModernIconLabel)
    private val picker = ModernIconPicker(activity) { selectIcon(it) }.also { picker ->
        picker.select(initialIcon)
        activity.findViewById<FrameLayout?>(R.id.modernIconPicker)?.addView(picker,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }
    private var pickerType: String? = null
    private var state: MainUiState? = null
    private var previewView: View? = null
    private var previewKey: Any? = null

    init {
        classic.setOnClickListener { select(TileButtonStyle.CLASSIC) }
        modern.setOnClickListener { select(TileButtonStyle.MODERN) }
        updateButtons()
    }

    fun select(next: TileButtonStyle) {
        if (style == next) return
        style = next
        updateButtons()
        update()
    }

    fun selectIcon(next: ModernIcon?, changed: Boolean = true) {
        if (changed) iconChanged = true
        if (icon == next) return
        icon = next
        picker.select(next)
        update()
    }

    fun acceptState(next: MainUiState) {
        state = next
        update()
    }

    /** Перестраивает предпросмотр только при смене вида/размера; иначе — новое состояние. */
    fun update() {
        val visible = TileButtonStyle.supports(type())
        section.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        updatePicker()
        val density = activity.resources.displayMetrics.density
        val size = sizePx().coerceIn((64 * density).toInt(), (180 * density).toInt())
        val isGroup = type() == "group"
        val key = listOf(style, size, isGroup)
        if (key != previewKey) {
            preview.removeAllViews()
            previewView = if (style == TileButtonStyle.MODERN) {
                ModernTileView(activity, if (isGroup) ModernTileView.Kind.GROUP else ModernTileView.Kind.SINGLE).apply {
                    isClickable = false
                    isFocusable = false
                    editOutline = true
                }
            } else TileButtonFactory.create(activity, title(), "preview", size, ButtonCommandAppearance.OFF).apply {
                isClickable = false
            }
            preview.addView(previewView, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            previewKey = key
        }
        val ids = entities()
        when (val view = previewView) {
            is ModernTileView -> view.bind(modernModel(isGroup, ids), animate = view.isAttachedToWindow)
            is Button -> ButtonCommandAppearance.render(view, title(), groupState(ids.map { confirmed(it.first) }, classic = true), null,
                isGroup = isGroup)
        }
    }

    /** Иконка настраивается только у Modern; подписи зависят от типа плитки. */
    private fun updatePicker() {
        val modernVisible = if (style == TileButtonStyle.MODERN) View.VISIBLE else View.GONE
        picker.visibility = modernVisible
        iconLabel?.visibility = modernVisible
        val type = type()
        if (type == pickerType) return
        pickerType = type
        if (type == "group") {
            iconLabel?.text = "Иконка группы"
            picker.setNoneLabel("Без иконки", "—")
        } else {
            iconLabel?.text = "Иконка кнопки"
            picker.setNoneLabel("По устройству", "А")
        }
    }

    private fun modernModel(isGroup: Boolean, ids: List<Pair<String, String>>): ModernTileView.Model {
        val states = ids.map { confirmed(it.first) }
        if (isGroup) {
            val items = ids.mapIndexed { i, (id, _) -> ModernTileView.Item(icon(id), states[i]) }
            return ModernTileView.Model(title(), ModernIcon.EMPTY_GRID, groupState(states, classic = false), items = items,
                groupIcon = icon)
        }
        val id = ids.firstOrNull()?.first ?: return ModernTileView.Model(title(), icon ?: ModernIcon.POWER, ConfirmedState.OFF)
        return ModernTileView.Model(title(), icon ?: icon(id), states.first())
    }

    private fun groupState(states: List<ConfirmedState>, classic: Boolean) = when {
        states.isEmpty() -> ConfirmedState.OFF
        ConfirmedState.ON in states -> ConfirmedState.ON
        classic && states.all { it == ConfirmedState.OFF } -> ConfirmedState.OFF
        !classic && ConfirmedState.OFF in states -> ConfirmedState.OFF
        else -> ConfirmedState.UNAVAILABLE
    }

    private fun icon(id: String) = ModernIcon.forEntity(id, state?.entityAttributes?.get(id))

    private fun confirmed(id: String): ConfirmedState {
        val current = state ?: return ConfirmedState.UNAVAILABLE
        return when {
            id in current.staleEntities -> ConfirmedState.UNAVAILABLE
            current.singleStates[id] == "on" -> ConfirmedState.ON
            current.singleStates[id] == "off" -> ConfirmedState.OFF
            else -> ConfirmedState.UNAVAILABLE
        }
    }

    private fun updateButtons() {
        fun paint(button: Button, selected: Boolean) {
            button.backgroundTintList = ColorStateList.valueOf(if (selected) 0xFF1E88E5.toInt() else 0xFF424242.toInt())
            button.alpha = if (selected) 1f else 0.75f
            button.isSelected = selected
        }
        paint(classic, style == TileButtonStyle.CLASSIC)
        paint(modern, style == TileButtonStyle.MODERN)
    }

    companion object {
        /** Записывает стиль, только если это новая плитка или стиль действительно изменён. */
        fun writeIfChanged(config: JSONObject, style: TileButtonStyle, isNew: Boolean) {
            if (isNew || TileButtonStyle.of(config) != style) TileButtonStyle.write(config, style)
        }
    }
}
