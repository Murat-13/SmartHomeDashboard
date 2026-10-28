package com.example.smarthomedashboard

import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

// Владеет только прокруткой текущего подробного дерева, без погодных данных и таймеров.
internal class WeatherForecastScrollState {
    enum class Section { HA_FORECAST, YANDEX_HOURLY, YANDEX_DAILY }

    private data class Pending(
        val scroll: HorizontalScrollView,
        val x: Int,
        val listener: View.OnLayoutChangeListener
    )

    private var activeContainer: LinearLayout? = null
    private var activeSource: Pair<String, String>? = null
    private val pending = mutableMapOf<Section, Pending>()

    fun rebuild(container: LinearLayout, tileId: String, source: String, build: () -> Unit) {
        val key = tileId to source
        val positions = if (activeContainer === container && activeSource == key) {
            scrolls(container).associate { (section, scroll) ->
                // Между двумя обновлениями layout мог ещё не произойти.
                section to (pending[section]?.x ?: scroll.scrollX)
            }
        } else emptyMap()
        clear()
        activeContainer = container
        activeSource = key
        build()

        scrolls(container).forEach { (section, scroll) ->
            val x = positions[section] ?: return@forEach
            val listener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    scroll.removeOnLayoutChangeListener(this)
                    if (pending[section]?.scroll !== scroll) return
                    pending.remove(section)
                    if (activeContainer === container && activeSource == key && scroll.parent === container) {
                        // Renderer создаёт новую ScrollView: её первый layout уже измерил строку.
                        // Android сам ограничивает x новым диапазоном, включая пустой прогноз.
                        scroll.scrollTo(x, 0)
                    }
                }
            }
            pending[section] = Pending(scroll, x, listener)
            scroll.addOnLayoutChangeListener(listener)
        }
    }

    fun clear() {
        pending.values.forEach { it.scroll.removeOnLayoutChangeListener(it.listener) }
        pending.clear()
        activeContainer = null
        activeSource = null
    }

    private fun scrolls(container: LinearLayout): List<Pair<Section, HorizontalScrollView>> =
        (0 until container.childCount).mapNotNull { index ->
            val scroll = container.getChildAt(index) as? HorizontalScrollView ?: return@mapNotNull null
            val section = scroll.tag as? Section ?: return@mapNotNull null
            section to scroll
        }
}
