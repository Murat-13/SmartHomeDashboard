package com.example.smarthomedashboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Редактор плитки «Инвертор»: показатели выбираются касанием строк на предпросмотре раскрытой карточки,
 * номинал, порог перегруза и знаки — справа; вид проверяется тестовыми сценариями без Home Assistant.
 * Данные — только уже полученные ViewModel панели; команд HA и записи нет до «Сохранить».
 */
class InverterModernEditorActivity : PinProtectedActivity(), BatteryModernCard.Editor {
    private lateinit var tileManager: TileManager
    internal lateinit var draft: InverterModernDraft
        private set
    internal lateinit var preview: InverterModernCard
        private set
    internal lateinit var testCard: InverterModernCard
        private set
    private var model = MainUiState()
    private var previewJob: Job? = null
    private val kit = ModernEditorKit(this) { model }
    internal lateinit var testResult: TextView
        private set
    /** Тестовый сценарий предпросмотра; только локально, в HA не отправляется. */
    internal var scenario: Scenario = Scenario.SOLAR
        private set

    /** Тестовые данные для проверки вида до подключения инвертора (Вт; АКБ: + заряд, − разряд; сеть: + из сети). */
    enum class Scenario(val title: String, val solar: Double?, val grid: Double?, val battery: Double?, val load: Double?,
                        val soc: Double?, val mode: String?, val fault: String? = null) {
        SOLAR("Солнце и АКБ", 3200.0, 0.0, -650.0, 3850.0, 76.0, "Off-Grid"),
        CHARGE("Солнце заряжает АКБ", 6800.0, 0.0, 4200.0, 2600.0, 54.0, "Off-Grid"),
        GRID("От сети", 0.0, 2400.0, 0.0, 2400.0, 100.0, "Line"),
        OVERLOAD("Перегруз", 4000.0, 0.0, -5300.0, 9300.0, 61.0, "Off-Grid"),
        FAULT("Авария", 0.0, 0.0, 0.0, 0.0, 40.0, "Fault", "Перегрев"),
        NO_DATA("Нет данных", null, null, null, null, null, null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tileManager = TileManager(this)
        val tile = intent.getStringExtra("tile_id")?.let { id -> tileManager.loadTiles().find { it.id == id } }
        if (tile == null || tile.type != InverterModernConfig.TYPE) {
            Toast.makeText(this, "Плитка не найдена", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        draft = savedInstanceState?.getString(STATE_DRAFT)?.let {
            try { InverterModernDraft.restore(it, tile) } catch (_: Exception) { null }
        } ?: InverterModernDraft(tile)
        savedInstanceState?.getString(STATE_SCENARIO)?.let { name -> Scenario.entries.firstOrNull { it.name == name }?.let { scenario = it } }
        TileEditorPreviewSource.states?.value?.let { model = it }
        buildUi()
        render()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        kit.loadCachedEntities()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 14, 20))
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
        }
        root.addView(kit.label("Инвертор — настройка", 22f, bold = true))
        root.addView(kit.hintBar(UserHints.Hint.INVERTER_MODERN_EDITOR,
            "Нажмите на строку справа в карточке, чтобы выбрать сущность Home Assistant. Мощность солнца можно задать " +
                "суммой или по входам PV1/PV2; мощность АКБ — датчиком или из напряжения и тока. Ненастроенные строки " +
                "на панели не показываются. Изменения применяются только кнопкой «Сохранить»."))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        preview = InverterModernCard(this, expanded = true, editor = this)
        val previewFrame = FrameLayout(this).apply { tag = "inv_editor_preview" }
        previewFrame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(previewFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), 0, 0, 0)
        }
        side.addView(kit.label("Проверка вида (без Home Assistant)", 16f, bold = true))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Scenario.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { item ->
                row.addView(kit.button(item.title, "inv_test_${item.name.lowercase()}") { scenario = item; renderTest() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            chips.addView(row)
        }
        side.addView(chips)
        testCard = InverterModernCard(this, expanded = false).apply { tag = "inv_test_card" }
        side.addView(FrameLayout(this).apply {
            addView(testCard, FrameLayout.LayoutParams(kit.dp(240), kit.dp(240), Gravity.CENTER_HORIZONTAL))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kit.dp(252)))
        testResult = kit.label("", 14f).apply { tag = "inv_test_result" }
        side.addView(testResult)

        side.addView(kit.label("Номинальная мощность, Вт", 16f, bold = true))
        side.addView(kit.input(number(draft.config.ratedPowerW), "11000").apply {
            tag = "inv_rated"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(kit.watcher { text -> BatteryFormat.number(text)?.takeIf { it > 0 }?.let { draft.setRatedPower(it); render() } })
        })
        side.addView(kit.label("Перегруз — от, % нагрузки", 16f, bold = true))
        side.addView(kit.input(number(draft.config.overloadPercent), "80").apply {
            tag = "inv_overload"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(kit.watcher { text -> BatteryFormat.number(text)?.let { draft.setOverloadPercent(it); render() } })
        })
        side.addView(kit.label("Если датчика «Нагрузка — загрузка» нет, процент считается от номинала.", 13f))
        side.addView(SwitchCompat(this).apply {
            tag = "inv_invert_battery"
            text = "АКБ: плюс у датчика — разряд"
            setTextColor(Color.WHITE)
            isChecked = draft.config.invertBatterySign
            setOnCheckedChangeListener { _, checked -> draft.setInvertBattery(checked); render() }
        })
        side.addView(SwitchCompat(this).apply {
            tag = "inv_invert_grid"
            text = "Сеть: плюс у датчика — отдача в сеть"
            setTextColor(Color.WHITE)
            isChecked = draft.config.invertGridSign
            setOnCheckedChangeListener { _, checked -> draft.setInvertGrid(checked); render() }
        })
        side.addView(kit.label("На плитке: «+» у АКБ — заряд, «−» — разряд. Включите, если у вашего датчика наоборот.", 13f))
        side.addView(kit.button("Тип, размер, удаление плитки…", "inv_tile_settings") { openTileSettings() })
        body.addView(ScrollView(this).apply { addView(side) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(kit.footer("inv", { leave() }, { save() }))
        setContentView(root)
    }

    private fun number(value: Double) = BatteryFormat.decimal(value, if (value % 1.0 == 0.0) 0 else 1, java.util.Locale.ROOT)

    /** Предпросмотр по данным панели: цвет сразу, без задержки подтверждения. */
    internal fun render() {
        val readings = BatteryReadings.from(model)
        val config = draft.config
        val snapshot = InverterModel.snapshot(config, readings)
        preview.bind(draft.title, config, readings, snapshot, snapshot.look, animate = false)
        renderTest()
    }

    /** Тестовый сценарий подставляется вместо источников только в карточке проверки. */
    internal fun renderTest() {
        if (!::testCard.isInitialized) return
        val item = scenario
        val values = mapOf(
            InverterModernConfig.ROLE_PV_POWER to item.solar, InverterModernConfig.ROLE_GRID_POWER to item.grid,
            InverterModernConfig.ROLE_BATTERY_POWER to item.battery, InverterModernConfig.ROLE_LOAD_POWER to item.load,
            InverterModernConfig.ROLE_BATTERY_SOC to item.soc
        )
        val texts = mapOf(InverterModernConfig.ROLE_MODE to item.mode, InverterModernConfig.ROLE_FAULT to item.fault)
        // Сценарий — в соглашении плитки: инверсия знаков черновика здесь не применяется.
        val base = draft.config.copy(invertBatterySign = false, invertGridSign = false)
        val test = base.copy(rows = base.rows.map { row ->
            if (row.role in values || row.role in texts) row.copy(entityId = PREVIEW_PREFIX + row.role, unit = "", decimals = null)
            else row.copy(entityId = "")
        })
        val noData = item == Scenario.NO_DATA
        val readings = BatteryReadings(HaSession(connected = !noData, synced = !noData),
            { id ->
                val role = id.removePrefix(PREVIEW_PREFIX)
                values[role]?.toString() ?: texts[role]
            },
            { false },
            { id -> if (id.endsWith("_soc")) JSONObject().put("unit_of_measurement", "%") else JSONObject().put("unit_of_measurement", "W") })
        val snapshot = InverterModel.snapshot(test, readings)
        testCard.bind(draft.title, test, readings, snapshot, snapshot.look, animate = true)
        testResult.text = "${item.title} → ${snapshot.status}"
    }

    override fun onStart() {
        super.onStart()
        previewJob?.cancel()
        TileEditorPreviewSource.states?.let { flow ->
            previewJob = lifecycleScope.launch {
                flow.collect {
                    model = it
                    if (::preview.isInitialized) render()
                }
            }
        }
    }

    override fun onStop() {
        previewJob?.cancel()
        previewJob = null
        super.onStop()
    }

    override fun onDestroy() {
        kit.dispose()
        if (::preview.isInitialized) preview.dispose()
        if (::testCard.isInitialized) testCard.dispose()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::draft.isInitialized) outState.putString(STATE_DRAFT, draft.saveState())
        outState.putString(STATE_SCENARIO, scenario.name)
        super.onSaveInstanceState(outState)
    }

    // ---- BatteryModernCard.Editor: касание строки на предпросмотре ----

    override fun editRole(role: String) {
        draft.config.row(role)?.let { editRow(it.id) }
    }

    override fun editRow(id: String) {
        val row = draft.row(id) ?: return
        kit.editRowDialog(row, isNew = false, onApply = { draft.update(it); render() }, onDelete = null, onMove = null, allowHide = false)
    }

    override fun editHeader() {
        val titleInput = kit.input(draft.title, "Название").apply { tag = "inv_header_title" }
        kit.show(AlertDialog.Builder(this).setTitle("Название плитки").setView(kit.column(kit.label("Название", 14f), titleInput))
            .setPositiveButton("Готово") { _, _ -> draft.setTitle(titleInput.text.toString().trim()); render() }
            .setNegativeButton("Отмена", null).create())
    }

    internal val openRowDialog get() = kit.openRowDialog
    internal val openPicker get() = kit.openPicker

    // ---- Сохранение / отмена ----

    internal fun save(): Boolean {
        if (!requireAdmin()) return false
        draft.validationError()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); return false }
        val latest = tileManager.loadTiles().find { it.id == draft.original.id }
        if (latest == null || latest != draft.original) {
            Toast.makeText(this, "Плитка изменена другим экраном. Закройте редактор и откройте её заново.", Toast.LENGTH_LONG).show()
            return false
        }
        tileManager.updateTile(draft.applyTo(latest))
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
        return true
    }

    internal fun leave() {
        if (!::draft.isInitialized || !draft.isDirty) { finish(); return }
        kit.show(AlertDialog.Builder(this).setTitle("Сохранить изменения?")
            .setMessage("Настройки «${draft.title}» пока не записаны.")
            .setPositiveButton("Сохранить") { _, _ -> save() }
            .setNegativeButton("Не сохранять") { _, _ -> finish() }
            .setNeutralButton("Продолжить редактирование", null).create())
    }

    private fun openTileSettings() {
        if (draft.isDirty) {
            Toast.makeText(this, "Сначала сохраните или отмените изменения", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, TileSettingsActivity::class.java).putExtra("tile_id", draft.original.id))
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        private const val STATE_DRAFT = "inverter_modern_draft"
        private const val STATE_SCENARIO = "inverter_modern_scenario"
        /** Внутренние id тестовых значений предпросмотра; не сущности HA. */
        internal const val PREVIEW_PREFIX = "preview.inverter_"
    }
}
