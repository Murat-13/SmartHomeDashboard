package com.example.smarthomedashboard

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupActivity : AppCompatActivity() {
    private lateinit var etLocalHost: EditText
    private lateinit var etRemoteHost: EditText
    private lateinit var etHaToken: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnSkip: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvHaTokenStatus: TextView
    private var haTokens: HaTokenAccess? = null
    private fun tokens() = haTokens ?: HaTokenAccess.create(this).also { haTokens = it }
    private var connection: HomeAssistantWebSocket? = null
    private var attempt = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        etLocalHost = findViewById(R.id.etLocalHost)
        etRemoteHost = findViewById(R.id.etRemoteHost)
        etHaToken = findViewById(R.id.etHaToken)
        btnConnect = findViewById(R.id.btnConnect)
        btnSkip = findViewById(R.id.btnSkip)
        tvStatus = findViewById(R.id.tvStatus)
        tvHaTokenStatus = findViewById(R.id.tvHaTokenStatus)
        findViewById<TextView>(R.id.tvSetupAddressHint).text = UserHints.SETUP_ADDRESS
        findViewById<TextView>(R.id.tvSetupTokenHint).text = UserHints.SETUP_TOKEN
        prepareTokenInput()
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        etLocalHost.setText(prefs.getString("ha_local_host", ""))
        etRemoteHost.setText(prefs.getString("ha_remote_host", ""))
        loadTokenStatus()
        btnConnect.setOnClickListener { tryConnect() }
        btnSkip.setOnClickListener {
            cancelConnection()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun prepareTokenInput() {
        etHaToken.isSaveEnabled = false
        etHaToken.isSaveFromParentEnabled = false
        etHaToken.text.clear()
    }

    private fun loadTokenStatus() {
        val current = attempt
        tvHaTokenStatus.text = "Проверка токена HA..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { tokens().read() }
            if (current == attempt && !isFinishing && !isDestroyed && HaTokenAccess.isCurrent(result)) {
                tvHaTokenStatus.text = result.message
            }
        }
    }

    private fun pending(value: Boolean) {
        btnConnect.isEnabled = !value
        btnSkip.isEnabled = !value
        etLocalHost.isEnabled = !value
        etRemoteHost.isEnabled = !value
        etHaToken.isEnabled = !value
    }

    private fun tryConnect() {
        val local = etLocalHost.text.toString().trim()
        val remote = etRemoteHost.text.toString().trim()
        val replacement = etHaToken.text.toString().trim()
        if (local.isEmpty() && remote.isEmpty()) {
            tvStatus.setText(R.string.fill_fields)
            return
        }
        cancelConnection()
        val current = attempt
        pending(true)
        tvStatus.text = "Чтение токена HA..."
        lifecycleScope.launch {
            val stored = withContext(Dispatchers.IO) { tokens().read() }
            if (current != attempt || isFinishing || isDestroyed) return@launch
            if (!HaTokenAccess.isCurrent(stored)) {
                tvStatus.text = "Токен HA изменён. Повторите подключение"
                pending(false)
                return@launch
            }
            if (stored.status != HaTokenAccess.Status.READY && stored.status != HaTokenAccess.Status.ABSENT) {
                tvStatus.text = stored.message
                pending(false)
                return@launch
            }
            val token = replacement.ifEmpty { stored.value.orEmpty() }
            if (token.isBlank()) {
                tvStatus.text = stored.message
                pending(false)
                return@launch
            }
            connect(local, remote, token, replacement.isNotEmpty(), stored.revision, current)
        }
    }

    private fun connect(local: String, remote: String, token: String, replacing: Boolean, revision: Long, current: Int) {
        val hosts = listOf(local, remote).filter { it.isNotEmpty() }.distinct()
        fun active() = current == attempt && !isFinishing && !isDestroyed && revision == HaTokenAccess.revision
        connection = HomeAssistantWebSocket(
            host = hosts.first(), token = token, alternateHosts = hosts.drop(1), autoReconnect = false,
            onStateChanged = { _, _, _ -> },
            onConnected = {
                // Только auth_ok разрешает сохранение нового токена и параметров подключения.
                if (active()) saveSettings(local, remote, token, replacing, revision, current)
            },
            onDisconnected = {},
            onError = { if (active()) tvStatus.text = it },
            onStatus = {
                if (active()) {
                    tvStatus.text = it
                    if (it.startsWith("Ошибка:")) pending(false)
                }
            }
        )
        connection?.connect()
    }

    private fun saveSettings(local: String, remote: String, token: String, replacing: Boolean, revision: Long, current: Int) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (replacing) tokens().replace(token, revision) else tokens().read()
            }
            if (current != attempt || isFinishing || isDestroyed) return@launch
            if (result.status != HaTokenAccess.Status.READY || !HaTokenAccess.isCurrent(result) || result.value != token) {
                tvStatus.text = if (HaTokenAccess.isCurrent(result)) result.message else "Токен HA изменён. Повторите подключение"
                cancelConnection()
                pending(false)
                return@launch
            }
            getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit {
                putString("ha_local_host", local)
                putString("ha_remote_host", remote)
                putBoolean("setup_completed", true)
            }
            etHaToken.text.clear()
            tvHaTokenStatus.text = result.message
            tvStatus.setText(R.string.connected)
            cancelConnection()
            startActivity(Intent(this@SetupActivity, MainActivity::class.java))
            finish()
        }
    }

    private fun cancelConnection() {
        attempt++
        connection?.disconnect()
        connection = null
    }

    override fun onDestroy() {
        cancelConnection()
        super.onDestroy()
    }
}
