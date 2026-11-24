package com.example.smarthomedashboard

import android.content.Context
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Диагностика камеры (docs/diagnostics-tz.md, часть 1): что мешает видео — сеть, камера или планшет — и что с этим
 * сделать. Проверки — чистые правила над сводкой ([DiagInput]); сбор сводки — [CameraDiagnosis]. Только чтение:
 * ни камере, ни дому ничего не отправляется.
 */
internal object CameraDiagnostics {
    enum class Level(val mark: String) { BAD("✗"), IMPROVE("⚠"), OK("✓"), UNKNOWN("–") }

    /** Одна проверка: номер из ТЗ (С1, К3…), что проверяли, итог, подробности и совет. */
    data class Check(val id: String, val title: String, val level: Level, val detail: String, val advice: String = "")

    fun evaluate(input: DiagInput): List<Check> {
        val line = lineCheck(input)
        return listOf(line, lossCheck(input), lateCheck(input, line), wifiCheck(input), loadCheck(input, line),
            keyCheck(input), sensorCheck(input), sizeCheck(input), codecCheck(input), subCheck(input), clockCheck(input),
            stallCheck(input), heatCheck(input), decodeCheck(input))
    }

    /** Строка-итог: «Всё в порядке» / «Мешает: N, можно лучше: M»; null — проверять пока нечего. */
    fun summary(checks: List<Check>): String? {
        val bad = checks.count { it.level == Level.BAD }
        val improve = checks.count { it.level == Level.IMPROVE }
        if (checks.none { it.level != Level.UNKNOWN }) return null
        return when {
            bad > 0 && improve > 0 -> "Мешает: $bad, можно лучше: $improve"
            bad > 0 -> "Мешает: $bad"
            improve > 0 -> "Можно лучше: $improve"
            else -> "Всё в порядке"
        }
    }

    // ---- Сеть ----

    /** С1. Линия камеры 10 Мбит/с: большой ключевой кадр идёт ≈ 1 МБ/с (24.11.2026: 0,4 с против 0,1 с на 100 Мбит/с). */
    private fun lineCheck(input: DiagInput): Check {
        val title = "Скорость линии камеры"
        val m = input.main
        val rate = m?.keyRateKbPerSec
        val span = m?.keySpanMs
        if (m == null || m.keySamples < MIN_KEYS || rate == null || span == null) {
            val small = m?.keyKbAll?.takeIf { it * 1024 < StreamStats.BIG_KEY_BYTES }
            return if (small != null) Check("С1", title, Level.OK, "Ключевые кадры небольшие ($small КБ) — скорость линии на видео почти не влияет")
                else Check("С1", title, Level.UNKNOWN, "Мало данных: нужно несколько ключевых кадров основного потока")
        }
        val what = "Ключевой кадр ${m.keyKb ?: "?"} КБ " + if (span < INSTANT_MS) "приходит сразу" else "идёт ${seconds(span)} с (≈ ${mb(rate)} МБ/с)"
        if (rate < SLOW_LINE_KB_PER_SEC && span >= SLOW_KEY_MS) {
            return Check("С1", title, if (span >= BAD_KEY_MS) Level.BAD else Level.IMPROVE,
                "$what — так бывает, когда порт камеры работает на 10 Мбит/с",
                "Проверьте на PoE-свитче режим Extend / CCTV / 250 m (он переводит порты на 10 Мбит/с), кабель и разъёмы " +
                    "камеры. Видео станет быстрее примерно на ${seconds((span - FAST_KEY_MS).coerceAtLeast(0))} с")
        }
        return Check("С1", title, Level.OK, "$what — линия справляется")
    }

