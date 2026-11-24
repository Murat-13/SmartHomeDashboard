package com.example.smarthomedashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Проверка адреса и токена настоящим подключением и их сохранение — одинаково у мастера и «Войти заново»
 * (раньше жило в `SetupActivity`). Только auth_ok разрешает сохранить токен и адрес. Главный поток.
 */
internal class HaConnector(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val tokens: () -> HaTokenAccess
) {
    /** Что сохранить. [external] без шифрования допускается только с прежним явным разрешением владельца. */
    data class Target(val local: String, val external: String, val uuid: String = "")

    private var connection: HomeAssistantWebSocket? = null
    private var attempt = 0
    private val handler = Handler(Looper.getMainLooper())
    val busy get() = connection != null || pending
    private var pending = false

    /**
     * [replacement] — новый токен; пусто — сохранённый. [onStatus] — ход; [onDone] — итог: null — сохранено, иначе причина
     * (прежнее подключение не изменено).
     */
    fun start(target: Target, replacement: String, onStatus: (String) -> Unit, onDone: (String?) -> Unit) {
        cancel()
        val hosts = listOf(target.local, target.external).filter { it.isNotEmpty() }.distinct()
        if (hosts.isEmpty()) { onDone("Укажите адрес Home Assistant"); return }
        val current = attempt
        pending = true
        fun finish(message: String?) {
            if (current != attempt) return
            cancel()
            onDone(message)
        }
        onStatus("Чтение токена HA...")
        owner.lifecycleScope.launch {
            val stored = withContext(Dispatchers.IO) { tokens().read() }
            if (current != attempt) return@launch
            if (!HaTokenAccess.isCurrent(stored)) { finish("Токен HA изменён. Повторите подключение"); return@launch }
            if (stored.status != HaTokenAccess.Status.READY && stored.status != HaTokenAccess.Status.ABSENT) {
                finish(stored.message); return@launch
            }
            val token = replacement.ifEmpty { stored.value.orEmpty() }
            if (token.isBlank()) { finish(stored.message); return@launch }
            connect(hosts, target, token, replacement.isNotEmpty(), stored.revision, current, onStatus, ::finish)
        }
    }

    private fun connect(hosts: List<String>, target: Target, token: String, replacing: Boolean, revision: Long, current: Int,
                        onStatus: (String) -> Unit, finish: (String?) -> Unit) {
        fun active() = current == attempt && revision == HaTokenAccess.revision
        connection = HomeAssistantWebSocket(
            host = hosts.first(), token = token, alternateHosts = hosts.drop(1), autoReconnect = false,
            allowInsecureRemote = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(HomeAssistantAddress.PREF_ALLOW_INSECURE_REMOTE, false),
            onStateChanged = { _, _, _ -> },
            onConnected = { if (active()) save(target, token, replacing, revision, current, finish) },
            onDisconnected = {},
            onError = { if (active()) onStatus(it) },
            onStatus = {
                if (active()) { if (it.startsWith("Ошибка:")) finish(it.removePrefix("Ошибка:").trim()) else onStatus(it) }
            }
        )
        connection?.connect()
        handler.postDelayed({ if (active()) finish("Нет ответа от Home Assistant") }, TIMEOUT_MS)
    }

    private fun save(target: Target, token: String, replacing: Boolean, revision: Long, current: Int, finish: (String?) -> Unit) {
        owner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { if (replacing) tokens().replace(token, revision) else tokens().read() }
            if (current != attempt) return@launch
            if (result.status != HaTokenAccess.Status.READY || !HaTokenAccess.isCurrent(result) || result.value != token) {
                finish(if (HaTokenAccess.isCurrent(result)) result.message else "Токен HA изменён. Повторите подключение")
                return@launch
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit {
                // Другой локальный адрес — возможно, другой Home Assistant: его идентификатор узнаётся заново.
                if (target.uuid.isNotEmpty()) putString(KEY_UUID, target.uuid)
                else if (target.local != prefs.getString(ConfigBackup.HA_LOCAL, "").orEmpty().trim()) remove(KEY_UUID)
                putString(ConfigBackup.HA_LOCAL, target.local)
                putString(ConfigBackup.HA_REMOTE, target.external)
                putBoolean(KEY_DONE, true)
            }
            finish(null)
        }
    }

    fun cancel() {
        attempt++
        pending = false
        handler.removeCallbacksAndMessages(null)
        connection?.disconnect()
        connection = null
    }

    companion object {
        const val PREFS = "dashboard_prefs"
        const val KEY_DONE = "setup_completed"
        /** Постоянный идентификатор Home Assistant из mDNS (для «найден по новому адресу»). */
        const val KEY_UUID = "ha_uuid"
        private const val TIMEOUT_MS = 30_000L
    }
}
