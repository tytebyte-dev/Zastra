package app.zastra.vpn.data

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.security.SecureRandom

/** Ключи SharedPreferences (часть из них совпадает с ключами экрана настроек). */
object Keys {
    const val SOCKS_PORT = "socks_port"
    const val SOCKS_USER = "socks_user"
    const val SOCKS_PASS = "socks_pass"

    const val DNS_MODE = "dns_mode"
    const val DOH_PROVIDER = "doh_provider"
    const val DOH_CUSTOM_URL = "doh_custom_url"
    const val DNS_PRIMARY = "dns_primary"
    const val DNS_SECONDARY = "dns_secondary"
    const val DNS_FALLBACK = "dns_fallback"

    const val APPS_MODE = "apps_mode"
    const val APPS_SELECTED = "apps_selected"
    const val APPS_SHOW_SYSTEM = "apps_show_system"

    const val PAC_URL = "pac_url"
    const val PAC_AUTO_UPDATE = "pac_auto_update"
    const val PAC_UPDATED_AT = "pac_updated_at"
    const val PAC_LAST_ATTEMPT = "pac_last_attempt"
    const val PAC_SOURCE = "pac_source"

    const val VPN_SHOULD_RUN = "vpn_should_run"
    const val NOTIF_ASKED = "notif_permission_asked"
    const val APP_LANGUAGE = "app_language"
}

/** Режим выбора приложений. */
enum class AppMode(val value: String) {
    /** VPN для всех приложений. */
    ALL("all"),

    /** VPN только для отмеченных приложений. */
    INCLUDE("include"),

    /** VPN для всех, кроме отмеченных. */
    EXCLUDE("exclude");

    companion object {
        fun from(v: String?): AppMode = values().firstOrNull { it.value == v } ?: ALL
    }
}

data class DnsSettings(
    val mode: String,
    val dohUrl: String,
    val primary: String,
    val secondary: String,
    val fallbackToPlain: Boolean,
) {
    val isDoh: Boolean get() = mode == DnsPresets.MODE_DOH
}

/** Готовые провайдеры DoH и их адреса для начальной загрузки (без обращения к системному DNS). */
object DnsPresets {
    const val MODE_DOH = "doh"
    const val MODE_PLAIN = "plain"
    const val PROVIDER_CUSTOM = "custom"

    const val DEFAULT_PRIMARY = "8.8.8.8"
    const val DEFAULT_SECONDARY = "8.8.4.4"
    const val DEFAULT_PROVIDER = "google"

    class Doh(val id: String, val url: String, val host: String, val bootstrap: List<String>)

    val DOH: List<Doh> = listOf(
        Doh("google", "https://dns.google/dns-query", "dns.google", listOf("8.8.8.8", "8.8.4.4")),
        Doh("cloudflare", "https://cloudflare-dns.com/dns-query", "cloudflare-dns.com", listOf("1.1.1.1", "1.0.0.1")),
        Doh("quad9", "https://dns.quad9.net/dns-query", "dns.quad9.net", listOf("9.9.9.9", "149.112.112.112")),
        Doh("adguard", "https://dns.adguard-dns.com/dns-query", "dns.adguard-dns.com", listOf("94.140.14.14", "94.140.15.15")),
    )

    fun urlFor(providerId: String): String? = DOH.firstOrNull { it.id == providerId }?.url
}

