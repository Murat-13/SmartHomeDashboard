package com.example.smarthomedashboard

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Панель не запускается даже безопасно ([SafeStart.Mode.RESCUE], docs/reliability.md): экран без плиток и видео — с него можно проверить
 * обновление, вернуть копию настроек, посмотреть ошибку. Никто не подошёл — через [RETRY_MS] панель пробует открыться сама.
 */
class SafeStartActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    /** Поверх открыты Настройки (панель чинят) — попытка открыть панель их не перебивает: ждём ещё (STAB-08). */
    private val retry: Runnable = object : Runnable {
        override fun run() { if (PanelIdle.showing != null) main.postDelayed(this, RETRY_MS) else openPanel(normal = false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(build())
        main.postDelayed(retry, RETRY_MS)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        main.removeCallbacks(retry)
        main.postDelayed(retry, RETRY_MS)
    }

    override fun onDestroy() {
        main.removeCallbacks(retry)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun build(): View {
        val pad = resources.getDimensionPixelSize(R.dimen.modern_space_l)
        val column = MaxWidthLinearLayout(this).apply { orientation = LinearLayout.VERTICAL; maxWidth = dp(820); setPadding(pad, pad, pad, pad) }
        column.addView(ModernUi.header(this, "Панель не запускается"))
        val last = CrashLog.entries(this).firstOrNull()
        column.addView(ModernUi.secondary(this, "Панель несколько раз подряд закрылась с ошибкой, в том числе без видео и записи. Настройки и плитки на месте." +
            (last?.summary?.takeIf { it.isNotEmpty() }?.let { " Последняя ошибка: $it." } ?: "")))
        val buttons = listOf(
            Triple("Попробовать безопасно", "safe_panel") { openPanel(normal = false) },
            Triple("Обычный запуск", "safe_normal") { openPanel(normal = true) },
            Triple("Настройки: обновление, копия", "safe_settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
            Triple("Сбои и отчёт", "safe_support") { startActivity(Intent(this, SupportActivity::class.java)) })
        for ((i, b) in buttons.withIndex()) {
            val (text, id, action) = b
            column.addView((if (i == 0) ModernUi.primaryButton(this, text, action) else ModernUi.secondaryButton(this, text, action)).apply { tag = id })
        }
        column.addView(ModernUi.caption(this, "Если никто не подойдёт, через ${RETRY_MS / 60_000} минут панель попробует открыться сама."))
        return FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@SafeStartActivity, R.color.modern_background))
            addView(ScrollView(this@SafeStartActivity).apply { addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)) })
        }
    }

    private fun openPanel(normal: Boolean) {
        if (normal) SafeStart.leave(this) else SafeStart.enterSafe()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object { const val RETRY_MS = 10 * 60_000L }
}
