package com.example.smarthomedashboard

/** ACK подтверждает запрос HA, но не физическое состояние устройства. */
enum class ServiceCommandResult {
    ACKNOWLEDGED, REJECTED, SEND_FAILED, DISCONNECTED, ACK_TIMEOUT
}

enum class ConfirmedState { ON, OFF, UNAVAILABLE }

data class PendingCommand(
    val entityId: String,
    val expectedState: String,
    val operationId: Long,
    val connectionGeneration: Int,
    val startedAtMs: Long,
    val deadlineAtMs: Long,
    val observedAfterStart: Boolean = false,
    val alreadyConfirmed: Boolean = false,
    val sent: Boolean = false,
    val acknowledged: Boolean = false
)
