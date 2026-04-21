package com.example.smarthomedashboard

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit

class SettingsActivity : AppCompatActivity() {

    private lateinit var etMqttHost: EditText
    private lateinit var etMqttPort: EditText
    private lateinit var etMqttUsername: EditText
    private lateinit var etMqttPassword: EditText
    private lateinit var spinnerTheme: Spinner
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etMqttHost = findViewById(R.id.etMqttHost)
        etMqttPort = findViewById(R.id.etMqttPort)
        etMqttUsername = findViewById(R.id.etMqttUsername)
        etMqttPassword = findViewById(R.id.etMqttPassword)
        spinnerTheme = findViewById(R.id.spinnerTheme)
        btnSave = findViewById(R.id.btnSave)

        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)

        etMqttHost.setText(prefs.getString("mqtt_host", "192.168.1.253"))
        etMqttPort.setText(prefs.getInt("mqtt_port", 1883).toString())
        etMqttUsername.setText(prefs.getString("mqtt_username", "murat"))
        etMqttPassword.setText(prefs.getString("mqtt_password", "019137-smS"))

        val theme = prefs.getString("app_theme", "system") ?: "system"
        spinnerTheme.setSelection(
            when (theme) {
                "light" -> 1
                "dark" -> 2
                else -> 0
            }
        )

        btnSave.setOnClickListener {
            val host = etMqttHost.text.toString().ifEmpty { "192.168.1.253" }
            val port = etMqttPort.text.toString().toIntOrNull() ?: 1883
            val username = etMqttUsername.text.toString()
            val password = etMqttPassword.text.toString()

            val selectedTheme = when (spinnerTheme.selectedItemPosition) {
                1 -> "light"
                2 -> "dark"
                else -> "system"
            }

            prefs.edit {
                putString("mqtt_host", host)
                putInt("mqtt_port", port)
                putString("mqtt_username", username)
                putString("mqtt_password", password)
                putString("app_theme", selectedTheme)
            }

            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }
    }
}