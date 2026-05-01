package com.example.smarthomedashboard

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        // Задержка 2.5 секунды (время на анимацию + немного статики)
        Handler(Looper.getMainLooper()).postDelayed({
            val prefs = getSharedPreferences("dashboard_prefs", MODE_PRIVATE)
            val setupCompleted = prefs.getBoolean("setup_completed", false)

            if (setupCompleted) {
                // Если всё настроено — в главный интерфейс
                startActivity(Intent(this, MainActivity::class.java))
            } else {
                // Если нет — на экран настройки
                startActivity(Intent(this, SetupActivity::class.java))
            }
            finish()
        }, 2000)
    }
}
