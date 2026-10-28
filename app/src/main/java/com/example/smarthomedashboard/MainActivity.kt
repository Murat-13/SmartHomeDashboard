package com.example.smarthomedashboard

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import android.util.Log

class MainActivity : AppCompatActivity() {

    private lateinit var bottomPanel: FrameLayout
    private lateinit var dimOverlay: View
    private var panelHint: View? = null
    private var panelHintText: TextView? = null
    private var shownPanelHint: UserHints.Hint? = null

    private lateinit var tileManager: TileManager
    private lateinit var yandexWeatherManager: YandexWeatherManager
    private var mqttRemovalMigration: MqttRemovalMigration? = null
    // Соединением владеет ViewModel; экран не хранит отдельную копию ссылки.
    private val webSocket: HomeAssistantWebSocket? get() = viewModel.getWebSocket()

    private var tempUnit = "C"
    private var motionSensorId: String? = null
    private var screenTimeoutMinutes = 5
    private var isDimmed = false
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { dimScreen() }

    private val viewModel: MainViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
    }

    private var isEditMode: Boolean
        get() = viewModel.uiState.value.isEditMode
        set(value) { viewModel.setEditMode(value) }

    private val panelController by lazy {
        DashboardPanelController(this, viewModel, object : DashboardPanelController.Host {
            override val panel get() = bottomPanel
            override val dim get() = dimOverlay
            override val hasPanel get() = this@MainActivity::bottomPanel.isInitialized
            override val hasDim get() = this@MainActivity::dimOverlay.isInitialized
            override val tiles get() = tileManager
            override val weather get() = yandexWeatherManager
            override val temperatureUnit get() = tempUnit
            override val motionEntity get() = motionSensorId
            override fun readPreferences() = readPanelPreferences()
            override fun updateHint() = updatePanelHint()
            override fun wake() = wakeScreen()
            override fun editTile(id: String) = openTileSettings(id)
            override fun requestEdit() = openWithPinCheck { enterEditMode() }
            override fun exitEdit() = exitEditMode()
            override fun settings() = openWithPinCheck {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
            override fun addTile() {
                startActivityForResult(Intent(this@MainActivity, TileSettingsActivity::class.java)
                    .putExtra("container", "grid"), REQUEST_TILE_SETTINGS)
            }
        })
    }

    private val screenW by lazy { resources.displayMetrics.widthPixels }
    private val screenH by lazy { resources.displayMetrics.heightPixels }

    companion object {
        private const val REQUEST_TILE_SETTINGS = 100
        private const val PREF_STARTER_TILES_DONE = "starter_tiles_done"
        private const val PREF_WELCOME_SHOWN = "welcome_shown"
        internal const val WEATHER_REFRESH_MS = 60 * 60 * 1000L

        /** Панель уже создана: по кнопке «Домой» заставка не нужна. */
        @Volatile internal var isAlive = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isAlive = true
        setupPanelWindow()
        setContentView(R.layout.activity_main)
        bottomPanel = findViewById(R.id.bottomPanel)
        dimOverlay = findViewById(R.id.dimOverlay)
        dimOverlay.setOnClickListener { panelController.collapseAll() }
        setupPanelHint(findViewById(R.id.panelHint), findViewById(R.id.tvPanelHint), findViewById(R.id.btnPanelHintClose))
        // Реальная высота панели известна только после layout: тогда уточняем место подсказки.
        bottomPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) updatePanelHint()
        }

        tileManager = TileManager(this)
        yandexWeatherManager = YandexWeatherManager(this)
        yandexWeatherManager.loadFromCache()

        createStarterTilesOnFirstLaunch()

        setupBottomPanel()
        setupWebSocket()
        showWelcomeOnFirstLaunch()
    }

    private var uiStateJob: Job? = null

    override fun onStart() {
        super.onStart()
        uiStateJob = lifecycleScope.launch {
            viewModel.uiState.map { it.statusMessage }.distinctUntilChanged().collect { status ->
                status?.let { setHaStatus(it) }
            }
        }
    }

    override fun onStop() {
        uiStateJob?.cancel()
        uiStateJob = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!::tileManager.isInitialized) return
        refreshBottomPanel()
        // Удаляем устаревшие данные подключения без доступа к сети и без блокировки UI.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                (mqttRemovalMigration ?: MqttRemovalMigration.create(applicationContext).also { mqttRemovalMigration = it }).run()
            }
        }

        // Сразу и далее раз в час: панель может неделями не уходить с экрана.
        clockHandler.removeCallbacks(weatherRefreshRunnable)
        clockHandler.post(weatherRefreshRunnable)

        setupWebSocket()
        subscribeToNeededEntities()
        checkKioskMode()
        resetIdleTimer()
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        pendingPin?.cancel()
        pendingPin = null
        haTokenRequest++
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        clockHandler.removeCallbacks(weatherRefreshRunnable)
        if (::yandexWeatherManager.isInitialized) yandexWeatherManager.cancelPendingRequests()
    }

    // Бесплатный ключ Яндекса ограничен ~50 запросами в сутки: раз в час — 24 запроса с запасом.
    private val weatherRefreshRunnable = object : Runnable {
        override fun run() {
            refreshYandexWeather()
            clockHandler.postDelayed(this, WEATHER_REFRESH_MS)
        }
    }

    private fun refreshYandexWeather() {
        // Локальный доступ к ключу не запускает запрос, если Яндекс не выбран источником погоды.
        yandexWeatherManager.fetchWeather(lifecycleScope, object : YandexWeatherManager.WeatherCallback {
            override fun onWeatherUpdated() {
                runOnUiThread { panelController.updateWeatherViews() }
            }
            override fun onError(error: String) {
                Log.e("MainActivity", "Yandex Weather Error: $error")
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun setupPanelWindow() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        // Панель висит на стене: экран не гаснет, пока она на переднем плане.
        // Флаг действует только для видимого окна, поэтому WAKE_LOCK не нужен.
        // Экономию даёт затемнение по датчику движения (dimScreen), а не отключение экрана:
        // так панель мгновенно просыпается по событию движения из HA.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun checkKioskMode() {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val isKiosk = prefs.getBoolean("kiosk_mode", false)
        try {
            if (isKiosk) {
                startLockTask()
            } else {
                stopLockTask()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        // При пересоздании (поворот) новая панель снова пометит себя в onCreate.
        isAlive = false
        clockHandler.removeCallbacks(weatherRefreshRunnable)
        if (::yandexWeatherManager.isInitialized) yandexWeatherManager.cancelPendingRequests()
        super.onDestroy()
        panelController.clearPanelInteractions()
        idleHandler.removeCallbacks(idleRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        haTokenRequest++
        haGeneration++
        viewModel.detachUi()
    }

    /**
     * Выполняет действие только после проверки PIN-кода (если он установлен).
     * Поддерживает "сессию": если верный PIN был введен недавно, повторный запрос не выводится.
     */
    private var pinAccess: PinAccess? = null
    private var pendingPin: Job? = null
    private fun openWithPinCheck(action: () -> Unit) {
        if (pendingPin?.isActive == true) return
        val access = pinAccess ?: AndroidPinAccess.create(this).also { pinAccess = it }
        pendingPin = PinSecurityUi.authorize(this, action, access = access)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        resetIdleTimer()
        panelController.backgroundTouch(event)
        return super.onTouchEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        resetIdleTimer()
        return panelController.dispatchTouch(event) ?: super.dispatchTouchEvent(event)
    }

    private fun enterEditMode() {
        isEditMode = true
        // Пользователь нашёл редактирование: подсказка «как войти» больше не нужна.
        onboardingPrefs().edit { putBoolean(UserHints.PREF_EDIT_MODE_FOUND, true) }
        refreshBottomPanel()
        Toast.makeText(this, "Режим редактирования", Toast.LENGTH_SHORT).show()
    }

    private fun exitEditMode() {
        isEditMode = false
        refreshBottomPanel()
    }

    private fun openTileSettings(id: String) {
        openWithPinCheck {
            startActivityForResult(
                Intent(this, TileSettingsActivity::class.java).putExtra("tile_id", id),
                REQUEST_TILE_SETTINGS
            )
        }
    }

    private fun refreshBottomPanel() = panelController.refreshBottomPanel()

    private fun readPanelPreferences() {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        motionSensorId = prefs.getString("motion_sensor_id", null)
        screenTimeoutMinutes = prefs.getInt("screen_timeout_minutes", 5)
        tempUnit = prefs.getString("temp_unit", "C") ?: "C"
    }

    private fun setupBottomPanel() {
        refreshBottomPanel()
    }

    // Повторное подключение после сворачивания заменяет обработчик той же View.

    private fun onboardingPrefs() = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)

    /**
     * На чистой установке создаём часы и погоду: им не нужны личные датчики.
     * Только один раз: удалённые пользователем плитки не возвращаются. Уже настроенная
     * панель прежней версии не меняется, а подсказки для новичка ей не показываются.
     */
    private fun createStarterTilesOnFirstLaunch() {
        val prefs = onboardingPrefs()
        if (prefs.getBoolean(PREF_STARTER_TILES_DONE, false)) return
        if (tileManager.loadTiles().isEmpty()) {
            val tiles = mutableListOf<TileEntity>()
            for ((type, title) in listOf("clock" to "Часы", "weather" to "Погода")) {
                val (x, y) = TilePlacement.findFreeSpot(
                    tiles, TilePlacement.WIDGET_WIDTH, TilePlacement.WIDGET_HEIGHT, screenW, screenH
                )
                tiles += TileEntity(UUID.randomUUID().toString(), type, "grid", title, x, y, 1, 1)
            }
            tileManager.saveTiles(tiles)
        } else {
            prefs.edit {
                putBoolean(PREF_WELCOME_SHOWN, true)
                putBoolean(UserHints.PREF_EDIT_MODE_FOUND, true)
            }
        }
        prefs.edit { putBoolean(PREF_STARTER_TILES_DONE, true) }
    }

    private fun showWelcomeOnFirstLaunch() {
        val prefs = onboardingPrefs()
        if (prefs.getBoolean(PREF_WELCOME_SHOWN, false)) return
        prefs.edit { putBoolean(PREF_WELCOME_SHOWN, true) }
        UserHints.showWelcome(this)
    }

    private fun setupPanelHint(container: View, text: TextView, close: View) {
        panelHint = container
        panelHintText = text
        close.setOnClickListener {
            val shown = shownPanelHint ?: return@setOnClickListener
            UserHints.hideWithUndo(container, shown, above = topBottomRowButton(), refresh = ::updatePanelHint)
        }
    }

    private fun updatePanelHint() {
        val container = panelHint ?: return
        val hint = when {
            isEditMode -> UserHints.Hint.EDIT_MODE
            !onboardingPrefs().getBoolean(UserHints.PREF_EDIT_MODE_FOUND, false) -> UserHints.Hint.HOW_TO_EDIT
            else -> null
        }?.takeUnless { UserHints.isHidden(this, it) }
        shownPanelHint = hint
        panelHintText?.text = when (hint) {
            UserHints.Hint.EDIT_MODE -> UserHints.EDIT_MODE
            UserHints.Hint.HOW_TO_EDIT -> UserHints.HOW_TO_EDIT
            else -> ""
        }
        container.isVisible = hint != null
        if (hint != null) placePanelHintAboveButtons(container)
    }

    private fun placePanelHintAboveButtons(container: View) {
        val params = container.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = resources.displayMetrics.density
        // До первого layout высоты панели ещё нет: берём экран, после layout пересчитаем.
        val margin = TilePlacement.hintBottomMargin(
            panelButtons().map { it.y to buttonHeight(it) }, panelHeight(),
            gap = (8 * density).toInt(), minMargin = (12 * density).toInt()
        )
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin
            container.layoutParams = params
        }
    }

    // До первого layout высоты панели ещё нет: берём экран, после layout пересчитаем.
    private fun panelHeight() = bottomPanel.height.takeIf { it > 0 } ?: screenH

    private fun buttonHeight(view: View) = view.layoutParams?.height?.takeIf { it > 0 } ?: view.height

    private fun panelButtons() = (0 until bottomPanel.childCount).map { bottomPanel.getChildAt(it) }
        .filter { it is Button && it.isVisible }

    /** Самая высокая кнопка нижнего ряда: над ней показываем Snackbar. */
    private fun topBottomRowButton(): View? {
        val half = panelHeight() / 2f
        return panelButtons().filter { it.y + buttonHeight(it) / 2f > half }.minByOrNull { it.y }
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            panelController.updateClockViews()
            clockHandler.postDelayed(this, 10000L)
        }
    }

    /**
     * Форматирует значение датчика с заданной точностью и конвертирует температуру, если нужно.
     */

    private var haConfiguration: List<String>? = null
    private var haGeneration = 0

    // Единственное применение статуса к экрану; обновления приходят из StateFlow только между start/stop.
    private fun setHaStatus(status: String) {
        findViewById<TextView>(R.id.tvHaLiveStatus).text = status
        getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit {
            putString("ha_connection_status", status)
        }
    }

    private var haTokens: HaTokenAccess? = null
    private var haTokenRequest = 0
    private var connectedTokenRevision = -1L
    private fun tokens() = haTokens ?: HaTokenAccess.create(this).also { haTokens = it }

    private fun setupWebSocket() {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val local = prefs.getString("ha_local_host", "").orEmpty().trim()
        val remote = prefs.getString("ha_remote_host", "").orEmpty().trim()
        val request = ++haTokenRequest
        if (connectedTokenRevision == -1L && viewModel.getWebSocket() != null) {
            connectedTokenRevision = viewModel.tokenRevision
        }
        if (connectedTokenRevision != HaTokenAccess.revision) {
            haGeneration++
            viewModel.disconnectWebSocket()
            haConfiguration = null
        }
        if (webSocket == null) viewModel.publishStatus("Чтение токена HA...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { tokens().read() }
            if (request != haTokenRequest || isFinishing || isDestroyed || !HaTokenAccess.isCurrent(result)) return@launch
            if (prefs.getString("ha_local_host", "").orEmpty().trim() != local ||
                prefs.getString("ha_remote_host", "").orEmpty().trim() != remote) return@launch
            if (result.status != HaTokenAccess.Status.READY) {
                haGeneration++
                viewModel.disconnectWebSocket()
                haConfiguration = null
                viewModel.publishStatus(result.message)
                return@launch
            }
            connectWithHaToken(local, remote, result.value.orEmpty().trim())
        }
    }

    private fun connectWithHaToken(local: String, remote: String, token: String) {
        connectedTokenRevision = HaTokenAccess.revision
        val tokenRevision = connectedTokenRevision
        viewModel.tokenRevision = tokenRevision
        val configuration = listOf(local, remote, token)
        if (haConfiguration == configuration && webSocket != null) {
            // connect() restarts a stopped client and leaves active/backoff connections alone.
            viewModel.resumeWebSocket()
            return
        }
        haConfiguration = configuration
        val generation = ++haGeneration
        val hosts = listOf(local, remote).filter { it.isNotEmpty() }.distinct()
        if (token.isEmpty() || hosts.isEmpty()) {
            viewModel.disconnectWebSocket()
            viewModel.publishStatus("Ошибка: укажите адрес и токен Home Assistant")
            return
        }
        viewModel.connectWebSocket(
            host = hosts.first(),
            token = token,
            alternateHosts = hosts.drop(1),
            onStateChangedCallback = { entity, state, _ ->
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) panelController.renderEntityState(entity, state)
            },
            onForecastChangedCallback = { entity, _ ->
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) panelController.renderForecastState(entity)
            },
            onConnectedCallback = {
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) {
                    val active = viewModel.getWebSocket()?.getHost().orEmpty()
                    val network = if (local.isNotEmpty() && active == local) "Локальная сеть" else "Интернет"
                    viewModel.publishStatus("$network ($active)")
                    Toast.makeText(this, "Подключено: $network", Toast.LENGTH_SHORT).show()
                    subscribeToNeededEntities()
                    viewModel.getEntities()
                }
            },
            onEntitiesListCallback = {
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) cacheEntities(it)
            }
        )
    }

    private fun cacheEntities(e: List<HaEntity>) {
        getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit {
            putString("cached_entities", com.google.gson.Gson().toJson(e))
            putLong("cached_entities_time", System.currentTimeMillis())
        }
    }

    private fun subscribeToNeededEntities() {
        val useYandex = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getBoolean("use_yandex_weather", false)
        val plan = PanelSubscriptionPlan.create(tileManager.getAllTiles(), motionSensorId, useYandex)
        viewModel.subscribeEntities(plan.entityIds)
        // Передаём весь актуальный набор, включая пустой при выборе Яндекса.
        viewModel.syncForecastSubscriptions(plan.forecastIds)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TILE_SETTINGS && resultCode == RESULT_OK) {
            refreshBottomPanel()
            subscribeToNeededEntities()
        }
    }

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        if (isDimmed) {
            wakeScreen()
        }
        // Запускаем таймер только если датчик движения выбран в настройках
        if (!motionSensorId.isNullOrEmpty()) {
            idleHandler.postDelayed(idleRunnable, screenTimeoutMinutes * 60 * 1000L)
        }
    }

    private fun dimScreen() {
        if (isDimmed) return
        isDimmed = true
        val params = window.attributes
        params.screenBrightness = 0.01f // Минимальная яркость
        window.attributes = params
    }

    private fun wakeScreen() {
        isDimmed = false
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE // Системное значение
        window.attributes = params
        resetIdleTimer()
    }
}
