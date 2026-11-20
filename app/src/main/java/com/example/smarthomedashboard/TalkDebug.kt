package com.example.smarthomedashboard

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Проверочная запись звука последнего разговора — только если на планшете создан файл
 * `Android/data/<пакет>/files/talk_debug/on` (через adb, для разбора хрипа и эха). Без него ничего не пишется.
 *
 * Дорожки (16 бит, моно, без заголовка): `mic.pcm` — микрофон до обработки, `mic_aec.pcm` — после эхоподавления,
 * `sent.pcm` — что отдано каналу, `sent_ref.pcm` — что реально ушло в домофон (после кодека, без пропущенных
 * блоков), `street_rx.pcm` — звук с улицы как пришёл, `street_aec.pcm` — после эхоподавления, `street.pcm` — как он
 * прозвучал в динамике планшета. Каждый разговор перезаписывает прежние; не длиннее [MAX_SECONDS] с. В резервные
 * копии и на GitHub не попадает. Файл `no_aec` в той же папке выключает эхоподавление (сравнение со старой защитой).
 */
internal object TalkDebug {
    private const val MAX_SECONDS = 90
    @Volatile private var dir: File? = null
    private val files = HashMap<String, FileOutputStream>()
    private val written = HashMap<String, Long>()

    /** Начать новую запись (прежняя затирается), если проверочная запись включена. */
    @Synchronized fun begin(context: Context) {
        end()
        val d = context.getExternalFilesDir(null)?.let { File(it, "talk_debug") } ?: return
        dir = if (File(d, "on").exists()) d else null
    }

    @Synchronized fun write(track: String, pcm: ShortArray, count: Int, rate: Int) {
        val d = dir ?: return
        val done = written[track] ?: 0L
        if (count <= 0 || done > MAX_SECONDS.toLong() * rate * 2) return
        val out = files.getOrPut(track) {
            File(d, "$track.rate").writeText(rate.toString())
            // Когда дорожка началась (по общим часам): по этому записи разных дорожек совмещаются при разборе.
            File(d, "$track.t0").writeText(System.nanoTime().toString())
            FileOutputStream(File(d, "$track.pcm"))
        }
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) { bytes[2 * i] = pcm[i].toByte(); bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
        runCatching { out.write(bytes) }
        written[track] = done + bytes.size
    }

    /** Есть ли в папке проверочных записей файл-переключатель [name] (создаётся через adb для измерений). */
    fun flag(context: Context, name: String): Boolean =
        context.getExternalFilesDir(null)?.let { File(File(it, "talk_debug"), name).exists() } == true

    @Synchronized fun end() {
        files.values.forEach { runCatching { it.close() } }
        files.clear()
        written.clear()
        dir = null
    }
}