    /** С2. Потери пакетов (оба потока). */
    private fun lossCheck(input: DiagInput): Check {
        val title = "Потери по дороге"
        val parts = listOfNotNull(input.main, input.sub).filter { it.lossPercent != null }
        val packets = parts.sumOf { it.packets }
        if (parts.isEmpty() || packets < MIN_PACKETS) return Check("С2", title, Level.UNKNOWN, "Мало данных")
        val percent = parts.sumOf { it.lossPercent!! * it.packets } / packets
        val text = "Потеряно ${percentText(percent)} пакетов видео"
        val advice = "Часть видео теряется по дороге — картинка может рассыпаться. Камера по Wi-Fi — улучшите сигнал у неё; " +
            "по проводу — проверьте кабель и разъёмы"
        return when {
            percent >= BAD_LOSS -> Check("С2", title, Level.BAD, text, advice)
            percent >= SOME_LOSS -> Check("С2", title, Level.IMPROVE, text, advice)
            else -> Check("С2", title, Level.OK, if (percent == 0.0) "Потерь нет" else text)
        }
    }

    /** С3. Неровный приход: самый поздний кадр минуты позже обычного. */
    private fun lateCheck(input: DiagInput, line: Check): Check {
        val title = "Ровность прихода кадров"
        val late = input.main?.lateMs ?: input.sub?.lateMs ?: return Check("С3", title, Level.UNKNOWN, "Мало данных")
        val text = "Самый поздний кадр приходит на ${seconds(late)} с позже обычного — на столько видео приходится отставать, " +
            "чтобы не замирать"
        val advice = if (line.level == Level.BAD || line.level == Level.IMPROVE) "Сначала исправьте скорость линии (С1) — " +
            "неровность уйдёт вместе с ней" else "Причина — у камеры или сети: проверьте нагрузку на сеть и Wi-Fi камеры; " +
            "ключевой кадр раз в 1 с и меньший битрейт тоже помогают"
        return when {
            late >= BAD_LATE_MS -> Check("С3", title, Level.BAD, text, advice)
            late >= SOME_LATE_MS -> Check("С3", title, Level.IMPROVE, text, advice)
            else -> Check("С3", title, Level.OK, "Кадры приходят ровно: самый поздний — на ${seconds(late)} с позже обычного")
        }
    }

    /** С4. Wi-Fi планшета. */
    private fun wifiCheck(input: DiagInput): Check {
        val title = "Wi-Fi планшета"
        val w = input.wifi ?: return Check("С4", title, Level.UNKNOWN, "Планшет не на Wi-Fi или сигнал не виден")
        val text = wifiText(w)
        val advice = "Планшет плохо слышит роутер — видео может запинаться. Подключите его к сети 5 ГГц или поставьте роутер " +
            "(или точку доступа) ближе"
        return when {
            w.rssi < BAD_RSSI -> Check("С4", title, Level.BAD, text, advice)
            w.rssi < WEAK_RSSI || (w.mhz in 1..2999 && w.mbps in 1 until SLOW_WIFI_MBPS) -> Check("С4", title, Level.IMPROVE, text, advice)
            else -> Check("С4", title, Level.OK, text)
        }
    }

    /** С5. Видео занимает почти всю линию — считаем, только если линия, похоже, 10 Мбит/с (С1). */
    private fun loadCheck(input: DiagInput, line: Check): Check {
        val title = "Нагрузка на линию камеры"
        val kbps = listOfNotNull(input.main?.kbps, input.sub?.kbps).takeIf { it.isNotEmpty() }?.sum()
            ?: return Check("С5", title, Level.UNKNOWN, "Мало данных")
        val text = "Камера шлёт ≈ ${mbps(kbps)} Мбит/с"
        if (line.level != Level.BAD && line.level != Level.IMPROVE) return Check("С5", title, Level.OK, "$text — линия справляется")
        val share = kbps / SLOW_LINE_KBPS.toDouble()
        val advice = "Это почти всё, что пропускает линия 10 Мбит/с. Лучше исправить линию (С1); если нельзя — снизьте битрейт " +
            "основного потока в настройках камеры"
        return when {
            share >= BAD_SHARE -> Check("С5", title, Level.BAD, "$text из ≈ 10", advice)
            share >= SOME_SHARE -> Check("С5", title, Level.IMPROVE, "$text из ≈ 10", advice)
            else -> Check("С5", title, Level.OK, "$text из ≈ 10")
        }
    }

