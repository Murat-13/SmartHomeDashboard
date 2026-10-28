package com.example.smarthomedashboard

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val openNext = Runnable { openNextScreen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Кнопка «Домой» в режиме главного экрана: панель уже работает, заставка не нужна.
        if (HomeScreenMode.isHomeLaunch(intent) && MainActivity.isAlive) {
            openNextScreen()
            return
        }
        setContentView(R.layout.activity_splash)

        val logo = findViewById<android.view.View>(R.id.splash_logo)
        val title = findViewById<android.view.View>(R.id.splash_title)

        // Начальное состояние для анимации
        logo.alpha = 0f
        logo.scaleX = 0.8f
        logo.scaleY = 0.8f
        title.alpha = 0f

        // Анимация появления
        logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        title.animate()
            .alpha(1f)
            .setDuration(1000)
            .setStartDelay(500)
            .start()

        handler.postDelayed(openNext, 2000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openNext)
        super.onDestroy()
    }

    private fun openNextScreen() {
        val setupCompleted = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
            .getBoolean("setup_completed", false)
        // MainActivity — singleTask: существующая панель выводится на передний план, а не создаётся заново.
        startActivity(Intent(this, if (setupCompleted) MainActivity::class.java else SetupActivity::class.java))
        finish()
    }
}
