package app.zastra.vpn.util

import android.content.Context
import android.content.res.Configuration
import androidx.preference.PreferenceManager
import app.zastra.vpn.data.Keys
import java.util.Locale

/**
 * Язык интерфейса вне экранов (сервис, уведомления, приложение). Экраны получают язык
 * через AppCompatDelegate.setApplicationLocales; здесь тот же выбор применяется к остальным контекстам.
 */
object LocaleHelper {
    const val RU = "ru"
    const val EN = "en"

    fun saved(context: Context): String? =
        try {
            PreferenceManager.getDefaultSharedPreferences(context).getString(Keys.APP_LANGUAGE, null)
        } catch (_: Throwable) {
            null
        }

    fun wrap(base: Context): Context {
        val tag = saved(base) ?: return base
        return try {
            val cfg = Configuration(base.resources.configuration)
            cfg.setLocale(Locale.forLanguageTag(tag))
            base.createConfigurationContext(cfg)
        } catch (_: Throwable) {
            base
        }
    }
}
