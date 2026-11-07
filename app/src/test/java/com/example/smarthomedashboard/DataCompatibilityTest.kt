package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.example.smarthomedashboard.data.TileManager
import com.example.smarthomedashboard.data.TileParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationTargetException

/**
 * Совместимость данных (0.9.13, docs/deep-testing.md). Образцы в test/resources/compat записаны настоящим кодом
 * прежних версий (v0.9.1, v0.9.4, v0.9.8 — их собственными шаблонами плиток, см. docs): так выглядят настройки на
 * планшетах, обновлённых с этих версий, и их резервные копии. Текущая версия обязана: прочитать все плитки без
 * повреждений, построить каждую на панели, обновлять её от всех её сущностей и принять старую копию при импорте.
 * Новый формат — новый образец здесь, старые не удаляются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class DataCompatibilityTest {
    private val context = RuntimeEnvironment.getApplication()
    private val versions = listOf("v0.9.1", "v0.9.4", "v0.9.8")

    private fun fixture(version: String) = JSONObject(requireNotNull(javaClass.classLoader!!.getResource("compat/$version.json")) {
        "Нет образца $version"
    }.readText())

    /** Настройки ровно с типами, как их записала старая версия. */
    private fun install(version: String): JSONObject {
        val json = fixture(version)
        for (name in listOf("dashboard_prefs", "tiles_prefs")) {
            val editor = context.getSharedPreferences(name, 0).edit().clear()
            val values = json.getJSONObject(name)
            values.keys().forEach { key ->
                val entry = values.getJSONObject(key)
                when (entry.getString("type")) {
                    "String" -> editor.putString(key, entry.getString("value"))
                    "Integer" -> editor.putInt(key, entry.getInt("value"))
                    "Long" -> editor.putLong(key, entry.getLong("value"))
                    "Boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
                    "Float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
                    else -> error("Тип ${entry.getString("type")} в образце $version")
                }
            }
            editor.commit()
        }
        return json
    }

    private fun entities(text: String) =
        Regex("""\b(?:sensor|binary_sensor|light|switch|weather|camera|lock|select)\.[a-z0-9_]+""").findAll(text).map { it.value }.toSet()

    @Test fun oldTilesAreReadIntactAndEveryEntityUpdatesItsTile() {
        for (version in versions) {
            install(version)
            val raw = context.getSharedPreferences("tiles_prefs", 0).getString("tiles", null)
            val parsed = TileParser.parse(raw)
            assertNull("$version: плитки прочитаны без повреждений", parsed.issue)
            val manager = TileManager(context)
            val tiles = manager.getAllTiles()
            assertEquals("$version: ни одна плитка не потеряна", org.json.JSONArray(raw).length(), tiles.size)
            assertTrue("$version: старые плитки на главной странице", tiles.all { it.page == 0 })
            // Клиент получает все state_changed; важно, что каждая сущность старой плитки обновляет именно её.
            val index = PanelDependencyIndex(tiles)
            for (tile in tiles) {
                val notRouted = entities(tile.config).filter { id -> index.affected(listOf(id)).none { it.id == tile.id } }
                assertTrue("$version: ${tile.id} (${tile.type}) не обновляется от $notRouted", notRouted.isEmpty())
            }
        }
    }

    @Test fun everyOldTileIsBuiltOnThePanel() {
        for (version in versions) {
            install(version)
            val activity = Robolectric.buildActivity(MainActivity::class.java).get().apply { pinGestureFixture(this) }
            activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            activity.resources.displayMetrics.widthPixels = 1920
            activity.resources.displayMetrics.heightPixels = 1200
            val manager = TileManager(activity)
            val panel = FrameLayout(activity)
            ReflectionHelpers.setField(activity, "tileManager", manager)
            ReflectionHelpers.setField(activity, "bottomPanel", panel)
            ReflectionHelpers.setField(activity, "dimOverlay", View(activity).apply { visibility = View.GONE })
            ReflectionHelpers.setField(activity, "yandexWeatherManager", YandexWeatherManager(activity))
            activity.window.setContentView(FrameLayout(activity).apply { addView(panel) })
            ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
            ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
            ArchitectureTestAccess.methods(activity).single { it.name == "connectDoorbells" }.apply { isAccessible = true }.invoke(activity)
            DoorbellHub.attach(activity.application)
            ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
                .apply { isAccessible = true }.invoke(activity)
            shadowOf(Looper.getMainLooper()).idle()
            for (tile in manager.getAllTiles()) {
                assertNotNull("$version: плитка ${tile.id} (${tile.type}) построена", panel.findViewWithTag<View>(tile.id))
            }
            DoorbellHub.actions = null
            DoorbellHub.connection = null
            DoorbellHub.detach()
        }
    }

    @Test fun oldBackupFilesImportCompletely() {
        for (version in versions) {
            val backup = fixture(version).getJSONObject("backup")
            // Текущие настройки другие: импорт обязан заменить плитки содержимым старой копии.
            context.getSharedPreferences("tiles_prefs", 0).edit().clear().putString("tiles", "[]").commit()
            context.getSharedPreferences("dashboard_prefs", 0).edit().clear().putString("app_theme", "light").commit()
            val activity = Robolectric.buildActivity(SettingsActivity::class.java).get().apply {
                setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            }
            val uri = Uri.parse("content://test-only/$version")
            shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(backup.toString().toByteArray()))
            invoke(activity, "importConfig")
            val started = shadowOf(activity).nextStartedActivityForResult
            invoke(activity, "onActivityResult", started.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            shadowOf(Looper.getMainLooper()).idle()
            val tiles = context.getSharedPreferences("tiles_prefs", 0).getString("tiles", null)
            assertEquals("$version: плитки старой копии приняты без изменений", backup.getString("tiles_json"), tiles)
            assertEquals("$version: настройки старой копии приняты", backup.getString("app_theme"),
                context.getSharedPreferences("dashboard_prefs", 0).getString("app_theme", null))
            assertNull("$version: принятая копия читается целиком", TileParser.parse(tiles).issue)
        }
    }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        try {
            target.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(target, *args)
        } catch (e: InvocationTargetException) { throw e.targetException }
    }
}
