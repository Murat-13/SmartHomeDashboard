package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity

/**
 * Источник видео камеры (этап 4, docs/recording.md) — общее для плитки «Домофон» и будущей плитки камеры (этап 5):
 * потоки RTSP, логин (пароль — в [SecretStore.Slot.CAMERA_PASSWORD]), камера HA и датчики событий по ключам
 * [RecordingConfig.TRIGGERS]. Хранится в настройках своей плитки в прежнем формате; здесь — только чтение.
 */
internal data class CameraSource(
    val haCamera: String,
    val rtspMain: String,
    val rtspSub: String,
    val rtspUser: String,
    /** Ключ события ([RecordingConfig.TRIGGERS]) → сущность HA (пусто — не задано). */
    val events: Map<String, String>,
    /** Ключ пароля в [CameraSecrets] (номер плитки «Камера»); пусто — пароль домофона. */
    val secretKey: String = ""
) {
    /** Круглосуточная запись и плитка: субпоток, если задан. */
    val subUrl get() = rtspSub.ifBlank { rtspMain }
    /** Записи событий и раскрытие: основной поток, если задан. */
    val mainUrl get() = rtspMain.ifBlank { rtspSub }

    companion object {
        /** Типы плиток с камерой. */
        val TYPES = setOf(DoorbellConfig.TYPE, DoorbellConfig.CAMERA_TYPE)

        fun of(tile: TileEntity): CameraSource? = when (tile.type) {
            DoorbellConfig.TYPE -> DoorbellConfig.from(tile.config).source
            // Плитка «Камера» (docs/camera-tz.md): пароль — свой у плитки (CameraSecrets).
            DoorbellConfig.CAMERA_TYPE -> DoorbellConfig.from(tile.config).source.copy(secretKey = tile.id)
            else -> null
        }
    }
}
