package com.example.smarthomedashboard.deep

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smarthomedashboard.AacEncoder
import com.example.smarthomedashboard.ContinuousRecorder
import com.example.smarthomedashboard.G711Depacketizer
import com.example.smarthomedashboard.RecFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Запись с камер на устройстве (docs/recording.md): настоящий AAC-кодер Android для звука G.711 и запись
 * субпотока RTSP-стенда CI (аргумент rtspHost, без камер владельца). Пишет только в свою временную папку.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RecordingDeepTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun g711ToAacProducesDecodableSound() {
        val encoder = AacEncoder()
        val frames = mutableListOf<com.example.smarthomedashboard.AudioFrame>()
        // 2 с синуса 440 Гц, пакетами по 20 мс (160 отсчётов), как у камер.
        for (p in 0 until 100) {
            val samples = ShortArray(160) { i -> (8000 * sin(2 * PI * 440 * (p * 160 + i) / 8000)).toInt().toShort() }
            frames += encoder.encode(G711Depacketizer.Pcm(1_000L + p * 160L, samples))
        }
        encoder.release()
        assertTrue("кадров AAC: ${frames.size}", frames.size >= 12)
        assertTrue("метки возрастают", frames.zipWithNext().all { (a, b) -> b.timestamp > a.timestamp })
        // Обратно через системный декодер: звук не тишина и не мусор.
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 8000, 1)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(encoder.config))
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var energy = 0.0
        var count = 0L
        var next = 0
        val deadline = System.currentTimeMillis() + 10_000
        while ((next < frames.size || count == 0L) && System.currentTimeMillis() < deadline) {
            if (next < frames.size) {
                val i = decoder.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val f = frames[next++]
                    decoder.getInputBuffer(i)!!.apply { clear(); put(f.data) }
                    decoder.queueInputBuffer(i, 0, f.data.size, f.timestamp * 125, 0)
                }
            }
            val o = decoder.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val b = decoder.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder())
                b.position(info.offset)
                repeat(info.size / 2) { val s = b.short.toDouble(); energy += s * s; count++ }
                decoder.releaseOutputBuffer(o, false)
            }
        }
        decoder.stop(); decoder.release()
        assertTrue("декодировано отсчётов: $count", count > 4_000)
        val rms = sqrt(energy / count)
        assertTrue("громкость синуса после кодера: $rms", rms > 1_000)
    }

    private fun record(url: String, seconds: Long): Pair<List<RecFile>, List<ContinuousRecorder.State>> {
        val root = File(context.cacheDir, "rec-deep-${System.nanoTime()}").apply { mkdirs() }
        val segments = mutableListOf<RecFile>()
        val states = mutableListOf<ContinuousRecorder.State>()
        val r = ContinuousRecorder("deep", url, "", null, audio = true, root = { root }, canWrite = { true },
            onSegment = { synchronized(segments) { segments += it } },
            onStatus = { _, s -> synchronized(states) { states += s.state } }, segmentMs = 5_000)
        r.start()
        Thread.sleep(seconds * 1_000)
        r.stop()
        return segments to states
    }

    @Test fun recordsStandSubstreamIntoPlayableSegments() {
        val host = argument("rtspHost")
        assumeTrue("Нужен аргумент rtspHost (RTSP-стенд CI)", host != null)
        val (segments, states) = record("rtsp://$host:8554/sub", 16)
        assertTrue("отрезков: ${segments.size}, состояния: $states", segments.size >= 2)
        val first = segments.first()
        val extractor = MediaExtractor()
        extractor.setDataSource(first.path.path)
        assertEquals(1, extractor.trackCount)
        val format = extractor.getTrackFormat(0)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
        assertEquals(640, format.getInteger(MediaFormat.KEY_WIDTH))
        extractor.selectTrack(0)
        var samples = 0
        while (extractor.advance()) samples++
        extractor.release()
        // 15 кадров/с, отрезок режется на ключевом кадре после 5 с (группа — 2 с): не меньше 5 с видео.
        assertTrue("кадров в отрезке: $samples", samples >= 70)
        segments.forEach { it.path.delete() }
    }

    @Test fun silentCameraIsRetriedWithoutSegments() {
        val host = argument("rtspHost")
        assumeTrue("Нужен аргумент rtspHost (RTSP-стенд CI)", host != null)
        // Порт 8556 стенда принимает соединение и молчит (как зависшая камера).
        val (segments, states) = record("rtsp://$host:8556/silent", 26)
        assertTrue(segments.isEmpty())
        assertTrue("состояния: $states", ContinuousRecorder.State.RETRYING in states)
    }
}
