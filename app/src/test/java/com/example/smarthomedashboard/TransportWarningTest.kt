package com.example.smarthomedashboard
import org.junit.Assert.*
import org.junit.Test
class TransportWarningTest {
 @Test fun cleartextSchemesAndLegacyLocalAddressesAreExplainedWithoutEchoingAddress(){
  for(address in listOf("http://fake.invalid","ws://fake.invalid","192.0.2.10:8123","localhost:8123")) {
   val warning=HomeAssistantAddress.transportWarning(listOf(address))
   assertNotNull(warning);assertFalse(warning!!.contains(address));assertTrue(warning.contains("HTTP/WS"))
  }
 }
 @Test fun tlsAndUnconfiguredAddressesDoNotShowCleartextWarning(){
  for(address in listOf("https://fake.invalid","wss://fake.invalid","fake.invalid",""))assertNull(HomeAssistantAddress.transportWarning(listOf(address)))
 }
 @Test fun malformedOrCredentialBearingUrlsAreNotEchoedInWarning(){
  assertNull(HomeAssistantAddress.transportWarning(listOf("https://fake:FAKE-PRIVATE@fake.invalid/")))
  assertNull(HomeAssistantAddress.transportWarning(listOf(":// FAKE-PRIVATE")))
 }
}
