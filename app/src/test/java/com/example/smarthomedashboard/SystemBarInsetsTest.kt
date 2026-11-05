package com.example.smarthomedashboard

import android.app.Activity
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** targetSdk 35 (0.9.7): на Android 15+ экраны сами отступают от системных полос, до 15 — ничего не меняется. */
@RunWith(RobolectricTestRunner::class)
class SystemBarInsetsTest {
    private fun createdActivity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun insets(): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 36, 0, 0))
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
        .build()

    @Test
    @Config(sdk = [35])
    fun android15ContentKeepsClearOfSystemBarsAndKeyboard() {
        val activity = createdActivity()
        val content = activity.findViewById<View>(android.R.id.content)

        ViewCompat.dispatchApplyWindowInsets(content, insets())
        assertEquals(listOf(0, 36, 0, 48), listOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom))
        assertTrue("под полосами — прежние цвета слоем поверх фона окна", activity.window.decorView.background is LayerDrawable)

        val withKeyboard = WindowInsetsCompat.Builder(insets())
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 400)).build()
        ViewCompat.dispatchApplyWindowInsets(content, withKeyboard)
        assertEquals("снизу — клавиатура, если она выше полосы навигации", 400, content.paddingBottom)
    }

    @Test
    @Config(sdk = [28])
    fun beforeAndroid15NothingChanges() {
        val activity = createdActivity()
        val content = activity.findViewById<View>(android.R.id.content)

        ViewCompat.dispatchApplyWindowInsets(content, insets())
        assertEquals(0, content.paddingTop)
        assertEquals(0, content.paddingBottom)
        assertFalse(activity.window.decorView.background is LayerDrawable)
        val metrics = activity.resources.displayMetrics
        assertEquals(metrics.widthPixels to metrics.heightPixels, SystemBarInsets.legacyWindowSize(activity))
    }
}