    // ---- Камера ----

    /** К1. Ключевые кадры редко: видео дольше включается и восстанавливается. */
    private fun keyCheck(input: DiagInput): Check {
        val title = "Ключевые кадры"
        val fromStream = listOfNotNull(input.main?.keyIntervalMs, input.sub?.keyIntervalMs).maxOrNull()
        val fromCamera = input.encoder?.let { e -> if (e.gov > 0 && e.fps > 0) e.gov * 1000L / e.fps else null }
        val interval = fromStream ?: fromCamera ?: return Check("К1", title, Level.UNKNOWN, "Мало данных")
        val text = "Ключевой кадр раз в ${seconds(interval)} с"
        val advice = "Видео дольше включается и дольше восстанавливается после сбоя. В настройках камеры поставьте интервал " +
            "ключевых кадров (I-frame, GOP) равным частоте кадров — раз в 1 с"
        return when {
            interval > BAD_KEY_INTERVAL_MS -> Check("К1", title, Level.BAD, text, advice)
            interval > SOME_KEY_INTERVAL_MS -> Check("К1", title, Level.IMPROVE, text, advice)
            else -> Check("К1", title, Level.OK, text)
        }
    }

    /**
     * К2. Частота не делит частоту датчика: шаги меток 40 и 80 мс при 20 к/с на датчике 25. Совет — только частота,
     * которую камера разрешает ([Onvif.EncoderOptions]); нет такой — «особенность камеры», не «можно лучше».
     */
    private fun sensorCheck(input: DiagInput): Check {
        val title = "Частота кадров"
        val streams = listOfNotNull(input.main?.let { Triple(it, input.encoder?.options, "основной поток") },
            input.sub?.let { Triple(it, input.subEncoder?.options, "субпоток") })
            .filter { (s, _, _) -> s.baseStepMs != null && s.skipStepShare != null }
        if (streams.isEmpty()) return Check("К2", title, Level.UNKNOWN, "Мало данных")
        val uneven = streams.filter { (s, _, _) -> s.skipStepShare!! >= SKIP_STEP_SHARE && s.baseStepMs!! in SENSOR_STEPS_MS }
        if (uneven.isEmpty()) {
            val fps = streams.first().first.fps?.let { ", ${fpsText(it.toFloat())} к/с" } ?: ""
            return Check("К2", title, Level.OK, "Кадры идут ровно$fps")
        }
        val base = uneven.first().first.baseStepMs!!
        // Шаги округлены до 10 мс (33 мс у датчика 30 к/с — это 30): датчик — ближайшая обычная частота.
        val sensor = SENSOR_FPS.minBy { abs(1000.0 / base - it) }
        // Ровные частоты — датчик, делённый нацело (25 → 25; 30 → 30, 15, 10), не реже MIN_EVEN_FPS.
        val even = (1..6).filter { sensor % it == 0 }.map { sensor / it }.filter { it >= MIN_EVEN_FPS }
        val what = uneven.joinToString(" и ") { (s, _, name) -> "$name — ${s.fps?.let { fpsText(it.toFloat()) } ?: "меньше"} к/с" }
        val detail = "Камера снимает $sensor к/с, а отдаёт $what: лишние кадры выбрасываются неравномерно (шаги $base и " +
            "${2 * base} мс) — движение чуть подёргивается"
        val fits = uneven.map { (_, options, name) -> name to options?.let { o -> even.filter { o.allows(it) } } }
        if (fits.all { it.second?.isEmpty() == true }) {
            val limits = uneven.joinToString(", ") { (_, o, name) -> "$name — до ${o!!.fpsMax} к/с" }
            return Check("К2", title, Level.OK, "Особенность камеры: $detail. Ровной частоты у неё нет ($limits)")
        }
        val advice = if (fits.all { it.second != null }) "Поставьте в камере " +
            fits.filter { it.second!!.isNotEmpty() }.joinToString("; ") { (name, ok) -> "$name — ${ok!!.joinToString(" или ")} к/с" }
            else "Поставьте в камере частоту, на которую $sensor делится нацело: ${even.joinToString(" или ")} к/с — если камера позволяет"
        return Check("К2", title, Level.IMPROVE, detail, advice)
    }

