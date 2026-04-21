package com.example.smarthomedashboard

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HomeAssistantWebSocket(
    private val host: String,
    private val token: String,
    private val onStateChanged: (entityId: String, state: String, attributes: JSONObject) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onEntitiesList: ((List<HaEntity>) -> Unit)? = null
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val messageId = AtomicInteger(1)
    private var isReconnecting = false

    fun connect() {
        val cleanHost = host.removePrefix("http://").removePrefix("https://")
        val wsScheme = if (host.startsWith("https")) "wss" else "ws"
        val url = "$wsScheme://$cleanHost/api/websocket"

        Log.d("HAWebSocket", "Connecting to: $url")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("HAWebSocket", "Connected")
                authenticate()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("HAWebSocket", "Closing: $reason")
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("HAWebSocket", "Closed")
                onDisconnected()
                scheduleReconnect()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("HAWebSocket", "Failure: ${t.message}")
                onDisconnected()
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isReconnecting) return
        isReconnecting = true
        Thread {
            Thread.sleep(5000)
            isReconnecting = false
            connect()
        }.start()
    }

    private fun authenticate() {
        sendMessage(JSONObject().apply {
            put("type", "auth")
            put("access_token", token)
        })
    }

    private fun handleMessage(text: String) {
        val json = JSONObject(text)
        val type = json.optString("type")
        when (type) {
            "auth_ok" -> {
                Log.d("HAWebSocket", "Auth OK")
                onConnected()
            }
            "auth_invalid" -> {
                Log.e("HAWebSocket", "Auth invalid")
                disconnect()
            }
            "result" -> {
                if (json.has("result")) {
                    val result = json.getJSONArray("result")
                    val entities = mutableListOf<HaEntity>()
                    for (i in 0 until result.length()) {
                        val obj = result.getJSONObject(i)
                        entities.add(HaEntity(
                            entityId = obj.optString("entity_id"),
                            name = obj.optString("name", obj.optString("entity_id")),
                            domain = obj.optString("entity_id").split(".").first()
                        ))
                    }
                    onEntitiesList?.invoke(entities)
                }
            }
            "event" -> {
                val event = json.optJSONObject("event")
                val data = event?.optJSONObject("a")
                if (data != null) {
                    val keys = data.keys()
                    while (keys.hasNext()) {
                        val entityId = keys.next()
                        val stateObj = data.optJSONObject(entityId)
                        val state = stateObj?.optString("s") ?: "unknown"
                        val attr = stateObj?.optJSONObject("a") ?: JSONObject()
                        onStateChanged(entityId, state, attr)
                    }
                }
            }
        }
    }

    fun getEntities() {
        sendMessage(JSONObject().apply {
            put("id", messageId.getAndIncrement())
            put("type", "config/entity_registry/list")
        })
    }

    fun subscribeEntities(entityIds: List<String>) {
        if (entityIds.isEmpty()) return
        sendMessage(JSONObject().apply {
            put("id", messageId.getAndIncrement())
            put("type", "subscribe_entities")
            put("entity_ids", entityIds)
        })
        Log.d("HAWebSocket", "Subscribed to: ${entityIds.joinToString()}")
    }

    fun callService(domain: String, service: String, entityId: String) {
        sendMessage(JSONObject().apply {
            put("id", messageId.getAndIncrement())
            put("type", "call_service")
            put("domain", domain)
            put("service", service)
            put("service_data", JSONObject().put("entity_id", entityId))
        })
        Log.d("HAWebSocket", "Call service: $domain.$service -> $entityId")
    }

    private fun sendMessage(json: JSONObject) {
        webSocket?.send(json.toString())
        Log.d("HAWebSocket", "Sent: $json")
    }

    fun disconnect() {
        webSocket?.close(1000, "User")
        webSocket = null
    }
}

data class HaEntity(
    val entityId: String,
    val name: String,
    val domain: String,
    val selected: Boolean = false
)