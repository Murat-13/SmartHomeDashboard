package com.example.smarthomedashboard

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * «Голосовые уведомления» (docs/voice-alerts.md): какие события произносить, громкость (независимо от системной,
 * с прослушиванием при движении ползунка), тихие часы и доля громкости в них, порог низкого заряда АКБ.
 * Настройки этого планшета; изменения применяются сразу (как остальные настройки экрана).
 */
class VoiceAlertsActivity : PinProtectedActivity() {
    private var current = VoiceAlertSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PanelAlerts.attach(applicationContext)
        current = PanelAlerts.settings()
        setContentView(build())
    }

    private fun build(): ScrollView {
        val pad = resources.getDimensionPixelSize(R.dimen.modern_space_l)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(ModernUi.header(this, "Голосовые уведомления"))
        column.addView(ModernUi.secondary(this, "Планшет произносит короткую фразу после мягкого сигнала. Одно и то же событие " +
            "не повторяется чаще, чем раз в несколько минут, фразы не накладываются. Настройки — только этого планшета."))

        val events = AlertEvent.entries.map { event ->
            ModernUi.switch(this, event.title, event in current.enabled) { on ->
                change(current.copy(enabled = if (on) current.enabled + event else current.enabled - event))
            }.apply { tag = "voice_event_${event.key}" }
        }
        column.addView(gap()); column.addView(section("События", *events.toTypedArray()))

        val volumeLabel = ModernUi.body(this, "")
        val volume = slider(current.volumePercent, "voice_volume", volumeLabel, { "Громкость: $it%" }) { value ->
            change(current.copy(volumePercent = value))
            PanelAlerts.preview("Сеть появилась", value / 100f)
        }
        column.addView(gap()); column.addView(section("Громкость", volumeLabel, volume,
            ModernUi.caption(this, "Не зависит от громкости планшета. Отпустите ползунок — прозвучит пример.")))

        val start = ModernUi.input(this, VoiceAlertSettings.minuteText(current.quietStartMin), "23:00").apply { tag = "voice_quiet_start" }
        val end = ModernUi.input(this, VoiceAlertSettings.minuteText(current.quietEndMin), "07:00").apply { tag = "voice_quiet_end" }
        val quietStatus = ModernUi.caption(this, "")
        fun applyQuiet() {
            val s = VoiceAlertSettings.parseMinute(start.text.toString())
            val e = VoiceAlertSettings.parseMinute(end.text.toString())
            if (s == null || e == null) { quietStatus.text = "Время в формате ЧЧ:ММ, например 23:00"; return }
            quietStatus.text = if (s == e) "Тихие часы выключены (начало = конец)" else
                "Тихие часы: ${VoiceAlertSettings.minuteText(s)}–${VoiceAlertSettings.minuteText(e)}"
            change(current.copy(quietStartMin = s, quietEndMin = e))
        }
        listOf(start, end).forEach { field ->
            field.setOnFocusChangeListener { _, focused -> if (!focused) applyQuiet() }
            field.setOnEditorActionListener { _, _, _ -> applyQuiet(); false }
        }
        val quietLabel = ModernUi.body(this, "")
        val quiet = slider(current.quietPercent, "voice_quiet_percent", quietLabel, { "Громкость в тихие часы: $it% от обычной" }) { value ->
            change(current.copy(quietPercent = value))
            PanelAlerts.preview("Сеть появилась", current.volumePercent / 100f * value / 100f)
        }
        column.addView(gap()); column.addView(section("Тихие часы", ModernUi.body(this, "Начало"), start, ModernUi.body(this, "Конец"), end,
            quietStatus, quietLabel, quiet))

        val lowLabel = ModernUi.body(this, "")
        val low = slider(current.batteryLowPercent, "voice_battery_low", lowLabel, { "Низкий заряд АКБ — ниже ${it.coerceAtLeast(1)}%" },
            max = 90) { value -> change(current.copy(batteryLowPercent = value.coerceAtLeast(1))) }
        column.addView(gap()); column.addView(section("Пороги", lowLabel, low))
        column.addView(gap()); column.addView(ModernUi.secondaryButton(this, "Готово") { finish() }.apply { tag = "voice_done" })
        return ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@VoiceAlertsActivity, R.color.modern_background))
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun change(next: VoiceAlertSettings) {
        if (next == current || !requireAdmin()) return
        current = next
        PanelAlerts.updateSettings(next)
    }

    private fun section(title: String, vararg children: android.view.View) = ModernUi.section(this, title, *children)

    private fun gap() = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, resources.getDimensionPixelSize(R.dimen.modern_space_m))
    }

    /** Подпись обновляется при движении; значение применяется при отпускании (тогда же — прослушивание). */
    private fun slider(value: Int, tag: String, label: TextView, text: (Int) -> String, max: Int = 100,
                       onRelease: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        progress = value
        this.tag = tag
        minimumHeight = resources.getDimensionPixelSize(R.dimen.modern_touch_target)
        progressTintList = ContextCompat.getColorStateList(this@VoiceAlertsActivity, R.color.modern_accent)
        thumbTintList = progressTintList
        label.text = text(value)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { label.text = text(progress) }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) = onRelease(seekBar.progress)
        })
    }
}
