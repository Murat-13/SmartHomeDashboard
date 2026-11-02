package com.example.smarthomedashboard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Копия «перед обновлением»: всегда новая, проверяется чтением, 2 последние, ежедневные не трогает. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PreUpdateBackupTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dir = File(context.filesDir, "backups")

    @Before fun clean() {
        context.getSharedPreferences("dashboard_prefs", 0).edit().clear().putString("ha_token", "FAKE-SECRET").commit()
        dir.deleteRecursively()
    }

    @Test fun createsVerifiedCopyKeepsTwoAndLeavesDailyCopies() {
        dir.mkdirs()
        File(dir, AutoBackup.PREFIX + "20261101_030000.json").writeText("{}")
        var clock = 1_800_000_000_000L
        repeat(3) { assertTrue(AutoBackup.beforeUpdate(context, "0.9.${it + 1}", now = clock.also { clock += 60_000 })) }
        val pre = dir.list()!!.filter { it.startsWith(AutoBackup.PRE_UPDATE_PREFIX) }.sorted()
        assertEquals(2, pre.size)
        assertTrue(pre.last().endsWith("_to_0.9.3.json"))
        val text = File(dir, pre.last()).readText()
        assertTrue(JSONObject(text).has("tiles_json"))
        assertFalse(text.contains("FAKE-SECRET"))
        assertTrue(File(dir, AutoBackup.PREFIX + "20261101_030000.json").exists())
    }

    @Test fun failedWriteOrReadbackReportsFalse() {
        val broken = object : AutoBackup.Store {
            val written = mutableMapOf<String, String>()
            override fun names() = written.keys.toList()
            override fun write(name: String, text: String) = true.also { written[name] = text.dropLast(1) }
            override fun read(name: String) = written[name]
            override fun delete(name: String) = written.remove(name) != null
        }
        assertFalse(AutoBackup.beforeUpdate(context, "0.9.1", broken))
        assertTrue("непроверенная копия удалена", broken.written.isEmpty())
        val failing = object : AutoBackup.Store {
            override fun names() = emptyList<String>()
            override fun write(name: String, text: String) = false
            override fun read(name: String): String? = null
            override fun delete(name: String) = false
        }
        assertFalse(AutoBackup.beforeUpdate(context, "0.9.1", failing))
    }
}
