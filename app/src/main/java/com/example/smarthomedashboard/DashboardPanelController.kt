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
    }
    private val bottomPanel get() = host.panel
    private val dimOverlay get() = host.dim
    private val tileManager get() = host.tiles
    private val motionSensorId get() = host.motionEntity
    private val screenW by lazy { resources.displayMetrics.widthPixels }
    private val screenH by lazy { resources.displayMetrics.heightPixels }
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

    private val sensorExpansion = SensorExpansionSession(handler)
    private val expandedSensorView: View? get() = sensorExpansion.current
    private val contentController by lazy {
        DashboardContentController(activity, viewModel.devices, host, { expandedSensorView }, ::resetSensorCollapseTimer)
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
    companion object { private const val DEFAULT_BUTTON_SIZE = 160 }

    fun dispatchTouch(event: MotionEvent): Boolean? = sensorWindowInput.dispatch(event)

    fun backgroundTouch(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val hit = findViewAt(event.x, event.y)
            if (hit == null || hit == dimOverlay) {
                if (isEditMode) host.exitEdit()
                else {
                    longPressRunnable = Runnable { host.requestEdit() }
                    handler.postDelayed(longPressRunnable!!, 3000L)
                }
            }
        } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            longPressRunnable?.let { handler.removeCallbacks(it) }
        }
    }

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
        clearPanelInteractions()
        bottomPanel.removeAllViews()

        val tiles = tileManager.getTilesByContainer("bottom_panel")

        // Сенсоры
        tileManager.getTilesByContainer("grid").forEach { tile ->
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

        // Кнопки управления (Настройки и +) только в режиме редактирования
        if (isEditMode) {
            val btnS = createSettingsButton().apply {
                layoutParams = FrameLayout.LayoutParams(DEFAULT_BUTTON_SIZE, DEFAULT_BUTTON_SIZE)
                x = (screenW - DEFAULT_BUTTON_SIZE - 20).toFloat()
                y = (screenH - DEFAULT_BUTTON_SIZE - 80).toFloat()
            }
            bottomPanel.addView(btnS)

            val btnA = createAddButton().apply {
                layoutParams = FrameLayout.LayoutParams(DEFAULT_BUTTON_SIZE, DEFAULT_BUTTON_SIZE)
                x = (screenW - DEFAULT_BUTTON_SIZE * 2 - 40).toFloat()
                y = (screenH - DEFAULT_BUTTON_SIZE - 80).toFloat()
            }
            bottomPanel.addView(btnA)
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gestureActive) return@setOnTouchListener true
                    if (kotlin.math.abs(event.rawX - startX) > 10 ||
                        kotlin.math.abs(event.rawY - startY) > 10) hasMoved = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!gestureActive) return@setOnTouchListener true
                    gestureActive = false
                    view.performClick()
                    if (!hasMoved && tile.type != "clock") {
                        if (expandedSensorView != null && expandedSensorView != view) collapseSensor()
                        else expandSensor(tile, view)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    gestureActive = false
                    hasMoved = false
                    true
                }
                else -> false
            }
        }
    }

    private fun createTileButton(tile: TileEntity): Button {
        val sz = getButtonSize(tile)
        return TileButtonFactory.create(activity, tile.title, tile.id, sz, ButtonCommandAppearance.OFF).apply {
            renderTileButton(this, tile)
            if (tile.type == "group") setupGroupTouch(tile, this)
            else setupButtonTouch(tile, this)
        }
    }

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

    private fun setupGroupTouch(tile: TileEntity, button: Button) {
        val editDrag = bindEditDrag(button, tile.id)
        var pressRunnable: Runnable? = null
        var startX = 0f
        var startY = 0f
        var hasMoved = false
        var gestureActive = false

        button.setOnTouchListener { view, event ->
            if (isEditMode) return@setOnTouchListener editDrag.onTouch(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pressRunnable?.let { handler.removeCallbacks(it) }
                    gestureActive = true
                    hasMoved = false
                    startX = event.rawX
                    startY = event.rawY
                    pressRunnable = Runnable {
                        if (!gestureActive) return@Runnable
                        if (expandedGroupId == tile.id) collapseChildButtons()
                        else expandChildButtons(tile, view as Button)
                    }.also { handler.postDelayed(it, 1000L) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gestureActive) return@setOnTouchListener true
                    if (kotlin.math.abs(event.rawX - startX) > 10 ||
                        kotlin.math.abs(event.rawY - startY) > 10) {
                        hasMoved = true
                        pressRunnable?.let { handler.removeCallbacks(it) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!gestureActive) return@setOnTouchListener true
                    gestureActive = false
                    view.performClick()
                    pressRunnable?.let { handler.removeCallbacks(it) }
                    pressRunnable = null
                    if (!hasMoved && expandedGroupId != tile.id) toggleGroup(tile.id)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
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

    private fun setupButtonTouch(
        tile: TileEntity,
        button: Button
    ) {
        button.setOnClickListener {
            if (!isEditMode) {
                val eid = buttonEntityId(tile, reportError = true)
                if (eid != null) {
                    viewModel.commands.toggleSingle(eid)
                }
            }
        }

        val editDrag = bindEditDrag(button, tile.id)
        button.setOnTouchListener { _, event ->
            if (isEditMode) editDrag.onTouch(event) else false
        }
    }

    private fun bindEditDrag(view: View, tileId: String): TileEditDragHandler {
        editDragHandlers.remove(view)?.dispose()
        return TileEditDragHandler(view, handler, screenW, screenH,
            onTap = { host.editTile(tileId) },
            onDrop = { x, y -> savePosition(tileId, x, y) }
        ).also { editDragHandlers[view] = it }
    }

    private fun savePosition(id: String, x: Int, y: Int) {
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

    fun collapseAll() {
        collapseSensor()
        collapseChildButtons()
    }

    private fun clearSensorExpansion() {
        sensorWindowInput.clear()
        weatherForecastScroll.clear()
        sensorExpansion.clear()
    }

    fun clearPanelInteractions() {
        renderedViews.clear()
        dependencyIndex = PanelDependencyIndex(emptyList())
        panelEntityTiles = null
        panelEntityMotionSensor = null
        panelEntityIds = emptySet()
        // Этот Handler принадлежит только Activity и обслуживает жесты старой панели.
        handler.removeCallbacksAndMessages(null)
        longPressRunnable = null
        collapseChildrenRunnable = null
        clearSensorExpansion()
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
            if (child is Button && child.tag == gid) renderTileButton(child, tile)
        }
    }

    private fun toggleGroup(gid: String) {
        // Ни одна команда не отправляется до успешной проверки текущего состава.
        val tile = tileManager.getAllTiles().find { it.id == gid && it.type == "group" } ?: return
        val group = groupForAction(tile) ?: return
        viewModel.commands.toggleGroup(gid, group.entityIds)
    }

    fun renderCommandState(eid: String) {
        renderControls(currentIndex().affected(listOf(eid)), setOf(eid))
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

    private fun viewForTile(id: String): View? {
        val cached = renderedViews[id]
        if (cached?.parent === bottomPanel) return cached
        // Проверяем фактическое дерево, если View создан/заменён вне обычного refresh.
        // Сохраняем только прямых живых детей; старые Views не остаются в кеше.
        renderedViews.clear()
        for (i in 0 until bottomPanel.childCount) {
            val child = bottomPanel.getChildAt(i)
            (child.tag as? String)?.let { renderedViews[it] = child }
        }
        return renderedViews[id]
    }

    private fun renderControls(tiles: List<TileEntity>, changed: Set<String>) {
        tiles.forEach { tile ->
            val button = viewForTile(tile.id) as? Button ?: return@forEach
            if (tile.type == "button" || tile.type == "group") renderTileButton(button, tile)
        }
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
        val weather = currentIndex().affected(listOf(eid)).filter { it.type == "weather" }
        contentController.updateTiles(weather, ::viewForTile)
    }

    fun updateClockViews() = contentController.updateClockViews()
    fun updateWeatherViews() = contentController.updateWeatherViews()
}
