package com.example.smarthomedashboard

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал обновлений приложения: найденные версии, копии перед установкой, продолжение без копии по PIN,
 * запуск и результат установки. Только версии и причины — без адресов, токенов и путей. [KEEP] последних строк.
 */
internal object UpdateLog {
    const val KEEP = 100
    private const val FILE = "update_log.txt"
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun add(context: Context, text: String, now: Long = System.currentTimeMillis()) {
        val line = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(Date(now)) + "  " + text.replace('\n', ' ')
        synchronized(lock) {
            runCatching {
                val file = file(context)
                val lines = (if (file.isFile) file.readLines(Charsets.UTF_8) else emptyList()) + line
                file.writeText(lines.takeLast(KEEP).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            }
        }
    }

    /** Строки от новых к старым. */
    fun lines(context: Context): List<String> = synchronized(lock) {
        runCatching { file(context).takeIf { it.isFile }?.readLines(Charsets.UTF_8).orEmpty() }.getOrDefault(emptyList())
            .filter { it.isNotBlank() }.reversed()
    }
}
