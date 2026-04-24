package com.example.smarthomedashboard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var widgetsGrid: RecyclerView
    private lateinit var bottomPanel: FrameLayout
    private lateinit var overlayContainer: FrameLayout
    private lateinit var dimOverlay: View
    private lateinit var gridAdapter: WidgetAdapter

    private lateinit var tileManager: TileManager
    private var webSocket: HomeAssistantWebSocket? = null

    private var pzemVoltage = "—"
    private var pzemCurrent = "—"
    private var pzemPower = "—"
    private var pzemEnergy = "—"
    private var pzemFrequency = "—"
    private var gridOnline = true
    private var techRoomTemp = "—"

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var collapseChildrenRunnable: Runnable? = null

    private val groupStates = mutableMapOf<String, MutableMap<String, String>>()
    private val singleStates = mutableMapOf<String, String>()
    private var isEditMode = false

    private val expandedChildButtons = mutableListOf<Button>()
    private var expandedGroupId: String? = null
    private var expandedSourceButton: Button? = null
    private var expandedTile: TileEntity? = null

    companion object {
        private const val REQUEST_TILE_SETTINGS = 100
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_main)

        widgetsGrid = findViewById(R.id.widgetsGrid)
        bottomPanel = findViewById(R.id.bottomPanel)
        overlayContainer = findViewById(R.id.overlayContainer)
        dimOverlay = findViewById(R.id.dimOverlay)
        dimOverlay.setOnClickListener { collapseAll() }

        tileManager = TileManager(this)
        setupGrid()
        setupBottomPanel()
        setupWebSocket()
        setupLongPressOnEmptySpace()
    }

    override fun onResume() {
        super.onResume()
        refreshGrid()
        refreshBottomPanel()
        subscribeToNeededEntities()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        webSocket?.disconnect()
    }

    private fun openWithPinCheck(action: () -> Unit) {
        val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
        val lastAuth = prefs.getLong("last_auth_time", 0L)
        if (System.currentTimeMillis() - lastAuth > 60 * 60 * 1000) {
            PinDialog(this) {
                prefs.edit { putLong("last_auth_time", System.currentTimeMillis()) }
                action()
            }.show()
        } else action()
    }

    private fun setupGrid() {
        widgetsGrid.layoutManager = GridLayoutManager(this, 4)
        if (tileManager.getTilesByContainer("grid").isEmpty()) createDefaultGridTiles()
        refreshGrid()
    }

    private fun refreshGrid() {
        val items = tileManager.getTilesByContainer("grid").map { it.toWidgetItem() }.toMutableList()
        gridAdapter = WidgetAdapter(
            context = this, widgets = items, overlayContainer = overlayContainer,
            recyclerView = widgetsGrid,
            onDataUpdate = { WidgetData(pzemVoltage, pzemCurrent, pzemPower, pzemEnergy, pzemFrequency, gridOnline) },
            isEditMode = isEditMode,
            onTileClick = { tileId -> if (isEditMode) openTileSettings(tileId) }
        )
        widgetsGrid.adapter = gridAdapter
    }

    private fun setupBottomPanel() { refreshBottomPanel() }

    private fun refreshBottomPanel() {
        bottomPanel.removeAllViews()
        var tiles = tileManager.getTilesByContainer("bottom_panel")
        if (tiles.isEmpty()) { createDefaultButtonTiles(); tiles = tileManager.getTilesByContainer("bottom_panel") }
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        tiles.forEach { tile ->
            val btn = createTileButton(tile)
            val sz = getButtonSize(tile)
            btn.layoutParams = FrameLayout.LayoutParams(sz, sz)
            btn.x = tile.x.toFloat().coerceIn(0f, (sw - sz).toFloat())
            btn.y = tile.y.toFloat().coerceIn(0f, (sh - sz - 60).toFloat())
            bottomPanel.addView(btn)
        }
        val btnS = createSettingsButton()
        btnS.layoutParams = FrameLayout.LayoutParams(160, 160)
        btnS.x = (sw - 180).toFloat(); btnS.y = (sh - 240).toFloat()
        bottomPanel.addView(btnS)
        if (isEditMode) {
            val btnA = createAddButton()
            btnA.layoutParams = FrameLayout.LayoutParams(160, 160)
            btnA.x = (sw - 360).toFloat(); btnA.y = (sh - 240).toFloat()
            bottomPanel.addView(btnA)
        }
    }

    private fun getButtonSize(tile: TileEntity) = try { JSONObject(tile.config).optInt("button_size", 160) } catch (_: Exception) { 160 }

    // ==================== СОЗДАНИЕ КНОПОК ====================

    private fun createTileButton(tile: TileEntity): Button {
        val sz = getButtonSize(tile)
        val on = "#8033CC33".toColorInt()
        val off = "#424242".toColorInt()
        return Button(this).apply {
            text = tile.title
            layoutParams = FrameLayout.LayoutParams(sz, sz)
            tag = tile.id; alpha = 0.8f; elevation = 8f
            background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null)
            if (tile.type == "group") background?.setTint(if (getGroupState(tile.id)) on else off)
            else { val e = JSONObject(tile.config).optString("entity_id", ""); background?.setTint(if (singleStates[e] == "on") on else off) }
            setTextColor("#FFFFFF".toColorInt()); textSize = 14f; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; isAllCaps = false

            // ГРУППА
            if (tile.type == "group") {
                var pr: Runnable? = null
                var sx = 0f; var sy = 0f; var hm = false; var dr = false; var vx = 0f; var vy = 0f
                setOnTouchListener { view, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            hm = false; dr = false; sx = event.rawX; sy = event.rawY; vx = view.x; vy = view.y
                            if (isEditMode) { pr = Runnable { dr = true; view.alpha = 0.6f; view.elevation = 20f }; handler.postDelayed(pr!!, 500L) }
                            else { pr = Runnable { if (expandedGroupId == tile.id) collapseChildButtons() else expandChildButtons(tile, view as Button) }; handler.postDelayed(pr!!, 1000L) }
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (kotlin.math.abs(event.rawX - sx) > 10 || kotlin.math.abs(event.rawY - sy) > 10) { hm = true; pr?.let { handler.removeCallbacks(it) } }
                            if (isEditMode && dr) { view.x = (vx + event.rawX - sx).coerceIn(0f, (resources.displayMetrics.widthPixels - view.width).toFloat()); view.y = (vy + event.rawY - sy).coerceIn(0f, (resources.displayMetrics.heightPixels - view.height - 60).toFloat()) }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            view.performClick(); pr?.let { handler.removeCallbacks(it) }
                            if (isEditMode && dr && hm) { view.alpha = 1.0f; view.elevation = 8f; saveButtonPosition(tile.id, view.x.toInt(), view.y.toInt()) }
                            else if (isEditMode && !hm) openTileSettings(tile.id)
                            else if (!isEditMode && !hm && expandedGroupId != tile.id) toggleGroup(tile.id, tile)
                            true
                        }
                        else -> false
                    }
                }
            }

            // ОБЫЧНАЯ КНОПКА
            if (tile.type != "group") {
                setOnClickListener {
                    if (!isEditMode) {
                        val e = JSONObject(tile.config).optString("entity_id", "")
                        if (e.isNotEmpty()) { val d = e.substringBefore("."); val c = singleStates[e] ?: "off"; val t = if (c == "on") "turn_off" else "turn_on"; webSocket?.callService(d, t, e); val n = if (c == "on") "off" else "on"; singleStates[e] = n; background?.setTint(if (n == "on") on else off) }
                    }
                }
                var dsx = 0f; var dsy = 0f; var vsx = 0f; var vsy = 0f; var idr = false; var mvd = false; var drr: Runnable? = null
                setOnTouchListener { view, event ->
                    if (!isEditMode) return@setOnTouchListener false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            mvd = false; dsx = event.rawX; dsy = event.rawY; vsx = view.x; vsy = view.y; idr = false
                            drr = Runnable { idr = true; view.alpha = 0.6f; view.elevation = 20f }; handler.postDelayed(drr!!, 500L); true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (kotlin.math.abs(event.rawX - dsx) > 10 || kotlin.math.abs(event.rawY - dsy) > 10) { mvd = true; drr?.let { handler.removeCallbacks(it) } }
                            if (idr) { view.x = (vsx + event.rawX - dsx).coerceIn(0f, (resources.displayMetrics.widthPixels - view.width).toFloat()); view.y = (vsy + event.rawY - dsy).coerceIn(0f, (resources.displayMetrics.heightPixels - view.height - 60).toFloat()) }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            view.performClick(); drr?.let { handler.removeCallbacks(it) }
                            if (idr && mvd) { view.alpha = 1.0f; view.elevation = 8f; saveButtonPosition(tile.id, view.x.toInt(), view.y.toInt()) }
                            else if (!mvd) openTileSettings(tile.id)
                            true
                        }
                        else -> false
                    }
                }
            }
        }
    }

    private fun saveButtonPosition(id: String, x: Int, y: Int) { val t = tileManager.loadTiles().toMutableList(); val i = t.indexOfFirst { it.id == id }; if (i >= 0) { t[i] = t[i].copy(x = x, y = y); tileManager.saveTiles(t) } }

    private fun createSettingsButton(): Button = Button(this).apply { text = "⚙"; alpha = 0.8f; elevation = 8f; background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null); background?.setTint("#424242".toColorInt()); setTextColor("#FFFFFF".toColorInt()); textSize = 28f; gravity = Gravity.CENTER; setOnClickListener { openWithPinCheck { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } } }
    private fun createAddButton(): Button = Button(this).apply { text = "+"; alpha = 0.8f; elevation = 8f; background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null); background?.setTint("#4CAF50".toColorInt()); setTextColor("#FFFFFF".toColorInt()); textSize = 32f; gravity = Gravity.CENTER; setOnClickListener { startActivityForResult(Intent(this@MainActivity, TileSettingsActivity::class.java).putExtra("container", "grid"), REQUEST_TILE_SETTINGS) } }

    // ==================== ВЫЕЗЖАНИЕ ====================
    private fun expandChildButtons(tile: TileEntity, src: Button) {
        collapseChildButtons()
        val cfg = JSONObject(tile.config); val ids = cfg.optJSONArray("entity_ids") ?: return
        expandedGroupId = tile.id; expandedSourceButton = src; expandedTile = tile
        val bs = getButtonSize(tile); val sw = resources.displayMetrics.widthPixels
        val loc = IntArray(2); src.getLocationOnScreen(loc); val cx = loc[0] + src.width / 2; val top = loc[1]
        val cnt = ids.length(); val max = 5; val fst = if (cnt <= max) cnt else (cnt + 1) / 2; val snd = cnt - fst
        var s1 = cx - fst * (bs + 12) / 2; var s2 = cx - snd * (bs + 12) / 2
        if (s1 < 0) s1 = 8; if (s1 + fst * (bs + 12) > sw) s1 = sw - fst * (bs + 12) - 8
        if (s2 < 0) s2 = 8; if (s2 + snd * (bs + 12) > sw) s2 = sw - snd * (bs + 12) - 8
        val rws = if (cnt <= max) 1 else 2; val fy = top - (bs + 12) * rws - 12; val sy = fy + bs + 12
        val afy = if (fy < 50) top + src.height + 12 else fy; val asy = if (fy < 50) afy + bs + 12 else sy
        expandedChildButtons.clear(); val rt = findViewById<FrameLayout>(android.R.id.content)
        for (i in 0 until cnt) {
            val eid = ids.getString(i); val st = groupStates[tile.id]?.get(eid) ?: "off"
            val nms = cfg.optJSONArray("child_names"); val nm = if (nms != null && i < nms.length()) nms.getString(i) else (i + 1).toString()
            val btn = Button(this).apply {
                text = nm; tag = eid; layoutParams = FrameLayout.LayoutParams(bs, bs); alpha = 0f; scaleX = 0.5f; scaleY = 0.5f; elevation = 12f
                background = ResourcesCompat.getDrawable(resources, R.drawable.bg_button_rounded, null)
                background?.setTint(if (st == "on") "#8033CC33".toColorInt() else "#424242".toColorInt())
                setTextColor("#FFFFFF".toColorInt()); textSize = 14f; gravity = Gravity.CENTER; isAllCaps = false
                setOnClickListener { val d = eid.substringBefore("."); val c = groupStates[tile.id]?.get(eid) ?: "off"; val t = if (c == "on") "turn_off" else "turn_on"; webSocket?.callService(d, t, eid); val n = if (c == "on") "off" else "on"; background?.setTint(if (n == "on") "#8033CC33".toColorInt() else "#424242".toColorInt()); updateGroupState(tile.id, eid, n); resetCollapseTimer() }
            }
            val r = if (i < fst) 0 else 1; val c = if (r == 0) i else i - fst
            btn.x = (if (r == 0) s1 + c * (bs + 12) else s2 + c * (bs + 12)).toFloat(); btn.y = (if (r == 0) afy else asy).toFloat()
            rt.addView(btn); expandedChildButtons.add(btn)
            btn.animate().alpha(0.8f).scaleX(1f).scaleY(1f).setDuration(250).setStartDelay(i * 50L).setInterpolator(DecelerateInterpolator()).start()
        }
        dimOverlay.isVisible = true; dimOverlay.animate().alpha(0.4f).setDuration(300).start(); resetCollapseTimer()
    }

    private fun resetCollapseTimer() { collapseChildrenRunnable?.let { handler.removeCallbacks(it) }; collapseChildrenRunnable = Runnable { collapseChildButtons() }; handler.postDelayed(collapseChildrenRunnable!!, 10000L) }
    private fun collapseChildButtons() { expandedGroupId = null; expandedSourceButton = null; expandedTile = null; val rt = findViewById<FrameLayout>(android.R.id.content); expandedChildButtons.forEachIndexed { i, b -> b.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(200).setStartDelay(i * 30L).setInterpolator(DecelerateInterpolator()).withEndAction { rt.removeView(b) }.start() }; expandedChildButtons.clear(); dimOverlay.animate().alpha(0f).setDuration(300).withEndAction { dimOverlay.isVisible = false }.start(); collapseChildrenRunnable?.let { handler.removeCallbacks(it) } }
    private fun collapseAll() { collapseChildButtons() }

    // ==================== ГРУППЫ ====================
    private fun updateGroupState(gid: String, eid: String, st: String) { if (!groupStates.containsKey(gid)) groupStates[gid] = mutableMapOf(); groupStates[gid]?.put(eid, st); updateGroupButtonAppearance(gid) }
    private fun getGroupState(gid: String) = groupStates[gid]?.values?.any { it == "on" } ?: false
    private fun updateGroupButtonAppearance(gid: String) { val c = if (getGroupState(gid)) "#8033CC33".toColorInt() else "#424242".toColorInt(); for (i in 0 until bottomPanel.childCount) { val ch = bottomPanel.getChildAt(i); if (ch is Button && ch.tag == gid) { ch.background?.setTint(c); return } } }
    private fun toggleGroup(gid: String, tile: TileEntity) { try { val ids = JSONObject(tile.config).optJSONArray("entity_ids") ?: return; val ts = if (getGroupState(gid)) "turn_off" else "turn_on"; for (i in 0 until ids.length()) webSocket?.callService(ids.getString(i).substringBefore("."), ts, ids.getString(i)) } catch (_: Exception) {} }

    // ==================== ОБЫЧНЫЕ КНОПКИ ====================
    private fun updateSingleButtonColor(eid: String) { val c = if (singleStates[eid] == "on") "#8033CC33".toColorInt() else "#424242".toColorInt(); for (i in 0 until bottomPanel.childCount) { val ch = bottomPanel.getChildAt(i); if (ch is Button) { val t = (ch.tag as? String)?.let { tileManager.getAllTiles().find { t -> t.id == it } }; if (t != null && t.type != "group") try { if (JSONObject(t.config).optString("entity_id", "") == eid) ch.background?.setTint(c) } catch (_: Exception) {} } } }

    // ==================== РЕДАКТИРОВАНИЕ ====================
    @SuppressLint("ClickableViewAccessibility")
    private fun setupLongPressOnEmptySpace() { widgetsGrid.setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_DOWN && widgetsGrid.findChildViewUnder(ev.x, ev.y) == null) { if (isEditMode) exitEditMode() else { longPressRunnable = Runnable { openWithPinCheck { enterEditMode() } }; handler.postDelayed(longPressRunnable!!, 1000L) } } else if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) { longPressRunnable?.let { handler.removeCallbacks(it) }; widgetsGrid.performClick() }; false } }
    private fun enterEditMode() { isEditMode = true; gridAdapter.setEditMode(true); refreshBottomPanel(); Toast.makeText(this, "Режим редактирования", Toast.LENGTH_SHORT).show() }
    private fun exitEditMode() { isEditMode = false; gridAdapter.setEditMode(false); refreshBottomPanel() }
    private fun openTileSettings(id: String) { openWithPinCheck { startActivityForResult(Intent(this, TileSettingsActivity::class.java).putExtra("tile_id", id), REQUEST_TILE_SETTINGS) } }

    private fun createDefaultGridTiles() { lifecycleScope.launch { tileManager.addTile(TileEntity(UUID.randomUUID().toString(), "sensor", "grid", "⚡ Сеть", 0, 0, 1, 1, config = "{}")); tileManager.addTile(TileEntity(UUID.randomUUID().toString(), "sensor", "grid", "🌡️ Температура", 1, 0, 1, 1, config = "{}")) } }
    private fun createDefaultButtonTiles() { val sh = resources.displayMetrics.heightPixels; lifecycleScope.launch { tileManager.addTile(TileEntity(UUID.randomUUID().toString(), "button", "bottom_panel", "💡 Свет", 20, sh - 250, 1, 1, config = """{"entity_id":"switch.sonoff_100288c9c3_1","button_size":160}""")); tileManager.addTile(TileEntity(UUID.randomUUID().toString(), "button", "bottom_panel", "🔥 Бойлер", 200, sh - 250, 1, 1, config = """{"entity_id":"switch.boiler","button_size":160}""")) } }

    private fun updatePzemWidget() { gridAdapter.updatePzemData(pzemVoltage, pzemCurrent, pzemPower, pzemEnergy, pzemFrequency, gridOnline) }
    private fun updateTemperatureWidget() { gridAdapter.updateTemperatureData(techRoomTemp); gridAdapter.updateWidgetByEntityId("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia", techRoomTemp) }
    private fun updateGridStatus() { gridOnline = (pzemVoltage.toFloatOrNull() ?: 0f) > 10f }

    private fun updateTilesForEntity(eid: String, st: String) {
        singleStates[eid] = st; val fmt = formatFloat(st, 1)
        when (eid) {
            "sensor.pzem_energy_monitor_pzem_voltage" -> { pzemVoltage = formatFloat(st, 0); updateGridStatus(); updatePzemWidget(); gridAdapter.updateWidgetByEntityId(eid, pzemVoltage) }
            "sensor.pzem_energy_monitor_pzem_power" -> { pzemPower = formatFloat(st, 0); updatePzemWidget(); gridAdapter.updateWidgetByEntityId(eid, pzemPower) }
            "sensor.pzem_energy_monitor_pzem_current" -> { pzemCurrent = formatFloat(st, 2); updatePzemWidget(); gridAdapter.updateWidgetByEntityId(eid, pzemCurrent) }
            "sensor.pzem_energy_monitor_pzem_energy" -> { pzemEnergy = formatFloat(st, 2); updatePzemWidget(); gridAdapter.updateWidgetByEntityId(eid, pzemEnergy) }
            "sensor.pzem_energy_monitor_pzem_frequency" -> { pzemFrequency = formatFloat(st, 1); updatePzemWidget(); gridAdapter.updateWidgetByEntityId(eid, pzemFrequency) }
            "sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia" -> { techRoomTemp = formatFloat(st, 1); updateTemperatureWidget(); gridAdapter.updateWidgetByEntityId(eid, techRoomTemp) }
            else -> gridAdapter.updateWidgetByEntityId(eid, fmt)
        }
        if (eid.startsWith("switch.")) updateSingleButtonColor(eid)
        tileManager.getAllTiles().filter { it.type == "group" }.forEach { t -> try { val ids = JSONObject(t.config).optJSONArray("entity_ids") ?: return@forEach; for (i in 0 until ids.length()) if (ids.getString(i) == eid) { updateGroupState(t.id, eid, st); updateGroupButtonAppearance(t.id); break } } catch (_: Exception) {} }
    }
    private fun formatFloat(v: String, d: Int) = v.toFloatOrNull()?.let { String.format("%.${d}f", it) } ?: "—"

    private fun setupWebSocket() { val p = getSharedPreferences("dashboard_prefs", MODE_PRIVATE); val tkn = p.getString("ha_token", "") ?: ""; if (tkn.isEmpty()) return; val lh = p.getString("ha_local_host", "192.168.1.253:8123") ?: "192.168.1.253:8123"; val rh = p.getString("ha_remote_host", "") ?: ""; webSocket = HomeAssistantWebSocket(lh, tkn, { e, s, _ -> runOnUiThread { updateTilesForEntity(e, s) } }, { handler.postDelayed({ subscribeToNeededEntities() }, 2000L) }, { if (rh.isNotEmpty()) { webSocket = HomeAssistantWebSocket(rh, tkn, { e, s, _ -> runOnUiThread { updateTilesForEntity(e, s) } }, { handler.postDelayed({ subscribeToNeededEntities() }, 2000L) }, {}, null); webSocket?.connect() } }, { cacheEntities(it) }); webSocket?.connect() }
    private fun cacheEntities(e: List<HaEntity>) { getSharedPreferences("dashboard_prefs", MODE_PRIVATE).edit { putString("cached_entities", com.google.gson.Gson().toJson(e)); putLong("cached_entities_time", System.currentTimeMillis()) } }
    private fun subscribeToNeededEntities() { val ids = mutableSetOf<String>(); tileManager.getAllTiles().forEach { t -> try { val c = JSONObject(t.config); c.optString("entity_id", "").takeIf { it.isNotEmpty() }?.let { ids.add(it) }; c.optJSONArray("entity_ids")?.let { for (i in 0 until it.length()) ids.add(it.getString(i)) } } catch (_: Exception) {} }; if (tileManager.getAllTiles().any { it.title == "⚡ Сеть" }) { ids.add("sensor.pzem_energy_monitor_pzem_voltage"); ids.add("sensor.pzem_energy_monitor_pzem_power"); ids.add("sensor.pzem_energy_monitor_pzem_current"); ids.add("sensor.pzem_energy_monitor_pzem_energy"); ids.add("sensor.pzem_energy_monitor_pzem_frequency") }; if (ids.isNotEmpty()) webSocket?.subscribeEntities(ids.toList()) }

    override fun onActivityResult(rc: Int, rc2: Int, d: Intent?) { super.onActivityResult(rc, rc2, d); if (rc == REQUEST_TILE_SETTINGS && rc2 == Activity.RESULT_OK) { refreshGrid(); refreshBottomPanel(); subscribeToNeededEntities() } }
}

fun TileEntity.toWidgetItem(): WidgetItem { val c = JSONObject(config); var e = c.optString("entity_id", ""); if (e.isEmpty()) { val a = c.optJSONArray("entity_ids"); if (a != null && a.length() > 0) e = a.getString(0) }; return WidgetItem(title = title, value = "", entityId = e, backgroundColor = if (title == "⚡ Сеть") "#8033CC33" else "#80333333", type = type, config = c.apply { put("id", id) }) }