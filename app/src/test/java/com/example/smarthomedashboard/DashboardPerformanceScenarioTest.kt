package com.example.smarthomedashboard

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Воспроизводимый in-process сценарий; JVM-время не является измерением Android frame time. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class DashboardPerformanceScenarioTest {
    private class RecordingSocket : WebSocket {
        val messages = mutableListOf<JSONObject>()
        override fun request() = Request.Builder().url("http://localhost/artificial-only").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(JSONObject(text)); return true }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }

    private class Reads(private val backing: MutableMap<String, String> = mutableMapOf()) :
        MutableMap<String, String> by backing {
        val ids = mutableSetOf<String>()
        override fun get(key: String): String? { ids.add(key); return backing[key] }
    }

    private fun deliver(client: HomeAssistantWebSocket, text: String) {
        HomeAssistantWebSocket::class.java.getDeclaredMethod("handleMessage", HaInboundMessage::class.java)
            .apply { isAccessible = true }.invoke(client, requireNotNull(HaInboundMessage.parse(text)))
    }

    private fun payload(size: Int): JSONArray = JSONArray().also { array ->
        repeat(size) { i -> array.put(JSONObject().put("entity_id", "sensor.artificial_$i")
            .put("state", i.toString()).put("attributes", JSONObject()
                .put("unit_of_measurement", "V").put("friendly_name", "Artificial $i"))) }
    }

    private fun initial(size: Int): JSONObject {
        val owner = MainViewModel()
        // Invalid syntax stops connection setup before any network operation.
        owner.connectWebSocket("http://[", "artificial-token", autoReconnect = false)
        val client = requireNotNull(owner.getWebSocket())
        val socket = RecordingSocket()
        ReflectionHelpers.setField(client, "running", true)
        ReflectionHelpers.setField(client, "authenticated", true)
        ReflectionHelpers.setField(client, "webSocket", socket)
        owner.subscribeEntities(emptyList())
        val request = socket.messages.last { it.optString("type") == "get_states" }
        val raw = JSONObject().put("type", "result").put("id", request.getInt("id"))
            .put("success", true).put("result", payload(size)).toString()
        var publications = 0
        val observer = CoroutineScope(Dispatchers.Unconfined).launch { owner.uiState.collect { publications++ } }
        try {
            val before = publications
            val start = System.nanoTime()
            deliver(client, raw)
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            assertEquals(size, owner.devices.singleStates.size)
            assertEquals((size - 1).toString(), owner.devices.singleStates["sensor.artificial_${size - 1}"])
            return JSONObject().put("entities", size).put("pipeline_ms", elapsed)
                .put("publications", publications - before)
        } finally {
            observer.cancel()
            owner.disconnectWebSocket()
        }
    }

    private fun allText(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { allText(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun rendering(): JSONObject {
        val activity = Robolectric.buildActivity(Activity::class.java).get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        }
        val owner = MainViewModel()
        val reads = Reads()
        ReflectionHelpers.setField(owner.devices, "singleStates", reads)
        val panel = FrameLayout(activity)
        val manager = TileManager(activity)
        val weather = YandexWeatherManager(activity)
        val tiles = (0 until 20).map { i -> TileEntity("card_$i", "sensor", "grid", "Card $i",
            0, i * 10, 1, 1, config = JSONObject().put("collapsed_sensor_configs", JSONArray().put(JSONObject()
                .put("entity_id", "sensor.artificial_$i").put("display_name", "V").put("decimals", 0))).toString()) }
        manager.saveTiles(tiles)
        val controller = DashboardPanelController(activity, owner, object : DashboardPanelController.Host {
            override val panel get() = panel
            override val dim = View(activity)
            override val hasPanel = true
            override val hasDim = true
            override val tiles get() = manager
            override val weather get() = weather
            override val temperatureUnit = "C"
            override val motionEntity: String? = null
            override fun readPreferences() = Unit
            override fun updateHint() = Unit
            override fun wake() = Unit
            override fun editTile(id: String) = Unit
            override fun requestEdit() = Unit
            override fun exitEdit() = Unit
            override fun settings() = Unit
            override fun addTile() = Unit
        })
        try {
            repeat(20) { owner.onEntityStateChanged("sensor.artificial_$it", "0") }
            controller.refreshBottomPanel()
            val before = allText(panel).toSet()
            reads.ids.clear()
            owner.onEntityStateChanged("sensor.artificial_0", "1")
            val start = System.nanoTime()
            controller.renderEntityState("sensor.artificial_0", "1")
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            val created = allText(panel).count { it !in before }
            val affected = reads.ids.count { it.startsWith("sensor.artificial_") }
            assertEquals("V  1", (panel.findViewWithTag<FrameLayout>("card_0")
                .findViewWithTag<LinearLayout>("sensor_text_container").getChildAt(1) as TextView).text.toString())
            var burstCreated = 0
            val burstStart = System.nanoTime()
            repeat(200) { i ->
                val old = allText(panel).toSet()
                val id = "sensor.artificial_${i % 5}"
                owner.onEntityStateChanged(id, i.toString())
                controller.renderEntityState(id, i.toString())
                burstCreated += allText(panel).count { it !in old }
            }
            return JSONObject().put("cards", 20).put("cards_read_per_event", affected)
                .put("text_views_created_per_event", created).put("render_event_ms", elapsed)
                .put("burst_events", 200).put("burst_ms", (System.nanoTime() - burstStart) / 1_000_000.0)
                .put("burst_text_views_created", burstCreated)
        } finally {
            controller.clearPanelInteractions()
            owner.disconnectWebSocket()
        }
    }

    @Test fun syntheticInitialLoadAndSensorBurst() {
        // Same dataset and warm-up count before/after; timings are observations, not pass thresholds.
        repeat(2) { initial(100) }
        val sizes = JSONArray()
        for (size in listOf(100, 500, 1000, 2000)) {
            val samples = (0 until 5).map { initial(size) }
            val median = samples.map { it.getDouble("pipeline_ms") }.sorted()[2]
            sizes.put(JSONObject().put("entities", size).put("median_pipeline_ms", median)
                .put("samples_ms", JSONArray(samples.map { it.getDouble("pipeline_ms") }))
                .put("publications", samples.first().getInt("publications")))
        }
        println("PERFORMANCE_SCENARIO=" + JSONObject().put("initial", sizes).put("render", rendering()))
    }
}
