package app.zastra.vpn.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.zastra.vpn.R
import app.zastra.vpn.data.AppMode
import app.zastra.vpn.data.Settings
import app.zastra.vpn.databinding.ActivityMainBinding
import app.zastra.vpn.vpn.VpnSnapshot
import app.zastra.vpn.vpn.VpnState
import app.zastra.vpn.vpn.VpnStatus
import app.zastra.vpn.vpn.ZastraVpnService
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import com.google.android.material.R as MaterialR

/** Главный экран: одна большая кнопка включения и выключения VPN. */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings

    private var shownColor = 0
    private var colorAnimator: ValueAnimator? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Разрешение на уведомления необязательно: VPN работает и без него.
            requestVpnPermission()
        }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                ZastraVpnService.start(this)
            } else {
                Snackbar.make(b.root, R.string.vpn_permission_denied, Snackbar.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = Settings.get(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarInsets()
        setSupportActionBar(b.toolbar)

        shownColor = ContextCompat.getColor(this, R.color.state_off)
        b.powerButton.setOnClickListener { onPowerClick() }
        setupFocusEffect()
        setupTelevision()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { VpnState.snapshot.collect { render(it) } }
                launch { VpnState.traffic.collect { renderStats() } }
            }
        }
    }

    /** Подсветка кнопки при выборе пультом или клавиатурой. */
    private fun setupFocusEffect() {
        val card = b.powerButton
        val stroke = (6 * resources.displayMetrics.density).toInt()
        card.setOnFocusChangeListener { _, focused ->
            card.strokeWidth = if (focused) stroke else 0
            card.strokeColor = ContextCompat.getColor(this, R.color.zastra_on_surface)
            card.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f)
                .setDuration(120).start()
        }
    }

    /** На телевизоре меню в панели не достать пультом — показываем обычные кнопки. */
    private fun setupTelevision() {
        if (!isTelevision(this)) return
        b.tvActions.visibility = View.VISIBLE
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnLog.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        b.powerButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        renderPacInfo()
    }

    override fun onDestroy() {
        colorAnimator?.cancel()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_log -> {
            startActivity(Intent(this, LogActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------ действия

    private fun onPowerClick() {
        when (VpnState.snapshot.value.status) {
            VpnStatus.DISCONNECTED -> beginConnect()
            VpnStatus.CONNECTING, VpnStatus.CONNECTED -> ZastraVpnService.stop(this)
            VpnStatus.DISCONNECTING -> Unit
        }
    }

    private fun beginConnect() {
        if (settings.appMode == AppMode.INCLUDE && settings.selectedApps.none { it != packageName }) {
            Snackbar.make(b.root, R.string.main_pick_apps, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_choose) {
                    startActivity(Intent(this, AppsActivity::class.java))
                }
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            !settings.notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            settings.notificationPermissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestVpnPermission()
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            ZastraVpnService.start(this)
        } else {
            vpnPermission.launch(intent)
        }
    }

    // ------------------------------------------------------------------ отображение

    private fun render(s: VpnSnapshot) {
        val colorRes: Int
        val status: Int
        var hint: String
        when (s.status) {
            VpnStatus.DISCONNECTED -> {
                colorRes = R.color.state_off
                status = R.string.status_disconnected
                hint = getString(R.string.hint_connect)
            }
            VpnStatus.CONNECTING -> {
                colorRes = R.color.state_connecting
                status = R.string.status_connecting
                hint = getString(R.string.hint_connecting)
            }
            VpnStatus.CONNECTED -> {
                colorRes = R.color.state_on
                status = R.string.status_connected
                hint = getString(R.string.hint_connected)
            }
            VpnStatus.DISCONNECTING -> {
                colorRes = R.color.state_connecting
                status = R.string.status_disconnecting
                hint = getString(R.string.hint_disconnecting)
            }
        }
        b.statusText.setText(status)

        val error = s.error
        if (s.status == VpnStatus.DISCONNECTED && !error.isNullOrBlank()) {
            hint = error
            b.hintText.setTextColor(ContextCompat.getColor(this, R.color.state_error))
        } else {
            b.hintText.setTextColor(MaterialColors.getColor(b.hintText, MaterialR.attr.colorOnSurfaceVariant, ContextCompat.getColor(this, R.color.state_off)))
        }
        b.hintText.text = hint

        val busy = s.status == VpnStatus.CONNECTING || s.status == VpnStatus.DISCONNECTING
        b.progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        b.powerButton.isClickable = s.status != VpnStatus.DISCONNECTING
        animateButtonColor(ContextCompat.getColor(this, colorRes))
        renderStats()
    }

    private fun renderStats() {
        if (VpnState.snapshot.value.status != VpnStatus.CONNECTED) {
            b.statsText.visibility = View.INVISIBLE
            return
        }
        val t = VpnState.traffic.value
        b.statsText.text = getString(
            R.string.stats_format,
            Formatter.formatShortFileSize(this, t.uploadBytes),
            Formatter.formatShortFileSize(this, t.downloadBytes),
            t.connections,
        )
        b.statsText.visibility = View.VISIBLE
    }

    private fun renderPacInfo() {
        b.pacInfo.text = if (settings.pacDownloaded && settings.pacUpdatedAt > 0) {
            getString(R.string.pac_info_downloaded, formatDateTime(this, settings.pacUpdatedAt))
        } else {
            getString(R.string.pac_info_builtin)
        }
    }

    private fun animateButtonColor(target: Int) {
        if (target == shownColor) return
        colorAnimator?.cancel()
        val anim = ValueAnimator.ofArgb(shownColor, target).setDuration(250)
        anim.addUpdateListener {
            shownColor = it.animatedValue as Int
            b.powerButton.setCardBackgroundColor(shownColor)
        }
        colorAnimator = anim
        anim.start()
    }
}
