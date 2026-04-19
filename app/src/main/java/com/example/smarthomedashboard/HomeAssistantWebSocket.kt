package com.example.smarthomedashboard

import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HomeAssistantWebSocket(
    private val host: String,
    private val token: String,
    private val onStateChanged: (String, String) -> Unit,
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
        val url = "ws://$host/api/websocket"
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
                Log.e("HAWebSocket", "Authentication failed: ${json.optString("message")}")
                disconnect()
            }
            "event" -> {
                val event = json.optJSONObject("event")
                val eventType = event?.optString("event_type")
                if (eventType == "state_changed") {
                    val data = event.optJSONObject("data")
                    val entityId = data?.optString("entity_id")
                    val newState = data?.optJSONObject("new_state")?.optString("state")
                    if (entityId != null && newState != null) {
                        onStateChanged(entityId, newState)
                    }
                }
            }
            "result" -> {
                // Ответ на наши запросы, пока не используем
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

    fun getStates(callback: (JSONObject) -> Unit) {
        val requestId = messageId++
        val getStatesMessage = JSONObject().apply {
            put("id", requestId)
            put("type", "get_states")
        }

        // Временно сохраняем callback (в реальном приложении лучше использовать map)
        sendMessage(getStatesMessage)
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