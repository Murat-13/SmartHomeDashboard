package com.example.smarthomedashboard

import android.app.AlertDialog
import android.os.Looper
import android.widget.Button
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
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

/** «Восстановить из копии» в «Устройстве»: список автокопий, подтверждение, тот же путь импорта и перезапуск. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-w1280dp-h800dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class RestoreFromCopyTest {
    private val context = RuntimeEnvironment.getApplication()
    private val tilesPrefs = context.getSharedPreferences("tiles_prefs", 0)
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)

    @Before fun clean() {
        tilesPrefs.edit().clear().commit()
        prefs.edit().clear().putString("ha_token", "FAKE-SECRET").commit()
        File(context.filesDir, "backups").deleteRecursively()
    }

    private fun settings() = Robolectric.buildActivity(SettingsActivity::class.java,
        android.content.Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_DEVICE))
        .create().get().also { allowSettingsTestAccess(it); idle() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Чтение копий идёт в фоне: ждём, пока появится новый диалог. */
    private fun awaitDialog(previous: AlertDialog?): AlertDialog {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            val latest = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog?
            if (latest != null && latest !== previous && latest.isShowing) return latest
            Thread.sleep(20)
        }
        fail("диалог не появился")
        throw AssertionError()
    }

    @Test fun deviceSectionOpensFromExtraAndRestoresChosenCopyAfterConfirmation() {
        TileManager(context).saveTiles(listOf(TileEntity("good", "clock", "grid", "Часы", 0, 0, 1, 1)))
        assertEquals(AutoBackup.Outcome.CREATED, AutoBackup.run(context, now = 1_800_000_000_000L))
        val goodJson = tilesPrefs.getString("tiles", null)
        tilesPrefs.edit().putString("tiles", "[{\"id\":").commit()

        val activity = settings()
        assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.sectionDevice).visibility)
        activity.findViewById<Button>(R.id.btnBackupRestore).performClick()
        val list = awaitDialog(null)
        assertEquals(1, list.listView.adapter.count)
        assertTrue((list.listView.adapter.getItem(0) as CharSequence).startsWith("Автокопия · "))
        shadowOf(list).clickOnItem(0)
        val confirm = awaitDialog(list)
        assertEquals("ничего не записано до подтверждения", "[{\"id\":", tilesPrefs.getString("tiles", null))
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val deadline = System.currentTimeMillis() + 10_000
        while (!activity.isFinishing && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(20) }
        assertEquals(goodJson, tilesPrefs.getString("tiles", null))
        assertTrue("панель перезапускается", activity.isFinishing)
        assertEquals("токен не тронут", "FAKE-SECRET", prefs.getString("ha_token", null))
    }

    @Test fun withoutCopiesOffersFileImport() {
        val activity = settings()
        activity.findViewById<Button>(R.id.btnBackupRestore).performClick()
        val dialog = awaitDialog(null)
        assertEquals("Копий нет", shadowOf(dialog).title)
        assertEquals("Импорт из файла", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
    }
}
