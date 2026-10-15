package app.zastra.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.zastra.vpn.data.Settings
import app.zastra.vpn.util.LogBuffer

class ZastraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Интерфейс всегда на русском (в том числе строки библиотек Material/AndroidX).
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        LogBuffer.install()
        // Логин, пароль и порт локального SOCKS5 создаются при первом запуске.
        Settings.get(this).ensureCredentials()
        createNotificationChannel()
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
    }
}
