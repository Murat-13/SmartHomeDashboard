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

    /**
     * Решение владельца 23.11.2026: «при переходе в архив пусть сразу показывает текущий поток». С потоком у камеры —
     * живой поток; любое место на шкале — запись; «Сейчас» — снова живой поток.
     */
    @Test fun withCameraStreamStartsLiveAndNowReturnsToIt() {
        TileManager(context).saveTiles(listOf(TileEntity("door", DoorbellConfig.TYPE, "grid", "Домофон", 0, 0, 1, 1,
            config = DoorbellConfig(rtspMain = "rtsp://127.0.0.1:1/h264Preview_01_main", rtspSub = "rtsp://127.0.0.1:1/h264Preview_01_sub")
                .writeTo(DoorbellConfig.newTileConfig()))))
        val archive = open()
        loaded(archive)
        assertTrue("сразу — живой поток", archive.shown.live)
        assertTrue(!archive.shown.continuous && archive.shown.current == null)
        archive.testActions.timelineScrub(now - 90_000, true)
        loaded(archive)
        assertTrue("место на шкале — запись", !archive.shown.live && archive.shown.continuous)
        archive.testActions.toNow()
        assertTrue("«Сейчас» — снова живой поток", archive.shown.live)
    }

    /**
     * Решение владельца 23.11.2026: «когда мотаю (постоянный субпоток) и навожу на события — пусть показывает в HD».
     * Место, где есть ролик события, — ролик события (HD), и пока ведут ленту, и когда отпустили; рядом — 24/7.
     */
    @Test fun scrubOverEventShowsHdClip() {
        file(RecFile.Kind.EVENT, now - 100_000, 30_000) // событие внутри круглосуточной записи
        val archive = open() // у камеры нет потока — «Сейчас» записи, 24/7
        loaded(archive)
        assertTrue("до события — 24/7", archive.shown.continuous)
        archive.testActions.timelineScrub(now - 90_000, false)
        assertEquals("ведут ленту по событию — ролик HD", now - 100_000, archive.shown.current?.file?.startMs)
        archive.testActions.timelineScrub(now - 85_000, true)
        assertEquals("отпустили на событии — ролик HD", now - 100_000, archive.shown.current?.file?.startMs)
        assertTrue(!archive.shown.continuous)
        archive.testActions.timelineScrub(now - 30_000, true)
        assertTrue("вне события — снова 24/7", archive.shown.continuous && archive.shown.current == null)
    }

    /**
     * Поворот экрана (решение владельца 23.11.2026 — «переделай всё, что необходимо»): архив остаётся тем же экраном —
     * место на шкале, ролик и вид «на весь экран» не теряются. Раньше Android строил архив заново, и он возвращался к
     * «Сейчас».
     */
    @Test fun rotationKeepsPlaceAndFullscreen() {
        val archive = open()
        loaded(archive)
        archive.testActions.timelineScrub(now - 90_000, true)
        loaded(archive)
        archive.testActions.fullscreen(true)
        val place = archive.shown.center
        assertTrue(archive.shown.continuous && archive.shown.fullscreen)

        // Поворот в книжное положение: ширина и высота меняются местами.
        val turned = android.content.res.Configuration(archive.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
            val w = screenWidthDp; screenWidthDp = screenHeightDp; screenHeightDp = w
        }
        controller!!.configurationChange(turned)
        shadowOf(Looper.getMainLooper()).idle()

        assertSame("экран архива — тот же, а не построен заново", archive, controller!!.get())
        assertEquals("место на шкале то же", place, archive.shown.center)
        assertTrue("по-прежнему запись на весь экран", archive.shown.continuous && archive.shown.fullscreen)
    }

    @Test fun fromRingWindowStillOpensLatestEvent() {
        val archive = open { putExtra(ArchiveActivity.EXTRA_LATEST, true) }
        loaded(archive)
        assertEquals("из окна звонка — последнее событие", now - 3_600_000, archive.shown.current?.file?.startMs)
        assertTrue(!archive.shown.continuous)
    }
}
