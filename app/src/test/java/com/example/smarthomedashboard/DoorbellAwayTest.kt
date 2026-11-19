package com.example.smarthomedashboard

import android.os.Looper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Звонок без хозяина (docs/ring-away-tz.md): уведомление на телефон и автоответ своей фразой. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DoorbellAwayTest {
    private val context = RuntimeEnvironment.getApplication()
    private val tile = "door_away_test"
    private val config = DoorbellConfig(rtspSub = "rtsp://192.0.2.10/sub", rtspUser = "admin", camera = "camera.door")
    private val sent = mutableListOf<Triple<String, String, Map<String, Any>>>()
    private val spoken = mutableListOf<Int>()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private var now = 1_000_000L

    @Before fun setUp() {
        DoorbellAway.reset()
        DoorbellAway.sender = { d, s, data, done -> sent += Triple(d, s, data); done(true) }
        DoorbellAway.speaker = { _, _, pcm -> spoken += pcm.size }
        DoorbellAway.clock = { now }
        DoorbellPhrases.setList(context, tile, listOf(DoorbellPhrases.Phrase("abc123", "Оставьте у калитки")))
        DoorbellPhrases.save(context, tile, "abc123", ShortArray(8_000) { 1_000 }, 8_000)
    }

    @After fun tearDown() {
        DoorbellAway.reset()
        DoorbellAway.save(context, tile, DoorbellAway.Settings())
        DoorbellPhrases.delete(context, tile, "abc123")
    }

    @Test fun phoneServicesAndNotificationContent() {
        val services = JSONObject().put("notify", JSONObject().put("mobile_app_pixel", JSONObject())
            .put("mobile_app_sm_s918b", JSONObject()).put("persistent_notification", JSONObject()))
            .put("light", JSONObject().put("turn_on", JSONObject()))
        assertEquals(listOf("notify.mobile_app_pixel", "notify.mobile_app_sm_s918b"), DoorbellAway.phoneServices(services))
        assertTrue(DoorbellAway.phoneServices(null).isEmpty())
        val with = DoorbellAway.notification("Калитка", "camera.door", 0, tile)
        assertEquals("Калитка", with["title"])
        assertTrue((with["message"] as String).startsWith("Звонят в калитку · "))
        assertEquals("/api/camera_proxy/camera.door", (with["data"] as JSONObject).getString("image"))
        assertFalse("без камеры в HA — без снимка", (DoorbellAway.notification("", "", 0, tile)["data"] as JSONObject).has("image"))
        assertEquals(DoorbellConfig.DEFAULT_TITLE, DoorbellAway.notification("", "", 0, tile)["title"])
        assertEquals("iPhone: звук и «срочное»", "time-sensitive", (with["data"] as JSONObject).getJSONObject("push").getString("interruption-level"))
        assertTrue(DoorbellAway.isNotifyService("notify.mobile_app_pixel"))
        assertFalse("чужая служба не принимается", DoorbellAway.isNotifyService("lock.unlock"))
    }

    @Test fun ringSendsNotificationOnlyFromChosenTablet() {
        DoorbellAway.onRing(context, tile, "Домофон", config)
        assertTrue("служба не выбрана — ничего", sent.isEmpty())
        DoorbellAway.save(context, tile, DoorbellAway.Settings(notifyService = "notify.mobile_app_pixel"))
        DoorbellAway.onRing(context, tile, "Домофон", config)
        assertEquals("notify", sent.single().first)
        assertEquals("mobile_app_pixel", sent.single().second)
        assertTrue(spoken.isEmpty())
    }

    @Test fun autoReplyAfterDelayUnlessAnsweredAndNotTwiceAMinute() {
        DoorbellAway.save(context, tile, DoorbellAway.Settings(autoReply = true, phraseId = "abc123", delaySeconds = 20))
        assertTrue(DoorbellAway.autoReplyReady(context, tile))
        // Подошли к окну звонка — фразы нет.
        DoorbellAway.onRing(context, tile, "Домофон", config)
        looper.idleFor(10, TimeUnit.SECONDS)
        DoorbellAway.answered(tile)
        looper.idleFor(30, TimeUnit.SECONDS)
        assertTrue(spoken.isEmpty())
        // Не подошли — через 20 с фраза.
        DoorbellAway.onRing(context, tile, "Домофон", config)
        looper.idleFor(19, TimeUnit.SECONDS)
        assertTrue("раньше срока — нет", spoken.isEmpty())
        looper.idleFor(2, TimeUnit.SECONDS)
        assertEquals(listOf(8_000), spoken)
        // Повторный звонок через полминуты — фраза не повторяется.
        now += 30_000
        DoorbellAway.onRing(context, tile, "Домофон", config)
        looper.idleFor(25, TimeUnit.SECONDS)
        assertEquals(1, spoken.size)
        // Через минуту — снова.
        now += 61_000
        DoorbellAway.onRing(context, tile, "Домофон", config)
        looper.idleFor(25, TimeUnit.SECONDS)
        assertEquals(2, spoken.size)
    }

    @Test fun repeatedRingRestartsCountdownAndDisabledOrNoPhraseIsSilent() {
        DoorbellAway.save(context, tile, DoorbellAway.Settings(autoReply = true, phraseId = "abc123", delaySeconds = 20))
        DoorbellAway.onRing(context, tile, "Домофон", config)
        looper.idleFor(15, TimeUnit.SECONDS)
        DoorbellAway.onRing(context, tile, "Домофон", config)
        looper.idleFor(15, TimeUnit.SECONDS)
        assertTrue("отсчёт начался заново", spoken.isEmpty())
        looper.idleFor(6, TimeUnit.SECONDS)
        assertEquals(1, spoken.size)
        // Выключили кнопкой в раскрытом домофоне — молчит.
        now += 120_000
        DoorbellAway.save(context, tile, DoorbellAway.settings(context, tile).copy(autoReply = false))
        DoorbellAway.onRing(context, tile, "Домофон", config)
        looper.idleFor(30, TimeUnit.SECONDS)
        assertEquals(1, spoken.size)
        // Фраза удалена — молчит, хотя включено.
        DoorbellAway.save(context, tile, DoorbellAway.settings(context, tile).copy(autoReply = true))
        DoorbellPhrases.delete(context, tile, "abc123")
        assertFalse(DoorbellAway.autoReplyReady(context, tile))
        DoorbellAway.onRing(context, tile, "Домофон", config)
        looper.idleFor(30, TimeUnit.SECONDS)
        assertEquals(1, spoken.size)
        // Без RTSP разговора нет — и автоответа тоже.
        DoorbellAway.onRing(context, tile, "Домофон", DoorbellConfig())
        assertFalse(DoorbellAway.waiting(tile))
    }

    /** Найдено на планшете: переключатель включён, фраза не выбрана — автоответ берёт первую записанную. */
    @Test fun autoReplyUsesFirstPhraseWhenNoneChosen() {
        DoorbellAway.save(context, tile, DoorbellAway.Settings(autoReply = true, phraseId = ""))
        assertTrue(DoorbellAway.autoReplyReady(context, tile))
        assertEquals("abc123", DoorbellAway.phrase(context, tile)?.id)
        DoorbellAway.onRing(context, tile, "Домофон", config)
        looper.idleFor(25, TimeUnit.SECONDS)
        assertEquals(listOf(8_000), spoken)
    }

    @Test fun expandedDoorbellAutoReplyButtonToggles() {
        val card = DoorbellCard(context, expanded = true)
        val button = card.findViewWithTag<android.widget.TextView>("door_autoreply")
        assertEquals("фраза не выбрана — кнопки нет", android.view.View.GONE, button.visibility)
        var state: Boolean? = null
        card.onAutoReply = { state = it }
        card.autoReply(false)
        assertEquals("Автоответ: выкл", button.text.toString())
        button.performClick()
        assertEquals(true, state)
        assertEquals("Автоответ: вкл", button.text.toString())
        card.ringMode = true
        assertEquals("в окне звонка кнопки нет", android.view.View.GONE, button.visibility)
        card.dispose()
    }
}
