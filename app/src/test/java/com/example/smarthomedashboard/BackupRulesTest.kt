package com.example.smarthomedashboard

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRulesTest {
    private fun xml(path: String): Element {
        val relative = "src/main/$path"
        val file = File(relative).takeIf { it.exists() } ?: File("app/$relative")
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }
    private fun rulesPermit(root: Element, file: String): Boolean {
        fun paths(tag: String) = root.getElementsByTagName(tag).let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
                .filter { it.getAttribute("domain") == "sharedpref" }.map { it.getAttribute("path") }
        }
        val excludes = paths("exclude")
        val includes = paths("include")
        return excludes.none { it == file || it == "." } &&
            (root.getElementsByTagName("include").length == 0 || includes.any { it == file || it == "." })
    }
    private fun assertPrefsRules(root: Element) {
        assertFalse("Открытые dashboard_prefs не должны резервироваться", rulesPermit(root, "dashboard_prefs.xml"))
        assertTrue("Плитки должны оставаться в backup", rulesPermit(root, "tiles_prefs.xml"))
        assertExcludesInternalCopies(root)
    }
    /** Внутренние копии (адреса HA, координаты) — files/backups/, там же, где их пишет AutoBackup. */
    private fun assertExcludesInternalCopies(root: Element) {
        val excludes = root.getElementsByTagName("exclude").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }.map { it.getAttribute("domain") to it.getAttribute("path") }
        }
        assertTrue("files/backups/ исключена: $excludes", ("file" to "backups/") in excludes)
        // Кроме копий — только снимки посетителей и фразы голосом владельца (домофон): остальные файлы — в backup.
        val allowed = setOf("backups/", "doorbell_rings/", "doorbell_phrases/", "gate_log/")
        assertTrue("других файлов приложения исключение не касается", excludes.none { it.first == "file" && it.second !in allowed })
    }
    @Test fun legacyBackupExcludesOnlySensitivePrefsAndKeepsTiles() {
        assertPrefsRules(xml("res/xml/backup_rules.xml"))
    }
    @Test fun cloudBackupExcludesSensitivePrefsAndKeepsTiles() {
        val sections = xml("res/xml/data_extraction_rules.xml").getElementsByTagName("cloud-backup")
        assertEquals(1, sections.length)
        assertPrefsRules(sections.item(0) as Element)
    }
    @Test fun deviceTransferExcludesSensitivePrefsAndKeepsTiles() {
        val sections = xml("res/xml/data_extraction_rules.xml").getElementsByTagName("device-transfer")
        assertEquals(1, sections.length)
        assertPrefsRules(sections.item(0) as Element)
    }
    /** Запись с камер: «записывать на этом планшете» не переезжает на другой планшет, видео не уходит в облако. */
    @Test fun recordingDeviceSettingsAndVideoAreNotBackedUp() {
        val legacy = xml("res/xml/backup_rules.xml")
        val rules = xml("res/xml/data_extraction_rules.xml")
        val roots = listOf(legacy, rules.getElementsByTagName("cloud-backup").item(0) as Element,
            rules.getElementsByTagName("device-transfer").item(0) as Element)
        for (root in roots) {
            assertFalse(rulesPermit(root, "recording_device.xml"))
            assertFalse("Секрет вебхука и флаг прямых событий — только этого планшета", rulesPermit(root, "camera_events.xml"))
            assertTrue("Плитки (и настройки записи камеры в них) остаются в backup", rulesPermit(root, "tiles_prefs.xml"))
            val excludes = root.getElementsByTagName("exclude").let { nodes ->
                (0 until nodes.length).map { nodes.item(it) as Element }.map { it.getAttribute("domain") to it.getAttribute("path") }
            }
            assertTrue(("external" to "records/") in excludes)
            assertTrue("Индекс роликов — про файлы этого планшета", ("database" to "recording_index.db") in excludes)
            assertTrue("Снимки посетителей — не в облако", ("file" to "doorbell_rings/") in excludes)
            assertTrue("Снимки журнала открытий — не в облако", ("file" to "gate_log/") in excludes)
            assertTrue("Голос владельца — не в облако", ("file" to "doorbell_phrases/") in excludes)
            assertFalse("Уведомление и автоответ — только этого планшета", rulesPermit(root, "doorbell_away.xml"))
            assertFalse("Пароли камер — не в облако", rulesPermit(root, "camera_secrets.xml"))
            assertFalse("Отпечатки сертификатов камер — только этого планшета", rulesPermit(root, "camera_tls.xml"))
            assertFalse("Проверка планшета — только этого планшета", rulesPermit(root, "tablet_watch.xml"))
        }
    }

    @Test fun manifestUsesBothRulesWithoutDisablingBackup() {
        val app = xml("AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals("true", app.getAttributeNS(ns, "allowBackup"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(ns, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(ns, "dataExtractionRules"))
    }
}
