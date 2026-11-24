package com.example.smarthomedashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * «Home Assistant найден по новому адресу» (docs/setup-tz.md, решение 6): роутер выдал ему другой адрес, локальное
 * подключение пропало, но тот же Home Assistant (по `uuid` из mDNS) виден в сети. Панель только предлагает — до ответа
 * человека токен на новый адрес не уходит: имя и `uuid` в сети можно подделать. Адрес https не трогается: у него своё имя.
 * Раз в минуту с панели, главный поток; mDNS — не чаще [LOOK_MS].
 */
internal class HaAddressWatch(
    context: Context,
    private val onChange: () -> Unit,
    private val browser: (Context) -> HaBrowser = { NsdBrowser(it) },
    private val check: (String) -> HaServer.Check = HaServer::check,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "ha-address").apply { isDaemon = true } }
    private var active: HaBrowser? = null
    private var lostAt = 0L
    private var lookedAt = NEVER
    private var learnedAt = NEVER
    private var declined: String? = null

    /** Новый адрес того же Home Assistant («http://узел:порт»); null — предлагать нечего. */
    var suggestion: String? = null
        private set

    private fun prefs() = app.getSharedPreferences(HaConnector.PREFS, Context.MODE_PRIVATE)
    private fun local() = prefs().getString(ConfigBackup.HA_LOCAL, "").orEmpty().trim()
    private fun uuid() = prefs().getString(HaConnector.KEY_UUID, "").orEmpty()

    /** [onLocal] — панель подключена по локальному адресу. */
    fun tick(onLocal: Boolean) {
        val local = local()
        if (local.isEmpty() || !HomeAssistantAddress.isCleartext(local)) { offer(null); return }
        if (onLocal) {
            lostAt = 0
            offer(null)
            if (now() - learnedAt >= LEARN_MS) { learnedAt = now(); look(local) }
            return
        }
        if (lostAt == 0L) lostAt = now().coerceAtLeast(1)
        if (uuid().isEmpty() || now() - lostAt < LOST_MS || now() - lookedAt < LOOK_MS) return
        lookedAt = now()
        look(local)
    }

    /** Один просмотр mDNS на [BROWSE_MS]: узнать `uuid` своего адреса или найти свой `uuid` по другому адресу. */
    private fun look(local: String) {
        if (active != null) return
        val browser = browser(app)
        active = browser
        browser.start { service ->
            val id = service.txt["uuid"].orEmpty()
            if (id.isEmpty()) return@start
            io.execute {
                val same = sameServer(local, service)
                val known = uuid()
                if (same) {
                    if (known != id) {
                        prefs().edit { putString(HaConnector.KEY_UUID, id) }
                        Log.i(TAG, "Home Assistant в сети узнан")
                    }
                } else if (known == id) {
                    val address = HaDiscovery.direct(service)
                    val result = check(address)
                    if (result.state == HaServer.State.READY) main.post {
                        // Адрес в настройках за это время могли сменить.
                        if (local() == local && lostAt != 0L) offer(result.origin.takeIf { it != declined })
                    }
                }
            }
        }
        main.postDelayed({ if (active === browser) { browser.stop(); active = null } }, BROWSE_MS)
    }

    private fun sameServer(local: String, service: HaService): Boolean {
        val url = try { HomeAssistantAddress.httpBase(local).toHttpUrl() } catch (_: IllegalArgumentException) { return false }
        if (url.port != service.port) return false
        if (url.host.equals(service.host, true)) return true
        return try { InetAddress.getByName(url.host).hostAddress == service.host } catch (_: java.io.IOException) { false }
    }

    private fun offer(address: String?) {
        if (suggestion == address) return
        suggestion = address
        if (address != null) Log.i(TAG, "Home Assistant найден по новому адресу — предлагаю переключиться")
        onChange()
    }

    /** Человек согласился: локальный адрес — новый. */
    fun accept(): Boolean {
        val address = suggestion ?: return false
        prefs().edit { putString(ConfigBackup.HA_LOCAL, address) }
        lostAt = 0
        offer(null)
        return true
    }

    /** «Не сейчас»: этот адрес больше не предлагается до перезапуска приложения. */
    fun decline() { declined = suggestion; offer(null) }

    fun stop() {
        active?.stop()
        active = null
        main.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val TAG = "HaAddressWatch"
        const val LOST_MS = 2 * 60_000L
        const val LOOK_MS = 2 * 60_000L
        const val LEARN_MS = 24 * 3600_000L
        private const val BROWSE_MS = 8_000L
        private const val NEVER = Long.MIN_VALUE / 2
    }
}
