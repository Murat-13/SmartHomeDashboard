package com.example.smarthomedashboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Вход в Home Assistant (docs/setup-tz.md, часть 1): с телефона по QR, на планшете его собственной страницей или
 * токеном. Итог — постоянный доступ в том же хранилище, что и раньше; сохраняется только после успешного подключения.
 * Открывается из мастера и из Настроек («Войти заново»). Поворот вход не прерывает (configChanges в манифесте).
 */
class HaLoginActivity : PinProtectedActivity(), PanelIdle.Busy {
    private enum class Mode(val label: String) { PHONE("С телефона"), TABLET("На планшете"), TOKEN("Токеном") }

    private lateinit var target: HaConnector.Target
    private var serverName = ""
    private var mode = Mode.PHONE
    /** Вход выполнен, идёт выдача доступа и проверка подключения: переключать способ нельзя. */
    private var working = false
    private var phone: PhoneLogin? = null
    private var web: WebView? = null
    private var webAttempt: HaLogin.Attempt? = null
    private lateinit var content: FrameLayout
    private lateinit var status: LinearLayout
    private val choices = mutableMapOf<Mode, TextView>()
    private var haTokens: HaTokenAccess? = null
    private fun tokens() = haTokens ?: HaTokenAccess.create(this).also { haTokens = it }
    private val connector by lazy { HaConnector(applicationContext, this, ::tokens) }
    /** Вход и выдача доступа (в тестах — подмена). */
    internal var grant: (HaLogin.Attempt, String, String) -> HaLogin.Granted = HaLogin::grant
    internal var homeAddress: () -> java.net.InetAddress? = { HaScan.network(this)?.first }
    internal val phoneLink get() = phone?.link
    internal val webAddress get() = webAttempt?.url

    private val origin by lazy { HaServer.origin(target.local.ifEmpty { target.external }) }

    /** Ждём телефон или ответ Home Assistant — без касаний на панель не возвращаемся; в мастере возвращаться некуда. */
    override val idleBusy get() = working || phone != null ||
        !getSharedPreferences(HaConnector.PREFS, MODE_PRIVATE).getBoolean(HaConnector.KEY_DONE, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = HaConnector.Target(intent.getStringExtra(EXTRA_LOCAL).orEmpty(), intent.getStringExtra(EXTRA_EXTERNAL).orEmpty(),
            intent.getStringExtra(EXTRA_UUID).orEmpty())
        serverName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val ok = try { origin.isNotEmpty() } catch (_: IllegalArgumentException) { false }
        if (!ok) { finish(); return }
        // На экране вводятся пароль и токен: без снимков и превью в «недавних».
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mode = savedInstanceState?.getString(STATE_MODE)?.let { saved -> Mode.entries.firstOrNull { it.name == saved } } ?: Mode.PHONE
        setContentView(build())
        show(mode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, mode.name)
    }

