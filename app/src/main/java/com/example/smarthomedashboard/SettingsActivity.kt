package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONTokener
import java.io.IOException
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : PinProtectedActivity() {

    private lateinit var etHaLocalHost: EditText
    private lateinit var etHaRemoteHost: EditText
    private lateinit var etHaToken: EditText
    private lateinit var tvHaTokenStatus: TextView
    private var haTokens: HaTokenAccess? = null
    private var tokenRequest = 0
    private fun tokens() = haTokens ?: HaTokenAccess.create(this).also { haTokens = it }
    private lateinit var tvHaConnectionStatus: TextView
    private lateinit var etAdminPin: EditText
    private lateinit var etPinSessionMinutes: EditText
    private lateinit var etScreenTimeout: EditText
    private lateinit var rgTempUnit: RadioGroup
    private lateinit var tvMotionSensor: TextView
    private lateinit var btnSelectMotionSensor: Button
    private lateinit var btnClearMotionSensor: Button
    private lateinit var spinnerTheme: Spinner
    private lateinit var cbKioskMode: CheckBox
    private lateinit var cbUseYandexWeather: CheckBox
    private lateinit var etYandexApiKey: EditText
    private lateinit var tvYandexKeyStatus: TextView
    private var weatherKeys: WeatherKeyAccess? = null
    private fun weatherAccess() = weatherKeys ?: WeatherKeyAccess.create(this).also { weatherKeys = it }
    private var weatherRequest = 0
    private var weatherRevision = -1L
    private var weatherLoading = false
    private var weatherConfigurationLoaded = false
    private var weatherFormConfiguration: WeatherKeyAccess.Configuration? = null
    private lateinit var etYandexLat: EditText
    private lateinit var etYandexLon: EditText
    private lateinit var btnExport: Button
    private lateinit var btnImport: Button
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etHaLocalHost = findViewById(R.id.etHaLocalHost)
        etHaRemoteHost = findViewById(R.id.etHaRemoteHost)
        etHaToken = findViewById(R.id.etHaToken)
        tvHaTokenStatus = findViewById(R.id.tvHaTokenStatus)
        tvHaConnectionStatus = findViewById(R.id.tvHaConnectionStatus)
        etAdminPin = findViewById(R.id.etAdminPin)
        etPinSessionMinutes = findViewById(R.id.etPinSessionMinutes)
        etScreenTimeout = findViewById(R.id.etScreenTimeout)
        rgTempUnit = findViewById(R.id.rgTempUnit)
        tvMotionSensor = findViewById(R.id.tvMotionSensor)
        btnSelectMotionSensor = findViewById(R.id.btnSelectMotionSensor)
        btnClearMotionSensor = findViewById(R.id.btnClearMotionSensor)
        spinnerTheme = findViewById(R.id.spinnerTheme)
        cbKioskMode = findViewById(R.id.cbKioskMode)
        cbUseYandexWeather = findViewById(R.id.cbUseYandexWeather)
        etYandexApiKey = findViewById(R.id.etYandexApiKey)
        tvYandexKeyStatus = findViewById(R.id.tvYandexKeyStatus)
        etYandexLat = findViewById(R.id.etYandexLat)
        etYandexLon = findViewById(R.id.etYandexLon)
        btnExport = findViewById(R.id.btnExport)
        btnImport = findViewById(R.id.btnImport)
        btnSave = findViewById(R.id.btnSave)

        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)

        etHaLocalHost.setText(prefs.getString("ha_local_host", ""))
        etHaRemoteHost.setText(prefs.getString("ha_remote_host", ""))
        prepareTransportWarning()
        prepareTokenInput()
        loadHaTokenStatus()
        tvHaConnectionStatus.text = "Статус: ${prefs.getString("ha_connection_status", "Не подключено")}"
        etAdminPin.text.clear()
        etAdminPin.isSaveEnabled=false
        etAdminPin.isSaveFromParentEnabled=false
        etAdminPin.excludeFromAutofill()
        etAdminPin.isFocusable=false
        etAdminPin.hint="Управление PIN и восстановлением"
        etAdminPin.setOnClickListener { PinSecurityUi.manage(this) }
        etPinSessionMinutes.setText(prefs.getInt("pin_session_minutes", 60).coerceAtLeast(5).toString())
        
        val motionSensorId = prefs.getString("motion_sensor_id", "")
        tvMotionSensor.text = if (motionSensorId.isNullOrEmpty()) "Датчик движения: Не выбран" else "Датчик: $motionSensorId"
        etScreenTimeout.setText(prefs.getInt("screen_timeout_minutes", 5).toString())

        prepareWeatherInput()
        loadWeatherConfiguration()

        cbKioskMode.isChecked = prefs.getBoolean("kiosk_mode", false)

        val tempUnit = prefs.getString("temp_unit", "C") ?: "C"
        if (tempUnit == "F") {
            findViewById<RadioButton>(R.id.rbFahrenheit).isChecked = true
        } else {
            findViewById<RadioButton>(R.id.rbCelsius).isChecked = true
        }

        val theme = prefs.getString("app_theme", "system") ?: "system"
        spinnerTheme.setSelection(
            when (theme) {
                "light" -> 1
                "dark" -> 2
                else -> 0
            }
        )

        btnSave.setOnClickListener { saveCurrentSettings() }

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
        findViewById<Button>(R.id.btnHelp).setOnClickListener { UserHints.showWelcome(this) }

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }
    }

    private fun prepareTransportWarning() {
        val warning=findViewById<TextView>(R.id.tvHaTransportWarning)
        fun update(){
            val message=HomeAssistantAddress.transportWarning(listOf(etHaLocalHost.text.toString(),etHaRemoteHost.text.toString()))
            warning.text=message.orEmpty()
            warning.visibility=if(message==null)android.view.View.GONE else android.view.View.VISIBLE
        }
        val watcher=object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){update()}
            override fun afterTextChanged(s:android.text.Editable?){}
        }
        etHaLocalHost.addTextChangedListener(watcher);etHaRemoteHost.addTextChangedListener(watcher);update()
    }

    private fun prepareWeatherInput() {
        etYandexApiKey.isSaveEnabled = false
        etYandexApiKey.isSaveFromParentEnabled = false
        etYandexApiKey.text.clear()
    }

    private fun setWeatherPending(pending: Boolean) {
        weatherLoading = pending
        etYandexApiKey.isEnabled = !pending
        etYandexLat.isEnabled = !pending
        etYandexLon.isEnabled = !pending
        cbUseYandexWeather.isEnabled = !pending
    }

    private fun setSavePending(pending: Boolean) {
        btnSave.isEnabled = !pending
        etHaToken.isEnabled = !pending
        etHaLocalHost.isEnabled = !pending
        etHaRemoteHost.isEnabled = !pending
        setWeatherPending(pending)
    }

    private fun loadWeatherConfiguration() {
        val request = ++weatherRequest
        setWeatherPending(true)
        tvYandexKeyStatus.text = "Чтение настроек Яндекса..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { weatherAccess().read() }
            if (request != weatherRequest || isFinishing || isDestroyed) return@launch
            setWeatherPending(false)
            if (!WeatherKeyAccess.isCurrent(result) ||
                weatherConfigurationLoaded && result.revision != weatherRevision) {
                tvYandexKeyStatus.text = "Настройки Яндекса изменились. Откройте экран повторно"
                return@launch
            }
            weatherRevision = result.revision
            tvYandexKeyStatus.text = result.message
            if (!weatherConfigurationLoaded && result.configuration != null) {
                etYandexLat.setText(result.configuration.latitude)
                etYandexLon.setText(result.configuration.longitude)
                cbUseYandexWeather.isChecked = result.configuration.enabled
                weatherConfigurationLoaded = true
                weatherFormConfiguration = result.configuration
            }
        }
    }

    private fun saveCurrentSettings() {
        if(!isPinAccessAllowed())return
        if (weatherLoading || !weatherConfigurationLoaded) {
            tvYandexKeyStatus.text = "Дождитесь чтения корректных настроек Яндекса"
            return
        }
        val newWeatherKey = etYandexApiKey.text.toString().trim().takeIf { it.isNotEmpty() }
        val latitude = etYandexLat.text.toString()
        val longitude = etYandexLon.text.toString()
        // Save постороннего параметра не нормализует прежние координаты.
        val weatherConfiguration = WeatherKeyAccess.Configuration(
            if (latitude == weatherFormConfiguration?.latitude) latitude else latitude.trim(),
            if (longitude == weatherFormConfiguration?.longitude) longitude else longitude.trim(),
            cbUseYandexWeather.isChecked)
        val expectedWeatherRevision = weatherRevision
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val haLocal = etHaLocalHost.text.toString().trim()
        val haRemote = etHaRemoteHost.text.toString().trim()
        val haToken = etHaToken.text.toString().trim()

        val sessionMinutes = etPinSessionMinutes.text.toString().toIntOrNull()
        if(sessionMinutes==null || sessionMinutes<5){
            etPinSessionMinutes.error="Минимальное время повторного запроса — 5 минут"
            return
        }
        val screenTimeout = etScreenTimeout.text.toString().toIntOrNull() ?: 5

        val selectedTheme = when (spinnerTheme.selectedItemPosition) {
            1 -> "light"
            2 -> "dark"
            else -> "system"
        }

        val tempUnit = if (rgTempUnit.checkedRadioButtonId == R.id.rbFahrenheit) "F" else "C"

        val request = ++tokenRequest
        val revision = HaTokenAccess.revision
        setSavePending(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (haToken.isEmpty()) tokens().read() else tokens().replace(haToken, revision)
            }
            if (request != tokenRequest || isFinishing || isDestroyed || !isPinAccessAllowed()) return@launch
            if (!HaTokenAccess.isCurrent(result) ||
                result.status !in listOf(HaTokenAccess.Status.READY, HaTokenAccess.Status.ABSENT)) {
                setSavePending(false)
                tvHaTokenStatus.text = if (HaTokenAccess.isCurrent(result)) result.message else "Токен HA изменён. Повторите сохранение"
                return@launch
            }
            val weather = withContext(Dispatchers.IO) {
                weatherAccess().save(newWeatherKey, weatherConfiguration, expectedWeatherRevision)
            }
            if (request != tokenRequest || isFinishing || isDestroyed || !isPinAccessAllowed()) return@launch
            if (!weather.usable || !WeatherKeyAccess.isCurrent(weather) || !HaTokenAccess.isCurrent(result)) {
                if (weather.status != WeatherKeyAccess.Status.STALE && WeatherKeyAccess.isCurrent(weather)) {
                    weatherRevision = weather.revision
                }
                tvYandexKeyStatus.text = if (WeatherKeyAccess.isCurrent(weather)) weather.message else "Настройки Яндекса изменились"
                setSavePending(false)
                return@launch
            }
            weatherRevision = weather.revision
            setSavePending(false)
            prefs.edit {
                putString("ha_local_host", haLocal)
                putString("ha_remote_host", haRemote)
                putInt("pin_session_minutes", sessionMinutes)
                putInt("screen_timeout_minutes", screenTimeout)
                putString("app_theme", selectedTheme)
                putString("temp_unit", tempUnit)
                putBoolean("kiosk_mode", cbKioskMode.isChecked)
            }

            etHaToken.text.clear()
            etYandexApiKey.text.clear()
            tvYandexKeyStatus.text = weather.message
            tvHaTokenStatus.text = result.message
            Toast.makeText(this@SettingsActivity, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            finish()
        }

    }

    private fun prepareTokenInput() {
        etHaToken.isSaveEnabled = false
        etHaToken.isSaveFromParentEnabled = false
        etHaToken.text.clear()
    }

    private fun loadHaTokenStatus() {
        val request = ++tokenRequest
        tvHaTokenStatus.text = "Проверка токена HA..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { tokens().read() }
            if (request == tokenRequest && !isFinishing && !isDestroyed && HaTokenAccess.isCurrent(result)) {
                tvHaTokenStatus.text = result.message
            }
        }
    }

    override fun onPause() {
        tokenRequest++
        weatherRequest++
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if(::etAdminPin.isInitialized)etAdminPin.text.clear()
        if (::tvHaTokenStatus.isInitialized) {
            btnSave.isEnabled = true
            etHaToken.isEnabled = true
            etHaLocalHost.isEnabled = true
            etHaRemoteHost.isEnabled = true
            loadHaTokenStatus()
        }
        if (::tvYandexKeyStatus.isInitialized) loadWeatherConfiguration()
    }

    override fun onDestroy() {
        tokenRequest++
        weatherRequest++
        super.onDestroy()
    }

    private fun exportConfig() {
        AlertDialog.Builder(this)
            .setTitle("Экспорт настроек")
            .setMessage("Данные доступа и PIN не входят в файл. Персональные параметры, например адрес сервера, названия и настройки плиток, могут входить.")
            .setPositiveButton("Продолжить") { _, _ ->
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "smarthome_config.json")
                }
                startActivityForResult(intent, 200)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun buildExportConfig(): JSONObject {
        val mainPrefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val tilePrefs = getSharedPreferences("tiles_prefs", MODE_PRIVATE)
        // Разрешены только прежние несекретные поля; новые prefs не попадают в экспорт автоматически.
        return JSONObject().apply {
            CONFIG_STRING_DEFAULTS.forEach { (key, default) -> put(key, mainPrefs.getString(key, default)) }
            CONFIG_INT_DEFAULTS.forEach { (key, default) -> put(key, mainPrefs.getInt(key, default)) }
            put("kiosk_mode", mainPrefs.getBoolean("kiosk_mode", false))
            put("tiles_json", tilePrefs.getString("tiles", "[]"))
        }
    }

    private fun parseConfigObject(text: String): JSONObject {
        val tokener = JSONTokener(text)
        val json = tokener.nextValue() as? JSONObject ?: throw JSONException("Ожидался объект настроек")
        require(tokener.nextClean().code == 0)
        return json
    }

    private fun configInt(value: Any?): Int {
        require(value is Int || value is Long && value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return (value as Number).toInt()
    }

    private fun validateImportedTiles(text: String) {
        val tokener = JSONTokener(text)
        val array = tokener.nextValue() as? JSONArray ?: throw JSONException("Ожидался список плиток")
        require(tokener.nextClean().code == 0)
        for (i in 0 until array.length()) {
            val tile = array.getJSONObject(i)
            // Проверяем типы данных, но не пересобираем JSON: порядок и дополнительные поля сохраняются.
            for (key in listOf("id", "type", "container", "title")) require(tile.opt(key) is String)
            for (key in listOf("x", "y", "width", "height")) configInt(tile.opt(key))
            for (key in listOf("appearance", "conditions", "config", "collapsedSensorIds", "colorRules", "icon", "sourceType")) {
                if (tile.has(key)) require(tile.opt(key) is String)
            }
            if (tile.has("fontSize")) configInt(tile.opt("fontSize"))
            if (tile.has("enabled")) require(tile.opt("enabled") is Boolean)
        }
    }

    private fun applyImportedConfig(json: JSONObject): Boolean {
        val strings = linkedMapOf<String, String>()
        val integers = linkedMapOf<String, Int>()
        CONFIG_STRING_DEFAULTS.keys.forEach { key ->
            if (json.has(key)) strings[key] = json.get(key) as? String ?: throw JSONException("Неверный тип настройки")
        }
        CONFIG_INT_DEFAULTS.keys.forEach { key ->
            if (json.has(key)) integers[key] = configInt(json.get(key))
        }
        val kiosk = if (json.has("kiosk_mode")) {
            json.get("kiosk_mode") as? Boolean ?: throw JSONException("Неверный тип настройки")
        } else null
        val tiles = if (json.has("tiles_json")) {
            (json.get("tiles_json") as? String ?: throw JSONException("Неверный тип списка плиток"))
                .also { validateImportedTiles(it) }
        } else null
        val skipped = json.keys().asSequence().any { it !in CONFIG_ALLOWED_KEYS }

        // Вся проверка завершена до первой записи. Отсутствующие поля, секреты и допуск не изменяем.
        if (strings.isNotEmpty() || integers.isNotEmpty() || kiosk != null) {
            getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit {
                strings.forEach { (key, value) -> putString(key, value) }
                integers.forEach { (key, value) -> putInt(key, value) }
                kiosk?.let { putBoolean("kiosk_mode", it) }
            }
        }
        if (tiles != null) {
            getSharedPreferences("tiles_prefs", MODE_PRIVATE).edit { putString("tiles", tiles) }
        }
        return skipped
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
            200 -> {
                data?.data?.let { uri ->
                    try {
                        val config = buildExportConfig().toString(4)
                        val output = contentResolver.openOutputStream(uri) ?: throw IOException()
                        output.use { it.write(config.toByteArray(Charsets.UTF_8)) }
                        Toast.makeText(this, "Конфиг сохранен", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        if (e !is IOException && e !is JSONException && e !is SecurityException) throw e
                        Toast.makeText(this, "Не удалось сохранить файл настроек", Toast.LENGTH_LONG).show()
                    }
                }
            }
            201 -> {
                data?.data?.let { uri ->
                    try {
                        val input = contentResolver.openInputStream(uri) ?: throw IOException()
                        val json = input.bufferedReader(Charsets.UTF_8).use { parseConfigObject(it.readText()) }
                        val skipped = applyImportedConfig(json)
                        val message = if (skipped) {
                            "Настройки импортированы. Данные доступа, PIN и другие неподдерживаемые поля пропущены. Перезагрузка..."
                        } else {
                            "Конфиг успешно импортирован. Перезагрузка..."
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        if (e !is IOException && e !is JSONException && e !is IllegalArgumentException && e !is SecurityException) throw e
                        // Сообщение парсера может содержать секрет из старого файла, поэтому его не показываем.
                        Toast.makeText(this, "Не удалось импортировать файл: проверьте формат настроек", Toast.LENGTH_LONG).show()
                        return
                    }

                    val restartIntent = packageManager.getLaunchIntentForPackage(packageName)
                    if (restartIntent != null) {
                        restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(restartIntent)
                        finishAffinity()
                    }
                }
            }
        }
    }

    companion object {
        private const val REQUEST_SELECT_MOTION_SENSOR = 1004
        private val CONFIG_STRING_DEFAULTS = linkedMapOf(
            "motion_sensor_id" to "", "app_theme" to "system"
        )
        private val CONFIG_INT_DEFAULTS = linkedMapOf(
            "pin_session_minutes" to 60, "screen_timeout_minutes" to 5
        )
        private val CONFIG_ALLOWED_KEYS = CONFIG_STRING_DEFAULTS.keys + CONFIG_INT_DEFAULTS.keys +
            setOf("kiosk_mode", "tiles_json")
    }
}
