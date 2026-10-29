package com.example.smarthomedashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HaSnapshotBatchTest {
    private val owner = MainViewModel()
    private val sent = mutableListOf<JSONObject>()
    private val entities = mutableListOf<List<HaEntity>>()
    private val client: HomeAssistantWebSocket
    init {
        owner.connectWebSocket("http://[", "artificial-token", autoReconnect = false,
            onEntitiesListCallback = { entities.add(it) })
        client = requireNotNull(owner.getWebSocket())
        ReflectionHelpers.setField(client, "running", true)
        ReflectionHelpers.setField(client, "authenticated", true)
        ReflectionHelpers.setField(client, "webSocket", object : WebSocket {
            override fun request() = Request.Builder().url("http://localhost/artificial-only").build()
            override fun queueSize() = 0L
            override fun send(text: String): Boolean { sent.add(JSONObject(text)); return true }
            override fun send(bytes: ByteString) = false
            override fun close(code: Int, reason: String?) = true
            override fun cancel() = Unit
        })
    }
    private fun deliver(message: JSONObject) {
        client.javaClass.getDeclaredMethod("handleMessage", HaInboundMessage::class.java)
            .apply { isAccessible = true }.invoke(client, requireNotNull(HaInboundMessage.parse(message.toString())))
    }
    private fun request(): JSONObject {
        owner.subscribeEntities(emptyList())
        return sent.last { it.optString("type") == "get_states" }
    }
    private fun row(id: String = "switch.test", value: String = "off") =
        JSONObject().put("entity_id", id).put("state", value).put("attributes", JSONObject())
    private fun result(request: JSONObject, rows: JSONArray) =
        JSONObject().put("type", "result").put("id", request.getInt("id")).put("success", true).put("result", rows)
    private fun event(value: String) {
        deliver(JSONObject().put("type", "event").put("event", JSONObject().put("event_type", "state_changed")
            .put("data", JSONObject().put("entity_id", "switch.test").put("new_state", row(value = value)))))
    }
    @After fun cleanup() = owner.disconnectWebSocket()

    @Test fun aThousandInitialStatesHaveOnePublication() {
        val req = request()
        val rows = JSONArray()
        repeat(1000) { rows.put(row("sensor.artificial_$it", it.toString())) }
        var count = 0
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { owner.uiState.collect { count++ } }
        try {
            val before = count
            deliver(result(req, rows))
            assertEquals(1, count - before)
            assertEquals(1000, owner.devices.singleStates.size)
        } finally { collector.cancel() }
    }

    @Test fun anEventAfterRequestCannotBeOverwrittenByOldInitialState() {
        val req = request()
        event("on")
        deliver(result(req, JSONArray().put(row())))
        assertEquals("on", owner.devices.singleStates["switch.test"])
    }

    @Test fun duplicateResponseCannotUndoMoreRecentEvent() {
        val response = result(request(), JSONArray().put(row()))
        deliver(response)
        event("on")
        deliver(response)
        assertEquals("on", owner.devices.singleStates["switch.test"])
    }

    @Test fun registryAndUnknownResponsesCannotMutateDeviceStates() {
        owner.getEntities()
        val registry = sent.last { it.optString("type") == "config/entity_registry/list" }
        deliver(result(registry, JSONArray().put(row(value = "on"))))
        assertFalse(owner.devices.singleStates.containsKey("switch.test"))
        assertEquals("switch.test", entities.single().single().entityId)
        deliver(result(JSONObject().put("id", 99999), JSONArray().put(row(value = "on"))))
        assertFalse(owner.devices.singleStates.containsKey("switch.test"))
    }

    @Test fun acceptedCommandAfterRequestKeepsFactsAndPendingUntilActualEvent() {
        owner.onEntityStateChanged("switch.test", "off")
        val req = request()
        owner.commands.toggleSingle("switch.test")
        deliver(result(req, JSONArray().put(row())))
        assertEquals("off", owner.devices.singleStates["switch.test"])
        assertNotNull(owner.uiState.value.pendingCommands["switch.test"])
        event("off")
        assertEquals("off", owner.devices.singleStates["switch.test"])
    }

    @Test fun emptyAndSizedSnapshotsPreserveAllValidDataWithAtMostOnePublication() {
        var count = 0
        val observer = CoroutineScope(Dispatchers.Unconfined).launch { owner.uiState.collect { count++ } }
        try {
            for (size in listOf(0, 1, 100, 500, 2000)) {
                val rows = JSONArray()
                repeat(size) { i -> rows.put(row("sensor.size_${size}_$i", i.toString())) }
                val before = count
                deliver(result(request(), rows))
                assertTrue(count - before <= 1)
                repeat(size) { i -> assertEquals(i.toString(), owner.devices.singleStates["sensor.size_${size}_$i"]) }
            }
        } finally { observer.cancel() }
    }

    @Test fun malformedElementsDoNotAbortValidStates() {
        val rows = JSONArray().put("bad").put(JSONObject.NULL).put(42)
            .put(JSONObject().put("entity_id", JSONObject.NULL).put("state", "on"))
            .put(JSONObject().put("entity_id", "sensor.bad").put("state", JSONObject()))
            .put(row("sensor.valid", "12")).put(row("sensor.other", "13"))
        deliver(result(request(), rows))
        assertEquals(mapOf("sensor.valid" to "12", "sensor.other" to "13"), owner.devices.singleStates)
    }

    @Test fun newerSnapshotRequestInvalidatesOnlyTheOldResponse() {
        val old = request()
        event("on")
        val current = request()
        deliver(result(old, JSONArray().put(row())))
        assertEquals("on", owner.devices.singleStates["switch.test"])
        deliver(result(current, JSONArray().put(row(value = "unknown"))))
        assertEquals("unknown", owner.devices.singleStates["switch.test"])
    }

    @Test fun commandAlreadyPendingAtRequestIsNotUndoneByInitialSnapshot() {
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        deliver(result(request(), JSONArray().put(row())))
        assertEquals("off", owner.devices.singleStates["switch.test"])
        assertNotNull(owner.uiState.value.pendingCommands["switch.test"])
    }

    @Test fun newerForecastSurvivesOldSnapshotWithoutLosingWeatherState() {
        val initial = request()
        client.subscribeForecast("weather.test")
        val forecast = sent.last { it.optString("type") == "weather/subscribe_forecast" }
        deliver(JSONObject().put("type", "event").put("id", forecast.getInt("id"))
            .put("event", JSONObject().put("forecast", JSONArray("""[{"temperature":7}]"""))))
        val weather = row("weather.test", "sunny").put("attributes", JSONObject()
            .put("temperature", 20).put("forecast", JSONArray("""[{"temperature":99}]""")))
        deliver(result(initial, JSONArray().put(weather)))
        assertEquals("sunny", owner.devices.singleStates["weather.test"])
        val attrs = owner.devices.entityAttributes.getValue("weather.test")
        assertEquals(20, attrs.getInt("temperature"))
        assertEquals(7, attrs.getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
    }

    @Test fun failedAndMissingReadResponsesReleaseTheirTrackingWithoutReplay() {
        val state = request()
        deliver(JSONObject().put("type", "result").put("id", state.getInt("id")).put("success", false))
        assertNull(ReflectionHelpers.getField<Any?>(client, "stateRequest"))
        request()
        owner.getEntities()
        val sentBefore = sent.size
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(31, java.util.concurrent.TimeUnit.SECONDS)
        assertNull(ReflectionHelpers.getField<Any?>(client, "stateRequest"))
        assertTrue(ReflectionHelpers.getField<Set<Int>>(client, "registryRequests").isEmpty())
        assertEquals(sentBefore, sent.size)
    }

    @Test fun pendingRegistryRepliesAreCorrelatedAndNewerReplyWins() {
        owner.getEntities()
        val first = sent.last()
        owner.getEntities()
        val second = sent.last()
        deliver(result(first, JSONArray().put(row("light.first", "on"))))
        assertEquals("light.first", entities.last().single().entityId)
        deliver(result(second, JSONArray().put(row("light.second", "off"))))
        deliver(result(first, JSONArray().put(row("light.stale", "on"))))
        assertEquals("light.second", entities.last().single().entityId)
        assertEquals(2, entities.size)
        assertTrue(owner.devices.singleStates.isEmpty())
    }

    @Test fun unansweredRegistryRequestsStayBoundedAndLatestResponseClearsTracking() {
        repeat(100) { owner.getEntities() }
        assertEquals(32, ReflectionHelpers.getField<Set<Int>>(client, "registryRequests").size)
        deliver(result(sent.last(), JSONArray()))
        assertTrue(ReflectionHelpers.getField<Set<Int>>(client, "registryRequests").isEmpty())
    }

    @Test fun initialGroupReconciliationIsIncludedInTheSamePublishedSnapshot() {
        owner.connectWebSocket("http://[", "artificial-token", autoReconnect = false,
            onStatesSnapshotCallback = { owner.updateGroupState("group", "switch.test", "on") })
        var count = 0
        val observer = CoroutineScope(Dispatchers.Unconfined).launch { owner.uiState.collect { count++ } }
        try {
            val before = count
            deliver(result(request(), JSONArray().put(row(value = "on"))))
            assertEquals(1, count - before)
            assertEquals("on", owner.uiState.value.singleStates["switch.test"])
            assertEquals("on", owner.uiState.value.groupStates["group"]?.get("switch.test"))
        } finally { observer.cancel() }
    }
}
