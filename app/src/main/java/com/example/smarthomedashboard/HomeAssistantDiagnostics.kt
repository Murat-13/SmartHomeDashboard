package com.example.smarthomedashboard

/** Remote error strings are untrusted and can contain credentials or arbitrary response fragments. */
internal object HomeAssistantDiagnostics {
    fun commandError(code:String?,forecast:Boolean=false):String = when(code) {
        "unknown_command","not_supported" -> "Home Assistant не поддерживает эту команду"
        "forecast_not_supported" -> "Прогноз для этого источника недоступен"
        "unauthorized","forbidden" -> "Home Assistant отказал в доступе к операции"
        "invalid_format" -> "Home Assistant отклонил формат команды"
        "not_found" -> "Запрошенный объект Home Assistant не найден"
        else -> if(forecast) "Не удалось получить прогноз Home Assistant" else "Не удалось выполнить команду Home Assistant"
    }
}
