package com.example.smarthomedashboard

import com.example.smarthomedashboard.RecordingTestSupport.accessUnits
import com.example.smarthomedashboard.RecordingTestSupport.boxes
import com.example.smarthomedashboard.RecordingTestSupport.nals
import com.example.smarthomedashboard.RecordingTestSupport.packetize
import com.example.smarthomedashboard.RecordingTestSupport.resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Запись на носитель — вне потока камеры (проверка аудита 23.11.2026, пункт C; docs/recording.md). Поток камеры раздаёт
 * кадры живому видео: раньше, пока носитель писал фрагмент или закрывал отрезок, он стоял (на SD-карте — до 1,3 с).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordingIoTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Носитель «завис»: поток записи занят, пока не отпустим. */
    private fun <T> storageStuck(block: () -> T): T {
        val storage = CountDownLatch(1)
        RecIo.execute { storage.await() }
        try { return block() } finally { storage.countDown() }
    }

    private fun filesUnder(root: File) = root.walkTopDown().filter { it.isFile }.toList()

    /**
     * Камера отдаёт 4 с видео, отрезки по секунде: смены отрезков и запись фрагментов не ждут носитель. Пока он занят,
     * на нём нет ни одного файла (поток камеры его не трогал); потом отрезки дописаны, обрезаны и переименованы.
     */
    @Test fun cameraThreadDoesNotWaitForStorage() {
        val root = tmp.newFolder("r")
        val segments = Collections.synchronizedList(mutableListOf<RecFile>())
        val r = ContinuousRecorder("door", "rtsp://cam/sub", "", null, audio = false, root = { root }, canWrite = { true },
            onSegment = { segments += it }, onStatus = { _, _ -> }, segmentMs = 1_000)
        val (frames, d) = sample(40) // 4 с, ключевой кадр раз в секунду
        var slowestNs = 0L
        storageStuck {
            val sink = r.newSink().apply { videoClock = 90_000 }
            for (f in frames) {
                val t = System.nanoTime()
                sink.video(f, d)
                slowestNs = maxOf(slowestNs, System.nanoTime() - t)
            }
            sink.close()
            assertEquals("Пока носитель занят, поток камеры на него не писал", emptyList<File>(), filesUnder(root))
        }
        assertTrue("Кадр с записью не ждал носитель: ${slowestNs / 1_000_000} мс", slowestNs < 500_000_000L)
        assertTrue(RecIo.flush(5_000))
        val files = RecLayout.scan(root)
        assertEquals("4 отрезка по секунде: $files", 4, files.size)
        assertEquals(4, segments.size)
        for (f in files) {
            assertFalse("отрезок закрыт и переименован: ${f.path.name}", f.open)
            val top = boxes(f.path.readBytes())
            assertEquals("ftyp", top[0]); assertEquals("moov", top[1])
            assertEquals(1, top.count { it == "moof" }); assertFalse(top.any { it.startsWith("!") })
        }
        assertTrue("После закрытия файлы не числятся открытыми", RecIo.busyFiles().none { it.startsWith(root.path) })
    }

    /** Носитель отстал больше чем на предел очереди: пишущий ждёт, как раньше, а память не растёт. */
    @Test fun fullQueueMakesWriterWaitInsteadOfGrowing() {
        val file = File(tmp.newFolder("q"), "c_1.part.mp4")
        val channel = QueuedFileChannel(file)
        val chunk = 1024 * 1024
        val fits = (RecIo.LIMIT_BYTES / chunk).toInt()
        val total = fits + 6
        val written = AtomicInteger()
        var writer: Thread? = null
        storageStuck {
            writer = thread(isDaemon = true) { repeat(total) { channel.write(ByteBuffer.wrap(ByteArray(chunk))); written.incrementAndGet() } }
            val until = System.currentTimeMillis() + 5_000
            while (written.get() < fits && System.currentTimeMillis() < until) Thread.sleep(10)
            Thread.sleep(300)
            assertEquals("Очередь приняла ровно предел", fits, written.get())
            assertTrue("Пишущий ждёт", writer!!.isAlive)
            assertTrue(RecIo.queuedBytes <= RecIo.LIMIT_BYTES)
        }
        writer!!.join(10_000)
        assertEquals(total, written.get())
        channel.close()
        assertTrue(RecIo.flush(10_000))
        assertEquals(total.toLong() * chunk, file.length())
        assertEquals(0L, RecIo.queuedBytes)
    }

    /** Ошибка носителя приходит при следующей записи — сессия записи обрывается, как и раньше. */
    @Test fun storageErrorReachesWriter() {
        val bad = File(tmp.newFile("not_a_dir"), "c_1.part.mp4") // папка — это файл: создать нельзя
        val channel = QueuedFileChannel(bad)
        channel.write(ByteBuffer.wrap(ByteArray(10)))
        assertTrue(RecIo.flush(5_000))
        try {
            channel.write(ByteBuffer.wrap(ByteArray(10)))
            fail("Ошибка носителя не дошла до записи")
        } catch (_: IOException) {
        }
        channel.close()
        assertTrue(RecIo.flush(5_000))
        assertFalse(bad.path in RecIo.busyFiles())
    }

    /**
     * Восстановление после падения не трогает файлы, которые ещё пишутся или закрываются (служба перезапущена в том же
     * процессе), и чинит оставшиеся от прошлого процесса.
     */
    @Test fun recoveryLeavesFilesBeingWritten() {
        val root = tmp.newFolder("rec")
        val start = 1_763_490_000_000L
        val dir = RecLayout.dayDir(root, "door", start).apply { mkdirs() }
        val left = File(dir, RecLayout.openName(RecFile.Kind.CONTINUOUS, start))
        val (frames, d) = sample(30)
        val params = VideoParams(d.codec, d.vps, d.sps!!, d.pps!!)
        Mp4SegmentWriter(left, params, null).use { w -> frames.forEach { w.writeVideo(it, it.timestamp * 1_000_000 / 90_000) } }
        val live = File(dir, RecLayout.openName(RecFile.Kind.CONTINUOUS, start + 60_000))
        val writer = Mp4SegmentWriter(live, params, null, queued = true)
        frames.forEach { writer.writeVideo(it, it.timestamp * 1_000_000 / 90_000) }
        assertTrue(RecIo.flush(5_000))
        val fixed = RecIo.call(5_000) { RecLayout.recover(root, RecIo.busyFiles()) }
        assertEquals("оставшийся от падения отрезок восстановлен", 1, fixed)
        assertTrue("идущий отрезок не тронут", live.exists())
        var closed: RecFile? = null
        RecLayout.finishLater(writer, live, "door", RecFile.Kind.CONTINUOUS, start + 60_000, 2_900) { closed = it }
        assertTrue(RecIo.flush(5_000))
        assertFalse(closed!!.open)
        assertEquals(2, RecLayout.scan(root).count { !it.open })
    }

    /**
     * Новый ролик события с того же ключевого кадра, что и прежний, который ещё закрывается (ревью 24.11.2026): имя
     * берётся свободное, поток камеры не ждёт носитель; два ролика в один файл не пишутся.
     */
    @Test fun sameStartGetsFreeNameWithoutWaiting() {
        val dir = tmp.newFolder("same")
        val start = 1_763_490_000_000L
        storageStuck {
            val first = QueuedFileChannel(File(dir, RecLayout.openName(RecFile.Kind.EVENT, start)))
            first.write(ByteBuffer.wrap(ByteArray(10)))
            first.close()
            val t = System.nanoTime()
            val free = RecLayout.freeStart(dir, RecFile.Kind.EVENT, start)
            assertTrue("не ждали носитель", System.nanoTime() - t < 100_000_000L)
            assertEquals(start + 1, free)
            try {
                QueuedFileChannel(File(dir, RecLayout.openName(RecFile.Kind.EVENT, start)))
                fail("второй писатель в тот же файл")
            } catch (_: IOException) {
            }
            QueuedFileChannel(File(dir, RecLayout.openName(RecFile.Kind.EVENT, free))).close()
        }
        assertTrue(RecIo.flush(5_000))
        assertEquals(start, RecLayout.freeStart(dir, RecFile.Kind.EVENT, start))
    }

    /** Кадры образца H.264 (10 кадров/с, ключевой раз в секунду), продолжённые до [count]. */
    private fun sample(count: Int): Pair<List<VideoFrame>, VideoDepacketizer> {
        val units = accessUnits(nals(resource("sample.h264")), false)
        val d = H264Depacketizer()
        val decoded = mutableListOf<VideoFrame>()
        var seq = 0
        units.forEachIndexed { i, au -> packetize(au, false, i * 9_000L, seq).forEach { p -> decoded += d.push(RtpPacket.parse(p, p.size)!!); seq++ } }
        val frames = (0 until count).map { i -> val b = decoded[i % decoded.size]; VideoFrame(i * 9_000L, b.nals, b.key) }
        return frames to d
    }
}
