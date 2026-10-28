package com.example.smarthomedashboard

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * LinearLayout с android:maxWidth: на телефоне занимает ширину экрана, на большом
 * планшете не растягивается в длинную строку, которую неудобно читать.
 */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    var maxWidth: Int = 0
        set(value) {
            field = value
            requestLayout()
        }

    init {
        context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxWidth)).apply {
            maxWidth = getDimensionPixelSize(0, 0)
            recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val spec = if (maxWidth > 0 && (mode == MeasureSpec.UNSPECIFIED || size > maxWidth)) {
            val clampedMode = if (mode == MeasureSpec.EXACTLY) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST
            MeasureSpec.makeMeasureSpec(maxWidth, clampedMode)
        } else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }
}
