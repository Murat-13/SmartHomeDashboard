package com.example.smarthomedashboard

import android.os.Looper
import android.util.SparseArray
import android.widget.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.example.smarthomedashboard.data.TileManager
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Настоящие методы потребителей и WebSocket auth по loopback; JCA использует искусственный тестовый ключ.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HaTokenConsumerIntegrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private val access by lazy { haTokenTestAccess(context) }
    private fun set(target: Any, name: String, value: Any?) = ArchitectureTestAccess.setField(target, name, value)
    private fun invoke(target: Any, name: String, vararg args: Any?) = try {
        ArchitectureTestAccess.methods(target).single { it.name == name }.apply { isAccessible = true }.invoke(target, *args)
    } catch (e: InvocationTargetException) { throw e.targetException }
    private fun live(activity: androidx.appcompat.app.AppCompatActivity) {
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        androidx.activity.ComponentActivity::class.java.declaredFields.single {
            it.type == androidx.savedstate.SavedStateRegistryController::class.java
        }.apply {
            isAccessible = true
            (get(activity) as androidx.savedstate.SavedStateRegistryController).performRestore(null)
        }
        androidx.fragment.app.FragmentActivity::class.java.declaredFields.single {
            it.type == androidx.fragment.app.FragmentController::class.java
        }.apply {
            isAccessible = true
            (get(activity) as androidx.fragment.app.FragmentController).attachHost(null)
        }
        (activity.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        set(activity, "haTokens", access)
    }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("Async condition timed out", condition())
    }
    private fun read() = Executors.newSingleThreadExecutor().let { pool ->
        try { pool.submit<HaTokenAccess.Result> { access.read() }.get(5, TimeUnit.SECONDS) }
        finally { pool.shutdownNow() }
    }
    private fun seed(value: String = "FAKE-LEGACY") { prefs.edit().putString("ha_token", value).commit() }

    /** Проверка и сохранение мастера и «Войти заново» — общее [HaConnector]. */
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
    }
    private class Setup(val connector: HaConnector, val owner: Owner) {
        var done = false
        var error: String? = null
        val statuses = mutableListOf<String>()
        fun start(address: String, token: String = "") = connector.start(HaConnector.Target("", address), token,
            { statuses += it }) { error = it; done = true }
    }
    private fun setup(tokens: () -> HaTokenAccess = { access }) = Owner().let { Setup(HaConnector(context, it, tokens), it) }
    private fun settings(): SettingsActivity = Robolectric.buildActivity(SettingsActivity::class.java).get().also { activity ->
        live(activity)
        listOf("etHaLocalHost", "etHaRemoteHost", "etHaToken",
            "etAdminPin", "etPinSessionMinutes", "etScreenTimeout",
            "etYandexApiKey", "etYandexLat", "etYandexLon").forEach { set(activity, it, EditText(activity)) }
        set(activity, "tvHaTokenStatus", TextView(activity))
        set(activity, "btnSave", Button(activity))
        set(activity, "rgTempUnit", RadioGroup(activity))
        set(activity, "cbKioskMode", CheckBox(activity))
        set(activity, "cbUseYandexWeather", CheckBox(activity))
        invoke(activity, "prepareTokenInput")
        allowSettingsTestAccess(activity)
        attachWeatherSettingsFields(activity)
    }
    private fun <T> field(target: Any, name: String): T =
        if (target is MainActivity && name == "webSocket") {
            ArchitectureTestAccess.getField(androidx.lifecycle.ViewModelProvider(target)[MainViewModel::class.java], name)
        } else ArchitectureTestAccess.getField(target, name)

    private class Peer(private val accept: Boolean = true) : WebSocketListener() {
        val messages = CopyOnWriteArrayList<JSONObject>()
        lateinit var socket: WebSocket
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required"}""")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JSONObject(text)
            messages.add(json)
            when (json.optString("type")) {
                "auth" -> webSocket.send(if (accept) """{"type":"auth_ok"}""" else """{"type":"auth_invalid"}""")
                else -> if (json.has("id")) webSocket.send(JSONObject().put("type", "result")
                    .put("id", json.getInt("id")).put("success", true).put("result", org.json.JSONArray()).toString())
            }
        }
    }

    @Test fun setupAuthOkMigratesWithoutOpenSaveAndDoesNotPrefill() {
        seed()
        MockWebServer().use { server ->
            val peer = Peer()
            server.enqueue(MockResponse().withWebSocketUpgrade(peer))
            server.start()
            val setup = setup()
            setup.start(server.url("/").toString())
            await { setup.done }
            assertNull(setup.error)
            assertEquals("FAKE-LEGACY", peer.messages.single { it.optString("type") == "auth" }.getString("access_token"))
            assertEquals("FAKE-LEGACY", read().value)
            assertFalse(prefs.contains("ha_token"))
            assertTrue(prefs.getBoolean("setup_completed", false))
            assertEquals(server.url("/").toString(), prefs.getString("ha_remote_host", null))
            assertTrue(setup.statuses.none { it.contains("FAKE-") })
        }
    }
    @Test fun setupRejectedNewTokenDoesNotPersistItOrMarkSetupComplete() {
        MockWebServer().use { server ->
            val peer = Peer(false)
            server.enqueue(MockResponse().withWebSocketUpgrade(peer))
            server.start()
            val setup = setup()
            setup.start(server.url("/").toString(), "FAKE-REJECTED")
            await { setup.done }
            assertTrue(peer.messages.any { it.optString("type") == "auth" })
            assertTrue(setup.error!!, setup.error!!.contains("токен"))
            assertEquals(HaTokenAccess.Status.ABSENT, read().status)
            assertFalse(prefs.contains("ha_token"))
            assertFalse(prefs.getBoolean("setup_completed", false))
            assertFalse(prefs.contains("ha_remote_host"))
        }
    }
    @Test fun settingsBlankSavePreservesSecureTokenAndExplicitInputReplacesWithoutPlaintext() {
        seed()
        read()
        val activity = settings()
        field<EditText>(activity, "etHaLocalHost").setText("http://ha.example.invalid")
        invoke(activity, "saveCurrentSettings")
        await { activity.isFinishing }
        assertEquals("FAKE-LEGACY", read().value)
        assertFalse(prefs.contains("ha_token"))
        val replacement = settings()
        field<EditText>(replacement, "etHaToken").setText("FAKE-REPLACEMENT")
        invoke(replacement, "saveCurrentSettings")
        await { replacement.isFinishing }
        assertEquals("FAKE-REPLACEMENT", read().value)
        assertFalse(prefs.contains("ha_token"))
        assertEquals("", field<EditText>(replacement, "etHaToken").text.toString())
    }
    @Test fun cancelingSettingsInputLeavesSavedTokenUnchanged() {
        seed()
        read()
        val activity = settings()
        field<EditText>(activity, "etHaToken").setText("FAKE-CANCELED")
        activity.finish()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("FAKE-LEGACY", read().value)
        assertFalse(prefs.contains("ha_token"))
    }
    @Test fun secretInputsDoNotSaveViewStateAndStatusNeverContainsToken() {
        seed()
        for (activity in listOf(settings())) {
            val input = field<EditText>(activity, "etHaToken")
            input.id = R.id.etHaToken
            input.setText("FAKE-UNSAVED-INPUT")
            val state = SparseArray<android.os.Parcelable>()
            input.saveHierarchyState(state)
            assertEquals(0, state.size())
            input.text.clear()
            invoke(activity, "loadHaTokenStatus")
            await { field<TextView>(activity, "tvHaTokenStatus").text == "Токен HA сохранён" }
            assertEquals("", input.text.toString())
            assertFalse(field<TextView>(activity, "tvHaTokenStatus").text.contains("FAKE-"))
        }
    }
    @Test fun mainReadsSecureTokenOnWorkerAndKeepsProtocol() {
        seed()
        read()
        MockWebServer().use { server ->
            val peer = Peer()
            server.enqueue(MockResponse().withWebSocketUpgrade(peer))
            server.start()
            prefs.edit().putString("ha_remote_host", server.url("/").toString()).commit()
            val activity = Robolectric.buildActivity(MainActivity::class.java).get()
            live(activity)
            val manager = TileManager(activity)
            manager.saveTiles(emptyList())
            set(activity, "tileManager", manager)
            set(activity, "bottomPanel", FrameLayout(activity))
            val root = FrameLayout(activity).apply { addView(TextView(activity).apply { id = R.id.tvHaLiveStatus }) }
            activity.window.setContentView(root)
            ArchitectureTestAccess.setField(activity.delegate, "mSubDecorInstalled", true)
            ArchitectureTestAccess.setField(activity.delegate, "mWindow", activity.window)
            invoke(activity, "setupWebSocket")
            await { peer.messages.any { it.optString("type") == "get_states" } }
            assertEquals("FAKE-LEGACY", peer.messages.single { it.optString("type") == "auth" }.getString("access_token"))
            assertFalse(prefs.contains("ha_token"))
            field<HomeAssistantWebSocket?>(activity, "webSocket")?.disconnect()
        }
    }
    @Test fun pickerReadsSecureTokenWithoutLegacyAndKeepsEntityRequest() {
        seed()
        read()
        MockWebServer().use { server ->
            val peer = Peer()
            server.enqueue(MockResponse().withWebSocketUpgrade(peer))
            server.start()
            prefs.edit().putString("ha_remote_host", server.url("/").toString()).commit()
            val activity = Robolectric.buildActivity(SensorPickerActivity::class.java).get()
            live(activity)
            set(activity, "tvStatus", TextView(activity))
            set(activity, "etSearch", EditText(activity))
            set(activity, "rvSensors", androidx.recyclerview.widget.RecyclerView(activity))
            invoke(activity, "setupRecyclerView")
            invoke(activity, "fetchEntitiesFromHA")
            await { peer.messages.any { it.optString("type") == "config/entity_registry/list" } }
            assertEquals("FAKE-LEGACY", peer.messages.single { it.optString("type") == "auth" }.getString("access_token"))
            assertFalse(prefs.contains("ha_token"))
            field<HomeAssistantWebSocket?>(activity, "webSocket")?.disconnect()
        }
    }
    @Test fun finishedScreenIgnoresDelayedTokenResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = HaTokenAccess(object : HaTokenSlot {
            override fun read(): SecretStore.ReadResult {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return SecretStore.ReadResult(SecretStore.Status.SUCCESS, "FAKE-DELAYED")
            }
            override fun write(value: String) = error("Unexpected write")
        }, object : LegacyHaToken {
            override fun read(): String? = null
            override fun phase(): String? = null
            override fun mark(phase: String?) = true
            override fun removeIfUnchanged(expected: String?) = true
        }) {}
        val setup = setup { slow }
        setup.start("http://ha.example.invalid")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        setup.connector.cancel()
        release.countDown()
        await { setup.owner.lifecycleScope.coroutineContext[kotlinx.coroutines.Job]!!.children.none() }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("отменённая проверка не сообщает итог", setup.done)
        assertFalse(prefs.getBoolean("setup_completed", false))
    }


    @Test fun setupAcceptsNewTokenOnlyAfterAuthOkWithoutPlaintextPreference() {
        MockWebServer().use { server ->
            val peer = Peer()
            server.enqueue(MockResponse().withWebSocketUpgrade(peer))
            server.start()
            val setup = setup()
            setup.start(server.url("/").toString(), "FAKE-SETUP-NEW")
            await { setup.done }
            assertNull(setup.error)
            assertEquals("FAKE-SETUP-NEW", read().value)
            assertFalse(prefs.contains("ha_token"))
            assertTrue(prefs.getBoolean("setup_completed", false))
        }
    }

    @Test fun migrationAndReplacementPreserveTilesAndEveryUnrelatedPreference() {
        seed()
        prefs.edit().putString("mqtt_password", "FAKE-MQTT-UNRELATED")
            .putString("yandex_api_key", "FAKE-WEATHER-UNRELATED")
            .putString("admin_pin", "FAKE-PIN-UNRELATED")
            .putString("unknown_future_parameter", "kept")
            .putString("ha_local_host", "http://ha.example.invalid").commit()
        val tiles = context.getSharedPreferences("tiles_prefs", 0)
        tiles.edit().putString("tiles", """[{"id":"existing","title":"Existing"}]""").commit()
        val unrelated = prefs.all.filterKeys { it != "ha_token" }
        val originalTiles = tiles.all
        read()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<HaTokenAccess.Result> { access.replace("FAKE-NEW") }.get(5, TimeUnit.SECONDS)
            assertEquals(HaTokenAccess.Status.READY, result.status)
        } finally { pool.shutdownNow() }
        assertEquals(unrelated, prefs.all)
        assertEquals(originalTiles, tiles.all)
        assertFalse(prefs.contains("ha_token"))
    }

    private fun main(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).get().also { activity ->
        live(activity)
        set(activity, "tileManager", TileManager(activity).apply { saveTiles(emptyList()) })
        set(activity, "bottomPanel", FrameLayout(activity))
        activity.window.setContentView(FrameLayout(activity).apply {
            addView(TextView(activity).apply { id = R.id.tvHaLiveStatus })
        })
        ArchitectureTestAccess.setField(activity.delegate, "mSubDecorInstalled", true)
        ArchitectureTestAccess.setField(activity.delegate, "mWindow", activity.window)
    }
    private fun unavailable(): HaTokenAccess = HaTokenAccess(object : HaTokenSlot {
        override fun read() = SecretStore.ReadResult(SecretStore.Status.KEY_UNAVAILABLE)
        override fun write(value: String): SecretStore.Status = error("No recovery write is allowed")
    }, PreferenceLegacyHaToken { prefs }) {
        check(Looper.myLooper() != Looper.getMainLooper())
    }

    @Test fun unavailableSecureStoreDoesNotDowngradeOrApplyConnectionSettingsInAnyConsumer() {
        seed()
        prefs.edit().putString("ha_local_host", "http://previous.example.invalid").commit()
        val setup = setup { unavailable() }
        setup.start("http://new.example.invalid", "FAKE-NEW")
        await { setup.done }
        assertNotNull(setup.error)
        assertFalse("подключения не было", setup.connector.busy)
        assertEquals("http://previous.example.invalid", prefs.getString("ha_local_host", null))

        val settings = settings()
        set(settings, "haTokens", unavailable())
        field<EditText>(settings, "etHaLocalHost").setText("http://new.example.invalid")
        field<EditText>(settings, "etHaToken").setText("FAKE-NEW")
        invoke(settings, "saveCurrentSettings")
        await { field<Button>(settings, "btnSave").isEnabled }
        assertFalse(settings.isFinishing)
        assertEquals("http://previous.example.invalid", prefs.getString("ha_local_host", null))

        val main = main()
        set(main, "haTokens", unavailable())
        invoke(main, "setupWebSocket")
        await { main.lifecycleScope.coroutineContext[kotlinx.coroutines.Job]!!.children.none() }
        assertNull(field<HomeAssistantWebSocket?>(main, "webSocket"))
        assertFalse(main.isFinishing)
        assertNull(shadowOf(main).nextStartedActivity)

        val picker = Robolectric.buildActivity(SensorPickerActivity::class.java).get()
        live(picker)
        set(picker, "tvStatus", TextView(picker))
        set(picker, "haTokens", unavailable())
        invoke(picker, "fetchEntitiesFromHA")
        await { picker.lifecycleScope.coroutineContext[kotlinx.coroutines.Job]!!.children.none() }
        assertNull(field<HomeAssistantWebSocket?>(picker, "webSocket"))
        assertEquals("FAKE-LEGACY", prefs.getString("ha_token", null))
        assertTrue(setup.error!!.contains("недоступен"))
        assertTrue((setup.statuses + setup.error!!).none { it.contains("FAKE-") })
        for (activity in listOf(settings, picker)) {
            val name = if (activity is SettingsActivity) "tvHaTokenStatus" else "tvStatus"
            val status = field<TextView>(activity, name).text.toString()
            assertTrue(status.contains("недоступен"))
            assertFalse(status.contains("FAKE-"))
        }
    }

    @Test fun loadingAndChangedAddressesCannotStartObsoleteMainOrPickerConnection() {
        for (isMain in listOf(true, false)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val delayed = HaTokenAccess(object : HaTokenSlot {
                override fun read(): SecretStore.ReadResult {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return SecretStore.ReadResult(SecretStore.Status.SUCCESS, "FAKE-DELAYED")
                }
                override fun write(value: String): SecretStore.Status = error("Unexpected write")
            }, PreferenceLegacyHaToken { prefs }) {
                check(Looper.myLooper() != Looper.getMainLooper())
            }
            prefs.edit().putString("ha_local_host", "http://old.example.invalid").commit()
            val activity = if (isMain) main() else Robolectric.buildActivity(SensorPickerActivity::class.java).get().also {
                live(it)
                set(it, "tvStatus", TextView(it))
            }
            set(activity, "haTokens", delayed)
            invoke(activity, if (isMain) "setupWebSocket" else "fetchEntitiesFromHA")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
            prefs.edit().putString("ha_local_host", "http://new.example.invalid").commit()
            release.countDown()
            await { activity.lifecycleScope.coroutineContext[kotlinx.coroutines.Job]!!.children.none() }
            assertNull(field<HomeAssistantWebSocket?>(activity, "webSocket"))
        }
    }

    @Test fun migratedTokenAndTransferPhaseNeverAppearInActualS1Export() {
        seed()
        read()
        prefs.edit().putString("ha_token_transfer_phase", "verified").commit()
        val settings = settings()
        val exported = invoke(settings, "buildExportConfig") as JSONObject
        assertFalse(exported.has("ha_token"))
        assertFalse(exported.has("ha_token_transfer_phase"))
        assertFalse(exported.toString().contains("FAKE-LEGACY"))
        assertEquals("FAKE-LEGACY", read().value)
    }
}
