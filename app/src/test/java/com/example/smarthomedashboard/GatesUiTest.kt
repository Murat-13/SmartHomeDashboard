package com.example.smarthomedashboard

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/** Калитки и ворота, этап 1 (docs/gate-tz.md): экраны настройки и кнопки в раскрытой плитке. Команды только записываются. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h800dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class GatesUiTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = TileManager(context)
    private val door = TileEntity("door", DoorbellConfig.TYPE, "grid", "Домофон", 10, 20, 1, 1,
        config = DoorbellConfig(rtspSub = "rtsp://10.0.0.5/sub").writeTo(DoorbellConfig.newTileConfig()))
    private val wicket = Gate("gate_1", "Калитка", Gate.Kind.RELAY, open = "switch.wicket")
    private val drive = Gate("gate_2", "Ворота", Gate.Kind.COVER, open = "cover.drive")
    private val sent = mutableListOf<String>()

    @Before fun setUp() {
        // Кадры анимаций — по часам теста (иначе Robolectric сам двигает время на каждый кадр и удержание «проходит»).
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        context.getSharedPreferences("dashboard_prefs", 0).edit().clear().commit()
        manager.saveTiles(listOf(door))
        GateHub.reset()
        GateHub.attach(context)
        GateHub.driver = GateHub.Driver { c, onResult -> sent += "${c.domain}.${c.service} ${c.entity}"; onResult(ServiceCommandResult.ACKNOWLEDGED); true }
        TileEditorPreviewSource.attach(MainViewModel())
    }

    @After fun tearDown() {
        GateHub.reset()
        DoorbellHub.detach()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) { val step = minOf(50L, left); shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS); left -= step }
    }

    private fun hold(view: View, ms: Long) {
        val t = SystemClock.uptimeMillis()
        MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0).also { view.dispatchTouchEvent(it); it.recycle() }
        advance(ms)
        MotionEvent.obtain(t, t + ms, MotionEvent.ACTION_UP, 10f, 10f, 0).also { view.dispatchTouchEvent(it); it.recycle() }
    }

    // ---- Редактор прохода ----

    /**
     * Экран под PIN: проверка PIN идёт в фоне и на этой среде (PIN не настроен по-настоящему) закрывает экран, если
     * успела раньше касаний теста. Тест проходил или падал от скорости машины (GatesUiTest на CI 23.11.2026: окно
     * «Удалить?» не появилось — экран уже закрывался). Здесь PIN заранее «выключен», и тест ждёт, пока экран откроется.
     */
    private fun <T : PinProtectedActivity> opened(controller: org.robolectric.android.controller.ActivityController<T>):
        org.robolectric.android.controller.ActivityController<T> {
        val a = controller.create().get()
        org.robolectric.util.ReflectionHelpers.setField(a, "pinGateAccess", PinAccess(object : PinAccess.Storage {
            override fun read() = PinAccess.State(null, null)
            override fun write(state: PinAccess.State) = true
        }, object : PinAccess.Legacy { override fun read(): String? = null; override fun remove() = true },
            clock = object : PinAccess.Clock { override fun elapsed() = 0L; override fun boot() = "test" }, worker = {}))
        controller.start().resume()
        weatherAwait { org.robolectric.util.ReflectionHelpers.getField<Boolean>(a, "pinAuthorized") }
        idle()
        return controller
    }

    private fun editor(gateId: String? = null) = opened(Robolectric.buildActivity(GateEditorActivity::class.java,
        Intent(context, GateEditorActivity::class.java).apply { gateId?.let { putExtra(GateEditorActivity.EXTRA_GATE, it) } })).get()

    @Test fun newGateIsSavedOnlyWhenDeviceChosen() {
        val e = editor()
        val root = e.findViewById<View>(android.R.id.content)
        assertEquals("Первый проход — «Калитка»", "Калитка", e.draft.name)
        assertFalse("Без устройства не сохраняется", e.save())
        assertTrue(GateStore.load(context).isEmpty())
        // Тип «Привод»: подходящие сущности — только cover; прежняя (реле) сбрасывается.
        e.set(Gate.ROLE_OPEN, "switch.wicket")
        root.findViewWithTag<View>("gate_kind_cover").performClick()
        assertEquals(Gate.Kind.COVER, e.draft.kind)
        assertEquals("Реле приводу не подходит — выбор сброшен", "", e.draft.open)
        assertTrue("У привода ожидание подтверждения — на ход ворот", e.draft.confirmSeconds >= 45)
        e.set(Gate.ROLE_OPEN, "cover.drive")
        e.set(Gate.ROLE_SENSOR, "binary_sensor.drive_closed")
        e.nameField.setText("Ворота")
        assertTrue(e.save())
        val saved = GateStore.load(context).single()
        assertEquals(Gate.Kind.COVER, saved.kind)
        assertEquals("Ворота", saved.name)
        assertEquals("cover.drive", saved.open)
        assertEquals("binary_sensor.drive_closed", saved.sensor)
    }

    @Test fun editAndDeleteExisting() {
        GateStore.save(context, listOf(wicket, drive))
        val e = editor("gate_1")
        assertEquals(wicket, e.draft)
        e.nameField.setText("Калитка у дома")
        assertTrue(e.save())
        assertEquals(listOf("Калитка у дома", "Ворота"), GateStore.load(context).map { it.name })
        val d = editor("gate_2")
        d.findViewById<View>(android.R.id.content).findViewWithTag<View>("gate_delete").performClick()
        idle()
        // Подтверждение удаления — диалог; «Удалить».
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("Удалить «Ворота»?", shadowOf(dialog).title.toString())
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        advance(300)
        assertEquals(listOf("gate_1"), GateStore.load(context).map { it.id })
    }

    // ---- Список «Калитки и ворота» ----

    @Test fun listShowsWhereGateIsShownAndPinChoice() {
        GateStore.save(context, listOf(wicket, drive))
        manager.saveTiles(listOf(door.copy(config = DoorbellConfig.from(door.config).copy(gates = listOf("gate_2")).writeTo(door.config))))
        val a = opened(Robolectric.buildActivity(GatesActivity::class.java)).get()
        idle()
        val root = a.findViewById<View>(android.R.id.content)
        val item = root.findViewWithTag<ViewGroup>("gates_item_gate_2")
        val text = (0 until item.childCount).joinToString(" ") { (item.getChildAt(it) as TextView).text.toString() }
        assertTrue(text, text.contains("показан: Домофон"))
        assertTrue(texts(root.findViewWithTag<ViewGroup>("gates_item_gate_1")).contains("не показан ни на одной плитке"))
        root.findViewWithTag<View>("gates_pin_1").performClick()
        assertEquals(GatePin.OUTSIDE_RING, GateStore.pin(context))
    }

    private fun texts(group: ViewGroup) = (0 until group.childCount).joinToString(" ") { (group.getChildAt(it) as TextView).text.toString() }

    // ---- Редактор плитки: какие проходы у неё ----

    private fun doorEditor() = opened(Robolectric.buildActivity(DoorbellEditorActivity::class.java,
        Intent(context, DoorbellEditorActivity::class.java).putExtra("tile_id", "door")))

    @Test fun tileEditorChecksGatesInListOrderAndNewOnesFromManageAreChecked() {
        GateStore.save(context, listOf(wicket, drive))
        val controller = doorEditor()
        val e = controller.get(); idle()
        val root = e.findViewById<View>(android.R.id.content)
        root.findViewWithTag<SwitchCompat>("door_gate_gate_2").isChecked = true
        root.findViewWithTag<SwitchCompat>("door_gate_gate_1").isChecked = true
        assertEquals("Порядок — как в общем списке", listOf("gate_1", "gate_2"), e.config.gateIds("door"))
        root.findViewWithTag<SwitchCompat>("door_gate_gate_1").isChecked = false
        assertEquals(listOf("gate_2"), e.config.gateIds("door"))
        // «Управление замком…» → там добавили проход → вернулись: новый уже отмечен у этой плитки.
        root.findViewWithTag<View>("door_gates_manage").performClick()
        controller.pause()
        GateStore.save(context, listOf(wicket, drive, Gate("gate_3", "Дверь", Gate.Kind.LOCK, open = "lock.door")))
        controller.resume()
        // После возврата экран снова проверяет PIN в фоне; без ожидания «Сохранить» на медленной машине CI попадал в
        // «сессия истекла» (Android CI для 2ecc576, 24.11.2026; локально проверка успевала раньше).
        weatherAwait { org.robolectric.util.ReflectionHelpers.getField<Boolean>(e, "pinAuthorized") }
        idle()
        assertEquals(listOf("gate_2", "gate_3"), e.config.gateIds("door"))
        assertTrue(e.save()); idle()
        assertEquals(listOf("gate_2", "gate_3"), DoorbellConfig.from(manager.loadTiles().single().config).gates)
    }

    // ---- Кнопки в раскрытой плитке (и без звонка) ----

    @Test fun expandedCardShowsGateButtonsAndHoldSendsCommand() {
        GateStore.save(context, listOf(wicket, drive))
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val card = DoorbellCard(activity, expanded = true)
        activity.findViewById<ViewGroup>(android.R.id.content).addView(card, ViewGroup.LayoutParams(1000, 750))
        var pressed: Pair<String, Gate.Action>? = null
        card.onGate = { gate, action -> pressed = gate.id to action; GateHub.run(gate.id, action) }
        card.gates(listOf(wicket, drive))
        idle()
        val open = card.findViewWithTag<HoldButton>("gate_gate_1_open")
        assertNotNull(open)
        assertEquals("Открыть калитку", open.label)
        assertNotNull("У привода — «Открыть» и «Закрыть»", card.findViewWithTag<HoldButton>("gate_gate_2_close"))
        assertNull("«Стоп» — только если привод его умеет", card.findViewWithTag<HoldButton>("gate_gate_2_stop"))
        hold(open, 400)
        assertNull("Отпустили раньше — ничего", pressed)
        hold(open, 1_100)
        assertEquals("gate_1" to Gate.Action.OPEN, pressed)
        assertEquals(listOf("switch.turn_on switch.wicket"), sent)
        // В окне звонка у карточки своих кнопок нет (у окна — свои).
        card.ringMode = true
        assertEquals(View.GONE, card.findViewWithTag<View>("gate_buttons").visibility)
    }
}
