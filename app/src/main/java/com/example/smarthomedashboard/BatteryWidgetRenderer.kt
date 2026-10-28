package com.example.smarthomedashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.toColorInt
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject

internal object BatteryWidgetRenderer {
    fun render(tile: TileEntity, view: FrameLayout, stateForEntity: (String) -> String?) {
        val progressFill = view.findViewWithTag<View>("battery_progress_fill")
        val previous = view.getTag(R.id.battery_widget_renderer_state) as? FillState
        if (progressFill == null) {
            previous?.dispose()
            view.setTag(R.id.battery_widget_renderer_state, null)
            return
        }

        // Общий список датчиков задаёт SOC; конфигурации свёрнутого/развёрнутого текста его не заменяют.
        val socEntityId = try {
            val config = JSONObject(tile.config)
            val arr = config.optJSONArray("entity_ids")
            if (arr != null && arr.length() > 0) arr.getString(0)
            else config.optString("entity_id", "")
        } catch (_: Exception) { "" }

        val socValueStr = stateForEntity(socEntityId) ?: "0"
        val soc = socValueStr.replace("%", "").trim().toFloatOrNull() ?: 0f
        val percent = (soc / 100f).coerceIn(0f, 1f)

        val state = if (previous != null && previous.fill === progressFill) previous else {
            previous?.dispose()
            FillState(view, progressFill).also { view.setTag(R.id.battery_widget_renderer_state, it) }
        }
        state.update(percent)

        val color = when {
            soc > 20f -> "#8033CC33"
            soc > 10f -> "#80FFCC00"
            else -> "#80FF3333"
        }
        progressFill.setBackgroundColor(color.toColorInt())
        view.setBackgroundColor("#80333333".toColorInt())
    }

    // Это служебное состояние анимации конкретной плитки, а не кеш данных датчика.
    // Оно принадлежит самой View через keyed tag; renderer не хранит глобальные ссылки на Views.
    private class FillState(private val card: FrameLayout, val fill: View) : View.OnLayoutChangeListener {
        private var fraction = 0f
        private var targetWidth = -1
        private var animator: ValueAnimator? = null

        init {
            card.addOnLayoutChangeListener(this)
        }

        fun update(percent: Float) {
            fraction = percent
            val target = (card.width * fraction).toInt()
            if (animator?.isRunning == true && targetWidth == target) return
            cancelAnimation()
            targetWidth = target
            if (card.width == 0 || fill.width == target) {
                // Записываем и layoutParams: после отмены анимации измеренная ширина могла отставать.
                setWidth(target)
                return
            }

            val next = ValueAnimator.ofInt(fill.width, target)
            animator = next
            next.duration = 500
            next.addUpdateListener {
                if (animator === next) setWidth(it.animatedValue as Int)
            }
            next.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === next) animator = null
                }
            })
            next.start()
        }

        override fun onLayoutChange(
            v: View, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
        ) {
            if (right - left == oldRight - oldLeft) return
            // После первого измерения и при resize используем последнюю долю заполнения.
            // Вторую анимацию не запускаем: размер карточки уже может анимироваться снаружи.
            cancelAnimation()
            targetWidth = ((right - left) * fraction).toInt()
            setWidth(targetWidth)
        }

        private fun setWidth(width: Int) {
            val params = fill.layoutParams
            if (params.width != width) {
                params.width = width
                fill.layoutParams = params
            }
        }

        private fun cancelAnimation() {
            val previous = animator
            animator = null
            previous?.cancel()
        }

        fun dispose() {
            cancelAnimation()
            card.removeOnLayoutChangeListener(this)
        }
    }
}
