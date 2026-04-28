package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var etMqttHost: EditText
    private lateinit var etMqttPort: EditText
    private lateinit var etMqttUsername: EditText
    private lateinit var etMqttPassword: EditText
    private lateinit var etAdminPin: EditText
    private lateinit var etPinSessionMinutes: EditText
    private lateinit var etScreenTimeout: EditText
    private lateinit var tvMotionSensor: TextView
    private lateinit var btnSelectMotionSensor: Button
    private lateinit var btnClearMotionSensor: Button
    private lateinit var spinnerTheme: Spinner
    private lateinit var cbKioskMode: CheckBox
    private lateinit var btnExport: Button
    private lateinit var btnImport: Button
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etMqttHost = findViewById(R.id.etMqttHost)
        etMqttPort = findViewById(R.id.etMqttPort)
        etMqttUsername = findViewById(R.id.etMqttUsername)
        etMqttPassword = findViewById(R.id.etMqttPassword)
        etAdminPin = findViewById(R.id.etAdminPin)
        etPinSessionMinutes = findViewById(R.id.etPinSessionMinutes)
        etScreenTimeout = findViewById(R.id.etScreenTimeout)
        tvMotionSensor = findViewById(R.id.tvMotionSensor)
        btnSelectMotionSensor = findViewById(R.id.btnSelectMotionSensor)
        btnClearMotionSensor = findViewById(R.id.btnClearMotionSensor)
        spinnerTheme = findViewById(R.id.spinnerTheme)
        cbKioskMode = findViewById(R.id.cbKioskMode)
        btnExport = findViewById(R.id.btnExport)
        btnImport = findViewById(R.id.btnImport)
        btnSave = findViewById(R.id.btnSave)

        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)

        etMqttHost.setText(prefs.getString("mqtt_host", "192.168.1.253"))
        etMqttPort.setText(prefs.getInt("mqtt_port", 1883).toString())
        etMqttUsername.setText(prefs.getString("mqtt_username", "murat"))
        etMqttPassword.setText(prefs.getString("mqtt_password", "019137-smS"))
        etAdminPin.setText(prefs.getString("admin_pin", ""))
        etPinSessionMinutes.setText(prefs.getInt("pin_session_minutes", 60).toString())
        
        val motionSensorId = prefs.getString("motion_sensor_id", "")
        tvMotionSensor.text = if (motionSensorId.isNullOrEmpty()) "Датчик движения: Не выбран" else "Датчик: $motionSensorId"
        etScreenTimeout.setText(prefs.getInt("screen_timeout_minutes", 5).toString())

        cbKioskMode.isChecked = prefs.getBoolean("kiosk_mode", false)

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
            val adminPin = etAdminPin.text.toString()
            val sessionMinutes = etPinSessionMinutes.text.toString().toIntOrNull() ?: 60
            val screenTimeout = etScreenTimeout.text.toString().toIntOrNull() ?: 5

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
                putString("admin_pin", adminPin)
                putInt("pin_session_minutes", sessionMinutes)
                putInt("screen_timeout_minutes", screenTimeout)
                putString("app_theme", selectedTheme)
                putBoolean("kiosk_mode", cbKioskMode.isChecked)
            }

            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnSelectMotionSensor.setOnClickListener {
            val intent = Intent(this, SensorPickerActivity::class.java)
            startActivityForResult(intent, REQUEST_SELECT_MOTION_SENSOR)
        }

        btnClearMotionSensor.setOnClickListener {
            prefs.edit { remove("motion_sensor_id") }
            tvMotionSensor.text = "Датчик движения: Не выбран"
            Toast.makeText(this, "Датчик удален", Toast.LENGTH_SHORT).show()
        }

        btnExport.setOnClickListener { exportConfig() }
        btnImport.setOnClickListener { importConfig() }

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }
    }

    private fun exportConfig() {
        try {
            val mainPrefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
            val tilePrefs = getSharedPreferences("tiles_prefs", MODE_PRIVATE)

            val fullConfig = JSONObject().apply {
                put("mqtt_host", mainPrefs.getString("mqtt_host", ""))
                put("mqtt_port", mainPrefs.getInt("mqtt_port", 1883))
                put("mqtt_username", mainPrefs.getString("mqtt_username", ""))
                put("mqtt_password", mainPrefs.getString("mqtt_password", ""))
                put("admin_pin", mainPrefs.getString("admin_pin", ""))
                put("pin_session_minutes", mainPrefs.getInt("pin_session_minutes", 60))
                put("screen_timeout_minutes", mainPrefs.getInt("screen_timeout_minutes", 5))
                put("motion_sensor_id", mainPrefs.getString("motion_sensor_id", ""))
                put("app_theme", mainPrefs.getString("app_theme", "system"))
                put("kiosk_mode", mainPrefs.getBoolean("kiosk_mode", false))
                put("tiles_json", tilePrefs.getString("tiles", "[]"))
            }

            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "smarthome_config.json")
            }
            startActivityForResult(intent, 200)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importConfig() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, 201)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_SELECT_MOTION_SENSOR -> {
                val selected = data?.getStringArrayListExtra("selected_sensors")
                if (!selected.isNullOrEmpty()) {
                    val sensorId = selected[0]
                    getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit {
                        putString("motion_sensor_id", sensorId)
                    }
                    tvMotionSensor.text = "Датчик: $sensorId"
                }
            }
            200 -> { // Export save
                data?.data?.let { uri ->
                    val mainPrefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
                    val tilePrefs = getSharedPreferences("tiles_prefs", MODE_PRIVATE)
                    val fullConfig = JSONObject().apply {
                        put("mqtt_host", mainPrefs.getString("mqtt_host", ""))
                        put("mqtt_port", mainPrefs.getInt("mqtt_port", 1883))
                        put("mqtt_username", mainPrefs.getString("mqtt_username", ""))
                        put("mqtt_password", mainPrefs.getString("mqtt_password", ""))
                        put("admin_pin", mainPrefs.getString("admin_pin", ""))
                        put("pin_session_minutes", mainPrefs.getInt("pin_session_minutes", 60))
                        put("screen_timeout_minutes", mainPrefs.getInt("screen_timeout_minutes", 5))
                        put("motion_sensor_id", mainPrefs.getString("motion_sensor_id", ""))
                        put("app_theme", mainPrefs.getString("app_theme", "system"))
                        put("kiosk_mode", mainPrefs.getBoolean("kiosk_mode", false))
                        put("tiles_json", tilePrefs.getString("tiles", "[]"))
                    }
                    contentResolver.openOutputStream(uri)?.use { 
                        it.write(fullConfig.toString(4).toByteArray())
                    }
                    Toast.makeText(this, "Конфиг сохранен", Toast.LENGTH_SHORT).show()
                }
            }
            201 -> { // Import load
                data?.data?.let { uri ->
                    try {
                        val jsonString = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        if (jsonString != null) {
                            val json = JSONObject(jsonString)
                            val mainPrefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
                            val tilePrefs = getSharedPreferences("tiles_prefs", MODE_PRIVATE)

                            mainPrefs.edit {
                                putString("mqtt_host", json.optString("mqtt_host"))
                                putInt("mqtt_port", json.optInt("mqtt_port", 1883))
                                putString("mqtt_username", json.optString("mqtt_username"))
                                putString("mqtt_password", json.optString("mqtt_password"))
                                putString("admin_pin", json.optString("admin_pin", ""))
                                putInt("pin_session_minutes", json.optInt("pin_session_minutes", 60))
                                putInt("screen_timeout_minutes", json.optInt("screen_timeout_minutes", 5))
                                putString("motion_sensor_id", json.optString("motion_sensor_id", ""))
                                putString("app_theme", json.optString("app_theme", "system"))
                                putBoolean("kiosk_mode", json.optBoolean("kiosk_mode", false))
                            }
                            tilePrefs.edit {
                                putString("tiles", json.optString("tiles_json", "[]"))
                            }
                            Toast.makeText(this, "Конфиг успешно импортирован. Перезагрузка...", Toast.LENGTH_LONG).show()
                            
                            val restartIntent = packageManager.getLaunchIntentForPackage(packageName)
                            restartIntent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(restartIntent)
                            finishAffinity()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    companion object {
        private const val REQUEST_SELECT_MOTION_SENSOR = 1004
    }
}
