package com.example.smarthomedashboard

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.smarthomedashboard.data.TileManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Отчёт для разработчика (docs/reliability.md): что за планшет, версия, какие плитки и камеры (сколько и каких — без названий, адресов
 * и сущностей) и последние сбои. Только показывается; отправляет человек сам, кнопкой «Поделиться».
 */
internal object SupportReport {
    fun build(context: Context): String {
        val out = StringBuilder()
        val info = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        @Suppress("DEPRECATION") // longVersionCode — с Android 9
        out.line("Панель ${info?.versionName.orEmpty()} (${info?.versionCode ?: 0}), канал: ${AppUpdater.channel(context).label}")
        out.line("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
        val m = context.resources.displayMetrics
        out.line("Экран ${m.widthPixels}×${m.heightPixels}, ${(m.widthPixels / m.density).toInt()}×${(m.heightPixels / m.density).toInt()} dp, шрифт ×${m.fontScale()}")
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        out.line("Память: свободно ${gb(memory.availMem)} из ${gb(memory.totalMem)} ГБ; место: ${gb(android.os.StatFs(context.filesDir.path).availableBytes)} ГБ")
        out.line("Работает ${SystemClock.elapsedRealtime().let { "${it / 3_600_000} ч" }} после включения; нагрев ${DeviceFacts.thermal(context) ?: "—"}; облегчённый режим: ${LightMode.reason ?: "нет"}; запуск: ${SafeStart.mode}")
        TalkTablet.profile(context)?.let { out.line("Звук разговора: ${it.mode}, ×${it.gain}, эхо тише на ${it.reductionDb} дБ, ${it.delayMs} мс") }
        val decoders = CameraDiagnosis.decoderFacts()
        out.line("Декодеры: H.264 ${decoders.maxSize(VideoDepacketizer.Codec.H264)}, H.265 ${decoders.maxSize(VideoDepacketizer.Codec.H265)}")
        val tiles = runCatching { TileManager(context).loadTiles() }.getOrDefault(emptyList())
        out.line("Плитки: " + tiles.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" } +
            "; страниц ${(tiles.maxOfOrNull { it.page } ?: 0) + 1}")
        tiles.filter { DoorbellConfig.isVideo(it.type) }.forEachIndexed { i, tile -> out.line("Видео ${i + 1}: " + video(tile.type, DoorbellConfig.from(tile.config))) }
        out.line("Обновления: " + CrashLog.sanitize(AppUpdater.statusText(context).second))
        val crashes = CrashLog.entries(context)
        out.line("Сбоев записано: ${crashes.size}")
        crashes.take(MAX_CRASHES).forEach { out.line("\n—— " + it.text.trim()) }
        return out.toString().trim()
    }

    /** Какой путь видео и что с ним — без адресов. */
    private fun video(type: String, c: DoorbellConfig): String {
        fun kind(url: String) = when { url.isBlank() -> "нет"; url.startsWith("rtsps", true) -> "rtsps"; TalkChannels.looksReolink(url) -> "Reolink"; else -> "rtsp" }
        val show = PlaybackLog.recent(DoorbellConfig.playableRtsp(c.rtspMain).trim()).lastOrNull()
        return (if (type == DoorbellConfig.CAMERA_TYPE) "камера" else "домофон") + ", основной ${kind(c.rtspMain)}, субпоток ${kind(c.rtspSub)}, Home Assistant ${if (c.camera.isBlank()) "нет" else "да"}" +
            (if (c.snapshotUrl.isBlank()) "" else ", снимок") + (show?.let { ", HD ${it.width}×${it.height} ${if (it.software) "программно" else "аппаратно"} %.0f к/с".format(Locale.US, it.shownFps) } ?: "")
    }

    private fun android.util.DisplayMetrics.fontScale() = String.format(Locale.US, "%.2f", android.content.res.Resources.getSystem().configuration.fontScale)
    private fun gb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1e9).replace('.', ',')
    private fun StringBuilder.line(text: String) { append(text).append('\n') }
    private const val MAX_CRASHES = 3
}

