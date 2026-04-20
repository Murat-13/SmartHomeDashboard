package com.example.smarthomedashboard

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HomeAssistantWebSocket(
    private val host: String,
    private val token: String,
    private val onStateChanged: (entityId: String, state: String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var messageId = 1

    fun connect() {
        val cleanHost = host
            .removePrefix("http://")
            .removePrefix("https://")

        val wsScheme = if (host.startsWith("https")) "wss" else "ws"
        val url = "$wsScheme://$cleanHost/api/websocket"

        Log.d("HAWebSocket", "Connecting to: $url")

        val request = Request.Builder()
            .url(url)
            .build()

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
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("HAWebSocket", "Failure: ${t.message}")
                onDisconnected()
            }
        })
    }

    private fun authenticate() {
        val authMessage = JSONObject().apply {
            put("type", "auth")
            put("access_token", token)
        }
        sendMessage(authMessage)
    }

    private fun handleMessage(text: String) {
        val json = JSONObject(text)
        val type = json.optString("type")

        when (type) {
            "auth_ok" -> {
                Log.d("HAWebSocket", "Authentication successful")
                onConnected()
                subscribeToEvents()
            }
            "auth_invalid" -> {
                Log.e("HAWebSocket", "Authentication failed")
                disconnect()
            }
            "event" -> {
                val event = json.optJSONObject("event")
                if (event != null) {
                    val eventType = event.optString("event_type")
                    if (eventType == "state_changed") {
                        val data = event.optJSONObject("data")
                        if (data != null) {
                            val entityId = data.optString("entity_id")
                            val newStateObj = data.optJSONObject("new_state")
                            val newState = newStateObj?.optString("state")
                            if (entityId.isNotEmpty() && newState != null) {
                                onStateChanged(entityId, newState)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun subscribeToEvents() {
        val subscribeMessage = JSONObject().apply {
            put("id", messageId++)
            put("type", "subscribe_events")
            put("event_type", "state_changed")
        }
        sendMessage(subscribeMessage)
    }

    private fun sendMessage(json: JSONObject) {
        webSocket?.send(json.toString())
        Log.d("HAWebSocket", "Sent: $json")
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }
}