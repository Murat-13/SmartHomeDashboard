package com.example.smarthomedashboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    },
    // Пока работаем через запасной адрес, так часто проверяем, не вернулся ли основной (локальный).
    private val primaryProbeIntervalMs: Long = TimeUnit.MINUTES.toMillis(5),
    private val onStatesSnapshot: (List<HaEntityState>) -> Unit = { states ->
        states.forEach { onStateChanged(it.entityId, it.state, it.attributes) }
    },
    /**
     * Почасовой прогноз (отдельно от дневного). [supported] = false — HA отказал в подписке:
     * интеграция не поддерживает почасовой тип. Это не ошибка соединения и не показывается статусом.
     */
    private val onHourlyForecastChanged: (entityId: String, forecast: JSONArray?, supported: Boolean) -> Unit = { _, _, _ -> },
    private val onDailyForecastUnavailable: (entityId: String) -> Unit = {},
    /**
     * Ошибка самого соединения (обрыв, отказ авторизации, тайм-аут подключения). Вызывается только из
     * разрыва; [onError] остаётся для ошибок операций при живом соединении (команда, прогноз, списки).
     * По умолчанию совпадает с [onError] — поведение прежних клиентов не меняется.
     */
    private val onConnectionError: (String) -> Unit = onError,
    /** Владелец явно разрешил внешний адрес без шифрования; иначе такой адрес отклоняется до подключения. */
    private val allowInsecureRemote: Boolean = false,
    /**
     * Соединение без разрыва перешло с запасного адреса на основной (локальный): данные продолжают
     * приходить, снимок состояний запрошен заново. [onDisconnected]/[onConnected] при этом не вызываются.
     */
    private val onEndpointSwitched: () -> Unit = {}
) {
    private val hosts = (listOf(host) + alternateHosts).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    @Volatile private var generation = 0
    @Volatile private var running = false
    private var authenticated = false
    private var authSent = false
    private var endpointIndex = 0
    private var failures = 0
    private var messageId = 1
    private var stateSubscriptionSent = false
    private var statesRequested = false
    private var entitiesRequested = false
    private companion object {
        /** Проба основного адреса: время на подключение и авторизацию. */
        const val PROBE_TIMEOUT_MS = 15_000L
        /** Выдержка после авторизации пробы до перехода на неё. */
        const val PROBE_SETTLE_MS = 2_000L
        /** Сколько ждать завершения команды устройству перед переходом, и как часто проверять. */
        const val ADOPT_WAIT_MS = 15_000L
        const val ADOPT_RETRY_MS = 500L
        /** Основной адрес отвалился раньше этого после перехода — проверки становятся реже. */
        const val FLAP_WINDOW_MS = 3 * 60_000L
        const val MAX_PROBE_DELAY_MS = 30 * 60_000L
        /** Сколько ждать ответного кадра закрытия от прежнего сокета. */
        const val CLOSE_GRACE_MS = 5_000L
        const val DAILY = "daily"
        const val HOURLY = "hourly"
        /**
         * Единственные команды [read]: только чтение — конфигурация, история и адрес потока камеры
         * (`camera/stream` выдаёт временную ссылку HLS, ничего не меняя в HA).
         */
        val READ_ONLY = setOf("get_config", "history/history_during_period", "camera/stream")
    }
    private val forecastEntities = linkedSetOf<String>()
    private val hourlyForecastEntities = linkedSetOf<String>()
    private enum class ForecastStatus { PENDING, SUBSCRIBED, COMPLETE }
    private data class ForecastRequest(
        val entityId: String,
        var status: ForecastStatus = ForecastStatus.PENDING,
        var wanted: Boolean = true,
        val type: String = DAILY
    )
    private val forecastSubscriptions = mutableMapOf<Int, ForecastRequest>()
    private data class ServiceRequest(val entityId: String, val result: (ServiceCommandResult) -> Unit, val timeout: Runnable)
    private val serviceRequests = mutableMapOf<Int, ServiceRequest>()
    // Только идентификаторы, без callbacks/данных устройств. Ограниченный хвост поздних ACK.
    private val retiredServiceIds = linkedSetOf<Int>()
    internal val connectionGeneration: Int get() = generation
    private data class StateRequest(
        val id: Int,
        val changed: MutableSet<String>,
        val forecasts: MutableSet<String>,
        val timeout: Runnable
    )
    private var stateRequest: StateRequest? = null
    /** Разовые запросы чтения ([read]): ответ или null при ошибке/разрыве/тайм-ауте. */
    private data class ReadRequest(val result: (Any?) -> Unit, val timeout: Runnable)
    private val readRequests = mutableMapOf<Int, ReadRequest>()
    private val registryRequests = linkedSetOf<Int>()
    private var registryTimeout: Runnable? = null
    private var inboundGate: HaMessageGate? = null
    private val rejectedHosts = mutableSetOf<String>()
    private val tokenRejectedHosts = mutableSetOf<String>()
    private var reconnectTask: Runnable? = null
    private var timeoutTask: Runnable? = null
    private var probeTask: Runnable? = null
    private var probeSocket: WebSocket? = null
    private var probeListener: SocketListener? = null
    private var probeTimeout: Runnable? = null
    /** Сколько раз подряд основной адрес отвалился вскоре после возврата на него: проверки реже. */
    private var probeBackoff = 0
    /** Время перехода на основной адрес ([SystemClock.elapsedRealtime]); 0 — переходов не было. */
    private var switchedAt = 0L
    /**
     * Основной адрес отказал пробе в доступе (неверный токен, HTTP 401/403): больше не пробуем до нового
     * [connect]. Иначе неверный токен уходил бы туда каждые 5 минут — HA считает это неудачными входами
     * и при включённой защите может заблокировать IP планшета.
     */
    private var primaryRefusedProbe = false
    /** Прежний сокет после перехода: закрывается штатно, но не дольше [CLOSE_GRACE_MS]. */
    private var retiring: WebSocket? = null
    private val dropRetiring = Runnable { retiring?.cancel(); retiring = null }

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
            probeBackoff = 0
            primaryRefusedProbe = false
            openConnection()
        }
    }

    private fun openConnection() {
        if (!running) return
        reconnectTask = null
        cancelPrimaryProbe()
        cancelIncoming()
        val id = ++generation
        val gate = HaMessageGate().also { inboundGate = it }
        authenticated = false
        authSent = false
        stateSubscriptionSent = false
        forecastSubscriptions.clear()
        // ID не сбрасываются: HA требует лишь рост в пределах соединения, а сквозная нумерация
        // исключает совпадение ID запроса прошлого соединения с ответом нового.
        retiredServiceIds.clear()
        onStatus("Подключение: ${getHost()}")
        // Токен не должен уйти через интернет открытым текстом без согласия владельца: адрес отклоняется
        // навсегда для этого клиента, подключение переходит к следующему адресу (обычно локальному).
        if (!allowInsecureRemote && HomeAssistantAddress.needsInsecureConsent(getHost())) {
            fail(HomeAssistantAddress.INSECURE_REMOTE_REFUSED, permanent = true)
            return
        }
        val request = try {
            Request.Builder().url(HomeAssistantAddress.webSocketUrl(getHost())).build()
        } catch (_: IllegalArgumentException) {
            fail("Некорректный адрес Home Assistant", permanent = true)
            return
        }
        timeoutTask = Runnable {
            if (running && generation == id && !authenticated) fail("Тайм-аут подключения или авторизации")
        }.also { handler.postDelayed(it, 15_000L) }
        webSocket = client.newWebSocket(request, SocketListener(id, gate))
    }

    /**
     * Слушатель одного сокета. [owner] — поколение, от имени которого сокет работает. Пока сокет — проба
     * основного адреса ([probing]), его сообщения идут в [handleProbeMessage]; после перехода
     * ([adoptProbe]) он получает поколение основного соединения и дальше неотличим от обычного.
     */
    private inner class SocketListener(
        @Volatile var owner: Int,
        val gate: HaMessageGate,
        @Volatile var probing: Boolean = false
    ) : WebSocketListener() {
        private fun alive() = running && generation == owner

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (probing) {
                handler.post { handleProbeMessage(webSocket, text) }
                return
            }
            if (!gate.acquire()) return
            // OkHttp сериализует сообщения соединения. JSON и подготовка массива не занимают UI.
            val message = HaInboundMessage.parse(text) { alive() }
            if (message == null) {
                gate.release()
                return
            }
            val posted = handler.post {
                try {
                    if (alive()) handleMessage(message)
                } finally {
                    gate.release()
                }
            }
            if (!posted) gate.release()
        }

        /**
         * Проба это или уже основной сокет — решается в главном потоке: сокет может быть принят как основной
         * между событием OkHttp и его обработкой, и его обрыв не должен потеряться.
         */
        private fun ended(webSocket: WebSocket, httpCode: Int? = null, mainFailure: () -> Unit) = handler.post {
            if (probing) probeLost(webSocket, httpCode) else if (alive()) mainFailure()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Ответный close(code) не отправляем: при кадре без кода OkHttp передаёт 1005,
            // а close(1005) бросает IllegalArgumentException в главном потоке. fail() отменяет сокет.
            ended(webSocket) { fail("Сервер закрыл соединение") }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ended(webSocket) { fail("Соединение закрыто") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            ended(webSocket, code) {
                if (code == 401 || code == 403) fail("Сервер временно отказал в доступе (HTTP $code)")
                else fail(HomeAssistantAddress.errorMessage(t))
            }
        }
    }

    private fun fail(message: String, permanent: Boolean = false, tokenRejected: Boolean = false) {
        if (!running) return
        // Основной адрес отвалился вскоре после возврата на него — нестабильная сеть: следующая проверка реже.
        if (endpointIndex == 0 && switchedAt > 0) {
            probeBackoff = if (SystemClock.elapsedRealtime() - switchedAt < FLAP_WINDOW_MS) probeBackoff + 1 else 0
            switchedAt = 0L
        }
        teardown()
        if (permanent || tokenRejected) rejectedHosts.add(getHost())
        if (tokenRejected) tokenRejectedHosts.add(getHost())
        failures++
        onDisconnected()
        onConnectionError(message)
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

    /**
     * Единая очистка текущего сокета для [fail] и [disconnect]: сначала поколение
     * (cancel() сам может вызвать onFailure), затем все ожидающие запросы завершаются отказом,
     * чтобы ни один не дождался ответа уже другого соединения.
     */
    private fun teardown() {
        cancelPrimaryProbe()
        cancelIncoming()
        generation++
        timeoutTask?.let { handler.removeCallbacks(it) }
        timeoutTask = null
        webSocket?.cancel()
        webSocket = null
        authenticated = false
        finishServiceRequests()
        finishReadRequests()
        forecastSubscriptions.clear()
    }

    private fun handleMessage(message: HaInboundMessage) {
        val json = message.json
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
                if (!resubscribe()) return
                onConnected()
                schedulePrimaryProbe()
            }
            "result" -> if (authenticated) {
                serviceRequests.remove(json.optInt("id"))?.let { request ->
                    handler.removeCallbacks(request.timeout)
                    // ACK подтверждает обработку запроса, а не новое состояние устройства.
                    retireService(json.optInt("id"))
                    request.result(if (json.optBoolean("success", false)) ServiceCommandResult.ACKNOWLEDGED else ServiceCommandResult.REJECTED)
                    if (!json.optBoolean("success", false)) {
                        onError(HomeAssistantDiagnostics.commandError(json.optJSONObject("error")?.optString("code"), forecast = false))
                    }
                    return
                }
                if (json.optInt("id") in retiredServiceIds) return
                readRequests.remove(json.optInt("id"))?.let { request ->
                    handler.removeCallbacks(request.timeout)
                    request.result(if (json.optBoolean("success", false)) json.opt("result") else null)
                    return
                }
                forecastSubscriptions[json.optInt("id")]?.let {
                    handleForecastResult(json, it)
                    return
                }
                val id = json.optInt("id", -1)
                stateRequest?.takeIf { it.id == id }?.let { request ->
                    stateRequest = null
                    handler.removeCallbacks(request.timeout)
                    if (!json.optBoolean("success", false) || json.optJSONArray("result") == null) {
                        onError("Не удалось получить состояния Home Assistant")
                        return
                    }
                    val states = message.states.filter { it.entityId !in request.changed }.map { state ->
                        if (state.entityId !in request.forecasts || !state.attributes.has("forecast")) state
                        else state.copy(attributes = JSONObject().also { copy ->
                            state.attributes.keys().forEach { key ->
                                if (key != "forecast") copy.put(key, state.attributes.get(key))
                            }
                        })
                    }
                    onStatesSnapshot(states)
                    onEntitiesList?.invoke(message.entities)
                    return
                }
                if (registryRequests.remove(id)) {
                    // Уже полученный более новый список не должен заменяться поздним старым ответом.
                    registryRequests.removeAll { it < id }
                    if (registryRequests.isEmpty()) {
                        registryTimeout?.let { handler.removeCallbacks(it) }
                        registryTimeout = null
                    }
                    if (!json.optBoolean("success", false) || json.optJSONArray("result") == null) {
                        onError("Не удалось получить список сущностей Home Assistant")
                    } else {
                        onEntitiesList?.invoke(message.entities)
                    }
                    return
                }
                // Ошибку не скрываем, но неизвестный/повторный массив не считается снимком состояний.
                if (!json.optBoolean("success", true)) {
                    onError(HomeAssistantDiagnostics.commandError(json.optJSONObject("error")?.optString("code"), forecast = false))
                }
            }
            "event" -> if (authenticated) {
                val event = json.optJSONObject("event") ?: return
                if (event.optString("event_type") == "state_changed") {
                    val data = event.optJSONObject("data")
                    val state = data?.optJSONObject("new_state")
                    if (state != null) deliverState(data.optString("entity_id"),
                        state.optString("state", "unknown"), state.optJSONObject("attributes") ?: JSONObject())
                }
                forecastSubscriptions[json.optInt("id")]?.let { request ->
                    if (event.has("forecast") && request.status != ForecastStatus.COMPLETE) {
                        request.status = ForecastStatus.SUBSCRIBED
                        if (request.wanted && request.entityId in entitiesFor(request.type)) {
                            deliverForecast(request, event.opt("forecast"))
                        } else {
                            retireForecast(json.optInt("id"), request)
                        }
                    }
                    return
                }
                event.optJSONObject("a")?.let { added ->
                    added.keys().forEach { entityId ->
                        val state = added.optJSONObject(entityId)
                        deliverState(entityId, state?.optString("s") ?: "unknown",
                            state?.optJSONObject("a") ?: JSONObject())
                    }
                }
                event.optJSONObject("c")?.let { changed ->
                    changed.keys().forEach { entityId ->
                        changed.optJSONObject(entityId)?.optJSONObject("+")?.let {
                            deliverState(entityId, it.optString("s", "unknown"),
                                it.optJSONObject("a") ?: JSONObject())
                        }
                    }
                }
            }
        }
    }

    private fun deliverState(entityId: String, state: String, attributes: JSONObject) {
        stateRequest?.changed?.add(entityId)
        onStateChanged(entityId, state, attributes)
    }

    private fun cancelIncoming() {
        inboundGate?.cancel()
        inboundGate = null
        stateRequest?.timeout?.let { handler.removeCallbacks(it) }
        stateRequest = null
        registryTimeout?.let { handler.removeCallbacks(it) }
        registryTimeout = null
        registryRequests.clear()
    }

    @Suppress("UNUSED_PARAMETER")
    fun subscribeEntities(entityIds: List<String>) = onMain {
        // subscribe_events covers all entities, including advanced widget configs.
        statesRequested = true
        // Каждый вызов — пересинхронизация полным снимком, кроме точного дубля: запрос уже отправлен,
        // а после него не пришло ни одного события и прогноза (так было при auth_ok + onConnected и onResume).
        if (authenticated && !snapshotPendingUnchanged()) {
            ensureStates()
        }
    }

    private fun snapshotPendingUnchanged(): Boolean {
        val pending = stateRequest ?: return false
        return pending.changed.isEmpty() && pending.forecasts.isEmpty()
    }

    private fun ensureStates() {
        if (!stateSubscriptionSent) {
            stateSubscriptionSent = true
            command("subscribe_events").put("event_type", "state_changed").let { sendMessage(it) }
        }
        stateRequest?.timeout?.let { handler.removeCallbacks(it) }
        val message = command("get_states")
        val id = message.getInt("id")
        val timeout = Runnable {
            if (stateRequest?.id == id) {
                stateRequest = null
                onError("Нет ответа на запрос состояний Home Assistant")
            }
        }
        stateRequest = StateRequest(id, serviceRequests.values.mapTo(HashSet()) { it.entityId }, HashSet(), timeout)
        if (sendMessage(message)) handler.postDelayed(timeout, 30_000L)
        else stateRequest = null
    }

    /** Адрес сервера текущего подключения (локальный или внешний) для REST и потоков; null — нет авторизации. */
    val httpBase: String? get() = if (authenticated) runCatching { HomeAssistantAddress.httpBase(getHost()) }.getOrNull() else null

    /**
     * Разовое чтение из HA для истории мощности и потока камеры. Разрешены только команды из [READ_ONLY]: через этот
     * метод невозможно отправить управляющий вызов. [result] — на главном потоке, null при отказе.
     */
    fun read(type: String, params: JSONObject = JSONObject(), timeoutMs: Long = 60_000L, result: (Any?) -> Unit) = onMain {
        require(type in READ_ONLY) { "Не команда чтения: $type" }
        if (!authenticated) { result(null); return@onMain }
        val message = command(type)
        params.keys().forEach { key -> if (key != "id" && key != "type") message.put(key, params.get(key)) }
        val id = message.getInt("id")
        val timeout = Runnable { readRequests.remove(id)?.result?.invoke(null) }
        readRequests[id] = ReadRequest(result, timeout)
        if (sendMessage(message)) handler.postDelayed(timeout, timeoutMs)
        else readRequests.remove(id)?.result?.invoke(null)
    }

    private fun finishReadRequests() {
        val pending = readRequests.values.toList()
        readRequests.clear()
        pending.forEach { handler.removeCallbacks(it.timeout); it.result(null) }
    }

    // Прежний добавляющий API оставлен для существующих вызывающих экранов.
    fun subscribeForecast(entityId: String) = onMain {
        syncForecastSubscriptions(forecastEntities + entityId)
    }

    fun syncForecastSubscriptions(entityIds: Collection<String>) = onMain { sync(DAILY, entityIds) }

    /** Почасовые подписки — отдельный набор; дневные подписки им не затрагиваются. */
    fun syncHourlyForecastSubscriptions(entityIds: Collection<String>) = onMain { sync(HOURLY, entityIds) }

    private fun entitiesFor(type: String) = if (type == HOURLY) hourlyForecastEntities else forecastEntities

    private fun sync(type: String, entityIds: Collection<String>) {
        val desired = entityIds.filter { it.startsWith("weather.") }.toCollection(linkedSetOf())
        val entities = entitiesFor(type)
        entities.clear()
        entities.addAll(desired)
        forecastSubscriptions.toMap().forEach { (id, request) ->
            if (request.type == type && request.wanted && request.entityId !in desired) retireForecast(id, request)
        }
        ensureForecasts()
    }

    private fun ensureForecasts() {
        if (!authenticated) return
        listOf(DAILY, HOURLY).forEach { type ->
            entitiesFor(type).toList().forEach { entityId ->
                if (forecastSubscriptions.values.none { it.wanted && it.entityId == entityId && it.type == type }) sendForecast(entityId, type)
            }
        }
    }

    private fun sendForecast(entityId: String, type: String) {
        if (!authenticated) return
        val message = command("weather/subscribe_forecast")
            .put("entity_id", entityId).put("forecast_type", type)
        forecastSubscriptions[message.getInt("id")] = ForecastRequest(entityId, type = type)
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
            } else if (request.type == HOURLY) {
                // Почасовой тип поддерживают не все интеграции: отказ — состояние данных, а не статус ошибки.
                if (request.entityId in hourlyForecastEntities) onHourlyForecastChanged(request.entityId, null, false)
            } else {
                onDailyForecastUnavailable(request.entityId)
                val code = json.optJSONObject("error")?.optString("code")
                if (code != "forecast_not_supported") onError(HomeAssistantDiagnostics.commandError(code, forecast = true))
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
            if (request.wanted && request.entityId in entitiesFor(request.type)) {
                deliverForecast(request, forecast)
            }
        }
        if (!request.wanted) retireForecast(id, request)
    }

    private fun deliverForecast(request: ForecastRequest, value: Any?) {
        val entityId = request.entityId
        if (request.type == HOURLY) {
            // Почасовой не влияет на атрибут forecast и снимок состояний (там — дневной прогноз).
            when {
                value is JSONArray -> onHourlyForecastChanged(entityId, value, true)
                value === JSONObject.NULL -> onHourlyForecastChanged(entityId, null, true)
            }
            return
        }
        // Отсутствующее/неверного типа поле не приравниваем к явному JSON null.
        when {
            value is JSONArray -> {
                stateRequest?.forecasts?.add(entityId)
                onForecastChanged(entityId, value)
            }
            value === JSONObject.NULL -> {
                stateRequest?.forecasts?.add(entityId)
                onForecastChanged(entityId, null)
            }
        }
    }

    fun getEntities() = onMain {
        entitiesRequested = true
        if (authenticated) requestEntities()
    }

    private fun requestEntities() {
        registryTimeout?.let { handler.removeCallbacks(it) }
        val message = command("config/entity_registry/list")
        val id = message.getInt("id")
        registryRequests.add(id)
        // Старые запросы списка заменяемыми данными не должны накапливаться без ограничения.
        while (registryRequests.size > 32) registryRequests.remove(registryRequests.first())
        val timeout = Runnable {
            if (registryRequests.isNotEmpty()) {
                registryRequests.clear()
                registryTimeout = null
                onError("Нет ответа на запрос списка сущностей Home Assistant")
            }
        }
        registryTimeout = timeout
        if (sendMessage(message)) handler.postDelayed(timeout, 30_000L)
        else {
            registryRequests.clear()
            registryTimeout = null
        }
    }

    /**
     * Команда устройству. [data] — дополнительные поля `service_data` (например, `option` для
     * `select.select_option`); `entity_id` задаётся только параметром и полями [data] не перезаписывается.
     */
    fun callService(
        domain: String,
        service: String,
        entityId: String,
        data: Map<String, Any> = emptyMap(),
        onResult: (ServiceCommandResult) -> Unit = {}
    ): Boolean {
        // Не ставим действие в очередь даже при вызове не с UI-потока.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { onError("Команда не отправлена: повторите действие на панели") }
            return false
        }
        if (!authenticated) {
            onError("Нет соединения: дождитесь переподключения и повторите команду")
            return false
        }
        val serviceData = JSONObject()
        data.forEach { (key, value) -> serviceData.put(key, value) }
        serviceData.put("entity_id", entityId)
        val message = command("call_service").put("domain", domain).put("service", service)
            .put("service_data", serviceData)
        val id = message.getInt("id")
        val timeout = Runnable {
            serviceRequests.remove(id)?.let {
                retireService(id)
                it.result(ServiceCommandResult.ACK_TIMEOUT)
                onError("Нет ответа Home Assistant на команду")
            }
        }
        serviceRequests[id] = ServiceRequest(entityId, onResult, timeout)
        if (!sendMessage(message)) {
            serviceRequests.remove(id)?.let {
                retireService(id)
                it.result(ServiceCommandResult.SEND_FAILED)
            }
            return false
        }
        // Однократный предел ожидания ответа; без опроса и повторной отправки действия.
        if (id in serviceRequests) handler.postDelayed(timeout, 15_000L)
        stateRequest?.changed?.add(entityId)
        return true
    }

    internal fun cancelServiceRequests(entityId: String) {
        serviceRequests.filterValues { it.entityId == entityId }.keys.toList().forEach { id ->
            serviceRequests.remove(id)?.let { handler.removeCallbacks(it.timeout) }
            retireService(id)
        }
    }

    private fun retireService(id: Int) {
        retiredServiceIds.add(id)
        while (retiredServiceIds.size > 128) retiredServiceIds.remove(retiredServiceIds.first())
    }

    private fun finishServiceRequests() {
        val requests = serviceRequests.toMap()
        serviceRequests.clear()
        requests.keys.forEach(::retireService)
        requests.values.forEach {
            handler.removeCallbacks(it.timeout)
            it.result(ServiceCommandResult.DISCONNECTED)
        }
    }

    private fun command(type: String): JSONObject = JSONObject().put("id", messageId++).put("type", type)

    private fun sendMessage(message: JSONObject): Boolean {
        if (!authenticated) return false
        val sent = try {
            webSocket?.send(message.toString()) == true
        } catch (_: RuntimeException) {
            false
        }
        if (!sent) {
            if (running) fail("Ошибка отправки: соединение потеряно")
            else onError("Ошибка отправки: соединение потеряно")
        }
        return sent
    }

    // ---- Возврат на основной (локальный) адрес без разрыва ----

    /**
     * Панель стоит дома: основной адрес всегда в приоритете. Пока работаем через запасной, раз в
     * [primaryProbeIntervalMs] пробуем основной отдельным сокетом. Проба полностью авторизуется (тем же токеном,
     * что ушёл бы туда при обычном подключении) и только после [PROBE_SETTLE_MS] устойчивой работы заменяет
     * текущее соединение — без «нет связи», устаревших показаний и разрыва графика ([adoptProbe]).
     * Если основной адрес вскоре снова отваливается, проверки становятся реже (до [MAX_PROBE_DELAY_MS]).
     */
    private fun schedulePrimaryProbe() {
        cancelPrimaryProbe()
        // Отклонённый навсегда основной адрес (некорректный, без согласия на http) не проверяем:
        // иначе каждые 5 минут шло бы переключение на него и сразу обратно.
        if (!autoReconnect || !authenticated || endpointIndex == 0 || hosts.size < 2 || hosts[0] in rejectedHosts ||
            primaryRefusedProbe) return
        val id = generation
        val delay = (primaryProbeIntervalMs shl probeBackoff.coerceAtMost(5)).coerceAtMost(MAX_PROBE_DELAY_MS)
        probeTask = Runnable { probePrimary(id) }.also { handler.postDelayed(it, delay) }
    }

    private fun probePrimary(id: Int) {
        probeTask = null
        if (!running || generation != id || !authenticated || endpointIndex == 0) return
        if (!allowInsecureRemote && HomeAssistantAddress.needsInsecureConsent(hosts[0])) return
        val request = try {
            Request.Builder().url(HomeAssistantAddress.webSocketUrl(hosts[0])).build()
        } catch (_: IllegalArgumentException) { return }
        // Не ответил и не авторизовался за отведённое время — остаёмся на запасном, проверим позже.
        probeTimeout = Runnable { abandonProbe() }.also { handler.postDelayed(it, PROBE_TIMEOUT_MS) }
        val listener = SocketListener(owner = id, gate = HaMessageGate(), probing = true)
        probeListener = listener
        probeSocket = client.newWebSocket(request, listener)
    }

    /** Сообщения пробы: только авторизация. Данные по пробе не идут, пока она не стала основным сокетом. */
    private fun handleProbeMessage(socket: WebSocket, text: String) {
        if (socket !== probeSocket) return
        val id = probeListener?.owner ?: return
        when (runCatching { JSONObject(text).optString("type") }.getOrNull()) {
            "auth_required" -> socket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
            "auth_ok" -> {
                probeTimeout?.let { handler.removeCallbacks(it) }
                // Короткая выдержка: сокет, который сразу рвётся (нестабильный Wi-Fi), не заменит рабочий.
                probeTimeout = Runnable { adoptProbe(id, SystemClock.elapsedRealtime() + ADOPT_WAIT_MS) }
                    .also { handler.postDelayed(it, PROBE_SETTLE_MS) }
            }
            // Основной адрес не принял токен — работаем через запасной; это не ошибка текущего соединения.
            "auth_invalid" -> refuseProbe()
        }
    }

    private fun probeLost(socket: WebSocket, httpCode: Int? = null) {
        if (socket !== probeSocket) return
        if (httpCode == 401 || httpCode == 403) refuseProbe() else abandonProbe()
    }

    private fun refuseProbe() {
        primaryRefusedProbe = true
        onStatus("Локальный адрес отказал в доступе: проверки отключены до переподключения")
        cancelPrimaryProbe()
    }

    private fun abandonProbe() {
        cancelPrimaryProbe()
        if (running) schedulePrimaryProbe()
    }

    /**
     * Проба становится основным соединением. Старое закрывается штатно, его ожидающие чтения завершаются
     * отказом (повторятся по новому сокету), а подписки и полный снимок состояний запрашиваются заново.
     * Команды устройствам не обрываются: пока ответ на команду ещё ожидается, переход откладывается
     * (до [deadline]), затем отменяется до следующей проверки.
     */
    private fun adoptProbe(id: Int, deadline: Long) {
        probeTimeout = null
        val socket = probeSocket
        val listener = probeListener
        if (socket == null || listener == null || !running || generation != id || !authenticated || endpointIndex == 0) {
            cancelPrimaryProbe()
            return
        }
        if (serviceRequests.isNotEmpty()) {
            if (SystemClock.elapsedRealtime() >= deadline) { abandonProbe(); return }
            probeTimeout = Runnable { adoptProbe(id, deadline) }.also { handler.postDelayed(it, ADOPT_RETRY_MS) }
            return
        }
        probeSocket = null
        probeListener = null
        onStatus("Локальный адрес снова доступен: переход без разрыва")
        val previous = webSocket
        // Как в teardown(), но без «разрыва» для владельца: данные остаются достоверными.
        cancelIncoming()
        generation++
        timeoutTask?.let { handler.removeCallbacks(it) }
        timeoutTask = null
        authenticated = false
        finishServiceRequests()
        finishReadRequests()
        forecastSubscriptions.clear()
        retireSocket(previous)
        listener.owner = generation
        listener.probing = false
        webSocket = socket
        inboundGate = listener.gate
        endpointIndex = 0
        failures = 0
        authSent = true
        authenticated = true
        stateSubscriptionSent = false
        switchedAt = SystemClock.elapsedRealtime()
        // То же, что после auth_ok обычного подключения: подписки, снимок, прогнозы, список сущностей.
        if (!resubscribe()) return
        onEndpointSwitched()
    }

    /**
     * Подписки, снимок, прогнозы и список сущностей нового соединения. false — отправка не удалась и
     * соединение уже разорвано через fail(): владельцу нельзя сообщать «подключено» после «разрыва».
     */
    private fun resubscribe(): Boolean {
        val id = generation
        if (statesRequested) ensureStates()
        ensureForecasts()
        if (entitiesRequested) requestEntities()
        return generation == id && authenticated
    }

    /** Штатное закрытие прежнего сокета; если сервер не ответил за [CLOSE_GRACE_MS] — обрыв. */
    private fun retireSocket(socket: WebSocket?) {
        handler.removeCallbacks(dropRetiring)
        dropRetiring.run()
        socket ?: return
        retiring = socket
        if (runCatching { socket.close(1000, "Switching to primary address") }.getOrDefault(false)) {
            handler.postDelayed(dropRetiring, CLOSE_GRACE_MS)
        } else dropRetiring.run()
    }

    private fun cancelPrimaryProbe() {
        probeTask?.let { handler.removeCallbacks(it) }
        probeTask = null
        probeTimeout?.let { handler.removeCallbacks(it) }
        probeTimeout = null
        probeListener = null
        probeSocket?.cancel()
        probeSocket = null
    }

    fun disconnect() = onMain {
        running = false
        reconnectTask?.let { handler.removeCallbacks(it) }
        reconnectTask = null
        handler.removeCallbacks(dropRetiring)
        dropRetiring.run()
        teardown()
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