/** «Сбои и отчёт» (Настройки → «О приложении»): последние сбои этого планшета и обезличенный отчёт, который человек отправляет сам. */
class SupportActivity : PinProtectedActivity() {
    private lateinit var content: LinearLayout
    private lateinit var debugButton: android.widget.TextView
    private fun renderDebug() { debugButton.text = if (DebugSwitches.allowed(this)) "Проверочный режим: включён — выключить" else "Проверочный режим: выключен — включить" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
        render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(12)) }

    private fun build(): View {
        val pad = resources.getDimensionPixelSize(R.dimen.modern_space_l)
        val column = MaxWidthLinearLayout(this).apply { orientation = LinearLayout.VERTICAL; maxWidth = dp(900); setPadding(pad, pad, pad, pad) }
        column.addView(ModernUi.header(this, "Сбои и отчёт"))
        column.addView(ModernUi.secondary(this, "Сбои хранятся только на этом планшете: последние ${CrashLog.KEEP}, без адресов, названий и паролей. Сами никуда не отправляются."))
        column.addView(gap())
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "support_list" }
        column.addView(content)
        column.addView(gap())
        column.addView(ModernUi.primaryButton(this, "Собрать отчёт") { showReport() }.apply { tag = "support_report" })
        column.addView(ModernUi.secondaryButton(this, "Очистить") { clear() }.apply { tag = "support_clear" })
        column.addView(gap())
        debugButton = ModernUi.secondaryButton(this, "") { DebugSwitches.set(this, !DebugSwitches.allowed(this)); renderDebug() }.apply { tag = "support_debug" }
        column.addView(debugButton)
        column.addView(ModernUi.secondary(this, "Проверочный режим — для замеров и разбора звука по просьбе разработчика: файлы-выключатели в папке приложения " +
            "действуют, только пока он включён. Через ${DebugSwitches.HOURS} ч выключается сам."))
        renderDebug()
        column.addView(gap())
        column.addView(ModernUi.secondaryButton(this, "Готово") { finish() }.apply { tag = "support_done" })
        return FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@SupportActivity, R.color.modern_background))
            addView(ScrollView(this@SupportActivity).apply { addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)) })
        }
    }

    private fun render() {
        content.removeAllViews()
        val entries = CrashLog.entries(this)
        if (entries.isEmpty()) {
            content.addView(ModernUi.statusRow(this, ModernUi.Status.OK, "Сбоев не было").apply { tag = "support_none" })
            return
        }
        val date = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
        for (e in entries) {
            val row = ModernUi.statusRow(this, ModernUi.Status.WARNING, "${date.format(Date(e.atMs))} — ${e.kind.title.lowercase(Locale("ru"))}" + e.summary.let { if (it.isEmpty()) "" else " ($it)" })
            row.isClickable = true
            row.minimumHeight = dp(48)
            row.setOnClickListener { showText(e.kind.title, e.text) }
            content.addView(row)
        }
    }

    private fun showReport() {
        val app = applicationContext
        Thread({
            val text = try { SupportReport.build(app) } catch (e: RuntimeException) { "Отчёт собрать не получилось (${e.javaClass.simpleName})" }
            runOnUiThread { if (!isFinishing && !isDestroyed) showText("Отчёт", text) }
        }, "support-report").apply { isDaemon = true; start() }
    }

    internal fun showText(title: String, text: String) {
        val view = TextView(this).apply {
            this.text = text; tag = "support_text"; textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Поделиться") { _, _ -> share(text) }
            .setNeutralButton("Скопировать") { _, _ ->
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, text))
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun share(text: String) = try {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Отправить отчёт"))
    } catch (_: RuntimeException) { Toast.makeText(this, "Отправить нечем — скопируйте текст", Toast.LENGTH_SHORT).show() }

    private fun clear() {
        if (!requireAdmin()) return
        AlertDialog.Builder(this).setTitle("Очистить записи о сбоях?").setPositiveButton("Очистить") { _, _ -> CrashLog.clear(this); render() }
            .setNegativeButton("Отмена", null).show()
    }
}
