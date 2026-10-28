package com.example.smarthomedashboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** App-private, backup-excluded atomic verifier record. Never stores the entered PIN/code. */
internal object AndroidPinAccess {
    fun create(context: Context): PinAccess {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
        val storage = PinRecordFile(File(app.noBackupFilesDir, "pin-security/record.json"), prefs)
        return PinAccess(
            storage,
            object : PinAccess.Legacy {
                override fun read(): String? = prefs.getString("admin_pin", null)

                override fun remove(): Boolean =
                    prefs
                        .edit()
                        .putBoolean("pin_verifier_initialized", true)
                        .remove("admin_pin")
                        .remove("last_auth_time")
                        .commit()
            },
            clock =
                object : PinAccess.Clock {
                    override fun elapsed() = SystemClock.elapsedRealtime()

                    override fun boot() =
                        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT)
                            .toString()
                },
            legacyApi24 = Build.VERSION.SDK_INT < 26,
            credentialsChanged = { PinSecurityUi.session.revoke() },
            worker = {
                check(Looper.myLooper() != Looper.getMainLooper()) {
                    "PIN requires a worker thread"
                }
            },
        )
    }
}

internal class PinRecordFile(
    private val location: File,
    private val prefs: SharedPreferences,
    private val fileExists: (File) -> Boolean = ::nativePinFileExists,
) : PinAccess.Storage {
    private val atomic = AtomicFile(location)

    override fun read(): PinAccess.State? {
        val baseExists = fileExists(location)
        val backupExists = fileExists(File(location.path + ".bak"))
        if (!baseExists && !backupExists) {
            check(!prefs.getBoolean("pin_verifier_initialized", false))
            return null
        }
        val bytes =
            atomic.openRead().use { input ->
                val data = ByteArray(4097)
                var total = 0
                while (total < data.size) {
                    val count = input.read(data, total, data.size - total)
                    if (count < 0) break
                    total += count
                }
                check(total <= 4096)
                data.copyOf(total)
            }
        val text = bytes.toString(Charsets.UTF_8)
        val tokener = org.json.JSONTokener(text)
        val json = tokener.nextValue() as? JSONObject ?: error("Invalid PIN record")
        check(tokener.nextClean().code == 0)
        check(
            json.keys().asSequence().toSet() ==
                setOf(
                    "version",
                    "enabled",
                    "pin",
                    "recovery",
                    "failures",
                    "cooldownEnd",
                    "cooldownDuration",
                    "boot",
                ),
        )
        check(json.get("version") == 1)
        val enabled = json.get("enabled") as? Boolean ?: error("Invalid PIN record")
        fun record(key: String, purpose: String): String? {
            if (json.isNull(key)) return null
            val value = json.get(key) as? String ?: error("Invalid PIN record")
            check(validRecord(value, purpose))
            return value
        }
        val pin = record("pin", "PIN")
        val recovery = record("recovery", "RECOVERY")
        check(enabled == (pin != null))
        check(enabled || recovery == null)
        val failures = (json.get("failures") as? Int) ?: error("Invalid PIN record")
        fun number(key: String): Long {
            val n = json.get(key)
            check(n is Int || n is Long)
            return (n as Number).toLong()
        }
        val end = number("cooldownEnd")
        val duration = number("cooldownDuration")
        val boot = json.get("boot") as? String ?: error("Invalid PIN record")
        check(failures in 0..35 && end >= 0 && duration in 0..900_000 && boot.length <= 64)
        check(duration == 0L || failures >= 5)
        return PinAccess.State(pin, recovery, failures, end, duration, boot)
    }

    override fun write(state: PinAccess.State): Boolean {
        check(state.pin == null || validRecord(state.pin, "PIN"))
        check(
            state.recovery == null || state.pin != null && validRecord(state.recovery, "RECOVERY"),
        )
        check(
            state.failures in 0..35 &&
                state.cooldownEnd >= 0 &&
                state.cooldownDuration in 0..900_000 &&
                state.boot.length <= 64,
        )
        if (!location.parentFile!!.isDirectory && !location.parentFile!!.mkdirs()) return false
        var output: java.io.FileOutputStream? = null
        return try {
            output = atomic.startWrite()
            val json =
                JSONObject()
                    .put("version", 1)
                    .put("enabled", state.pin != null)
                    .put("pin", state.pin ?: JSONObject.NULL)
                    .put("recovery", state.recovery ?: JSONObject.NULL)
                    .put("failures", state.failures)
                    .put("cooldownEnd", state.cooldownEnd)
                    .put("cooldownDuration", state.cooldownDuration)
                    .put("boot", state.boot)
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            output = null
            val saved = checkNotNull(read())
            saved.pin == state.pin &&
                saved.recovery == state.recovery &&
                saved.failures == state.failures &&
                saved.cooldownEnd == state.cooldownEnd &&
                saved.cooldownDuration == state.cooldownDuration &&
                saved.boot == state.boot
        } catch (_: Exception) {
            output?.let { atomic.failWrite(it) }
            false
        }
    }

    private fun validRecord(value: String, purpose: String): Boolean {
        if (value.length > 512) return false
        val f = value.split(':')
        if (f.size != 6 || f[0] != "1" || f[1] != purpose) return false
        if (
            !(f[2] == "PBKDF2WithHmacSHA512" && f[3] == "220000" ||
                f[2] == "PBKDF2WithHmacSHA256" && f[3] == "600000" ||
                f[2] == "PBKDF2WithHmacSHA1" && f[3] == "1400000")
        )
            return false
        return f[4].length == 64 &&
            f[5].length == 64 &&
            (f[4] + f[5]).all { it in '0'..'9' || it in 'a'..'f' }
    }
}

private fun nativePinFileExists(file: File): Boolean =
    try {
        val stat = Os.lstat(file.path)
        check(OsConstants.S_ISREG(stat.st_mode))
        true
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT) false else throw e
    }
