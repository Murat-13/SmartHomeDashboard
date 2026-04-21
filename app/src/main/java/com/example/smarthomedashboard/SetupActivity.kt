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
    private lateinit var btnSkip: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        etLocalHost = findViewById(R.id.etLocalHost)
        etRemoteHost = findViewById(R.id.etRemoteHost)
        etHaToken = findViewById(R.id.etHaToken)
        btnConnect = findViewById(R.id.btnConnect)
        btnSkip = findViewById(R.id.btnSkip)
        tvStatus = findViewById(R.id.tvStatus)

        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)

        etLocalHost.setText(prefs.getString("ha_local_host", "192.168.1.253:8123"))
        etRemoteHost.setText(prefs.getString("ha_remote_host", ""))
        etHaToken.setText(prefs.getString("ha_token", ""))

        btnConnect.setOnClickListener {
            tryConnect()
        }

        btnSkip.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun tryConnect() {
        val localHost = etLocalHost.text.toString().trim()
        val remoteHost = etRemoteHost.text.toString().trim()
        val token = etHaToken.text.toString().trim()

        if (localHost.isEmpty() || token.isEmpty()) {
            tvStatus.setText(R.string.fill_fields)
            return
        }

        tvStatus.setText(R.string.checking_local)
        btnConnect.isEnabled = false
        btnSkip.isEnabled = false

        thread {
            val host = if (isHostReachable(localHost)) {
                runOnUiThread { tvStatus.setText(R.string.local_available) }
                localHost
            } else if (remoteHost.isNotEmpty()) {
                runOnUiThread { tvStatus.setText(R.string.trying_remote) }
                remoteHost
            } else {
                runOnUiThread {
                    tvStatus.setText(R.string.local_unavailable)
                    btnConnect.isEnabled = true
                    btnSkip.isEnabled = true
                }
                return@thread
            }

            runOnUiThread { tvStatus.text = getString(R.string.connecting_to, host) }
            testConnection(host, token, localHost, remoteHost)
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
        } catch (_: Exception) {
            false
        }
    }

    private fun testConnection(host: String, token: String, localHost: String, remoteHost: String) {
        val webSocket = HomeAssistantWebSocket(
            host = host,
            token = token,
            onStateChanged = { _, _, _ -> },
            onConnected = {
                runOnUiThread {
                    tvStatus.setText(R.string.connected)
                    saveSettings(localHost, remoteHost, token)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            },
            onDisconnected = {
                runOnUiThread {
                    tvStatus.setText(R.string.connection_failed)
                    btnConnect.isEnabled = true
                    btnSkip.isEnabled = true
                }
            },
            onEntitiesList = null
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