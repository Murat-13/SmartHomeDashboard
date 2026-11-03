package com.example.smarthomedashboard

import android.app.AlertDialog
import android.os.Looper
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/**
 * Повреждённые плитки (0.9.2, этап 1): чтение не меняет сохранённый JSON, оригинал копируется до первой
 * записи, автокопии и копия перед обновлением не создаются из повреждённых данных, панель запускается.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h800dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TileDamageTest {
    private val context = RuntimeEnvironment.getApplication()
    private val tilesPrefs = context.getSharedPreferences("tiles_prefs", 0)
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private val backups = File(context.filesDir, "backups")
    private val broken = """[{"id":"a","type":"clock","title":"Часы"}, null, {"type":"x"}, {"id":"b","""

    private fun stored() = tilesPrefs.getString("tiles", null)
    private fun damagedFiles() = backups.list()?.filter { it.startsWith(TileManager.DAMAGED_PREFIX) }.orEmpty()
    private fun autoCopies() = backups.list()?.filter { it.startsWith(AutoBackup.PREFIX) }.orEmpty()

    @Before fun clean() {
        tilesPrefs.edit().clear().commit()
        prefs.edit().clear().commit()
        context.getSharedPreferences("onboarding_prefs", 0).edit().clear().commit()
        backups.deleteRecursively()
    }

    @Test fun readingDamagedDataNeverRewritesItAndPreservesOriginalOnce() {
        tilesPrefs.edit().putString("tiles", broken).commit()
        val manager = TileManager(context)
        assertTrue(manager.loadTiles().isEmpty())
        assertTrue(manager.loadIssue()!!.unreadable)
        assertEquals("чтение не перезаписывает исходный JSON", broken, stored())
        TileManager(context).loadTiles()
        assertEquals("одна и та же строка сохраняется один раз", 1, damagedFiles().size)
        assertEquals(broken, File(backups, damagedFiles().single()).readText())
    }

    @Test fun partlyDamagedListShowsIntactTilesAndEditingKeepsOriginalCopy() {
        val partly = """[{"id":"a","type":"clock","title":"Часы"}, null, {"type":"x"}]"""
        tilesPrefs.edit().putString("tiles", partly).commit()
        val manager = TileManager(context)
        assertEquals(listOf("a"), manager.loadTiles().map { it.id })
        assertEquals(2, manager.loadIssue()!!.skipped)
        manager.updateTilePosition("a", 50, 60)
        assertEquals(50, manager.loadTiles().single().x)
        assertNull("после правки сохранены только исправные плитки", manager.loadIssue())
        assertEquals("оригинал до правки сохранён", partly, File(backups, damagedFiles().single()).readText())
    }

    @Test fun writeIsRefusedWhenOriginalCannotBePreserved() {
        tilesPrefs.edit().putString("tiles", broken).commit()
        backups.parentFile!!.mkdirs()
        backups.writeText("не папка") // папку копий создать нельзя
        val manager = TileManager(context)
        assertFalse(manager.saveTiles(emptyList()))
        assertEquals("единственный оригинал не уничтожен", broken, stored())
        backups.delete()
    }

    @Test fun damagedTilesDoNotPushOutGoodAutoCopiesOrCountAsPreUpdateCopy() {
        TileManager(context).saveTiles(listOf(TileEntity("a", "clock", "grid", "Часы", 0, 0, 1, 1)))
        var clock = 1_800_000_000_000L
        assertEquals(AutoBackup.Outcome.CREATED, AutoBackup.run(context, now = clock))
        val good = autoCopies()
        tilesPrefs.edit().putString("tiles", broken).commit()
        repeat(4) {
            clock += 86_400_000L
            assertEquals(AutoBackup.Outcome.DAMAGED, AutoBackup.run(context, now = clock))
        }
        assertEquals("исправная копия на месте", good, autoCopies())
        assertTrue(AutoBackup.statusText(context).second.startsWith("Бэкап: пропущен — плитки повреждены"))
        assertFalse(AutoBackup.beforeUpdate(context, "0.9.2", now = clock))
        assertTrue(backups.list()!!.none { it.startsWith(AutoBackup.PRE_UPDATE_PREFIX) })
    }

    @Test fun restorableCopiesListsAutoAndPreUpdateNewestFirst() {
        TileManager(context).saveTiles(listOf(TileEntity("a", "clock", "grid", "Часы", 0, 0, 1, 1)))
        AutoBackup.run(context, now = 1_800_000_000_000L)
        AutoBackup.beforeUpdate(context, "0.9.2", now = 1_800_000_600_000L)
        TileManager.preserveDamaged(context, broken)
        val copies = AutoBackup.restorableCopies(context)
        assertEquals(2, copies.size)
        assertTrue(copies[0].label.startsWith("Перед обновлением до 0.9.2 · "))
        assertTrue(copies[1].label.startsWith("Автокопия · "))
        val restored = JSONObject(copies[1].store.read(copies[1].name)!!)
        assertTrue(restored.getString("tiles_json").contains("\"a\""))
    }

    @Test fun panelStartsWithDamagedTilesShowsRecoveryAndWritesNothing() {
        tilesPrefs.edit().putString("tiles", broken).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(activity.isFinishing)
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals("Плитки повреждены", shadowOf(dialog).title)
        assertEquals("Восстановить…", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertEquals("стартовые плитки не записаны поверх повреждённых данных", broken, stored())
        assertEquals(1, damagedFiles().size)
    }

    @Test fun panelStartsWithJsonNull() {
        tilesPrefs.edit().putString("tiles", "null").commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(activity.isFinishing)
        assertEquals("null", stored())
    }
}
