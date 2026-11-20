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
    }

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

    /** Провайдер документов: новые файлы появляются в списке папки только после «индексации» (никогда в тесте). */
    class LaggingDocs : DocumentsProvider() {
        companion object {
            lateinit var dir: File
            val unlisted = mutableSetOf<String>()
        }

        override fun onCreate() = true

        private fun row(c: MatrixCursor, id: String) {
            val f = if (id == "root") dir else File(dir, id)
            c.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, if (id == "root") "root" else f.name)
                .add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
                .add(Document.COLUMN_SIZE, f.length()).add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE or Document.FLAG_DIR_SUPPORTS_CREATE)
        }

        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf("root_id"))
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
            MatrixCursor(projection ?: DEFAULT).also { row(it, documentId) }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(projection ?: DEFAULT).also { c -> dir.list()!!.filter { it !in unlisted }.forEach { row(c, it) } }
        override fun isChildDocument(parentDocumentId: String, documentId: String) = true
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            File(dir, displayName).createNewFile()
            unlisted += displayName
            return displayName
        }
        override fun deleteDocument(documentId: String) { File(dir, documentId).delete() }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            ParcelFileDescriptor.open(File(dir, documentId), ParcelFileDescriptor.parseMode(mode))
    }

    private companion object {
        const val AUTHORITY = "test.lagging.docs"
        val DEFAULT = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_FLAGS)
    }
}
