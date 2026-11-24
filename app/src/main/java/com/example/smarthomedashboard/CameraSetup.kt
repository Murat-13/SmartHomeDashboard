package com.example.smarthomedashboard

import java.io.IOException

/**
 * Найденная камера → готовые настройки плитки (docs/setup-tz.md, часть 3): ONVIF, а у Reolink без ONVIF — её обычные
 * ссылки RTSP, проверенные подключением. Камере ничего не меняется. Блокирующее — рабочий поток.
 */
internal object CameraSetup {
    /** Не вышло; [auth] — камера ответила, но логин или пароль не приняла. Текст показывается человеку. */
    class Failed(message: String, val auth: Boolean = false) : IOException(message)

    class Ready(val config: DoorbellConfig, val title: String, val info: Onvif.Info?)

    internal var probe: (String, Int, String, String) -> Onvif.Info = Onvif::probe
    /** Поток отвечает: подключиться, найти видео, закрыть. */
    internal var checkRtsp: (String, String, String) -> Unit = { url, user, password ->
        RtspSession(url, user, password).use { session -> session.open { it.isVideo } }
    }

    fun connect(found: CameraFound, user: String, password: String): Ready {
        val login = user.trim().ifEmpty { DEFAULT_USER }
        val onvif: Exception = try {
            val info = probe(found.host, found.onvifPort, login, password)
            val main = info.main?.uri.orEmpty()
            if (main.isBlank()) throw Onvif.OnvifException("Камера не дала ссылки на видео")
            return Ready(DoorbellConfig(host = found.host, onvifPort = info.port, model = info.title.take(MAX_MODEL), rtspUser = login,
                rtspMain = main, rtspSub = info.sub?.uri.orEmpty()), found.title, info)
        } catch (e: Onvif.OnvifException) {
            if (e.auth) throw Failed("Камера не приняла логин или пароль", auth = true)
            e
        } catch (e: IOException) { e }
        if (found.brand == CameraBrand.REOLINK) return reolink(found, login, password)
        throw Failed(if (onvif is Onvif.OnvifException && found.onvifPort > 0) onvif.message.orEmpty() else
            "Камера не ответила по ONVIF. Включите ONVIF в её настройках" + (if (found.brand == CameraBrand.OTHER) " или введите ссылки RTSP в редакторе плитки" else ""))
    }

    /** Reolink без ONVIF: обычные ссылки обоих потоков, если камера отдаёт по ним видео. */
    private fun reolink(found: CameraFound, user: String, password: String): Ready {
        val main = "rtsp://${found.host}:554/h264Preview_01_main"
        val sub = "rtsp://${found.host}:554/h264Preview_01_sub"
        try {
            checkRtsp(sub, user, password)
        } catch (e: RtspException) {
            throw if (e.status == 401) Failed("Камера не приняла логин или пароль", auth = true) else Failed("Камера не отдаёт видео: ${e.message}")
        } catch (_: IOException) {
            throw Failed("Камера Reolink найдена, но RTSP и ONVIF у неё выключены (или модель их не умеет)")
        }
        return Ready(DoorbellConfig(host = found.host, model = listOf("Reolink", found.model.takeUnless { it.contains("reolink", true) }.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" ").take(MAX_MODEL), rtspUser = user, rtspMain = main, rtspSub = sub), found.title, null)
    }

    /** Чем этот планшет покажет основной поток — по настройкам камеры, без подключения. "" — камера не сообщила размер. */
    fun decoderNote(info: Onvif.Info?, decoders: DecoderFacts): String {
        val main = info?.main?.takeIf { it.width > 0 && it.height > 0 } ?: return ""
        val codec = when {
            main.encoding.contains("265") || main.encoding.contains("HEVC", true) -> VideoDepacketizer.Codec.H265
            main.encoding.contains("264") || main.encoding.contains("AVC", true) -> VideoDepacketizer.Codec.H264
            else -> return "Основной поток ${main.width}×${main.height}, ${main.encoding}: эта панель показывает H.264 и H.265"
        }
        val name = "${main.width}×${main.height} ${if (codec == VideoDepacketizer.Codec.H265) "H.265" else "H.264"}" + (if (main.fps > 0) ", ${main.fps} к/с" else "")
        if (decoders.hardware(codec, main.width, main.height)) return "Основной поток $name — планшет справится"
        val limit = decoders.maxSize(codec) ?: return "Основной поток $name — этот планшет покажет субпоток"
        return "Основной поток $name — больше предела аппаратного декодера этого планшета (${limit.first}×${limit.second}): покажет программно или субпотоком"
    }

    const val DEFAULT_USER = "admin"
    private const val MAX_MODEL = 80
}

/**
 * «Где включить» по маркам — только то, что подтверждено официальными страницами и руководствами (docs/setup-tz.md, «Этап 3»);
 * названия пунктов — как в самих приложениях и на страницах камер.
 */
internal object CameraHints {
    fun of(brand: CameraBrand): String = TEXTS[brand]?.let { "$it $COMMON" } ?: COMMON

    private const val COMMON = "Логин и пароль — самой камеры, а не облачного приложения."
    private val TEXTS = mapOf(
        CameraBrand.REOLINK to "Reolink: в приложении — шестерёнка → More Features → Advanced Network Settings → Server Settings, включите RTSP и ONVIF. " +
            "У камер с батареей и с 4G этих портов нет.",
        CameraBrand.HIKVISION to "Hikvision: на веб-странице камеры — Configuration → Network → Advanced Settings → Integration Protocol (в новых " +
            "прошивках Network → Platform Access → ONVIF). Включите ONVIF и добавьте пользователя ONVIF — он отдельный от пользователя камеры.",
        CameraBrand.DAHUA to "Dahua: на веб-странице камеры включите ONVIF (Network → Platform Access или System → Safety → System Service). Пользователь " +
            "ONVIF — отдельный: System → Account → ONVIF User.",
        CameraBrand.TAPO to "Tapo: в приложении — «Настройки устройства» → «Расширенные настройки» → «Учётная запись камеры». Создайте её: логин и пароль " +
            "нужны от неё, не от TP-Link ID. Камеры с батареей не поддерживаются.",
        CameraBrand.EZVIZ to "EZVIZ: логин admin, пароль — код с наклейки камеры (6 заглавных букв) или местный пароль, если его задавали. ONVIF есть " +
            "только у части моделей, у видеозвонков его нет.",
        CameraBrand.IMOU to "Imou: у камер с батареей ONVIF и RTSP нет, у остальных — зависит от модели.",
        CameraBrand.UNIVIEW to "Uniview: логин и пароль — от веб-страницы камеры.",
        CameraBrand.AXIS to "Axis: на веб-странице камеры — System → Accounts → ONVIF accounts. Пока пользователя ONVIF не создали, ONVIF выключен.",
        CameraBrand.AMCREST to "Amcrest: на веб-странице камеры — Setup → Network → Connection → ONVIF, включите. Логин — самой камеры."
    )
}
