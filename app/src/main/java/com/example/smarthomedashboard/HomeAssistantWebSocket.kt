package com.example.smarthomedashboard

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONArray
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
    private val handler = Handler(Looper.getMainLooper())
    private var pendingEntityIds: List<String>? = null

    fun connect() {
        val cleanHost = host.removePrefix("http://").removePrefix("https://")
        // ВСЕГДА используем wss для внешнего адреса
        val wsScheme = if (host.startsWith("https") || host.contains("saidovmurat")) "wss" else "ws"
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
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            when (type) {
                "auth_ok" -> {
                    Log.d("HAWebSocket", "Auth OK")
                    onConnected()
                    pendingEntityIds?.let {
                        Log.d("HAWebSocket", "Sending pending subscription for: $it")
                        sendSubscribeMessage(it)
                        pendingEntityIds = null
                    }
                }
                "auth_invalid" -> {
                    Log.e("HAWebSocket", "Auth invalid")
                    disconnect()
                }
                "result" -> {
                    // ИСПРАВЛЕНО: проверяем тип result
                    val resultObj = json.opt("result")
                    if (resultObj is JSONArray) {
                        val entities = mutableListOf<HaEntity>()
                        for (i in 0 until resultObj.length()) {
                            val obj = resultObj.getJSONObject(i)
                            entities.add(HaEntity(
                                entityId = obj.optString("entity_id"),
                                name = obj.optString("name", obj.optString("entity_id")),
                                domain = obj.optString("entity_id").split(".").first()
                            ))
                        }
                        onEntitiesList?.invoke(entities)
                    } else {
                        // Это ответ на call_service или другую команду — игнорируем
                        Log.d("HAWebSocket", "Result is not JSONArray, ignoring")
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
        } catch (e: Exception) {
            Log.e("HAWebSocket", "Error parsing message: ${e.message}")
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

        if (webSocket == null) {
            Log.w("HAWebSocket", "WebSocket is null, saving subscription for later")
            pendingEntityIds = entityIds
            return
        }

        sendSubscribeMessage(entityIds)
    }

    private fun sendSubscribeMessage(entityIds: List<String>) {
        val message = JSONObject().apply {
            put("id", messageId.getAndIncrement())
            put("type", "subscribe_entities")
            put("entity_ids", JSONArray(entityIds))  // ИСПРАВЛЕНО: отправляем как JSONArray
        }
        webSocket?.send(message.toString())
        Log.d("HAWebSocket", "Sent subscribe_entities: $message")
    }

    fun callService(domain: String, service: String, entityId: String) {
        if (webSocket == null) {
            Log.w("HAWebSocket", "WebSocket is null, reconnecting...")
            connect()
            handler.postDelayed({
                callService(domain, service, entityId)
            }, 1000)
            return
        }

        val message = JSONObject().apply {
            put("id", messageId.getAndIncrement())
            put("type", "call_service")
            put("domain", domain)
            put("service", service)
            put("service_data", JSONObject().put("entity_id", entityId))
        }

        try {
            webSocket?.send(message.toString())
            Log.d("HAWebSocket", "Call service: $domain.$service -> $entityId")
        } catch (e: Exception) {
            Log.e("HAWebSocket", "Failed to send service call: ${e.message}")
            webSocket = null
            connect()
            handler.postDelayed({
                callService(domain, service, entityId)
            }, 1000)
        }
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