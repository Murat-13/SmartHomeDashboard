package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Автокопии: только при изменениях, 3 последних, старые удаляются лишь после проверенной новой. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutoBackupTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private val dir = File(context.filesDir, "backups")
    private var clock = 1_800_000_000_000L
    private fun files() = dir.list()?.filter { it.startsWith(AutoBackup.PREFIX) }?.sorted().orEmpty()
    private fun change(n: Int) = TileManager(context).saveTiles(listOf(TileEntity("t$n", "sensor", "grid", "Т$n", n, n, 1, 1)))
    private fun run() = AutoBackup.run(context, now = clock.also { clock += 86_400_000L })

    @Before fun clean() {
        prefs.edit().clear().putString("ha_token", "FAKE-SECRET").commit()
        context.getSharedPreferences("tiles_prefs", 0).edit().clear().commit()
        dir.deleteRecursively()
    }

    @Test fun createsOnlyWhenSomethingChangedAndKeepsThreeNewest() {
        change(1)
        assertEquals(AutoBackup.Outcome.CREATED, run())
        assertEquals(AutoBackup.Outcome.UNCHANGED, run())
        assertEquals(1, files().size)
        for (n in 2..5) { change(n); assertEquals(AutoBackup.Outcome.CREATED, run()) }
        val kept = files()
        assertEquals(3, kept.size)
        // Самая новая копия — последнее состояние; секретов в файле нет.
        val newest = File(dir, kept.last()).readText()
        assertTrue(JSONObject(newest).getString("tiles_json").contains("\"t5\""))
        assertFalse(newest.contains("FAKE-SECRET"))
        assertEquals("ok", prefs.getString(AutoBackup.PREF_LAST_RESULT, null))
    }

    @Test fun failedWriteKeepsPreviousCopiesAndReportsError() {
        change(1)
        run()
        val before = files()
        change(2)
        val broken = object : AutoBackup.Store {
            override fun names() = before
            override fun write(name: String, text: String) = false
            override fun read(name: String): String? = null
            // Убрать свой недописанный файл можно; старые копии — нельзя.
            override fun delete(name: String) = if (name in before) error("Удалена старая копия: $name") else true
        }
        assertEquals(AutoBackup.Outcome.FAILED, AutoBackup.run(context, broken, clock))
        assertEquals(before, files())
        assertEquals("error", prefs.getString(AutoBackup.PREF_LAST_RESULT, null))
        assertEquals(ModernUi.Status.ERROR, AutoBackup.statusText(context).first)
    }

    /** Статус показывает последнюю проверку ночной задачи, а не только последнюю созданную копию. */
    @Test fun statusShowsLastCheckAndWarnsWhenTaskStopped() {
        val p = context.getSharedPreferences("dashboard_prefs", android.content.Context.MODE_PRIVATE)
        val now = java.util.Calendar.getInstance().apply { set(2026, 10, 19, 23, 30, 0) }.timeInMillis
        val copyAt = java.util.Calendar.getInstance().apply { set(2026, 10, 7, 3, 0, 0) }.timeInMillis
        p.edit().putString(AutoBackup.PREF_LAST_RESULT, "ok").putLong(AutoBackup.PREF_LAST_TIME, copyAt)
            .putLong(AutoBackup.PREF_LAST_CHECK, now - 8 * 3_600_000L).commit()
        assertEquals(ModernUi.Status.OK to "Бэкап: проверен сегодня в 15:30 (копия от 7 нояб.)", AutoBackup.statusText(context, now))
        p.edit().putLong(AutoBackup.PREF_LAST_CHECK, now - 26 * 3_600_000L).commit()
        assertEquals("Бэкап: проверен вчера в 21:30 (копия от 7 нояб.)", AutoBackup.statusText(context, now).second)
        p.edit().putLong(AutoBackup.PREF_LAST_CHECK, now - 3 * 86_400_000L).commit()
        val stale = AutoBackup.statusText(context, now)
        assertEquals(ModernUi.Status.ERROR, stale.first)
        assertTrue(stale.second, stale.second.startsWith("Бэкап: проверки не было с 16 нояб."))
    }

    @Test fun manualExportFilesAreNeverRotated() {
        dir.mkdirs()
        File(dir, "smarthome_config.json").writeText("{}")
        for (n in 1..5) { change(n); run() }
        assertTrue(File(dir, "smarthome_config.json").exists())
        assertEquals(3, files().size)
    }
}
