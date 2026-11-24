package com.example.smarthomedashboard

import android.content.Context
import android.util.Log
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * Сбор сведений для диагностики камеры ([CameraDiagnostics], docs/diagnostics-tz.md):
 * - [passive] — без сети: сводка общего потока (если запись на этом планшете его держит), показы видео, планшет;
 * - [full] — по кнопке «Проверить сейчас»: оба потока по [seconds] с (общий поток — свой, нет его — своё подключение
 *   только на время проверки), настройки кодировщика и часы камеры по ONVIF.
 * Камере ничего не пишется. В журнал — без адресов и паролей.
 */
internal object CameraDiagnosis {
    /** Что проверяем: потоки (как их открывает плитка), логин, пароль, узел и порт ONVIF. */
    data class Target(val main: String, val sub: String, val user: String, val password: String?, val host: String, val onvifPort: Int)

    class Result(val input: DiagInput, val checks: List<CameraDiagnostics.Check>, val camera: String?)

    fun target(config: DoorbellConfig, password: String?): Target {
        val main = DoorbellConfig.playableRtsp(config.rtspMain).trim()
        val sub = DoorbellConfig.playableRtsp(config.rtspSub).trim()
        val host = config.host.trim().ifBlank { hostOf(main.ifBlank { sub }) }
        return Target(main, sub, config.rtspUser, password, host, config.onvifPort)
    }

    private fun hostOf(url: String): String = try { URI(url).host.orEmpty() } catch (_: Exception) { "" }

    fun passive(context: Context, t: Target): Result {
        val main = t.main.takeIf { it.isNotBlank() }?.let { CameraHub.stats(it, t.user) }
        val sub = t.sub.takeIf { it.isNotBlank() && it != t.main }?.let { CameraHub.stats(it, t.user) }
        return result(context, t, main, sub, encoder = null, clock = null, camera = null)
    }

    /**
     * Полная проверка (блокирующая, рабочий поток). [progress] — сколько секунд осталось; [cancelled] — экран закрыт.
     * Ошибка подключения к потоку — не исключение: та проверка остаётся «мало данных».
     */
    fun full(context: Context, t: Target, seconds: Int, cancelled: () -> Boolean, progress: (Int) -> Unit): Result {
        val mainResult = AtomicReference<StreamSnapshot?>()
        val subResult = AtomicReference<StreamSnapshot?>()
        val onvif = AtomicReference<Onvif.Info?>()
        val clock = AtomicReference<Long?>()
        val threads = mutableListOf<Thread>()
        fun later(name: String, block: () -> Unit) = Thread({
            try { block() } catch (e: Exception) { Log.w(TAG, "$name: ${e.javaClass.simpleName}") }
        }, "diag-$name").apply { isDaemon = true; start(); threads += this }
        val shared = { url: String -> CameraHub.stats(url, t.user)?.takeIf { it.observedMs >= seconds * 1000L } }
        if (t.main.isNotBlank()) later("main") { mainResult.set(shared(t.main) ?: watch(t.main, t, seconds, cancelled)) }
        if (t.sub.isNotBlank() && t.sub != t.main) later("sub") { subResult.set(shared(t.sub) ?: watch(t.sub, t, seconds, cancelled)) }
        if (t.host.isNotBlank()) {
            later("onvif") {
                if (!t.password.isNullOrEmpty()) onvif.set(Onvif.probe(t.host, t.onvifPort, t.user.ifBlank { "admin" }, t.password))
                clock.set(onvif.get()?.clockOffsetMs ?: Onvif.clock(t.host, onvif.get()?.port ?: t.onvifPort))
            }
        }
        val until = System.currentTimeMillis() + seconds * 1000L + WAIT_EXTRA_MS
        while (threads.any { it.isAlive } && !cancelled() && System.currentTimeMillis() < until) {
            progress(((until - WAIT_EXTRA_MS - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0))
            try { Thread.sleep(PROGRESS_MS) } catch (_: InterruptedException) { break }
        }
        val info = onvif.get()
        val encoder = info?.main?.takeIf { it.fps > 0 || it.gov > 0 }
        return result(context, t, mainResult.get(), subResult.get(), encoder, clock.get(),
            info?.let { listOf(it.title, it.firmware.takeIf { f -> f.isNotBlank() }?.let { f -> "прошивка $f" }).filterNotNull().joinToString(", ") })
    }

    /** Свой поток для проверки, если общего нет; общий есть, но короче проверки — ждём его. */
    private fun watch(url: String, t: Target, seconds: Int, cancelled: () -> Boolean): StreamSnapshot? {
        if (CameraHub.stats(url, t.user) != null) {
            val until = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < until && !cancelled()) Thread.sleep(PROGRESS_MS)
            return CameraHub.stats(url, t.user)
        }
        return StreamProbe.run(url, t.user, t.password, seconds, cancelled)
    }

    private fun result(context: Context, t: Target, main: StreamSnapshot?, sub: StreamSnapshot?, encoder: Onvif.Stream?,
                       clock: Long?, camera: String?): Result {
        val input = DiagInput(
            main = main, sub = sub,
            subMissing = t.main.isNotBlank() && (t.sub.isBlank() || t.sub == t.main),
            mainShows = PlaybackLog.recent(t.main), subShows = if (t.sub != t.main) PlaybackLog.recent(t.sub) else emptyList(),
            mainUndecodable = t.main.isNotBlank() && t.main in DoorbellVideoView.undecodable,
            encoder = encoder, clockOffsetMs = clock,
            wifi = DeviceFacts.wifi(context), thermal = DeviceFacts.thermal(context), decoders = decoders()
        )
        return Result(input, CameraDiagnostics.evaluate(input), camera)
    }

    fun report(context: Context, result: Result): String {
        val version = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }
        return CameraDiagnostics.report(result.checks, ReportFacts(version, DeviceFacts.tablet, result.camera, result.input.main,
            result.input.sub, result.input.wifi))
    }

    /** Декодеры планшета не меняются, пока работает приложение. */
    @Volatile private var cachedDecoders: DecoderFacts? = null
    private fun decoders(): DecoderFacts = cachedDecoders ?: DeviceFacts.decoders().also { cachedDecoders = it }

    private const val TAG = "CameraDiagnosis"
    private const val PROGRESS_MS = 500L
    /** Подключение к камере и ONVIF сверх времени проверки. */
    private const val WAIT_EXTRA_MS = 15_000L
}