    /** К3. Основной поток больше, чем тянет аппаратный декодер планшета. Совет — размер, который камера разрешает. */
    private fun sizeCheck(input: DiagInput): Check {
        val title = "Размер основного потока"
        val m = input.main
        val d = input.decoders
        if (m == null || m.width <= 0 || d == null) return Check("К3", title, Level.UNKNOWN, "Мало данных")
        val size = "${m.width}×${m.height}"
        if (d.hardware(VideoDepacketizer.Codec.H264, m.width, m.height)) {
            return Check("К3", title, Level.OK, "$size — планшет показывает его аппаратно")
        }
        val limit = d.maxSize(VideoDepacketizer.Codec.H264)?.let { "${it.first}×${it.second}" }
        val options = input.encoder?.options?.takeIf { it.resolutions.isNotEmpty() }
        val fitting = options?.resolutions?.filter { (w, h) -> d.hardware(VideoDepacketizer.Codec.H264, w, h) }
            ?.maxByOrNull { (w, h) -> w.toLong() * h }
        val advice = when {
            fitting != null -> "Поставьте основной поток ${fitting.first}×${fitting.second} — планшет будет показывать его аппаратно: " +
                "быстрее и без нагрева"
            options != null -> "У этой камеры основной поток бывает только ${options.resolutions.joinToString(", ") { "${it.first}×${it.second}" }}"
            else -> "Для HD поставьте основной поток не больше ${limit ?: "1920×1080"} — планшет будет показывать его аппаратно: " +
                "быстрее и без нагрева"
        }
        val shows = input.mainShows.takeLast(RECENT_SHOWS)
        if (input.mainUndecodable || (shows.isNotEmpty() && shows.none { it.keepsUp })) {
            return Check("К3", title, Level.BAD, "$size — планшет не успевает его показывать" +
                (shows.lastOrNull()?.let { ": показано ${fpsText(it.shownFps)} к/с" } ?: ""), advice)
        }
        val software = "$size — больше предела аппаратного декодера${limit?.let { " ($it)" } ?: ""}, планшет показывает его программно" +
            (shows.lastOrNull()?.let { " и успевает: ${fpsText(it.shownFps)} к/с" } ?: "")
        if (options != null && fitting == null) {
            return Check("К3", title, Level.OK, "Особенность камеры: $software. Меньшего размера у основного потока нет")
        }
        return Check("К3", title, Level.IMPROVE, software, advice)
    }

    /** К4. H.265 без аппаратного декодера нужного размера; камера без H.264 — особенность камеры. */
    private fun codecCheck(input: DiagInput): Check {
        val title = "Кодек"
        val m = input.main ?: return Check("К4", title, Level.UNKNOWN, "Мало данных")
        val codec = m.codec ?: return Check("К4", title, Level.UNKNOWN, "Мало данных")
        if (codec == VideoDepacketizer.Codec.H264) return Check("К4", title, Level.OK, "H.264 — его показывает любой планшет")
        val d = input.decoders ?: return Check("К4", title, Level.UNKNOWN, "H.265, декодеры планшета не известны")
        if (m.width > 0 && d.hardware(VideoDepacketizer.Codec.H265, m.width, m.height)) {
            return Check("К4", title, Level.OK, "H.265 — планшет показывает его аппаратно")
        }
        if (input.encoder?.options?.h264 == false) {
            return Check("К4", title, Level.OK, "Особенность камеры: H.265 — планшет показывает его только программно, H.264 у камеры нет")
        }
        val h264 = m.width > 0 && d.hardware(VideoDepacketizer.Codec.H264, m.width, m.height)
        return Check("К4", title, if (h264) Level.BAD else Level.IMPROVE,
            "H.265 — планшет показывает его только программно",
            "Переключите основной поток камеры на H.264" + if (h264) " — его планшет показывает аппаратно" else "")
    }

