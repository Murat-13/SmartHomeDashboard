package com.example.smarthomedashboard

import android.app.AlertDialog
import android.content.Context
import android.view.View
import androidx.core.content.edit
import com.google.android.material.snackbar.Snackbar

/** Тексты подсказок для нового пользователя. Собраны в одном месте, чтобы их было просто править. */
internal object UserHints {
    const val HOW_TO_EDIT = "Чтобы добавить плитки и кнопки, удерживайте палец на пустом месте экрана 3 секунды"

    const val EDIT_MODE = "Режим редактирования: нажмите плитку — её настройки · удерживайте и тащите — " +
        "переместить · уголок — размер · «+» — новая плитка · «⚙» — настройки · пустое место — выход"

    const val WELCOME_TITLE = "Добро пожаловать!"

    val WELCOME = """
        На панели уже есть часы и погода. Всё остальное добавляете вы.

        Добавить плитку или кнопку
        Удерживайте палец на пустом месте экрана 3 секунды — включится режим редактирования. Нажмите «+», выберите тип плитки и устройства из Home Assistant.

        В режиме редактирования
        • нажатие на плитку — её настройки;
        • удержание и перетаскивание — переместить;
        • уголок внизу справа — изменить размер;
        • «⚙» — настройки приложения: адрес HA, PIN, режим киоска, затемнение экрана;
        • нажатие на пустое место — выйти.

        Как пользоваться
        • кнопка включает и выключает устройство;
        • группа: короткое нажатие переключает все устройства, удержание 1 секунду открывает отдельные кнопки;
        • нажатие на плитку датчика или погоды показывает подробности.

        Чтобы никто случайно не изменил панель, задайте PIN в настройках.
        Подсказки на экране закрываются крестиком. Вернуть их и снова открыть эту справку можно в «⚙» → «Подсказки и справка».
    """.trimIndent()

    /** Подсказки, которые пользователь может скрыть крестиком. */
    enum class Hint(internal val key: String) {
        HOW_TO_EDIT("hint_hidden_how_to_edit"),
        EDIT_MODE("hint_hidden_edit_mode"),
        TILE_TYPE("hint_hidden_tile_type")
    }

    /** Пользователь уже входил в редактирование: подсказка «как войти» ему не нужна. */
    const val PREF_EDIT_MODE_FOUND = "edit_mode_found"

    private fun prefs(context: Context) = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    fun isHidden(context: Context, hint: Hint) = prefs(context).getBoolean(hint.key, false)

    fun setHidden(context: Context, hint: Hint, hidden: Boolean) {
        prefs(context).edit { putBoolean(hint.key, hidden) }
    }

    /** Есть ли что возвращать: скрытые крестиком или уже ненужная подсказка новичка. */
    fun canRestore(context: Context): Boolean {
        val prefs = prefs(context)
        return Hint.entries.any { prefs.getBoolean(it.key, false) } || prefs.getBoolean(PREF_EDIT_MODE_FOUND, false)
    }

    /** Возвращает все подсказки, включая «как войти в редактирование». */
    fun restoreAll(context: Context) {
        prefs(context).edit {
            Hint.entries.forEach { remove(it.key) }
            putBoolean(PREF_EDIT_MODE_FOUND, false)
        }
    }

    /**
     * Скрывает подсказку и предлагает отмену, как принято в Material: случайное нажатие
     * не должно заставлять искать настройку возврата.
     */
    fun hideWithUndo(anchor: View, hint: Hint, refresh: () -> Unit) {
        val context = anchor.context
        setHidden(context, hint, true)
        refresh()
        undoNotice(anchor, HIDDEN_NOTICE) {
            setHidden(context, hint, false)
            refresh()
        }
    }

    const val HIDDEN_NOTICE = "Подсказка скрыта. Вернуть: «⚙» → «Подсказки и справка»"

    // В JVM-тестах нет ресурсов Material для Snackbar: тест подменяет показ и сам нажимает «Отменить».
    internal var undoNotice: (anchor: View, message: String, undo: () -> Unit) -> Unit = { anchor, message, undo ->
        Snackbar.make(anchor, message, Snackbar.LENGTH_LONG).setAction("Отменить") { undo() }.show()
    }

    const val SETUP_ADDRESS = "Адрес в домашней сети, например http://192.168.1.10:8123 или " +
        "http://homeassistant.local:8123. Внешний адрес нужен, если панель будет подключаться через " +
        "интернет (Nabu Casa, DuckDNS и т. п.). Достаточно одного из двух."

    const val SETUP_TOKEN = "Где взять токен: откройте Home Assistant → ваш профиль (внизу слева) → " +
        "вкладка «Безопасность» → «Долгосрочные токены доступа» → «Создать токен». Скопируйте его сюда. " +
        "Токен хранится на планшете в зашифрованном виде."

    fun tileType(type: String): String = when (type) {
        "sensor" -> "Показывает значения выбранных датчиков. Нажатие раскрывает подробный вид. " +
            "Названия строк, точность и цвет фона по условиям — в «Расширенных настройках»."
        "button" -> "Включает и выключает одно устройство: свет, розетку, реле. Выберите одно устройство."
        "group" -> "Одна кнопка для нескольких устройств. Нажатие переключает все сразу, удержание " +
            "1 секунду открывает отдельные кнопки. Имена и порядок задаются после первого сохранения."
        "battery" -> "Шкала заряда. Первый выбранный датчик — уровень заряда в процентах (0–100). " +
            "Строки с другими значениями — в «Расширенных настройках»."
        "clock" -> "Часы и дата. Датчики выбирать не нужно."
        "weather" -> "Погода из Home Assistant: выберите сущность weather.* (без выбора — weather.home). " +
            "Яндекс Погоду можно включить в настройках приложения."
        else -> ""
    }

    fun showWelcome(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(WELCOME_TITLE)
            .setMessage(WELCOME)
            .setPositiveButton("Понятно", null)
            .show()
    }
}
