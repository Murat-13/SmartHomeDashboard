package com.example.smarthomedashboard

import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

/**
 * Архив из раскрытой плитки (решение владельца 23.11.2026): «сразу должна открываться текущая картина, а не чёрный
 * экран». Кадр плитки — сразу, затем свежая запись «Сейчас»; из окна звонка — по-прежнему последнее событие.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land")
class ArchiveStartTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private val now = System.currentTimeMillis()
    private var controller: ActivityController<ArchiveActivity>? = null

    @Before fun setUp() {
        root = context.getExternalFilesDirs(null)[0]
        File(root, RecLayout.ROOT).deleteRecursively()
        TileManager(context).saveTiles(listOf(TileEntity("door", DoorbellConfig.TYPE, "grid", "Домофон", 0, 0, 1, 1,
            config = DoorbellConfig.newTileConfig())))
        // Круглосуточная запись последних 2 минут и событие час назад.
        file(RecFile.Kind.CONTINUOUS, now - 120_000, 60_000)
        file(RecFile.Kind.CONTINUOUS, now - 60_000, 60_000)
        file(RecFile.Kind.EVENT, now - 3_600_000, 30_000)
    }

    @After fun tearDown() {
        ArchiveActivity.handoffFrame = null
        controller?.pause()?.stop()?.destroy()
        File(root, RecLayout.ROOT).deleteRecursively()
    }

    private fun file(kind: RecFile.Kind, start: Long, duration: Long) =
        File(RecLayout.dayDir(root, "door", start).apply { mkdirs() }, RecLayout.closedName(kind, start, duration))
            .writeBytes(ByteArray(100))

    /** Дождаться чтения записей (фоновый поток) и его применения на главном. */
    private fun loaded(archive: ArchiveActivity) {
        val until = System.currentTimeMillis() + 5_000
        while (archive.shown.loading && System.currentTimeMillis() < until) { Thread.sleep(40); shadowOf(Looper.getMainLooper()).idle() }
        repeat(3) { Thread.sleep(40); shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun open(intent: Intent.() -> Unit = {}): ArchiveActivity {
        controller = Robolectric.buildActivity(ArchiveActivity::class.java,
            Intent(context, ArchiveActivity::class.java).putExtra(ArchiveActivity.EXTRA_TILE, "door").apply(intent))
            .create().start().resume()
        return controller!!.get()
    }

    @Test fun fromTileShowsTileFrameAtOnceThenPlaysNow() {
        val frame = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        ArchiveActivity.handoffFrame = frame
        val archive = open()
        assertSame("кадр плитки — сразу, пока записи читаются", frame, archive.shown.placeholder)
        assertEquals(160f / 120f, archive.shown.videoAspect, 0.01f)
        assertNull("кадр забран — следующий архив его не покажет", ArchiveActivity.handoffFrame)
        loaded(archive)
        assertTrue("сразу — круглосуточная запись: ${archive.shown.copy(placeholder = null)}", archive.shown.continuous)
        val at = archive.shown.center!!
        assertTrue("«Сейчас» — за 10 с до конца записанного: ${now - at}", (now - at) in 9_000L..11_000L)
    }

    @Test fun fromRingWindowStillOpensLatestEvent() {
        val archive = open { putExtra(ArchiveActivity.EXTRA_LATEST, true) }
        loaded(archive)
        assertEquals("из окна звонка — последнее событие", now - 3_600_000, archive.shown.current?.file?.startMs)
        assertTrue(!archive.shown.continuous)
    }
}
