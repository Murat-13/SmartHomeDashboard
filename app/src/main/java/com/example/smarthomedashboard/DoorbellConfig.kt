package com.example.smarthomedashboard

import org.json.JSONObject

/**
 * Настройки плитки «Домофон» (`type = doorbell_modern`, docs/doorbell.md). Хранятся в `tile.config` под ключом [KEY];
 * размер и остальные ключи config не трогаются. Пароль RTSP — не здесь, а в защищённом хранилище
 * ([SecretStore.Slot.CAMERA_PASSWORD]). Настройки звука — свои на каждом планшете (у каждого своя копия плиток).
 */
internal data class DoorbellConfig(
    /** Сущность камеры HA: снимки, поток через HA (запасной путь и режим «снимок»). */
    val camera: String = "",
    /** Прямой поток с домофона (основной путь в домашней сети). Пусто — только через HA. */
    val rtspMain: String = "",
    /** Субпоток (низкое качество) для плитки; пусто — основной. */
    val rtspSub: String = "",
    val rtspUser: String = "",
    val mode: VideoMode = VideoMode.LIVE,
    /** Кнопка звонка (binary_sensor «visitor» у Reolink). */
    val visitor: String = "",
    val person: String = "",
    val animal: String = "",
    val vehicle: String = "",
    val motion: String = "",
    /** Замок/реле калитки: lock, switch, button, cover. Пусто — кнопки «Открыть» нет. */
    val lock: String = "",
    /** Свет у входа: light или switch. */
    val light: String = "",
    /** Готовые фразы домофона (select quick reply). */
    val quickReply: String = "",
    val autoCollapse: Boolean = true,
    val collapseSeconds: Int = 30,
    /** Своя мелодия (content:// из выбора файла); пусто — встроенная. */
    val ringTone: String = "",
    val ringVolume: Int = 70,
    /** Голос «Звонят в калитку» после мелодии. */
    val ringVoice: Boolean = true,
    val quietStartMin: Int = 23 * 60,
    val quietEndMin: Int = 7 * 60,
    val quietPercent: Int = 20,
    /** Яркость окна звонка в тихие часы, %. */
    val nightBrightness: Int = 40,
    /** Свет у входа при звонке после заката: включить и погасить через столько минут после закрытия окна. */
    val autoLight: Boolean = true,
    val lightOffMinutes: Int = 3,
    /** Звук при потере связи с домофоном. */
    val offlineSound: Boolean = true,
    /** Пропорция картинки (ширина/высота). По умолчанию 4:3; уточняется по реальному видео. */
    val aspect: Float = DEFAULT_ASPECT
) {
    enum class VideoMode(val key: String, val title: String) {
        LIVE("live", "Живое видео"), SNAPSHOT("snapshot", "Снимок раз в минуту");
        companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: LIVE }
    }

    /** Датчики событий для значков (в порядке показа). */
    val detections: List<Pair<String, Detection>> get() = listOf(
        person to Detection.PERSON, animal to Detection.ANIMAL, vehicle to Detection.VEHICLE, motion to Detection.MOTION
    ).filter { it.first.isNotBlank() }

    enum class Detection(val title: String) { PERSON("Человек"), ANIMAL("Животное"), VEHICLE("Машина"), MOTION("Движение") }

    /** Сущности HA, от которых зависит плитка (подписки панели). `sun.sun` — для света после заката. */
    val entityIds: Set<String> get() = listOf(camera, visitor, person, animal, vehicle, motion, lock, light, quickReply,
        if (autoLight && light.isNotBlank()) SUN else "").filter { it.isNotBlank() }.toCollection(LinkedHashSet())

    val hasVideo get() = camera.isNotBlank() || rtspMain.isNotBlank() || rtspSub.isNotBlank()

    fun isQuiet(minuteOfDay: Int) = VoiceAlertSettings(quietStartMin = quietStartMin, quietEndMin = quietEndMin).isQuiet(minuteOfDay)

    fun ringVolumeAt(minuteOfDay: Int): Float {
        val base = ringVolume.coerceIn(0, 100) / 100f
        return if (isQuiet(minuteOfDay)) base * quietPercent.coerceIn(0, 100) / 100f else base
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("camera", camera); put("rtsp_main", rtspMain); put("rtsp_sub", rtspSub); put("rtsp_user", rtspUser)
        put("mode", mode.key)
        put("visitor", visitor); put("person", person); put("animal", animal); put("vehicle", vehicle); put("motion", motion)
        put("lock", lock); put("light", light); put("quick_reply", quickReply)
        put("auto_collapse", autoCollapse); put("collapse_seconds", collapseSeconds)
        put("ring_tone", ringTone); put("ring_volume", ringVolume); put("ring_voice", ringVoice)
        put("quiet_start", quietStartMin); put("quiet_end", quietEndMin); put("quiet_percent", quietPercent)
        put("night_brightness", nightBrightness); put("auto_light", autoLight); put("light_off_minutes", lightOffMinutes)
        put("offline_sound", offlineSound); put("aspect", aspect.toDouble())
    }

    fun writeTo(config: String): String {
        val json = try { JSONObject(config) } catch (_: Exception) { JSONObject() }
        return json.put(KEY, toJson()).toString()
    }

    companion object {
        const val TYPE = "doorbell_modern"
        const val KEY = "doorbell_modern"
        const val VERSION = 1
        const val DEFAULT_TITLE = "Домофон"
        const val DEFAULT_ASPECT = 4f / 3f
        const val DEFAULT_WIDTH = 480
        const val SUN = "sun.sun"

        fun from(config: String): DoorbellConfig {
            val json = try { JSONObject(config).optJSONObject(KEY) } catch (_: Exception) { null } ?: return DoorbellConfig()
            return fromJson(json)
        }

        fun fromJson(j: JSONObject): DoorbellConfig {
            val d = DoorbellConfig()
            fun str(key: String) = j.optString(key).trim()
            fun int(key: String, default: Int, range: IntRange) = j.optInt(key, default).takeIf { it in range } ?: default
            return DoorbellConfig(
                camera = str("camera"), rtspMain = str("rtsp_main"), rtspSub = str("rtsp_sub"), rtspUser = str("rtsp_user"),
                mode = VideoMode.of(j.optString("mode")),
                visitor = str("visitor"), person = str("person"), animal = str("animal"), vehicle = str("vehicle"),
                motion = str("motion"), lock = str("lock"), light = str("light"), quickReply = str("quick_reply"),
                autoCollapse = j.optBoolean("auto_collapse", d.autoCollapse),
                collapseSeconds = int("collapse_seconds", d.collapseSeconds, 5..3600),
                ringTone = str("ring_tone"), ringVolume = int("ring_volume", d.ringVolume, 0..100),
                ringVoice = j.optBoolean("ring_voice", d.ringVoice),
                quietStartMin = int("quiet_start", d.quietStartMin, 0 until 1440),
                quietEndMin = int("quiet_end", d.quietEndMin, 0 until 1440),
                quietPercent = int("quiet_percent", d.quietPercent, 0..100),
                nightBrightness = int("night_brightness", d.nightBrightness, 5..100),
                autoLight = j.optBoolean("auto_light", d.autoLight),
                lightOffMinutes = int("light_off_minutes", d.lightOffMinutes, 0..120),
                offlineSound = j.optBoolean("offline_sound", d.offlineSound),
                aspect = j.optDouble("aspect", DEFAULT_ASPECT.toDouble()).toFloat().takeIf { it.isFinite() && it in 0.3f..4f }
                    ?: DEFAULT_ASPECT
            )
        }

        /** Новая плитка: 4:3, шаблон без источников; уже заданный размер и посторонние ключи сохраняются. */
        fun newTileConfig(existing: String = "{}"): String {
            val json = try { JSONObject(existing) } catch (_: Exception) { JSONObject() }
            if (!json.has("button_size")) json.put("button_size", DEFAULT_WIDTH)
            if (!json.has("widget_height")) json.put("widget_height", (DEFAULT_WIDTH / DEFAULT_ASPECT).toInt())
            if (!json.has(KEY)) json.put(KEY, DoorbellConfig().toJson())
            return json.toString()
        }

        /** Плитка всегда в пропорции видео внутри сохранённого прямоугольника; config не переписывается. */
        fun fit(width: Int, height: Int, aspect: Float): Pair<Int, Int> {
            val a = aspect.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_ASPECT
            val w = width.coerceAtLeast((MIN_HEIGHT * a).toInt())
            val h = height.coerceAtLeast(MIN_HEIGHT)
            return if (w >= h * a) (h * a).toInt() to h else w to (w / a).toInt()
        }

        const val MIN_HEIGHT = 120

        /**
         * Логин и пароль, вставленные прямо в адрес (`rtsp://user:pass@host/...`): адрес без них, логин, пароль.
         * Пароль не должен оставаться в config открытым текстом (и в резервных копиях).
         */
        fun splitCredentials(url: String): Triple<String, String?, String?> {
            val scheme = url.substringBefore("://", "")
            if (scheme.isEmpty()) return Triple(url, null, null)
            val rest = url.substringAfter("://")
            val authority = rest.substringBefore("/")
            if ("@" !in authority) return Triple(url, null, null)
            val info = authority.substringBeforeLast("@")
            fun dec(v: String) = try { java.net.URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { v }
            val user = dec(info.substringBefore(":")).takeIf { it.isNotEmpty() }
            val pass = if (":" in info) dec(info.substringAfter(":")) else null
            return Triple("$scheme://" + rest.substring(info.length + 1), user, pass)
        }

        /** RTSP-адрес с логином/паролем для плеера. Пароль в config не хранится. */
        fun withCredentials(url: String, user: String, password: String?): String {
            if (user.isBlank() || url.isBlank() || "@" in url.substringAfter("://").substringBefore("/")) return url
            val scheme = url.substringBefore("://", "")
            if (scheme.isEmpty()) return url
            fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
            val auth = enc(user) + (password?.takeIf { it.isNotEmpty() }?.let { ":" + enc(it) } ?: "")
            return "$scheme://$auth@" + url.substringAfter("://")
        }
    }
}
