package com.example.smarthomedashboard

import android.widget.Button

/** Статичный акцент и подпись: нет бесконечного аниматора или дополнительного таймера View. */
internal object ButtonCommandAppearance {
    const val ON = 0x8033CC33.toInt()
    const val OFF = 0xFF424242.toInt()
    const val PENDING = 0xFF80652F.toInt()
    const val UNAVAILABLE = 0xFF455A64.toInt()

    private data class Appearance(val title: String, val color: Int, val status: String?)

    fun render(button: Button, title: String, confirmed: ConfirmedState, pending: PendingCommand?, isGroup: Boolean = false) {
        val appearance = when {
            pending != null -> Appearance(title, PENDING, when {
                isGroup -> "Выполняется…"
                pending.expectedState == "on" -> "Включается…"
                else -> "Выключается…"
            })
            confirmed == ConfirmedState.ON -> Appearance(title, ON, null)
            confirmed == ConfirmedState.OFF -> Appearance(title, OFF, null)
            else -> Appearance(title, UNAVAILABLE, "Нет данных")
        }
        if (button.getTag(R.id.button_command_appearance) == appearance) return
        button.setTag(R.id.button_command_appearance, appearance)
        button.background?.setTint(appearance.color)
        button.text = appearance.status?.let { "$title\n$it" } ?: title
        button.contentDescription = "$title: ${appearance.status ?: if (confirmed == ConfirmedState.ON) "Включено" else "Выключено"}"
    }
}
