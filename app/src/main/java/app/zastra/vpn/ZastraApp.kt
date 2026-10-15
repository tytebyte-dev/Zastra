package app.zastra.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import app.zastra.vpn.core.util.ZLog
import java.io.File
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.zastra.vpn.data.Settings
import app.zastra.vpn.util.LogBuffer

class ZastraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashReporter()
        LogBuffer.install()
        reportPreviousCrash()
        // Любая ошибка инициализации не должна ронять приложение при запуске
        // (на некоторых оболочках, например MIUI, часть системных вызовов ведёт себя иначе).
        safely("язык") {
            // Интерфейс всегда на русском (в том числе строки библиотек Material/AndroidX).
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        }
        // Логин, пароль и порт локального SOCKS5 создаются при первом запуске.
        safely("учётные данные") { Settings.get(this).ensureCredentials() }
        safely("канал уведомлений") { createNotificationChannel() }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            ZLog.e("App", "ошибка инициализации ($what)", e)
        }
    }

    /** Сохраняет трассировку необработанного сбоя, чтобы показать её в журнале при следующем запуске. */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(filesDir, CRASH_FILE).writeText(Log.getStackTraceString(error))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun reportPreviousCrash() {
        try {
            val f = File(filesDir, CRASH_FILE)
            if (f.isFile) {
                ZLog.e("Crash", "предыдущий запуск завершился сбоем:\n" + f.readText().take(3000))
                f.delete()
            }
        } catch (_: Throwable) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_VPN,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_VPN = "vpn"
        private const val CRASH_FILE = "last_crash.txt"
    }
}
