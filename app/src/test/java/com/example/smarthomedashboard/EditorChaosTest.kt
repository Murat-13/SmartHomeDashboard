package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.random.Random

/**
 * Редакторы плиток на испорченных настройках (0.9.13, docs/deep-testing.md): повреждённая плитка должна
 * открываться в редакторе (чтобы её можно было исправить), переживать поворот экрана и закрытие без сохранения.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class EditorChaosTest {
    private val context = RuntimeEnvironment.getApplication()

    @After fun tearDown() = DoorbellHub.detach()

    private val editors: Map<String, Class<out Activity>> = mapOf(
        DoorbellConfig.TYPE to DoorbellEditorActivity::class.java,
        BatteryModernConfig.TYPE to BatteryModernEditorActivity::class.java,
        PowerModernConfig.TYPE to PowerModernEditorActivity::class.java,
        InverterModernConfig.TYPE to InverterModernEditorActivity::class.java,
        HomeClimateConfig.TYPE to HomeClimateEditorActivity::class.java,
    )

    @Test fun damagedTilesOpenInEveryEditorAndSurviveRecreation() {
        val iterations = (System.getProperty("chaos.iterations")?.toIntOrNull() ?: 60) / 3
        val failures = mutableListOf<String>()
        val rnd = Random(4242)
        repeat(iterations.coerceAtLeast(10)) { i ->
            for ((type, editor) in editors) {
                val seed = ChaosTiles.generate(Random(i * 31L + type.hashCode())).firstOrNull { it.type == type }
                    ?: TileEntity("e", type, "grid", "Плитка", 10, 10, 1, 1, config = "{}")
                val config = if (rnd.nextInt(4) == 0) seed.config else
                    runCatching { (ChaosJson.mutate(JSONObject(seed.config), rnd, 0) as? JSONObject ?: JSONObject()).toString() }.getOrDefault("{]")
                val tile = seed.copy(id = "e", type = type, config = config, title = if (rnd.nextBoolean()) ChaosStates.text(rnd) else seed.title)
                TileManager(context).saveTiles(listOf(tile))
                val step = "$type, итерация $i"
                try {
                    val controller = Robolectric.buildActivity(editor, Intent(context, editor).putExtra("tile_id", "e"))
                    controller.create().start().resume()
                    shadowOf(Looper.getMainLooper()).idle()
                    val state = Bundle()
                    controller.pause().saveInstanceState(state).stop()
                    controller.destroy()
                    // Поворот: редактор восстанавливается из сохранённого состояния.
                    val again = Robolectric.buildActivity(editor, Intent(context, editor).putExtra("tile_id", "e"))
                    again.create(state).start().resume()
                    shadowOf(Looper.getMainLooper()).idle()
                    again.pause().stop().destroy()
                    // Robolectric хранит показанные Toast вместе с экраном-контекстом: без сброса экраны копятся.
                    org.robolectric.shadows.ShadowToast.reset()
                } catch (e: Throwable) {
                    failures += "$step: ${e.javaClass.simpleName}: ${e.message?.take(200)}\n" +
                        e.stackTrace.filter { it.className.startsWith("com.example") }.take(6).joinToString("\n") { "    at $it" }
                }
            }
        }
        System.getProperty("chaos.heapdump")?.let { path ->
            repeat(3) { System.gc() }
            java.lang.management.ManagementFactory.newPlatformMXBeanProxy(java.lang.management.ManagementFactory.getPlatformMBeanServer(),
                "com.sun.management:type=HotSpotDiagnostic", com.sun.management.HotSpotDiagnosticMXBean::class.java)
                .dumpHeap("$path-editors.hprof", true)
        }
        assertTrue("Редакторы упали ${failures.size} раз:\n" + failures.distinctBy { it.lines().getOrNull(1) }.take(10).joinToString("\n\n"),
            failures.isEmpty())
    }
}