    private fun setStatus(state: ModernUi.Status, text: String) {
        ModernUi.setStatus(status, state, text)
        status.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun build(): View {
        val pad = resources.getDimensionPixelSize(R.dimen.modern_space_l)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(ContextCompat.getColor(this@HaLoginActivity, R.color.modern_background))
        }
        column.addView(ModernUi.header(this, "Вход в Home Assistant"))
        column.addView(ModernUi.secondary(this, (if (serverName.isBlank()) "" else "$serverName · ") + origin))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(12)) }
        Mode.entries.forEach { m ->
            val button = android.widget.Button(this).apply {
                text = m.label; isAllCaps = false; tag = "login_mode_${m.name.lowercase(Locale.ROOT)}"; minHeight = dp(48)
                setTextColor(Color.WHITE)
                setOnClickListener { if (!working) show(m) }
            }
            choices[m] = button
            row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        column.addView(row)
        content = FrameLayout(this).apply { tag = "login_content" }
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = ModernUi.statusRow(this, ModernUi.Status.UNKNOWN, "")
        column.addView(status)
        column.addView(ModernUi.secondaryButton(this, "Отмена") { finish() }.apply { tag = "login_cancel" })
        return column
    }

    private fun show(next: Mode, message: String? = null) {
        closePhone()
        closeWeb()
        mode = next
        paint()
        content.removeAllViews()
        content.addView(when (next) { Mode.PHONE -> phoneView(); Mode.TABLET -> tabletView(); Mode.TOKEN -> tokenView() },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (message != null) setStatus(ModernUi.Status.ERROR, message)
    }

    private fun paint() = choices.forEach { (m, view) ->
        view.background = BatteryModernCard.rounded(if (m == mode) Color.argb(90, 95, 168, 211) else Color.argb(40, 30, 44, 56),
            10 * resources.displayMetrics.density, Color.argb(if (m == mode) 220 else 70, 95, 168, 211))
        view.isEnabled = !working
    }

    // ---- С телефона ----

    private fun phoneView(): View {
        val address = homeAddress()
        val server = try { address?.let { PhoneLogin(origin, it, ::onPhoneCode, ::onPhoneClosed) } } catch (_: java.io.IOException) { null }
        if (server == null) {
            setStatus(ModernUi.Status.WARNING, "Вход с телефона недоступен")
            return message("Планшет сейчас не в домашней сети (нужен Wi-Fi или провод). Подключите его к той же сети, что и " +
                "Home Assistant, или войдите на планшете.")
        }
        phone = server
        server.start()
        setStatus(ModernUi.Status.PROGRESS, "Жду вход с телефона (QR действует 10 минут)")
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val qr = ImageView(this).apply {
            tag = "login_qr"
            contentDescription = "QR-код для входа с телефона"
            setImageBitmap(QrCode.encode(server.link).bitmap(QR_SCALE))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            listOf("1. Телефон — в той же домашней сети, что и планшет.",
                "2. Наведите камеру телефона на QR и откройте ссылку.",
                "3. Войдите в Home Assistant на телефоне.",
                "Панель подключится сама. Пароль вводится только на странице Home Assistant.").forEach {
                addView(ModernUi.body(this@HaLoginActivity, it).apply { setPadding(0, dp(4), 0, dp(4)) })
            }
        }
        val box = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val side = if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(qr, side)
            val text = if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(24) }
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
            addView(steps, text)
        }
        return box
    }

    private fun onPhoneCode(attempt: HaLogin.Attempt, code: String) = runOnUiThread {
        if (!isFinishing && !isDestroyed && phone?.attempt === attempt) signIn(attempt, code)
    }

    private fun onPhoneClosed() = runOnUiThread {
        if (isFinishing || isDestroyed || working || mode != Mode.PHONE || phone == null) return@runOnUiThread
        phone = null
        content.removeAllViews()
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(message("QR больше не действует."))
            addView(ModernUi.primaryButton(this@HaLoginActivity, "Показать новый QR") { show(Mode.PHONE) }.apply { tag = "login_qr_again" })
        })
        setStatus(ModernUi.Status.UNKNOWN, "")
    }

    private fun closePhone() { val old = phone; phone = null; old?.close() }

    // ---- На планшете ----

    @SuppressLint("SetJavaScriptEnabled") // страница входа Home Assistant — приложение на JavaScript; моста к панели нет
    private fun tabletView(): View {
        val attempt = HaLogin.onTablet(origin)
        val view = try { WebView(this) } catch (_: RuntimeException) {
            setStatus(ModernUi.Status.WARNING, "На этом планшете нет Android System WebView")
            return message("Страница входа на этом планшете не открывается. Войдите с телефона или вставьте токен.")
        }
        webAttempt = attempt
        web = view
        view.tag = "login_web"
        view.setBackgroundColor(Color.BLACK)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
        }
        view.webViewClient = client(attempt)
        setStatus(ModernUi.Status.PROGRESS, "Открываю страницу входа…")
        clearWebData { if (web === view && !isDestroyed) view.loadUrl(attempt.url) }
        return view
    }

    private fun client(attempt: HaLogin.Attempt) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!request.isForMainFrame) return false
            if (HaLogin.isReturn(attempt, url)) { returned(attempt, url); return true }
            // Вход через сторонний сайт (если настроен в Home Assistant) — обычная страница; другие схемы не открываем.
            return request.url.scheme != "http" && request.url.scheme != "https"
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            if (HaLogin.isReturn(attempt, url)) { view.stopLoading(); returned(attempt, url) }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (webAttempt === attempt && !working && url.startsWith(origin)) setStatus(ModernUi.Status.UNKNOWN,
                "Войдите на странице Home Assistant")
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (webAttempt === attempt) failed("Сертификат этого адреса не прошёл проверку Android. Войдите с телефона или исправьте адрес")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && webAttempt === attempt && !working) {
                setStatus(ModernUi.Status.ERROR, "Страница входа не открылась: ${error.description}")
            }
        }
    }

    internal fun returned(attempt: HaLogin.Attempt, url: String) {
        if (working || webAttempt !== attempt) return
        val code = try { HaLogin.code(attempt, url) } catch (e: HaLogin.LoginException) { failed(e.message.orEmpty()); return }
        signIn(attempt, code)
    }

    private fun closeWeb() {
        val old = web ?: return
        web = null
        webAttempt = null
        old.stopLoading()
        (old.parent as? ViewGroup)?.removeView(old)
        old.destroy()
        clearWebData {}
    }

    /** Следы входа на планшете не остаются: cookie, хранилище страницы и кэш. */
    private fun clearWebData(then: () -> Unit) {
        try {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies { then() }
        } catch (_: RuntimeException) { then() }
    }

    // ---- Токеном ----

    private fun tokenView(): View {
        val input = ModernUi.input(this, "", "Вставьте токен", password = true).apply {
            tag = "login_token"
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            excludeFromAutofill()
        }
        val saved = ModernUi.secondaryButton(this, "Подключиться с сохранённым доступом") { connect(input, "") }
            .apply { tag = "login_saved"; visibility = View.GONE }
        setStatus(ModernUi.Status.UNKNOWN, "")
        lifecycleScope.launch {
            val stored = withContext(Dispatchers.IO) { tokens().read() }
            if (isDestroyed || mode != Mode.TOKEN) return@launch
            if (stored.status == HaTokenAccess.Status.READY) saved.visibility = View.VISIBLE
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ModernUi.secondary(this@HaLoginActivity, UserHints.SETUP_TOKEN))
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) })
            addView(ModernUi.primaryButton(this@HaLoginActivity, "Проверить и подключиться") {
                val value = input.text.toString().trim()
                if (value.isEmpty()) setStatus(ModernUi.Status.WARNING, "Вставьте токен") else connect(input, value)
            }.apply { tag = "login_token_go" }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(saved)
        }
        return ScrollView(this).apply { addView(column) }
    }

    private fun connect(input: EditText, value: String) {
        if (!requireAdmin()) return
        busy(true)
        save(target, value) { input.text.clear() }
    }

    // ---- Общее ----

    private fun message(text: String) = ModernUi.body(this, text).apply { setPadding(0, dp(12), 0, dp(12)) }

    private fun busy(value: Boolean) { working = value; paint() }

    private fun signIn(attempt: HaLogin.Attempt, code: String) {
        if (working) return
        busy(true)
        setStatus(ModernUi.Status.PROGRESS, "Home Assistant выдаёт доступ панели…")
        content.removeAllViews()
        content.addView(message("Вход выполнен. Подключаю панель…"))
        closeWeb()
        val run = grant
        val name = accessName()
        Thread({
            val result = runCatching { run(attempt, code, name) }
            runOnUiThread {
                closePhone()
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { granted ->
                    if (serverName.isBlank()) serverName = granted.name
                    // Внешний адрес из настроек Home Assistant — только если своего не указали.
                    val next = if (target.external.isEmpty() && target.local.isNotEmpty() && granted.external != target.local)
                        target.copy(external = granted.external) else target
                    admin = granted.admin
                    save(next, granted.token) {}
                }.onFailure { failed((it as? HaLogin.LoginException)?.message ?: "Вход не удался. Попробуйте ещё раз") }
            }
        }, "ha-login").apply { isDaemon = true; start() }
    }

    private var admin = false

    private fun save(next: HaConnector.Target, token: String, done: () -> Unit) = connector.start(next, token,
        onStatus = { if (!isDestroyed) setStatus(ModernUi.Status.PROGRESS, it) },
        onDone = { error ->
            if (!isFinishing && !isDestroyed) {
                if (error == null) {
                    done()
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_NAME, serverName).putExtra(EXTRA_ADMIN, admin))
                    finish()
                } else failed(error)
            }
        })

    private fun failed(text: String) {
        busy(false)
        setStatus(ModernUi.Status.ERROR, text)
        when (mode) {
            // Одноразовая страница и её код использованы — новый QR и новая страница входа.
            Mode.PHONE -> show(Mode.PHONE, text)
            Mode.TABLET -> show(Mode.TABLET, text)
            Mode.TOKEN -> Unit
        }
    }

    override fun onStart() {
        super.onStart()
        if (::content.isInitialized && mode == Mode.PHONE && phone == null && !working) show(Mode.PHONE)
    }

    override fun onStop() {
        // Экран не виден — страница для телефона закрывается; вернулись — новый QR.
        if (!working) closePhone()
        super.onStop()
    }

    override fun onDestroy() {
        connector.cancel()
        closePhone()
        closeWeb()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (phone == null || working || mode != Mode.PHONE) return
        // Раскладка «QR и шаги» — по ширине экрана; страница и QR те же.
        content.findViewWithTag<View>("login_qr")?.let { qr ->
            val box = qr.parent as? LinearLayout ?: return
            val wide = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            box.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            qr.layoutParams = if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            box.getChildAt(1).layoutParams = if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginStart = dp(24) } else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
    }

    /** Имя доступа в профиле Home Assistant: по нему его можно найти и отозвать. */
    private fun accessName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.titlecase(Locale.ROOT) }
        val model = Build.MODEL.orEmpty()
        val tablet = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        return "Панель — ${tablet.trim()} — ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru")).format(Date())}"
    }

    companion object {
        const val EXTRA_LOCAL = "login_local"
        const val EXTRA_EXTERNAL = "login_external"
        const val EXTRA_UUID = "login_uuid"
        const val EXTRA_NAME = "login_name"
        const val EXTRA_ADMIN = "login_admin"
        private const val STATE_MODE = "login_mode"
        private const val QR_SCALE = 8

        internal fun intent(context: Context, target: HaConnector.Target, name: String) = Intent(context, HaLoginActivity::class.java)
            .putExtra(EXTRA_LOCAL, target.local).putExtra(EXTRA_EXTERNAL, target.external)
            .putExtra(EXTRA_UUID, target.uuid).putExtra(EXTRA_NAME, name)
    }
}
