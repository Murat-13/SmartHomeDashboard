package com.example.smarthomedashboard

import android.content.Context
import androidx.core.content.edit
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Создание предложенных плиток и «Убрать предложенные плитки» (docs/setup-tz.md, часть 2). Плитки пользователя не
 * трогаются никогда: предложенное ставится только на пустую панель (или вместо прежнего предложенного, которое ещё не
 * правили) и убирается, только пока его не правили.
 */
internal object StarterTiles {
    private const val PREFS = "dashboard_prefs"
    private const val TILES = "tiles_prefs"
    /** Что создано: отпечаток плиток и проходов и id созданных проходов. Только на этом планшете, в копии не входит. */
    private const val KEY = "starter_applied"

    private fun applied(context: Context): JSONObject? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { JSONObject(it) }
    } catch (_: Exception) { null }

    /** Плитки и проходы вместе: правка прохода — тоже правка предложенного. */
    private fun fingerprint(context: Context) = ConfigBackup.fingerprint(
        context.getSharedPreferences(TILES, Context.MODE_PRIVATE).getString("tiles", "").orEmpty() + "|" +
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(GateStore.KEY, "").orEmpty())

    /** Предложенные плитки стоят и не правились. */
    fun untouched(context: Context): Boolean = applied(context)?.optString("tiles") == fingerprint(context)

    /** Можно предлагать: панель пуста или на ней только непоправленное предложенное. */
    fun canPropose(context: Context): Boolean {
        val manager = TileManager(context)
        return manager.loadTiles().isEmpty() && manager.loadIssue() == null || untouched(context)
    }

    /** Поставить [plan]; false — панель уже не пуста или запись не удалась (ничего не изменено). */
    fun apply(context: Context, plan: StarterPlan.Plan): Boolean {
        if (!canPropose(context)) return false
        removeGates(context)
        if (!TileManager(context).saveTiles(plan.tiles)) return false
        GateStore.save(context, GateStore.load(context) + plan.gates)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(DashboardPanelController.PREF_PAGE_COUNT, plan.pages.coerceAtLeast(1))
            // Стартовые часы и погода больше не нужны; подсказки новичка остаются.
            putBoolean(MainActivity.PREF_STARTER_TILES_DONE, true)
            putString(KEY, JSONObject().put("tiles", fingerprint(context)).put("gates", JSONArray(plan.gates.map { it.id })).toString())
        }
        return true
    }

    /** Убрать предложенное; false — панель уже правили, ничего не изменено. */
    fun undo(context: Context): Boolean {
        if (!untouched(context)) return false
        removeGates(context)
        if (!TileManager(context).saveTiles(emptyList())) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY)
            putInt(DashboardPanelController.PREF_PAGE_COUNT, 1)
            putBoolean(MainActivity.PREF_STARTER_TILES_DONE, false)
        }
        return true
    }

    private fun removeGates(context: Context) {
        val ids = applied(context)?.optJSONArray("gates") ?: return
        val mine = (0 until ids.length()).mapTo(HashSet()) { ids.optString(it) }
        if (mine.isEmpty()) return
        val all = GateStore.load(context)
        if (all.any { it.id in mine }) GateStore.save(context, all.filter { it.id !in mine })
    }
}
