package com.example.smarthomedashboard

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** События камер по ONVIF (docs/camera-tz.md, часть 2): разбор, длящиеся события, подписка и выборка. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameraEventsTest {
    private val sunk = mutableListOf<Pair<String, List<CameraEventType>>>()

    @Before fun setUp() { OnvifEvents.sink = { id, e -> sunk += id to e } }
    @After fun tearDown() { OnvifEvents.detach() }

    /** Ответ PullMessages как у Reolink (RuleEngine/MyRuleDetector/PeopleDetect + CellMotionDetector). */
    private fun pullResponse(vararg items: Pair<String, String?>) = """<?xml version="1.0"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
        xmlns:tev="http://www.onvif.org/ver10/events/wsdl" xmlns:wsnt="http://docs.oasis-open.org/wsn/b-2" xmlns:tt="http://www.onvif.org/ver10/schema"
        xmlns:tns1="http://www.onvif.org/ver10/topics"><s:Body><tev:PullMessagesResponse>""" +
        items.joinToString("") { (topic, value) ->
            "<wsnt:NotificationMessage><wsnt:Topic Dialect=\"http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet\">$topic</wsnt:Topic>" +
                "<wsnt:Message><tt:Message UtcTime=\"2026-11-20T01:00:00Z\" PropertyOperation=\"Changed\"><tt:Source>" +
                "<tt:SimpleItem Name=\"VideoSourceConfigurationToken\" Value=\"vsconf\"/></tt:Source><tt:Data>" +
                (value?.let { "<tt:SimpleItem Name=\"State\" Value=\"$it\"/>" } ?: "") +
                "</tt:Data></tt:Message></wsnt:Message></wsnt:NotificationMessage>"
        } + "</tev:PullMessagesResponse></s:Body></s:Envelope>"

    @Test fun parsesTopicsAndStatesOfDifferentMakers() {
        val events = Onvif.events(Onvif.parse(pullResponse(
            "tns1:RuleEngine/MyRuleDetector/PeopleDetect" to "true",
            "tns1:RuleEngine/CellMotionDetector/Motion" to "false",
            "tns1:RuleEngine/MyRuleDetector/VehicleDetect" to null)))
        assertEquals(listOf(true, false, null), events.map { it.state })
        assertEquals(CameraEventType.PERSON, Onvif.typeOf(events[0].topic))
        assertEquals(CameraEventType.MOTION, Onvif.typeOf(events[1].topic))
        assertEquals(CameraEventType.VEHICLE, Onvif.typeOf(events[2].topic))
        assertEquals(CameraEventType.ANIMAL, Onvif.typeOf("tns1:RuleEngine/MyRuleDetector/DogCatDetect"))
        assertEquals("Hikvision — движение", CameraEventType.MOTION, Onvif.typeOf("tns1:VideoSource/MotionAlarm"))
        assertEquals(null, Onvif.typeOf("tns1:Device/Trigger/DigitalInput"))
    }

    @Test fun lastingEventIsRefreshedUntilItEnds() {
        OnvifEvents.attach(org.robolectric.RuntimeEnvironment.getApplication())
        val looper = shadowOf(Looper.getMainLooper())
        // Человек появился — сразу событие; пока стоит — повтор раз в 4 с; ушёл — повторов нет.
        assertEquals(listOf(CameraEventType.PERSON), OnvifEvents.apply("cam", listOf(Onvif.Event("tns1:RuleEngine/MyRuleDetector/PeopleDetect", true))))
        looper.idle()
        assertEquals(1, sunk.size)
        looper.idleFor(java.time.Duration.ofSeconds(9))
        assertTrue("повторы, пока стоит: $sunk", sunk.size >= 3)
        assertEquals("повторное «true» — не новое событие", emptyList<CameraEventType>(),
            OnvifEvents.apply("cam", listOf(Onvif.Event("tns1:RuleEngine/MyRuleDetector/PeopleDetect", true))))
        OnvifEvents.apply("cam", listOf(Onvif.Event("tns1:RuleEngine/MyRuleDetector/PeopleDetect", false)))
        looper.idle()
        val before = sunk.size
        looper.idleFor(java.time.Duration.ofSeconds(9))
        assertEquals("ушёл — тишина", before, sunk.size)
        // Звонок у камеры пропускается; разовое событие — сразу, без повторов.
        assertTrue(OnvifEvents.apply("cam", listOf(Onvif.Event("tns1:RuleEngine/MyRuleDetector/Visitor", true))).isEmpty())
        assertEquals(listOf(CameraEventType.VEHICLE), OnvifEvents.apply("cam", listOf(Onvif.Event("tns1:RuleEngine/MyRuleDetector/VehicleDetect", null))))
        assertTrue(OnvifEvents.activeTypes("cam").isEmpty())
    }

    @Test fun subscribeAndPullUseAddressingAndCameraHost() {
        val requests = mutableListOf<Pair<String, String>>()
        Onvif.transport = { url, body ->
            requests += url to body
            when {
                body.contains("GetSystemDateAndTime") -> 500 to "<x/>"
                body.contains("GetCapabilities") -> 200 to """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tt="http://www.onvif.org/ver10/schema"><s:Body><GetCapabilitiesResponse><Capabilities><tt:Events><tt:XAddr>http://10.0.0.1:8000/onvif/event_service</tt:XAddr></tt:Events></Capabilities></GetCapabilitiesResponse></s:Body></s:Envelope>"""
                body.contains("CreatePullPointSubscription") -> 200 to """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://www.w3.org/2005/08/addressing"><s:Body><CreatePullPointSubscriptionResponse><SubscriptionReference><wsa:Address>http://10.0.0.1:8000/onvif/Subscription?Idx=7</wsa:Address></SubscriptionReference></CreatePullPointSubscriptionResponse></s:Body></s:Envelope>"""
                body.contains("PullMessages") -> 200 to pullResponse("tns1:RuleEngine/MyRuleDetector/PeopleDetect" to "true")
                else -> 200 to "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Body/></s:Envelope>"
            }
        }
        val sub = Onvif.subscribe("192.168.31.14", 8000, "admin", "secret")
        assertEquals("адрес подписки — по адресу камеры", "http://192.168.31.14:8000/onvif/Subscription?Idx=7", sub.address)
        val events = Onvif.pull(sub)
        assertEquals(CameraEventType.PERSON, Onvif.typeOf(events.single().topic))
        val pull = requests.last { it.second.contains("PullMessages") }
        assertEquals(sub.address, pull.first)
        assertTrue("WS-Addressing To", pull.second.contains("<a:To s:mustUnderstand=\"1\">http://192.168.31.14:8000/onvif/Subscription?Idx=7</a:To>"))
        assertTrue(pull.second.contains("PullMessagesRequest"))
    }
}
