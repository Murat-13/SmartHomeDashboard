package com.example.smarthomedashboard

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit

class SettingsActivity : AppCompatActivity() {

    private lateinit var etMqttHost: EditText
    private lateinit var etMqttPort: EditText
    private lateinit var etMqttUsername: EditText
    private lateinit var etMqttPassword: EditText
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etMqttHost = findViewById(R.id.etMqttHost)
        etMqttPort = findViewById(R.id.etMqttPort)
        etMqttUsername = findViewById(R.id.etMqttUsername)
        etMqttPassword = findViewById(R.id.etMqttPassword)
        btnSave = findViewById(R.id.btnSave)

        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)

        // Загружаем сохранённые настройки MQTT
        etMqttHost.setText(prefs.getString("mqtt_host", "192.168.1.253"))
        etMqttPort.setText(prefs.getInt("mqtt_port", 1883).toString())
        etMqttUsername.setText(prefs.getString("mqtt_username", "murat"))
        etMqttPassword.setText(prefs.getString("mqtt_password", "019137-smS"))

        btnSave.setOnClickListener {
            val host = etMqttHost.text.toString().ifEmpty { "192.168.1.253" }
            val port = etMqttPort.text.toString().toIntOrNull() ?: 1883
            val username = etMqttUsername.text.toString()
            val password = etMqttPassword.text.toString()

            prefs.edit {
                putString("mqtt_host", host)
                putInt("mqtt_port", port)
                putString("mqtt_username", username)
                putString("mqtt_password", password)
            }

            Toast.makeText(this, "Настройки сохранены. Перезапустите приложение.", Toast.LENGTH_LONG).show()
            finish()
        }

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }
    }
}