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
 * Экран «Планшет»: «Проверка планшета» (docs/setup-tz.md, часть 4) — что мешает панели работать круглосуточно; ниже
 * (docs/diagnostics-tz.md, часть 2) — что планшет умеет с видео и что приложение решило для каждой камеры.
 * Настройки → «Устройство» → «Планшет». «Забыть опыт» — с PIN, как другие изменения настроек.
 */
class TabletActivity : PinProtectedActivity() {
    private lateinit var content: LinearLayout
    private var checks: List<TabletCheck.Item> = emptyList()
    private var checkRun = 0

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
        column.addView(ModernUi.secondary(this, "Что мешает панели работать круглосуточно, что этот планшет умеет с видео и что приложение решило для каждой камеры. Опыт " +
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
        content.addView(gap())
        val summary = ModernUi.statusRow(this, ModernUi.Status.PROGRESS, "Проверяю…").apply { id = R.id.tabletSummary }
        val checkList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "tablet_checks" }
        content.addView(ModernUi.section(this, "Проверка планшета", summary, checkList))
        if (checks.isNotEmpty()) showChecks()
        readChecks()
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }
        content.addView(gap())
        content.addView(ModernUi.section(this, "Устройство",
            ModernUi.body(this, DeviceFacts.tablet + (if (version.isNotBlank()) " · приложение $version" else "")).apply { tag = "tablet_model" },
            ModernUi.body(this, "Нагрев: " + heatText(DeviceFacts.thermal(this))).apply { tag = "tablet_heat" }))
        LightMode.refresh(this)
        val choices = LightMode.Choice.entries
        content.addView(gap())
        content.addView(ModernUi.section(this, "Облегчённый режим",
            ModernUi.body(this, "Сейчас: " + (LightMode.reason?.let { "включён — $it" } ?: "выключен")).apply { tag = "tablet_light_state" },
            ModernUi.caption(this, "Раскрытие без размытия фона, короче анимации, живое видео — у двух плиток страницы, остальные " +
                "показывают снимок Home Assistant раз в 10 с. «Сам» — включается, когда мало памяти или раскрытие запинается, " +
                "и на время сильного нагрева."),
            choice("tablet_light", choices.map { it.title }, choices.indexOf(LightMode.choice)) { i ->
                if (requireAdmin()) { LightMode.set(this, choices[i]); render() } else render()
            }))
        content.addView(gap())
        content.addView(ModernUi.section(this, "Экран",
            ModernUi.switch(this, "Экран OLED: понемногу сдвигать картинку", TabletWatch.oled(this)) { on ->
                if (requireAdmin()) TabletWatch.setOled(this, on) else render()
            }.apply { tag = "tablet_oled" },
            ModernUi.caption(this, "Раз в ${PanelCooling.SHIFT_MINUTES} минуты панель сдвигается на пару пикселей — защита OLED от выгорания. На обычном (LCD) экране не нужно.")))
        content.addView(gap())
        content.addView(talkSection())
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

    // ---- Звук разговора (docs/talk-tz.md, «Самопроверка звука») ----

    private var talkText: Pair<ModernUi.Status, String>? = null

    private fun talkSection(): View {
        val profile = TalkTablet.profile(this)
        val (status, text) = talkText ?: talkState(profile)
        val row = ModernUi.statusRow(this, status, text).apply { id = R.id.tabletTalk; tag = "tablet_talk_state" }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buttons.addView(ModernUi.primaryButton(this, "Проверить звук") { if (requireAdmin()) checkTalk() }.apply { tag = "tablet_talk_check"; isEnabled = !TalkSelfCheck.busy })
        if (profile != null) buttons.addView(ModernUi.secondaryButton(this, "Забыть проверку звука") { if (requireAdmin()) { TalkTablet.clear(this); talkText = null; render() } }
            .apply { tag = "tablet_talk_forget" })
        return ModernUi.section(this, "Звук разговора", row, ModernUi.caption(this, "Планшет около 12 секунд играет своим динамиком сигнал, похожий на речь, слушает " +
            "микрофоном и подбирает, как убрать эхо в разговоре. Домофон в этом не участвует: у калитки ничего не слышно. Проверяйте в тишине."), buttons)
    }

