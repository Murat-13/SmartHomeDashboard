package com.example.smarthomedashboard

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.TimeUnit

// Нативная графика Robolectric: настоящие шрифты и отрисовка. Рендеры — build/reports/inverter-modern/*.png.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InverterModernVisualTest {
    private val activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    @After fun cleanup() {
        PanelMotion.dimmed = false
        durationScale(1f)
    }

    /** Системный «Масштаб анимации» (скрытый API, как переключатель в настройках разработчика). */
    private fun durationScale(value: Float) = ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType)
        .invoke(null, value)

    private val all = InverterModernConfig.templateRows().map { it.role }
    private val config = all.fold(InverterModernConfig()) { c, role -> c.withRow(c.row(role)!!.copy(entityId = "sensor.$role")) }

    private fun readings(values: Map<String, String>, connected: Boolean = true) =
        BatteryReadings(HaSession(connected, connected), { values[it.removePrefix("sensor.")] }, { false }, { id ->
            val role = id.removePrefix("sensor.")
            JSONObject().apply {
                when {
                    role == InverterModernConfig.ROLE_PRIORITY -> put("options", JSONArray(listOf("UTI", "SOL", "SBU", "SUB")))
                    role.endsWith("power") -> put("unit_of_measurement", "W")
                    role.endsWith("voltage") -> put("unit_of_measurement", "V")
                    role.endsWith("current") -> put("unit_of_measurement", "A")
                    role == "battery_soc" || role == "load_percent" -> put("unit_of_measurement", "%")
                    role == "grid_frequency" -> put("unit_of_measurement", "Hz")
                    role == "load_apparent" -> put("unit_of_measurement", "VA")
                }
            }
        })

    private val solar = mapOf("pv_power" to "3200", "pv1_power" to "1750", "pv1_voltage" to "312.4", "pv2_power" to "1450",
        "pv2_voltage" to "298.0", "battery_power" to "-650", "battery_soc" to "76", "battery_voltage" to "52.6",
        "battery_current" to "-12.4", "grid_power" to "0", "grid_voltage" to "0", "grid_frequency" to "0",
        "load_power" to "3850", "load_percent" to "35", "load_voltage" to "230.1", "load_current" to "16.9",
        "load_apparent" to "3920", "mode" to "Off-Grid", "priority" to "SBU", "fault" to "0", "warning" to "0")
    private val scenarios = mapOf(
        "solar" to solar,
        "charge" to solar + mapOf("pv_power" to "6800", "battery_power" to "4200", "load_power" to "2600", "battery_soc" to "54"),
        "grid" to solar + mapOf("pv_power" to "0", "battery_power" to "0", "grid_power" to "2400", "load_power" to "2400",
            "battery_soc" to "100", "mode" to "Line"),
        "overload" to solar + mapOf("pv_power" to "4000", "battery_power" to "-5300", "load_power" to "9300", "load_percent" to "85"),
        "fault" to solar + mapOf("pv_power" to "0", "battery_power" to "0", "load_power" to "0", "fault" to "Перегрев", "mode" to "Fault"),
        "nodata" to emptyMap()
    )

    private fun host(card: View, w: Int, h: Int): FrameLayout {
        val root = FrameLayout(activity)
        root.addView(card, FrameLayout.LayoutParams(w, h))
        activity.setContentView(root)
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
        }
        return root
    }

    private fun bind(card: InverterModernCard, values: Map<String, String>, connected: Boolean = true) {
        val r = readings(values, connected)
        val snap = InverterModel.snapshot(config, r)
        card.bind("Инвертор", config, r, snap, snap.look, animate = false)
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/reports/inverter-modern/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun rendersCompactSquaresAtSeveralSizes() {
        listOf(340, 260, 200, 480).forEach { side ->
            scenarios.forEach { (name, values) ->
                val card = InverterModernCard(activity, expanded = false)
                bind(card, values, connected = name != "nodata")
                host(card, side, side)
                assertTrue(card.flow.width > 0 && card.flow.height > 0)
                save(card, "compact_${side}_$name")
            }
        }
    }

    @Test fun rendersExpandedCard() {
        listOf(1500 to 960, 1100 to 1000).forEach { (w, h) ->
            listOf("solar", "fault").forEach { name ->
                val card = InverterModernCard(activity, expanded = true)
                card.onPriority = {}
                bind(card, scenarios.getValue(name))
                host(card, w, h)
                save(card, "expanded_${w}x${h}_$name")
            }
        }
    }

    @Test fun dotsRunOnlyWhileVisibleAndMoving() {
        val card = InverterModernCard(activity, expanded = false)
        bind(card, solar)
        host(card, 340, 340)
        val flow = card.flow
        assertTrue("Видимая схема с потоками — точки бегут", flow.animating)
        PanelMotion.dimmed = true
        assertFalse("Экран затемнён", flow.animating)
        PanelMotion.dimmed = false
        assertTrue(flow.animating)
        card.setHiddenBySource(true)
        flow.setHiddenBySource(true)
        assertFalse("Плитка скрыта раскрытием", flow.animating)
        flow.setHiddenBySource(false)
        assertTrue(flow.animating)
        card.visibility = View.INVISIBLE
        assertFalse("Невидимая", flow.animating)
        card.visibility = View.VISIBLE
        assertTrue(flow.animating)
        bind(card, solar + mapOf("pv_power" to "0", "pv1_power" to "0", "pv2_power" to "0", "battery_power" to "0", "load_power" to "0"))
        assertFalse("Потоков нет — аниматора нет", flow.animating)
        bind(card, solar)
        assertTrue(flow.animating)
        (card.parent as FrameLayout).removeView(card)
        assertFalse("Отсоединена — аниматор освобождён", flow.animating)
        durationScale(0f)
        val again = InverterModernCard(activity, expanded = false)
        bind(again, solar)
        host(again, 340, 340)
        assertFalse("Анимации отключены в системе", again.flow.animating)
    }

    @Test fun framesAdvanceDotsAndDoNotCrashOnTinySizes() {
        val card = InverterModernCard(activity, expanded = false)
        bind(card, scenarios.getValue("charge"))
        host(card, 340, 340)
        repeat(30) { shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS) }
        save(card, "compact_340_charge_frame30")
        val tiny = InverterModernCard(activity, expanded = false)
        bind(tiny, solar)
        host(tiny, 60, 60)
        save(tiny, "compact_60_solar")
    }

    @Test fun faultPulsesOnlyWhenVisible() {
        val card = InverterModernCard(activity, expanded = false)
        bind(card, scenarios.getValue("fault"))
        host(card, 340, 340)
        assertEquals(PowerModernConfig.RED, card.accent)
        assertTrue(card.pulsing)
        card.setHiddenBySource(true)
        assertFalse(card.pulsing)
        assertTrue(card.pulseRequested)
    }
}
