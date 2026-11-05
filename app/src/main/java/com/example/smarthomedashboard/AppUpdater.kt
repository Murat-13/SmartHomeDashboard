package com.example.smarthomedashboard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import androidx.core.content.edit

/**
 * HTTP к GitHub для обновлений. Весь путь, включая переадресации на CDN, — только HTTPS на домены GitHub:
 * `network_security_config` разрешает HTTP глобально ради локального HA, поэтому ограничение — здесь, в коде.
 */
internal class UpdateHttp(
    client: OkHttpClient,
    private val releasesUrl: String = RELEASES_URL,
    /** false — только для тестов на локальном MockWebServer. */
    private val requireHttps: Boolean = true,
) {
    class HttpStatus(val code: Int) : IOException("HTTP $code")

    private val http = client.newBuilder()
        .followSslRedirects(false) // переадресация с HTTPS на HTTP не выполняется
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .addNetworkInterceptor { chain ->
            ensureAllowed(chain.request().url)
            chain.proceed(chain.request())
        }
        .build()

    private fun ensureAllowed(url: HttpUrl) {
        if (requireHttps && !(url.isHttps && trusted(url.host))) throw IOException("Адрес загрузки не HTTPS GitHub")
    }

    private fun request(url: String): Request {
        val parsed = url.toHttpUrlOrNull() ?: throw IOException("Неверный адрес")
        ensureAllowed(parsed)
        return Request.Builder().url(parsed)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SmartHomeDashboard-updater")
            .build()
    }

    /** Список выпусков (JSON), не больше 2 МБ. */
    fun releases(): String = text(releasesUrl, 2_000_000)

    fun text(url: String, maxBytes: Long = 4096): String =
        http.newCall(request(url)).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatus(response.code)
            response.peekBody(maxBytes).string()
        }

    /** Скачивает [url] в [target], не больше [maxBytes]; возвращает SHA-256 (hex) скачанного. */
    fun download(url: String, target: File, maxBytes: Long): String =
        http.newCall(request(url)).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatus(response.code)
            val body = response.body ?: throw IOException("Пустой ответ")
            if (body.contentLength() > maxBytes) throw IOException("Файл слишком большой")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw IOException("Файл слишком большой")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            hex(digest.digest())
        }

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/Murat-13/SmartHomeDashboard/releases?per_page=30"

        fun trusted(host: String) = host == "github.com" || host.endsWith(".github.com") ||
            host.endsWith(".githubusercontent.com")

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Пакет, версия, отпечатки (SHA-256) сертификатов подписи и признак отладочной сборки
 * (`ApplicationInfo.FLAG_DEBUGGABLE`): debug при наличии ключа подписан тем же ключом, что release.
 */
internal data class ApkFacts(val packageName: String, val versionCode: Long, val signers: Set<String>,
                             val debuggable: Boolean = false)

/** Сведения об установленном приложении и о скачанном APK. */
internal interface ApkCheck {
    fun installed(): ApkFacts?
    fun archive(file: File): ApkFacts?
}

internal class PackageApkCheck(private val context: Context) : ApkCheck {
    private val pm get() = context.packageManager

