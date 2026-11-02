package com.example.smarthomedashboard

import android.app.AlertDialog
import android.view.KeyEvent
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

/** Истечение админ-сессии на открытом экране: правки не теряются молча, «Назад» не обходит PIN. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PinExpiryTest {
    @Before fun reset() = PinSecurityUi.session.revoke()

    private fun activity(): IsolatedPinGateActivity {
        val activity = Robolectric.buildActivity(IsolatedPinGateActivity::class.java).get()
        weatherLive(activity)
        activity.setTheme(R.style.Theme_SmartHomeDashboard)
        activity.window.setContentView(FrameLayout(activity))
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putInt("pin_session_minutes", 5).commit()
        val access = PinAccess(object : PinAccess.Storage {
            override fun read() = PinAccess.State("test-record-unused-by-status", null)
            override fun write(state: PinAccess.State) = true
        }, object : PinAccess.Legacy { override fun read(): String? = null; override fun remove() = true },
            clock = object : PinAccess.Clock { override fun elapsed() = 0L; override fun boot() = "test" }, worker = {})
        ReflectionHelpers.setField(activity, "pinGateAccess", access)
        return activity
    }
    private fun gate(a: IsolatedPinGateActivity) = ReflectionHelpers.callInstanceMethod<Unit>(a, "requestPinAccess")
    private fun enter(a: IsolatedPinGateActivity) {
        PinSecurityUi.session.grant()
        gate(a)
        weatherAwait { ReflectionHelpers.getField<Boolean>(a, "pinAuthorized") }
    }
    private fun back(a: IsolatedPinGateActivity) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
    }
    private fun expired(): AlertDialog? = (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog?)
        ?.takeIf { it.isShowing && ShadowAlertDialog.getLatestAlertDialog().let { d -> org.robolectric.Shadows.shadowOf(d).title } == "Сессия администратора истекла" }

    @Test fun saveAfterExpiryIsRefusedAndOffersPinOrLeave() {
        val a = activity()
        enter(a)
        assertTrue(a.requireAdmin())
        PinSecurityUi.session.revoke()
        assertFalse(a.requireAdmin())
        val dialog = expired()!!
        assertFalse(a.isFinishing)
        assertEquals("Ввести PIN", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        weatherAwait { a.isFinishing }
    }

    @Test fun backAfterExpiryAsksInsteadOfOpeningScreenDialogs() {
        val a = activity()
        enter(a)
        PinSecurityUi.session.revoke()
        back(a)
        assertNotNull(expired())
        assertFalse(a.isFinishing)
    }

    @Test fun backBeforeFirstEntryJustLeaves() {
        val a = activity()
        back(a)
        assertTrue(a.isFinishing)
    }

    @Test fun cancellingPinAfterExpiryKeepsScreenOpen() {
        val a = activity()
        enter(a)
        PinSecurityUi.session.revoke()
        gate(a)
        weatherAwait { ShadowDialog.getLatestDialog() is PinDialog }
        ShadowDialog.getLatestDialog().cancel()
        weatherAwait { expired() != null }
        assertFalse(a.isFinishing)
    }

    @Test fun sessionLengthIsClampedToOwnerRange() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("dashboard_prefs", 0)
        prefs.edit().putInt("pin_session_minutes", 0).commit()
        assertEquals(1, PinSecurityUi.sessionMinutes(context))
        prefs.edit().putInt("pin_session_minutes", 5000).commit()
        assertEquals(1000, PinSecurityUi.sessionMinutes(context))
        prefs.edit().putInt("pin_session_minutes", 1).commit()
        assertEquals(1, PinSecurityUi.sessionMinutes(context))
    }
}
