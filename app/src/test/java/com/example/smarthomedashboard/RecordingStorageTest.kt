package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// Запись с камер: настройки, имена файлов и порядок очистки (docs/recording.md, решения владельца 18.11.2026).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordingStorageTest {
    private val day = 86_400_000L
    private val gb = RecRetention.GB
    private val now = 1_763_500_000_000L

    private fun file(kind: RecFile.Kind, ageDays: Double, bytes: Long, camera: String = "door", starred: Boolean = false, open: Boolean = false) =
        RecFile(File("/x/${kind.prefix}_${(now - ageDays * day).toLong()}.mp4"), camera, kind,
            (now - ageDays * day).toLong(), 60_000, bytes, starred, open)

    private val rules: (String) -> RecRetention.Rules = { RecRetention.Rules(7 * day, 30 * day) }

    @Test fun expiredByOwnTrackOnly() {
        val oldC = file(RecFile.Kind.CONTINUOUS, 8.0, gb / 10)
        val freshC = file(RecFile.Kind.CONTINUOUS, 6.0, gb / 10)
        val eventTenDays = file(RecFile.Kind.EVENT, 10.0, gb / 10)
        val oldEvent = file(RecFile.Kind.EVENT, 31.0, gb / 10)
        val plan = RecRetention.plan(listOf(oldC, freshC, eventTenDays, oldEvent), rules, now, 100 * gb, 20 * gb, 2 * gb)
        assertEquals(setOf(oldC, oldEvent), plan.delete.toSet())
        assertFalse(plan.pause)
    }

    @Test fun overLimitDeletesOldestContinuousBeforeEvents() {
        val c = (1..10).map { file(RecFile.Kind.CONTINUOUS, it / 10.0, gb) } // 10 ГБ
        val e = (1..5).map { file(RecFile.Kind.EVENT, it / 10.0 + 2, gb) }   // 5 ГБ
        val plan = RecRetention.plan(c + e, rules, now, 100 * gb, 12 * gb, 2 * gb)
        // 15 ГБ при лимите 12: удаляются 3 самых старых отрезка 24/7, события целы.
        assertEquals(c.sortedBy { it.startMs }.take(3).toSet(), plan.delete.toSet())
    }

    @Test fun eventsGoOnlyAfterAllContinuousAndStarredStay() {
        val c = listOf(file(RecFile.Kind.CONTINUOUS, 0.5, gb))
        val starred = file(RecFile.Kind.EVENT, 5.0, gb, starred = true)
        val e = listOf(file(RecFile.Kind.EVENT, 4.0, gb), file(RecFile.Kind.EVENT, 3.0, gb), starred)
        val plan = RecRetention.plan(c + e, rules, now, 100 * gb, 1 * gb, 2 * gb)
        assertTrue(c.single() in plan.delete)
        assertTrue(e[0] in plan.delete); assertTrue(e[1] in plan.delete)
        assertFalse(starred in plan.delete)
    }

    @Test fun openSegmentIsNeverDeletedAndLowFreeSpacePauses() {
        val open = file(RecFile.Kind.CONTINUOUS, 0.0, gb, open = true)
        val old = file(RecFile.Kind.CONTINUOUS, 1.0, gb / 2)
        // Свободно 1 ГБ при остатке 2 ГБ: даже после удаления всего закрытого — меньше остатка → пауза.
        val plan = RecRetention.plan(listOf(open, old), rules, now, 1 * gb, 20 * gb, 2 * gb)
        assertEquals(listOf(old), plan.delete)
        assertTrue(plan.pause)
        val ok = RecRetention.plan(listOf(open, old), rules, now, 3 * gb / 2 + gb / 2, 20 * gb, 2 * gb)
        assertFalse(ok.pause)
    }

    @Test fun autoLimitIsSmallerOf20GbAndHalfOfSpace() {
        assertEquals(20 * gb, RecRetention.limit(0, 100 * gb, 0))
        assertEquals(15 * gb, RecRetention.limit(0, 20 * gb, 10 * gb))
        assertEquals(50 * gb, RecRetention.limit(50, 1 * gb, 0))
    }

    @Test fun namesRoundTrip() {
        val dir = File("/r/records/door/2025-11-18")
        val closed = RecLayout.parse(File(dir, RecLayout.closedName(RecFile.Kind.CONTINUOUS, 1_763_490_000_123, 60_040)), "door")!!
        assertEquals(1_763_490_000_123, closed.startMs); assertEquals(60_040, closed.durationMs); assertFalse(closed.open)
        val open = RecLayout.parse(File(dir, RecLayout.openName(RecFile.Kind.CONTINUOUS, 1_763_490_000_123)), "door")!!
        assertTrue(open.open)
        val star = RecLayout.parse(File(dir, RecLayout.closedName(RecFile.Kind.EVENT, 5_000_000_000, 10, starred = true)), "door")!!
        assertTrue(star.starred); assertEquals(RecFile.Kind.EVENT, star.kind)
        assertNull(RecLayout.parse(File(dir, "notes.txt"), "door"))
        assertEquals("2025-11-18", RecLayout.day(1_763_490_000_123))
        assertEquals("tile_1__x", RecLayout.cameraKey("tile 1/.x"))
    }

    @Test fun configDefaultsRoundTripAndOtherKeysKept() {
        val d = RecordingConfig()
        assertEquals(RecordingConfig.Mode.CONTINUOUS_EVENTS, d.mode)
        assertEquals(setOf("visitor", "person", "vehicle", "animal"), d.eventTriggers)
        assertEquals(10, d.preSeconds); assertEquals(20, d.postSeconds); assertEquals(7, d.keepContinuousDays)
        val base = DoorbellConfig(rtspSub = "rtsp://cam/sub").writeTo("{\"button_size\":480}")
        val custom = d.copy(mode = RecordingConfig.Mode.CONTINUOUS, keepContinuousDays = 3, audioContinuous = false)
        val text = custom.writeTo(base)
        assertEquals(custom, RecordingConfig.from(text))
        assertEquals("rtsp://cam/sub", DoorbellConfig.from(text).rtspSub)
        assertTrue(text.contains("button_size"))
        // Мусор и значения вне диапазона — к умолчаниям.
        val broken = RecordingConfig.from("{\"recording\":{\"mode\":\"x\",\"keep_continuous_days\":0,\"triggers\":[\"bad\",\"person\"]}}")
        assertEquals(RecordingConfig.Mode.CONTINUOUS_EVENTS, broken.mode)
        assertEquals(7, broken.keepContinuousDays)
        assertEquals(setOf("person"), broken.eventTriggers)
        assertEquals(RecordingConfig(), RecordingConfig.from("not json"))
    }
    @Test fun remoteModeIsSeparateAndOffByDefault() {
        val d = RecordingConfig()
        assertEquals(RecordingConfig.Mode.OFF, d.remoteMode)
        assertEquals(RecordingConfig.Mode.CONTINUOUS_EVENTS, d.effectiveMode(local = true))
        assertEquals(RecordingConfig.Mode.OFF, d.effectiveMode(local = false))
        val custom = d.copy(remoteMode = RecordingConfig.Mode.EVENTS)
        assertEquals(custom, RecordingConfig.from(custom.writeTo("{}")))
        // Старая конфигурация без ключа — удалённо не пишем.
        assertEquals(RecordingConfig.Mode.OFF, RecordingConfig.from("{\"recording\":{\"mode\":\"continuous\"}}").remoteMode)
    }

    @Test fun cameraIsLocalOnlyInPlainInterfaceSubnet() {
        fun ip(s: String) = java.net.InetAddress.getByName(s)
        val wifi = RecLocality.Iface("wlan0", ip("192.168.1.23"), 24)
        val vpn = RecLocality.Iface("tun0", ip("10.8.0.2"), 8)
        assertTrue(RecLocality.isLocal(ip("192.168.1.50"), listOf(wifi, vpn)))
        assertFalse("Камера в Чечне через роутерный VPN", RecLocality.isLocal(ip("192.168.8.50"), listOf(wifi, vpn)))
        assertFalse("Через туннель на самом планшете — издалека", RecLocality.isLocal(ip("10.1.2.3"), listOf(wifi, vpn)))
        assertTrue(RecLocality.isLocal(ip("127.0.0.1"), emptyList()))
        assertTrue(RecLocality.sameSubnet(ip("10.0.5.7"), ip("10.0.1.1"), 16))
        assertFalse(RecLocality.sameSubnet(ip("10.0.5.7"), ip("10.0.1.1"), 24))
        assertTrue(RecLocality.sameSubnet(ip("192.168.1.200"), ip("192.168.1.2"), 25) == false)
    }

    @Test fun bytesPerDayFromLastDayOfContinuousRecording() {
        val minute = 60_000L
        fun seg(ageMin: Long, bytes: Long, camera: String = "door", kind: RecFile.Kind = RecFile.Kind.CONTINUOUS) =
            RecFile(File("/x"), camera, kind, now - ageMin * minute, minute, bytes)
        val hour = (1L..60L).map { seg(it, 3_000_000) }
        // 3 МБ в минуту → ~4,3 ГБ в сутки; события, другие камеры и старше суток не считаются.
        val noise = listOf(seg(5, 999_000_000, kind = RecFile.Kind.EVENT), seg(5, 999_000_000, camera = "gate"), seg(2_000, 999_000_000))
        assertEquals(3_000_000L * 1440, RecRetention.bytesPerDay(hour + noise, "door", now))
        assertNull("Меньше получаса — без оценки", RecRetention.bytesPerDay(hour.take(20), "door", now))
    }

    @Test fun g711DecodesStandardValuesAndAacConfig() {
        assertEquals(8, G711Depacketizer.decodeALaw(0xD5))
        assertEquals(-8, G711Depacketizer.decodeALaw(0x55))
        assertEquals(32256, G711Depacketizer.decodeALaw(0xAA))
        assertEquals(0, G711Depacketizer.decodeULaw(0xFF))
        assertEquals(-32124, G711Depacketizer.decodeULaw(0x00))
        assertEquals(32124, G711Depacketizer.decodeULaw(0x80))
        val track = SdpParser.parse("m=audio 0 RTP/AVP 8\r\n").single()
        val d = G711Depacketizer.create(track)!!
        val pcm = d.push(RtpPacket.parse(RecordingTestSupport.rtp(8, false, 1, 160, byteArrayOf(0xD5.toByte(), 0x55)), 14)!!)
        assertEquals(160L, pcm.timestamp); assertEquals(listOf<Short>(8, -8), pcm.samples.toList())
        assertEquals(listOf(0x15, 0x88), AacConfig.audioSpecificConfig(8000, 1).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x14, 0x08), AacConfig.audioSpecificConfig(16000, 1).map { it.toInt() and 0xFF })
    }
}
