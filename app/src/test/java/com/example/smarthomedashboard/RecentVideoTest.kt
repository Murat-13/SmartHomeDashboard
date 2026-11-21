package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Видео с последнего ключевого кадра для нового плеера ([RecentVideo], docs/doorbell.md, «Быстрый старт плитки»). */
class RecentVideoTest {
    /** Пакет RTP: в полезной части — его номер [id]; [marker] — пакет закрывает кадр. */
    private fun packet(id: Int, marker: Boolean, size: Int = 20) = ByteArray(size).also {
        it[0] = 0x80.toByte()
        it[1] = ((if (marker) 0x80 else 0) or 96).toByte()
        it[12] = id.toByte()
    }

    private fun RecentVideo.frame(vararg ids: Int, key: Boolean, size: Int = 20, marker: Boolean = true) {
        ids.forEachIndexed { index, id -> add(packet(id, marker && index == ids.lastIndex, size), size) }
        frame(key)
    }

    private fun RecentVideo.ids(): List<Int> = mutableListOf<Int>().also { out -> replay { data, offset, _ -> out += data[offset + 12].toInt() } }

    @Test fun replayStartsWithLastKeyFrame() {
        val recent = RecentVideo()
        recent.frame(1, key = false)
        recent.frame(2, key = false)
        assertEquals("до ключевого кадра отдавать нечего", emptyList<Int>(), recent.ids())
        recent.frame(3, 4, 5, key = true)           // SPS, PPS, IDR — один кадр тремя пакетами
        recent.frame(6, key = false)
        recent.frame(7, 8, key = false)
        assertEquals(listOf(3, 4, 5, 6, 7, 8), recent.ids())
        recent.add(packet(9, marker = false), 20)   // кадр ещё идёт — его начало тоже отдаётся: дальше плеер получит живое
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9), recent.ids())
        recent.add(packet(10, marker = true), 20)
        recent.frame(false)
        recent.frame(11, 12, key = true)
        assertEquals("новый ключевой кадр — новая группа", listOf(11, 12), recent.ids())
    }

    @Test fun packetLengthsSurvive() {
        val recent = RecentVideo()
        recent.add(packet(1, marker = false, size = 1400), 1400)
        recent.add(packet(2, marker = true, size = 37), 37)
        recent.frame(true)
        val lengths = mutableListOf<Int>()
        recent.replay { _, _, length -> lengths += length }
        assertEquals(listOf(1400, 37), lengths)
    }

    @Test fun groupLargerThanBufferIsNotKeptAndRetriedLater() {
        val recent = RecentVideo(capacity = 1_000)
        recent.frame(1, key = true, size = 400)
        assertEquals(listOf(1), recent.ids())
        recent.frame(2, key = false, size = 400)
        recent.frame(3, key = false, size = 400)    // не поместился: группа не хранится
        assertEquals(emptyList<Int>(), recent.ids())
        // Следующие группы какое-то время не копятся вовсе (основной поток не помещается никогда).
        repeat(30) { recent.frame(10 + it, key = true, size = 400); assertEquals(emptyList<Int>(), recent.ids()) }
        recent.frame(50, key = false, size = 100)
        recent.frame(51, key = true, size = 100)    // потом — новая попытка: поток мог стать легче
        recent.frame(52, key = false, size = 100)
        assertEquals(listOf(51, 52), recent.ids())
    }

    @Test fun streamWithoutFrameMarkersIsNotKept() {
        val recent = RecentVideo()
        recent.frame(1, 2, key = true, marker = false)
        recent.frame(3, key = false, marker = false)
        assertEquals("границы кадров неизвестны — начать с ключевого нельзя", emptyList<Int>(), recent.ids())
        recent.frame(4, key = false)
        recent.frame(5, 6, key = true)
        assertEquals(listOf(5, 6), recent.ids())
    }

    @Test fun clearForgetsPreviousCameraSession() {
        val recent = RecentVideo()
        recent.frame(1, key = true)
        recent.frame(2, key = false)
        recent.clear()
        assertEquals(emptyList<Int>(), recent.ids())
        recent.frame(3, key = false)
        assertEquals(emptyList<Int>(), recent.ids())
        recent.frame(4, key = true)
        assertEquals(listOf(4), recent.ids())
    }
}
