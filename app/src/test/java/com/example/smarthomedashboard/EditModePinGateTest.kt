package com.example.smarthomedashboard

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.InvocationTargetException
import java.time.Duration

// Режим редактирования не должен сохранять изменения после истечения PIN-сессии.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [SensorCardResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class EditModePinGateTest {
    private val activity by lazy {
        Robolectric.buildActivity(MainActivity::class.java).get().apply {
            pinGestureFixture(this)
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            resources.displayMetrics.widthPixels = 1920
            resources.displayMetrics.heightPixels = 1200
        }
    }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }

    // Сессия — общий объект процесса: сбрасывается и до, и после теста, порядок классов не влияет (CI 0.9.2).
    @org.junit.Before fun noSessionBefore() = PinSecurityUi.session.revoke()
    @After fun revokeSession() = PinSecurityUi.session.revoke()

    /** Изолированное хранилище с включённым PIN: проверка PIN не может «разрешить» действие сама. */
    private fun pinEnabledAccess() = PinAccess(object : PinAccess.Storage {
        override fun read() = PinAccess.State("test-pin-hash", null)
        override fun write(state: PinAccess.State) = true
    }, object : PinAccess.Legacy { override fun read(): String? = null; override fun remove() = true },
        clock = object : PinAccess.Clock { override fun elapsed() = 0L; override fun boot() = "test" }, worker = {})

    private fun open(): FrameLayout {
        val tile = TileEntity("card", "sensor", "grid", "Датчик", 20, 30, 1, 1,
            config = """{"entity_id":"sensor.soc"}""")
        manager.saveTiles(listOf(tile))
        ArchitectureTestAccess.setField(activity, "tileManager", manager)
        ArchitectureTestAccess.setField(activity, "bottomPanel", panel)
        ArchitectureTestAccess.setField(activity, "isEditMode", true)
        val card = try {
            ArchitectureTestAccess.method(activity, "createSensorView", TileEntity::class.java)
                .apply { isAccessible = true }.invoke(activity, tile) as FrameLayout
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
        panel.addView(card)
        layout(card)
        card.x = 20f
        card.y = 30f
        return card
    }

    private fun layout(card: FrameLayout) {
        card.forceLayout()
        card.measure(
            View.MeasureSpec.makeMeasureSpec(card.layoutParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(card.layoutParams.height, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, card.layoutParams.width, card.layoutParams.height)
    }

    private fun send(view: View, action: Int, x: Float = 5f, y: Float = 5f) {
        val event = MotionEvent.obtain(0, 1, action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun resize(card: FrameLayout) {
        val handle = (0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<ImageView>().single()
        send(handle, MotionEvent.ACTION_DOWN)
        send(handle, MotionEvent.ACTION_MOVE, 85f, 65f)
        send(handle, MotionEvent.ACTION_UP)
        layout(card)
    }

    private fun prefs() = activity.getSharedPreferences("tiles_prefs", 0).all.toMap()
    private fun pinRequested() = ArchitectureTestAccess.getField<Job?>(activity, "pendingPin") != null
    private fun savedWidth() = JSONObject(manager.loadTiles().single().config).optInt("button_size", -1)

    @Test fun expiredSessionRollsBackResizeWithoutWritingAndAsksForPin() {
        val card = open()
        ArchitectureTestAccess.setField(activity, "pinDisabled", false)
        // PIN включён и в хранилище, а не только в кеше экрана: раньше изолированное хранилище говорило
        // «отключён», и успевшая завершиться фоновая проверка законно сохраняла размер (упало в CI).
        ArchitectureTestAccess.setField(activity, "pinAccess", pinEnabledAccess())
        val before = prefs()
        resize(card)
        assertEquals(before, prefs())
        assertEquals(220, card.width)
        assertEquals(180, card.height)
        assertTrue(pinRequested())
        // Проверка PIN завершилась — размер так и не записан, нужен ввод PIN.
        awaitPinGate(activity)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, prefs())
    }

    @Test fun liveSessionSavesAndEditActionExtendsIt() {
        val card = open()
        ArchitectureTestAccess.setField(activity, "pinDisabled", false)
        PinSecurityUi.session.grant()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(50))
        resize(card)
        assertEquals(300, savedWidth())
        assertFalse(pinRequested())
        // Без продления сессия (60 мин по умолчанию) истекла бы через 10 минут.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(20))
        assertTrue(PinSecurityUi.session.isValid(60))
    }

    @Test fun unknownPinStateDoesNotSaveUntilCheckShowsPinDisabled() {
        val card = open()
        ArchitectureTestAccess.setField(activity, "pinDisabled", null)
        // Хранилище «PIN отключён» отвечает только по сигналу теста. Раньше оно отвечало сразу, и если
        // фоновая проверка успевала до приостановки withContext, сохранение шло синхронно внутри жеста —
        // тест «плавал» (падал в CI 03.11 и в базовом прогоне 04.11). Поведение приложения то же.
        val release = java.util.concurrent.CountDownLatch(1)
        ArchitectureTestAccess.setField(activity, "pinAccess", PinAccess(object : PinAccess.Storage {
            override fun read(): PinAccess.State {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return PinAccess.State(null, null)
            }
            override fun write(state: PinAccess.State) = true
        }, object : PinAccess.Legacy { override fun read(): String? = null; override fun remove() = true },
            clock = object : PinAccess.Clock { override fun elapsed() = 0L; override fun boot() = "test" }, worker = {}))
        val before = prefs()
        resize(card)
        assertEquals(before, prefs())
        assertTrue(pinRequested())
        // Проверка ответила «PIN отключён» — действие применяется после неё.
        release.countDown()
        awaitPinGate(activity)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(300, savedWidth())
        assertEquals(true, ArchitectureTestAccess.getField<Boolean?>(activity, "pinDisabled"))
    }

    @Test fun pinDisabledSavesImmediately() {
        val card = open()
        resize(card)
        assertEquals(300, savedWidth())
        assertFalse(pinRequested())
    }
}
