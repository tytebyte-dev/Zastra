package app.zastra.vpn.core.socks

import app.zastra.vpn.core.dns.DnsService
import app.zastra.vpn.core.pac.Hop
import app.zastra.vpn.core.pac.PacEvaluator
import app.zastra.vpn.core.pac.PacParser
import app.zastra.vpn.core.util.Net
import app.zastra.vpn.core.util.ZLog
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Превращает «куда хочет подключиться приложение» в цепочку действий из PAC
 * (HTTPS-прокси → PROXY → DIRECT). Само решение принимает PAC-сценарий (FindProxyForURL),
 * приложение ничего не знает о конкретных прокси-серверах.
 *
 * Для имён хоста решение принимается в два шага: сначала только по имени (без DNS),
 * и, если PAC ответил DIRECT, — ещё раз с IP-адресом (PAC проверяет и список IP).
 */
class RouteDecider(private val engine: PacEvaluator, private val dns: DnsService) {
    private class Entry(val hops: List<Hop>, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var cacheGeneration = engine.generation

    private val direct = listOf<Hop>(Hop.Direct)

    fun forHost(host: String, port: Int): List<Hop> {
        val h = Net.normalizeHost(host)
        if (!Net.isSafeHostname(h)) return direct
        val key = h + "|" + schemeFor(port)
        cached(key)?.let { return it }

        val url = urlFor(h, port)
        var hops = evaluate(url, h, null)
        if (hops.first().isDirect) {
            val ip = try {
                dns.resolve(h).firstOrNull { it is Inet4Address }?.hostAddress
            } catch (e: Exception) {
                ZLog.d(TAG, "не удалось определить IP для $h: ${e.message}")
                null
            }
            if (ip != null) hops = evaluate(url, h, ip)
        }
        store(key, hops)
        return hops
    }

    /** Соединение по IP-литералу (без имени): PAC видит placeholder-хост, dnsResolve вернёт этот IP. */
    fun forIp(ip: InetAddress, port: Int): List<Hop> {
        if (ip !is Inet4Address || ip.isLoopbackAddress || ip.isSiteLocalAddress ||
            ip.isLinkLocalAddress || ip.isAnyLocalAddress || ip.isMulticastAddress
        ) return direct
        val addr = ip.hostAddress ?: return direct
        val key = "ip|$addr"
        cached(key)?.let { return it }
        val hops = evaluate("https://$PLACEHOLDER_HOST/", PLACEHOLDER_HOST, addr)
        store(key, hops)
        return hops
    }

    fun clearCache() = cache.clear()

    private fun evaluate(url: String, host: String, ip: String?): List<Hop> {
        return try {
            PacParser.parse(engine.findProxy(url, host, ip))
        } catch (e: Exception) {
            ZLog.w(TAG, "PAC: ошибка для $host", e)
            direct
        }
    }

    private fun cached(key: String): List<Hop>? {
        val gen = engine.generation
        if (gen != cacheGeneration) {
            cache.clear()
            cacheGeneration = gen
            return null
        }
        val e = cache[key] ?: return null
        return if (e.expiresAtMs > System.currentTimeMillis()) e.hops else null
    }

    private fun store(key: String, hops: List<Hop>) {
        if (cache.size > MAX_ENTRIES) cache.clear()
        cache[key] = Entry(hops, System.currentTimeMillis() + TTL_MS)
    }

    private fun schemeFor(port: Int) = when (port) {
        80 -> "http"
        443 -> "https"
        else -> "p$port"
    }

    private fun urlFor(host: String, port: Int) = when (port) {
        80 -> "http://$host/"
        443 -> "https://$host/"
        else -> "https://$host:$port/"
    }

    private companion object {
        const val TAG = "Route"
        const val PLACEHOLDER_HOST = "zastra-ip.invalid"
        const val TTL_MS = 5 * 60_000L
        const val MAX_ENTRIES = 8192
    }
}
