package com.example.smarthomedashboard.deep

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smarthomedashboard.DoorbellConfig
import com.example.smarthomedashboard.DoorbellRingWindow
import com.example.smarthomedashboard.Gate
import com.example.smarthomedashboard.GateStore
import com.example.smarthomedashboard.HoldButton
import com.example.smarthomedashboard.MainActivity
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Калитки и ворота, этап 1 (docs/gate-tz.md), сквозь всё приложение на эмуляторе с фальшивым HA: раскрытая плитка
 * домофона без звонка → удержание «Открыть калитку» → команда ушла в HA → подтверждение по датчику двери.
 */
@RunWith(AndroidJUnit4::class)
class GatesDeepTest {
    private lateinit var ha: FakeHomeAssistant
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun stop() {
        scenario?.close()
        if (::ha.isInitialized) ha.close()
    }

    /** Удержание пальцем по центру кнопки — как на стене: касание, [ms] мс, отпускание. */
    private fun hold(view: View, ms: Long) {
        val location = IntArray(2)
        onMain { view.getLocationOnScreen(location) }
        val x = location[0] + view.width / 2f
        val y = location[1] + view.height / 2f
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        SystemClock.sleep(ms)
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + ms, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test fun openWicketFromExpandedTileWithoutRing() {
        assumeTrue("Только эмулятор: тест заменяет данные приложения", isEmulator())
        ha = FakeHomeAssistant()
        val tiles = allTiles(DoorbellConfig(camera = "camera.deep_door", visitor = "binary_sensor.deep_visitor", gates = listOf("gate_deep")))
        seedStates(ha, tiles)
        ha.set("switch.deep_wicket", "off")
        ha.set("binary_sensor.deep_wicket_door", "off")
        prepareApp(ha, tiles)
        val wicket = Gate("gate_deep", "Калитка", Gate.Kind.RELAY, open = "switch.deep_wicket", sensor = "binary_sensor.deep_wicket_door")
        appContext.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE).edit()
            .putString(GateStore.KEY, JSONArray().put(wicket.toJson()).toString()).commit()

        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(30_000, "подключение к фальшивому HA") { ha.connected() >= 1 }
        var activity: MainActivity? = null
        scenario!!.onActivity { activity = it }
        val tile = onMain { activity!!.window.decorView.findViewWithTag<View>("deep_door") }
        tap(activity!!, tile)
        var button: HoldButton? = null
        waitFor(10_000, "кнопка «Открыть калитку» в раскрытой плитке") {
            button = onMain { activity!!.window.decorView.findViewWithTag<View>("door_expanded")?.findViewWithTag("gate_gate_deep_open") }
            button?.isShown == true
        }
        assertEquals("Открыть калитку", onMain { button!!.label })

        hold(button!!, 400)
        SystemClock.sleep(500)
        assertEquals("Отпустили раньше — команды нет", 0, ha.commands.count { it.optString("domain") == "switch" })
        hold(button!!, 1_300)
        waitFor(10_000, "команда реле калитки в HA") {
            ha.commands.any { it.optString("service") == "turn_on" &&
                it.optJSONObject("service_data")?.optString("entity_id") == "switch.deep_wicket" }
        }
        // Реле включилось, а датчик двери ещё «закрыто» — ждём факта. Дверь открылась — «Открыто (датчик)».
        ha.push("binary_sensor.deep_wicket_door", "on")
        waitFor(10_000, "подтверждение по датчику двери") { onMain { button!!.state } == DoorbellRingWindow.Gate.OPENED }
        log("Калитка из раскрытой плитки без звонка: команда ушла, датчик подтвердил")
        assertEquals("Одно удержание — одна команда", 1, ha.commands.count { it.optString("domain") == "switch" })
    }
}
