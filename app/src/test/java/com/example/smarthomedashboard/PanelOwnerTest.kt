package com.example.smarthomedashboard

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * BUG-01 (аудит 25.11.2026): в режиме «главный экран» после «Назад» новая панель появляется раньше, чем уничтожена прежняя.
 * Прежняя, уходя, не отключает у новой голос, домофон, калитки, запись мощности и признак «панель жива».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PanelOwnerTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun setUp() { PanelAlerts.detach() }
    @After fun tearDown() { PanelAlerts.detach() }

    private fun attached(): Any? = ReflectionHelpers.getField(DoorbellHub, "app")

    @Test fun leavingPanelDoesNotDisconnectItsReplacement() {
        val old = Any()
        val next = Any()
        PanelAlerts.attach(context, old)
        assertNotNull(attached())
        PanelAlerts.attach(context, next)
        PanelAlerts.detach(old)
        assertNotNull("домофон и калитки остались у новой панели", attached())
        PanelAlerts.detach(next)
        assertNull("последняя панель ушла — отключено", attached())
    }

    /**
     * Второй аудит 25.11.2026, NEW-RUN-1 (решение владельца: откладывать): уведомление во время разговора не звучит — фраза из динамика
     * ушла бы в микрофон, к человеку у калитки. Звучит, когда разговор закончен.
     */
    @Test fun voiceAlertWaitsForEndOfTalk() {
        PanelBusy.resetForTests()
        PanelAlerts.attach(context, Any())
        val talk = Any()
        try {
            PanelBusy.talk(talk, true)
            PanelAlerts.external(Alert(AlertEvent.DOORBELL_OFFLINE, "Домофон не на связи"), volume = 1f)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse("во время разговора панель молчит", PanelAlerts.speaking)
            PanelBusy.talk(talk, false)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue("после разговора уведомление звучит", PanelAlerts.speaking)
        } finally { PanelBusy.resetForTests() }
    }

    @Test fun editorWithoutPanelNeverTakesOwnership() {
        val panel = Any()
        PanelAlerts.attach(context, panel)
        PanelAlerts.attach(context)
        PanelAlerts.detach(panel)
        assertNull(attached())
        // Подключил редактор (панели нет) — следующая панель всё равно становится владельцем.
        PanelAlerts.attach(context)
        PanelAlerts.attach(context, panel)
        PanelAlerts.detach(Any())
        assertNotNull(attached())
        PanelAlerts.detach(panel)
        assertNull(attached())
    }

    @Test fun powerHistoryBelongsToTheLastPanel() {
        val store = PowerHistoryStore(context, name = null)
        val recorder = PowerHistoryRecorder(store, worker = { it.run() })
        try {
            val old = MainViewModel().apply { powerHistory = recorder }
            val next = MainViewModel().apply { powerHistory = recorder }
            ReflectionHelpers.setField(recorder, "connected", true)
            old.disconnectWebSocket()
            assertTrue("запись новой панели не прервана", ReflectionHelpers.getField(recorder, "connected"))
            next.disconnectWebSocket()
            assertFalse(ReflectionHelpers.getField(recorder, "connected"))
        } finally { recorder.stop(); store.close() }
    }

    @Test fun oldScreenDoesNotClearAliveMarkOfNewOne() {
        val field = MainActivity::class.java.getDeclaredField("isAlive").apply { isAccessible = true }
        val mark = MainActivity::class.java.getDeclaredField("aliveScreen").apply { isAccessible = true }
        val next = Robolectric.buildActivity(MainActivity::class.java).get()
        val old = Robolectric.buildActivity(MainActivity::class.java).get()
        try {
            field.setBoolean(null, true)
            mark.set(null, java.lang.ref.WeakReference(next))
            MainActivity.screenGone(old)
            assertTrue("прежний экран признак новой панели не снимает", MainActivity.isAlive)
            MainActivity.screenGone(next)
            assertFalse(MainActivity.isAlive)
        } finally { field.setBoolean(null, false); mark.set(null, null) }
    }
}
