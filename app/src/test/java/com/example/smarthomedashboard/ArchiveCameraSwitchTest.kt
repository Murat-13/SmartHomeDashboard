package com.example.smarthomedashboard

import android.content.Intent
import android.os.Looper
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Быстрая смена камер в архиве (ревью 22.11.2026, docs/review-2026-11-22.md, пункт 3): чтения диска идут
 * параллельно, и опоздавшее чтение прежней камеры заменяло список уже выбранной — записи одной камеры под
 * названием другой. Порядок завершения чтений задаётся защёлками: результат одинаков при каждом запуске.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land")
class ArchiveCameraSwitchTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private val today = RecArchive.dayStart(System.currentTimeMillis())
    private var controller: ActivityController<ArchiveActivity>? = null

    /** Чтения по порядку вызова: n-е ждёт свою защёлку (если она задана). */
    private val calls = AtomicInteger()
    private val entered = Semaphore(0)
    private val gates = ConcurrentHashMap<Int, CountDownLatch>()
    private val done = Semaphore(0)

    @Before fun setUp() {
        root = context.getExternalFilesDirs(null)[0]
        File(root, RecLayout.ROOT).deleteRecursively()
        TileManager(context).saveTiles(listOf("camC", "camA", "camB").map {
            TileEntity(it, DoorbellConfig.CAMERA_TYPE, "grid", "Камера $it", 0, 0, 1, 1, config = DoorbellConfig.newTileConfig())
        })
        event("camA", 10)
        event("camB", 15)
        event("camB", 16)
        ArchiveActivity.loadHook = { _, load ->
            val n = calls.getAndIncrement()
            gates[n]?.let { entered.release(); it.await(10, TimeUnit.SECONDS) }
            load().also { done.release() }
        }
    }

    @After fun tearDown() {
        gates.values.forEach { it.countDown() }
        ArchiveActivity.loadHook = null
        controller?.pause()?.stop()?.destroy()
        File(root, RecLayout.ROOT).deleteRecursively()
    }

    private fun event(camera: String, hour: Int) =
        File(RecLayout.dayDir(root, camera, today + hour * 3_600_000L).apply { mkdirs() },
            RecLayout.closedName(RecFile.Kind.EVENT, today + hour * 3_600_000L, 30_000)).writeBytes(ByteArray(100))

    private fun idle() {
        repeat(5) { Thread.sleep(80); shadowOf(Looper.getMainLooper()).idle() }
    }

    /** Открыть архив камеры C и дождаться её списка; следующие чтения ([held]) будут ждать своих защёлок. */
    private fun open(held: Int): ArchiveActivity {
        repeat(held) { gates[it + 1] = CountDownLatch(1) }
        controller = Robolectric.buildActivity(ArchiveActivity::class.java,
            Intent(context, ArchiveActivity::class.java).putExtra(ArchiveActivity.EXTRA_TILE, "camC")).create().start().resume()
        assertTrue("Архив камеры C не прочитан", done.tryAcquire(5, TimeUnit.SECONDS))
        idle()
        return controller!!.get()
    }

    private fun switch(activity: ArchiveActivity, camera: String) {
        activity.switchCamera(camera)
        assertTrue("Чтение $camera не началось", entered.tryAcquire(5, TimeUnit.SECONDS))
    }

    /** Отпустить n-е чтение и дождаться, пока его результат дойдёт до экрана (или будет отброшен). */
    private fun release(n: Int) {
        gates.getValue(n).countDown()
        assertTrue("Чтение $n не завершилось", done.tryAcquire(5, TimeUnit.SECONDS))
        idle()
    }

    /** Всё на экране — от выбранной камеры: список событий и отметки шкалы. */
    private fun assertShows(activity: ArchiveActivity, camera: String, events: Int) {
        val ui = activity.shown
        assertEquals(camera, ui.camera)
        assertEquals("Камера $camera", ui.title)
        assertEquals("События в списке — камеры $camera", listOf(camera), ui.events.map { it.file.camera }.distinct())
        assertEquals(events, ui.events.size)
        assertEquals("Отметки шкалы — камеры $camera", events, ui.timeline?.events?.size)
    }

    @Test fun lateLoadOfPreviousCameraDoesNotReplaceSelected() {
        val a = open(held = 2)
        switch(a, "camA") // чтение 1 — ждёт
        switch(a, "camB") // чтение 2 — ждёт
        release(2)
        assertShows(a, "camB", events = 2)
        release(1) // прежняя камера пришла последней
        assertShows(a, "camB", events = 2)
    }

    @Test fun staleLoadOfSameCameraIsDropped() {
        val a = open(held = 3)
        switch(a, "camA") // А1
        switch(a, "camB") // Б
        switch(a, "camA") // А2
        release(3)
        assertShows(a, "camA", events = 1)
        release(2)
        assertShows(a, "camA", events = 1)
        // А1 устарело, хотя камера та же: его результат (здесь — уже с новым роликом) экран не трогает.
        event("camA", 11)
        release(1)
        assertShows(a, "camA", events = 1)
    }
}
