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
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeRight
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
        override fun timelineTap(atMs: Long, toleranceMs: Long) { calls += "tap"; lastAt = atMs }
        override fun timelineScrub(atMs: Long, done: Boolean) { calls += "scrub:$done"; lastAt = atMs }
        override fun timelineSpan(spanMs: Long) { calls += "span"; lastSpan = spanMs }
        override fun toNow() { calls += "now" }
        override fun neighbour(forward: Boolean) { calls += "event:$forward" }
        override fun captureFrame() { calls += "capture" }
        override fun frameAction(save: Boolean?) { calls += "frame:$save" }
        override fun mark(start: Boolean) { calls += "mark:$start" }
        override fun clearMarks() { calls += "clear" }
        override fun shareSegment() { calls += "segment" }
        override fun tab(tab: ArchiveUi.Tab) { calls += "tab:$tab" }
        override fun calendar(show: Boolean) { calls += "calendar:$show" }
        override fun ringThumb(ring: RingLog.Ring) {}
        override fun ringPhoto(ring: RingLog.Ring?) { calls += "ring:${ring?.atMs}" }
        override fun openRing(ring: RingLog.Ring) { calls += "open:${ring.atMs}" }
        override fun preview(atMs: Long) { previews++ }
        override fun selectWeek() { calls += "week" }
        override fun camera(tileId: String) { calls += "camera:$tileId" }
        var previews = 0
        var lastAt = 0L
        var lastSpan = 0L
        override fun scrubClip(fraction: Float, done: Boolean) { calls += "clip:$done" }
        override fun togglePlay() { calls += "toggle" }
        override fun seekBy(deltaMs: Long) { calls += "seekBy:$deltaMs" }
        override fun frameStep() { calls += "frame" }
        override fun speed(value: Float) { calls += "speed:$value" }
        override fun star(event: RecArchive.Event) { calls += "star:${event.file.startMs}" }
        override fun delete(event: RecArchive.Event) { calls += "delete:${event.file.startMs}" }
        override fun confirmDelete(event: RecArchive.Event?) { calls += "confirm:${event?.file?.startMs}" }
        override fun share(event: RecArchive.Event) { calls += "share:${event.file.startMs}" }
        override fun thumbnail(file: RecFile) {}
        override fun fullscreen(on: Boolean) { calls += "fullscreen:$on" }
        override fun toggleControls() { calls += "controls" }
    }

    /**
     * Решения владельца 23.11.2026: архив сразу показывает кадр плитки, а не чёрный экран; ролик — на весь экран кнопкой
     * ⛶ и двойным касанием; на весь экран касание прячет кнопки, внизу — перемотка, скорость, соседние события.
     */
    /**
     * Владелец 23.11.2026: на весь экран, когда шкала доходит до события, «пропадает возможность мотать пальцем». Шкала
     * остаётся под пальцем (и не сдвигается), прокрутка доходит до конца; в обычном виде — то же.
     */
    @Test fun scrubThroughEventKeepsTimelineUnderFinger() {
        val actions = Recorded()
        fun start(full: Boolean) = ArchiveUi(title = "Домофон", loading = false, days = listOf(day), day = day,
            continuous = true, fullscreen = full, center = day + 3_600_000,
            timeline = ArchiveUi.Timeline(listOf(day until day + 86_400_000), emptyList()))
        var ui by androidx.compose.runtime.mutableStateOf(start(true))
        compose.setContent { ArchiveScreen(ui, emptyMap<String, Bitmap?>(), actions, video = {}) }
        for (full in listOf(true, false)) {
            actions.calls.clear()
            ui = start(full)
            compose.waitForIdle()
            val before = compose.onNodeWithTag("archive_timeline").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("archive_timeline").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(-80f, 0f)) }
            // Под риской событие — плеер переключился на ролик события в HD.
            ui = ui.copy(continuous = false, current = event(day + 3_500_000, setOf("person")), durationMs = 65_000)
            compose.waitForIdle()
            assertEquals("шкала не сдвинулась (на весь экран: $full)", before,
                compose.onNodeWithTag("archive_timeline").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("archive_timeline").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(-80f, 0f)); up() }
            compose.waitForIdle()
            assertTrue("прокрутка дошла до конца (на весь экран: $full): ${actions.calls}", "scrub:true" in actions.calls)
        }
    }

    @Test fun placeholderFrameAndFullscreenControls() {
        val frame = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val actions = Recorded()
        var ui by androidx.compose.runtime.mutableStateOf(ArchiveUi(title = "Домофон", loading = true, placeholder = frame))
        compose.setContent { ArchiveScreen(ui, emptyMap<String, Bitmap?>(), actions, video = {}) }
        compose.onNodeWithTag("archive_placeholder", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Выберите событие или место на шкале").assertDoesNotExist()

        val clip = event(day + 3_600_000, setOf("person"))
        ui = ui.copy(loading = false, placeholder = null, current = clip, durationMs = 65_000, days = listOf(day), day = day)
        compose.waitForIdle()
        compose.onNodeWithTag("archive_placeholder", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("archive_fullscreen").performClick()
        compose.onNodeWithTag("archive_video").performTouchInput { doubleClick(center) }
        assertEquals(listOf("fullscreen:true", "fullscreen:true"), actions.calls)

        actions.calls.clear()
        ui = ui.copy(fullscreen = true)
        compose.waitForIdle()
        compose.onNodeWithTag("archive_fullscreen_view").assertIsDisplayed()
        compose.onNodeWithTag("archive_list").assertDoesNotExist()
        compose.onNodeWithTag("archive_clip_slider").assertIsDisplayed()
        compose.onNodeWithTag("archive_fs_back10").performClick()
        compose.onNodeWithTag("archive_fs_speed_4").performClick()
        compose.onNodeWithTag("archive_fs_next_event").performClick()
        compose.onNodeWithTag("archive_video").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(1_000) // одиночное касание — когда вышло время на второе
        compose.waitForIdle()
        compose.onNodeWithTag("archive_fullscreen_exit").performClick()
        assertEquals(listOf("seekBy:-10000", "speed:4.0", "event:true", "controls", "fullscreen:false"), actions.calls)

        actions.calls.clear()
        ui = ui.copy(controls = false)
        compose.waitForIdle()
        compose.onNodeWithTag("archive_fullscreen_controls").assertDoesNotExist()
        compose.onNodeWithTag("archive_video").performTouchInput { doubleClick(center) }
        assertEquals("двойное касание — обратно", listOf("fullscreen:false"), actions.calls)
    }

    private fun event(start: Long, labels: Set<String>, starred: Boolean = false) =
        RecArchive.Event(RecFile(File("e_$start.mp4"), "door", RecFile.Kind.EVENT, start, 65_000, 10, starred), labels)

    @Test fun screenShowsEventsWithLabelsAndDispatchesActions() {
        val a = event(day + 3_600_000, setOf("visitor"))
        val b = event(day + 7_200_000, setOf("person", "vehicle"), starred = true)
        val actions = Recorded()
        val ui = ArchiveUi(title = "Домофон", loading = false, days = listOf(day), day = day, events = listOf(b, a),
            timeline = ArchiveUi.Timeline(listOf(day + 3_000_000 until day + 4_000_000),
                listOf(ArchiveUi.Mark(day + 3_600_000, day + 3_630_000, "visitor"))), center = day + 3_600_000, current = b)
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

    /**
     * Шкала как у Reolink (решение владельца 19.11.2026): риска по центру неподвижна. Касание по центру — тот же
     * момент; ведение ленты вправо — назад во времени, по ходу движения перемотка, отпустили — точный переход;
     * два пальца — масштаб; у ролика события — своя полоса.
     */
    @Test fun timelineTapSeeksDragScrubsPinchZoomsAndClipBarScrubs() {
        val a = event(day + 3_600_000, setOf("person"))
        val actions = Recorded()
        val centerAt = day + 12 * 3_600_000L
        val ui = ArchiveUi(title = "Домофон", loading = false, days = listOf(day), day = day, events = listOf(a),
            timeline = ArchiveUi.Timeline(listOf(day until day + 86_400_000L), emptyList()), center = centerAt,
            spanMs = 3_600_000L, current = a, positionMs = 10_000, durationMs = 65_000)
        compose.setContent { ArchiveScreen(ui, emptyMap(), actions, video = {}) }
        compose.onNodeWithTag("archive_timeline").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf("tap"), actions.calls)
        assertEquals("касание по риске — момент под ней", centerAt.toDouble(), actions.lastAt.toDouble(), 60_000.0)
        actions.calls.clear()
        compose.onNodeWithTag("archive_timeline").performTouchInput {
            down(center)
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(width / 40f, 0f), delayMillis = 200) }
            up()
        }
        compose.waitForIdle()
        assertTrue("по ходу движения: ${actions.calls}", actions.calls.count { it == "scrub:false" } >= 2)
        assertTrue("превью над риской запрашиваются по ходу", actions.previews >= 2)
        assertEquals("отпустили — точный переход, не «касание»", "scrub:true", actions.calls.last())
        assertFalse(actions.calls.contains("tap"))
        assertTrue("лента вправо — назад во времени", actions.lastAt < centerAt - 10 * 60_000L)
        assertTrue("на четверть ширины (час на всю) — около 15 мин", actions.lastAt > centerAt - 25 * 60_000L)
        actions.calls.clear()
        compose.onNodeWithTag("archive_timeline").performTouchInput { pinch(center - androidx.compose.ui.geometry.Offset(20f, 0f),
            centerLeft + androidx.compose.ui.geometry.Offset(5f, 0f), center + androidx.compose.ui.geometry.Offset(20f, 0f),
            centerRight - androidx.compose.ui.geometry.Offset(5f, 0f)) }
        compose.waitForIdle()
        assertEquals(listOf("span"), actions.calls)
        assertTrue("пальцы развели — видно меньше времени: ${actions.lastSpan}", actions.lastSpan < 3_600_000L)
        assertTrue(actions.lastSpan >= ArchiveTimeline.MIN_SPAN)
        actions.calls.clear()
        actions.calls.clear()
        compose.onNodeWithTag("archive_clip_slider").assertIsDisplayed().performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals("clip:true", actions.calls.last())
    }

    @Test fun yuvToArgbScalesAndConverts() {
        // 4×2 кадр: серый (Y=126, U=V=128) → около (128,128,128); уменьшение до 2 по ширине.
        val y = IntArray(8) { 126 }
        val uv = IntArray(4) { 128 }
        val r = Yuv.toArgb(4, 2, 0, 0, 2, { y[it] }, 4, 1, { uv[it] }, { uv[it] }, 2, 1)!!
        assertEquals(2, r.width); assertEquals(1, r.height)
        val px = r.pixels[0]
        assertEquals(128.0, ((px shr 16) and 0xFF).toDouble(), 2.0)
        assertEquals(128.0, (px and 0xFF).toDouble(), 2.0)
        // Красный: высокий V (Cr).
        val red = Yuv.toArgb(2, 2, 0, 0, 2, { 82 }, 2, 1, { 90 }, { 240 }, 1, 1)!!.pixels[0]
        assertTrue("красный канал ${(red shr 16) and 0xFF}", ((red shr 16) and 0xFF) > 200)
        assertTrue(((red shr 8) and 0xFF) < 80)
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

    /** Новое 19.11.2026: «◀ Событие ▶», кадр, отрезок, 8×, «Сейчас», календарь, вкладка «Звонки», место. */
    @Test fun newControlsCallsTabAndStorage() {
        val a = event(day + 3_600_000, setOf("person"))
        val actions = Recorded()
        val ring = RingLog.Ring(day + 3_605_000, null, answered = false)
        var ui by mutableStateOf(ArchiveUi(title = "Домофон", loading = false, days = listOf(day), day = day, events = listOf(a),
            timeline = ArchiveUi.Timeline(emptyList(), emptyList()), center = day + 3_600_000, current = a, continuous = false,
            rings = listOf(ring, RingLog.Ring(day + 100_000, null, answered = true)), storage = "Записи: 3,2 из 20 ГБ"))
        compose.setContent { ArchiveScreen(ui, emptyMap(), actions, video = {}) }
        listOf("archive_prev_event", "archive_next_event", "archive_capture", "archive_mark_in", "archive_mark_out",
            "archive_speed_8", "archive_now", "archive_calendar").forEach { compose.onNodeWithTag(it).performClick() }
        assertEquals(listOf("event:false", "event:true", "capture", "mark:true", "mark:false", "speed:8.0", "now", "calendar:true"), actions.calls)
        compose.onNodeWithTag("archive_storage").assertIsDisplayed()
        // Отрезок отмечен — «Поделиться отрезком» и сброс.
        ui = ui.copy(markIn = day + 3_601_000, markOut = day + 3_620_000)
        compose.waitForIdle()
        actions.calls.clear()
        compose.onNodeWithTag("archive_share_segment").performClick()
        compose.onNodeWithTag("archive_clear_marks").performClick()
        assertEquals(listOf("segment", "clear"), actions.calls)
        // Звонки: пропущенный и отвеченный, касание — снимок, «Запись звонка».
        ui = ui.copy(tab = ArchiveUi.Tab.CALLS)
        compose.waitForIdle()
        compose.onNodeWithText("Пропущен").assertIsDisplayed()
        compose.onNodeWithText("Ответили").assertIsDisplayed()
        actions.calls.clear()
        compose.onNodeWithTag("archive_ring_${ring.atMs}").performClick()
        assertEquals(listOf("ring:${ring.atMs}"), actions.calls)
        ui = ui.copy(ringPhoto = ring)
        compose.waitForIdle()
        compose.onNodeWithText("Снимка нет: в момент звонка видео не шло").assertIsDisplayed()
        compose.onNodeWithTag("archive_ring_open").performClick()
        assertEquals("open:${ring.atMs}", actions.calls.last())
    }

    /** «Неделя»: события за 7 дней с заголовками дней; статус копии ★. */
    @Test fun weekListHasDayHeadersAndCopyStatus() {
        val yesterday = day - 86_400_000L
        val a = event(day + 3_600_000, setOf("person"))
        val b = event(yesterday + 7_200_000, setOf("visitor"))
        val actions = Recorded()
        var ui by mutableStateOf(ArchiveUi(title = "Домофон", loading = false, days = listOf(day, yesterday), day = day,
            events = listOf(a), copyStatus = "★ ролики копируются ночью в папку копий"))
        compose.setContent { ArchiveScreen(ui, emptyMap(), actions, video = {}) }
        compose.onNodeWithTag("archive_week").performClick()
        assertEquals(listOf("week"), actions.calls)
        compose.onNodeWithTag("archive_copy_status").assertIsDisplayed()
        ui = ui.copy(week = true, events = listOf(a, b))
        compose.waitForIdle()
        compose.onNodeWithTag("archive_week_day_$day").assertIsDisplayed()
        compose.onNodeWithTag("archive_week_day_$yesterday").assertIsDisplayed()
        compose.onNodeWithText("Сегодня · 1").assertIsDisplayed()
        compose.onNodeWithText("Вчера · 1").assertIsDisplayed()
    }

    /** Ночная копия ★ (ArchiveCopy): копирует, пропускает уже скопированные, заменяет недописанные, чистит за ошибкой. */
    @Test fun starredCopySkipsCopiedAndReplacesPartial() {
        val root = tmp.newFolder("root")
        val f1 = RecLayout.parse(file(root, "door", RecFile.Kind.EVENT, day + 1_000, 20_000, starred = true), "door")!!
        val f2 = RecLayout.parse(file(root, "door", RecFile.Kind.EVENT, day + 61_000, 20_000, starred = true), "door")!!
        val stored = linkedMapOf<String, java.io.ByteArrayOutputStream>()
        val folder = object : ArchiveCopy.Folder {
            override fun sizes() = stored.mapValues { it.value.size().toLong() }
            override fun create(name: String) = java.io.ByteArrayOutputStream().also { stored[name] = it }
            override fun delete(name: String) { stored.remove(name) }
        }
        val name1 = ArchiveCopy.nameFor("Калитка: двор", f1)
        assertTrue(name1, name1.startsWith("Калитка  двор ") && name1.endsWith(".mp4"))
        stored[ArchiveCopy.nameFor("Домофон", f2)] = java.io.ByteArrayOutputStream().apply { write(ByteArray(10)) } // недописанная
        val first = ArchiveCopy.copy(listOf("Домофон" to f1, "Домофон" to f2), folder)
        assertEquals(2, first.copied)
        assertEquals(listOf(100, 100), stored.values.map { it.size() })
        val second = ArchiveCopy.copy(listOf("Домофон" to f1, "Домофон" to f2), folder)
        assertEquals("уже скопированные — пропуск", ArchiveCopy.Result(0, 2, 0, 0), second)
        val broken = ArchiveCopy.copy(listOf("Домофон" to f1.copy(startMs = day + 999_000)), folder, open = { null })
        assertEquals(1, broken.failed)
        assertEquals("за неудачей не остаётся пустого файла", 2, stored.size)
    }

    /**
     * Второй аудит 25.11.2026, NEW-DATA-1: ночью список папки копий бывает пустым — по нему одному все ★-ролики копировались бы заново
     * (под именами «… (1)»). Копия находится по своему адресу; удалённая владельцем — копируется снова.
     */
    @Test fun starredCopyDoesNotRepeatWhenFolderListIsEmpty() {
        val root = tmp.newFolder("root")
        val f1 = RecLayout.parse(file(root, "door", RecFile.Kind.EVENT, day + 1_000, 20_000, starred = true), "door")!!
        val f2 = RecLayout.parse(file(root, "door", RecFile.Kind.EVENT, day + 61_000, 20_000, starred = true), "door")!!
        val stored = linkedMapOf<String, java.io.ByteArrayOutputStream>()
        var listed = true; var created = 0
        val folder = object : ArchiveCopy.Folder {
            override var lastRef: String? = null
            override fun sizes() = if (listed) stored.mapValues { it.value.size().toLong() } else emptyMap()
            // Как хранилище Android: имя занято — файл ложится рядом как «имя (1)».
            override fun create(name: String) = java.io.ByteArrayOutputStream().also { val key = if (name in stored) "$name (1)" else name; stored[key] = it; lastRef = "ref:$key"; created++ }
            override fun delete(name: String) { stored.remove(name) }
            override fun sizeAt(ref: String) = stored[ref.removePrefix("ref:")]?.size()?.toLong()
            override fun deleteAt(ref: String) { stored.remove(ref.removePrefix("ref:")) }
        }
        val files = listOf("Домофон" to f1, "Домофон" to f2)
        val refs = HashMap<String, String>()
        assertEquals(2, ArchiveCopy.copy(files, folder, refs).copied)
        listed = false
        assertEquals("список пуст — копии находятся по адресу", ArchiveCopy.Result(0, 2, 0, 0), ArchiveCopy.copy(files, folder, ArchiveCopy.loadRefs(org.json.JSONObject(refs as Map<*, *>).toString())))
        assertEquals(2, created)
        stored.remove(ArchiveCopy.nameFor("Домофон", f1))
        assertEquals("удалённая владельцем копия делается заново", 1, ArchiveCopy.copy(files, folder, refs).copied)
        // Оборванная копия при пустом списке папки: заменяется по своему адресу, второй файл рядом не появляется.
        stored[ArchiveCopy.nameFor("Домофон", f2)] = java.io.ByteArrayOutputStream().apply { write(ByteArray(7)) }
        assertEquals(1, ArchiveCopy.copy(files, folder, refs).copied)
        assertEquals(listOf(100, 100), stored.values.map { it.size() })
        ArchiveCopy.copy(files.take(1), folder, refs)
        assertEquals("сняли ★ — адрес забыт", setOf(ArchiveCopy.nameFor("Домофон", f1)), refs.keys)
    }

    @Test fun storageTextAndCalendarDates() {
        // Строка о месте — общая с редактором (RecRetention.outlookText, RecordingStorageTest, RecordingSpaceOutlookTest).
        assertEquals("3,2 ГБ", RecRetention.size(3_435_973_837L))
        assertEquals("20 ГБ", RecRetention.size(20 * RecRetention.GB))
        assertEquals("512 МБ", RecRetention.size(RecRetention.GB / 2))
        assertEquals("2,8 дн.", RecRetention.daysText(2.8))
        assertEquals("85 дн.", RecRetention.daysText(85.4))
        assertEquals("5 ч", RecRetention.daysText(5.5 / 24))
        fun used(u: Long, l: Long) = RecRetention.usedOfLimit(RecRetention.Outlook(u, l, false, false, null, null, null, false, l, 0))
        assertEquals("3,2 из 20 ГБ", used(3_435_973_837L, 20 * RecRetention.GB))
        assertEquals("700 МБ из 1 ГБ", used(700L * 1024 * 1024, RecRetention.GB))
        // DatePicker: местная полночь ↔ полночь UTC того же числа.
        assertEquals(day, utcDateToLocal(localToUtcDate(day)))
        assertEquals(0L, localToUtcDate(day) % 86_400_000L)
    }

    /** Несколько камер (docs/camera-tz.md): переключение; у камеры нет вкладки «Звонки». */
    @Test fun cameraSwitchAndNoCallsTabForCamera() {
        val actions = Recorded()
        var ui by mutableStateOf(ArchiveUi(title = "Улица", loading = false, cameras = listOf("door" to "Домофон", "cam" to "Улица"),
            camera = "cam", doorbell = false))
        compose.setContent { ArchiveScreen(ui, emptyMap(), actions, video = {}) }
        compose.onNodeWithTag("archive_camera_door").performClick()
        assertEquals(listOf("camera:door"), actions.calls)
        compose.onNodeWithTag("archive_tab_calls").assertDoesNotExist()
        ui = ui.copy(doorbell = true, camera = "door")
        compose.waitForIdle()
        compose.onNodeWithTag("archive_tab_calls").assertIsDisplayed()
        // Одна камера — переключателя нет.
        ui = ui.copy(cameras = listOf("door" to "Домофон"))
        compose.waitForIdle()
        compose.onNodeWithTag("archive_camera_door").assertDoesNotExist()
    }

    @Test fun emptyArchiveSaysSo() {
        compose.setContent { ArchiveScreen(ArchiveUi(title = "Домофон", loading = false), emptyMap(), Recorded(), video = {}) }
        compose.onNodeWithText("Записей пока нет").assertIsDisplayed()
    }
}
