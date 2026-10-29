package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class HaInboundMessageTest {
    @Test fun decodingChecksCancellationDuringPreparation() {
        val rows = JSONArray()
        repeat(2000) { rows.put(JSONObject().put("entity_id", "sensor.artificial_$it").put("state", "1")) }
        val raw = JSONObject().put("type", "result").put("success", true).put("result", rows).toString()
        var checks = 0
        assertNull(HaInboundMessage.parse(raw) { ++checks < 10 })
        assertEquals(10, checks)
        assertNull(HaInboundMessage.parse("{broken"))
    }

    @Test fun cancelReleasesReaderWaitingForBoundedDeliverySlot() {
        val gate = HaMessageGate(1)
        assertTrue(gate.acquire())
        val worker = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val waiting = worker.submit<Boolean> { entered.countDown(); gate.acquire() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            gate.cancel()
            assertFalse(waiting.get(2, TimeUnit.SECONDS))
            assertFalse(gate.acquire())
        } finally { worker.shutdownNow() }
    }

    @Test fun releasingSlotDeliversWaitingMessageWithoutConflation() {
        val gate = HaMessageGate(1)
        assertTrue(gate.acquire())
        val worker = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val waiting = worker.submit<Boolean> { entered.countDown(); gate.acquire() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            gate.release()
            assertTrue(waiting.get(2, TimeUnit.SECONDS))
            gate.release()
        } finally { gate.cancel(); worker.shutdownNow() }
    }
}