    /** К5. Субпоток не подходит плитке. */
    private fun subCheck(input: DiagInput): Check {
        val title = "Субпоток для плитки"
        if (input.subMissing) {
            return Check("К5", title, Level.IMPROVE, "Отдельного субпотока нет — плитка показывает основной поток",
                "Включите в камере второй поток (субпоток) 640×480 или 640×360 и укажите его в поле «Прямой поток для плитки»: " +
                    "меньше нагрузка на планшет и сеть")
        }
        val s = input.sub
        if (s == null || s.width <= 0) return Check("К5", title, Level.UNKNOWN, "Мало данных")
        if (s.height > MAX_SUB_HEIGHT) {
            val options = input.subEncoder?.options?.takeIf { it.resolutions.isNotEmpty() }
            val smaller = options?.resolutions?.filter { it.second in MIN_SUB_HEIGHT..MAX_SUB_HEIGHT }?.maxByOrNull { (w, h) -> w.toLong() * h }
            val text = "Субпоток ${s.width}×${s.height} — тяжеловат для плитки"
            if (options != null && smaller == null) return Check("К5", title, Level.OK, "Особенность камеры: $text, меньшего у неё нет")
            return Check("К5", title, Level.IMPROVE, text,
                "Для плитки хватит " + (smaller?.let { "${it.first}×${it.second}" } ?: "640×480 или 640×360") + " — меньше нагрузка на планшет и сеть")
        }
        return Check("К5", title, Level.OK, "Субпоток ${s.width}×${s.height}")
    }

    /** К6. Часы камеры уходят: время в архиве и событиях неверное. */
    private fun clockCheck(input: DiagInput): Check {
        val title = "Часы камеры"
        val off = input.clockOffsetMs ?: return Check("К6", title, Level.UNKNOWN, "Не проверено: камера не ответила по ONVIF")
        val text = "Часы камеры ${if (off > 0) "спешат" else "отстают"} на ${durationText(abs(off))} относительно планшета"
        val advice = "Время в архиве и событиях будет неверным. Включите в камере синхронизацию времени (NTP) и проверьте часовой пояс"
        return when {
            abs(off) > BAD_CLOCK_MS -> Check("К6", title, Level.BAD, text, advice)
            abs(off) > SOME_CLOCK_MS -> Check("К6", title, Level.IMPROVE, text, advice)
            else -> Check("К6", title, Level.OK, "Часы камеры идут вместе с планшетом")
        }
    }

    // ---- Планшет ----

    /** П1. Остановки видео на этом планшете. */
    private fun stallCheck(input: DiagInput): Check {
        val title = "Остановки видео"
        val shows = (input.mainShows + input.subShows).sortedBy { it.atMs }.takeLast(RECENT_SHOWS * 2)
        val seconds = shows.sumOf { it.seconds.toDouble() }
        if (shows.isEmpty() || seconds < MIN_SHOW_SECONDS) return Check("П1", title, Level.UNKNOWN, "Видео ещё мало показывалось")
        val perFive = shows.sumOf { it.longStalls } * 300 / seconds
        val text = "Остановок длиннее 0,2 с: ${String.format(Locale.US, "%.1f", perFive).replace('.', ',')} за 5 минут показа"
        val advice = "Смотрите советы выше: обычно причина — неровный приход (С3) или тяжёлый поток (К3)"
        return when {
            perFive > BAD_STALLS -> Check("П1", title, Level.BAD, text, advice)
            perFive > SOME_STALLS -> Check("П1", title, Level.IMPROVE, text, advice)
            else -> Check("П1", title, Level.OK, if (perFive == 0.0) "Остановок нет" else text)
        }
    }

