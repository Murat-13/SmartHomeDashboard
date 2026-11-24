package com.example.smarthomedashboard

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** «Home Assistant найден по новому адресу» (docs/setup-tz.md, решение 6): только предложение. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HaAddressWatchTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private var time = 1_000_000L
    private var changes = 0
    private val browsers = mutableListOf<Browser>()
    private var answers = HaServer.State.READY

    private class Browser : HaBrowser {
        var onService: ((HaService) -> Unit)? = null
        override fun start(onService: (HaService) -> Unit) { this.onService = onService }
        override fun stop() { onService = null }
    }

    private val watch by lazy {
        HaAddressWatch(context, { changes++ }, { Browser().also { b -> browsers += b } },
            { HaServer.Check(answers, HaServer.origin(it), "") }, { time })
    }

    @Before fun setUp() { prefs.edit().clear().putString("ha_local_host", "http://192.168.77.10:8123").commit() }

    private fun service(host: String, uuid: String) = HaService("Дом", host, 8123, mapOf("uuid" to uuid))
    private fun settle() { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
    private fun minute(onLocal: Boolean) { time += 60_000; watch.tick(onLocal); shadowOf(Looper.getMainLooper()).idle() }
    private fun endBrowse() = shadowOf(Looper.getMainLooper()).idleFor(9, TimeUnit.SECONDS)

    private fun learn() {
        minute(true)
        browsers.single().onService!!(service("192.168.77.11", "other"))
        browsers.single().onService!!(service("192.168.77.10", "mine"))
        settle()
        assertEquals("mine", prefs.getString(HaConnector.KEY_UUID, null))
        endBrowse()
    }

    @Test fun sameServerAtNewAddressIsOfferedAfterTwoMinutesAndAppliedOnlyByHuman() {
        learn()
        repeat(2) { minute(false) }
        assertEquals("до двух минут mDNS не смотрим", 1, browsers.size)
        minute(false)
        assertEquals(2, browsers.size)
        browsers.last().onService!!(service("192.168.77.99", "stranger"))
        settle()
        assertNull("чужой Home Assistant не предлагается", watch.suggestion)
        browsers.last().onService!!(service("192.168.77.33", "mine"))
        settle()
        assertEquals("http://192.168.77.33:8123", watch.suggestion)
        assertEquals("без человека ничего не меняется", "http://192.168.77.10:8123", prefs.getString("ha_local_host", null))
        assertTrue(watch.accept())
        assertEquals("http://192.168.77.33:8123", prefs.getString("ha_local_host", null))
        assertNull(watch.suggestion)
        assertFalse(watch.accept())
    }

    @Test fun declinedAddressIsNotOfferedAgainAndConnectionClearsOffer() {
        learn()
        repeat(3) { minute(false) }
        browsers.last().onService!!(service("192.168.77.33", "mine"))
        settle()
        watch.decline()
        assertNull(watch.suggestion)
        endBrowse()
        repeat(3) { minute(false) }
        browsers.last().onService!!(service("192.168.77.33", "mine"))
        settle()
        assertNull("«Не сейчас» — больше не предлагается", watch.suggestion)
        endBrowse()
        repeat(3) { minute(false) }
        browsers.last().onService!!(service("192.168.77.34", "mine"))
        settle()
        assertEquals("http://192.168.77.34:8123", watch.suggestion)
        minute(true)
        assertNull("связь вернулась", watch.suggestion)
    }

    @Test fun encryptedOrAbsentLocalAddressAndUnknownServerAreLeftAlone() {
        prefs.edit().putString("ha_local_host", "https://home.example.com").putString(HaConnector.KEY_UUID, "mine").commit()
        repeat(5) { minute(false) }
        prefs.edit().putString("ha_local_host", "").commit()
        repeat(5) { minute(false) }
        prefs.edit().putString("ha_local_host", "http://192.168.77.10:8123").remove(HaConnector.KEY_UUID).commit()
        repeat(5) { minute(false) }
        assertTrue("без известного идентификатора не ищем", browsers.isEmpty())
        assertEquals(0, changes)
    }

    @Test fun silentAddressIsNotOffered() {
        learn()
        answers = HaServer.State.NOT_HA
        repeat(3) { minute(false) }
        browsers.last().onService!!(service("192.168.77.33", "mine"))
        settle()
        assertNull(watch.suggestion)
    }
}
