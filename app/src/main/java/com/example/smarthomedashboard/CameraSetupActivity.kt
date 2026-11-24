package com.example.smarthomedashboard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Одна найденная камера (docs/setup-tz.md, часть 3): логин и пароль → проверка потоков → живая картинка → «Добавить».
 * Плитку создаёт мастер; здесь — её настройки и пароль (сразу в защищённое хранилище под номером будущей плитки). Камере
 * ничего не меняется.
 */
class CameraSetupActivity : PinProtectedActivity(), PanelIdle.Busy {
    /** Логин и пароль прошлой камеры — только в памяти, пока открыт мастер: предлагаются кнопкой, сами не подставляются. */
    internal class Login(val user: String, val password: String)

    private lateinit var found: CameraFound
    private val tileId = UUID.randomUUID().toString()
    private var doorbell = false
    private var ready: CameraSetup.Ready? = null
    private var password = ""
    private var added = false
    /** Пароль уже лёг в хранилище под номером будущей плитки: не добавили — убрать, даже если следующая проверка не прошла. */
    private var stored = false
    private var run = 0
    private lateinit var userInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var titleInput: EditText
    private lateinit var status: LinearLayout
    private lateinit var hint: TextView
    private lateinit var result: LinearLayout
    private var video: DoorbellVideoView? = null
    private val typeButtons = mutableListOf<TextView>()

