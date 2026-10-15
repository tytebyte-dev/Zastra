package app.zastra.vpn.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.zastra.vpn.R
import app.zastra.vpn.ZastraApp
import app.zastra.vpn.core.dns.DnsService
import app.zastra.vpn.core.pac.PacEngine
import app.zastra.vpn.core.socks.Dialer
import app.zastra.vpn.core.socks.RouteDecider
import app.zastra.vpn.core.socks.SocketProtector
import app.zastra.vpn.core.socks.Socks5Server
import app.zastra.vpn.core.util.ZLog
import app.zastra.vpn.data.Settings
import app.zastra.vpn.net.DnsFactory
import app.zastra.vpn.net.OkHttpPoster
import app.zastra.vpn.net.PacStore
import app.zastra.vpn.util.LocaleHelper
import app.zastra.vpn.ui.MainActivity
import hev.htproxy.TProxyService
import java.io.File
import java.net.BindException
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * VPN-сервис Zastra.
 *
 * Схема: приложения → TUN → hev-socks5-tunnel → локальный SOCKS5 (логин/пароль) →
 * маршрутизация по PAC (HTTPS-прокси / PROXY / напрямую).
 */
class ZastraVpnService : VpnService() {

    private val lifecycle = Executors.newSingleThreadExecutor { r -> Thread(r, "zastra-lifecycle") }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "zastra-stats").also { it.isDaemon = true }
    }
    private var statsTask: ScheduledFuture<*>? = null

    private lateinit var settings: Settings
    private val poster by lazy { OkHttpPoster() }

    // Ниже — состояние, которым управляет только поток [lifecycle].
    private var engine: PacEngine? = null
    private var dnsService: DnsService? = null
    private var socks: Socks5Server? = null
    private var tun: ParcelFileDescriptor? = null
    private var hevConfigFile: File? = null
    private var tunnelStarted = false
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile
    private var running = false

    private val protector = object : SocketProtector {
        override fun protect(socket: Socket): Boolean = this@ZastraVpnService.protect(socket)
        override fun protect(socket: DatagramSocket): Boolean = this@ZastraVpnService.protect(socket)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                lifecycle.execute { stopVpn(userRequested = true, error = null) }
                return START_NOT_STICKY
            }
            ACTION_RELOAD_PAC -> {
                lifecycle.execute { reloadPac() }
                return START_STICKY
            }
            else -> {
                if (intent == null && !settings.vpnShouldRun) {
                    // Перезапуск системой после гибели процесса, но пользователь VPN не включал.
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground(getString(R.string.notif_connecting))
                VpnState.snapshot.value = VpnSnapshot(VpnStatus.CONNECTING)
                lifecycle.execute { startVpn() }
                return START_STICKY
            }
        }
    }

    override fun onRevoke() {
        ZLog.i(TAG, "VPN отозван системой или другим приложением")
        lifecycle.execute { stopVpn(userRequested = true, error = getString(R.string.err_vpn_revoked)) }
    }

    override fun onDestroy() {
        try {
            lifecycle.submit { cleanup() }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        lifecycle.shutdown()
        scheduler.shutdownNow()
        if (VpnState.snapshot.value.status != VpnStatus.DISCONNECTED) {
            VpnState.snapshot.value = VpnSnapshot(VpnStatus.DISCONNECTED)
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ запуск

    private fun startVpn() {
        if (running) return
        try {
            // 1. PAC-сценарий (исполняется как есть)
            val eng = PacEngine()
            engine = eng
            eng.load(PacStore(this, settings).loadScript())

            // 2. DNS, маршрутизация, локальный SOCKS5 с логином/паролем
            val dns = DnsService(DnsFactory.create(settings.dnsSettings(), protector, poster))
            dnsService = dns
            val dialer = Dialer(RouteDecider(eng, dns), dns, protector)
            var server = newSocksServer(dialer, dns, settings.socksPort)
            try {
                server.start()
            } catch (e: BindException) {
                ZLog.w(TAG, "порт ${settings.socksPort} занят, выбираем другой", e)
                server = newSocksServer(dialer, dns, 0)
                server.start()
                settings.socksPort = server.port
            }
            socks = server

            // 3. TUN-интерфейс и правила по приложениям
            val pfd = buildTunnel() ?: throw IllegalStateException(getString(R.string.err_vpn_revoked))
            tun = pfd

            // 4. tun2socks
            val cfg = File(filesDir, "hev.yml")
            cfg.writeText(HevConfig.build(server.port, settings.socksUser, settings.socksPass))
            hevConfigFile = cfg
            if (!TProxyService.TProxyStartService(cfg.absolutePath, pfd.fd)) {
                throw IllegalStateException(getString(R.string.err_tunnel_start))
            }
            tunnelStarted = true
            Thread.sleep(400)
            if (!TProxyService.TProxyIsRunning()) throw IllegalStateException(getString(R.string.err_tunnel_start))

            running = true
            settings.vpnShouldRun = true
            registerPrefListener()
            startStats()
            VpnState.snapshot.value = VpnSnapshot(VpnStatus.CONNECTED)
            updateNotification(getString(R.string.notif_connected))
            ZLog.i(TAG, "VPN запущен, SOCKS5 127.0.0.1:" + server.port)
            maybeAutoUpdatePac()
        } catch (e: Throwable) {
            ZLog.e(TAG, "не удалось запустить VPN", e)
            val message = e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.err_generic)
            cleanup()
            settings.vpnShouldRun = false
            VpnState.snapshot.value = VpnSnapshot(VpnStatus.DISCONNECTED, message)
            finishService()
        }
    }

    private fun newSocksServer(dialer: Dialer, dns: DnsService, port: Int) = Socks5Server(
        username = settings.socksUser,
        password = settings.socksPass,
        dialer = dialer,
        dns = dns,
        protector = protector,
        requestedPort = port,
        rejectedIps = setOf(HevConfig.TUN_DNS),
    )

    private fun buildTunnel(): ParcelFileDescriptor? {
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(HevConfig.MTU)
            .addAddress(HevConfig.TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(HevConfig.TUN_DNS)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        b.setConfigureIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        applyAppRules(b)
        return b.establish()
    }

    /**
     * VPN работает только для установленных браузеров, остальные приложения идут мимо него.
     * Список браузеров определяется при каждом подключении.
     */
    private fun applyAppRules(b: Builder) {
        val browsers = BrowserDetector.find(this)
        var added = 0
        for (br in browsers) {
            try {
                b.addAllowedApplication(br.packageName)
                added++
            } catch (e: PackageManager.NameNotFoundException) {
                ZLog.w(TAG, "браузер ${br.packageName} не найден, пропускаем")
            }
        }
        if (added == 0) throw IllegalStateException(getString(R.string.err_no_browsers))
        ZLog.i(TAG, "VPN для браузеров: " + browsers.joinToString { it.packageName })
    }

    // ------------------------------------------------------------------ остановка

    private fun stopVpn(userRequested: Boolean, error: String?) {
        if (VpnState.snapshot.value.status != VpnStatus.DISCONNECTED) {
            VpnState.snapshot.value = VpnSnapshot(VpnStatus.DISCONNECTING)
        }
        running = false
        if (userRequested) settings.vpnShouldRun = false
        cleanup()
        VpnState.traffic.value = Traffic()
        VpnState.snapshot.value = VpnSnapshot(VpnStatus.DISCONNECTED, error)
        ZLog.i(TAG, "VPN остановлен")
        finishService()
    }

    private fun cleanup() {
        running = false
        statsTask?.cancel(false)
        statsTask = null
        unregisterPrefListener()
        if (tunnelStarted) {
            try {
                TProxyService.TProxyStopService()
            } catch (e: Throwable) {
                ZLog.w(TAG, "остановка tun2socks", e)
            }
            tunnelStarted = false
        }
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        socks?.stop()
        socks = null
        engine?.close()
        engine = null
        dnsService = null
        hevConfigFile?.delete()
        hevConfigFile = null
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ фоновые задачи

    private fun startStats() {
        statsTask = scheduler.scheduleWithFixedDelay({
            try {
                if (!TProxyService.TProxyIsRunning()) {
                    statsTask?.cancel(false)
                    lifecycle.execute {
                        if (running) stopVpn(userRequested = false, error = getString(R.string.err_tunnel_died))
                    }
                    return@scheduleWithFixedDelay
                }
                val s = TProxyService.TProxyGetStats()
                VpnState.traffic.value = Traffic(s[1], s[3], socks?.activeConnections ?: 0)
            } catch (_: Throwable) {
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun registerPrefListener() {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && (key.startsWith("dns_") || key.startsWith("doh_"))) {
                lifecycle.execute { applyDnsSettings() }
            }
        }
        prefListener = l
        settings.prefs.registerOnSharedPreferenceChangeListener(l)
    }

    private fun unregisterPrefListener() {
        prefListener?.let { settings.prefs.unregisterOnSharedPreferenceChangeListener(it) }
        prefListener = null
    }

    /** Настройки DNS применяются «на лету», без переподключения. */
    private fun applyDnsSettings() {
        val dns = dnsService ?: return
        dns.setTransport(DnsFactory.create(settings.dnsSettings(), protector, poster))
        ZLog.i(TAG, "настройки DNS применены")
    }

    private fun reloadPac() {
        val eng = engine ?: return
        try {
            eng.load(PacStore(this, settings).loadScript())
            ZLog.i(TAG, "PAC перезагружен")
        } catch (e: Throwable) {
            ZLog.w(TAG, "не удалось перезагрузить PAC", e)
        }
    }

    private fun maybeAutoUpdatePac() {
        if (!settings.pacAutoUpdate) return
        val age = System.currentTimeMillis() - settings.pacUpdatedAt
        if (settings.pacUpdatedAt != 0L && age < AUTO_UPDATE_INTERVAL_MS) return
        Thread({
            when (val r = PacStore(this, settings).download()) {
                is PacStore.UpdateResult.Updated -> lifecycle.execute { reloadPac() }
                is PacStore.UpdateResult.Failed -> ZLog.w(TAG, "автообновление PAC: " + r.message)
                is PacStore.UpdateResult.RateLimited -> Unit
            }
        }, "zastra-pac-update").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ уведомление

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ZastraVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, ZastraApp.CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_disconnect), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "VPN"
        private const val NOTIF_ID = 1
        private const val AUTO_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

        const val ACTION_START = "app.zastra.vpn.START"
        const val ACTION_STOP = "app.zastra.vpn.STOP"
        const val ACTION_RELOAD_PAC = "app.zastra.vpn.RELOAD_PAC"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ZastraVpnService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ZastraVpnService::class.java).setAction(ACTION_STOP))
        }

        /** Просит работающий сервис перечитать PAC (после обновления). */
        fun reloadPac(context: Context) {
            if (VpnState.snapshot.value.status == VpnStatus.CONNECTED) {
                try {
                    context.startService(Intent(context, ZastraVpnService::class.java).setAction(ACTION_RELOAD_PAC))
                } catch (e: IllegalStateException) {
                    ZLog.w(TAG, "не удалось передать сервису команду обновить PAC", e)
                }
            }
        }
    }
}