    /** П2. Перегрев: Android замедляет планшет. */
    private fun heatCheck(input: DiagInput): Check {
        val title = "Нагрев планшета"
        val t = input.thermal ?: return Check("П2", title, Level.UNKNOWN, "Android не сообщает нагрев")
        val advice = "Android замедляет перегретый планшет. Уберите его с солнца, от батареи, дайте воздуху доступ к корпусу"
        return when {
            t >= PowerManager.THERMAL_STATUS_SEVERE -> Check("П2", title, Level.BAD, "Планшет перегрет", advice)
            t >= PowerManager.THERMAL_STATUS_MODERATE -> Check("П2", title, Level.IMPROVE, "Планшет заметно нагрет", advice)
            else -> Check("П2", title, Level.OK, "Нагрев в норме")
        }
    }

    /** П3. Основной поток не декодируется ни одним декодером планшета. */
    private fun decodeCheck(input: DiagInput): Check {
        val title = "Показ основного потока"
        if (input.mainUndecodable) {
            return Check("П3", title, Level.BAD, "Планшет не смог показать основной поток — в раскрытой плитке показан субпоток",
                "Смотрите «Размер основного потока» (К3) и «Кодек» (К4)")
        }
        val last = input.mainShows.lastOrNull() ?: return Check("П3", title, Level.UNKNOWN, "Основной поток ещё не показывался")
        return Check("П3", title, Level.OK, "Показан ${if (last.software) "программно" else "аппаратно"}, ${fpsText(last.shownFps)} к/с")
    }

    // ---- Отчёт ----

    /** Текст для поддержки: без адресов, логинов, паролей и токенов. */
    fun report(checks: List<Check>, facts: ReportFacts): String = buildString {
        appendLine("Диагностика камеры — SmartHomeDashboard ${facts.appVersion}")
        appendLine("Планшет: ${facts.tablet}")
        appendLine("Камера: ${facts.camera ?: "модель неизвестна"}")
        facts.main?.let { appendLine("Основной поток: ${streamText(it)}") }
        facts.sub?.let { appendLine("Субпоток: ${streamText(it)}") }
        facts.wifi?.let { appendLine("Wi-Fi планшета: ${wifiText(it)}") }
        appendLine("Итог: ${summary(checks) ?: "проверять пока нечего"}")
        appendLine()
        for (c in checks) {
            appendLine("${c.level.mark} ${c.id}. ${c.title}: ${c.detail}")
            if (c.advice.isNotBlank() && (c.level == Level.BAD || c.level == Level.IMPROVE)) appendLine("   Что сделать: ${c.advice}")
        }
    }.trimEnd()

    fun streamText(s: StreamSnapshot): String = listOfNotNull(
        s.codec?.let { if (it == VideoDepacketizer.Codec.H264) "H.264" else "H.265" },
        s.width.takeIf { it > 0 }?.let { "$it×${s.height}" },
        s.fps?.let { "${String.format(Locale.US, "%.0f", it)} к/с" },
        s.kbps?.let { "$it кбит/с" },
        s.keyIntervalMs?.let { "ключевой раз в ${seconds(it)} с" }
    ).joinToString(", ").ifBlank { "нет данных" }

    private fun wifiText(w: WifiFacts) = listOfNotNull(
        "${w.rssi} дБм",
        w.mhz.takeIf { it > 0 }?.let { if (it < 3000) "2,4 ГГц" else if (it < 5900) "5 ГГц" else "6 ГГц" },
        w.mbps.takeIf { it > 0 }?.let { "$it Мбит/с" }
    ).joinToString(", ")

    private fun seconds(ms: Long) = String.format(Locale.US, if (ms < 10_000) "%.2f" else "%.0f", ms / 1000.0)
        .trimEnd('0').trimEnd('.').replace('.', ',').ifBlank { "0" }
    private fun mb(kbPerSec: Long) = String.format(Locale.US, "%.1f", kbPerSec / 1024.0).replace('.', ',')
    private fun mbps(kbps: Long) = String.format(Locale.US, "%.1f", kbps / 1000.0).replace('.', ',')
    private fun fpsText(fps: Float) = String.format(Locale.US, "%.0f", fps)
    private fun percentText(p: Double) = (if (p < 0.1) String.format(Locale.US, "%.2f", p) else String.format(Locale.US, "%.1f", p))
        .replace('.', ',') + " %"
    private fun durationText(ms: Long) = when {
        ms < 120_000 -> "${ms / 1000} с"
        ms < 7_200_000 -> "${ms / 60_000} мин"
        else -> "${ms / 3_600_000} ч"
    }

