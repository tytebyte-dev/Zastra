package app.zastra.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import app.zastra.vpn.BuildConfig
import app.zastra.vpn.R
import app.zastra.vpn.data.DnsPresets
import app.zastra.vpn.data.Keys
import app.zastra.vpn.data.Settings
import app.zastra.vpn.net.DnsFactory
import app.zastra.vpn.net.PacStore
import app.zastra.vpn.util.LocaleHelper
import app.zastra.vpn.vpn.BrowserDetector
import app.zastra.vpn.vpn.VpnState
import app.zastra.vpn.vpn.VpnStatus
import app.zastra.vpn.vpn.ZastraVpnService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Экран настроек: приложения, DNS, локальный SOCKS5, PAC, сведения о программе. */
class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var settings: Settings
    private val mainHandler = Handler(Looper.getMainLooper())

    private inline fun <reified T : Preference> pref(key: String): T =
        requireNotNull(findPreference<T>(key)) { "нет настройки $key" }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        settings = Settings.get(requireContext())
        setPreferencesFromResource(R.xml.preferences, rootKey)
        setupLanguage()
        setupApps()
        setupDns()
        setupSocks()
        setupPac()
        setupAbout()
        preferenceScreen.removeIconSpace()
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        refreshSocks()
        refreshPac()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ язык

    private fun setupLanguage() {
        val lang = pref<ListPreference>(Keys.APP_LANGUAGE)
        if (lang.value == null) lang.value = LocaleHelper.RU
        lang.setOnPreferenceChangeListener { _, v ->
            settings.language = v as String
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(v))
            true
        }
    }

    // ------------------------------------------------------------------ приложения

    private fun setupApps() {
        // Только информация: выбирать приложения не нужно, VPN работает для браузеров.
        pref<Preference>(PREF_APPS).isSelectable = false
    }

    private fun refreshApps() {
        val names = BrowserDetector.find(requireContext()).joinToString { it.label }
        pref<Preference>(PREF_APPS).summary =
            if (names.isEmpty()) getString(R.string.pref_apps_summary_none)
            else getString(R.string.pref_apps_summary_list, names)
    }

    // ------------------------------------------------------------------ DNS

    private fun setupDns() {
        val mode = pref<ListPreference>(Keys.DNS_MODE)
        val provider = pref<ListPreference>(Keys.DOH_PROVIDER)
        val custom = pref<EditTextPreference>(Keys.DOH_CUSTOM_URL)
        val primary = pref<EditTextPreference>(Keys.DNS_PRIMARY)
        val secondary = pref<EditTextPreference>(Keys.DNS_SECONDARY)
        val fallback = pref<SwitchPreferenceCompat>(Keys.DNS_FALLBACK)

        fun updateVisibility(modeValue: String?, providerValue: String?) {
            val doh = modeValue == DnsPresets.MODE_DOH
            provider.isVisible = doh
            custom.isVisible = doh && providerValue == DnsPresets.PROVIDER_CUSTOM
            fallback.isVisible = doh
        }
        updateVisibility(mode.value, provider.value)
        mode.setOnPreferenceChangeListener { _, v ->
            updateVisibility(v as String, provider.value)
            true
        }
        provider.setOnPreferenceChangeListener { _, v ->
            updateVisibility(mode.value, v as String)
            true
        }

        custom.setOnBindEditTextListener { it.configure(InputType.TYPE_TEXT_VARIATION_URI) }
        custom.setOnPreferenceChangeListener { _, v ->
            validate(DnsFactory.isValidDohUrl(v as String), R.string.err_doh_url)
        }
        listOf(primary, secondary).forEach { p ->
            p.setOnBindEditTextListener { it.configure(InputType.TYPE_CLASS_TEXT) }
        }
        primary.setOnPreferenceChangeListener { _, v ->
            validate(DnsFactory.isValidDnsAddress(v as String), R.string.err_dns_address)
        }
        secondary.setOnPreferenceChangeListener { _, v ->
            val text = (v as String).trim()
            validate(text.isEmpty() || DnsFactory.isValidDnsAddress(text), R.string.err_dns_address)
        }
    }

    // ------------------------------------------------------------------ локальный SOCKS5

    private fun setupSocks() {
        pref<Preference>(PREF_SOCKS_ADDRESS).setOnPreferenceClickListener {
            copy("SOCKS5", socksAddress())
            true
        }
        pref<Preference>(PREF_SOCKS_USER).setOnPreferenceClickListener {
            copy("SOCKS5", settings.socksUser)
            true
        }
        pref<Preference>(PREF_SOCKS_PASS).setOnPreferenceClickListener {
            showPasswordDialog()
            true
        }
        pref<Preference>(PREF_SOCKS_COPY).setOnPreferenceClickListener {
            copy(
                "SOCKS5",
                "socks5://" + settings.socksUser + ":" + settings.socksPass + "@" + socksAddress(),
            )
            true
        }
        pref<Preference>(PREF_SOCKS_REGEN).setOnPreferenceClickListener {
            confirmRegenerate()
            true
        }
    }

    private fun socksAddress() = "127.0.0.1:" + settings.socksPort

    private fun refreshSocks() {
        pref<Preference>(PREF_SOCKS_ADDRESS).summary = socksAddress()
        pref<Preference>(PREF_SOCKS_USER).summary = settings.socksUser
        pref<Preference>(PREF_SOCKS_PASS).summary = getString(R.string.pref_socks_pass_hidden)
    }

    private fun showPasswordDialog() {
        val pass = settings.socksPass
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pref_socks_pass)
            .setMessage(pass)
            .setPositiveButton(R.string.dialog_copy) { _, _ -> copy("SOCKS5", pass) }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun confirmRegenerate() {
        if (VpnState.snapshot.value.status != VpnStatus.DISCONNECTED) {
            toast(R.string.err_stop_vpn_first)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.regen_confirm_title)
            .setMessage(R.string.regen_confirm_message)
            .setPositiveButton(R.string.dialog_create) { _, _ ->
                if (VpnState.snapshot.value.status == VpnStatus.DISCONNECTED) {
                    settings.regenerateCredentials()
                    refreshSocks()
                    toast(R.string.regen_done)
                } else {
                    toast(R.string.err_stop_vpn_first)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ PAC

    private fun setupPac() {
        val url = pref<EditTextPreference>(Keys.PAC_URL)
        if (url.text.isNullOrBlank()) url.text = Settings.DEFAULT_PAC_URL
        url.setOnBindEditTextListener { it.configure(InputType.TYPE_TEXT_VARIATION_URI) }
        url.setOnPreferenceChangeListener { _, v ->
            validate((v as String).trim().startsWith("https://", ignoreCase = true), R.string.err_doh_url)
        }

        pref<Preference>(PREF_PAC_UPDATE).setOnPreferenceClickListener {
            updatePacNow()
            true
        }
        pref<Preference>(PREF_PAC_RESET).setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_pac_reset)
                .setMessage(R.string.pac_reset_confirm)
                .setPositiveButton(R.string.dialog_reset) { _, _ ->
                    val ctx = requireContext().applicationContext
                    PacStore(ctx, settings).resetToBuiltin()
                    ZastraVpnService.reloadPac(ctx)
                    refreshPac()
                    toast(R.string.pac_reset_done)
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            true
        }
    }

    private fun refreshPac() {
        val ctx = requireContext()
        val downloaded = settings.pacDownloaded && settings.pacUpdatedAt > 0
        pref<Preference>(PREF_PAC_UPDATE).summary = if (downloaded) {
            getString(R.string.pac_state_downloaded, formatDateTime(ctx, settings.pacUpdatedAt))
        } else {
            getString(R.string.pac_state_builtin)
        }
        pref<Preference>(PREF_PAC_RESET).isEnabled = settings.pacDownloaded
    }

    private fun updatePacNow() {
        val update = pref<Preference>(PREF_PAC_UPDATE)
        val app = requireContext().applicationContext
        update.isEnabled = false
        update.summary = getString(R.string.pac_updating)
        Thread({
            val result = PacStore(app, settings).download()
            if (result is PacStore.UpdateResult.Updated) ZastraVpnService.reloadPac(app)
            mainHandler.post {
                val text = when (result) {
                    is PacStore.UpdateResult.Updated -> app.getString(R.string.pac_updated)
                    is PacStore.UpdateResult.RateLimited -> app.getString(R.string.pac_rate_limited, result.waitSeconds)
                    is PacStore.UpdateResult.Failed -> app.getString(R.string.pac_failed, result.message)
                }
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                if (isAdded) {
                    update.isEnabled = true
                    refreshPac()
                }
            }
        }, "zastra-pac-manual").start()
    }

    // ------------------------------------------------------------------ о программе

    private fun setupAbout() {
        pref<Preference>(PREF_VERSION).summary = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
        pref<Preference>(PREF_LICENSES).setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_licenses)
                .setMessage(R.string.licenses_text)
                .setPositiveButton(R.string.dialog_close, null)
                .show()
            true
        }
    }

    // ------------------------------------------------------------------ вспомогательное

    private fun validate(ok: Boolean, @StringRes message: Int): Boolean {
        if (!ok) toast(message)
        return ok
    }

    private fun toast(@StringRes message: Int) {
        Toast.makeText(requireContext().applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun copy(label: String, text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        // Начиная с Android 13 система сама показывает подтверждение.
        if (Build.VERSION.SDK_INT < 33) toast(R.string.copied)
    }

    private fun android.widget.EditText.configure(variation: Int) {
        inputType = InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
        setSelectAllOnFocus(true)
    }

    private fun Preference.removeIconSpace() {
        isIconSpaceReserved = false
        if (this is PreferenceGroup) {
            for (i in 0 until preferenceCount) getPreference(i).removeIconSpace()
        }
    }

    private companion object {
        const val PREF_APPS = "pref_apps"
        const val PREF_SOCKS_ADDRESS = "pref_socks_address"
        const val PREF_SOCKS_USER = "pref_socks_user"
        const val PREF_SOCKS_PASS = "pref_socks_pass"
        const val PREF_SOCKS_COPY = "pref_socks_copy"
        const val PREF_SOCKS_REGEN = "pref_socks_regen"
        const val PREF_PAC_UPDATE = "pref_pac_update"
        const val PREF_PAC_RESET = "pref_pac_reset"
        const val PREF_VERSION = "pref_version"
        const val PREF_LICENSES = "pref_licenses"
    }
}
