package com.example.smarthomedashboard

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Папка копий (SAF), у которой список файлов отстаёт от записи — как ночью 20.11.2026 на SM-X216B: копия
 * записалась, поиск по имени её не нашёл, статус «ошибка записи», лишний файл остался. Копия проверяется и
 * убирается по адресу, который вернул createDocument.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BackupFolderLagTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")

    @Before fun setUp() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("tiles_prefs", 0).edit().clear().commit()
        TileManager(context).saveTiles(listOf(TileEntity("t1", "sensor", "grid", "Т1", 1, 1, 1, 1)))
        val info = ProviderInfo().apply {
            authority = AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        }
        Robolectric.buildContentProvider(LaggingDocs::class.java).create(info)
        LaggingDocs.dir = File(context.cacheDir, "lagging-docs").apply { deleteRecursively(); mkdirs() }
        LaggingDocs.unlisted.clear()
        LaggingDocs.blind = false
    }

    private fun autoCopies() = LaggingDocs.dir.list()!!.filter { it.startsWith(AutoBackup.PREFIX) }.sorted()

    @Test fun freshCopyIsVerifiedEvenWhenFolderListingLags() {
        val store = AutoBackup.FolderStore(context, tree)
        assertEquals(AutoBackup.Outcome.CREATED, AutoBackup.run(context, store, 1_800_000_000_000L))
        assertEquals("ok", prefs.getString(AutoBackup.PREF_LAST_RESULT, null))
        assertEquals(1, LaggingDocs.dir.list()!!.size)
    }

    @Test fun failedCopyIsRemovedEvenWhenFolderListingLags() {
        val store = AutoBackup.FolderStore(context, tree)
        val name = AutoBackup.PREFIX + "20261120_030000.json"
        assertTrue(store.write(name, "{}"))
        assertTrue("лишний файл не остаётся в папке", store.delete(name))
        assertEquals(0, LaggingDocs.dir.list()!!.size)
    }

    /**
     * Ночь 21.11.2026 на SM-X216B: новая копия записалась и проверилась, но список папки её ещё не показывал — лишняя
     * старая копия осталась (4 вместо 3). Свежая копия учитывается и без списка.
     */
    @Test fun oldCopiesAreTrimmedEvenWhenFolderListingLags() {
        val old = listOf("20261105", "20261106", "20261107").map { AutoBackup.PREFIX + it + "_030000.json" }
        old.forEach { File(LaggingDocs.dir, it).writeText("{}") }
        File(LaggingDocs.dir, "smarthome_config.json").writeText("{}")   // ручная копия владельца — не наша
        val store = AutoBackup.FolderStore(context, tree)
        assertEquals(AutoBackup.Outcome.CREATED, AutoBackup.run(context, store, 1_800_000_000_000L))
        val left = autoCopies()
        assertEquals("остаются ${AutoBackup.KEEP} последние копии", AutoBackup.KEEP, left.size)
        assertEquals(old.drop(1), left.take(2))
        assertTrue("ручная копия не тронута", File(LaggingDocs.dir, "smarthome_config.json").exists())
    }

    /** Накопившиеся лишние копии убираются и той ночью, когда новая копия не нужна (настройки не менялись). */
    @Test fun unchangedNightStillTrimsLeftovers() {
        val store = AutoBackup.FolderStore(context, tree)
        assertEquals(AutoBackup.Outcome.CREATED, AutoBackup.run(context, store, 1_800_000_000_000L))
        LaggingDocs.unlisted.clear()
        val old = listOf("20200101", "20200102", "20200103", "20200104").map { AutoBackup.PREFIX + it + "_030000.json" }
        old.forEach { File(LaggingDocs.dir, it).writeText("{}") }
        assertEquals(AutoBackup.Outcome.UNCHANGED, AutoBackup.run(context, AutoBackup.FolderStore(context, tree), 1_800_086_400_000L))
        val left = autoCopies()
        assertEquals(AutoBackup.KEEP, left.size)
        assertEquals(old.drop(2), left.take(2))
    }

    /**
     * Ночью список папки пуст (так повела себя папка на SM-X216B: по списку не удалилось ничего). Приложение помнит
     * свои копии с прошлой уборки и удаляет лишнюю по прямому адресу; без изменений новая копия не создаётся.
     */
    @Test fun nightWithEmptyFolderListingTrimsByMemory() {
        listOf("20261105", "20261106", "20261107").forEach { File(LaggingDocs.dir, AutoBackup.PREFIX + it + "_030000.json").writeText("{}") }
        assertEquals(AutoBackup.Outcome.CREATED, AutoBackup.run(context, AutoBackup.FolderStore(context, tree), 1_800_000_000_000L))
        val day = autoCopies()
        assertEquals(AutoBackup.KEEP, day.size)

        LaggingDocs.blind = true
        assertEquals("ничего не менялось — копия не нужна, хотя список пуст",
            AutoBackup.Outcome.UNCHANGED, AutoBackup.run(context, AutoBackup.FolderStore(context, tree), 1_800_086_400_000L))
        assertEquals(day, autoCopies())

        TileManager(context).saveTiles(listOf(TileEntity("t2", "sensor", "grid", "Т2", 1, 1, 1, 1)))
        assertEquals(AutoBackup.Outcome.CREATED, AutoBackup.run(context, AutoBackup.FolderStore(context, tree), 1_800_172_800_000L))
        val night = autoCopies()
        assertEquals("лишняя копия удалена без списка папки", AutoBackup.KEEP, night.size)
        assertEquals(day.drop(1), night.take(2))
    }

    /** Копию убрал сам владелец: приложение не «помнит» её вечно и не пытается удалять каждую ночь. */
    @Test fun copyRemovedByOwnerIsForgotten() {
        assertEquals(AutoBackup.Outcome.CREATED, AutoBackup.run(context, AutoBackup.FolderStore(context, tree), 1_800_000_000_000L))
        LaggingDocs.unlisted.clear()
        val first = autoCopies().single()
        // В памяти — копия, которой уже нет, и ещё три настоящие в папке.
        prefs.edit().putString(AutoBackup.PREF_KEPT, "$tree\n${AutoBackup.PREFIX}20200101_030000.json\n$first").commit()
        listOf("20270201", "20270202", "20270203").forEach { File(LaggingDocs.dir, AutoBackup.PREFIX + it + "_030000.json").writeText("{}") }
        assertEquals(AutoBackup.Outcome.UNCHANGED, AutoBackup.run(context, AutoBackup.FolderStore(context, tree), 1_800_086_400_000L))
        assertEquals(AutoBackup.KEEP, autoCopies().size)
        val kept = prefs.getString(AutoBackup.PREF_KEPT, "")!!.split('\n').drop(1)
        assertEquals("в памяти — только то, что лежит в папке", autoCopies(), kept)
    }

    /** Провайдер документов: новые файлы появляются в списке папки только после «индексации» (никогда в тесте). */
    class LaggingDocs : DocumentsProvider() {
        companion object {
            lateinit var dir: File
            val unlisted = mutableSetOf<String>()
            /** Список папки пуст, хотя файлы на месте. */
            @Volatile var blind = false
        }

        /** Файл по адресу документа: имя или, как у хранилища Android, «папка/имя». */
        private fun file(id: String) = File(dir, id.removePrefix("root/"))

        override fun onCreate() = true

        private fun row(c: MatrixCursor, id: String) {
            val f = if (id == "root") dir else file(id)
            c.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, if (id == "root") "root" else f.name)
                .add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
                .add(Document.COLUMN_SIZE, f.length()).add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE or Document.FLAG_DIR_SUPPORTS_CREATE)
        }

        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf("root_id"))
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
            MatrixCursor(projection ?: DEFAULT).also { row(it, documentId) }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(projection ?: DEFAULT).also { c -> if (!blind) dir.list()!!.filter { it !in unlisted }.forEach { row(c, it) } }
        override fun isChildDocument(parentDocumentId: String, documentId: String) = true
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            File(dir, displayName).createNewFile()
            unlisted += displayName
            return displayName
        }
        override fun deleteDocument(documentId: String) {
            if (!file(documentId).delete()) throw java.io.FileNotFoundException(documentId)
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    private companion object {
        const val AUTHORITY = "test.lagging.docs"
        val DEFAULT = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_FLAGS)
    }
}
