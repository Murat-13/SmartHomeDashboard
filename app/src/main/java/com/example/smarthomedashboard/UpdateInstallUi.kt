package com.example.smarthomedashboard

import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

/**
 * Установка обновления по кнопке (боковая панель или «О приложении»). Вызывающий уже проверил PIN.
 * Подтверждение → (разрешение Android) → копия → установка; без копии — только после явного предупреждения.
 */
internal object UpdateInstallUi {
    fun start(activity: AppCompatActivity, onChanged: () -> Unit = {}) {
        val status = AppUpdater.status(activity)
        if (status.state != AppUpdater.State.READY) {
            Toast.makeText(activity, "Готового обновления нет", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Установить версию ${status.versionName}?")
            .setMessage(buildString {
                status.notes.takeIf { it.isNotBlank() }?.let { append(it.take(600)).append("\n\n") }
                append("Перед установкой будет сделана копия настроек. Панель закроется на несколько секунд и откроется снова. ")
                append("Если Android попросит подтверждение — нажмите «Обновить».")
            })
            .setPositiveButton("Установить") { _, _ -> run(activity, allowWithoutBackup = false, onChanged) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun run(activity: AppCompatActivity, allowWithoutBackup: Boolean, onChanged: () -> Unit) {
        activity.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                UpdateInstaller.install(activity.applicationContext, allowWithoutBackup = allowWithoutBackup)
            }
            onChanged()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when (outcome) {
                UpdateInstaller.Outcome.STARTED ->
                    Toast.makeText(activity, "Установка обновления…", Toast.LENGTH_LONG).show()
                UpdateInstaller.Outcome.NO_UPDATE -> {
                    // Файл мог быть отвергнут при повторной проверке (например, отладочная сборка) — показываем причину.
                    val rejected = AppUpdater.status(activity).takeIf { it.state == AppUpdater.State.ERROR }?.message
                    Toast.makeText(activity, rejected?.let { "Обновление отклонено: $it" }
                        ?: "Файл обновления не найден или изменился — дождитесь новой проверки", Toast.LENGTH_LONG).show()
                }
                UpdateInstaller.Outcome.NEEDS_PERMISSION -> AlertDialog.Builder(activity)
                    .setTitle("Нужно разрешение Android")
                    .setMessage("Один раз разрешите панели устанавливать приложения: в открывшихся настройках включите " +
                        "«Разрешить из этого источника», вернитесь и снова нажмите «Установить».")
                    .setPositiveButton("Открыть настройки") { _, _ ->
                        // Нет разрешения может быть только на Android 8+ (см. UpdateInstaller.canInstall).
                        if (android.os.Build.VERSION.SDK_INT >= 26) runCatching {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                "package:${activity.packageName}".toUri()))
                        }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
                UpdateInstaller.Outcome.BACKUP_FAILED -> ModernUi.dangerDialog(activity, "Копия не создана",
                    "Не удалось сделать копию настроек перед обновлением. Установка остановлена. Можно продолжить без копии — " +
                        "это будет записано в журнал обновлений.", "Установить без копии") {
                    run(activity, allowWithoutBackup = true, onChanged)
                }.show()
                UpdateInstaller.Outcome.NOT_SILENT, UpdateInstaller.Outcome.FAILED ->
                    Toast.makeText(activity, "Не удалось начать установку — подробности в журнале обновлений", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Журнал обновлений, новые записи сверху. */
    fun showLog(activity: AppCompatActivity) {
        val lines = UpdateLog.lines(activity)
        AlertDialog.Builder(activity)
            .setTitle("Журнал обновлений")
            .setMessage(if (lines.isEmpty()) "Записей пока нет" else lines.joinToString("\n"))
            .setPositiveButton("Закрыть", null)
            .show()
    }
}
