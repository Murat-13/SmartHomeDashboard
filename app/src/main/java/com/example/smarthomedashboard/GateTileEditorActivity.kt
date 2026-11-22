package com.example.smarthomedashboard

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.example.smarthomedashboard.data.TileManager

/**
 * Редактор плитки «Калитка или ворота» (docs/gate-tz.md, решение 3): название и какой проход она показывает. Сами
 * проходы — в «Калитки и ворота» (кнопка здесь же). Пишется только по «Сохранить».
 */
class GateTileEditorActivity : PinProtectedActivity() {
    private val kit = ModernEditorKit(this) { TileEditorPreviewSource.states?.value ?: MainUiState() }
    private lateinit var tiles: TileManager
    private var tileId = ""
    internal var gateId = ""
        private set
    private var original = ""
    private lateinit var list: LinearLayout
    internal lateinit var titleField: android.widget.EditText
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tiles = TileManager(this)
        tileId = intent.getStringExtra("tile_id").orEmpty()
        val tile = tiles.loadTiles().firstOrNull { it.id == tileId && it.type == GateTileConfig.TYPE }
        if (tile == null) { finish(); return }
        original = GateTileConfig.gateId(tile.config)
        gateId = savedInstanceState?.getString(STATE_GATE) ?: original
        setContentView(build(tile.title))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_GATE, gateId)
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) render()
    }

    override fun onDestroy() {
        kit.dispose()
        super.onDestroy()
    }

    private fun build(title: String): View {
        val pad = resources.getDimensionPixelSize(R.dimen.modern_space_l)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        column.addView(ModernUi.header(this, "Калитка или ворота"))
        column.addView(ModernUi.secondary(this, "Кнопка со статусом: удержание 1 секунду — открыть, касание — все кнопки и положение."))
        titleField = ModernUi.input(this, title, "Например: Ворота").apply { tag = "gate_tile_title_field" }
        column.addView(gap()); column.addView(ModernUi.section(this, "Название плитки", titleField))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(gap()); column.addView(ModernUi.section(this, "Проход", list,
            ModernUi.secondaryButton(this, "Калитки и ворота…") { startActivity(Intent(this, GatesActivity::class.java)) }
                .apply { tag = "gate_tile_manage" }))
        render()
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@GateTileEditorActivity, R.color.modern_background))
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@GateTileEditorActivity, R.color.modern_background))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(kit.footer("gate_tile", { leave() }, { save() }))
        }
    }

    private fun render() {
        list.removeAllViews()
        val gates = GateStore.load(this)
        if (gates.isEmpty()) {
            list.addView(ModernUi.body(this, "Проходов пока нет — добавьте в «Калитки и ворота»."))
            return
        }
        gates.forEach { gate ->
            val on = gate.id == gateId
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(kit.dp(14), kit.dp(10), kit.dp(14), kit.dp(10))
                tag = "gate_tile_pick_${gate.id}"
                isClickable = true
                isSelected = on
                contentDescription = gate.name
                setOnClickListener { gateId = gate.id; render() }
                addView(kit.label(gate.name, 16f, bold = true))
                addView(kit.label(gate.kind.title + if (gate.ready) "" else " · не настроен", 13f))
                background = BatteryModernCard.rounded(if (on) Color.argb(90, 95, 168, 211) else Color.argb(40, 30, 44, 56),
                    kit.dp(12).toFloat(), Color.argb(if (on) 220 else 70, 95, 168, 211))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = kit.dp(6); gravity = Gravity.CENTER_VERTICAL }
            })
        }
    }

    private val dirty get() = gateId != original ||
        titleField.text.toString().trim() != tiles.loadTiles().firstOrNull { it.id == tileId }?.title.orEmpty()

    internal fun save(): Boolean {
        if (!requireAdmin()) return false
        val tile = tiles.loadTiles().firstOrNull { it.id == tileId } ?: return false
        val title = titleField.text.toString().trim().ifBlank { GateStore.load(this).firstOrNull { it.id == gateId }?.name ?: tile.title }
        tiles.saveTiles(tiles.loadTiles().map {
            if (it.id == tileId) it.copy(title = title, config = GateTileConfig.withGate(it.config, gateId)) else it
        })
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
        return true
    }

    private fun leave() {
        if (!dirty) { finish(); return }
        kit.show(ModernUi.leaveDialog(this, "Настройки плитки пока не записаны.", { save() }, { finish() }))
    }

    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, resources.getDimensionPixelSize(R.dimen.modern_space_m)) }

    companion object {
        private const val STATE_GATE = "gate_tile_gate"
    }
}
