package com.example.smarthomedashboard

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt

internal object TileButtonFactory {
    fun create(
        context: Context,
        title: String,
        tileId: String,
        size: Int,
        initialColor: Int
    ): Button {
        return Button(context).apply {
            text = title
            layoutParams = FrameLayout.LayoutParams(size, size)
            tag = tileId
            alpha = 0.8f
            elevation = 8f
            background = ResourcesCompat.getDrawable(context.resources, R.drawable.bg_button_rounded, null)
            background?.setTint(initialColor)

            setTextColor("#FFFFFF".toColorInt())
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            isAllCaps = false
        }
    }
}
