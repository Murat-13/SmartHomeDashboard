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

    @Test fun manualExportFilesAreNeverRotated() {
        dir.mkdirs()
        File(dir, "smarthome_config.json").writeText("{}")
        for (n in 1..5) { change(n); run() }
        assertTrue(File(dir, "smarthome_config.json").exists())
        assertEquals(3, files().size)
    }
}