/** Настройки приложения. Единый источник правды для UI и VPN-сервиса. */
class Settings private constructor(context: Context) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    // ---------- локальный SOCKS5 ----------

    var socksPort: Int
        get() = prefs.getInt(Keys.SOCKS_PORT, 0)
        set(v) = prefs.edit().putInt(Keys.SOCKS_PORT, v).apply()

    val socksUser: String get() = prefs.getString(Keys.SOCKS_USER, null).orEmpty()
    val socksPass: String get() = prefs.getString(Keys.SOCKS_PASS, null).orEmpty()

    /** Создаёт логин, пароль и порт при первом запуске (и если что-то из них потерялось). */
    fun ensureCredentials() {
        if (socksUser.isBlank() || socksPass.isBlank() || socksPort !in 1024..65535) regenerateCredentials()
    }

    fun regenerateCredentials() {
        val rnd = SecureRandom()
        fun token(alphabet: String, n: Int) = buildString(n) { repeat(n) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
        prefs.edit()
            .putString(Keys.SOCKS_USER, "zastra_" + token("abcdefghijklmnopqrstuvwxyz0123456789", 6))
            .putString(Keys.SOCKS_PASS, token("ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789", 24))
            .putInt(Keys.SOCKS_PORT, 20000 + rnd.nextInt(40000))
            .apply()
    }

    // ---------- DNS ----------

    fun dnsSettings(): DnsSettings {
        val mode = prefs.getString(Keys.DNS_MODE, DnsPresets.MODE_DOH) ?: DnsPresets.MODE_DOH
        val provider = prefs.getString(Keys.DOH_PROVIDER, DnsPresets.DEFAULT_PROVIDER) ?: DnsPresets.DEFAULT_PROVIDER
        val url = if (provider == DnsPresets.PROVIDER_CUSTOM) {
            prefs.getString(Keys.DOH_CUSTOM_URL, null)?.trim().orEmpty()
        } else {
            DnsPresets.urlFor(provider).orEmpty()
        }.ifBlank { DnsPresets.urlFor(DnsPresets.DEFAULT_PROVIDER)!! }
        return DnsSettings(
            mode = mode,
            dohUrl = url,
            primary = prefs.getString(Keys.DNS_PRIMARY, DnsPresets.DEFAULT_PRIMARY)?.trim().orEmpty(),
            secondary = prefs.getString(Keys.DNS_SECONDARY, DnsPresets.DEFAULT_SECONDARY)?.trim().orEmpty(),
            fallbackToPlain = prefs.getBoolean(Keys.DNS_FALLBACK, true),
        )
    }

    // ---------- приложения ----------

    var appMode: AppMode
        get() = AppMode.from(prefs.getString(Keys.APPS_MODE, AppMode.ALL.value))
        set(v) = prefs.edit().putString(Keys.APPS_MODE, v.value).apply()

    var selectedApps: Set<String>
        get() = prefs.getStringSet(Keys.APPS_SELECTED, emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(Keys.APPS_SELECTED, HashSet(v)).apply()

    var showSystemApps: Boolean
        get() = prefs.getBoolean(Keys.APPS_SHOW_SYSTEM, true)
        set(v) = prefs.edit().putBoolean(Keys.APPS_SHOW_SYSTEM, v).apply()

    // ---------- PAC ----------

    var pacUrl: String
        get() = prefs.getString(Keys.PAC_URL, DEFAULT_PAC_URL)?.trim().orEmpty().ifBlank { DEFAULT_PAC_URL }
        set(v) = prefs.edit().putString(Keys.PAC_URL, v).apply()

    val pacAutoUpdate: Boolean get() = prefs.getBoolean(Keys.PAC_AUTO_UPDATE, true)

    var pacUpdatedAt: Long
        get() = prefs.getLong(Keys.PAC_UPDATED_AT, 0L)
        set(v) = prefs.edit().putLong(Keys.PAC_UPDATED_AT, v).apply()

    var pacLastAttempt: Long
        get() = prefs.getLong(Keys.PAC_LAST_ATTEMPT, 0L)
        set(v) = prefs.edit().putLong(Keys.PAC_LAST_ATTEMPT, v).apply()

    /** true, если используется скачанный PAC, false — встроенный. */
    var pacDownloaded: Boolean
        get() = prefs.getString(Keys.PAC_SOURCE, "builtin") == "downloaded"
        set(v) = prefs.edit().putString(Keys.PAC_SOURCE, if (v) "downloaded" else "builtin").apply()

    // ---------- служебное ----------

    var vpnShouldRun: Boolean
        get() = prefs.getBoolean(Keys.VPN_SHOULD_RUN, false)
        set(v) = prefs.edit().putBoolean(Keys.VPN_SHOULD_RUN, v).apply()

    /** Язык интерфейса: "ru" или "en"; null — пользователь ещё не выбирал (первый запуск). */
    var language: String?
        get() = prefs.getString(Keys.APP_LANGUAGE, null)
        set(v) = prefs.edit().putString(Keys.APP_LANGUAGE, v).apply()

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(Keys.NOTIF_ASKED, false)
        set(v) = prefs.edit().putBoolean(Keys.NOTIF_ASKED, v).apply()

    companion object {
        const val DEFAULT_PAC_URL = "https://p.thenewone.lol:8443/proxy.pac"

        @Volatile
        private var instance: Settings? = null

        fun get(context: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(context.applicationContext).also { instance = it }
            }
    }
}
