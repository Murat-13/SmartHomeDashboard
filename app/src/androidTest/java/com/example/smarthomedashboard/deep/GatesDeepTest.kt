package com.example.smarthomedashboard.deep

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smarthomedashboard.DoorbellConfig
import com.example.smarthomedashboard.GateTileConfig
import com.example.smarthomedashboard.data.TileEntity
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.util.Collections
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
    private var screen: TestScreen<MainActivity>? = null

    private var shelly: MockWebServer? = null

    @After fun stop() {
        screen?.close()
        if (::ha.isInitialized) ha.close()
        shelly?.shutdown()
    }

    /**
     * Удержание пальцем по центру кнопки — как на стене: касание, [ms] мс, отпускание. С запасом: на медленном эмуляторе
     * CI касание обрабатывается с опозданием до полсекунды, и 1,3 с «удержания» оказывались меньше секунды.
     */
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

    /**
     * Раскрытие закончилось: кнопка полсекунды на одном месте. Иначе на медленном эмуляторе CI палец попадал мимо, карточка
     * сворачивалась, а удержание на пустом месте включало режим редактирования.
     */
    private fun still(view: View) {
        var last = intArrayOf(-1, -1)
        var since = 0L
        waitFor(15_000, "кнопка остановилась") {
            val now = IntArray(2).also { onMain { view.getLocationOnScreen(it) } }
            if (!now.contentEquals(last)) { last = now; since = SystemClock.uptimeMillis() }
            SystemClock.uptimeMillis() - since >= 500
        }
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
        // Через GateStore: список проходов в памяти процесса (от прошлого теста) сбрасывается.
        GateStore.save(appContext, listOf(wicket))

        screen = TestScreen(MainActivity::class.java)
        waitFor(30_000, "подключение к фальшивому HA") { ha.connected() >= 1 }
        val activity = screen!!.activity
        val tile = onMain { activity.window.decorView.findViewWithTag<View>("deep_door") }
        tap(activity, tile)
        var button: HoldButton? = null
        waitFor(10_000, "кнопка «Открыть калитку» в раскрытой плитке") {
            button = onMain { activity.window.decorView.findViewWithTag<View>("door_expanded")?.findViewWithTag("gate_gate_deep_open") }
            button?.isShown == true
        }
        assertEquals("Открыть калитку", onMain { button!!.label })
        still(button!!)

        hold(button!!, 400)
        SystemClock.sleep(500)
        assertEquals("Отпустили раньше — команды нет", 0, ha.commands.count { it.optString("domain") == "switch" })
        hold(button!!, 2_500)
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

    /**
     * Этапы 2 и 4: плитка «Калитка или ворота» открывает калитку напрямую через Shelly (поддельный — на самом устройстве),
     * без Home Assistant; итог — по датчику на входе Shelly; касание раскрывает кнопки.
     */
    @Test fun gateTileOpensShellyDirectly() {
        assumeTrue("Только эмулятор: тест заменяет данные приложения", isEmulator())
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val door = java.util.concurrent.atomic.AtomicBoolean(false)
        shelly = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    calls += path
                    val body = when {
                        path.startsWith("/rpc/Switch.Set") -> JSONObject().put("was_on", false)
                        path.startsWith("/rpc/Switch.GetStatus") -> JSONObject().put("id", 0).put("output", false)
                        path.startsWith("/rpc/Input.GetStatus") -> JSONObject().put("id", 0).put("state", door.get())
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(body.toString())
                }
            }
            start()
        }
        ha = FakeHomeAssistant()
        val tiles = allTiles() + TileEntity("deep_gate", GateTileConfig.TYPE, "grid", "Калитка", 1300, 520, 1, 1,
            config = GateTileConfig.newTileConfig("{}", "gate_shelly"))
        seedStates(ha, tiles)
        prepareApp(ha, tiles)
        val wicket = Gate("gate_shelly", "Калитка", Gate.Kind.RELAY, link = Gate.Link.DIRECT,
            direct = Gate.Direct(host = "127.0.0.1:${shelly!!.port}", input = 0))
        // Через GateStore: список проходов в памяти процесса (от прошлого теста) сбрасывается.
        GateStore.save(appContext, listOf(wicket))

        screen = TestScreen(MainActivity::class.java)
        waitFor(30_000, "подключение к фальшивому HA") { ha.connected() >= 1 }
        val activity = screen!!.activity
        var button: HoldButton? = null
        waitFor(10_000, "плитка «Калитка» с кнопкой") {
            button = onMain { activity.window.decorView.findViewWithTag<View>("deep_gate")?.findViewWithTag("gate_tile_button") }
            button?.isShown == true
        }
        waitFor(10_000, "опрос Shelly: закрыто по датчику") { onMain { button!!.stateHint.orEmpty() }.startsWith("Закрыт") }
        still(button!!)
        hold(button!!, 2_500)
        waitFor(10_000, "импульс реле Shelly") { calls.any { it.startsWith("/rpc/Switch.Set") } }
        assertEquals("/rpc/Switch.Set?id=0&on=true&toggle_after=3", calls.first { it.startsWith("/rpc/Switch.Set") })
        door.set(true)
        waitFor(10_000, "подтверждение по входу Shelly") { onMain { button!!.state } == DoorbellRingWindow.Gate.OPENED }
        assertEquals("Одно удержание — одна команда", 1, calls.count { it.startsWith("/rpc/Switch.Set") })
        assertEquals("В Home Assistant ничего не ушло", 0, ha.commands.size)
        // Касание — раскрыть: все кнопки прохода.
        door.set(false)
        hold(button!!, 80)
        waitFor(10_000, "раскрытая плитка прохода") {
            onMain { activity.window.decorView.findViewWithTag<View>("gate_expanded")?.isShown == true }
        }
        log("Плитка «Калитка»: Shelly напрямую — импульс ушёл, вход подтвердил, касание раскрыло")
    }
}
