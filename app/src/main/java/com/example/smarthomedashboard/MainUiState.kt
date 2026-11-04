package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONObject

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data class Connecting(val host: String) : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}

data class MainUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val singleStates: Map<String, String> = emptyMap(),
    val groupStates: Map<String, Map<String, String>> = emptyMap(),
    val entityAttributes: Map<String, JSONObject> = emptyMap(),
    val syntheticForecasts: Map<String, JSONArray?> = emptyMap(),
    val pendingCommands: Map<String, PendingCommand> = emptyMap(),
    val staleEntities: Set<String> = emptySet(),
    /** Соединение и синхронизация текущей сессии; не зависит от статуса-текста ошибки команды. */
    val haSession: HaSession = HaSession(),
    val hourlyForecasts: Map<String, JSONArray?> = emptyMap(),
    val hourlyUnsupported: Set<String> = emptySet(),
    val dailyForecasts: Map<String, JSONArray?> = emptyMap(),
    val dailyUnsupported: Set<String> = emptySet(),
    internal val yandexWeather: YandexClimateWeather = YandexClimateWeather(),
    val isEditMode: Boolean = false,
    val statusMessage: String? = null,
    /** Последняя ошибка операции при живом соединении (команда, прогноз, списки); не меняет [connectionStatus]. */
    val operationError: OperationError? = null
)

/**
 * Ошибка операции для кратковременного показа на плашке. [atMs] — `SystemClock.elapsedRealtime()`:
 * пересозданный экран показывает её только остаток срока, а не заново.
 */
data class OperationError(val message: String, val atMs: Long)
