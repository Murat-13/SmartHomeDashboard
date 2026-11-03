package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.GZIPInputStream

/** Отдельный бэкап истории мощности: копия, ротация, восстановление без дублей и перезаписи, целостность. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PowerHistoryBackupTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private val dir = File(context.filesDir, "backups")
    private val db = PowerHistoryStore(context, name = null)
    private val store get() = AutoBackup.store(context)
    private var now = 1_800_000_000_000L
    private val day = PowerHistory.DAY_MS

    @Before fun clean() {
        prefs.edit().clear().putString("ha_token", "FAKE-SECRET").putString("ha_url", "http://192.0.2.10:8123").commit()
        dir.deleteRecursively()
    }

    @After fun close() = db.close()

    private fun seed() {
        val a = db.sourceId("homeA", "sensor.inv")!!
        db.insert(a, listOf(PowerSample(now - 1000, 120.5), PowerSample(now - 500, null), PowerSample(now, -820.0)))
        db.insertMinutes(a, listOf(PowerBucket(now - 10 * day, -50.0, 900.0, 300.0, 60_000, 12)))
        val b = db.sourceId("homeB", "sensor.grid")!!
        db.insert(b, listOf(PowerSample(now, 42.0)))
    }

    private fun run() = PowerHistoryBackup.run(context, store, db, now)
    private fun files() = dir.list()?.filter { it.startsWith(PowerHistoryBackup.PREFIX) }?.sorted().orEmpty()

    @Test fun createsVerifiedCopyOnlyOnChangeAndKeepsThree() {
        assertEquals(PowerHistoryBackup.Outcome.EMPTY, run())
        seed()
        assertEquals(PowerHistoryBackup.Outcome.CREATED, run())
        assertEquals(PowerHistoryBackup.Outcome.UNCHANGED, run().also { now += day })
        val summary = PowerHistoryBackup.summary(store, files().single())!!
        assertEquals(listOf("homeA" to "sensor.inv", "homeB" to "sensor.grid"), summary.sources)
        assertEquals(4, summary.raw)
        assertEquals(1, summary.minutes)
        repeat(4) { i ->
            now += day
            db.insert(db.sourceId("homeA", "sensor.inv")!!, listOf(PowerSample(now + i, i.toDouble())))
            assertEquals(PowerHistoryBackup.Outcome.CREATED, run())
        }
        assertEquals(3, files().size)
        assertTrue(PowerHistoryBackup.statusText(context).startsWith("История мощности: копия"))
    }

    @Test fun restoreAddsWithoutDuplicatesAndKeepsNewerLocalData() {
        seed()
        run()
        val name = files().single()
        val target = PowerHistoryStore(context, name = null)
        try {
            // На новом планшете уже есть свежая точка с тем же временем — она не заменяется.
            val local = target.sourceId("homeA", "sensor.inv")!!
            target.insert(local, listOf(PowerSample(now, 5.0)))
            val added = PowerHistoryBackup.restore(store, name, target)!!
            assertEquals(4 + 1 - 1, added)
            assertEquals(0, PowerHistoryBackup.restore(store, name, target))
            val series = target.series(local, now - 10 * day - 1, now + 1)
            assertEquals(listOf(120.5, null, 5.0), series.samples.map { it.watts })
            assertEquals(900.0, series.buckets.single().max, 0.0)
            assertNotNull("Разные серверы HA не смешиваются", target.sourceId("homeB", "sensor.grid", create = false))
            assertNull(target.sourceId("homeA", "sensor.grid", create = false))
        } finally {
            target.close()
        }
    }

    @Test fun damagedOrForeignFileIsRejectedWithoutChanges() {
        seed()
        run()
        val name = files().single()
        val file = File(dir, name)
        val text = GZIPInputStream(file.inputStream()).bufferedReader().readText()
        // Меняем одно значение — сумма не сходится.
        val tampered = text.replace("-820.0", "-821.0")
        java.util.zip.GZIPOutputStream(file.outputStream()).bufferedWriter().use { it.write(tampered) }
        assertNull(PowerHistoryBackup.summary(store, name))
        val target = PowerHistoryStore(context, name = null)
        try {
            assertNull(PowerHistoryBackup.restore(store, name, target))
            assertTrue(target.sources().isEmpty())
        } finally {
            target.close()
        }
        File(dir, "smarthome_power_20990101_000000.txt.gz").writeText("not gzip")
        assertNull(PowerHistoryBackup.summary(store, "smarthome_power_20990101_000000.txt.gz"))
    }

    @Test fun copyHasNoSecretsAndSettingsBackupsAreIndependent() {
        TileManager(context).saveTiles(listOf(TileEntity("t", "sensor", "grid", "Т", 1, 1, 1, 1)))
        assertEquals(AutoBackup.Outcome.CREATED, AutoBackup.run(context, now = now))
        seed()
        assertEquals(PowerHistoryBackup.Outcome.CREATED, run())
        val text = GZIPInputStream(File(dir, files().single()).inputStream()).bufferedReader().readText()
        assertFalse(text.contains("FAKE-SECRET"))
        assertFalse(text.contains("192.0.2.10"))
        assertFalse(text.contains("http"))
        // Копии настроек не видят файлы истории, а их ротация не трогает историю.
        assertTrue(AutoBackup.restorableCopies(context).none { it.name.startsWith(PowerHistoryBackup.PREFIX) })
        repeat(4) { i ->
            TileManager(context).saveTiles(listOf(TileEntity("t$i", "sensor", "grid", "Т$i", i, i, 1, 1)))
            AutoBackup.run(context, now = now + (i + 1) * day)
        }
        assertEquals(1, files().size)
        assertEquals(3, dir.list()!!.count { it.startsWith(AutoBackup.PREFIX) })
    }

    @Test fun failedWriteKeepsPreviousCopies() {
        seed()
        run()
        val before = files()
        val broken = object : AutoBackup.Store by store {
            override fun output(name: String): java.io.OutputStream? = null
        }
        db.insert(db.sourceId("homeA", "sensor.inv")!!, listOf(PowerSample(now + 5, 1.0)))
        assertEquals(PowerHistoryBackup.Outcome.FAILED, PowerHistoryBackup.run(context, broken, db, now + day))
        assertEquals(before, files())
        assertTrue(PowerHistoryBackup.statusText(context).contains("ошибка"))
    }
}
