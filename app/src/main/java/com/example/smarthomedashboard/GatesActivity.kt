package com.example.smarthomedashboard

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.example.smarthomedashboard.data.TileManager

/**
 * «Калитки и ворота» (docs/gate-tz.md, решения владельца 22.11.2026): общий список проходов панели — что можно открыть
 * с панели и как. Открывается из Настроек («Устройство») и кнопкой «Управление замком» в редакторе домофона или камеры.
 * Здесь же — «PIN для открытия» (тот же PIN, что у настроек и архива; по умолчанию выключен).
 */
class GatesActivity : PinProtectedActivity() {
    private lateinit var list: LinearLayout
    private lateinit var add: View
    private val listener: () -> Unit = { renderList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GateHub.attach(applicationContext)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        GateHub.listen(listener)
        renderList()
    }

    override fun onPause() {
        GateHub.unlisten(listener)
        super.onPause()
    }

    private fun build(): View {
        val pad = resources.getDimensionPixelSize(R.dimen.modern_space_l)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        column.addView(ModernUi.header(this, "Калитки и ворота"))
        column.addView(ModernUi.secondary(this, "Что можно открыть с панели: калитка, ворота, дверь. Проход настраивается один раз, " +
            "а его кнопки показываются у отмеченных плиток домофона и камеры — в раскрытой плитке (и без звонка) и в окне " +
            "звонка. Открыть или закрыть — удержанием 1 секунду, «Стоп» — сразу."))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "gates_list" }
        add = ModernUi.primaryButton(this, "+ Добавить проход") { open(null) }.apply { tag = "gates_add" }
        column.addView(gap()); column.addView(ModernUi.section(this, "Проходы", list, add))

        val pins = GatePin.entries
        column.addView(gap()); column.addView(ModernUi.section(this, "PIN для открытия",
            ModernUi.secondary(this, "Тот же PIN, что у настроек и архива. «Вне окна звонка» — гостю у калитки открывают без PIN, " +
                "а просто так, из раскрытой плитки, — только с PIN."),
            choice("gates_pin", pins.map { it.title }, pins.indexOf(GateStore.pin(this))) { i ->
                if (requireAdmin()) GateStore.setPin(this, pins[i])
            }))
        column.addView(gap()); column.addView(ModernUi.secondary(this, "Безопасность: открывать ворота с приводом дистанционно " +
            "можно, только если их видно (камера), а у привода есть фотоэлементы и ограничение усилия. Порядок «магнит → " +
            "открыть → закрыть», автовыключение реле и автозакрытие настраиваются в самом приводе или реле."))
        column.addView(gap()); column.addView(ModernUi.secondaryButton(this, "Готово") { finish() }.apply { tag = "gates_done" })
        return ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@GatesActivity, R.color.modern_background))
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun renderList() {
        if (!::list.isInitialized) return
        val gates = GateHub.gates(this)
        val tiles = TileManager(this).loadTiles().filter { DoorbellConfig.isVideo(it.type) }
        list.removeAllViews()
        if (gates.isEmpty()) list.addView(ModernUi.body(this, "Пока ни одного. Добавьте калитку или ворота — кнопка появится у плиток, где её отметите."))
        gates.forEach { gate ->
            val shownOn = tiles.filter { DoorbellConfig.from(it.config).gateIds(it.id).contains(gate.id) }.map { it.title.ifBlank { "Домофон" } }
            val status = GateHub.status(gate)
            val lines = listOfNotNull(
                gate.kind.title,
                if (!gate.ready) "не настроен: выберите устройство" else GateLogic.statusText(status).takeIf { it.isNotBlank() }?.let { "сейчас: $it" },
                if (shownOn.isEmpty()) "не показан ни на одной плитке" else "показан: " + shownOn.joinToString(", ")
            )
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tag = "gates_item_${gate.id}"
                isClickable = true
                contentDescription = gate.name
                val d = resources.displayMetrics.density
                setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
                background = BatteryModernCard.rounded(Color.argb(50, 30, 44, 56), 12 * d, Color.argb(80, 95, 168, 211))
                addView(ModernUi.title(this@GatesActivity, gate.name))
                addView(ModernUi.caption(this@GatesActivity, lines.joinToString(" · ")))
                setOnClickListener { open(gate.id) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = (8 * d).toInt() }
            })
        }
        add.isEnabled = gates.size < Gate.MAX
    }

    private fun open(gateId: String?) {
        startActivity(Intent(this, GateEditorActivity::class.java).apply { gateId?.let { putExtra(GateEditorActivity.EXTRA_GATE, it) } })
    }

    private fun choice(tag: String, items: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val d = resources.displayMetrics.density
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.tag = tag; setPadding(0, (8 * d).toInt(), 0, 0) }
        val buttons = items.mapIndexed { index, item ->
            android.widget.Button(this).apply { text = item; isAllCaps = false; this.tag = "${tag}_$index"; minHeight = (48 * d).toInt() }
        }
        fun paint(active: Int) = buttons.forEachIndexed { i, b ->
            b.background = BatteryModernCard.rounded(if (i == active) Color.argb(90, 95, 168, 211) else Color.argb(40, 30, 44, 56),
                10 * d, Color.argb(if (i == active) 220 else 70, 95, 168, 211))
            b.setTextColor(Color.WHITE)
        }
        buttons.forEachIndexed { i, b ->
            b.setOnClickListener { paint(i); onPick(i) }
            line.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (6 * d).toInt() })
        }
        paint(selected)
        return line
    }

    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, resources.getDimensionPixelSize(R.dimen.modern_space_m)) }
}
