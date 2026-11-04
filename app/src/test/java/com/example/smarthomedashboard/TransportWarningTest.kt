package com.example.smarthomedashboard

import org.junit.Assert.*
import org.junit.Test

/**
 * Решение владельца 04.11.2026: http в домашней сети — норма и молчит; внешний адрес без шифрования —
 * только с явным разрешением, с предупреждением. Адреса — искусственные (документационные диапазоны, .invalid).
 */
class TransportWarningTest {
    @Test fun publicCleartextAddressesAreExplainedWithoutEchoingAddress() {
        for (address in listOf("http://fake.invalid", "ws://fake.invalid", "192.0.2.10:8123", "http://203.0.113.7:8123")) {
            val warning = HomeAssistantAddress.transportWarning(listOf(address))
            assertNotNull(address, warning)
            assertFalse(warning!!.contains(address))
            assertTrue(warning.contains("без шифрования"))
            assertTrue(HomeAssistantAddress.needsInsecureConsent(address))
        }
    }

    @Test fun homeNetworkCleartextIsAllowedSilently() {
        val local = listOf(
            "localhost:8123", "http://127.0.0.1:8123", "192.168.1.20:8123", "http://10.0.0.5:8123",
            "http://172.20.1.1:8123", "http://169.254.10.1", "http://100.101.102.103:8123",
            "http://homeassistant:8123", "http://homeassistant.local:8123", "http://ha.lan", "http://ha.home.arpa",
            "http://[::1]:8123", "http://[fe80::1]:8123", "http://[fd12:3456::1]:8123")
        for (address in local) {
            assertTrue(address, HomeAssistantAddress.isPrivateNetwork(address))
            assertFalse(address, HomeAssistantAddress.needsInsecureConsent(address))
        }
        assertNull(HomeAssistantAddress.transportWarning(local))
    }

    @Test fun rangeEdgesAreNotMistakenForPrivate() {
        for (address in listOf("http://172.32.0.1", "http://172.15.0.1", "http://100.128.0.1", "http://192.169.0.1",
            "http://11.0.0.1", "http://[2001:db8::1]:8123", "http://local.example.invalid")) {
            assertFalse(address, HomeAssistantAddress.isPrivateNetwork(address))
            assertTrue(address, HomeAssistantAddress.needsInsecureConsent(address))
        }
    }

    @Test fun tlsAndUnconfiguredAddressesDoNotShowCleartextWarning() {
        for (address in listOf("https://fake.invalid", "wss://fake.invalid", "fake.invalid", "")) {
            assertNull(HomeAssistantAddress.transportWarning(listOf(address)))
            assertFalse(HomeAssistantAddress.needsInsecureConsent(address))
        }
    }

    @Test fun malformedOrCredentialBearingUrlsAreNotEchoedInWarning() {
        assertNull(HomeAssistantAddress.transportWarning(listOf("https://fake:FAKE-PRIVATE@fake.invalid/")))
        assertNull(HomeAssistantAddress.transportWarning(listOf(":// FAKE-PRIVATE")))
        assertFalse(HomeAssistantAddress.needsInsecureConsent(":// FAKE-PRIVATE"))
    }
}
