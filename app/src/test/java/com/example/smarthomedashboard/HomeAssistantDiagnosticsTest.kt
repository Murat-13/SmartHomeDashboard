package com.example.smarthomedashboard
import org.junit.Assert.*
import org.junit.Test
class HomeAssistantDiagnosticsTest {
 @Test fun unknownErrorValuesNeverBecomeOutput() {
  for(code in listOf(null,"","FAKE-PRIVATE-TOKEN","https://fake.invalid/?token=FAKE","unauthorized\nFAKE")) {
   val message=HomeAssistantDiagnostics.commandError(code)
   assertEquals("Не удалось выполнить команду Home Assistant",message)
   assertEquals("Не удалось получить прогноз Home Assistant",HomeAssistantDiagnostics.commandError(code,true))
  }
 }
 @Test fun exactKnownCodesHaveUsefulBoundedMessages() {
  assertEquals("Home Assistant отказал в доступе к операции",HomeAssistantDiagnostics.commandError("unauthorized"))
  assertEquals("Прогноз для этого источника недоступен",HomeAssistantDiagnostics.commandError("forecast_not_supported",true))
  assertNotEquals(HomeAssistantDiagnostics.commandError(null),HomeAssistantDiagnostics.commandError("unknown_command"))
 }
}
