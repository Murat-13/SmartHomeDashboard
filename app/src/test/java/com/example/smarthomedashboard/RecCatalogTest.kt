package com.example.smarthomedashboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Список записей в памяти (docs/recording.md, «Архив открывается сразу»): что знает, чего не знает, и гонки с обходом. */
class RecCatalogTest {
    private val sd = File("/sd/files")
    private val internal = File("/int/files")
    private val t0 = 1_700_000_000_000L

    @Before fun setUp() = RecCatalog.clear()
    @After fun tearDown() = RecCatalog.clear()

    private fun rec(root: File, camera: String, start: Long, kind: RecFile.Kind = RecFile.Kind.CONTINUOUS, starred: Boolean = false, bytes: Long = 100) =
        RecFile(File(RecLayout.dayDir(root, camera, start), RecLayout.closedName(kind, start, 60_000, starred)), camera, kind, start, 60_000, bytes, starred)

    private fun names(camera: String, vararg roots: File) = RecCatalog.camera(roots.toList(), camera)?.map { it.startMs - t0 }?.sorted()

    @Test fun unknownUntilEveryStorageIsWalked() {
        assertNull(names("door", sd))
        RecCatalog.publish(sd, listOf(rec(sd, "door", t0), rec(sd, "yard", t0 + 1)), RecCatalog.mark())
        assertEquals(listOf(0L), names("door", sd))
        assertNull("память планшета ещё не обойдена", names("door", sd, internal))
        RecCatalog.publish(internal, listOf(rec(internal, "door", t0 + 5)), RecCatalog.mark(), withSizes = false)
        assertEquals(listOf(0L, 5L), names("door", sd, internal))
        assertEquals("камеры нет — пусто, а не неизвестно", emptyList<Long>(), names("other", sd, internal))
        assertEquals(2, RecCatalog.files(sd)!!.size)
        assertNull("без размеров оценку места по списку не считаем", RecCatalog.files(internal))
    }

    @Test fun newDeletedAndStarredFilesAreKnownAtOnce() {
        val first = rec(sd, "door", t0, RecFile.Kind.EVENT)
        RecCatalog.add(first)
        assertNull("носитель не обойдён — обход найдёт файл сам", names("door", sd))
        RecCatalog.publish(sd, listOf(first), RecCatalog.mark())
        RecCatalog.add(rec(sd, "door", t0 + 1))
        RecCatalog.add(rec(sd, "yard", t0 + 2))
        assertEquals(listOf(0L, 1L), names("door", sd))
        assertEquals("новая камера", listOf(2L), names("yard", sd))
        val star = rec(sd, "door", t0, RecFile.Kind.EVENT, starred = true)
        RecCatalog.replace(first, star)
        assertEquals(star.path, RecCatalog.camera(listOf(sd), "door")!!.single { it.kind == RecFile.Kind.EVENT }.path)
        RecCatalog.remove(listOf(star))
        assertEquals(listOf(1L), names("door", sd))
    }

    /** Обход идёт секунды: удаление и ★ за это время не теряются и не возвращаются. */
    @Test fun editsDuringWalkAreNotLost() {
        val gone = rec(sd, "door", t0, RecFile.Kind.EVENT)
        val plain = rec(sd, "door", t0 + 1, RecFile.Kind.EVENT)
        val star = rec(sd, "door", t0 + 1, RecFile.Kind.EVENT, starred = true)
        RecCatalog.publish(sd, listOf(gone, plain), RecCatalog.mark())
        val since = RecCatalog.mark()
        val walked = listOf(gone, plain)
        RecCatalog.remove(listOf(gone))
        RecCatalog.replace(plain, star)
        RecCatalog.publish(sd, walked, since)
        assertEquals(listOf(star.path), RecCatalog.camera(listOf(sd), "door")!!.map { it.path })
        // Сделанное до начала обхода его не правит: файл мог появиться снова.
        RecCatalog.publish(sd, listOf(gone), RecCatalog.mark())
        assertEquals(listOf(0L), names("door", sd))
        // То же для чтения одной камеры архивом.
        RecCatalog.clear()
        val from = RecCatalog.mark()
        RecCatalog.replace(plain, star)
        RecCatalog.publishCamera(sd, "door", listOf(plain), from)
        assertEquals(listOf(star.path), RecCatalog.camera(listOf(sd), "door")!!.map { it.path })
    }

    @Test fun archiveListingNeverReplacesServiceWalk() {
        RecCatalog.publishCamera(sd, "door", listOf(rec(sd, "door", t0)), RecCatalog.mark())
        assertEquals(listOf(0L), names("door", sd))
        assertNull("другие камеры не читались", names("yard", sd))
        RecCatalog.add(rec(sd, "door", t0 + 1))
        assertEquals("новый файл виден и без обхода службы", listOf(0L, 1L), names("door", sd))
        RecCatalog.publish(sd, listOf(rec(sd, "door", t0), rec(sd, "door", t0 + 1), rec(sd, "yard", t0 + 2)), RecCatalog.mark())
        RecCatalog.publishCamera(sd, "door", emptyList(), RecCatalog.mark())
        assertEquals(listOf(0L, 1L), names("door", sd))
        assertEquals(listOf(2L), names("yard", sd))
    }

    @Test fun reconcileFixesOutsideChangesButNotFreshFiles() {
        val old = rec(sd, "door", t0)
        val now = t0 + 3_600_000
        val fresh = rec(sd, "door", now - 70_000)
        RecCatalog.publish(sd, listOf(old, fresh, rec(sd, "yard", t0)), RecCatalog.mark())
        val found = rec(sd, "door", t0 + 600_000)
        // Чтение началось до того, как закрылся [fresh]: его там нет, но он остаётся.
        assertTrue(RecCatalog.reconcile(listOf(sd), "door", listOf(found), RecCatalog.mark(), now))
        assertEquals(listOf(600_000L, 3_600_000L - 70_000), names("door", sd))
        assertEquals("другая камера не тронута", listOf(0L), names("yard", sd))
        assertFalse("повторно — без изменений", RecCatalog.reconcile(listOf(sd), "door", listOf(found, fresh), RecCatalog.mark(), now))
        // ★ во время чтения: прежнее имя в прочитанном — не новый файл.
        val since = RecCatalog.mark()
        val star = rec(sd, "door", t0 + 600_000, starred = true)
        RecCatalog.replace(found, star)
        assertFalse(RecCatalog.reconcile(listOf(sd), "door", listOf(found, fresh), since, now))
        assertEquals(listOf(star.path, fresh.path), RecCatalog.camera(listOf(sd), "door")!!.sortedBy { it.startMs }.map { it.path })
    }
}
