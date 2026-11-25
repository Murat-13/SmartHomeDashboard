package com.example.smarthomedashboard

import android.content.Intent
import android.os.Looper
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Архив открывается сразу, сколько бы записей ни было (владелец 25.11.2026: «секунд 5–10 приходится ждать появления архива»). Чтение
 * носителя здесь держится защёлкой: всё, что видно до её снятия, от носителя не зависит.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land")
class ArchiveInstantTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var roots: List<File>
    private val root get() = roots[0]
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L
    private var controller: ActivityController<ArchiveActivity>? = null
    private var held = CountDownLatch(1)
    @Volatile private var loads = 0

    @Before fun setUp() {
        roots = RecArchive.defaultRoots(context)
        File(root, RecLayout.ROOT).deleteRecursively()
        RecCatalog.clear()
        TileManager(context).saveTiles(listOf(TileEntity("door", DoorbellConfig.TYPE, "grid", "Домофон", 0, 0, 1, 1,
            config = DoorbellConfig(rtspMain = "rtsp://127.0.0.1:1/h264Preview_01_main", rtspSub = "rtsp://127.0.0.1:1/h264Preview_01_sub")
                .writeTo(DoorbellConfig.newTileConfig()))))
        // Событие сейчас и по событию в каждый из 5 прошлых дней.
        for (d in 0..5) file(RecFile.Kind.EVENT, now - 600_000 - d * day)
        ArchiveActivity.loadHook = { _, load -> loads++; held.await(20, TimeUnit.SECONDS); load() }
    }

    @After fun tearDown() {
        held.countDown()
        ArchiveActivity.loadHook = null
        controller?.pause()?.stop()?.destroy()
        File(root, RecLayout.ROOT).deleteRecursively()
        RecCatalog.clear()
    }

    private fun file(kind: RecFile.Kind, start: Long): RecFile {
        val f = File(RecLayout.dayDir(root, "door", start).apply { mkdirs() }, RecLayout.closedName(kind, start, 30_000)).apply { writeBytes(ByteArray(100)) }
        return RecFile(f, "door", kind, start, 30_000, 100)
    }

    /** Служба записи обошла носители. */
    private fun walked() = roots.forEach { RecCatalog.publish(it, RecLayout.scan(it), RecCatalog.mark()) }

    private fun open(intent: Intent.() -> Unit = {}): ArchiveActivity {
        controller = Robolectric.buildActivity(ArchiveActivity::class.java,
            Intent(context, ArchiveActivity::class.java).putExtra(ArchiveActivity.EXTRA_TILE, "door").apply(intent)).create().start().resume()
        return controller!!.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < until) { Thread.sleep(20); idle() }
        assertTrue(what, condition())
    }

    @Test fun listComesFromMemoryWithoutTouchingStorage() {
        walked()
        val archive = open()
        assertTrue("живой поток — сразу, ничего не дожидаясь", archive.shown.live)
        await("список из памяти: ${archive.shown.days.size}") { !archive.shown.loading && archive.shown.days.size == 6 }
        assertEquals("носитель не читался", 0, loads)
        assertEquals(1, archive.shown.events.size)
        assertEquals(6, archive.shown.timeline!!.events.size)
        assertEquals("место — прямой эфир", null, archive.shown.center)
    }

    @Test fun recordingClosedAfterWalkIsInTheList() {
        walked()
        // Событие закончилось после обхода: служба сообщила о нём.
        RecCatalog.add(file(RecFile.Kind.EVENT, now - 60_000))
        val archive = open()
        await("свежее событие сверху") { archive.shown.events.firstOrNull()?.file?.startMs == now - 60_000 }
        assertEquals(0, loads)
    }

    /** Первые секунды после запуска приложения: памяти ещё нет — сначала последние дни, остальное следом. */
    @Test fun coldStartShowsLastDaysFirst() {
        val archive = open()
        assertTrue(archive.shown.live)
        await("последние дни") { !archive.shown.loading && archive.shown.events.isNotEmpty() }
        assertTrue("остальное ещё читается: дней ${archive.shown.days.size}", archive.shown.days.size in 1..3)
        held.countDown()
        await("все дни") { archive.shown.days.size == 6 }
        assertTrue("прямой эфир не прерван", archive.shown.live)
        // Прочитанное архивом — тоже в памяти: второй вход носитель не читает.
        controller!!.pause().stop().destroy()
        val before = loads
        val again = open()
        await("список") { again.shown.days.size == 6 }
        assertEquals(before, loads)
    }

    /** Из окна звонка — последнее событие, из журнала — момент старого дня: он открывается, когда прочитан. */
    @Test fun placeWaitsOnlyForItsOwnDay() {
        val archive = open { putExtra(ArchiveActivity.EXTRA_LATEST, true) }
        await("последнее событие — из первой порции") { archive.shown.current?.file?.startMs == now - 600_000 }
        assertFalse(archive.shown.live)
        controller!!.pause().stop().destroy()
        RecCatalog.clear()
        val old = now - 600_000 - 5 * day
        val journal = open { putExtra(ArchiveActivity.EXTRA_AT, old + 10_000) }
        await("последние дни") { !journal.shown.loading }
        assertEquals("старый день ещё не прочитан — на другое место не встаём", null, journal.shown.current)
        held.countDown()
        await("момент открыт: ${journal.shown.current?.file?.startMs}") { journal.shown.current?.file?.startMs == old }
        assertEquals(RecArchive.dayStart(old), journal.shown.day)
    }

    @Test fun refreshKeepsChosenDayWeekAndPlayback() {
        held.countDown()
        val archive = open()
        await("все дни") { archive.shown.days.size == 6 }
        val third = archive.shown.days[3]
        archive.testActions.selectDay(third)
        archive.testActions.play(archive.shown.events.first())
        val playing = archive.shown.current
        // Второй раз: память есть, но на носителе файл убрали не из приложения.
        walked()
        val refresh = ArchiveActivity::class.java.getDeclaredMethod("refresh", Class.forName("com.example.smarthomedashboard.ArchiveActivity\$Prepared")).apply { isAccessible = true }
        val prepared = Class.forName("com.example.smarthomedashboard.ArchiveActivity\$Prepared").declaredConstructors.single().apply { isAccessible = true }
        val snapshot = RecArchive(context).known("door")!!
        refresh.invoke(archive, prepared.newInstance(snapshot, null))
        assertEquals("день тот же", third, archive.shown.day)
        assertEquals("ролик не тронут", playing, archive.shown.current)
        assertEquals(1, archive.shown.events.size)
        archive.testActions.selectWeek()
        assertTrue(archive.shown.week && archive.shown.events.size >= 5)
        refresh.invoke(archive, prepared.newInstance(snapshot, null))
        assertTrue("«Неделя» осталась", archive.shown.week && archive.shown.events.size >= 5)
    }

    @Test fun verifyNoticesFileRemovedOutside() {
        walked()
        File(RecLayout.dayDir(root, "door", now - 600_000 - 3 * day), RecLayout.closedName(RecFile.Kind.EVENT, now - 600_000 - 3 * day, 30_000)).delete()
        val archive = RecArchive(context)
        assertEquals(6, archive.known("door")!!.days.size)
        assertTrue(archive.verify("door"))
        assertEquals(5, archive.known("door")!!.days.size)
        assertFalse(archive.verify("door"))
    }

    /** Месяц записи двух камер: 90 тысяч файлов. Список из памяти — доли секунды. */
    @Test fun monthOfRecordingsIsStillQuick() {
        val files = ArrayList<RecFile>()
        for (camera in listOf("door", "yard")) for (d in 0 until 30) for (m in 0 until 1_500) {
            val start = now - d * day - m * 57_000L
            val kind = if (m % 10 == 0) RecFile.Kind.EVENT else RecFile.Kind.CONTINUOUS
            files += RecFile(File(RecLayout.dayDir(root, camera, start), RecLayout.closedName(kind, start, 57_000)), camera, kind, start, 57_000, 4_000_000)
        }
        roots.forEach { r -> RecCatalog.publish(r, if (r == root) files else emptyList(), RecCatalog.mark()) }
        RecArchive(context).known("door")
        val started = System.nanoTime()
        val snapshot = RecArchive(context).known("door")!!
        val days = snapshot.days.size
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(45_000, snapshot.continuous.size + snapshot.events.size)
        assertTrue("дней $days", days in 30..32)
        assertTrue("45 тысяч файлов камеры за $ms мс", ms < 1_500)
    }

    /** Имена — без обращения к каждому файлу: размер не спрашивается. */
    @Test fun archiveReadsNamesOnly() {
        val listed = RecLayout.dayDirs(root, "door").flatMap { RecLayout.names(it, "door") }
        assertEquals(6, listed.size)
        assertTrue(listed.all { it.bytes == 0L && it.durationMs == 30_000L })
        assertTrue(RecLayout.scan(root).all { it.bytes == 100L })
        assertTrue(RecLayout.scan(root, sized = false).all { it.bytes == 0L })
    }
}
