package app.zastra.vpn.core.dns

import app.zastra.vpn.core.util.Net
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Резолвер приложения: кэш с учётом TTL поверх выбранного транспорта (обычный DNS или DoH).
 * Транспорт можно подменять на лету (смена настроек DNS без переподключения VPN).
 */
class DnsService(transport: DnsTransport) {
    @Volatile
    private var transport: DnsTransport = transport

    private class Entry(val addresses: List<InetAddress>, val expiresAtNanos: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun setTransport(t: DnsTransport) {
        transport = t
        cache.clear()
    }

    /**
     * Возвращает IP-адреса хоста (IPv4 — первыми). Пустой список — имя не существует.
     * @throws IOException если вышестоящий DNS недоступен.
     */
    @Throws(IOException::class)
    fun resolve(host: String): List<InetAddress> {
        val h = Net.normalizeHost(host)
        Net.parseIpLiteral(h)?.let { return listOf(it) }
        if (!Net.isSafeHostname(h)) return emptyList()

        val now = System.nanoTime()
        cache[h]?.let { if (it.expiresAtNanos - now > 0) return it.addresses }

        var result = query(h, DnsMessage.TYPE_A)
        if (result.addresses.isEmpty() && result.rcode == DnsMessage.RCODE_NOERROR) {
            val v6 = query(h, DnsMessage.TYPE_AAAA)
            if (v6.addresses.isNotEmpty()) result = v6
        }
        val ttl = when {
            result.addresses.isNotEmpty() -> result.ttl.coerceIn(MIN_TTL_SEC, MAX_TTL_SEC)
            else -> NEGATIVE_TTL_SEC
        }
        if (cache.size > MAX_ENTRIES) cache.clear()
        cache[h] = Entry(result.addresses, System.nanoTime() + ttl * 1_000_000_000L)
        return result.addresses
    }

    /** Пересылает готовый DNS-запрос (для приложений с собственным DNS на UDP/53). */
    @Throws(IOException::class)
    fun exchange(query: ByteArray): ByteArray = transport.exchange(query)

    private class Result(val addresses: List<InetAddress>, val ttl: Long, val rcode: Int)

    private fun query(name: String, type: Int): Result {
        val id = ThreadLocalRandom.current().nextInt(0x10000)
        val q = DnsMessage.buildQuery(name, type, id)
        val resp = transport.exchange(q)
        val parsed = DnsMessage.parse(resp) ?: throw IOException("DNS: повреждённый ответ")
        if (parsed.id != id || !parsed.isResponse) throw IOException("DNS: ответ не соответствует запросу")
        return Result(DnsMessage.addresses(parsed), DnsMessage.minTtl(parsed), parsed.rcode)
    }

    private companion object {
        const val MIN_TTL_SEC = 30L
        const val MAX_TTL_SEC = 3600L
        const val NEGATIVE_TTL_SEC = 15L
        const val MAX_ENTRIES = 4096
    }
}
