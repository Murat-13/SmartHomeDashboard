package com.example.smarthomedashboard

import android.content.Intent
import android.os.Looper
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/**
 * «На сколько хватит места» в редакторе камеры и в архиве (ревью 22.11.2026, docs/review-2026-11-22.md, пункт 5).
 * Лимит один на все камеры и на ролики событий, поэтому и оценка — по всем записям носителя, куда идёт запись.
 * Раньше редактор делил лимит на объём одной своей камеры, и обе надписи не учитывали ролики событий: на московском
 * планшете архив обещал ≈ 4 дня при фактических ≈ 2,8.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land")
class RecordingSpaceOutlookTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L

    @Before fun setUp() {
        root = context.getExternalFilesDirs(null)[0]
        File(root, RecLayout.ROOT).deleteRecursively()
        roomy()
        TileManager(context).saveTiles(listOf("cam1", "cam2", "cam3", "cam4").map {
            TileEntity(it, DoorbellConfig.CAMERA_TYPE, "grid", "Камера $it", 0, 0, 1, 1, config = DoorbellConfig.newTileConfig())
        })
        RecordingDevice(context).limitGb = 1
    }

    @After fun tearDown() {
        File(root, RecLayout.ROOT).deleteRecursively()
        RecordingDevice(context).limitGb = 0
        DoorbellHub.detach()
    }

    /** Свободно ~40 ГБ: место не заполнено. */
    private fun roomy() = ShadowStatFs.registerStats(root, 10_000_000, 10_000_000, 10_000_000)

    /** Круглосуточная запись камеры за [hours] часов: по 128 КБ в час (3 МБ в сутки). */
    private fun continuous(camera: String, hours: Int) {
        for (h in 1..hours) put(camera, RecFile.Kind.CONTINUOUS, now - h * hour, hour, 128 * 1024)
    }

    private fun put(camera: String, kind: RecFile.Kind, start: Long, duration: Long, bytes: Int) {
        File(RecLayout.dayDir(root, camera, start).apply { mkdirs() }, RecLayout.closedName(kind, start, duration))
            .writeBytes(ByteArray(bytes))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(what: String, read: () -> String?): String {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            idle()
            read()?.takeIf { it.isNotBlank() }?.let { return it }
            Thread.sleep(50)
        }
        error("$what: оценка не появилась")
    }

    private fun editorText(tileId: String): String {
        val editor = Robolectric.buildActivity(DoorbellEditorActivity::class.java,
            Intent(context, DoorbellEditorActivity::class.java).putExtra("tile_id", tileId)).create().get()
        return waitFor("Редактор") { editor.estimateText() }
    }

    private fun archiveText(tileId: String): String {
        val archive = Robolectric.buildActivity(ArchiveActivity::class.java,
            Intent(context, ArchiveActivity::class.java).putExtra(ArchiveActivity.EXTRA_TILE, tileId)).create().start().resume().get()
        return waitFor("Архив") { archive.shown.storage }
    }

    @Test fun editorCountsAllCamerasSharingTheLimit() {
        listOf("cam1", "cam2", "cam3", "cam4").forEach { continuous(it, 48) }
        val text = editorText("cam1")
        // 4 камеры по 3 МБ в сутки = 12 МБ; 1 ГБ / 12 МБ ≈ 85 дней (по одной камере вышло бы 341).
        assertTrue(text, text.contains("примерно на 85 дн."))
        assertFalse(text, text.contains("341"))
    }

    @Test fun eventClipsAreCountedInBothPlaces() {
        continuous("cam1", 48)
        for (i in 1..12) put("cam1", RecFile.Kind.EVENT, now - i * 4 * hour + 600_000, 120_000, 512 * 1024)
        // 3 МБ 24/7 + 3 МБ событий в сутки = 6 МБ; 1 ГБ / 6 МБ ≈ 170 дней (без событий вышло бы 341).
        val archive = archiveText("cam1")
        assertTrue(archive, archive.contains("примерно на 170 дн."))
        assertTrue("Видно, сколько занимают события", archive.contains("события"))
        val editor = editorText("cam1")
        assertTrue(editor, editor.contains("примерно на 170 дн."))
    }

    @Test fun fullArchiveShowsActualDepthNotForecast() {
        continuous("cam1", 67) // самая старая круглосуточная запись — 67 ч назад (≈ 2,8 дн.)
        ShadowStatFs.registerStats(root, 1_000, 1_000, 1_000) // свободно меньше неприкосновенного остатка
        val archive = archiveText("cam1")
        assertTrue(archive, archive.contains("мало свободного места"))
        assertTrue(archive, archive.contains("2,8 дн."))
        assertFalse("Прогноз «хватит на…» при заполненном месте неверен", archive.contains("хватит"))
    }
}
