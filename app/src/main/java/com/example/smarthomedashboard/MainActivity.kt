package com.example.smarthomedashboard

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java].also { model ->
            if (model.powerHistory == null) model.powerHistory = PowerHistoryRecorder.get(applicationContext)
        }
    }

    private var isEditMode: Boolean
        get() = viewModel.uiState.value.isEditMode
        set(value) { viewModel.setEditMode(value) }

    // Боковая панель, жест двумя пальцами и события связи. Соединение HA не дублируется.
    private var drawer: SideDrawer? = null
    private var navigation: TwoFingerSwipe? = null
    private var internet: InternetMonitor? = null
    /** Через какой адрес подключён HA: true — локальный, false — удалённый, null — нет связи. */
    private var localRoute: Boolean? = null
    private var touchEvent: MotionEvent? = null
    private val pillHandler = Handler(Looper.getMainLooper())
    private var everConnected = false
    private var lossShown = false
    private val showLoss = Runnable {
        lossShown = true
        pillHandler.removeCallbacks(hideOperationError)
        showPill(ModernUi.Status.ERROR, "Нет связи с Home Assistant · переподключение…")
    }
    // Скрывает только плашку ошибки операции: потерю связи не снимает.
    private val hideOperationError = Runnable { if (!lossShown) hidePill.run() }
    private val hidePill = Runnable { findViewById<View?>(R.id.statusPill)?.visibility = View.GONE }

    private val panelController: DashboardPanelController by lazy {
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
            override fun addTile() = openWithPinCheck {
                TileEditorPreviewSource.attach(viewModel)
                startActivityForResult(Intent(this@MainActivity, TileSettingsActivity::class.java)
                    .putExtra("container", "grid")
                    .putExtra(TileSettingsActivity.EXTRA_PAGE, panelController.currentPage), REQUEST_TILE_SETTINGS)
            }
            override fun adminAction(retry: () -> Unit) = this@MainActivity.adminAction(retry)
            override fun pageChanged() {
                showDots(highlight = panelController.screenIndex())
                drawer?.refreshPage()
                scheduleAutoHome()
            }
        })
    }

    // Размер окна «как до Android 15» (без полосы навигации): раскладка плиток прежняя при targetSdk 35.
    private val windowSize by lazy { SystemBarInsets.legacyWindowSize(this) }
    private val screenW get() = windowSize.first
    private val screenH get() = windowSize.second

    companion object {
        private const val REQUEST_TILE_SETTINGS = 100
        private const val PREF_STARTER_TILES_DONE = "starter_tiles_done"
        private const val PREF_WELCOME_SHOWN = "welcome_shown"
        internal const val WEATHER_REFRESH_MS = 60 * 60 * 1000L
        // Короткое переподключение не показываем; при запуске даём время на первое подключение.
        private const val PILL_LOSS_DELAY_MS = 5_000L
        private const val PILL_START_DELAY_MS = 15_000L
        private const val PILL_RECOVERED_MS = 3_000L
        /** Ошибка операции при живом соединении видна на плашке столько, затем плашка скрывается. */
        internal const val PILL_OPERATION_ERROR_MS = 12_000L
        private const val PAGE_FLING = 1000f
        private const val DOTS_VISIBLE_MS = 2_000L
        private const val PEEK_FALLBACK_MS = 400L
        internal const val PREF_HOME_SECONDS = "page_home_seconds"
        internal const val PREF_MOTION_ENABLED = "motion_enabled"

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
        viewModel.onYandexWeatherChanged(yandexWeatherManager.climateData)

        createStarterTilesOnFirstLaunch()

        setupBottomPanel()
        if (savedInstanceState == null) showTileIssue()
        setupSystemPanel()
        AutoBackup.schedule(applicationContext)
        PowerHistoryBackup.schedule(applicationContext)
        AppUpdater.schedule(applicationContext)
        UpdateInstaller.schedule(applicationContext)
        setupWebSocket()
        showWelcomeOnFirstLaunch()
        startVoiceAlerts()
    }

    /**
     * Голосовые уведомления (docs/voice-alerts.md): следят за состоянием HA, пока жива панель — и когда поверх
     * открыты настройки или редактор (Activity остановлена, но не уничтожена). В тестах Robolectric не включаются.
     */
    private fun startVoiceAlerts() {
        connectDoorbells()
        if (android.os.Build.FINGERPRINT == "robolectric") return
        PanelAlerts.attach(applicationContext)
        lifecycleScope.launch {
            viewModel.uiState.map(PanelAlerts::stateKey).distinctUntilChanged().collect {
                PanelAlerts.onState(viewModel.uiState.value)
            }
        }
    }

    /** «Домофон»: окно звонка и видео пользуются соединением и командами этой панели (docs/doorbell.md). */
    private val doorbellListener: () -> Unit = { if (::tileManager.isInitialized) panelController.renderDoorbells() }
    private val doorbellActions = object : DoorbellHub.Actions {
        override fun call(domain: String, service: String, entityId: String, data: Map<String, Any>,
                          onResult: (ServiceCommandResult) -> Unit) = viewModel.callService(domain, service, entityId, data, onResult)
        override fun prepareForRing() {
            resetIdleTimer()
            if (::tileManager.isInitialized) panelController.collapseAll()
        }
        override fun tileBounds(tileId: String) = if (::tileManager.isInitialized) panelController.tileBounds(tileId) else null
        override fun keepAwake() = resetIdleTimer()
        override fun tileVideo(tileId: String) = if (::tileManager.isInitialized) panelController.tileVideo(tileId) else null
    }

    private val doorbellConnection: () -> HomeAssistantWebSocket? = { viewModel.getWebSocket() }

    private fun connectDoorbells() {
        DoorbellHub.connection = doorbellConnection
        DoorbellHub.actions = doorbellActions
        DoorbellHub.addListener(doorbellListener)
    }

    private var uiStateJob: Job? = null

    override fun onStart() {
        super.onStart()
        uiStateJob = lifecycleScope.launch {
            launch {
                viewModel.uiState.map { it.statusMessage }.distinctUntilChanged().collect { status ->
                    status?.let { setHaStatus(it) }
                }
            }
            // Достоверность показаний «АКБ Modern» зависит от связи и синхронизации сессии.
            launch {
                viewModel.uiState.map { it.haSession }.distinctUntilChanged().collect {
                    if (::tileManager.isInitialized) panelController.renderSessionState()
                }
            }
            launch {
                viewModel.uiState.map { it.connectionStatus }.distinctUntilChanged().collect { onConnectionStatus(it) }
            }
            launch {
                viewModel.uiState.map { it.operationError }.distinctUntilChanged().collect { it?.let(::onOperationError) }
            }
        }
        internet?.start()
    }

    override fun onStop() {
        uiStateJob?.cancel()
        uiStateJob = null
        internet?.stop()
        pillHandler.removeCallbacks(showLoss)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!::tileManager.isInitialized) return
        refreshPinState()
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
        pinStateRequest?.cancel()
        pinDisabled = null
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
                runOnUiThread {
                    viewModel.onYandexWeatherChanged(yandexWeatherManager.climateData)
                    panelController.updateWeatherViews()
                }
            }
            override fun onError(error: String) {
                viewModel.onYandexWeatherChanged(yandexWeatherManager.climateData)
                if (::tileManager.isInitialized && ::bottomPanel.isInitialized) panelController.updateWeatherViews()
                Log.e("MainActivity", "Yandex Weather Error: $error")
            }
        }, requiredByTile = ::tileManager.isInitialized && tileManager.getAllTiles().any { it.type == HomeClimateConfig.TYPE &&
            HomeClimateConfig.from(it.config).weather.source == HomeClimateConfig.SOURCE_YANDEX })
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
        DoorbellHub.removeListener(doorbellListener)
        if (DoorbellHub.actions === doorbellActions) DoorbellHub.actions = null
        if (DoorbellHub.connection === doorbellConnection) DoorbellHub.connection = null
        // Новое окно начинает с рабочей яркостью: пауза анимаций затемнения не переносится.
        PanelMotion.dimmed = false
        clockHandler.removeCallbacks(weatherRefreshRunnable)
        if (::yandexWeatherManager.isInitialized) yandexWeatherManager.cancelPendingRequests()
        super.onDestroy()
        drawer?.dispose()
        pageRunner.cancel()
        pageHandler.removeCallbacksAndMessages(null)
        pillHandler.removeCallbacksAndMessages(null)
        panelController.rememberForRecreation(isChangingConfigurations)
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
    /** Уже прочитано, что PIN отключён; null — состояние неизвестно и доступ не предполагается. */
    private var pinDisabled: Boolean? = null
    private var pinStateRequest: Job? = null
    private fun openWithPinCheck(action: () -> Unit) {
        if (pendingPin?.isActive == true) return
        val access = pinAccess ?: AndroidPinAccess.create(this).also { pinAccess = it }
        pendingPin = PinSecurityUi.authorize(this, action, access = access,
            onStatus = { pinDisabled = it == PinAccess.Status.DISABLED })
    }

    /**
     * Сохранение из режима редактирования разрешено сразу, если PIN отключён или сессия жива
     * (админ-действие её продлевает). Иначе действие не сохраняется и запрашивается PIN;
     * [retry] выполняется, только если проверка показала, что PIN отключён.
     */
    private fun adminAction(retry: () -> Unit): Boolean {
        if (pinDisabled == true || PinSecurityUi.session.touch(PinSecurityUi.sessionMinutes(this))) return true
        openWithPinCheck {
            if (pinDisabled == true) retry()
            else Toast.makeText(this, "PIN подтверждён — повторите действие", Toast.LENGTH_SHORT).show()
        }
        return false
    }

    /** В других экранах PIN могли включить или отключить: состояние перечитывается после возврата. */
    private fun refreshPinState() {
        val access = pinAccess ?: return
        pinStateRequest = lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { access.status() }.status
            pinDisabled = status == PinAccess.Status.DISABLED
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        resetIdleTimer()
        panelController.backgroundTouch(event)
        return super.onTouchEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        resetIdleTimer()
        // Окно звонка поверх панели: жесты панели (страницы, боковая панель) не работают, касания — окну.
        if (DoorbellHub.RingUi.current?.let { it.isShowing && it.host === this } == true) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) scheduleAutoHome()
        val side = drawer
        val swipe = navigation
        if (side != null && swipe != null) {
            touchEvent = event
            // Распознанный жест двумя пальцами ведёт себя сам до отпускания пальцев.
            if (swipe.active) {
                swipe.onTouch(event)
                touchEvent = null
                return true
            }
            // Превью страницы ещё прикрывает новую (доли секунды): его кнопки не нажимаются.
            if (pagePeek?.visibility == View.VISIBLE) return true
            if (side.isOpen) return side.handleTouch(event) || super.dispatchTouchEvent(event)
            if (side.edgeTap(event, panelController::hasTileAt)) return true
            if (swipe.onTouch(event)) return true
            touchEvent = null
        }
        return panelController.dispatchTouch(event) ?: super.dispatchTouchEvent(event)
    }

    /** Боковая панель, жест двумя пальцами и монитор интернета. */
    private fun setupSystemPanel() {
        // Раскрытая карточка/группа: «Назад» сворачивает её. Зарегистрирован раньше боковой панели —
        // открытая панель (и полноэкранный график, добавляемый позже) обрабатывают «Назад» первыми.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                DoorbellHub.RingUi.current?.takeIf { it.isShowing && it.host === this@MainActivity }?.let { it.close(); return }
                if (::tileManager.isInitialized && panelController.collapseOnBack()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        val root = findViewById<FrameLayout?>(R.id.mainRoot) ?: return
        val back = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { drawer?.close() }
        }
        onBackPressedDispatcher.addCallback(this, back)
        val side = SideDrawer(root, object : SideDrawer.Host {
            override fun settings() = openWithPinCheck { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            override fun addTile() = openWithPinCheck { chooseAddition() }
            override fun edit() = openWithPinCheck { enterEditMode() }
            override fun portraitCustom() = panelController.hasCustomPortrait()
            override fun resetPortrait() = openWithPinCheck {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Вернуть автоматическую раскладку?")
                    .setMessage("Вертикальная раскладка этой страницы снова будет строиться из горизонтальной. " +
                        "Горизонтальная раскладка не изменится.")
                    .setPositiveButton("Вернуть") { _, _ -> panelController.resetPortraitLayout() }
                    .setNegativeButton("Отмена", null).show()
            }
            override fun installUpdate() = openWithPinCheck { UpdateInstallUi.start(this@MainActivity) }
            override fun endAdmin() {
                PinSecurityUi.session.revoke()
                Toast.makeText(this@MainActivity, "Админ-режим завершён", Toast.LENGTH_SHORT).show()
            }
            override fun adminActive() = pinDisabled != true &&
                PinSecurityUi.session.isValid(PinSecurityUi.sessionMinutes(this@MainActivity))
            override fun busy() = pendingPin?.isActive == true
            override fun stateChanged(open: Boolean) {
                back.isEnabled = open
                if (open) updateSystemStatus()
            }
            override fun pageText() = "Страница ${panelController.currentPage + 1} из ${panelController.pageCount()}"
        })
        drawer = side
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        navigation = TwoFingerSwipe(slop, { !isEditMode && !panelController.hasExpansion() && !side.isOpen && !pageRunner.running },
            object : TwoFingerSwipe.Listener {
                private var pages = false
                // На главной слева направо — панель; иначе листание на соседнюю существующую страницу.
                override fun onStart(direction: Int): Boolean {
                    // Листание идёт по экранам: страницы и их продолжения в вертикальном положении (0.9.19).
                    val screen = panelController.screenIndex()
                    pages = !(direction > 0 && screen == 0)
                    if (!pages) return true
                    val target = screen - direction
                    if (target < 0 || target >= panelController.screenCount()) return false
                    beginPageDrag(target)
                    return true
                }
                override fun onClaim() = cancelPanelTouches()
                override fun onDrag(dx: Float) = if (pages) dragPage(dx) else side.dragOpen(dx)
                override fun onEnd(dx: Float, velocityX: Float) = if (pages) releasePage(velocityX) else side.release(velocityX)
                override fun onCancel() = if (pages) releasePage(0f) else side.release(0f)
            })
        pagePeek = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(pagePeek, root.indexOfChild(bottomPanel) + 1,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pageDots = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER; visibility = View.GONE }
        root.addView(pageDots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
        internet = InternetMonitor(this) { updateSystemStatus() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ===== Страницы =====
    private var pagePeek: FrameLayout? = null
    private var pageDots: LinearLayout? = null
    private var pageTarget = 0
    /** Экран, с которого началось листание (не пересчитывается на каждом кадре). */
    private var pageFrom = 0
    private var pageOffset = 0f
    private var pageCommit = false
    private val pageHandler = Handler(Looper.getMainLooper())
    private val pageRunner = SpringMotion.Runner(::applyPageOffset) { pageSettled() }
    private val hideDots = Runnable { pageDots?.visibility = View.GONE }
    private val autoHome = Runnable { goHomeIfIdle() }

    /** «+» в панели: плитка на текущую страницу или новая пустая страница. */
    private fun chooseAddition() {
        android.app.AlertDialog.Builder(this).setTitle("Добавить")
            .setItems(arrayOf("Плитку на эту страницу", "Новую страницу")) { _, which ->
                if (which == 0) {
                    TileEditorPreviewSource.attach(viewModel)
                    startActivityForResult(Intent(this, TileSettingsActivity::class.java)
                        .putExtra("container", "grid")
                        .putExtra(TileSettingsActivity.EXTRA_PAGE, panelController.currentPage), REQUEST_TILE_SETTINGS)
                } else {
                    val page = panelController.addPage()
                    panelController.showPage(page)
                    enterEditMode()
                    Toast.makeText(this, "Страница ${page + 1} создана", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun beginPageDrag(target: Int) {
        pageRunner.cancel()
        pageHandler.removeCallbacks(peekFallback)
        pageTarget = target
        pageFrom = panelController.screenIndex()
        pagePeek?.let { panelController.renderPeek(target, it); it.visibility = View.VISIBLE }
        showDots(highlight = panelController.screenIndex())
    }

    /** Соседняя страница выезжает вслед за пальцами; дальше её края движения нет. */
    private fun dragPage(dx: Float) {
        val width = bottomPanel.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        applyPageOffset(if (pageTarget < pageFrom) dx.coerceIn(0f, width) else dx.coerceIn(-width, 0f))
    }

    private fun releasePage(velocityX: Float) {
        val width = bottomPanel.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val toLeftPage = pageTarget < pageFrom
        pageCommit = kotlin.math.abs(pageOffset) > width / 3 ||
            (toLeftPage && velocityX > PAGE_FLING) || (!toLeftPage && velocityX < -PAGE_FLING)
        val end = if (!pageCommit) 0f else if (toLeftPage) width else -width
        pageRunner.start(pageOffset, end, velocityX)
    }

    private fun applyPageOffset(offset: Float) {
        pageOffset = offset
        val width = bottomPanel.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        bottomPanel.translationX = offset
        pagePeek?.translationX = offset + if (pageTarget < pageFrom) -width else width
    }

    private fun pageSettled() {
        val committed = pageCommit
        if (committed) panelController.showScreen(pageTarget)
        pageCommit = false
        pageOffset = 0f
        bottomPanel.translationX = 0f
        val peek = pagePeek
        if (committed && peek != null) {
            // Свежие Modern-карточки подгоняют масштаб и цифры после первого layout (post): пока они
            // не дорисованы, такая же страница-превью остаётся поверх, и рывка не видно.
            peek.translationX = 0f
            revealPageAfterFit(peek)
        } else peek?.let { clearPeek(it) }
        showDots(highlight = panelController.screenIndex())
        scheduleAutoHome()
    }

    private fun clearPeek(peek: FrameLayout) {
        pageHandler.removeCallbacks(peekFallback)
        peek.removeAllViews()
        peek.visibility = View.GONE
        peek.translationX = 0f
    }

    private val peekFallback = Runnable { pagePeek?.let(::clearPeek) }

    /** Превью убирается через два кадра после первой отрисовки новой страницы (запасной таймаут — 400 мс). */
    private fun revealPageAfterFit(peek: FrameLayout) {
        pageHandler.removeCallbacks(peekFallback)
        pageHandler.postDelayed(peekFallback, PEEK_FALLBACK_MS)
        val observer = bottomPanel.viewTreeObserver
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (bottomPanel.viewTreeObserver.isAlive) bottomPanel.viewTreeObserver.removeOnPreDrawListener(this)
                bottomPanel.postOnAnimation { bottomPanel.postOnAnimation { if (!pageRunner.running) clearPeek(peek) } }
                return true
            }
        })
        bottomPanel.invalidate()
    }

    /** Точки страниц видны во время листания и ~2 с после; при одной странице не показываются. */
    private fun showDots(highlight: Int) {
        val dots = pageDots ?: return
        val count = panelController.screenCount()
        pageHandler.removeCallbacks(hideDots)
        if (count < 2) { dots.visibility = View.GONE; return }
        dots.removeAllViews()
        repeat(count) { index ->
            dots.addView(View(this).apply {
                background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.modern_status_dot)?.mutate()
                    ?.apply { setTint(if (index == highlight) ModernUi.color(this@MainActivity, R.color.modern_accent) else 0x66FFFFFF) }
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(5); marginEnd = dp(5) })
        }
        dots.visibility = View.VISIBLE
        pageHandler.postDelayed(hideDots, DOTS_VISIBLE_MS)
    }

    /** Отсчёт автовозврата на главную; любое касание на второстепенной странице начинает его заново. */
    private fun scheduleAutoHome() {
        pageHandler.removeCallbacks(autoHome)
        if (panelController.screenIndex() == 0) return
        val seconds = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getInt(PREF_HOME_SECONDS, 60).coerceAtLeast(10)
        pageHandler.postDelayed(autoHome, seconds * 1000L)
    }

    /** Не возвращаемся посреди редактирования, раскрытого виджета, открытой панели или жеста. */
    private fun goHomeIfIdle() {
        if (panelController.screenIndex() == 0) return
        if (isEditMode || panelController.hasExpansion() || drawer?.isOpen == true || navigation?.active == true ||
            pendingPin?.isActive == true || pageRunner.running) {
            scheduleAutoHome()
            return
        }
        beginPageDrag(0)
        val width = bottomPanel.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        pageCommit = true
        pageRunner.start(0f, width, 0f)
    }

    /** Начатые касания плиток отменяются: кнопка не отправит команду, долгое нажатие не сработает. */
    private fun cancelPanelTouches() {
        val source = touchEvent ?: return
        val cancel = MotionEvent.obtain(source).apply { action = MotionEvent.ACTION_CANCEL }
        try { panelController.dispatchTouch(cancel) ?: super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
    }

    private fun updateSystemStatus() {
        val side = drawer ?: return
        val status = viewModel.uiState.value.connectionStatus
        val (haState, haText) = when (status) {
            ConnectionStatus.Connected -> ModernUi.Status.OK to "HA: подключено"
            is ConnectionStatus.Connecting -> ModernUi.Status.PROGRESS to "HA: подключение…"
            is ConnectionStatus.Error -> ModernUi.Status.ERROR to "HA: ошибка подключения"
            ConnectionStatus.Disconnected -> ModernUi.Status.ERROR to "HA: нет связи"
        }
        ModernUi.setStatus(side.haRow, haState, haText)
        val route = localRoute.takeIf { status == ConnectionStatus.Connected }
        ModernUi.setStatus(side.routeRow, if (route == null) ModernUi.Status.UNKNOWN else ModernUi.Status.OK,
            when (route) { true -> "Адрес: локальный"; false -> "Адрес: удалённый"; null -> "Адрес: —" })
        when (internet?.available) {
            true -> ModernUi.setStatus(side.internetRow, ModernUi.Status.OK, "Интернет: есть")
            false -> ModernUi.setStatus(side.internetRow, ModernUi.Status.ERROR, "Интернет: нет")
            null -> ModernUi.setStatus(side.internetRow, ModernUi.Status.UNKNOWN, "Интернет: неизвестно")
        }
        AutoBackup.statusText(this).let { (state, text) -> ModernUi.setStatus(side.backupRow, state, text) }
        val update = AppUpdater.drawerText(this)
        if (update != null) ModernUi.setStatus(side.updateRow, ModernUi.Status.PROGRESS, update)
        side.updateRow.visibility = if (update == null) View.GONE else View.VISIBLE
        side.updateButton.visibility = side.updateRow.visibility
        side.refreshAdmin()
        side.refreshPage()
    }

    /**
     * События связи вместо постоянной строки: потеря показывается, если связи нет дольше короткого
     * переподключения, и висит до восстановления; затем коротко «Связь восстановлена».
     */
    private fun onConnectionStatus(status: ConnectionStatus) {
        if (status == ConnectionStatus.Connected) {
            pillHandler.removeCallbacks(showLoss)
            if (lossShown) {
                lossShown = false
                showPill(ModernUi.Status.OK, "Связь с Home Assistant восстановлена")
                pillHandler.postDelayed(hidePill, PILL_RECOVERED_MS)
            }
            everConnected = true
        } else if (!lossShown) {
            pillHandler.removeCallbacks(showLoss)
            pillHandler.postDelayed(showLoss, if (everConnected) PILL_LOSS_DELAY_MS else PILL_START_DELAY_MS)
        }
        updateSystemStatus()
    }

    /**
     * Ошибка операции (команда, прогноз) при живом соединении: янтарная плашка на [PILL_OPERATION_ERROR_MS].
     * Потерю связи не перекрывает. После пересоздания экрана показывается только остаток срока.
     */
    private fun onOperationError(error: OperationError) {
        if (lossShown) return
        val remaining = PILL_OPERATION_ERROR_MS - (SystemClock.elapsedRealtime() - error.atMs)
        if (remaining <= 0) return
        showPill(ModernUi.Status.WARNING, error.message)
        pillHandler.removeCallbacks(hideOperationError)
        pillHandler.postDelayed(hideOperationError, remaining)
    }

    private fun showPill(state: ModernUi.Status, text: String) {
        val pill = findViewById<TextView?>(R.id.statusPill) ?: return
        pillHandler.removeCallbacks(hidePill)
        pill.text = text
        val dot = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.modern_status_dot)?.mutate()
        dot?.setTint(ModernUi.color(this, state.color))
        pill.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
        pill.visibility = View.VISIBLE
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
        TileEditorPreviewSource.attach(viewModel)
        openWithPinCheck {
            // Тип читаем после PIN: плитка могла измениться, пока открыт диалог.
            val type = if (::tileManager.isInitialized) tileManager.loadTiles().find { it.id == id }?.type else null
            val screen = when (type) {
                BatteryModernConfig.TYPE -> BatteryModernEditorActivity::class.java
                PowerModernConfig.TYPE -> PowerModernEditorActivity::class.java
                InverterModernConfig.TYPE -> InverterModernEditorActivity::class.java
                DoorbellConfig.TYPE -> DoorbellEditorActivity::class.java
                HomeClimateConfig.TYPE -> HomeClimateEditorActivity::class.java
                else -> TileSettingsActivity::class.java
            }
            startActivityForResult(Intent(this, screen).putExtra("tile_id", id), REQUEST_TILE_SETTINGS)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::tileManager.isInitialized) panelController.setInteractive(hasFocus)
    }

    private fun refreshBottomPanel() = panelController.refreshBottomPanel()

    private fun readPanelPreferences() {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        // Выключенный датчик движения: экран всегда с рабочей яркостью, выбранная сущность сохраняется.
        motionSensorId = prefs.getString("motion_sensor_id", null)?.takeIf { prefs.getBoolean(PREF_MOTION_ENABLED, true) }
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
        // Пустой список из-за повреждения — не «первый запуск»: стартовые плитки не пишутся поверх данных,
        // приветствие нового пользователя не показывается поверх сообщения о восстановлении.
        if (tileManager.loadIssue() != null) {
            prefs.edit { putBoolean(PREF_WELCOME_SHOWN, true) }
            return
        }
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

    /**
     * Сохранённые плитки повреждены: исправные показаны, исходные данные уже сохранены отдельным файлом
     * (TileManager). Предлагаем восстановление из копии через PIN; панель продолжает работать.
     */
    private fun showTileIssue() {
        val issue = tileManager.loadIssue() ?: return
        val what = if (issue.unreadable) "Сохранённые плитки не удалось прочитать, поэтому панель пуста."
            else "Не удалось прочитать плиток: ${issue.skipped} из ${issue.total}. Остальные работают."
        android.app.AlertDialog.Builder(this)
            .setTitle("Плитки повреждены")
            .setMessage("$what\n\nИсходные данные сохранены и не будут потеряны. Восстановите панель из автокопии " +
                "или файла: Настройки → Устройство → «Восстановить из копии».")
            .setPositiveButton("Восстановить…") { _, _ ->
                openWithPinCheck {
                    startActivity(Intent(this, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_DEVICE))
                }
            }
            .setNegativeButton("Позже", null)
            .show()
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
        val allowInsecure = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
            .getBoolean(HomeAssistantAddress.PREF_ALLOW_INSECURE_REMOTE, false)
        val configuration = listOf(local, remote, token, allowInsecure.toString())
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
            allowInsecureRemote = allowInsecure,
            onStateChangedCallback = { entity, state, _ ->
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) panelController.renderEntityState(entity, state)
            },
            onStatesSnapshotCallback = { entities ->
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) panelController.renderInitialStates(entities)
            },
            onCommandStateChangedCallback = { entity ->
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) panelController.renderCommandState(entity)
            },
            onForecastChangedCallback = { entity, _ ->
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) panelController.renderForecastState(entity)
            },
            onConnectedCallback = {
                if (haGeneration == generation && tokenRevision == HaTokenAccess.revision && !isDestroyed) {
                    val active = viewModel.getWebSocket()?.getHost().orEmpty()
                    localRoute = local.isNotEmpty() && active == local
                    val network = if (localRoute == true) "Локальная сеть" else "Интернет"
                    viewModel.publishStatus("$network ($active)")
                    // Маршрут меняется и без смены статуса связи (переход на локальный без разрыва).
                    updateSystemStatus()
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
        viewModel.setPowerSources(PowerModernConfig.graphSources(tileManager.getAllTiles()))
        // Передаём весь актуальный набор, включая пустой при выборе Яндекса.
        viewModel.syncForecastSubscriptions(plan.forecastIds)
        viewModel.syncHourlyForecastSubscriptions(plan.hourlyForecastIds)
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
        // Схемы потоков не двигаются при яркости 1%: экономия ЦП на стене 24/7.
        PanelMotion.dimmed = true
        val params = window.attributes
        params.screenBrightness = 0.01f // Минимальная яркость
        window.attributes = params
    }

    private fun wakeScreen() {
        isDimmed = false
        PanelMotion.dimmed = false
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE // Системное значение
        window.attributes = params
        resetIdleTimer()
    }
}
