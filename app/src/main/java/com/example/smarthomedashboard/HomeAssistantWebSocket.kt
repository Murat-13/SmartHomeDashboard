package com.example.smarthomedashboard

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * All connection state and callbacks are serialized on the main looper.
 * A generation invalidates callbacks from cancelled sockets before another socket is opened.
 */
class HomeAssistantWebSocket(
    private val host: String,
    private val token: String,
    private val onStateChanged: (entityId: String, state: String, attributes: JSONObject) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onEntitiesList: ((List<HaEntity>) -> Unit)? = null,
    alternateHosts: List<String> = emptyList(),
    private val autoReconnect: Boolean = true,
    private val onError: (String) -> Unit = {},
    private val onStatus: (String) -> Unit = {},
    // Прежние клиенты сохраняют старую форму callback; Activity задаёт явный
    // обработчик прогноза и не использует unknown как признак частичного обновления.
    private val onForecastChanged: (entityId: String, forecast: JSONArray?) -> Unit = { entityId, forecast ->
        onStateChanged(entityId, "unknown", JSONObject().put("forecast", forecast ?: JSONObject.NULL))
    }
) {
    private val hosts = (listOf(host) + alternateHosts).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var generation = 0
    private var running = false
    private var authenticated = false
    private var authSent = false
    private var endpointIndex = 0
    private var failures = 0
    private var messageId = 1
    private var stateSubscriptionSent = false
    private var statesRequested = false
    private var entitiesRequested = false
    private val forecastEntities = linkedSetOf<String>()
    private enum class ForecastStatus { PENDING, SUBSCRIBED, COMPLETE }
    private data class ForecastRequest(
        val entityId: String,
        var status: ForecastStatus = ForecastStatus.PENDING,
        var wanted: Boolean = true
    )
    private val forecastSubscriptions = mutableMapOf<Int, ForecastRequest>()
    private val rejectedHosts = mutableSetOf<String>()
    private val tokenRejectedHosts = mutableSetOf<String>()
    private var reconnectTask: Runnable? = null
    private var timeoutTask: Runnable? = null

    fun getHost(): String = hosts.getOrElse(endpointIndex) { host }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }

    fun connect() = onMain {
        if (!running) {
            running = true
            failures = 0
            rejectedHosts.clear()
            tokenRejectedHosts.clear()
            endpointIndex = 0
            openConnection()
        }
    }

    private fun openConnection() {
        if (!running) return
        reconnectTask = null
        val id = ++generation
        authenticated = false
        authSent = false
        stateSubscriptionSent = false
        forecastSubscriptions.clear()
        messageId = 1
        onStatus("Подключение: ${getHost()}")
        val request = try {
            Request.Builder().url(HomeAssistantAddress.webSocketUrl(getHost())).build()
        } catch (_: IllegalArgumentException) {
            fail("Некорректный адрес Home Assistant", permanent = true)
            return
        }
        timeoutTask = Runnable {
            if (running && generation == id && !authenticated) fail("Тайм-аут подключения или авторизации")
        }.also { handler.postDelayed(it, 15_000L) }
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            private fun current(block: () -> Unit) = handler.post {
                if (running && generation == id) block()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                current { handleMessage(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                current {
                    webSocket.close(code, null)
                    fail("Сервер закрыл соединение")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                current { fail("Соединение закрыто") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                current {
                    if (response?.code == 401 || response?.code == 403) {
                        fail("Сервер временно отказал в доступе (HTTP ${response.code})")
                    } else {
                        fail(HomeAssistantAddress.errorMessage(t))
                    }
                }
            }
        })
    }

    private fun fail(message: String, permanent: Boolean = false, tokenRejected: Boolean = false) {
        if (!running) return
        // Invalidate first: cancel() can itself cause onFailure.
        generation++
        timeoutTask?.let { handler.removeCallbacks(it) }
        timeoutTask = null
        webSocket?.cancel()
        webSocket = null
        authenticated = false
        forecastSubscriptions.clear()
        if (permanent || tokenRejected) rejectedHosts.add(getHost())
        if (tokenRejected) tokenRejectedHosts.add(getHost())
        failures++
        onDisconnected()
        onError(message)
        val available = hosts.indices.filter { hosts[it] !in rejectedHosts }
        if (available.isEmpty() && autoReconnect && tokenRejectedHosts.isNotEmpty()) {
            // A revoked token must not cause a tight loop, but may become usable again later.
            val delay = TimeUnit.MINUTES.toMillis(10)
            onStatus("Неверный или отозванный токен HA. Повторная проверка через 10 минут")
            reconnectTask = Runnable {
                rejectedHosts.removeAll(tokenRejectedHosts)
                tokenRejectedHosts.clear()
                failures = 0
                endpointIndex = hosts.indices.first { hosts[it] !in rejectedHosts }
                openConnection()
            }.also { handler.postDelayed(it, delay) }
            return
        }
        if (available.isEmpty() || (!autoReconnect && failures >= hosts.size)) {
            running = false
            onStatus("Ошибка: $message")
            return
        }
        endpointIndex = (1..hosts.size).map { (endpointIndex + it) % hosts.size }
            .first { it in available }
        val delay = if (failures < hosts.size) 0L else HomeAssistantAddress.retryDelay(failures - hosts.size)
        onStatus("Переподключение через ${delay / 1000} сек.: $message")
        reconnectTask = Runnable { openConnection() }.also { handler.postDelayed(it, delay) }
    }

    private fun handleMessage(text: String) {
        val json = try { JSONObject(text) } catch (_: Exception) { return }
        when (json.optString("type")) {
            "auth_required" -> if (!authenticated && !authSent) {
                authSent = true
                webSocket?.send(JSONObject().put("type", "auth").put("access_token", token).toString())
            }
            "auth_invalid" -> fail("Отказ авторизации: неверный или отозванный токен", tokenRejected = true)
            "auth_ok" -> if (authSent && !authenticated) {
                authenticated = true
                failures = 0
                timeoutTask?.let { handler.removeCallbacks(it) }
                timeoutTask = null
                // Восстанавливаем только актуальные желаемые источники до вызова владельца.
                if (statesRequested) ensureStates()
                ensureForecasts()
                if (entitiesRequested) requestEntities()
                onConnected()
            }
            "result" -> if (authenticated) {
                forecastSubscriptions[json.optInt("id")]?.let {
                    handleForecastResult(json, it)
                    return
                }
                if (!json.optBoolean("success", true)) {
                    onError(HomeAssistantDiagnostics.commandError(json.optJSONObject("error")?.optString("code"), forecast = false))
                    return
                }
                val result = json.optJSONArray("result") ?: return
                val entities = mutableListOf<HaEntity>()
                for (i in 0 until result.length()) {
                    val obj = result.optJSONObject(i) ?: continue
                    val entityId = obj.optString("entity_id")
                    if (entityId.isEmpty()) continue
                    val attrs = obj.optJSONObject("attributes") ?: JSONObject()
                    if (obj.has("state")) onStateChanged(entityId, obj.optString("state"), attrs)
                    entities.add(HaEntity(entityId, displayName(entityId, obj, attrs), entityId.substringBefore(".")))
                }
                onEntitiesList?.invoke(entities)
            }
            "event" -> if (authenticated) {
                val event = json.optJSONObject("event") ?: return
                if (event.optString("event_type") == "state_changed") {
                    val data = event.optJSONObject("data")
                    val state = data?.optJSONObject("new_state")
                    if (state != null) onStateChanged(data.optString("entity_id"),
                        state.optString("state", "unknown"), state.optJSONObject("attributes") ?: JSONObject())
                }
                forecastSubscriptions[json.optInt("id")]?.let { request ->
                    if (event.has("forecast") && request.status != ForecastStatus.COMPLETE) {
                        request.status = ForecastStatus.SUBSCRIBED
                        if (request.wanted && request.entityId in forecastEntities) {
                            deliverForecast(request.entityId, event.opt("forecast"))
                        } else {
                            retireForecast(json.optInt("id"), request)
                        }
                    }
                    return
                }
                event.optJSONObject("a")?.let { added ->
                    added.keys().forEach { entityId ->
                        val state = added.optJSONObject(entityId)
                        onStateChanged(entityId, state?.optString("s") ?: "unknown",
                            state?.optJSONObject("a") ?: JSONObject())
                    }
                }
                event.optJSONObject("c")?.let { changed ->
                    changed.keys().forEach { entityId ->
                        changed.optJSONObject(entityId)?.optJSONObject("+")?.let {
                            onStateChanged(entityId, it.optString("s", "unknown"),
                                it.optJSONObject("a") ?: JSONObject())
                        }
                    }
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun subscribeEntities(entityIds: List<String>) = onMain {
        // subscribe_events covers all entities, including advanced widget configs.
        statesRequested = true
        if (authenticated) {
            ensureStates()
        }
    }

    private fun ensureStates() {
        if (!stateSubscriptionSent) {
            stateSubscriptionSent = true
            command("subscribe_events").put("event_type", "state_changed").let { sendMessage(it) }
        }
        sendMessage(command("get_states"))
    }

    // Прежний добавляющий API оставлен для существующих вызывающих экранов.
    fun subscribeForecast(entityId: String) = onMain {
        syncForecastSubscriptions(forecastEntities + entityId)
    }

    fun syncForecastSubscriptions(entityIds: Collection<String>) = onMain {
        val desired = entityIds.filter { it.startsWith("weather.") }.toCollection(linkedSetOf())
        forecastEntities.clear()
        forecastEntities.addAll(desired)
        forecastSubscriptions.toMap().forEach { (id, request) ->
            if (request.wanted && request.entityId !in desired) retireForecast(id, request)
        }
        ensureForecasts()
    }

    private fun ensureForecasts() {
        if (!authenticated) return
        forecastEntities.toList().forEach { entityId ->
            if (forecastSubscriptions.values.none { it.wanted && it.entityId == entityId }) sendForecast(entityId)
        }
    }

    private fun sendForecast(entityId: String) {
        if (!authenticated) return
        val message = command("weather/subscribe_forecast")
            .put("entity_id", entityId).put("forecast_type", "daily")
        forecastSubscriptions[message.getInt("id")] = ForecastRequest(entityId)
        sendMessage(message)
    }

    private fun retireForecast(id: Int, request: ForecastRequest) {
        request.wanted = false
        when (request.status) {
            // Запрос ещё не доказывает наличие подписки. Дождёмся ACK или event,
            // игнорируя данные, но сохраняя возможность отменить созданную подписку.
            ForecastStatus.PENDING -> Unit
            ForecastStatus.SUBSCRIBED -> {
                forecastSubscriptions.remove(id)
                sendMessage(command("unsubscribe_events").put("subscription", id))
            }
            ForecastStatus.COMPLETE -> forecastSubscriptions.remove(id)
        }
    }

    private fun handleForecastResult(json: JSONObject, request: ForecastRequest) {
        if (request.status == ForecastStatus.COMPLETE) return
        val id = json.optInt("id")
        if (!json.optBoolean("success", true)) {
            request.status = ForecastStatus.COMPLETE
            if (!request.wanted) {
                forecastSubscriptions.remove(id)
            } else {
                onError(HomeAssistantDiagnostics.commandError(json.optJSONObject("error")?.optString("code"), forecast = true))
            }
            return
        }

        if (!json.has("result") || json.isNull("result")) {
            // Официальный subscribe_forecast подтверждается пустым result;
            // его id становится id подписки, данные затем приходят через event.
            request.status = ForecastStatus.SUBSCRIBED
        } else {
            // Ответ с данными завершает одноразовый запрос, а не создаёт подписку.
            request.status = ForecastStatus.COMPLETE
            val result = json.opt("result")
            val forecast = if (result is JSONArray) result else (result as? JSONObject)?.opt("forecast")
            if (request.wanted && request.entityId in forecastEntities) {
                deliverForecast(request.entityId, forecast)
            }
        }
        if (!request.wanted) retireForecast(id, request)
    }

    private fun deliverForecast(entityId: String, value: Any?) {
        // Отсутствующее/неверного типа поле не приравниваем к явному JSON null.
        when {
            value is JSONArray -> onForecastChanged(entityId, value)
            value === JSONObject.NULL -> onForecastChanged(entityId, null)
        }
    }

    fun getEntities() = onMain {
        entitiesRequested = true
        if (authenticated) requestEntities()
    }

    private fun requestEntities() {
        sendMessage(command("config/entity_registry/list"))
    }

    fun callService(domain: String, service: String, entityId: String) = onMain {
        // Do not replay stale device actions after a network outage.
        if (!authenticated) {
            onError("Нет соединения: дождитесь переподключения и повторите команду")
        } else {
            sendMessage(command("call_service").put("domain", domain).put("service", service)
                .put("service_data", JSONObject().put("entity_id", entityId)))
        }
    }

    private fun command(type: String): JSONObject = JSONObject().put("id", messageId++).put("type", type)

    private fun sendMessage(message: JSONObject) {
        if (authenticated && webSocket?.send(message.toString()) != true) fail("Ошибка отправки: соединение потеряно")
    }

    fun disconnect() = onMain {
        running = false
        generation++
        reconnectTask?.let { handler.removeCallbacks(it) }
        timeoutTask?.let { handler.removeCallbacks(it) }
        reconnectTask = null
        timeoutTask = null
        authenticated = false
        webSocket?.cancel()
        webSocket = null
        forecastSubscriptions.clear()
    }
}

/**
 * Понятное имя сущности для списков выбора. get_states отдаёт friendly_name в атрибутах, а реестр
 * сущностей — name (заданное пользователем, чаще всего null) и original_name от интеграции.
 * JSON null нельзя читать через optString: он становится строкой «null».
 */
internal fun displayName(entityId: String, entry: JSONObject, attributes: JSONObject): String {
    fun text(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }
    return text(attributes, "friendly_name") ?: text(entry, "name") ?: text(entry, "original_name") ?: entityId
}

data class HaEntity(
    val entityId: String,
    val name: String,
    val domain: String,
    val selected: Boolean = false
)
