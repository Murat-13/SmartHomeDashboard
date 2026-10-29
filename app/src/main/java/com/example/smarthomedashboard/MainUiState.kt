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
    val isEditMode: Boolean = false,
    val statusMessage: String? = null
)
