package com.example.smarthomedashboard

import android.app.AlertDialog
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.example.smarthomedashboard.data.TileManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран «Планшет» (docs/diagnostics-tz.md, часть 2): что этот планшет умеет и что приложение решило для каждой камеры.
 * Настройки → «Устройство» → «Планшет». «Забыть опыт» — с PIN, как другие изменения настроек.
 */
class TabletActivity : PinProtectedActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun build(): View {
        val pad = resources.getDimensionPixelSize(R.dimen.modern_space_l)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        column.addView(ModernUi.header(this, "Планшет"))
        column.addView(ModernUi.secondary(this, "Что этот планшет умеет с видео и что приложение решило для каждой камеры. Опыт " +
            "показа запоминается и переживает перезапуск; после обновления Android или приложения он собирается заново."))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "tablet_content" }
        column.addView(content)
        column.addView(gap())
        column.addView(ModernUi.secondaryButton(this, "Проверить заново") {
            CameraDiagnosis.refreshDecoders()
            render()
        }.apply { tag = "tablet_refresh" })
        column.addView(ModernUi.secondaryButton(this, "Забыть опыт") { forget() }.apply { tag = "tablet_forget" })
        column.addView(gap())
        column.addView(ModernUi.secondaryButton(this, "Готово") { finish() }.apply { tag = "tablet_done" })
        return ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@TabletActivity, R.color.modern_background))
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }
        content.addView(gap())
        content.addView(ModernUi.section(this, "Устройство",
            ModernUi.body(this, DeviceFacts.tablet + (if (version.isNotBlank()) " · приложение $version" else "")).apply { tag = "tablet_model" },
            ModernUi.body(this, "Нагрев: " + heatText(DeviceFacts.thermal(this))).apply { tag = "tablet_heat" }))
        val decoders = CameraDiagnosis.decoderFacts()
        content.addView(gap())
        content.addView(ModernUi.section(this, "Декодеры видео",
            ModernUi.body(this, "H.264 — " + limitText(decoders.maxSize(VideoDepacketizer.Codec.H264))).apply { tag = "tablet_h264" },
            ModernUi.body(this, "H.265 — " + limitText(decoders.maxSize(VideoDepacketizer.Codec.H265))).apply { tag = "tablet_h265" },
            ModernUi.caption(this, "Больше этого — программно: медленнее и сильнее греет планшет.")))
        val tiles = TileManager(this).loadTiles().filter { DoorbellConfig.isVideo(it.type) }
        val rows = tiles.map { tile ->
            val config = DoorbellConfig.from(tile.config)
            val main = DoorbellConfig.playableRtsp(config.rtspMain).trim()
            val sub = DoorbellConfig.playableRtsp(config.rtspSub).trim()
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tag = "tablet_camera_${tile.id}"
                addView(ModernUi.title(this@TabletActivity, tile.title.ifBlank { if (tile.type == DoorbellConfig.CAMERA_TYPE) "Камера" else "Домофон" }))
                addView(ModernUi.body(this@TabletActivity, "HD: " + hdText(main, sub)))
                (TabletProfile.jitter(main).takeIf { it > 0 } ?: TabletProfile.jitter(sub).takeIf { it > 0 })?.let { ms ->
                    addView(ModernUi.caption(this@TabletActivity, "Запас на неровный приход кадров — с ${seconds(ms)} с (запомнено)"))
                }
                setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.modern_space_m))
            }
        }
        content.addView(gap())
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            rows.ifEmpty { listOf(ModernUi.body(this@TabletActivity, "Плиток с видео нет")) }.forEach { addView(it) }
        }
        content.addView(ModernUi.section(this, "Камеры", list))
    }

    /** Что решено для основного потока ([main]) на этом планшете. */
    private fun hdText(main: String, sub: String): String {
        if (main.isBlank()) return "основной поток не указан"
        val hasSub = sub.isNotBlank() && sub != main
        val record = TabletProfile.record(main)
        val since = record?.atMs?.takeIf { it > 0 }?.let { " (с ${SimpleDateFormat("d MMM", Locale("ru")).format(Date(it))})" } ?: ""
        if (record?.heavy == true && hasSub) return "субпоток — программный декодер не успевал показывать основной$since"
        if ((record?.undecodable == true || main in DoorbellVideoView.undecodable) && hasSub) return "субпоток — основной поток этот планшет не показывает$since"
        val show = PlaybackLog.recent(main).lastOrNull()
            ?: return if (record?.software == true) "программно — аппаратный декодер не берёт этот размер$since"
                else "ещё не показывался после запуска приложения"
        val how = if (show.software) "программно" else "аппаратно"
        return "$how, ${show.width}×${show.height}, ${String.format(Locale.US, "%.0f", show.shownFps)} к/с" +
            if (show.keepsUp) ", успевает" else ", не успевает"
    }

    private fun forget() {
        if (!requireAdmin()) return
        AlertDialog.Builder(this)
            .setTitle("Забыть опыт показа видео?")
            .setMessage("Планшет заново узнает, какие потоки показывать аппаратно, программно или субпотоком, и какой нужен запас. " +
                "Первые показы после этого могут начаться чуть дольше.")
            .setPositiveButton("Забыть") { _, _ -> TabletProfile.forget(); render() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun limitText(size: Pair<Int, Int>?) = size?.let { "аппаратно до ${it.first}×${it.second}" } ?: "только программно"

    private fun heatText(status: Int?) = when {
        status == null -> "Android не сообщает"
        status >= PowerManager.THERMAL_STATUS_SEVERE -> "перегрет — Android замедляет планшет"
        status >= PowerManager.THERMAL_STATUS_MODERATE -> "заметно нагрет"
        else -> "в норме"
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.2f", ms / 1000.0).trimEnd('0').trimEnd('.').replace('.', ',')

    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, resources.getDimensionPixelSize(R.dimen.modern_space_m)) }
}