    override val idleBusy get() = !getSharedPreferences(HaConnector.PREFS, MODE_PRIVATE).getBoolean(HaConnector.KEY_DONE, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        found = CameraFound(intent.getStringExtra(EXTRA_HOST).orEmpty(), intent.getStringExtra(EXTRA_NAME).orEmpty(), intent.getStringExtra(EXTRA_MODEL).orEmpty(),
            CameraBrand.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_BRAND) } ?: CameraBrand.OTHER, intent.getIntExtra(EXTRA_PORT, 0),
            doorbell = intent.getBooleanExtra(EXTRA_DOORBELL, false))
        if (found.host.isBlank()) { finish(); return }
        doorbell = found.doorbell
        setContentView(build())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun gap(size: Int = 12) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(size)) }

    private fun build(): View {
        val pad = resources.getDimensionPixelSize(R.dimen.modern_space_l)
        val column = MaxWidthLinearLayout(this).apply { orientation = LinearLayout.VERTICAL; maxWidth = dp(820); setPadding(pad, pad, pad, pad) }
        column.addView(ModernUi.header(this, found.title))
        column.addView(ModernUi.secondary(this, listOf(found.brand.title, found.host).filter { it.isNotBlank() }.distinct().joinToString(" · ")))
        column.addView(gap())
        column.addView(ModernUi.body(this, "Логин и пароль самой камеры (не облачного приложения). Пароль хранится на планшете зашифрованно."))
        column.addView(gap(8))
        userInput = ModernUi.input(this, CameraSetup.DEFAULT_USER, "Логин камеры").apply { tag = "camera_user" }
        passwordInput = ModernUi.input(this, "", "Пароль камеры", password = true).apply {
            tag = "camera_password"
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            excludeFromAutofill()
        }
        column.addView(userInput)
        column.addView(gap(8))
        column.addView(passwordInput)
        last?.let { previous ->
            column.addView(ModernUi.secondaryButton(this, "Тот же логин и пароль, что у прошлой камеры") {
                userInput.setText(previous.user); passwordInput.setText(previous.password); check()
            }.apply { tag = "camera_same" })
        }
        column.addView(gap())
        column.addView(ModernUi.primaryButton(this, "Проверить") { check() }.apply { tag = "camera_check" })
        status = ModernUi.statusRow(this, ModernUi.Status.UNKNOWN, "").apply { visibility = View.GONE }
        column.addView(status)
        hint = ModernUi.secondary(this, "").apply { tag = "camera_hint"; visibility = View.GONE }
        column.addView(hint)
        result = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "camera_result"; visibility = View.GONE }
        column.addView(result)
        column.addView(gap(20))
        column.addView(ModernUi.secondaryButton(this, "Отмена") { finish() }.apply { tag = "camera_cancel" })
        return FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@CameraSetupActivity, R.color.modern_background))
            addView(ScrollView(this@CameraSetupActivity).apply { addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL)) })
        }
    }

    private fun setStatus(state: ModernUi.Status, text: String) {
        ModernUi.setStatus(status, state, text)
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showHint(text: String) { hint.text = text; hint.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE }

    /** Одна попытка за касание: пароли сами не подбираются — камеры блокируют вход. */
    internal fun check() {
        if (!requireAdmin()) return
        val user = userInput.text.toString().trim().ifEmpty { CameraSetup.DEFAULT_USER }
        val typed = passwordInput.text.toString()
        if (typed.isEmpty()) { setStatus(ModernUi.Status.WARNING, "Введите пароль камеры"); return }
        clearResult()
        val current = ++run
        setStatus(ModernUi.Status.PROGRESS, "Подключаюсь к камере…")
        showHint("")
        busy(true)
        Thread({
            val outcome = runCatching { CameraSetup.connect(found, user, typed) }
            runOnUiThread {
                if (current != run || isFinishing || isDestroyed) return@runOnUiThread
                busy(false)
                outcome.onSuccess { show(it, user, typed) }.onFailure { e ->
                    setStatus(ModernUi.Status.ERROR, (e as? CameraSetup.Failed)?.message ?: "Камера не ответила")
                    showHint(CameraHints.of(found.brand))
                }
            }
        }, "camera-setup").apply { isDaemon = true; start() }
    }

    private fun busy(value: Boolean) {
        listOf("camera_check", "camera_same").forEach { window.decorView.findViewWithTag<View>(it)?.isEnabled = !value }
    }

    private fun show(next: CameraSetup.Ready, user: String, typed: String) {
        ready = next
        password = typed
        last = Login(user, typed)
        setStatus(ModernUi.Status.OK, "Камера ответила" + (next.info?.let { ": ${it.title}" } ?: ""))
        val note = CameraSetup.decoderNote(next.info, CameraDiagnosis.decoderFacts())
        // Картинка — с паролем из защищённого хранилища, как у настоящей плитки.
        stored = true
        CameraPasswords.save(this, tileId, typed) { ok ->
            if (isFinishing || isDestroyed || ready !== next) return@save
            if (!ok) { setStatus(ModernUi.Status.ERROR, "Не удалось сохранить пароль камеры"); return@save }
            renderResult(next, note)
        }
    }

    private fun renderResult(next: CameraSetup.Ready, note: String) {
        result.removeAllViews()
        result.visibility = View.VISIBLE
        val frame = FrameLayout(this).apply { tag = "camera_picture"; setBackgroundColor(Color.BLACK) }
        val view = DoorbellVideoView(this).apply { muted = true }
        video = view
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.bind(DoorbellVideoView.Source(next.config.rtspSub.ifBlank { next.config.rtspMain }, next.config.rtspUser, "", false, secretKey = tileId, snapshotUrl = next.config.snapshotUrl), null)
        result.addView(frame, LinearLayout.LayoutParams(dp(PICTURE_W), dp((PICTURE_W / DoorbellConfig.DEFAULT_ASPECT).toInt())).apply { topMargin = dp(8) })
        if (note.isNotEmpty()) result.addView(ModernUi.secondary(this, note).apply { tag = "camera_note" })
        result.addView(gap())
        result.addView(ModernUi.title(this, "Как назвать"))
        titleInput = ModernUi.input(this, next.title, "Название плитки").apply { tag = "camera_title" }
        result.addView(titleInput)
        result.addView(gap(8))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        typeButtons.clear()
        listOf("Камера" to false, "Домофон" to true).forEach { (label, value) ->
            val button = android.widget.Button(this).apply {
                text = label; isAllCaps = false; tag = if (value) "camera_type_doorbell" else "camera_type_camera"; minHeight = dp(48)
                setTextColor(Color.WHITE)
                setOnClickListener { doorbell = value; paintType() }
            }
            typeButtons += button
            row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        result.addView(row)
        result.addView(ModernUi.caption(this, "Домофон — с кнопкой звонка, разговором и окном звонка. Кнопка звонка Home Assistant выбирается в редакторе плитки."))
        paintType()
        result.addView(gap())
        result.addView(ModernUi.primaryButton(this, "Добавить на панель") { add() }.apply { tag = "camera_add" })
    }

    private fun paintType() = typeButtons.forEachIndexed { i, view ->
        val on = (i == 1) == doorbell
        view.background = BatteryModernCard.rounded(if (on) Color.argb(90, 95, 168, 211) else Color.argb(40, 30, 44, 56),
            10 * resources.displayMetrics.density, Color.argb(if (on) 220 else 70, 95, 168, 211))
    }

    private fun add() {
        val next = ready ?: return
        if (!requireAdmin()) return
        val title = titleInput.text.toString().trim().ifEmpty { if (doorbell) DoorbellConfig.DEFAULT_TITLE else DoorbellConfig.CAMERA_TITLE }
        // У домофона — прежний набор настроек (без адреса и порта ONVIF) и прежнее место пароля.
        val config = if (doorbell) next.config.copy(host = "", onvifPort = 0, model = "", snapshotUrl = "") else next.config.copy(talk = false)
        val done = { ok: Boolean ->
            if (!isFinishing && !isDestroyed) {
                if (!ok) setStatus(ModernUi.Status.ERROR, "Не удалось сохранить пароль камеры")
                else {
                    added = true
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, tileId).putExtra(EXTRA_DOORBELL, doorbell).putExtra(EXTRA_TITLE, title)
                        .putExtra(EXTRA_HOST, found.host).putExtra(EXTRA_CONFIG, config.toJson().toString()))
                    finish()
                }
            }
        }
        if (doorbell) saveDoorbell(password, done) else done(true)
    }

    /** Пароль у домофонов панели один общий: другой домофон с другим паролем не затирается. */
    private fun saveDoorbell(typed: String, done: (Boolean) -> Unit) {
        val save = {
            CameraPasswords.save(this, "", typed) { ok ->
                if (ok) CameraPasswords.save(applicationContext, tileId, "") {}
                done(ok)
            }
        }
        if (!intent.getBooleanExtra(EXTRA_OTHER_DOORBELL, false)) { save(); return }
        CameraPasswords.read(this, "") { current ->
            if (isFinishing || isDestroyed) return@read
            if (current.isNullOrEmpty() || current == typed) save()
            else setStatus(ModernUi.Status.WARNING, "У домофонов на панели один общий пароль, а у другого домофона он не такой. " +
                "Добавьте этот как «Камера» или задайте обоим одинаковый пароль")
        }
    }

    private fun clearResult() {
        ready = null
        video?.dispose()
        video = null
        result.removeAllViews()
        result.visibility = View.GONE
    }

    override fun onDestroy() {
        run++
        video?.dispose()
        // Не добавили — пароль будущей плитки не остаётся.
        if (!added && stored && !isChangingConfigurations) CameraPasswords.save(applicationContext, tileId, "") {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HOST = "camera_host"
        const val EXTRA_NAME = "camera_name"
        const val EXTRA_MODEL = "camera_model"
        const val EXTRA_BRAND = "camera_brand"
        const val EXTRA_PORT = "camera_port"
        const val EXTRA_DOORBELL = "camera_doorbell"
        const val EXTRA_OTHER_DOORBELL = "camera_other_doorbell"
        const val EXTRA_ID = "camera_id"
        const val EXTRA_TITLE = "camera_title"
        const val EXTRA_CONFIG = "camera_config"
        private const val PICTURE_W = 320

        internal var last: Login? = null

        internal fun intent(context: Context, found: CameraFound, otherDoorbell: Boolean = false) = Intent(context, CameraSetupActivity::class.java)
            .putExtra(EXTRA_OTHER_DOORBELL, otherDoorbell)
            .putExtra(EXTRA_HOST, found.host).putExtra(EXTRA_NAME, found.name).putExtra(EXTRA_MODEL, found.model)
            .putExtra(EXTRA_BRAND, found.brand.name).putExtra(EXTRA_PORT, found.onvifPort).putExtra(EXTRA_DOORBELL, found.doorbell)
    }
}
