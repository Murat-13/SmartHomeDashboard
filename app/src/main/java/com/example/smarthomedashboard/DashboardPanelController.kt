package com.example.smarthomedashboard

import android.content.ContextWrapper

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.json.JSONArray
import androidx.core.content.edit

/** Дерево панели и его обработчики. Живёт ровно столько же, сколько экран. */
internal class DashboardPanelController(
    private val activity: Activity,
    private val viewModel: MainViewModel,
    private val host: Host
) : ContextWrapper(activity) {
    interface Host : DashboardContentController.Sources {
        val dim: View
        val hasPanel: Boolean
        val hasDim: Boolean
        val motionEntity: String?
        fun readPreferences()
        fun updateHint()
        fun wake()
        fun editTile(id: String)
        fun requestEdit()
        fun exitEdit()
        fun settings()
        fun addTile()
        /** Админ-действие режима редактирования: true — можно сохранить сейчас; [retry] — если позже выяснится, что PIN отключён. */
        fun adminAction(retry: () -> Unit): Boolean
        /** Сменилась текущая страница (точки, панель). */
        fun pageChanged() {}
    }
    private val bottomPanel get() = host.panel
    private val dimOverlay get() = host.dim
    private val tileManager get() = host.tiles
    private val motionSensorId get() = host.motionEntity
    // Размер окна «как до Android 15» (без полосы навигации): раскладка плиток прежняя при targetSdk 35.
    private val windowSize by lazy { SystemBarInsets.legacyWindowSize(activity) }
    private val screenW get() = windowSize.first
    private val screenH get() = windowSize.second
    private fun <T : View> findViewById(id: Int): T = activity.findViewById(id)

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var collapseChildrenRunnable: Runnable? = null

    // Только представления данных владельца: отдельных копий в Activity нет.
    private val groupStates get() = viewModel.devices.groupStates
    private val singleStates get() = viewModel.devices.singleStates
    private val isEditMode get() = viewModel.uiState.value.isEditMode

    private val expandedChildButtons = mutableListOf<Button>()
    private val closingChildButtons = mutableSetOf<Button>()
    private var expandedGroupId: String? = null
    private var expandedSourceButton: Button? = null
    private var expandedTile: TileEntity? = null

    // Раскрытая Modern-группа: собственный overlay и таймер, те же команды ViewModel.
    internal val modernGroup by lazy {
        ModernGroupPanel(activity, { activity.findViewById(android.R.id.content) }, object : ModernGroupPanel.Host {
            override fun childModel(tileId: String, entityId: String, name: String) = modernChildModel(tileId, entityId, name)
            override fun parentModel(tileId: String) =
                tileManager.getAllTiles().find { it.id == tileId && it.type == "group" }?.let(::modernModel)
            override fun childTapped(tileId: String, entityId: String, view: View) {
                if (isEditMode) return
                ModernPress.haptic(view)
                viewModel.commands.toggleMember(tileId, entityId)
                updateGroupButtonAppearance(tileId)
            }
            override fun closed(tileId: String) {
                if (viewModel.openModernGroup == tileId) viewModel.openModernGroup = null
            }
        })
    }

    private val sensorExpansion = SensorExpansionSession(handler)
    private val expandedSensorView: View? get() = sensorExpansion.current
    private val contentController by lazy {
        DashboardContentController(activity, viewModel.devices, host, { expandedSensorView }, ::resetSensorCollapseTimer,
            ::renderModernBattery)
    }

    // «АКБ Modern» раскрывается отдельной View поверх панели; обычные датчики — прежним способом.
    internal val modernExpansion by lazy {
        BatteryModernExpansion(handler, { activity.findViewById(android.R.id.content) },
            { if (host.hasPanel) bottomPanel else null }, BatteryModernExpansion.blurAvailable(activity))
    }
    private val modernWindowInput by lazy {
        SensorWindowTouchHandler(
            { modernExpansion.card?.takeIf { modernExpansion.isOpen } },
            { view -> view.parent != null && tileManager.getAllTiles().any { it.id == modernExpansion.tileId } },
            modernExpansion::touch, ::clearModernExpansion, ::collapseModernBattery,
            android.view.ViewConfiguration.get(activity).scaledTouchSlop,
            { card, x, y -> (card as? ModernInteractiveArea)?.interactiveAt(x, y) == true }
        )
    }
    private val weatherForecastScroll get() = contentController.weatherForecastScroll
    private var isResizing = false
    private val resizeHandlers = mutableListOf<SensorResizeHandler>()
    private val sensorWindowInput by lazy {
        SensorWindowTouchHandler(
            { expandedSensorView },
            { source -> source.parent === bottomPanel && tileManager.getAllTiles().any { it.id == source.tag } },
            ::resetSensorCollapseTimer, ::clearSensorExpansion, ::collapseSensor,
            android.view.ViewConfiguration.get(activity).scaledTouchSlop
        )
    }

    private val editDragHandlers = mutableMapOf<View, TileEditDragHandler>()

    private var dependencyIndex = PanelDependencyIndex(emptyList())
    private val renderedViews = mutableMapOf<String, View>()
    private var panelEntityTiles: List<TileEntity>? = null
    private var panelEntityMotionSensor: String? = null
    private var panelEntityIds: Set<String> = emptySet()
    companion object {
        /** Вход в редактирование долгим нажатием на пустое место (было 3 с). */
        internal const val EDIT_LONG_PRESS_MS = 1500L
        internal const val PREF_PAGE_COUNT = "page_count"
        /** Подтверждение смены приоритета инвертора закрывается само без команды. */
        internal const val PRIORITY_DIALOG_MS = 30_000L
        /** Пауза после касания Modern-плитки до сборки раскрытой карточки (0.9.16): листание успевает отменить. */
        internal const val PREPARE_DELAY_MS = 40L
        /** Заранее собранная карточка годна не дольше (долгое удержание — карточка удаляется). */
        internal const val PREPARED_MAX_AGE_MS = 1_500L
    }

    fun dispatchTouch(event: MotionEvent): Boolean? =
        modernWindowInput.dispatch(event) ?: sensorWindowInput.dispatch(event)

    fun backgroundTouch(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val hit = findViewAt(event.x, event.y)
            if (hit == null || hit == dimOverlay) {
                if (isEditMode) host.exitEdit()
                else {
                    longPressRunnable = Runnable { host.requestEdit() }
                    handler.postDelayed(longPressRunnable!!, EDIT_LONG_PRESS_MS)
                }
            }
        } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            longPressRunnable?.let { handler.removeCallbacks(it) }
        }
    }

    /** Под точкой есть плитка (а не пустое место или затемнение). */
    fun hasTileAt(x: Float, y: Float) = findViewAt(x, y).let { it != null && it != dimOverlay }

    /** Текущая страница (0 — главная); хранится во ViewModel и переживает поворот. */
    var currentPage: Int
        get() = viewModel.currentPage
        private set(value) { viewModel.currentPage = value }

    /** Число страниц: явно созданные (в том числе пустые) и все, где есть плитки; минимум одна. */
    fun pageCount(): Int = maxOf(1, pagePrefs().getInt(PREF_PAGE_COUNT, 1),
        (tileManager.getAllTiles().maxOfOrNull { it.page } ?: 0) + 1)

    /** Новая пустая страница справа; возвращает её номер. */
    fun addPage(): Int {
        val index = pageCount()
        pagePrefs().edit { putInt(PREF_PAGE_COUNT, index + 1) }
        return index
    }

    private fun pagePrefs() = activity.getSharedPreferences("dashboard_prefs", android.content.Context.MODE_PRIVATE)

    fun showPage(page: Int) {
        currentPage = page.coerceIn(0, pageCount() - 1)
        refreshBottomPanel()
        host.pageChanged()
    }

    /**
     * Превью соседней страницы на время листания: те же плитки и уже известные данные, но без регистрации
     * в панели — после листания страница строится заново обычным путём.
     */
    fun renderPeek(page: Int, container: FrameLayout) {
        container.removeAllViews()
        tileManager.getTilesByContainer("grid").filter { it.page == page }.forEach { tile ->
            val card = createSensorView(tile)
            placeTile(card, tile.x.toFloat(), tile.y.toFloat(), card.layoutParams.width, card.layoutParams.height)
            container.addView(card)
        }
        tileManager.getTilesByContainer("bottom_panel").filter { it.page == page }.forEach { tile ->
            val button = createTileButton(tile)
            val size = getButtonSize(tile)
            button.layoutParams = FrameLayout.LayoutParams(size, size)
            placeTile(button, tile.x.toFloat(), tile.y.toFloat(), size, size)
            container.addView(button)
        }
        contentController.updateSensorDisplay(container)
        contentController.updateClockViews(container)
    }

    /** Перенос плитки на соседнюю страницу (удержание у края в режиме редактирования). */
    private fun moveTileToPage(id: String, direction: Int) {
        val target = currentPage + direction
        if (target < 0 || target >= pageCount()) return
        if (!host.adminAction { if (writePage(id, target, direction)) showPage(target) }) return
        if (writePage(id, target, direction)) showPage(target)
    }

    /** Страница и x — одной записью; на новой странице плитка встаёт у края, с которого пришла. */
    private fun writePage(id: String, target: Int, direction: Int): Boolean {
        val tiles = tileManager.loadTiles().toMutableList()
        val index = tiles.indexOfFirst { it.id == id }
        if (index < 0) return false
        val tile = tiles[index]
        val width = TilePlacement.displaySize(tile.container, tile.config, screenW, screenH).width
        val x = if (direction > 0) 20 else screenW - width - 20
        tiles[index] = tile.copy(page = target, x = x)
        tileManager.saveTiles(tiles)
        return true
    }

    /** Раскрыт виджет или идёт изменение размера: навигационные жесты в это время не работают. */
    fun hasExpansion() = isResizing || expandedGroupId != null || expandedSensorView != null ||
        modernExpansion.isOpen || modernGroup.isOpen

    private fun findViewAt(x: Float, y: Float): View? {
        for (i in bottomPanel.childCount - 1 downTo 0) {
            val child = bottomPanel.getChildAt(i)
            if (child.isVisible &&
                x >= child.x && x <= child.x + child.width &&
                y >= child.y && y <= child.y + child.height
            ) return child
        }
        return null
    }

    fun refreshBottomPanel() {
        // Форматирование нового дерева должно учитывать уже изменённые настройки.
        host.readPreferences()
        // Открытая Modern-панель переживает перестроение дерева и пересоздание экрана (поворот).
        val keepModernGroup = (if (modernGroup.isOpen) modernGroup.tileId else viewModel.openModernGroup)
            ?.takeIf { !isEditMode }
            ?.let { id -> tileManager.getAllTiles().find { it.id == id && it.type == "group" && TileButtonStyle.isModern(it.type, it.config) } }
        clearPanel(keepModernGroup = keepModernGroup != null && modernGroup.isOpen)
        bottomPanel.removeAllViews()

        val page = currentPage
        val tiles = tileManager.getTilesByContainer("bottom_panel").filter { it.page == page }
        // Состояния удалённых плиток «Электропитание Modern» не накапливаются.
        viewModel.retainPowerTrackers(tileManager.getAllTiles().filter { it.type == PowerModernConfig.TYPE }.mapTo(HashSet()) { it.id })
        viewModel.retainInverterTrackers(tileManager.getAllTiles().filter { it.type == InverterModernConfig.TYPE }.mapTo(HashSet()) { it.id })

        // Сенсоры
        tileManager.getTilesByContainer("grid").filter { it.page == page }.forEach { tile ->
            val card = createSensorView(tile)
            placeTile(card, tile.x.toFloat(), tile.y.toFloat(), card.layoutParams.width, card.layoutParams.height)
            bottomPanel.addView(card)
            renderedViews[tile.id] = card
        }

        // Кнопки
        tiles.forEach { tile ->
            val btn = createTileButton(tile)
            val sz = getButtonSize(tile)
            btn.layoutParams = FrameLayout.LayoutParams(sz, sz)
            placeTile(btn, tile.x.toFloat(), tile.y.toFloat(), sz, sz)
            bottomPanel.addView(btn)
            renderedViews[tile.id] = btn
        }

        // Настройки и «+» теперь в боковой панели (SideDrawer), поверх плиток их больше нет.

        if (keepModernGroup == null) {
            viewModel.openModernGroup = null
        } else {
            (renderedViews[keepModernGroup.id] as? ModernTileView)?.let { source ->
                val entries = modernEntries(keepModernGroup)
                if (modernGroup.isOpen) modernGroup.reattach(source, keepModernGroup.title, entries)
                // После пересоздания Activity — сразу открытая панель, без повторного вступления.
                else bottomPanel.post {
                    if (source.parent === bottomPanel && !isEditMode && viewModel.openModernGroup == keepModernGroup.id) {
                        modernGroup.open(keepModernGroup.id, keepModernGroup.title, entries, source, animate = false)
                    }
                }
            } ?: modernGroup.clear()
        }

        // Используем уже полученные значения, не ожидая следующего события или тика часов.
        contentController.updateSensorDisplay()
        contentController.updateClockViews()
        host.updateHint()
    }

    private fun getButtonSize(tile: TileEntity): Int =
        TilePlacement.displaySize("bottom_panel", tile.config, screenW, screenH).width

    private fun placeTile(view: View, x: Float, y: Float, width: Int = view.width, height: Int = view.height) {
        val (safeX, safeY) = TilePlacement.position(x, y, width, height, screenW, screenH)
        view.x = safeX
        view.y = safeY
    }

    private fun createSensorView(tile: TileEntity): View {
        val (savedWidth, savedHeight) = TilePlacement.displaySize("grid", tile.config, screenW, screenH)
        if (isModern(tile.type)) return createModernBatteryView(tile, savedWidth, savedHeight)

        val card = SensorCardFactory.createBase(activity, tile.id, tile.type, savedWidth, savedHeight)
        if (tile.type == "battery") {
            // Устанавливаем заполнение из уже известных состояний, не дожидаясь нового события HA.
            contentController.updateBatteryWidget(tile, card)
        }
        val views = SensorCardFactory.addContent(
            activity, card, tile.type, tile.title, tile.fontSize, savedHeight, isEditMode
        )
        val resizeHandle = views.resizeHandle

        // Перетаскивание и раскрытие
        setupSensorTouch(card, tile)

        resizeHandlers += SensorResizeHandler(
            card, resizeHandle, tile.type, screenW, screenH,
            onResizingChanged = { isResizing = it },
            saveSize = { width, height -> saveSensorSize(tile.id, width, height) }
        )

        return card
    }

    private fun saveSensorSize(tileId: String, width: Int, height: Int): Boolean {
        // Без действующего доступа размер откатывается (false); при отключённом PIN применяется после проверки.
        if (!host.adminAction { if (writeSensorSize(tileId, width, height)) refreshBottomPanel() }) return false
        return writeSensorSize(tileId, width, height)
    }

    private fun writeSensorSize(tileId: String, width: Int, height: Int): Boolean {
        // Читаем актуальную плитку на UP, не сохраняя старую копию из начала жеста.
        val tiles = tileManager.loadTiles().toMutableList()
        val idx = tiles.indexOfFirst { it.id == tileId }
        if (idx < 0) return false
        val currentTile = tiles[idx]
        val config = try {
            JSONObject(currentTile.config)
        } catch (_: org.json.JSONException) {
            Toast.makeText(activity, "Не удалось сохранить размер: повреждены настройки плитки", Toast.LENGTH_SHORT).show()
            return false
        }
        config.put("button_size", width)
        config.put("widget_height", height)
        tiles[idx] = currentTile.copy(config = config.toString())
        tileManager.saveTiles(tiles)
        return true
    }

    private fun expandSensor(tile: TileEntity, source: View) {
        val original = SensorExpansionSession.Bounds.of(source)

        val idsToShow = contentController.getExpandedSensorIds(tile)

        // Обновляем содержимое виджета (табличный вид)
        val container = source.findViewWithTag<LinearLayout>("sensor_text_container")
        if (container != null) {
            if (tile.type == "weather") {
                contentController.buildWeatherDetailedContent(tile, container)
            } else {
                contentController.buildSensorContent(tile, idsToShow, true, container)
            }
        }

        // Ограничиваем максимальные размеры (90% экрана)
        val maxW = (screenW * 0.9f).toInt()
        val maxH = (screenH * 0.8f).toInt()

        // Измеряем контент с ограничением
        container.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )

        val neededW = container.measuredWidth + 60
        val neededH = container.measuredHeight + 60

        // Вычисляем целевую позицию (стараемся центрировать относительно исходной точки)
        var targetX = original.x - (neededW - original.width) / 2f
        var targetY = original.y - (neededH - original.height) / 2f

        // Сохраняем прежние отступы от краёв экрана в px.
        if (targetX < 10f) targetX = 10f
        if (targetX + neededW > screenW - 10) targetX = (screenW - neededW - 10).toFloat()
        if (targetY < 10f) targetY = 10f
        if (targetY + neededH > screenH - 70) targetY = (screenH - neededH - 70).toFloat()

        sensorExpansion.expand(source,
            SensorExpansionSession.Bounds(neededW, neededH, targetX, targetY), ::collapseSensor)
    }

    private fun resetSensorCollapseTimer() = sensorExpansion.touch()

    private fun collapseSensor() {
        val source = expandedSensorView ?: return
        sensorWindowInput.clear()
        weatherForecastScroll.clear()
        val tile = tileManager.getAllTiles().find { it.id == source.tag as? String }
        if (tile == null) {
            clearSensorExpansion()
            return
        }
        val target = checkNotNull(sensorExpansion.original)
        val idsToShow = contentController.getCollapsedSensorIds(tile)

        val container = source.findViewWithTag<LinearLayout>("sensor_text_container")
        if (container != null) {
            if (tile.type == "weather") {
                // В начале сворачивания высота View ещё развёрнутая; шрифт нужен для исходной высоты.
                SensorCardFactory.buildCollapsedWeatherContent(activity, container, target.height)
            } else {
                contentController.buildSensorContent(tile, idsToShow, false, container)
            }
        }

        sensorExpansion.collapse { view ->
            // Только живое дерево с актуальной плиткой снова получает ввод.
            if (view.parent === bottomPanel) {
                tileManager.getAllTiles().find { it.id == tile.id }?.let {
                    setupSensorTouch(view, it)
                }
            }
        }
        if (tile.type == "weather") {
            // Только после сброса режима раскрытия, иначе updateWeatherViews снова построит прогноз.
            contentController.updateWeatherViews()
        }
    }

    private fun setupSensorTouch(card: View, tile: TileEntity) {
        val editDrag = bindEditDrag(card, tile.id)
        var startX = 0f
        var startY = 0f
        var hasMoved = false
        var gestureActive = false

        card.setOnTouchListener { view, event ->
            if (isResizing) return@setOnTouchListener true
            if (isEditMode) return@setOnTouchListener editDrag.onTouch(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    gestureActive = true
                    hasMoved = false
                    startX = event.rawX
                    startY = event.rawY
                    if (isModern(tile.type)) startPreparing(tile.id, view)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gestureActive) return@setOnTouchListener true
                    if (kotlin.math.abs(event.rawX - startX) > 10 ||
                        kotlin.math.abs(event.rawY - startY) > 10) {
                        if (!hasMoved) cancelPreparing(view)
                        hasMoved = true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!gestureActive) return@setOnTouchListener true
                    gestureActive = false
                    if (hasMoved) cancelPreparing(view)
                    view.performClick()
                    if (!hasMoved && isModern(tile.type)) {
                        // Актуальная плитка: настройки могли измениться после построения дерева.
                        if (expandedSensorView != null) collapseSensor()
                        else tileManager.getAllTiles().find { it.id == tile.id }?.let { expandModernBattery(it, view) }
                    } else if (!hasMoved && tile.type != "clock") {
                        if (expandedSensorView != null && expandedSensorView != view) collapseSensor()
                        else expandSensor(tile, view)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    gestureActive = false
                    hasMoved = false
                    cancelPreparing(view)
                    true
                }
                else -> false
            }
        }
    }

    private fun createTileButton(tile: TileEntity): View {
        val sz = getButtonSize(tile)
        if (TileButtonStyle.isModern(tile.type, tile.config)) return createModernTile(tile, sz)
        return TileButtonFactory.create(activity, tile.title, tile.id, sz, ButtonCommandAppearance.OFF).apply {
            renderTileButton(this, tile)
            if (tile.type == "group") setupGroupTouch(tile, this)
            else setupButtonTouch(tile, this)
        }
    }

    private fun createModernTile(tile: TileEntity, size: Int): View {
        val kind = if (tile.type == "group") ModernTileView.Kind.GROUP else ModernTileView.Kind.SINGLE
        return ModernTileView(activity, kind).apply {
            tag = tile.id
            layoutParams = FrameLayout.LayoutParams(size, size)
            editOutline = isEditMode
            bind(modernModel(tile), animate = false)
            if (tile.type == "group") setupGroupTouch(tile, this)
            else setupButtonTouch(tile, this)
        }
    }

    /** Устройства группы в порядке конфигурации; повреждённая конфигурация — пустой список. */
    // Настройки плиток разбираются один раз на версию, а не на каждое событие HA.
    private val iconConfigs = ParsedTileConfig { ModernIcon.fromConfig(it) }
    private val buttonEntities = ParsedTileConfig { TileButtonConfig.entityId(it) }
    private val groupEntries = ParsedTileConfig { config ->
        val group = (TileButtonConfig.group(config) as? ButtonConfigResult.Valid)?.value
        group?.entityIds?.mapIndexed { i, id -> ModernGroupPanel.Entry(id, group.childNames[i]) }.orEmpty()
    }
    private val livePower = ParsedTileConfig { config -> LivePowerConfig.read(config) }
    private val childPower = ParsedTileConfig { config ->
        val json = runCatching { JSONObject(config).optJSONObject("child_power") }.getOrNull()
        json?.keys()?.asSequence()?.associateWith { LivePowerConfig.read(json.optJSONObject(it)) }.orEmpty()
    }
    private val batteryConfigs = ParsedTileConfig { BatteryModernConfig.from(it) }
    private val powerConfigs = ParsedTileConfig { PowerModernConfig.from(it) }
    private val climateConfigs = ParsedTileConfig { HomeClimateConfig.from(it) }
    private val inverterConfigs = ParsedTileConfig { InverterModernConfig.from(it) }
    private val parsedConfigs = listOf(iconConfigs, buttonEntities, groupEntries, livePower, childPower,
        batteryConfigs, powerConfigs, climateConfigs, inverterConfigs)

    private fun modernEntries(tile: TileEntity): List<ModernGroupPanel.Entry> = groupEntries.of(tile)

    private fun modernIcon(entityId: String, hint: String? = null) =
        ModernIcon.forEntity(entityId, viewModel.devices.entityAttributes[entityId], hint)

    private fun recentFailure(entityId: String) =
        viewModel.commands.recentFailure(entityId, ModernTileView.ERROR_MS)

    /**
     * Только подтверждённые факты HA: Modern не рисует предполагаемый ON/OFF. Ожидание и ошибка —
     * отдельные признаки по entity ID, цвет от них не меняется.
     */
    internal fun modernModel(tile: TileEntity): ModernTileView.Model {
        // Выбор пользователя из редактора; без него — прежний автоподбор.
        val chosen = iconConfigs.of(tile)
        if (tile.type == "group") {
            val ids = modernEntries(tile).map { it.entityId }
            val states = ids.map { viewModel.devices.confirmedState(it) }
            val state = when {
                ConfirmedState.ON in states -> ConfirmedState.ON
                ConfirmedState.OFF in states || ids.isEmpty() -> ConfirmedState.OFF
                else -> ConfirmedState.UNAVAILABLE
            }
            // Один общий spinner — только для общей команды этой группы.
            val pending = ids.mapNotNull { viewModel.commands.pendingFor(it)?.takeIf { p -> p.groupId == tile.id }?.startedAtMs }.minOrNull()
            val items = ids.mapIndexed { i, id -> ModernTileView.Item(chosen ?: modernIcon(id), states[i], recentFailure(id)) }
            return ModernTileView.Model(tile.title, ModernIcon.EMPTY_GRID, state, pending, null, items, groupIcon = chosen)
        }
        val id = (buttonEntities.of(tile) as? ButtonConfigResult.Valid)?.value
            ?: return ModernTileView.Model(tile.title, chosen ?: ModernIcon.POWER, ConfirmedState.OFF)
        // Gson оставляет null в поле, которого нет в старой записи, даже если тип не nullable.
        val hint = (tile.icon as String?)?.takeIf { it.isNotBlank() }
        return ModernTileView.Model(
            tile.title, chosen ?: modernIcon(id, hint), viewModel.devices.confirmedState(id),
            viewModel.commands.pendingFor(id)?.startedAtMs, recentFailure(id),
            powerText = powerText(livePower.of(tile))
        )
    }

    /** Дочерняя кнопка панели: свой spinner только при индивидуальной команде. */
    private fun modernChildModel(tileId: String, entityId: String, name: String): ModernTileView.Model {
        val tile = tileManager.getAllTiles().find { it.id == tileId }
        val icon = tile?.takeIf { it.type == "group" }?.let { iconConfigs.of(it) }
        return ModernTileView.Model(
            name, icon ?: modernIcon(entityId), viewModel.devices.confirmedState(entityId),
            viewModel.commands.pendingFor(entityId)?.takeIf { it.groupId == null }?.startedAtMs, recentFailure(entityId),
            powerText = powerText(tile?.let { childPower.of(it)[entityId] } ?: LivePowerConfig())
        )
    }

    private fun powerText(config: LivePowerConfig) = config.text(viewModel.devices.singleStates,
        viewModel.devices.entityAttributes, viewModel.devices.staleEntities)

    private fun renderTileButton(button: Button, tile: TileEntity) {
        val ids = if (tile.type == "group") {
            val result = TileButtonConfig.group(tile.config)
            if (result is ButtonConfigResult.Valid) {
                viewModel.groupIsOn(tile.id, result.value.entityIds)
                result.value.entityIds
            } else emptyList()
        } else listOfNotNull(buttonEntityId(tile))
        val confirmed = when {
            ids.isEmpty() -> ConfirmedState.OFF // Пустая/повреждённая конфигурация не является устройством.
            ids.any { viewModel.devices.confirmedState(it) == ConfirmedState.ON } -> ConfirmedState.ON
            ids.all { viewModel.devices.confirmedState(it) == ConfirmedState.OFF } -> ConfirmedState.OFF
            else -> ConfirmedState.UNAVAILABLE
        }
        val pending = ids.firstNotNullOfOrNull { viewModel.commands.pendingFor(it) }
        ButtonCommandAppearance.render(button, tile.title, confirmed, pending, isGroup = tile.type == "group")
    }

    private fun buttonEntityId(tile: TileEntity, reportError: Boolean = false): String? {
        return when (val result = TileButtonConfig.entityId(tile.config)) {
            is ButtonConfigResult.Valid -> result.value.also {
                if (it == null && reportError) {
                    Toast.makeText(activity, "У кнопки не выбрано устройство.", Toast.LENGTH_SHORT).show()
                }
            }
            ButtonConfigResult.Invalid -> {
                if (reportError) showButtonConfigError()
                null
            }
        }
    }

    private fun groupForAction(tile: TileEntity): TileButtonConfig.Group? {
        return when (val result = TileButtonConfig.group(tile.config)) {
            is ButtonConfigResult.Valid -> {
                if (result.value.entityIds.isEmpty()) {
                    Toast.makeText(activity, "В группе не выбраны устройства.", Toast.LENGTH_SHORT).show()
                    null
                } else result.value
            }
            ButtonConfigResult.Invalid -> {
                showButtonConfigError()
                null
            }
        }
    }

    private fun showButtonConfigError() {
        Toast.makeText(activity, "Не удалось прочитать конфигурацию плитки. Откройте настройки.",
            Toast.LENGTH_SHORT).show()
    }

    private fun setupGroupTouch(tile: TileEntity, button: View) {
        val editDrag = bindEditDrag(button, tile.id)
        val modern = button is ModernTileView
        var pressRunnable: Runnable? = null
        var startX = 0f
        var startY = 0f
        var hasMoved = false
        var gestureActive = false
        var holdFired = false
        // Системная длительность долгого нажатия (меняется в специальных возможностях Android).
        val holdMs = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        fun hold(view: View) {
            holdFired = true
            if (view is ModernTileView) {
                if (modernGroup.isOpen && modernGroup.tileId == tile.id) modernGroup.close()
                else expandModernGroup(tile, view)
            } else if (expandedGroupId == tile.id) collapseChildButtons()
            else expandChildButtons(tile, view as Button)
        }

        button.setOnTouchListener { view, event ->
            if (isEditMode) return@setOnTouchListener editDrag.onTouch(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pressRunnable?.let { handler.removeCallbacks(it) }
                    gestureActive = true
                    hasMoved = false
                    holdFired = false
                    startX = event.rawX
                    startY = event.rawY
                    if (modern) ModernPress.handle(view, event, enabled = true)
                    pressRunnable = Runnable {
                        if (gestureActive) hold(view)
                    }.also { handler.postDelayed(it, holdMs) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gestureActive) return@setOnTouchListener true
                    if (kotlin.math.abs(event.rawX - startX) > 10 ||
                        kotlin.math.abs(event.rawY - startY) > 10) {
                        hasMoved = true
                        pressRunnable?.let { handler.removeCallbacks(it) }
                        if (modern) ModernPress.release(view)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!gestureActive) return@setOnTouchListener true
                    gestureActive = false
                    if (modern) ModernPress.handle(view, event, enabled = true)
                    view.performClick()
                    pressRunnable?.let { handler.removeCallbacks(it) }
                    pressRunnable = null
                    // Удержание дольше порога — всегда раскрытие, даже если главный поток не успел выполнить таймер;
                    // иначе долгое нажатие переключало бы устройства группы.
                    if (!hasMoved && !holdFired && event.eventTime - event.downTime >= holdMs) hold(view)
                    val expanded = if (modern) modernGroup.tileId == tile.id else expandedGroupId == tile.id
                    if (!hasMoved && !expanded && !holdFired) {
                        if (modern) ModernPress.haptic(view)
                        toggleGroup(tile.id)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (modern) ModernPress.handle(view, event, enabled = true)
                    pressRunnable?.let { handler.removeCallbacks(it) }
                    pressRunnable = null
                    gestureActive = false
                    hasMoved = false
                    true
                }
                else -> false
            }
        }
    }

    // Обработчик касаний возвращает false: клик идёт штатно через View.onTouchEvent → performClick.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupButtonTouch(
        tile: TileEntity,
        button: View
    ) {
        val modern = button is ModernTileView
        button.setOnClickListener {
            if (!isEditMode) {
                val eid = buttonEntityId(tile, reportError = true)
                if (eid != null) {
                    if (modern) ModernPress.haptic(button)
                    viewModel.commands.toggleSingle(eid)
                }
            }
        }

        val editDrag = bindEditDrag(button, tile.id)
        button.setOnTouchListener { view, event ->
            if (isEditMode) editDrag.onTouch(event)
            else {
                if (modern) ModernPress.handle(view, event, enabled = true)
                false
            }
        }
    }

    private fun expandModernGroup(tile: TileEntity, source: ModernTileView) {
        // Тот же разбор конфигурации, что и у Classic; пустая группа открывает панель с подписью.
        if (TileButtonConfig.group(tile.config) is ButtonConfigResult.Invalid) { showButtonConfigError(); return }
        collapseChildButtons()
        collapseSensor()
        collapseModernBattery()
        ModernPress.release(source)
        ModernPress.haptic(source)
        viewModel.openModernGroup = tile.id
        modernGroup.open(tile.id, tile.title, modernEntries(tile), source, animate = true)
    }

    private fun bindEditDrag(view: View, tileId: String): TileEditDragHandler {
        editDragHandlers.remove(view)?.dispose()
        return TileEditDragHandler(view, handler, screenW, screenH,
            onTap = { host.editTile(tileId) },
            onDrop = { x, y -> savePosition(tileId, x, y) },
            onEdge = { direction -> handler.post { moveTileToPage(tileId, direction) } }
        ).also { editDragHandlers[view] = it }
    }

    /** false — перенос не сохранён (нет доступа), плитка возвращается на место. */
    private fun savePosition(id: String, x: Int, y: Int): Boolean {
        if (!host.adminAction { writePosition(id, x, y); refreshBottomPanel() }) return false
        writePosition(id, x, y)
        return true
    }

    private fun writePosition(id: String, x: Int, y: Int) {
        val tiles = tileManager.loadTiles().toMutableList()
        val idx = tiles.indexOfFirst { it.id == id }
        if (idx >= 0) {
            tiles[idx] = tiles[idx].copy(x = x, y = y)
            tileManager.saveTiles(tiles)
        }
    }

    fun createSettingsButton(): Button {
        return Button(activity).apply {
            text = "⚙"
            alpha = 0.8f
            elevation = 8f
            background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null)
            background?.setTint("#424242".toColorInt())
            setTextColor("#FFFFFF".toColorInt())
            textSize = 28f
            gravity = Gravity.CENTER
            setOnClickListener {
                host.settings()
            }
        }
    }

    fun createAddButton(): Button {
        return Button(activity).apply {
            text = "+"
            alpha = 0.8f
            elevation = 8f
            background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null)
            background?.setTint("#4CAF50".toColorInt())
            setTextColor("#FFFFFF".toColorInt())
            textSize = 32f
            gravity = Gravity.CENTER
            setOnClickListener {
                host.addTile()
            }
        }
    }

    private fun expandChildButtons(tile: TileEntity, src: Button) {
        // Проверяем весь список до изменения интерфейса и создания обработчиков дочерних кнопок.
        val group = groupForAction(tile) ?: return
        val ids = group.entityIds
        collapseChildButtons()

        expandedGroupId = tile.id
        expandedSourceButton = src
        expandedTile = tile

        val bs = getButtonSize(tile)
        val loc = IntArray(2)
        src.getLocationOnScreen(loc)
        val cx = loc[0] + src.width / 2
        val top = loc[1]

        val cnt = ids.size
        val max = 5
        val fst = if (cnt <= max) cnt else (cnt + 1) / 2
        val snd = cnt - fst

        var s1 = cx - fst * (bs + 12) / 2
        var s2 = cx - snd * (bs + 12) / 2
        if (s1 < 0) s1 = 8
        if (s1 + fst * (bs + 12) > screenW) s1 = screenW - fst * (bs + 12) - 8
        if (s2 < 0) s2 = 8
        if (s2 + snd * (bs + 12) > screenW) s2 = screenW - snd * (bs + 12) - 8

        val rws = if (cnt <= max) 1 else 2
        val fy = top - (bs + 12) * rws - 12
        val sy = fy + bs + 12
        val afy = if (fy < 50) top + src.height + 12 else fy
        val asy = if (fy < 50) afy + bs + 12 else sy

        expandedChildButtons.clear()
        val rt = findViewById<FrameLayout>(android.R.id.content)

        for (i in 0 until cnt) {
            val eid = ids[i]
            val nm = group.childNames[i]

            val btn = Button(activity).apply {
                text = nm
                tag = eid
                layoutParams = FrameLayout.LayoutParams(bs, bs)
                alpha = 0f; scaleX = 0.5f; scaleY = 0.5f; elevation = 12f
                background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null)
                ButtonCommandAppearance.render(this, nm, viewModel.devices.confirmedState(eid), viewModel.commands.pendingFor(eid))
                setTextColor("#FFFFFF".toColorInt()); textSize = 14f
                gravity = Gravity.CENTER; isAllCaps = false
                setOnClickListener {
                    viewModel.commands.toggleMember(tile.id, eid)
                    updateGroupButtonAppearance(tile.id)
                    resetCollapseTimer()
                }
            }

            val r = if (i < fst) 0 else 1
            val c = if (r == 0) i else i - fst
            btn.x = (if (r == 0) s1 + c * (bs + 12) else s2 + c * (bs + 12)).toFloat()
            btn.y = (if (r == 0) afy else asy).toFloat()

            rt.addView(btn)
            expandedChildButtons.add(btn)

            btn.animate().alpha(0.8f).scaleX(1f).scaleY(1f)
                .setDuration(250).setStartDelay(i * 50L)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        dimOverlay.isVisible = true
        dimOverlay.animate().alpha(0.4f).setDuration(300).start()
        resetCollapseTimer()
    }

    private fun resetCollapseTimer() {
        collapseChildrenRunnable?.let { handler.removeCallbacks(it) }
        collapseChildrenRunnable = Runnable { collapseChildButtons() }
        handler.postDelayed(collapseChildrenRunnable!!, 10000L)
    }

    private fun collapseChildButtons() {
        expandedGroupId = null; expandedSourceButton = null; expandedTile = null
        val rt = findViewById<FrameLayout>(android.R.id.content)
        expandedChildButtons.forEachIndexed { i, b ->
            closingChildButtons.add(b)
            b.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f)
                .setDuration(200).setStartDelay(i * 30L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { rt.removeView(b); closingChildButtons.remove(b) }.start()
        }
        expandedChildButtons.clear()
        dimOverlay.animate().alpha(0f).setDuration(300)
            .withEndAction { dimOverlay.isVisible = false }.start()
        collapseChildrenRunnable?.let { handler.removeCallbacks(it) }
    }

    /**
     * «Назад» при раскрытой карточке или группе сворачивает её, а не закрывает панель (найдено агентом 05.11.2026:
     * раньше «Назад» при раскрытой АКБ уводил с панели). false — сворачивать нечего.
     */
    fun collapseOnBack(): Boolean {
        if (expandedGroupId == null && expandedSensorView == null && !modernExpansion.isOpen && !modernGroup.isOpen) return false
        collapseAll()
        return true
    }

    fun collapseAll() {
        collapseSensor()
        collapseModernBattery()
        collapseChildButtons()
        modernGroup.close()
    }

    /** Modern-плитки (АКБ, Электропитание, Часы · Погода · Климат): общие раскрытие, resize, перемещение и редактирование. */
    private fun isModern(type: String) =
        type == BatteryModernConfig.TYPE || type == PowerModernConfig.TYPE || type == HomeClimateConfig.TYPE ||
            type == InverterModernConfig.TYPE || type == DoorbellConfig.TYPE

    private fun createModernBatteryView(tile: TileEntity, savedWidth: Int, savedHeight: Int): View {
        // «Часы · Погода · Климат» — всегда 2:1 внутри сохранённого прямоугольника; config не переписывается.
        val doorbellAspect = if (tile.type == DoorbellConfig.TYPE) DoorbellConfig.from(tile.config).aspect else null
        val (width, height) = when {
            tile.type == HomeClimateConfig.TYPE -> HomeClimateConfig.fit(savedWidth, savedHeight)
            // «Домофон» — всегда в пропорции видео (4:3), меняется только размер.
            doorbellAspect != null -> DoorbellConfig.fit(savedWidth, savedHeight, doorbellAspect)
            else -> savedWidth to savedHeight
        }
        val (card, resizeHandle) = when (tile.type) {
            PowerModernConfig.TYPE -> PowerModernCard.createTile(activity, tile.id, width, height, isEditMode)
            HomeClimateConfig.TYPE -> HomeClimateCompactView.createTile(activity, tile.id, width, height, isEditMode)
            InverterModernConfig.TYPE -> InverterModernCard.createTile(activity, tile.id, width, height, isEditMode)
            DoorbellConfig.TYPE -> DoorbellCard.createTile(activity, tile.id, width, height, isEditMode)
            else -> BatteryModernCard.createTile(activity, tile.id, width, height, isEditMode)
        }
        // Новая View сразу получает уже подтверждённое оформление (без повторной анимации).
        renderModern(tile, card, animate = false)
        setupSensorTouch(card, tile)
        resizeHandlers += SensorResizeHandler(
            card, resizeHandle, tile.type, screenW, screenH,
            onResizingChanged = { isResizing = it },
            saveSize = { w, h -> saveSensorSize(tile.id, w, h) },
            aspectRatio = if (tile.type == HomeClimateConfig.TYPE) HomeClimateConfig.ASPECT else doorbellAspect
        )
        return card
    }

    private fun modernReadings() = BatteryReadings.from(viewModel.uiState.value.haSession, viewModel.devices)

    private fun renderModernBattery(tile: TileEntity, view: FrameLayout) = renderModern(tile, view, animate = true)

    /** Только отрисовка: таймер автосворачивания событиями HA не перезапускается. */
    private fun renderModern(tile: TileEntity, view: FrameLayout?, animate: Boolean) {
        if (tile.type == HomeClimateConfig.TYPE) {
            renderClimate(tile, view)
            return
        }
        val readings = modernReadings()
        if (tile.type == PowerModernConfig.TYPE) {
            renderPower(tile, view, readings, animate)
            return
        }
        if (tile.type == InverterModernConfig.TYPE) {
            renderInverter(tile, view, readings, animate)
            return
        }
        if (tile.type == DoorbellConfig.TYPE) {
            renderDoorbell(tile, view, readings, animate)
            return
        }
        val config = batteryConfigs.of(tile)
        view?.findViewWithTag<BatteryModernCard>("bm_compact")?.bind(tile.title, config, readings, host.temperatureUnit)
        if (modernExpansion.isOpen && modernExpansion.tileId == tile.id) {
            (modernExpansion.card as? BatteryModernCard)?.bind(tile.title, config, readings, host.temperatureUnit)
        }
    }

    /** Показания, прогнозы и статус связи — из данных панели; часы тикают внутри самих карточек. */
    private fun renderClimate(tile: TileEntity, view: FrameLayout?) {
        val snapshot = HomeClimateSnapshot.from(tile.title, climateConfigs.of(tile),
            viewModel.uiState.value.haSession, viewModel.devices, viewModel.uiState.value.yandexWeather)
        view?.findViewWithTag<HomeClimateCompactView>("hc_compact")?.bind(snapshot)
        if (modernExpansion.isOpen && modernExpansion.tileId == tile.id) {
            (modernExpansion.card as? HomeClimateExpandedCard)?.bind(snapshot)
        }
    }

    /** Отложенные проверки подтверждения диапазона: не больше одной на плитку. */
    private val powerChecks = mutableMapOf<String, Runnable>()

    /**
     * Цифры — сразу; цвет — по трекеру этой плитки (только её собственное напряжение).
     * Если новый диапазон ещё не подтверждён, плитка перерисуется к моменту подтверждения.
     */
    private fun renderPower(tile: TileEntity, view: FrameLayout?, readings: BatteryReadings, animate: Boolean) {
        val config = powerConfigs.of(tile)
        val tracker = viewModel.powerTracker(tile.id)
        val voltage = readings.of(config.row(PowerModernConfig.ROLE_VOLTAGE))
        val now = android.os.SystemClock.uptimeMillis()
        val confirmed = tracker.update((voltage as? BatteryReading.Value)?.number, config, now)
        val visual = PowerVisual.of(config, voltage, readings, confirmed)
        view?.findViewWithTag<PowerModernCard>("pm_compact")?.bind(tile.title, config, readings, visual, animate)
        if (modernExpansion.isOpen && modernExpansion.tileId == tile.id) {
            (modernExpansion.card as? PowerModernCard)?.let { card ->
                card.bind(tile.title, config, readings, visual, animate)
                card.graph?.bind(config.graphSource)
            }
        }
        powerChecks.remove(tile.id)?.let(handler::removeCallbacks)
        val at = tracker.nextCheckAt ?: return
        val check = Runnable {
            powerChecks.remove(tile.id)
            tileManager.getAllTiles().find { it.id == tile.id }?.let { renderModern(it, viewForTile(it.id) as? FrameLayout, true) }
        }
        powerChecks[tile.id] = check
        handler.postDelayed(check, (at - now).coerceAtLeast(0L))
    }

    /**
     * «Домофон»: картинка и подписи из показаний HA, звонки и пропущенные — из [DoorbellHub].
     * Подпись «Звонок: N мин назад» обновляется раз в минуту, пока на панели есть домофон.
     */
    private fun renderDoorbell(tile: TileEntity, view: FrameLayout?, readings: BatteryReadings, animate: Boolean) {
        val config = DoorbellConfig.from(tile.config)
        val snapshot = DoorbellModel.snapshot(config, readings)
        val now = System.currentTimeMillis()
        val offline = DoorbellHub.offline(tile.id)
        val missed = DoorbellHub.missed(tile.id)
        val last = DoorbellHub.lastRing(tile.id)
        view?.findViewWithTag<DoorbellCard>("door_compact")?.bind(tile.title, config, snapshot, offline, missed, last, now, animate)
        if (modernExpansion.isOpen && modernExpansion.tileId == tile.id) {
            (modernExpansion.card as? DoorbellCard)?.bind(tile.title, config, snapshot, offline, missed, last, now, animate)
        }
        handler.removeCallbacks(doorbellTick)
        handler.postDelayed(doorbellTick, 60_000L)
    }

    private val doorbellTick = Runnable { renderDoorbells() }

    /** Звонок, пропущенные, окно звонка открылось/закрылось: перерисовать плитки домофона текущей страницы. */
    fun renderDoorbells() {
        if (!host.hasPanel) return
        tileManager.getAllTiles().filter { it.type == DoorbellConfig.TYPE }.forEach { tile ->
            renderModern(tile, viewForTile(tile.id) as? FrameLayout, animate = true)
        }
    }

    /** Видео плитки домофона на экране (для бесшовного начала окна звонка); null — плитки не видно. */
    fun tileVideo(tileId: String): DoorbellVideoView? =
        (viewForTile(tileId)?.takeIf { it.isShown } as? ViewGroup)?.findViewWithTag<DoorbellCard>("door_compact")?.video

    /** Прямоугольник плитки в окне — окно звонка растёт из неё. */
    fun tileBounds(tileId: String): android.graphics.RectF? {
        val view = viewForTile(tileId) ?: return null
        if (!view.isShown || view.width <= 0) return null
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return android.graphics.RectF(location[0].toFloat(), location[1].toFloat(),
            location[0] + view.width.toFloat(), location[1] + view.height.toFloat())
    }

    /** Отложенные проверки подтверждения цвета «Инвертора»: не больше одной на плитку. */
    private val inverterChecks = mutableMapOf<String, Runnable>()

    /** Схема и цифры — сразу; цвет — по трекеру плитки (смена держится 1,8 с, без мерцания у границ). */
    private fun renderInverter(tile: TileEntity, view: FrameLayout?, readings: BatteryReadings, animate: Boolean) {
        val config = inverterConfigs.of(tile)
        val snapshot = InverterModel.snapshot(config, readings)
        val tracker = viewModel.inverterTracker(tile.id)
        val now = android.os.SystemClock.uptimeMillis()
        val look = tracker.update(snapshot.look, now)
        view?.findViewWithTag<InverterModernCard>("inv_compact")?.bind(tile.title, config, readings, snapshot, look, animate)
        if (modernExpansion.isOpen && modernExpansion.tileId == tile.id) {
            val pending = config.row(InverterModernConfig.ROLE_PRIORITY)?.entityId?.takeIf { it.isNotBlank() }
                ?.let { viewModel.commands.pendingFor(it)?.expectedState }
            (modernExpansion.card as? InverterModernCard)?.bind(tile.title, config, readings, snapshot, look, animate, pending)
        }
        inverterChecks.remove(tile.id)?.let(handler::removeCallbacks)
        val at = tracker.nextCheckAt ?: return
        val check = Runnable {
            inverterChecks.remove(tile.id)
            tileManager.getAllTiles().find { it.id == tile.id }?.let { renderModern(it, viewForTile(it.id) as? FrameLayout, true) }
        }
        inverterChecks[tile.id] = check
        handler.postDelayed(check, (at - now).coerceAtLeast(0L))
    }

    /**
     * Смена приоритета источника (решение владельца): подтверждение → PIN → `select.select_option`.
     * Ожидание показывается на карточке до факта HA; повтор при ожидании не отправляется.
     */
    private var priorityDialog: android.app.AlertDialog? = null
    private val priorityDialogTimeout = Runnable { closePriorityDialog() }

    private fun confirmPriority(tileId: String, option: String) {
        modernExpansion.touch()
        closePriorityDialog()
        val label = InverterModel.priorityLabel(option)
        priorityDialog = ModernUi.dangerDialog(activity, "Сменить приоритет источника?",
            "Инвертор переключится на «$label». Дом может перейти на другой источник питания.", "Сменить") {
            if (host.adminAction { sendPriority(tileId, option) }) sendPriority(tileId, option)
        }.apply {
            setOnDismissListener { if (priorityDialog === this) { priorityDialog = null; handler.removeCallbacks(priorityDialogTimeout) } }
            show()
        }
        // Забытое подтверждение на стене не висит: через 30 с закрывается само, команды нет.
        handler.postDelayed(priorityDialogTimeout, PRIORITY_DIALOG_MS)
    }

    private fun closePriorityDialog() {
        handler.removeCallbacks(priorityDialogTimeout)
        priorityDialog?.let { priorityDialog = null; if (it.isShowing) it.dismiss() }
    }

    private fun sendPriority(tileId: String, option: String) {
        // Только из открытой карточки этой плитки: после сворачивания или перестройки панели команда не уходит.
        if (!modernExpansion.isOpen || modernExpansion.tileId != tileId) return
        val tile = tileManager.getAllTiles().find { it.id == tileId } ?: return
        val entity = inverterConfigs.of(tile).row(InverterModernConfig.ROLE_PRIORITY)?.entityId?.takeIf { it.isNotBlank() } ?: return
        viewModel.commands.selectOption(entity, option)
        renderModern(tile, viewForTile(tile.id) as? FrameLayout, animate = true)
    }

    /** Смена связи/синхронизации меняет достоверность всех показаний Modern без событий сущностей. */
    fun renderSessionState() {
        if (!host.hasPanel) return
        tileManager.getAllTiles().filter { isModern(it.type) }.forEach { tile ->
            (viewForTile(tile.id) as? FrameLayout)?.let { renderModernBattery(tile, it) }
        }
    }

    /** Окно не в фокусе (системный диалог, шторка): автосворачивание приостанавливается. */
    fun setInteractive(interactive: Boolean) {
        if (interactive) modernExpansion.resume() else modernExpansion.pause()
    }

    /** Метка для системного трейса (atrace/Perfetto): старт раскрытия по частям; без записи трейса почти бесплатна. */
    private inline fun <T> traced(name: String, block: () -> T): T {
        android.os.Trace.beginSection(name)
        try {
            return block()
        } finally {
            android.os.Trace.endSection()
        }
    }

    private fun expandModernBattery(tile: TileEntity, source: View) = traced("SHD expand ${tile.type}") {
        expandModernBatteryTraced(tile, source)
    }

    /**
     * Раскрытая карточка, собранная, пока палец ещё на плитке (0.9.16). Трейс на Huawei MatePad SE (0.9.15):
     * от касания до первого кадра раскрытия 40–140 мс уходило на создание карточки, заполнение данными и первую
     * разметку (масштаб карточек применяется после первой разметки ещё одним проходом). Обычное нажатие длится
     * 70–150 мс: эту работу можно сделать за это время. Карточка добавляется в overlay невидимой (INVISIBLE —
     * не рисуется и не получает касаний), размечается обычным проходом; по отпусканию [BatteryModernExpansion.expand]
     * только показывает её. Сдвиг пальца, отмена, перестроение панели или ожидание дольше [PREPARED_MAX_AGE_MS] —
     * карточка удаляется. Домофон не готовится заранее (видео одалживается в момент раскрытия), только снимок фона.
     */
    private class Prepared(val tileId: String, val config: String, val type: String, val source: View,
                           val target: BatteryModernExpansion.Bounds) {
        val startedAt = android.os.SystemClock.uptimeMillis()
        var card: View? = null
        /** Данные уже в карточке (АКБ, климат): по отпусканию не заполнять повторно. */
        var bound = false
        var graphState: PowerGraphState? = null
    }
    private var prepared: Prepared? = null
    private val prepareCreate = Runnable { prepared?.let(::prepareCreate) }
    private val prepareBind = Runnable { prepared?.let(::prepareBind) }
    private val preparedExpired = Runnable { discardPrepared() }

    private fun canPrepare(): Boolean = !isEditMode && !isResizing && expandedSensorView == null &&
        modernExpansion.card == null && !modernGroup.isOpen

    private fun startPreparing(tileId: String, source: View) {
        discardPrepared()
        if (!canPrepare()) return
        val tile = tileManager.getAllTiles().find { it.id == tileId } ?: return
        // Снимок фона — сразу: копия кадра идёт на фоновом потоке, главный поток почти не занят (0.9.15).
        modernExpansion.prefetch(source)
        val target = expandedTarget(tile) ?: return
        prepared = Prepared(tile.id, tile.config, tile.type, source, target)
        // Небольшая пауза: если это начало листания страницы, палец за это время уходит дальше порога сдвига,
        // и карточка не собирается вовсе (иначе сборка на главном потоке притормозила бы начало листания).
        handler.postDelayed(prepareCreate, PREPARE_DELAY_MS)
        handler.postDelayed(preparedExpired, PREPARED_MAX_AGE_MS)
    }

    private fun cancelPreparing(source: View) {
        if (prepared?.source === source) discardPrepared()
        modernExpansion.cancelPrefetch(source)
    }

    private fun prepareCreate(p: Prepared) = traced("SHD prepare create") {
        if (prepared !== p || p.card != null || !canPrepare()) return@traced
        val tile = tileManager.getAllTiles().find { it.id == p.tileId && it.config == p.config } ?: return@traced discardPrepared()
        p.card = when (tile.type) {
            HomeClimateConfig.TYPE -> HomeClimateExpandedCard(activity)
            InverterModernConfig.TYPE -> InverterModernCard(activity, expanded = true)
            PowerModernConfig.TYPE -> {
                val card = PowerModernCard(activity, expanded = true)
                val prefs = activity.getSharedPreferences("dashboard_prefs", android.content.Context.MODE_PRIVATE)
                val state = PowerGraphState(PowerGraphPanel.savedPeriod(prefs, tile.id))
                p.graphState = state
                card.attachGraph(PowerGraphPanel(activity, viewModel.powerHistory, tile.id, state, prefs,
                    onExpand = { openPowerGraph(card, tile.id, state) }, onTouch = modernExpansion::touch))
                card.graph?.bind(PowerModernConfig.from(tile.config).graphSource)
                card
            }
            BatteryModernConfig.TYPE -> BatteryModernCard(activity, expanded = true)
            else -> null
        }
        if (p.card == null) return@traced discardPrepared()
        // Заполнение и разметка — следующим шагом, чтобы не занимать главный поток одним куском.
        handler.post(prepareBind)
    }

    private fun prepareBind(p: Prepared) = traced("SHD prepare bind") {
        val card = p.card ?: return@traced
        if (prepared !== p || card.parent != null) return@traced
        val tile = tileManager.getAllTiles().find { it.id == p.tileId && it.config == p.config } ?: return@traced discardPrepared()
        when (card) {
            is HomeClimateExpandedCard -> {
                card.bind(HomeClimateSnapshot.from(tile.title, climateConfigs.of(tile), viewModel.uiState.value.haSession,
                    viewModel.devices, viewModel.uiState.value.yandexWeather))
                p.bound = true
            }
            is BatteryModernCard -> {
                card.bind(tile.title, BatteryModernConfig.from(tile.config), modernReadings(), host.temperatureUnit)
                p.bound = true
            }
        }
        val overlay = activity.findViewById<FrameLayout>(android.R.id.content) ?: return@traced discardPrepared()
        card.visibility = View.INVISIBLE
        // Разметка обычным проходом, в том числе отложенный пересчёт масштаба карточки после первого размера.
        overlay.addView(card, FrameLayout.LayoutParams(p.target.width, p.target.height))
    }

    /** Заранее собранная карточка для раскрытия [tile] из [source]; null — собирать как обычно. */
    private fun takePrepared(tile: TileEntity, source: View): Prepared? {
        val p = prepared ?: return null
        prepared = null
        handler.removeCallbacks(prepareCreate)
        handler.removeCallbacks(prepareBind)
        handler.removeCallbacks(preparedExpired)
        val fresh = android.os.SystemClock.uptimeMillis() - p.startedAt <= PREPARED_MAX_AGE_MS
        if (p.card != null && fresh && p.tileId == tile.id && p.config == tile.config && p.source === source &&
            p.target == expandedTarget(tile)) return p
        release(p)
        return null
    }

    private fun discardPrepared() {
        handler.removeCallbacks(prepareCreate)
        handler.removeCallbacks(prepareBind)
        handler.removeCallbacks(preparedExpired)
        val p = prepared ?: return
        prepared = null
        release(p)
    }

    private fun release(p: Prepared) {
        val card = p.card ?: return
        p.card = null
        (card as? ModernOverlayCard)?.dispose()
        (card.parent as? ViewGroup)?.removeView(card)
    }

    /** Размер и место раскрытой карточки — одно правило и для заранее собранной, и для обычной. */
    private fun expandedTarget(tile: TileEntity): BatteryModernExpansion.Bounds? {
        val (width, height) = when (tile.type) {
            HomeClimateConfig.TYPE -> {
                val width = minOf((screenW * 0.92f).toInt(), 1700)
                width to (if (screenW < screenH) (screenH * 0.92f).toInt()
                    else minOf((screenH * 0.92f).toInt(), (width * 0.74f).toInt()).coerceAtLeast((screenH * 0.6f).toInt()))
            }
            InverterModernConfig.TYPE -> {
                val width = minOf((screenW * 0.9f).toInt(), 1600)
                width to minOf((screenH * 0.9f).toInt(), (width * 0.64f).toInt()).coerceAtLeast(screenH / 2)
            }
            PowerModernConfig.TYPE -> {
                val width = minOf((screenW * 0.9f).toInt(), 1700)
                width to minOf((screenH * 0.9f).toInt(), (width * 0.68f).toInt()).coerceAtLeast(screenH / 2)
            }
            BatteryModernConfig.TYPE -> {
                val width = minOf((screenW * 0.92f).toInt(), 1600)
                width to minOf((screenH * 0.9f).toInt(), (width * 0.8f).toInt().coerceAtLeast(screenH / 2))
            }
            else -> return null
        }
        return BatteryModernExpansion.Bounds((screenW - width) / 2f, (screenH - height) / 2f, width, height)
    }

    private fun expandModernBatteryTraced(tile: TileEntity, source: View) {
        val ready = takePrepared(tile, source)
        try {
            expandModern(tile, source, ready)
        } finally {
            // Заранее собранная карточка не пригодилась (другой тип, ошибка) — не оставлять её невидимой в overlay.
            if (ready != null && ready.card != null && ready.card !== modernExpansion.card) release(ready)
        }
    }

    private fun expandModern(tile: TileEntity, source: View, ready: Prepared?) {
        // Снимок фона копируется на фоновом потоке, пока ниже собирается карточка (0.9.15); начатый при касании
        // плитки (0.9.16) — переиспользуется.
        traced("SHD prefetch") { modernExpansion.prefetch(source, reuseFresh = true) }
        traced("SHD close others") {
            collapseChildButtons()
            modernGroup.close()
        }
        if (tile.type == HomeClimateConfig.TYPE) {
            // Та же механика: рост из плитки к центру, blur панели, автосворачивание, закрытие тапом.
            val target = expandedTarget(tile) ?: return
            val card = ready?.card as? HomeClimateExpandedCard ?: traced("SHD card create") { HomeClimateExpandedCard(activity) }
            traced("SHD expansion") { modernExpansion.expand(tile.id, source, card, target) }
            if (ready?.bound != true) traced("SHD card bind") { renderModern(tile, null, animate = false) }
            return
        }
        if (tile.type == DoorbellConfig.TYPE) {
            // Видео крупно со звуком (только слушать). Касание сворачивает; автосворачивание — по настройке плитки.
            val config = DoorbellConfig.from(tile.config)
            var height = (screenH * 0.9f).toInt()
            var width = (height * config.aspect).toInt()
            if (width > screenW * 0.94f) { width = (screenW * 0.94f).toInt(); height = (width / config.aspect).toInt() }
            val target = BatteryModernExpansion.Bounds((screenW - width) / 2f, (screenH - height) / 2f, width, height)
            DoorbellHub.acknowledge(tile.id)
            val expanded = DoorbellCard(activity, expanded = true)
            modernExpansion.expand(tile.id, source, expanded, target,
                if (config.autoCollapse) config.collapseSeconds * 1000L else null)
            renderModern(tile, null, animate = false)
            // Бесшовно: пока подключается основной поток, крупно идёт уже работающий субпоток плитки;
            // при сворачивании — наоборот (DoorbellVideoView.lendTo / partner).
            source.findViewWithTag<DoorbellCard>("door_compact")?.video?.let { compact ->
                expanded.video.partner = compact
                compact.lendTo(expanded.video)
            }
            return
        }
        if (tile.type == InverterModernConfig.TYPE) {
            // Слева схема крупно, справа подробности узлов и приоритет источника.
            val target = expandedTarget(tile) ?: return
            val card = ready?.card as? InverterModernCard ?: InverterModernCard(activity, expanded = true)
            card.onPriority = { option -> confirmPriority(tile.id, option) }
            card.onClosing = ::closePriorityDialog
            card.scroll?.setOnScrollChangeListener { _, _, _, _, _ -> modernExpansion.touch() }
            modernExpansion.expand(tile.id, source, card, target)
            renderModern(tile, null, animate = false)
            return
        }
        if (tile.type == PowerModernConfig.TYPE) {
            // Компоновка по эскизу: слева линия и главные цифры, справа строки и «История мощности».
            // С графиком карточка выше (до 90% экрана), центр панели под камеры не затрагивается.
            val target = expandedTarget(tile) ?: return
            val card = ready?.card as? PowerModernCard ?: traced("SHD card create") {
                val card = PowerModernCard(activity, expanded = true)
                val prefs = activity.getSharedPreferences("dashboard_prefs", android.content.Context.MODE_PRIVATE)
                val state = PowerGraphState(PowerGraphPanel.savedPeriod(prefs, tile.id))
                val panel = traced("SHD graph create") {
                    PowerGraphPanel(activity, viewModel.powerHistory, tile.id, state, prefs,
                        onExpand = { openPowerGraph(card, tile.id, state) }, onTouch = modernExpansion::touch)
                }
                traced("SHD graph attach") { card.attachGraph(panel) }
                traced("SHD graph bind") { panel.bind(PowerModernConfig.from(tile.config).graphSource) }
                card
            }
            traced("SHD expansion") { modernExpansion.expand(tile.id, source, card, target) }
            traced("SHD card bind") { renderModern(tile, null, animate = false) }
            return
        }
        // Сюда доходит только «АКБ Modern». Плитка другого типа (например, повреждённая конфигурация с повторным id,
        // когда по id находится не та плитка) раньше раскрывалась пустой карточкой АКБ, а в 0.9.16 роняла панель.
        if (tile.type != BatteryModernConfig.TYPE) return
        val target = expandedTarget(tile) ?: return
        val card = ready?.card as? BatteryModernCard ?: traced("SHD card create") { BatteryModernCard(activity, expanded = true) }
        if (ready?.bound != true) {
            traced("SHD card bind") { card.bind(tile.title, BatteryModernConfig.from(tile.config), modernReadings(), host.temperatureUnit) }
        }
        traced("SHD expansion") { modernExpansion.expand(tile.id, source, card, target) }
    }

    /**
     * Полноэкранный график — слой поверх приложения (не Activity) с тем же состоянием просмотра:
     * период, позиция, масштаб, курсор. Закрывается кнопкой ✕, «Назад» и вместе с карточкой.
     */
    private fun openPowerGraph(card: PowerModernCard, tileId: String, state: PowerGraphState) {
        if (card.fullscreen != null) return
        val host = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val prefs = activity.getSharedPreferences("dashboard_prefs", android.content.Context.MODE_PRIVATE)
        val overlay = FrameLayout(activity).apply {
            tag = "pg_overlay"
            isClickable = true
            setBackgroundColor(android.graphics.Color.argb(245, 6, 18, 30))
            val pad = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = 40f
            alpha = 0f
        }
        lateinit var back: androidx.activity.OnBackPressedCallback
        fun close() {
            if (card.fullscreen !== overlay) return
            card.fullscreen = null
            card.closeFullscreen = null
            back.remove()
            fade(overlay, 0f, BatteryModernExpansion.CLOSE_MS) { (overlay.parent as? ViewGroup)?.removeView(overlay) }
            // Возврат к карточке с тем же периодом, позицией и масштабом.
            card.graph?.refresh()
            modernExpansion.touch()
        }
        back = object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() = close() }
        val panel = PowerGraphPanel(activity, viewModel.powerHistory, tileId, state, prefs, fullscreen = true,
            onExpand = { close() }, onTouch = modernExpansion::touch)
        overlay.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tileManager.getAllTiles().find { it.id == tileId }?.let { panel.bind(PowerModernConfig.from(it.config).graphSource) }
        host.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.fullscreen = overlay
        card.closeFullscreen = ::close
        // Снимается в close(); без привязки к жизненному циклу — «Назад» работает сразу после открытия.
        (activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.addCallback(back)
        fade(overlay, 1f, BatteryModernExpansion.OPEN_MS) {}
        modernExpansion.touch()
    }

    /** Плавная смена прозрачности слоя; предыдущая анимация этого слоя отменяется. */
    private fun fade(view: View, to: Float, duration: Long, end: () -> Unit) {
        graphFade?.let { it.removeAllListeners(); it.removeAllUpdateListeners(); it.cancel() }
        val animator = android.animation.ValueAnimator.ofFloat(view.alpha, to).setDuration(duration)
        animator.addUpdateListener { view.alpha = it.animatedValue as Float }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (graphFade === animator) graphFade = null
                view.alpha = to
                end()
            }
        })
        graphFade = animator
        animator.start()
    }

    /** Анимация полноэкранного графика (одновременно открыт не больше одного). */
    private var graphFade: android.animation.ValueAnimator? = null

    private fun collapseModernBattery() {
        modernWindowInput.clear()
        modernExpansion.collapse()
    }

    private fun clearModernExpansion() {
        modernWindowInput.clear()
        modernExpansion.clear()
    }

    private fun clearSensorExpansion() {
        sensorWindowInput.clear()
        weatherForecastScroll.clear()
        sensorExpansion.clear()
    }

    fun clearPanelInteractions() = clearPanel(keepModernGroup = false)

    private fun clearPanel(keepModernGroup: Boolean) {
        discardPrepared()
        if (!keepModernGroup) modernGroup.clear()
        renderedViews.clear()
        scannedChildren = -1
        parsedConfigs.forEach { it.clear() }
        dependencyIndex = PanelDependencyIndex(emptyList())
        panelEntityTiles = null
        panelEntityMotionSensor = null
        panelEntityIds = emptySet()
        // Этот Handler принадлежит только Activity и обслуживает жесты старой панели.
        handler.removeCallbacksAndMessages(null)
        longPressRunnable = null
        collapseChildrenRunnable = null
        clearSensorExpansion()
        clearModernExpansion()
        powerChecks.clear()
        resizeHandlers.forEach { it.dispose() }
        resizeHandlers.clear()
        editDragHandlers.values.forEach { it.dispose() }
        editDragHandlers.clear()
        isResizing = false

        // Список раскрытых кнопок уже пуст при анимированном закрытии, поэтому
        // отдельно учитываем кнопки, которые ещё ожидают удаления из overlay.
        (expandedChildButtons + closingChildButtons).distinct().forEach { button ->
            button.animate().withEndAction(null).setListener(null).cancel()
            button.cancelPendingInputEvents()
            button.setOnClickListener(null)
            (button.parent as? ViewGroup)?.removeView(button)
        }
        expandedChildButtons.clear()
        closingChildButtons.clear()
        expandedGroupId = null
        expandedSourceButton = null
        expandedTile = null
        if (host.hasDim) {
            dimOverlay.animate().withEndAction(null).setListener(null).cancel()
            dimOverlay.alpha = 0f
            dimOverlay.isVisible = false
        }
        if (host.hasPanel) {
            for (i in 0 until bottomPanel.childCount) {
                val child = bottomPanel.getChildAt(i)
                child.animate().withEndAction(null).setListener(null).cancel()
                child.cancelPendingInputEvents()
                child.setOnTouchListener(null)
                child.setOnClickListener(null)
            }
        }
    }

    private fun updateGroupState(gid: String, eid: String, st: String) {
        viewModel.updateGroupState(gid, eid, st)
        updateGroupButtonAppearance(gid)
    }

    private fun getGroupState(gid: String): Boolean {
        val tile = tileManager.getAllTiles().find { it.id == gid && it.type == "group" } ?: return false
        val result = TileButtonConfig.group(tile.config)
        if (result !is ButtonConfigResult.Valid) return false
        return viewModel.groupIsOn(gid, result.value.entityIds)
    }

    private fun updateGroupButtonAppearance(gid: String) {
        val tile = tileManager.getAllTiles().find { it.id == gid && it.type == "group" } ?: return
        for (i in 0 until bottomPanel.childCount) {
            val child = bottomPanel.getChildAt(i)
            if (child.tag != gid) continue
            if (child is ModernTileView) child.bind(modernModel(tile), animate = true)
            else if (child is Button) renderTileButton(child, tile)
        }
        if (modernGroup.tileId == gid) modernGroup.refreshParent()
    }

    private fun toggleGroup(gid: String) {
        // Ни одна команда не отправляется до успешной проверки текущего состава.
        val tile = tileManager.getAllTiles().find { it.id == gid && it.type == "group" } ?: return
        val group = groupForAction(tile) ?: return
        viewModel.commands.toggleGroup(gid, group.entityIds)
    }

    fun renderCommandState(eid: String) {
        val tiles = currentIndex().affected(listOf(eid))
        renderControls(tiles, setOf(eid))
        // Ожидание приоритета инвертора могло сняться без события сущности (отказ, тайм-аут).
        tiles.filter { it.type == InverterModernConfig.TYPE }.forEach { renderModern(it, viewForTile(it.id) as? FrameLayout, true) }
    }

    private fun currentIndex(): PanelDependencyIndex {
        val tiles = tileManager.getAllTiles()
        if (tiles !== panelEntityTiles || motionSensorId != panelEntityMotionSensor) {
            dependencyIndex = PanelDependencyIndex(tiles)
            panelEntityIds = dependencyIndex.entityIds + listOfNotNull(motionSensorId)
            panelEntityTiles = tiles
            panelEntityMotionSensor = motionSensorId
        }
        return dependencyIndex
    }

    /** Число детей панели при последнем пересчёте кэша; −1 — пересчёта ещё не было. */
    private var scannedChildren = -1

    private fun viewForTile(id: String): View? {
        val cached = renderedViews[id]
        if (cached?.parent === bottomPanel) return cached
        // Плитка другой страницы не нарисована: если дерево не менялось с прошлого пересчёта, искать
        // нечего. Раньше каждое такое событие сбрасывало и пересобирало кэш всей страницы.
        if (cached == null && bottomPanel.childCount == scannedChildren) return null
        // Проверяем фактическое дерево, если View создан/заменён вне обычного refresh.
        // Сохраняем только прямых живых детей; старые Views не остаются в кеше.
        renderedViews.clear()
        for (i in 0 until bottomPanel.childCount) {
            val child = bottomPanel.getChildAt(i)
            (child.tag as? String)?.let { renderedViews[it] = child }
        }
        scannedChildren = bottomPanel.childCount
        return renderedViews[id]
    }

    private fun renderControls(tiles: List<TileEntity>, changed: Set<String>) {
        tiles.forEach { tile ->
            if (tile.type != "button" && tile.type != "group") return@forEach
            when (val view = viewForTile(tile.id)) {
                is ModernTileView -> modernModel(tile).let { if (view.boundModel != it) view.bind(it, animate = true) }
                is Button -> renderTileButton(view, tile)
            }
        }
        if (modernGroup.tileId != null) modernGroup.render(changed)
        val group = expandedGroupId?.let { id -> tileManager.getAllTiles().find { it.id == id } }
        val config = group?.let { TileButtonConfig.group(it.config) }
        expandedChildButtons.forEachIndexed { childIndex, button ->
            if (button.tag !in changed) return@forEachIndexed
            val id = button.tag as String
            val title = if (config is ButtonConfigResult.Valid) {
                config.value.childNames.getOrNull(childIndex)
            } else null
            ButtonCommandAppearance.render(button, title ?: id, viewModel.devices.confirmedState(id), viewModel.commands.pendingFor(id))
        }
    }

    private fun updateTilesForEntity(eid: String, st: String, attrs: JSONObject? = null) {
        viewModel.onEntityStateChanged(eid, st, attrs)
        renderEntityState(eid, st)
    }

    fun renderInitialStates(entityIds: List<String>) {
        val changed = entityIds.toSet()
        val tiles = currentIndex().affected(changed)
        motionSensorId?.takeIf { it in changed }?.let { wakeForState(it, singleStates[it]) }
        renderControls(tiles, changed)
        contentController.updateTiles(tiles, ::viewForTile)
    }

    private fun wakeForState(id: String, state: String?) {
        if (id == motionSensorId && state in listOf("on", "true", "playing")) host.wake()
    }

    fun renderEntityState(eid: String, st: String) {
        // Пробуждение не ждёт отрисовки и не зависит от conflation StateFlow.
        wakeForState(eid, st)
        val tiles = currentIndex().affected(listOf(eid))
        if (tiles.isEmpty()) return
        renderControls(tiles, setOf(eid))
        contentController.updateTiles(tiles, ::viewForTile)
    }

    private fun updateForecastForEntity(eid: String, forecast: JSONArray?) {
        viewModel.onForecastChanged(eid, forecast)
        renderForecastState(eid)
    }

    fun renderForecastState(eid: String) {
        val affected = currentIndex().affected(listOf(eid))
        val weather = affected.filter { it.type == "weather" }
        contentController.updateTiles(weather, ::viewForTile)
        affected.filter { it.type == HomeClimateConfig.TYPE }.forEach { renderClimate(it, viewForTile(it.id) as? FrameLayout) }
    }

    fun updateClockViews() = contentController.updateClockViews()
    fun updateWeatherViews() {
        contentController.updateWeatherViews()
        tileManager.getAllTiles().filter { it.type == HomeClimateConfig.TYPE &&
            HomeClimateConfig.from(it.config).weather.source == HomeClimateConfig.SOURCE_YANDEX }.forEach {
            renderClimate(it, viewForTile(it.id) as? FrameLayout)
        }
    }
}
