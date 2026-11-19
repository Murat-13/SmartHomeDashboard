package com.example.smarthomedashboard

import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

// Запись с камер, этап 3 (docs/recording.md): архив — файлы и метки, звёздочка, удаление, «Поделиться», шкала суток, экран.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h800dp-land") // планшет на стене
class RecordingArchiveTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val day = RecArchive.dayStart(System.currentTimeMillis())

    private fun file(root: File, camera: String, kind: RecFile.Kind, start: Long, duration: Long, starred: Boolean = false): File =
        File(RecLayout.dayDir(root, camera, start).apply { mkdirs() }, RecLayout.closedName(kind, start, duration, starred))
            .apply { writeBytes(ByteArray(100)) }

    @Test fun archiveListsOnlyThisCameraWithLabelsNewestFirst() {
        val root = tmp.newFolder("root")
        val index = RecIndex(context, null)
        val e1 = file(root, "door", RecFile.Kind.EVENT, day + 3_600_000, 30_000)
        val e2 = file(root, "door", RecFile.Kind.EVENT, day + 7_200_000, 45_000)
        file(root, "door", RecFile.Kind.CONTINUOUS, day + 3_000_000, 60_000)
        file(root, "other", RecFile.Kind.EVENT, day + 3_700_000, 10_000)
        File(RecLayout.dayDir(root, "door", day), RecLayout.openName(RecFile.Kind.EVENT, day + 9_000_000)).writeBytes(ByteArray(5))
        index.add(RecLayout.parse(e1, "door")!!, setOf("visitor", "person"))
        val archive = RecArchive(context, roots = { listOf(root) }, index = { index })
        val snap = archive.load("door")
        assertEquals("Идущий (.part) и чужая камера не показываются", listOf(e2.name, e1.name), snap.events.map { it.file.path.name })
        assertEquals(setOf("visitor", "person"), snap.events[1].labels)
        assertTrue(snap.events[0].labels.isEmpty())
        assertEquals(1, snap.continuous.size)
        assertEquals(listOf(day), snap.days)
        index.close()
    }

    @Test fun starRenamesFileAndDeleteRemovesFileAndIndexRow() {
        val root = tmp.newFolder("root")
        val index = RecIndex(context, null)
        val f = RecLayout.parse(file(root, "door", RecFile.Kind.EVENT, day + 1_000, 20_000), "door")!!
        index.add(f, setOf("vehicle"))
        val archive = RecArchive(context, roots = { listOf(root) }, index = { index })
        val starred = archive.setStarred(f, true)!!
        assertTrue(starred.starred)
        assertTrue(starred.path.name.endsWith("_s.mp4"))
        assertFalse(f.path.exists())
        // Звёздочка в имени — после повторного чтения метки и отметка на месте.
        val again = archive.load("door").events.single()
        assertTrue(again.starred)
        assertEquals(setOf("vehicle"), again.labels)
        assertTrue(archive.delete(again.file))
        assertFalse(again.file.path.exists())
        assertTrue(index.events("door").isEmpty())
        index.close()
    }

    @Test fun shareCopyHasReadableNameAndSameBytes() {
        val root = tmp.newFolder("root")
        val f = RecLayout.parse(file(root, "door", RecFile.Kind.EVENT, day + 21 * 3_600_000L + 30 * 60_000L, 20_000), "door")!!
        val copy = RecArchive(context, roots = { listOf(root) }).shareCopy(f, "Калитка: двор")
        assertEquals("Калитка  двор ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(day))} 21-30.mp4", copy.name)
        assertTrue(copy.readBytes().contentEquals(f.path.readBytes()))
        assertTrue(copy.path.contains(RecArchive.SHARE_DIR))
    }

    @Test fun dayTimelineMergesSmallGapsAndLocatesPositions() {
        fun seg(startMin: Int, durMin: Int) = RecFile(File("c"), "door", RecFile.Kind.CONTINUOUS, day + startMin * 60_000L, durMin * 60_000L, 1)
        // 10:00–10:02 подряд (стык 1 с), пропуск, 11:00–11:01.
        val segments = listOf(seg(600, 1), RecFile(File("c2"), "door", RecFile.Kind.CONTINUOUS, day + 601 * 60_000L + 1_000, 60_000, 1), seg(660, 1))
        val d = ArchiveDay(day, segments, emptyList())
        assertEquals(2, d.coverage.size)
        assertEquals(day + 600 * 60_000L, d.coverage[0].first)
        // Внутри второго отрезка.
        assertEquals(1 to 29_000L, d.locate(day + 601 * 60_000L + 30_000))
        // В пропуске — начало следующего отрезка.
        assertEquals(2 to 0L, d.locate(day + 630 * 60_000L))
        // До начала дня записи — первый отрезок; после конца — конец последнего.
        assertEquals(0 to 0L, d.locate(day))
        assertEquals(2, d.locate(day + 1_000 * 60_000L)!!.first)
        assertEquals(day + 660 * 60_000L + 5_000, d.wallTime(2, 5_000))
        assertEquals(0.5f, d.fraction(day + 12 * 3_600_000L), 0.0001f)
        assertEquals(day + 6 * 3_600_000L, d.timeAt(0.25f))
        assertNull(ArchiveDay(day, emptyList(), emptyList()).locate(day))
    }

    // ---- Экран ----

    private class Recorded : ArchiveActions {
        val calls = mutableListOf<String>()
        override fun back() { calls += "back" }
        override fun selectDay(day: Long) { calls += "day" }
        override fun filter(label: String?) { calls += "filter:$label" }
        override fun play(event: RecArchive.Event) { calls += "play:${event.file.startMs}" }
        override fun seekTimeline(fraction: Float) { calls += "seek" }
        override fun togglePlay() { calls += "toggle" }
        override fun seekBy(deltaMs: Long) { calls += "seekBy:$deltaMs" }
        override fun frameStep() { calls += "frame" }
        override fun speed(value: Float) { calls += "speed:$value" }
        override fun star(event: RecArchive.Event) { calls += "star:${event.file.startMs}" }
        override fun delete(event: RecArchive.Event) { calls += "delete:${event.file.startMs}" }
        override fun confirmDelete(event: RecArchive.Event?) { calls += "confirm:${event?.file?.startMs}" }
        override fun share(event: RecArchive.Event) { calls += "share:${event.file.startMs}" }
        override fun thumbnail(file: RecFile) {}
    }

    private fun event(start: Long, labels: Set<String>, starred: Boolean = false) =
        RecArchive.Event(RecFile(File("e_$start.mp4"), "door", RecFile.Kind.EVENT, start, 65_000, 10, starred), labels)

    @Test fun screenShowsEventsWithLabelsAndDispatchesActions() {
        val a = event(day + 3_600_000, setOf("visitor"))
        val b = event(day + 7_200_000, setOf("person", "vehicle"), starred = true)
        val actions = Recorded()
        val ui = ArchiveUi(title = "Домофон", loading = false, days = listOf(day), day = day, events = listOf(b, a),
            timeline = ArchiveUi.Timeline(listOf(0.1f to 0.2f), listOf(0.15f to "visitor")), current = b)
        compose.setContent { ArchiveScreen(ui, emptyMap<String, Bitmap?>(), actions, video = {}) }
        compose.onNodeWithText("Архив · Домофон").assertIsDisplayed()
        // Метки события — в его строке («Звонок» ещё и в фильтрах).
        compose.onNode(androidx.compose.ui.test.hasText("Звонок") and androidx.compose.ui.test.hasAnyAncestor(
            androidx.compose.ui.test.hasTestTag("archive_event_${a.file.startMs}")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.hasText("Машина") and androidx.compose.ui.test.hasAnyAncestor(
            androidx.compose.ui.test.hasTestTag("archive_event_${b.file.startMs}")), useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("1 мин 05 с").assertCountEquals(2)
        compose.onNodeWithText("02:00:00").assertIsDisplayed()
        compose.onNodeWithTag("archive_event_${a.file.startMs}").performClick()
        compose.onNodeWithTag("archive_star_${a.file.startMs}").performClick()
        compose.onNodeWithTag("archive_filter_person").performClick()
        compose.onNodeWithTag("archive_fwd10").performClick()
        compose.onNodeWithTag("archive_speed_2").performClick()
        compose.onNodeWithTag("archive_share").performClick()
        compose.onNodeWithTag("archive_delete").performClick()
        assertEquals(listOf("play:${a.file.startMs}", "star:${a.file.startMs}", "filter:person", "seekBy:10000", "speed:2.0",
            "share:${b.file.startMs}", "delete:${b.file.startMs}"), actions.calls)
        // Сохранённый ролик — «★ Сохранён» у текущего.
        compose.onNodeWithText("★ Сохранён").assertIsDisplayed()
    }

    @Test fun deleteNeedsConfirmationAndLockedArchiveShowsNothing() {
        val a = event(day + 3_600_000, setOf("animal"))
        val actions = Recorded()
        var ui by mutableStateOf(ArchiveUi(title = "Домофон", loading = false, days = listOf(day), day = day,
            events = listOf(a), current = a, confirmDelete = a))
        compose.setContent { ArchiveScreen(ui, emptyMap(), actions, video = {}) }
        compose.onNodeWithTag("archive_delete_confirm").performClick()
        assertEquals(listOf("confirm:${a.file.startMs}"), actions.calls)
        ui = ArchiveUi(locked = true)
        compose.waitForIdle()
        compose.onNodeWithText("Архив защищён PIN").assertIsDisplayed()
        compose.onNodeWithTag("archive_list").assertDoesNotExist()
    }

    @Test fun doorbellCardShowsArchiveButtonAndRecordingDot() {
        val expanded = DoorbellCard(context, expanded = true)
        val archive = expanded.findViewWithTag<android.widget.TextView>("door_archive")
        assertEquals(android.view.View.GONE, archive.visibility)
        var opened = 0
        expanded.onArchive = { opened++ }
        assertEquals(android.view.View.VISIBLE, archive.visibility)
        archive.performClick()
        assertEquals(1, opened)
        // У компактной плитки архива нет.
        val compact = DoorbellCard(context, expanded = false).apply { onArchive = {} }
        assertEquals(android.view.View.GONE, compact.findViewWithTag<android.view.View>("door_archive").visibility)

        val rec = compact.findViewWithTag<android.widget.TextView>("door_rec")
        compact.tileId = "door_rec_test"
        assertEquals("Запись выключена — точки нет", android.view.View.GONE, rec.visibility)
        RecordingDevice(context).setEnabled("door_rec_test", true)
        compact.tileId = "door_rec_test"
        assertEquals("●", rec.text.toString())
        RecordingStatus.set("door_rec_test" + RecorderService.HD, CameraRecorder.Status(CameraRecorder.State.RECORDING, EventRecorder.IDLE))
        compact.tileId = "door_rec_test"
        assertEquals("● REC", rec.text.toString())
        RecordingStatus.clear()
        RecordingDevice(context).setEnabled("door_rec_test", false)
    }

    @Test fun emptyArchiveSaysSo() {
        compose.setContent { ArchiveScreen(ArchiveUi(title = "Домофон", loading = false), emptyMap(), Recorded(), video = {}) }
        compose.onNodeWithText("Записей пока нет").assertIsDisplayed()
    }
}
