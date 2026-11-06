package com.example.smarthomedashboard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.TimeUnit

// «АКБ» с тем же ореолом, что у остальных плиток Modern. Рендер — build/reports/battery-halo/tiles.png.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatteryHaloVisualTest {
    @Test fun batteryHasSameHaloAsPowerTile() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = FrameLayout(activity)
        val (power, _) = PowerModernCard.createTile(activity, "p", 300, 360, false)
        val (battery, _) = BatteryModernCard.createTile(activity, "b", 540, 390, false)
        row.addView(power, FrameLayout.LayoutParams(300, 360).apply { leftMargin = 20; topMargin = 20 })
        row.addView(battery, FrameLayout.LayoutParams(540, 390).apply { leftMargin = 340; topMargin = 5 })
        activity.findViewById<ViewGroup>(android.R.id.content).addView(row, ViewGroup.LayoutParams(920, 420))
        repeat(3) {
            row.measure(View.MeasureSpec.makeMeasureSpec(920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY))
            row.layout(0, 0, 920, 420)
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
        }
        val card = battery.getChildAt(0) as BatteryModernCard
        assertEquals("Ореол АКБ по той же формуле", PowerModernCard.haloFor(1f), card.haloPx, 0.01f)
        val bitmap = Bitmap.createBitmap(920, 420, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.rgb(5, 8, 12)); row.draw(this) }
        // Внутри полосы ореола (2 px от края плитки) — свечение, а не фон.
        val px = bitmap.getPixel(340 + 270, 5 + 3)
        assertTrue("Ореол вокруг АКБ виден", Color.green(px) > 20)
        val file = File("build/reports/battery-halo/tiles.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