    private const val MIN_KEYS = 3
    /** Быстрее — кадр пришёл одним куском (камера рядом), скорость не показываем. */
    private const val INSTANT_MS = 10L
    /** Ключевой кадр медленнее этого (КБ/с) — линия ≈ 10 Мбит/с (24.11: ≈ 1000 КБ/с против ≈ 3800 на 100 Мбит/с). */
    private const val SLOW_LINE_KB_PER_SEC = 1_300L
    /** Медленная линия заметна, когда ключевой кадр идёт дольше этого; от [BAD_KEY_MS] — «мешает». */
    private const val SLOW_KEY_MS = 120L
    private const val BAD_KEY_MS = 250L
    /** Столько идёт ключевой кадр по линии 100 Мбит/с — для оценки выигрыша. */
    private const val FAST_KEY_MS = 100L
    private const val SLOW_LINE_KBPS = 10_000L
    private const val SOME_SHARE = 0.6
    private const val BAD_SHARE = 0.8
    private const val MIN_PACKETS = 1_000L
    private const val SOME_LOSS = 0.1
    private const val BAD_LOSS = 1.0
    private const val SOME_LATE_MS = 300L
    private const val BAD_LATE_MS = 600L
    private const val WEAK_RSSI = -70
    private const val BAD_RSSI = -80
    private const val SLOW_WIFI_MBPS = 50
    private const val SOME_KEY_INTERVAL_MS = 2_000L
    private const val BAD_KEY_INTERVAL_MS = 5_000L
    private const val SKIP_STEP_SHARE = 0.10
    /** Шаг датчика 25–55 мс (18–40 к/с): у камер обычно 25 или 30 к/с. */
    private val SENSOR_STEPS_MS = 25..55
    /** Ровная частота реже этой — не совет (5 к/с с датчика 25 — не видео). */
    private const val MIN_EVEN_FPS = 10
    private val SENSOR_FPS = listOf(20, 25, 30)
    private const val MIN_SUB_HEIGHT = 240
    private const val MAX_SUB_HEIGHT = 720
    private const val SOME_CLOCK_MS = 5_000L
    private const val BAD_CLOCK_MS = 60_000L
    private const val RECENT_SHOWS = 3
    private const val MIN_SHOW_SECONDS = 20.0
    private const val SOME_STALLS = 3.0
    private const val BAD_STALLS = 10.0
}

/** Всё, по чему судят проверки. null — не известно (не измерено, не поддерживается). */
internal data class DiagInput(
    val main: StreamSnapshot? = null,
    val sub: StreamSnapshot? = null,
    /** Субпотока нет: поле пустое или совпадает с основным. */
    val subMissing: Boolean = false,
    val mainShows: List<ShowSummary> = emptyList(),
    val subShows: List<ShowSummary> = emptyList(),
    val mainUndecodable: Boolean = false,
    /** Настройки кодировщика основного потока и субпотока по ONVIF (с тем, что камера разрешает). */
    val encoder: Onvif.Stream? = null,
    val subEncoder: Onvif.Stream? = null,
    /** Часы камеры минус часы планшета, мс. */
    val clockOffsetMs: Long? = null,
    val wifi: WifiFacts? = null,
    /** Нагрев ([PowerManager.THERMAL_STATUS_NONE]…). */
    val thermal: Int? = null,
    val decoders: DecoderFacts? = null
)

internal data class WifiFacts(val rssi: Int, val mhz: Int, val mbps: Int)

internal data class ReportFacts(val appVersion: String, val tablet: String, val camera: String?, val main: StreamSnapshot?,
                                val sub: StreamSnapshot?, val wifi: WifiFacts?)

