package com.example.smarthomedashboard

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class SetupActivity : AppCompatActivity() {

    private lateinit var etLocalHost: EditText
    private lateinit var etRemoteHost: EditText
    private lateinit var etHaToken: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        etLocalHost = findViewById(R.id.etLocalHost)
        etRemoteHost = findViewById(R.id.etRemoteHost)
        etHaToken = findViewById(R.id.etHaToken)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)

        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)

        etLocalHost.setText(prefs.getString("ha_local_host", "192.168.1.253:8123"))
        etRemoteHost.setText(prefs.getString("ha_remote_host", ""))
        etHaToken.setText(prefs.getString("ha_token", ""))

        btnConnect.setOnClickListener {
            val localHost = etLocalHost.text.toString().trim()
            val remoteHost = etRemoteHost.text.toString().trim()
            val token = etHaToken.text.toString().trim()

            if (localHost.isEmpty() || token.isEmpty()) {
                tvStatus.text = "Заполните локальный адрес и токен"
                return@setOnClickListener
            }

            tvStatus.text = "Проверяю локальную сеть..."
            btnConnect.isEnabled = false

            thread {
                val host = if (isHostReachable(localHost)) {
                    runOnUiThread { tvStatus.text = "✅ Локальная сеть доступна" }
                    localHost
                } else if (remoteHost.isNotEmpty()) {
                    runOnUiThread { tvStatus.text = "⚠️ Локальная сеть недоступна, пробую интернет..." }
                    remoteHost
                } else {
                    runOnUiThread {
                        tvStatus.text = "❌ Локальная сеть недоступна, внешний адрес не указан"
                        btnConnect.isEnabled = true
                    }
                    return@thread
                }

                runOnUiThread { tvStatus.text = "Подключение к $host..." }
                testConnection(host, token, localHost, remoteHost)
            }
        }
    }

    private fun isHostReachable(host: String): Boolean {
        return try {
            val cleanHost = host.split(":").first()
            val port = host.split(":").getOrNull(1)?.toIntOrNull() ?: 8123
            val socket = Socket()
            socket.connect(InetSocketAddress(cleanHost, port), 3000)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun testConnection(host: String, token: String, localHost: String, remoteHost: String) {
        val webSocket = HomeAssistantWebSocket(
            host = host,
            token = token,
            onStateChanged = { _, _ -> },
            onConnected = {
                runOnUiThread {
                    tvStatus.text = "✅ Подключено!"
                    saveSettings(localHost, remoteHost, token)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            },
            onDisconnected = {
                runOnUiThread {
                    tvStatus.text = "❌ Не удалось подключиться"
                    btnConnect.isEnabled = true
                }
            }
        )
        webSocket.connect()
    }

    private fun saveSettings(localHost: String, remoteHost: String, token: String) {
        getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit {
            putString("ha_local_host", localHost)
            putString("ha_remote_host", remoteHost)
            putString("ha_token", token)
            putBoolean("setup_completed", true)
        }
    }
}