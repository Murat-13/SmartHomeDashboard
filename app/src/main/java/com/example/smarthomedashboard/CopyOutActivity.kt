package com.example.smarthomedashboard

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.InetAddress

/**
 * «Скопировать на другой планшет» (docs/setup-tz.md, часть 5), старый планшет: код, QR и одноразовая страница в домашней сети на
 * 10 минут. Передаётся то же, что в файле копии, — без токена, паролей, ключей и PIN. Экран закрыли — страницы нет.
 */
class CopyOutActivity : PinProtectedActivity(), PanelIdle.Busy {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private var server: CopyServer? = null
    private var advert: CopyAdvert? = null
    private var until = 0L
    private var finishedText: Pair<ModernUi.Status, String>? = null

    override val idleBusy get() = server != null

    /** Адрес планшета в домашней сети (в тестах — подмена). */
    internal var homeAddress: () -> InetAddress? = { HaScan.lan(applicationContext)?.address }
    internal var newCode: () -> String = { CopyCode.create() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(build())
        begin()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun gap(size: Int = 12) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(size)) }

    private fun build(): View {
        val pad = resources.getDimensionPixelSize(R.dimen.modern_space_l)
        val column = MaxWidthLinearLayout(this).apply { orientation = LinearLayout.VERTICAL; maxWidth = dp(820); setPadding(pad, pad, pad, pad) }
        column.addView(ModernUi.header(this, "Скопировать на другой планшет"))
        column.addView(ModernUi.secondary(this, "Копируются плитки, страницы, адреса Home Assistant и настройки панели — как в файле копии. Вход в Home Assistant, " +
            "пароли камер, ключ Яндекса и PIN не копируются."))
        column.addView(gap())
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "copy_out" }
        column.addView(content)
        column.addView(gap(20))
        column.addView(ModernUi.secondaryButton(this, "Готово") { finish() }.apply { tag = "copy_done" })
        return FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@CopyOutActivity, R.color.modern_background))
            addView(ScrollView(this@CopyOutActivity).apply { addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)) })
        }
    }

    private fun begin() {
        stop()
        finishedText = null
        content.removeAllViews()
        val address = homeAddress()
        val started = try {
            address?.let { a ->
                val (w, h) = SystemBarInsets.legacyWindowSize(this)
                val payload = CopyPayload.of(this, TileScale.Screen(w, h, resources.displayMetrics.density))
                CopyServer(a, newCode(), payload.toJson(), CopyPayload.deviceName()) { event, n -> main.post { onEvent(event, n) } }
            }
        } catch (_: IOException) { null }
        if (started == null) {
            content.addView(ModernUi.statusRow(this, ModernUi.Status.WARNING, "Планшет сейчас не в домашней сети (нужен Wi-Fi или провод)").apply { id = R.id.copyStatus })
            content.addView(ModernUi.primaryButton(this, "Повторить") { begin() }.apply { tag = "copy_again" })
            return
        }
        server = started
        started.start()
        until = System.currentTimeMillis() + CopyServer.LIFETIME_MS
        advert = CopyAdvert(this).apply { start(started.port, CopyPayload.deviceName()) }
        showCode(started)
        main.post(tick)
    }

    private fun showCode(s: CopyServer) {
        val wide = resources.configuration.screenWidthDp >= WIDE_DP
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf("1. На новом планшете установите панель и откройте её.", "2. На первом экране нажмите «Скопировать с другого планшета».",
            "3. Выберите этот планшет и введите код:").forEach { text.addView(ModernUi.body(this, it)) }
        text.addView(ModernUi.header(this, CopyCode.pretty(s.code)).apply {
            tag = "copy_code"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CODE_SP)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.05f
            setPadding(0, dp(8), 0, dp(8))
        })
        text.addView(ModernUi.secondary(this, "Не находит — введите адрес: ${s.host}").apply { tag = "copy_address" })
        text.addView(ModernUi.statusRow(this, ModernUi.Status.PROGRESS, "Жду новый планшет").apply { id = R.id.copyStatus })
        val qr = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(this@CopyOutActivity).apply {
                tag = "copy_qr"
                contentDescription = "QR-код для нового планшета"
                setImageBitmap(QrCode.encode(s.link).bitmap(QR_SCALE))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(QR_DP), dp(QR_DP)))
            addView(ModernUi.caption(this@CopyOutActivity, "Или наведите на QR камеру нового планшета").apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(QR_COLUMN_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        content.addView(LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(text, if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(qr, if (wide) LinearLayout.LayoutParams(dp(QR_COLUMN_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(24) }
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        })
    }

    private var wrong = ""
    private val tick = object : Runnable {
        override fun run() {
            if (server == null) return
            paint()
            main.postDelayed(this, 1_000)
        }
    }

    private fun paint() {
        val left = ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        status(if (wrong.isEmpty()) ModernUi.Status.PROGRESS else ModernUi.Status.WARNING,
            (wrong.ifEmpty { "Жду новый планшет" }) + " · код действует ещё ${left / 60}:${(left % 60).toString().padStart(2, '0')}")
    }

    private fun onEvent(event: CopyServer.Event, n: Int) {
        if (isFinishing || isDestroyed) return
        when (event) {
            CopyServer.Event.WRONG -> { wrong = "Неверный код, осталось попыток: $n"; paint() }
            CopyServer.Event.SENT -> finishedText = ModernUi.Status.OK to "Скопировано. Продолжайте на новом планшете"
            CopyServer.Event.LOCKED -> finishedText = ModernUi.Status.ERROR to "Слишком много неверных кодов"
            CopyServer.Event.CLOSED -> if (server != null) ended(finishedText ?: (ModernUi.Status.UNKNOWN to "Код больше не действует"))
        }
    }

    private fun ended(result: Pair<ModernUi.Status, String>) {
        stop()
        content.removeAllViews()
        content.addView(ModernUi.statusRow(this, result.first, result.second).apply { id = R.id.copyStatus })
        if (result.first != ModernUi.Status.OK) content.addView(ModernUi.primaryButton(this, "Показать новый код") { begin() }.apply { tag = "copy_again" })
    }

    private fun status(state: ModernUi.Status, text: String) { content.findViewById<LinearLayout>(R.id.copyStatus)?.let { ModernUi.setStatus(it, state, text) } }

    private fun stop() {
        main.removeCallbacks(tick)
        wrong = ""
        advert?.stop()
        advert = null
        val old = server
        server = null
        old?.close()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private companion object {
        const val WIDE_DP = 700
        const val CODE_SP = 40f
        const val QR_DP = 220
        const val QR_COLUMN_DP = 300
        const val QR_SCALE = 8
    }
}
