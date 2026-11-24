package com.example.smarthomedashboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONArray
import org.json.JSONException
import java.net.URLDecoder

/**
 * «Скопировать с другого планшета» (docs/setup-tz.md, часть 5), новый планшет: найти старый в сети (или адрес), ввести код с
 * него, посмотреть, что скопируется, и применить — так же, как файл копии. Только при первой настройке; из QR (ссылка из
 * браузера) открывается с готовым адресом и кодом, но копирует всё равно только по кнопке.
 */
class CopyInActivity : PinProtectedActivity(), PanelIdle.Busy {
    internal class Found(val name: String, val host: String)

    private lateinit var content: LinearLayout
    private lateinit var status: LinearLayout
    private var addressInput: EditText? = null
    private var codeInput: EditText? = null
    private val found = mutableListOf<Found>()
    private var picked: String? = null
    private var browser: HaBrowser? = null
    private var run = 0
    private var payload: CopyPayload? = null
    private var fit = true

    override val idleBusy get() = true

    internal var newBrowser: (Context) -> HaBrowser = { NsdBrowser(it, CopyAdvert.TYPE) }
    private fun client(host: String) = CopyClient(host, HaScan.lan(applicationContext)?.network?.socketFactory)
    internal var fetch: (String, String) -> CopyPayload = { host, code -> client(host).take(code) }
    /** Старый планшет отвечает и называет себя (в тестах — подмена). */
    internal var greet: (String) -> String = { host -> client(host).hello().name }
    /** Экран этого планшета (в тестах — подмена). */
    internal var screen: () -> TileScale.Screen = { SystemBarInsets.legacyWindowSize(this).let { (w, h) -> TileScale.Screen(w, h, resources.displayMetrics.density) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Из мастера — всегда; снаружи (ссылка из QR, другое приложение) — только пока панель не настроена.
        if (callingActivity?.packageName != packageName && !allowed(this)) {
            Toast.makeText(this, "Панель уже настроена. Копирование — при первой настройке", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(build())
        ask()
        link(intent)?.let { (host, code) ->
            addressInput?.setText(host)
            codeInput?.setText(CopyCode.pretty(code))
            setStatus(ModernUi.Status.OK, "Адрес и код — из QR. Нажмите «Скопировать»")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        link(intent)?.let { (host, code) -> if (payload == null) { addressInput?.setText(host); codeInput?.setText(CopyCode.pretty(code)) } }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun gap(size: Int = 12) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(size)) }

    private fun build(): View {
        val pad = resources.getDimensionPixelSize(R.dimen.modern_space_l)
        val column = MaxWidthLinearLayout(this).apply { orientation = LinearLayout.VERTICAL; maxWidth = dp(820); setPadding(pad, pad, pad, pad) }
        column.addView(ModernUi.header(this, "Скопировать с другого планшета"))
        column.addView(gap(8))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "copy_in" }
        column.addView(content)
        status = ModernUi.statusRow(this, ModernUi.Status.UNKNOWN, "").apply { id = R.id.copyStatus; visibility = View.GONE }
        column.addView(status)
        column.addView(gap(20))
        column.addView(ModernUi.secondaryButton(this, "Отмена") { finish() }.apply { tag = "copy_cancel" })
        return FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@CopyInActivity, R.color.modern_background))
            addView(ScrollView(this@CopyInActivity).apply { addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL)) })
        }
    }

    private fun setStatus(state: ModernUi.Status, text: String) {
        ModernUi.setStatus(status, state, text)
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- Старый планшет и код ----

    private fun ask() {
        content.removeAllViews()
        content.addView(ModernUi.body(this, "На старом планшете: Настройки → «Устройство» → «Скопировать на другой планшет». Там появится код. Оба планшета — в одной домашней сети."))
        content.addView(gap())
        content.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "copy_found" })
        content.addView(ModernUi.title(this, "Код со старого планшета"))
        codeInput = ModernUi.input(this, "", "Например, K7H-2MX-9AB").apply {
            tag = "copy_code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSaveEnabled = false
            excludeFromAutofill()
        }.also { content.addView(it) }
        content.addView(gap(8))
        content.addView(ModernUi.title(this, "Адрес, если планшет не нашёлся"))
        addressInput = ModernUi.input(this, "", "Со старого планшета, например 192.168.1.50:40123").apply { tag = "copy_address" }.also { content.addView(it) }
        content.addView(gap())
        content.addView(ModernUi.primaryButton(this, "Скопировать") { copy() }.apply { tag = "copy_start" })
        renderFound()
    }

    private fun renderFound() {
        val list = content.findViewWithTag<LinearLayout>("copy_found") ?: return
        list.removeAllViews()
        if (found.isEmpty()) list.addView(ModernUi.statusRow(this, ModernUi.Status.PROGRESS, "Ищу старый планшет в сети…"))
        found.forEach { item ->
            val on = (picked ?: found.first().host) == item.host
            list.addView(ModernUi.statusRow(this, if (on) ModernUi.Status.OK else ModernUi.Status.UNKNOWN, item.name).apply {
                id = View.generateViewId()
                isClickable = true
                contentDescription = "copy_found_${item.host}"
                setOnClickListener { picked = item.host; renderFound() }
            })
        }
        list.addView(gap(8))
    }

    /** Запись mDNS бывает старой (экран с кодом уже закрыли) — показывается только тот, кто отвечает. */
    private fun onService(s: HaService) {
        val host = "${s.host}:${s.port}"
        if (!HomeAssistantAddress.isPrivateNetwork(s.host)) return
        Thread({
            val name = runCatching { greet(host) }.getOrNull() ?: return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed || payload != null || found.any { it.host == host }) return@runOnUiThread
                // Тот же планшет показал новый код — порт другой.
                found.removeAll { it.host.substringBeforeLast(':') == s.host }
                if (picked?.substringBeforeLast(':') == s.host) picked = null
                found += Found(name.ifBlank { "Планшет" }, host)
                renderFound()
            }
        }, "copy-greet").apply { isDaemon = true; start() }
    }

    internal fun copy() {
        if (!requireAdmin()) return
        val code = CopyCode.parse(codeInput?.text?.toString().orEmpty())
        if (code == null) { setStatus(ModernUi.Status.WARNING, "Введите код со старого планшета — девять знаков"); return }
        val typed = addressInput?.text?.toString().orEmpty().trim().removePrefix("http://").trimEnd('/')
        val host = typed.ifEmpty { picked ?: found.firstOrNull()?.host.orEmpty() }
        if (host.isEmpty()) { setStatus(ModernUi.Status.WARNING, "Старый планшет не нашёлся — введите адрес с его экрана"); return }
        if (!Regex("[A-Za-z0-9.\\-]+:\\d{1,5}").matches(host) || !HomeAssistantAddress.isPrivateNetwork(host.substringBeforeLast(':'))) {
            setStatus(ModernUi.Status.ERROR, "Нужен адрес в домашней сети вместе с портом — как на экране старого планшета"); return
        }
        val current = ++run
        busy(true)
        setStatus(ModernUi.Status.PROGRESS, "Копирую…")
        Thread({
            val outcome = runCatching { fetch(host, code).also { ConfigImport.parse(it.config) } }
            runOnUiThread {
                if (current != run || isFinishing || isDestroyed) return@runOnUiThread
                busy(false)
                outcome.onSuccess { show(it) }.onFailure { e ->
                    setStatus(ModernUi.Status.ERROR, (e as? CopyClient.Failed)?.message ?: "Настройки пришли повреждёнными — попробуйте ещё раз")
                }
            }
        }, "copy-in").apply { isDaemon = true; start() }
    }

    private fun busy(value: Boolean) { content.findViewWithTag<View>("copy_start")?.isEnabled = !value }

    // ---- Что скопируется ----

    private fun show(next: CopyPayload) {
        payload = next
        stopSearch()
        setStatus(ModernUi.Status.UNKNOWN, "")
        content.removeAllViews()
        val json = ConfigImport.parse(next.config)
        val tiles = try { JSONArray(json.optString("tiles_json", "[]")) } catch (_: JSONException) { JSONArray() }
        val items = (0 until tiles.length()).mapNotNull { tiles.optJSONObject(it) }
        val pages = items.maxOfOrNull { it.optInt("page") }?.plus(1) ?: 1
        val video = items.count { DoorbellConfig.isVideo(it.optString("type")) }
        content.addView(ModernUi.statusRow(this, ModernUi.Status.OK, "Получено с «${next.name.ifBlank { "старого планшета" }}»"))
        content.addView(ModernUi.body(this, "Плиток: ${items.size}, страниц: $pages" + (if (video > 0) ", с видео: $video — их пароли введёте здесь" else "")).apply { tag = "copy_summary" })
        val mine = TileManager(this).loadTiles().size
        if (mine > 0 && !StarterTiles.untouched(this)) content.addView(ModernUi.statusRow(this, ModernUi.Status.WARNING, "Плитки этого планшета ($mine) будут заменены"))
        val from = next.screen
        val to = screen()
        if (from != null && TileScale.needed(from, to)) {
            content.addView(gap())
            content.addView(ModernUi.title(this, "Экран другого размера"))
            content.addView(ModernUi.secondary(this, "На старом — ${from.long}×${from.short}, здесь — ${to.long}×${to.short}. Плитки можно подогнать: вид тот же, " +
                "${if (TileScale.landscape(from, to) > 1) "крупнее" else "мельче"} в ${percent(TileScale.landscape(from, to))}."))
            content.addView(ModernUi.switch(this, "Подогнать под этот экран", fit) { fit = it }.apply { tag = "copy_fit" })
        }
        content.addView(gap())
        content.addView(ModernUi.primaryButton(this, "Применить") { apply() }.apply { tag = "copy_apply" })
    }

    private fun percent(s: Float) = String.format(java.util.Locale.US, "%.2f", if (s > 1) s else 1 / s).replace('.', ',') + " раза"

    internal fun apply() {
        val next = payload ?: return
        if (!requireAdmin()) return
        val outcome = try {
            val json = ConfigImport.parse(next.config)
            val from = next.screen
            val to = screen()
            if (fit && from != null && TileScale.needed(from, to) && json.has("tiles_json")) json.put("tiles_json", TileScale.apply(json.getString("tiles_json"), from, to))
            ConfigImport.apply(this, json)
        } catch (_: JSONException) { failed(); return } catch (_: IllegalArgumentException) { failed(); return }
        val data = Intent().putExtra(EXTRA_LOCAL, outcome.local.orEmpty()).putExtra(EXTRA_REMOTE, outcome.remote.orEmpty())
        setResult(RESULT_OK, data)
        // Открыли по QR из браузера: мастер не ждёт ответа — открыть его на шаге входа.
        if (callingActivity == null) startActivity(Intent(this, SetupActivity::class.java).putExtras(data).putExtra(EXTRA_COPIED, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun failed() = setStatus(ModernUi.Status.ERROR, "Настройки не подошли: на планшетах разные версии панели")

    private fun stopSearch() { browser?.stop(); browser = null }

    override fun onResume() {
        super.onResume()
        if (payload == null && browser == null && !isFinishing) browser = newBrowser(this).also { it.start(::onService) }
    }

    override fun onPause() {
        stopSearch()
        super.onPause()
    }

    override fun onDestroy() {
        run++
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LOCAL = "copy_local"
        const val EXTRA_REMOTE = "copy_remote"
        const val EXTRA_COPIED = "copy_copied"

        /** Только первая настройка: настроенную панель ссылкой или копированием не заменить. */
        internal fun allowed(context: Context): Boolean {
            val prefs = context.getSharedPreferences(HaConnector.PREFS, Context.MODE_PRIVATE)
            return !prefs.getBoolean(HaConnector.KEY_DONE, false)
        }

        /** «smarthomedashboard://copy/<адрес:порт>/<код>» → адрес и код; чужое — null. */
        internal fun link(intent: Intent?): Pair<String, String>? {
            val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
            if (uri.scheme != CopyServer.SCHEME || uri.host != "copy") return null
            val parts = uri.encodedPath.orEmpty().trim('/').split('/')
            if (parts.size != 2) return null
            val host = URLDecoder.decode(parts[0], "UTF-8")
            val code = CopyCode.parse(parts[1]) ?: return null
            return host.takeIf { Regex("[0-9.]+:\\d{1,5}").matches(it) && HomeAssistantAddress.isPrivateNetwork(it.substringBeforeLast(':')) }?.let { it to code }
        }
    }
}
