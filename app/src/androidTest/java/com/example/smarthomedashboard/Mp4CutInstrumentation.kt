package com.example.smarthomedashboard

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * «Поделиться отрезком» (Mp4Cut) на настоящих файлах записи: файлы кладут вручную в /data/local/tmp/shd_cut/
 * (имена как в архиве: c_<начало>_<длительность>.mp4, e_…). Без файлов тест пропускается — в CI он молчит.
 * Проверяется: отрезок через границу двух файлов 24/7, отрезок из ролика события, первый кадр ключевой,
 * длительность ≈ заданной, файл читается системным плеером.
 */
@RunWith(AndroidJUnit4::class)
class Mp4CutInstrumentation {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun copyIn(): List<RecFile> {
        val listing = instrumentation.uiAutomation.executeShellCommand("ls /data/local/tmp/shd_cut").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }
        val dir = File(context.cacheDir, "cut_in").apply { deleteRecursively(); mkdirs() }
        return listing.lines().map { it.trim() }.filter { it.endsWith(".mp4") }.mapNotNull { name ->
            val target = File(dir, name)
            instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/shd_cut/$name").use { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            RecLayout.parse(target, "door")
        }
    }

    private fun check(out: File, expectMs: Long) {
        val extractor = MediaExtractor()
        extractor.setDataSource(out.path)
        val video = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        extractor.selectTrack(video)
        assertTrue("первый кадр отрезка — ключевой", extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
        assertEquals("начинается с нуля", 0L, extractor.sampleTime)
        extractor.release()
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(out.path)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        retriever.release()
        // С ключевого кадра не позже начала: отрезок может быть длиннее на интервал между ключевыми кадрами (до ~4 с).
        assertTrue("длительность $duration мс, ждали ≈ $expectMs", duration in (expectMs - 1_500)..(expectMs + 5_000))
    }

    @Test fun cutsAcrossContinuousSegmentsAndFromEvent() {
        val files = copyIn()
        val continuous = files.filter { it.kind == RecFile.Kind.CONTINUOUS }.sortedBy { it.startMs }
        assumeTrue("нет файлов в /data/local/tmp/shd_cut", continuous.size >= 2)
        // 20 с через стык первого и второго отрезка.
        val from = continuous[0].endMs - 10_000
        val out = File(context.cacheDir, "cut_out.mp4")
        val ms = Mp4Cut.cut(continuous, from, from + 20_000, out)
        assertTrue("вырезано $ms мс", ms in 18_000..25_000)
        check(out, 20_000)
        files.firstOrNull { it.kind == RecFile.Kind.EVENT }?.let { event ->
            val eventOut = File(context.cacheDir, "cut_event.mp4")
            val got = Mp4Cut.cut(listOf(event), event.startMs + 5_000, event.startMs + 15_000, eventOut)
            assertTrue("из события $got мс", got in 8_000..15_000)
            check(eventOut, 10_000)
        }
        // Вне записи — ничего.
        assertEquals(0L, Mp4Cut.cut(continuous, continuous.last().endMs + 60_000, continuous.last().endMs + 70_000, File(context.cacheDir, "none.mp4")))
    }
}