    private fun talkState(profile: TalkTablet.Profile?): Pair<ModernUi.Status, String> {
        if (profile == null) return ModernUi.Status.UNKNOWN to "Не проверялся — разговор работает как обычно"
        val date = SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(profile.atMs))
        return when (profile.mode) {
            TalkTablet.Mode.TURNS -> ModernUi.Status.WARNING to "Есть эхо — разговор по очереди (проверено $date)"
            TalkTablet.Mode.SYSTEM -> ModernUi.Status.OK to "Хорошо, вместе с подавлением эха Android (проверено $date)"
            TalkTablet.Mode.OWN -> ModernUi.Status.OK to "Хорошо (проверено $date)" + (if (profile.gain < 1f) " — звук улицы убавлен" else "")
        }
    }

    internal fun checkTalk() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        if (DoorbellTalk.active) { talkStatus(ModernUi.Status.WARNING, "Сейчас идёт разговор — проверьте после него"); return }
        val started = TalkSelfCheck.start(this, { text -> runOnUiThread { talkStatus(ModernUi.Status.PROGRESS, text) } }) { result ->
            result.profile?.let { TalkTablet.save(applicationContext, it) }
            val status = when (result.verdict) {
                TalkSelfCheck.Verdict.GOOD, TalkSelfCheck.Verdict.GOOD_QUIETER, TalkSelfCheck.Verdict.GOOD_SYSTEM -> ModernUi.Status.OK
                TalkSelfCheck.Verdict.CANCELLED -> ModernUi.Status.UNKNOWN
                else -> ModernUi.Status.WARNING
            }
            runOnUiThread { talkText = status to result.text; if (!isFinishing && !isDestroyed) render() }
        }
        if (started) content.findViewWithTag<View>("tablet_talk_check")?.isEnabled = false
    }

    private fun talkStatus(status: ModernUi.Status, text: String) {
        talkText = status to text
        if (!isFinishing && !isDestroyed) content.findViewById<View>(R.id.tabletTalk)?.let { ModernUi.setStatus(it as LinearLayout, status, text) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) checkTalk()
        else talkStatus(ModernUi.Status.WARNING, "Нужно разрешение на микрофон")
    }

    /** Ушли с экрана — проверка останавливается (поворот — нет: она в фоне и доиграет сама). */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) TalkSelfCheck.cancel()
    }

    /** Факты читаются в фоне: список причин закрытия и настройки Android — не в главном потоке. */
    private fun readChecks() {
        val run = ++checkRun
        Thread({
            val items = try { TabletCheck.evaluate(TabletCheck.read(this)) } catch (_: RuntimeException) { emptyList() }
            runOnUiThread {
                if (run != checkRun || isFinishing || isDestroyed) return@runOnUiThread
                checks = items
                showChecks()
            }
        }, "tablet-check").apply { isDaemon = true; start() }
    }

    private fun showChecks() {
        val list = content.findViewWithTag<LinearLayout>("tablet_checks") ?: return
        TabletCheckView.summary(checks).let { (status, text) -> ModernUi.setStatus(content.findViewById(R.id.tabletSummary), status, text) }
        TabletCheckView.render(list, checks) { action -> if (requireAdmin()) TabletActions.run(this, action) { readChecks() } }
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

    private fun choice(tag: String, items: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val d = resources.displayMetrics.density
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.tag = tag; setPadding(0, (8 * d).toInt(), 0, 0) }
        items.forEachIndexed { i, item ->
            val button = android.widget.Button(this).apply {
                text = item; isAllCaps = false; this.tag = "${tag}_$i"; minHeight = (48 * d).toInt()
                background = BatteryModernCard.rounded(if (i == selected) android.graphics.Color.argb(90, 95, 168, 211)
                    else android.graphics.Color.argb(40, 30, 44, 56), 10 * d, android.graphics.Color.argb(if (i == selected) 220 else 70, 95, 168, 211))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener { onPick(i) }
            }
            line.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (6 * d).toInt() })
        }
        return line
    }

    private fun limitText(size: Pair<Int, Int>?) = size?.let { "аппаратно до ${it.first}×${it.second}" } ?: "только программно"

    private fun heatText(status: Int?) = when {
        status == null -> "Android не сообщает"
        status >= PowerManager.THERMAL_STATUS_SEVERE -> "перегрет — Android замедляет планшет"
        status >= PowerManager.THERMAL_STATUS_MODERATE -> "заметно нагрет"
        else -> "в норме"
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.2f", ms / 1000.0).trimEnd('0').trimEnd('.').replace('.', ',')

    private companion object { const val REQUEST_MIC = 4712 }

    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, resources.getDimensionPixelSize(R.dimen.modern_space_m)) }
}
