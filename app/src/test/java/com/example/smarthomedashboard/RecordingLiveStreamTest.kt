package com.example.smarthomedashboard

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Запись с живого RTSP-потока (ручная проверка, в CI пропускается): `REC_LIVE_URL` — адрес потока,
 * `REC_LIVE_OUT` — папка для отрезков, `REC_LIVE_SECONDS` — длительность (по умолчанию 25), отрезки по 5 с.
 * Логин/пароль — `REC_LIVE_USER` / `REC_LIVE_PASSWORD`. Готовые файлы проверяются ffprobe/плеером.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordingLiveStreamTest {
    @Test fun recordLiveStreamIntoSegments() {
        val url = System.getenv("REC_LIVE_URL")
        assumeTrue("REC_LIVE_URL не задан — проверка с живым потоком пропущена", !url.isNullOrBlank())
        val out = File(System.getenv("REC_LIVE_OUT") ?: "build/rec-live").apply { mkdirs() }
        val seconds = System.getenv("REC_LIVE_SECONDS")?.toLongOrNull() ?: 25
        val segments = mutableListOf<RecFile>()
        val states = mutableListOf<ContinuousRecorder.State>()
        val recorder = ContinuousRecorder(
            camera = "live", url = url!!, user = System.getenv("REC_LIVE_USER").orEmpty(),
            password = System.getenv("REC_LIVE_PASSWORD"), audio = true, root = { out }, canWrite = { true },
            onSegment = { synchronized(segments) { segments += it } },
            onStatus = { _, s -> synchronized(states) { states += s.state } },
            segmentMs = 5_000
        )
        recorder.start()
        Thread.sleep(seconds * 1_000)
        recorder.stop()
        println("Отрезки: " + segments.joinToString { "${it.path.name} ${it.bytes} Б" })
        println("Состояния: $states")
        assertTrue(segments.isNotEmpty())
    }
}
