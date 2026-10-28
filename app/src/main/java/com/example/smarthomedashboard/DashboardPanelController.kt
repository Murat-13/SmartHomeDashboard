package com.example.smarthomedashboard

import android.content.ContextWrapper

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt
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
import org.json.JSONObject
import java.util.UUID
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import android.util.Log
import android.graphics.Typeface

/** Дерево панели и его обработчики. Живёт ровно столько же, сколько экран. */
internal class DashboardPanelController(
    private val activity: Activity,
    private val viewModel: MainViewModel,
    private val host: Host
) : ContextWrapper(activity) {
    interface Host {
        val panel: FrameLayout
        val dim: View
        val hasPanel: Boolean
        val hasDim: Boolean
        val tiles: TileManager
        val weather: YandexWeatherManager
        val temperatureUnit: String
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
    private val yandexWeatherManager get() = host.weather
    private val tempUnit get() = host.temperatureUnit
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
    private val entityAttributes get() = viewModel.devices.entityAttributes
    private val syntheticForecasts get() = viewModel.devices.syntheticForecasts
    private var isEditMode: Boolean
        get() = viewModel.uiState.value.isEditMode
        set(value) { viewModel.setEditMode(value) }

    private val expandedChildButtons = mutableListOf<Button>()
    private val closingChildButtons = mutableSetOf<Button>()
    private var expandedGroupId: String? = null
    private var expandedSourceButton: Button? = null
    private var expandedTile: TileEntity? = null

    private val sensorExpansion = SensorExpansionSession(handler)
    private val expandedSensorView: View? get() = sensorExpansion.current
    private val weatherForecastScroll = WeatherForecastScrollState()
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

    fun findViewAt(x: Float, y: Float): View? {
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
        }

        // Кнопки
        tiles.forEach { tile ->
            val btn = createTileButton(tile)
            val sz = getButtonSize(tile)
            btn.layoutParams = FrameLayout.LayoutParams(sz, sz)
            placeTile(btn, tile.x.toFloat(), tile.y.toFloat(), sz, sz)
            bottomPanel.addView(btn)
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
        updateSensorDisplay()
        updateClockViews()
        host.updateHint()
    }

    fun getButtonSize(tile: TileEntity): Int =
        TilePlacement.displaySize("bottom_panel", tile.config, screenW, screenH).width

    fun placeTile(view: View, x: Float, y: Float, width: Int = view.width, height: Int = view.height) {
        val (safeX, safeY) = TilePlacement.position(x, y, width, height, screenW, screenH)
        view.x = safeX
        view.y = safeY
    }

    fun createSensorView(tile: TileEntity): View {
        val (savedWidth, savedHeight) = TilePlacement.displaySize("grid", tile.config, screenW, screenH)

        val card = SensorCardFactory.createBase(activity, tile.id, tile.type, savedWidth, savedHeight)
        if (tile.type == "battery") {
            // Устанавливаем заполнение из уже известных состояний, не дожидаясь нового события HA.
            updateBatteryWidget(tile, card)
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

    fun saveSensorSize(tileId: String, width: Int, height: Int): Boolean {
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

    fun expandSensor(tile: TileEntity, source: View) {
        val original = SensorExpansionSession.Bounds.of(source)

        val idsToShow = getExpandedSensorIds(tile)

        // Обновляем содержимое виджета (табличный вид)
        val container = source.findViewWithTag<LinearLayout>("sensor_text_container")
        if (container != null) {
            if (tile.type == "weather") {
                buildWeatherDetailedContent(tile, container)
            } else {
                buildSensorContent(tile, idsToShow, true, container)
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

    fun resetSensorCollapseTimer() = sensorExpansion.touch()

    fun collapseSensor() {
        val source = expandedSensorView ?: return
        sensorWindowInput.clear()
        weatherForecastScroll.clear()
        val tile = tileManager.getAllTiles().find { it.id == source.tag as? String }
        if (tile == null) {
            clearSensorExpansion()
            return
        }
        val target = checkNotNull(sensorExpansion.original)
        val idsToShow = getCollapsedSensorIds(tile)

        val container = source.findViewWithTag<LinearLayout>("sensor_text_container")
        if (container != null) {
            if (tile.type == "weather") {
                // В начале сворачивания высота View ещё развёрнутая; шрифт нужен для исходной высоты.
                SensorCardFactory.buildCollapsedWeatherContent(activity, container, target.height)
            } else {
                buildSensorContent(tile, idsToShow, false, container)
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
            updateWeatherViews()
        }
    }

    fun setupSensorTouch(card: View, tile: TileEntity) {
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

    fun createTileButton(tile: TileEntity): Button {
        val sz = getButtonSize(tile)
        val on = "#8033CC33".toColorInt()
        val off = "#424242".toColorInt()
        val initialColor = if (tile.type == "group") {
            if (TileButtonConfig.group(tile.config) is ButtonConfigResult.Invalid) off
            else if (getGroupState(tile.id)) on else off
        } else {
            val eid = buttonEntityId(tile)
            if (eid != null && singleStates[eid] == "on") on else off
        }

        return TileButtonFactory.create(activity, tile.title, tile.id, sz, initialColor).apply {
            if (tile.type == "group") {
                setupGroupTouch(tile, this)
            } else {
                setupButtonTouch(tile, this, on, off)
            }
        }
    }

    fun buttonEntityId(tile: TileEntity, reportError: Boolean = false): String? {
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

    fun groupForAction(tile: TileEntity): TileButtonConfig.Group? {
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

    fun showButtonConfigError() {
        Toast.makeText(activity, "Не удалось прочитать конфигурацию плитки. Откройте настройки.",
            Toast.LENGTH_SHORT).show()
    }

    fun setupGroupTouch(tile: TileEntity, button: Button) {
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

    fun setupButtonTouch(
        tile: TileEntity,
        button: Button,
        activeColor: Int,
        inactiveColor: Int
    ) {
        button.setOnClickListener {
            if (!isEditMode) {
                val eid = buttonEntityId(tile, reportError = true)
                if (eid != null) {
                    val newState = viewModel.commands.toggleSingle(eid)
                    button.background?.setTint(if (newState == "on") activeColor else inactiveColor)
                }
            }
        }

        val editDrag = bindEditDrag(button, tile.id)
        button.setOnTouchListener { _, event ->
            if (isEditMode) editDrag.onTouch(event) else false
        }
    }

    fun bindEditDrag(view: View, tileId: String): TileEditDragHandler {
        editDragHandlers.remove(view)?.dispose()
        return TileEditDragHandler(view, handler, screenW, screenH,
            onTap = { host.editTile(tileId) },
            onDrop = { x, y -> savePosition(tileId, x, y) }
        ).also { editDragHandlers[view] = it }
    }

    fun savePosition(id: String, x: Int, y: Int) {
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

    fun expandChildButtons(tile: TileEntity, src: Button) {
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
            val st = groupStates[tile.id]?.get(eid) ?: "off"
            val nm = group.childNames[i]

            val btn = Button(activity).apply {
                text = nm
                tag = eid
                layoutParams = FrameLayout.LayoutParams(bs, bs)
                alpha = 0f; scaleX = 0.5f; scaleY = 0.5f; elevation = 12f
                background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null)
                background?.setTint(if (st == "on") "#8033CC33".toColorInt() else "#424242".toColorInt())
                setTextColor("#FFFFFF".toColorInt()); textSize = 14f
                gravity = Gravity.CENTER; isAllCaps = false
                setOnClickListener {
                    val n = viewModel.commands.toggleMember(tile.id, eid)
                    background?.setTint(if (n == "on") "#8033CC33".toColorInt() else "#424242".toColorInt())
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

    fun resetCollapseTimer() {
        collapseChildrenRunnable?.let { handler.removeCallbacks(it) }
        collapseChildrenRunnable = Runnable { collapseChildButtons() }
        handler.postDelayed(collapseChildrenRunnable!!, 10000L)
    }

    fun collapseChildButtons() {
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

    fun clearSensorExpansion() {
        sensorWindowInput.clear()
        weatherForecastScroll.clear()
        sensorExpansion.clear()
    }

    fun clearPanelInteractions() {
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

    fun updateGroupState(gid: String, eid: String, st: String) {
        viewModel.updateGroupState(gid, eid, st)
        updateGroupButtonAppearance(gid)
    }

    fun getGroupState(gid: String): Boolean {
        val tile = tileManager.getAllTiles().find { it.id == gid && it.type == "group" } ?: return false
        val result = TileButtonConfig.group(tile.config)
        if (result !is ButtonConfigResult.Valid) return false
        return viewModel.groupIsOn(gid, result.value.entityIds)
    }

    fun updateGroupButtonAppearance(gid: String) {
        val c = if (getGroupState(gid)) "#8033CC33".toColorInt() else "#424242".toColorInt()
        for (i in 0 until bottomPanel.childCount) {
            val ch = bottomPanel.getChildAt(i)
            if (ch is Button && ch.tag == gid) { ch.background?.setTint(c); return }
        }
    }

    fun toggleGroup(gid: String) {
        // Ни одна команда не отправляется до успешной проверки текущего состава.
        val tile = tileManager.getAllTiles().find { it.id == gid && it.type == "group" } ?: return
        val group = groupForAction(tile) ?: return
        viewModel.commands.toggleGroup(gid, group.entityIds)
    }

    fun updateSingleButtonColor(eid: String) {
        val c = if (singleStates[eid] == "on") "#8033CC33".toColorInt() else "#424242".toColorInt()
        for (i in 0 until bottomPanel.childCount) {
            val ch = bottomPanel.getChildAt(i)
            if (ch is Button) {
                val tile = (ch.tag as? String)?.let { tileManager.getAllTiles().find { t -> t.id == it } }
                if (tile != null && tile.type != "group") {
                    if (buttonEntityId(tile) == eid) {
                        ch.background?.setTint(c)
                    }
                }
            }
        }
    }

    fun updateClockViews() {
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
        for (i in 0 until bottomPanel.childCount) {
            val v = bottomPanel.getChildAt(i)
            v.findViewWithTag<TextView>("clock_text")?.text = time
            v.findViewWithTag<TextView>("clock_date")?.text = date
        }
        // Тик часов не приносит новых данных подробного прогноза.
        updateWeatherViews(refreshExpanded = false)
    }

    fun updateWeatherViews() {
        updateWeatherViews(refreshExpanded = true)
    }

    fun updateWeatherViews(refreshExpanded: Boolean) {
        val useYandex = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getBoolean("use_yandex_weather", false)

        for (i in 0 until bottomPanel.childCount) {
            val v = bottomPanel.getChildAt(i)
            val tag = v.tag as? String ?: continue
            val tile = tileManager.getAllTiles().find { it.id == tag } ?: continue
            if (tile.type == "weather") {
                val container = v.findViewWithTag<LinearLayout>("sensor_text_container")
                if (container != null && expandedSensorView == v) {
                    if (refreshExpanded) buildWeatherDetailedContent(tile, container)
                    continue
                }

                if (useYandex) {
                    CompactWeatherRenderer.renderYandex(v, yandexWeatherManager.currentTemp,
                        yandexWeatherManager.getWeatherIcon(yandexWeatherManager.currentState),
                        yandexWeatherManager.translateCondition(yandexWeatherManager.currentState))
                } else {
                    CompactWeatherRenderer.renderHomeAssistant(v, tile, singleStates::get, entityAttributes::get)
                }
            }
        }
    }

    fun buildWeatherDetailedContent(tile: TileEntity, container: LinearLayout) {
        val useYandex = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getBoolean("use_yandex_weather", false)
        val source = if (useYandex) "yandex" else "ha:${WeatherEntityResolver.resolve(tile.config)}"
        weatherForecastScroll.rebuild(container, tile.id, source) {
            if (useYandex) {
                buildYandexWeatherDetailedContent(tile, container)
            } else {
                HomeAssistantWeatherDetailsRenderer.render(
                    activity,
                    tile,
                    container,
                    entityAttributes::get,
                    singleStates::get,
                    ::resetSensorCollapseTimer
                )
            }
        }
    }

    fun buildYandexWeatherDetailedContent(tile: TileEntity, container: LinearLayout) {
        YandexWeatherDetailsRenderer.render(
            activity,
            container,
            yandexWeatherManager,
            ::resetSensorCollapseTimer
        )
    }

    fun updateSensorDisplay() {
        val sensorTiles = tileManager.getTilesByContainer("grid")
        for (i in 0 until bottomPanel.childCount) {
            val child = bottomPanel.getChildAt(i)
            val tag = child.tag as? String ?: continue
            val tile = sensorTiles.find { it.id == tag } ?: continue

            if (child is FrameLayout) {
                val isExpanded = (expandedSensorView == child)

                val idsToShow = if (isExpanded) {
                    getExpandedSensorIds(tile)
                } else {
                    getCollapsedSensorIds(tile)
                }

                // Погодное дерево обновляется отдельно, обычные строки не должны его перестраивать.
                val container = child.findViewWithTag<LinearLayout>("sensor_text_container")
                if (container != null && tile.type != "clock" && tile.type != "weather") {
                    buildSensorContent(tile, idsToShow, isExpanded, container)
                }

                if (tile.type == "clock") {
                    updateClockViews()
                } else if (tile.type == "battery") {
                    updateBatteryWidget(tile, child)
                } else {
                    updateNormalSensorWidget(tile, child)
                }
            }
        }
    }

    fun updateBatteryWidget(tile: TileEntity, view: FrameLayout) {
        BatteryWidgetRenderer.render(tile, view, singleStates::get)
    }

    fun updateNormalSensorWidget(tile: TileEntity, child: FrameLayout) {
        SensorBackgroundRenderer.render(tile, child, singleStates::get)
    }

    fun getCollapsedSensorIds(tile: TileEntity): List<String> {
        return SensorConfigResolver.getCollapsedSensorIds(tile)
    }

    fun getExpandedSensorIds(tile: TileEntity): List<String> {
        return SensorConfigResolver.getExpandedSensorIds(tile)
    }

    fun buildSensorContent(tile: TileEntity, entityIds: List<String>, isExpanded: Boolean, container: LinearLayout) {
        SensorContentRenderer.render(
            tile,
            entityIds,
            isExpanded,
            container,
            ::loadSensorConfigs,
            singleStates::get,
            ::formatSensorValue
        )
    }

    fun loadSensorConfigs(tile: TileEntity, isExpanded: Boolean): List<SensorConfig> {
        return SensorConfigResolver.loadSensorConfigs(tile, isExpanded)
    }

    fun formatSensorValue(value: String, decimals: Int, entityId: String? = null): String {
        val unit = if (entityId != null) {
            entityAttributes[entityId]?.optString("unit_of_measurement") ?: ""
        } else {
            ""
        }
        return SensorValueFormatter.formatSensorValue(value, decimals, unit, tempUnit)
    }

    fun isShownOnPanel(eid: String): Boolean {
        // TileManager возвращает тот же список, пока плитки не менялись.
        val tiles = tileManager.getAllTiles()
        if (tiles !== panelEntityTiles || motionSensorId != panelEntityMotionSensor) {
            panelEntityIds = PanelEntityIds.collect(tiles, listOfNotNull(motionSensorId))
            panelEntityTiles = tiles
            panelEntityMotionSensor = motionSensorId
        }
        return eid in panelEntityIds
    }

    fun updateTilesForEntity(eid: String, st: String, attrs: JSONObject? = null) {
        viewModel.onEntityStateChanged(eid, st, attrs)
        renderEntityState(eid, st)
    }

    fun renderEntityState(eid: String, st: String) {

        // Состояние храним для всех сущностей: плитка, добавленная позже, сразу покажет значение.
        // А перерисовка нужна только для тех, которые читает панель; HA шлёт события всего дома.
        if (!isShownOnPanel(eid)) return

        // Полное обновление погоды выполняется один раз ниже через updateWeatherViews.

        if (eid == motionSensorId) {
            if (st == "on" || st == "true" || st == "playing") {
                host.wake()
            }
        }

        // Цвет определяется фактическим источником кнопки независимо от домена HA.
        updateSingleButtonColor(eid)
        if (!eid.startsWith("weather.")) {
            // На плитке может быть любая сущность (binary_sensor, input_number, climate),
            // а не только sensor.*: посторонние события уже отсеяны выше.
            updateSensorDisplay()
        }
        updateWeatherViews()

        tileManager.getAllTiles().filter { it.type == "group" }.forEach { t ->
            val result = TileButtonConfig.group(t.config)
            if (result is ButtonConfigResult.Valid && eid in result.value.entityIds) {
                updateGroupState(t.id, eid, st); updateGroupButtonAppearance(t.id)
            }
        }
    }

    fun updateForecastForEntity(eid: String, forecast: JSONArray?) {
        viewModel.onForecastChanged(eid, forecast)
        renderForecastState(eid)
    }

    fun renderForecastState(eid: String) {
        updateWeatherViews()
    }

    fun formatFloat(v: String, d: Int): String {
        return SensorValueFormatter.formatFloat(v, d)
    }
}
