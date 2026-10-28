package com.example.smarthomedashboard

import android.os.Looper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.savedstate.SavedStateRegistryController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

// Настоящие onStart/onStop и StateFlow; XML/onCreate и сетевое подключение не запускаются.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HaStatusLifecycleTest {
    private fun screen(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        androidx.activity.ComponentActivity::class.java.declaredFields.single {
            it.type == SavedStateRegistryController::class.java
        }.apply {
            isAccessible = true
            (get(activity) as SavedStateRegistryController).performRestore(null)
        }
        androidx.fragment.app.FragmentActivity::class.java.declaredFields.single {
            it.type == androidx.fragment.app.FragmentController::class.java
        }.apply {
            isAccessible = true
            (get(activity) as androidx.fragment.app.FragmentController).attachHost(null)
        }
        (activity.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        activity.window.setContentView(FrameLayout(activity).apply {
            addView(TextView(activity).apply { id = R.id.tvHaLiveStatus })
        })
        ArchitectureTestAccess.setField(activity.delegate, "mSubDecorInstalled", true)
        ArchitectureTestAccess.setField(activity.delegate, "mWindow", activity.window)
        return activity
    }

    private fun lifecycle(activity: MainActivity, method: String) {
        MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun text(activity: MainActivity) =
        activity.findViewById<TextView>(R.id.tvHaLiveStatus).text.toString()

    private fun client(activity: MainActivity): HomeAssistantWebSocket? =
        MainActivity::class.java.getDeclaredMethod("getWebSocket").apply {
            isAccessible = true
        }.invoke(activity) as HomeAssistantWebSocket?

    private fun artificialClient() = HomeAssistantWebSocket(
        "http://localhost/test-only", "ARTIFICIAL-TOKEN", { _, _, _ -> }, {}, {}
    )

    @Test fun screenConnectionAlwaysFollowsOwnerReplacementAndDisconnection() {
        val activity = screen()
        val owner = ViewModelProvider(activity)[MainViewModel::class.java]
        assertNull(client(activity))
        val first = artificialClient()
        ArchitectureTestAccess.setField(owner, "webSocket", first)
        assertSame(first, client(activity))
        val second = artificialClient()
        ArchitectureTestAccess.setField(owner, "webSocket", second)
        assertSame(second, client(activity))
        owner.disconnectWebSocket()
        assertNull(client(activity))
    }

    @Test fun newScreenSeesRetainedClientBeforeAsynchronousTokenRead() {
        val old = screen()
        val owner = ViewModelProvider(old)[MainViewModel::class.java]
        val existing = artificialClient()
        ArchitectureTestAccess.setField(owner, "webSocket", existing)
        val next = screen()
        ArchitectureTestAccess.setField(next, "viewModel\$delegate", lazy { owner })
        assertSame(existing, client(next))
        assertSame(existing, client(old))
        owner.disconnectWebSocket()
        assertNull(client(next))
        assertNull(client(old))
    }

    @Test fun stoppedScreenDoesNotRenderButRestartShowsLatestStatus() {
        val activity = screen()
        val owner = ViewModelProvider(activity)[MainViewModel::class.java]
        lifecycle(activity, "onStart")
        owner.publishStatus("Чтение токена HA...")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Чтение токена HA...", text(activity))
        owner.publishStatus("Неверный токен HA")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Неверный токен HA", text(activity))
        lifecycle(activity, "onStop")
        owner.publishStatus("Повтор через 10 минут")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Неверный токен HA", text(activity))
        lifecycle(activity, "onStart")
        assertEquals("Повтор через 10 минут", text(activity))
        assertEquals("Повтор через 10 минут",
            activity.getSharedPreferences("dashboard_prefs", 0).getString("ha_connection_status", null))
        lifecycle(activity, "onStop")
    }

    @Test fun newScreenReadsRetainedStatusAndOldScreenStaysDetached() {
        val old = screen()
        val owner = ViewModelProvider(old)[MainViewModel::class.java]
        lifecycle(old, "onStart")
        owner.publishStatus("Ожидание")
        shadowOf(Looper.getMainLooper()).idle()
        lifecycle(old, "onStop")
        val next = screen()
        // Общий ViewModel имитирует сохранённого владельца при recreation.
        ArchitectureTestAccess.setField(next, "viewModel\$delegate", lazy { owner })
        owner.publishStatus("Повторное подключение")
        lifecycle(next, "onStart")
        assertEquals("Повторное подключение", text(next))
        assertEquals("Ожидание", text(old))
        lifecycle(next, "onStop")
    }
}