    override fun installed() = runCatching { facts(pm.getPackageInfo(context.packageName, FLAGS)) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun PackageManager.archiveInfo(file: File) = getPackageArchiveInfo(file.absolutePath, FLAGS)

    override fun archive(file: File) = runCatching { pm.archiveInfo(file)?.let(::facts) }.getOrNull()

    /** Нет `applicationInfo` — свойства файла не определить: такой APK считается непригодным (null). */
    @Suppress("DEPRECATION")
    private fun facts(info: PackageInfo): ApkFacts? {
        val app = info.applicationInfo ?: return null
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else info.signatures
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val signers = signatures.orEmpty().map { UpdateHttp.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
        return ApkFacts(info.packageName, code, signers.toSet(), (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }

    private companion object {
        @Suppress("DEPRECATION")
        val FLAGS = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    }
}

/**
 * Проверка обновлений на GitHub Releases: выбор по каналу → загрузка → SHA-256 → пакет, версия и подпись
 * совпадают с установленным приложением. Результат — готовый к установке файл во внутренней папке без
 * резервного копирования Android. Установка — отдельно (этап 09в), перед ней — [AutoBackup.beforeUpdate].
 * Ничего не бросает: сбой сети, GitHub, места или подписи становится статусом и не мешает панели.
 */
internal object AppUpdater {
    const val PREF_CHANNEL = "update_channel"
    const val PREF_STATE = "update_state"
    const val PREF_MESSAGE = "update_message"
    const val PREF_VERSION_NAME = "update_version_name"
    const val PREF_VERSION_CODE = "update_version_code"
    const val PREF_SHA = "update_sha256"
    const val PREF_NOTES = "update_notes"
    const val PREF_LAST_CHECK = "update_last_check"
    /** «код:хэш» выпуска, отвергнутого по пакету/версии/подписи: тот же файл повторно не скачивается. */
    const val PREF_REJECTED = "update_rejected"
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    const val SPACE_MARGIN = 20L * 1024 * 1024
    const val CHECK_HOURS = 6L
    private const val WORK = "update_check"

    enum class State { NONE, UP_TO_DATE, READY, ERROR }

    data class Status(
        val state: State,
        val message: String,
        val versionName: String? = null,
        val versionCode: Long = 0,
        val lastCheck: Long = 0,
        val notes: String = "",
    )

    class Deps(
        val http: UpdateHttp,
        val apk: ApkCheck,
        val dir: File,
        val freeBytes: (File) -> Long = { it.usableSpace },
    )

    private fun defaultDeps(context: Context) = Deps(UpdateHttp(OkHttpClient()), PackageApkCheck(context.applicationContext),
        File(context.noBackupFilesDir, "updates"))

    /** Только для тестов экранов: без обращений к настоящему GitHub. */
    @androidx.annotation.VisibleForTesting
    internal var depsFactory: (Context) -> Deps = ::defaultDeps

    fun deps(context: Context) = depsFactory(context)

    private fun prefs(context: Context) = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    fun channel(context: Context) = UpdateChannel.from(prefs(context).getString(PREF_CHANNEL, null))

    fun setChannel(context: Context, channel: UpdateChannel) {
        prefs(context).edit { putString(PREF_CHANNEL, channel.pref) }
    }

    fun status(context: Context): Status {
        val prefs = prefs(context)
        val state = runCatching { State.valueOf(prefs.getString(PREF_STATE, null) ?: "NONE") }.getOrDefault(State.NONE)
        return Status(state, prefs.getString(PREF_MESSAGE, "").orEmpty(), prefs.getString(PREF_VERSION_NAME, null),
            prefs.getLong(PREF_VERSION_CODE, 0), prefs.getLong(PREF_LAST_CHECK, 0), prefs.getString(PREF_NOTES, "").orEmpty())
    }

    /**
     * Готовый файл для установки; null — нет, изменился после проверки или больше не проходит проверку
     * ([problemOf]): ранее скачанный файл (в том числе принятый старой версией без новых правил) каждый раз
     * проверяется заново и при отказе отвергается так же, как свежая загрузка.
     */
    fun readyFile(context: Context, deps: Deps = deps(context)): File? {
        val status = status(context)
        if (status.state != State.READY) return null
        val file = File(deps.dir, apkName(status.versionCode))
        val sha = prefs(context).getString(PREF_SHA, null) ?: return null
        if (!file.isFile || runCatching { sha256(file) }.getOrNull() != sha) return null
        val installed = deps.apk.installed() ?: return null
        val problem = problemOf(deps.apk.archive(file), installed, status.versionCode, status.versionName.orEmpty())
        if (problem != null) {
            reject(context, file, "${status.versionCode}:$sha", status.versionName.orEmpty(), status.versionCode, problem,
                System.currentTimeMillis())
            return null
        }
        return file
    }

    /** Причина, по которой скачанный APK нельзя ставить; null — пригоден. */
    private fun problemOf(facts: ApkFacts?, installed: ApkFacts, versionCode: Long, versionName: String): String? = when {
        facts == null -> "скачанный файл не является приложением"
        facts.packageName != installed.packageName -> "файл от другого приложения"
        facts.debuggable -> "Отладочная сборка не устанавливается"
        facts.versionCode != versionCode -> "версия в файле не совпадает с выпуском $versionName"
        facts.signers.isEmpty() || facts.signers != installed.signers -> "подпись $versionName не совпадает с установленным приложением"
        else -> null
    }

    /** Отказ: файл удаляется, тот же «код:хэш» повторно не скачивается, причина — в статусе и журнале. */
    private fun reject(context: Context, file: File, rejection: String, versionName: String, versionCode: Long,
                       problem: String, now: Long): Status {
        file.delete()
        prefs(context).edit { putString(PREF_REJECTED, rejection) }
        UpdateLog.add(context, "Выпуск $versionName отвергнут: $problem", now)
        return save(context, Status(State.ERROR, problem, versionName, versionCode, now), sha = null)
    }

    private val lock = ReentrantLock()

    /** Одна проверка. Параллельный вызов (фон + кнопка) не запускает вторую загрузку, а возвращает текущий статус. */
    fun check(context: Context, deps: Deps = deps(context), now: Long = System.currentTimeMillis()): Status {
        if (!lock.tryLock()) return status(context)
        return try {
            runCheck(context, deps, now)
        } catch (_: Exception) {
            fail(context, now, "непредвиденная ошибка проверки")
        } finally {
            lock.unlock()
        }
    }

    private fun runCheck(context: Context, deps: Deps, now: Long): Status {
        val prefs = prefs(context)
        val installed = deps.apk.installed() ?: return fail(context, now, "не удалось прочитать установленную версию")
        val previous = status(context)
        val releases = try {
            AppReleases.parse(deps.http.releases())
        } catch (e: IOException) {
            // Уже скачанное и проверенное обновление сбой связи не отменяет.
            if (previous.state == State.READY && previous.versionCode > installed.versionCode && readyFile(context, deps) != null) {
                prefs.edit { putLong(PREF_LAST_CHECK, now) }
                return status(context)
            }
            return fail(context, now, when ((e as? UpdateHttp.HttpStatus)?.code) {
                null -> "нет связи с GitHub"
                403, 429 -> "GitHub временно ограничил запросы"
                404 -> "выпуски на GitHub не найдены"
                else -> "GitHub ответил ошибкой ${e.code}"
            })
        }
        val release = AppReleases.pick(releases, channel(context), installed.versionCode)
        if (release == null) {
            clean(deps.dir)
            return save(context, Status(State.UP_TO_DATE, "установлена последняя версия", lastCheck = now), sha = null)
        }
        if (release.apkSize > MAX_APK_BYTES) return fail(context, now, "файл обновления ${release.versionName} слишком большой")
        val expected = release.sha256 ?: release.sha256Url?.let { url ->
            runCatching { AppReleases.parseSha256File(deps.http.text(url)) }.getOrNull()
        } ?: return fail(context, now, "в выпуске ${release.versionName} нет SHA-256")

        val file = File(deps.dir, apkName(release.versionCode))
        val ready = Status(State.READY, "готово к установке", release.versionName, release.versionCode, now, release.notes)
        // Уже скачанный файл повторно проверяется целиком (readyFile): отказ записывает отвергнутый «код:хэш».
        if (previous.state == State.READY && previous.versionCode == release.versionCode &&
            prefs.getString(PREF_SHA, null) == expected && readyFile(context, deps) != null) {
            return save(context, ready, expected)
        }
        val rejection = "${release.versionCode}:$expected"
        val current = status(context)
        if (prefs.getString(PREF_REJECTED, null) == rejection && current.state == State.ERROR && current.versionCode == release.versionCode) {
            prefs.edit { putLong(PREF_LAST_CHECK, now) }
            return status(context)
        }

        deps.dir.mkdirs()
        clean(deps.dir)
        val need = (release.apkSize.takeIf { it > 0 } ?: (MAX_APK_BYTES / 4)) + SPACE_MARGIN
        if (deps.freeBytes(deps.dir) < need) return fail(context, now, "мало места для загрузки обновления")

        val part = File(deps.dir, "${file.name}.part")
        val actual = try {
            deps.http.download(release.apkUrl, part, MAX_APK_BYTES)
        } catch (_: IOException) {
            part.delete()
            return fail(context, now, "не удалось скачать ${release.versionName}")
        }
        if (actual != expected) {
            part.delete()
            return fail(context, now, "файл ${release.versionName} повреждён при загрузке (SHA-256 не совпал)")
        }
        if (!part.renameTo(file)) { part.delete(); return fail(context, now, "не удалось сохранить файл обновления") }

        val problem = problemOf(deps.apk.archive(file), installed, release.versionCode, release.versionName)
        if (problem != null) return reject(context, file, rejection, release.versionName, release.versionCode, problem, now)
        prefs.edit { remove(PREF_REJECTED) }
        UpdateLog.add(context, "Найдено обновление ${release.versionName} (${channel(context).label} канал): скачано, SHA-256 и подпись проверены", now)
        return save(context, ready, expected)
    }

    /** Новая версия установлена: скачанный файл больше не нужен, следующая проверка начнётся заново. */
    fun afterUpdate(context: Context, deps: Deps = deps(context)) {
        clean(deps.dir)
        save(context, Status(State.NONE, ""), sha = null)
    }

    private fun apkName(code: Long) = "update-$code.apk"

    private fun clean(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fail(context: Context, now: Long, message: String) =
        save(context, Status(State.ERROR, message, lastCheck = now), sha = null)

    private fun save(context: Context, status: Status, sha: String?): Status {
        prefs(context).edit {
            putString(PREF_STATE, status.state.name)
            putString(PREF_MESSAGE, status.message)
            putString(PREF_VERSION_NAME, status.versionName)
            putLong(PREF_VERSION_CODE, status.versionCode)
            putString(PREF_SHA, sha)
            putString(PREF_NOTES, status.notes)
            putLong(PREF_LAST_CHECK, status.lastCheck)
        }
        return status
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return UpdateHttp.hex(digest.digest())
    }

    /** Строка для «О приложении». */
    fun statusText(context: Context): Pair<ModernUi.Status, String> {
        val status = status(context)
        val time = if (status.lastCheck > 0) " · проверено ${SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(status.lastCheck))}" else ""
        return when (status.state) {
            State.READY -> ModernUi.Status.PROGRESS to "Обновление ${status.versionName} скачано и проверено$time"
            State.UP_TO_DATE -> ModernUi.Status.OK to "Обновлений нет$time"
            State.ERROR -> ModernUi.Status.WARNING to "Обновления: ${status.message}$time"
            State.NONE -> ModernUi.Status.UNKNOWN to "Обновления ещё не проверялись"
        }
    }

    /** Строка боковой панели: только когда обновление готово; иначе панель её не показывает. */
    fun drawerText(context: Context): String? =
        status(context).takeIf { it.state == State.READY }?.let { "Доступно обновление ${it.versionName}" }

    /** Раз в [CHECK_HOURS] часов при наличии сети. Повторный вызов расписание не дублирует. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<Worker>(CHECK_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(10, TimeUnit.MINUTES)
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (_: IllegalStateException) {
            // WorkManager не инициализирован (тестовое окружение без манифеста).
        }
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            withContext(Dispatchers.IO) { check(applicationContext) }
            // Ошибка видна в статусе; следующая попытка — по расписанию.
            return Result.success()
        }
    }
}
