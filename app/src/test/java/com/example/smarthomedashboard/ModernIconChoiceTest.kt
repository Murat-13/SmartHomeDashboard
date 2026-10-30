package com.example.smarthomedashboard

import android.app.Activity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Выбор иконки Modern: каталог, хранение, совместимость старых плиток и предпросмотр редактора. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [ButtonStateResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ModernIconChoiceTest {

    @Test fun catalogHasRequestedIconsWithUniqueStableKeys() {
        val keys = ModernIcon.choices.map { it.key!! }
        assertEquals(keys.size, keys.toSet().size)
        listOf("power", "lightbulb", "lamp", "ceiling_light", "flash", "water_heater", "fan", "snowflake", "home",
            "gate", "garage", "thermometer", "plug", "water", "air_conditioner").forEach { assertTrue(it, it in keys) }
        // Служебные значки не выбираются.
        assertFalse(ModernIcon.HELP in ModernIcon.choices)
        assertFalse(ModernIcon.WARNING in ModernIcon.choices)
        assertFalse(ModernIcon.EMPTY_GRID in ModernIcon.choices)
        assertTrue(ModernIcon.choices.all { !it.label.isNullOrBlank() })
    }

    @Test fun keysAliasesAndUnknownValuesAreSafe() {
        assertEquals(ModernIcon.SNOWFLAKE, ModernIcon.fromKey("snowflake"))
        assertEquals(ModernIcon.BOILER, ModernIcon.fromKey("boiler"))
        assertEquals(ModernIcon.AIR_CONDITIONER, ModernIcon.fromKey(" AC_UNIT "))
        assertNull(ModernIcon.fromKey("future_icon"))
        assertNull(ModernIcon.fromKey(""))
        assertNull(ModernIcon.fromConfig("""{"entity_id":"light.a"}"""))
        assertNull(ModernIcon.fromConfig("{broken"))
        assertEquals(ModernIcon.GATE, ModernIcon.fromConfig("""{"modern_icon":"gate"}"""))
    }

    @Test fun writeOnlyChangesIconKeyAndOnlyWhenEdited() {
        val config = JSONObject("""{"entity_ids":["a.b"],"child_names":["A"],"button_size":200,"button_style":"modern","x":{"y":1}}""")
        ModernIcon.writeIfChanged(config, ModernIcon.FAN, changed = false)
        assertFalse(config.has("modern_icon"))
        ModernIcon.writeIfChanged(config, ModernIcon.FAN, changed = true)
        assertEquals("fan", config.getString("modern_icon"))
        assertEquals(200, config.getInt("button_size"))
        assertEquals(1, config.getJSONObject("x").getInt("y"))
        // «Авто/без иконки» снова убирает ключ — прежнее поведение.
        ModernIcon.writeIfChanged(config, null, changed = true)
        assertFalse(config.has("modern_icon"))
        // Неизвестный ключ более новой версии без явного выбора не теряется.
        val future = JSONObject("""{"modern_icon":"future_icon"}""")
        ModernIcon.writeIfChanged(future, null, changed = false)
        assertEquals("future_icon", future.getString("modern_icon"))
        ModernIcon.writeIfChanged(future, null, changed = true)
        assertFalse(future.has("modern_icon"))
    }

    @Test fun legacyTileJsonWithoutIconFieldsStillLoads() {
        // Старая запись без полей icon/modern_icon: Gson оставляет null в поле icon.
        val json = """[{"id":"b","type":"button","container":"bottom_panel","title":"Старая","x":1,"y":2,"width":1,"height":1,
            "config":"{\"entity_id\":\"switch.a\",\"button_style\":\"modern\"}","enabled":true}]"""
        val tile = Gson().fromJson<List<TileEntity>>(json, object : TypeToken<List<TileEntity>>() {}.type).single()
        assertNull(tile.icon as String?)
        assertNull(ModernIcon.fromConfig(tile.config))
        assertEquals(TileButtonStyle.MODERN, TileButtonStyle.of(tile.config))
    }

    private fun editorHost(): Activity {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(host).apply { id = R.id.buttonStyleSection })
            addView(Button(host).apply { id = R.id.btnStyleClassic })
            addView(Button(host).apply { id = R.id.btnStyleModern })
            addView(FrameLayout(host).apply { id = R.id.buttonStylePreview })
            addView(TextView(host).apply { id = R.id.tvModernIconLabel })
            addView(FrameLayout(host).apply { id = R.id.modernIconPicker })
        }
        host.setContentView(content)
        return host
    }

    private fun picker(host: Activity) = host.findViewById<FrameLayout>(R.id.modernIconPicker).getChildAt(0) as ModernIconPicker
    private fun previewModel(host: Activity) =
        (host.findViewById<FrameLayout>(R.id.buttonStylePreview).getChildAt(0) as ModernTileView).boundModel!!

    @Test fun pickingIconUpdatesSinglePreviewImmediatelyWithoutSaving() {
        val host = editorHost()
        val editor = TileStyleEditor(host, TileButtonStyle.MODERN, null, { "Свет" }, { "button" }, { 160 }, { listOf("switch.a" to "a") })
        editor.update()
        // Без выбора — прежний автоподбор по домену.
        assertEquals(ModernIcon.POWER, previewModel(host).icon)
        assertFalse(editor.iconChanged)
        assertEquals("Иконка кнопки", host.findViewById<TextView>(R.id.tvModernIconLabel).text)

        picker(host).cellFor(ModernIcon.SNOWFLAKE).performClick()
        assertEquals(ModernIcon.SNOWFLAKE, editor.icon)
        assertTrue(editor.iconChanged)
        assertEquals(ModernIcon.SNOWFLAKE, previewModel(host).icon)
        assertEquals(ModernIcon.SNOWFLAKE, picker(host).selected)

        // Classic: выбор иконки скрыт, сам выбор не теряется.
        host.findViewById<Button>(R.id.btnStyleClassic).performClick()
        assertEquals(android.view.View.GONE, picker(host).visibility)
        assertEquals(ModernIcon.SNOWFLAKE, editor.icon)
    }

    @Test fun groupPreviewShowsGroupIconAndKeepsThumbnails() {
        val host = editorHost()
        val editor = TileStyleEditor(host, TileButtonStyle.MODERN, ModernIcon.GATE, { "Ворота" }, { "group" },
            { 200 }, { listOf("cover.a" to "a", "light.b" to "b") })
        editor.update()
        val model = previewModel(host)
        assertEquals(ModernIcon.GATE, model.groupIcon)
        assertEquals(2, model.items.size)
        assertTrue(model.items.all { it.icon == ModernIcon.GATE })
        assertEquals("Иконка группы", host.findViewById<TextView>(R.id.tvModernIconLabel).text)
        picker(host).cellFor(null).performClick()
        assertNull(previewModel(host).groupIcon)
        assertEquals(ModernIcon.BULB, previewModel(host).items[1].icon)
    }
}