/** Аппаратные декодеры планшета: какой наибольший размер и берут ли они данный. */
internal class DecoderFacts(
    private val max: Map<VideoDepacketizer.Codec, Pair<Int, Int>>,
    private val check: (VideoDepacketizer.Codec, Int, Int) -> Boolean
) {
    fun maxSize(codec: VideoDepacketizer.Codec) = max[codec]
    fun hardware(codec: VideoDepacketizer.Codec, width: Int, height: Int) = check(codec, width, height)
}

/** Итог одного показа живого видео (пишет [DoorbellVideoView] при закрытии плеера). */
internal data class ShowSummary(
    val atMs: Long,
    val seconds: Float,
    val decoder: String,
    val software: Boolean,
    val width: Int,
    val height: Int,
    val shownFps: Float,
    /** Доля выброшенных и пропущенных кадров. */
    val droppedShare: Float,
    val longStalls: Int
) {
    /** Успевает: почти все кадры показаны и остановок не больше 3 за 30 с. */
    val keepsUp get() = droppedShare < 0.2f && longStalls * 30f / seconds.coerceAtLeast(1f) <= 3f
}

/** Последние показы по каждому потоку камеры (адрес RTSP камеры, не ретранслятора). Только в памяти. */
internal object PlaybackLog {
    private const val KEEP = 10
    private val shows = ConcurrentHashMap<String, List<ShowSummary>>()

    fun add(url: String, show: ShowSummary) {
        if (url.isBlank()) return
        shows.merge(url.trim(), listOf(show)) { old, new -> (old + new).takeLast(KEEP) }
    }

    fun recent(url: String): List<ShowSummary> = if (url.isBlank()) emptyList() else shows[url.trim()].orEmpty()

    internal fun clear() = shows.clear()
}

/** Сведения о планшете для проверок: Wi-Fi, нагрев, декодеры, модель. */
internal object DeviceFacts {
    fun wifi(context: Context): WifiFacts? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) null else {
            val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) caps.transportInfo as? WifiInfo
                else @Suppress("DEPRECATION") context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
            info?.takeIf { it.rssi > -127 && it.rssi < 0 }?.let { WifiFacts(it.rssi, it.frequency, it.linkSpeed) }
        }
    } catch (_: SecurityException) { null } catch (_: RuntimeException) { null }

    fun thermal(context: Context): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try { context.getSystemService(PowerManager::class.java)?.currentThermalStatus } catch (_: RuntimeException) { null }
    } else null

    fun decoders(): DecoderFacts {
        val list = try { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } } catch (_: RuntimeException) { emptyList() }
        fun hardware(codec: VideoDepacketizer.Codec) = list.filter { info ->
            info.supportedTypes.any { it.equals(codec.mime, ignoreCase = true) } &&
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
                else !DoorbellVideoView.isSoftwareDecoder(info.name)
        }.mapNotNull { info -> try { info.getCapabilitiesForType(codec.mime).videoCapabilities } catch (_: RuntimeException) { null } }
        val caps = VideoDepacketizer.Codec.entries.associateWith { hardware(it) }
        val supports = { codec: VideoDepacketizer.Codec, w: Int, h: Int -> caps[codec].orEmpty().any { it.isSizeSupported(w, h) || it.isSizeSupported(h, w) } }
        // Предел — наибольший обычный размер, который декодер берёт целиком: ширина и высота у него ограничены порознь
        // (Samsung SM-X216B: до 1920 и до 1920), но вместе — только 1920×1088.
        val max = caps.keys.mapNotNull { codec -> STANDARD_SIZES.firstOrNull { (w, h) -> supports(codec, w, h) }?.let { codec to it } }.toMap()
        return DecoderFacts(max, supports)
    }

    val tablet: String get() = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"

    /** Обычные размеры видео, от большего к меньшему: по ним пишется предел аппаратного декодера. */
    private val STANDARD_SIZES = listOf(3840 to 2160, 2560 to 1920, 2560 to 1440, 2304 to 1296, 1920 to 1080, 1280 to 720, 640 to 480)
}
