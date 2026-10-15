package app.zastra.vpn.net

import app.zastra.vpn.core.dns.DnsTransport
import app.zastra.vpn.core.dns.DohTransport
import app.zastra.vpn.core.dns.FailoverDnsTransport
import app.zastra.vpn.core.dns.HttpPoster
import app.zastra.vpn.core.dns.UdpDnsTransport
import app.zastra.vpn.core.socks.SocketProtector
import app.zastra.vpn.core.util.Net
import app.zastra.vpn.data.DnsPresets
import app.zastra.vpn.data.DnsSettings
import java.net.InetSocketAddress

/** Собирает DNS-транспорт по настройкам пользователя: DoH или обычный DNS (по умолчанию — Google). */
object DnsFactory {
    fun create(s: DnsSettings, protector: SocketProtector, poster: HttpPoster): DnsTransport {
        val plain = UdpDnsTransport(plainServers(s), protector)
        if (!s.isDoh) return plain
        val doh = DohTransport(s.dohUrl, poster)
        return if (s.fallbackToPlain) FailoverDnsTransport(doh, plain, "DoH") else doh
    }

    /** Серверы обычного DNS; при пустых/неверных значениях — Google DNS. */
    fun plainServers(s: DnsSettings): List<InetSocketAddress> {
        val list = listOf(s.primary, s.secondary).mapNotNull { parseDnsAddress(it) }
        return list.ifEmpty {
            listOf(DnsPresets.DEFAULT_PRIMARY, DnsPresets.DEFAULT_SECONDARY).mapNotNull { parseDnsAddress(it) }
        }
    }

    /** Принимает IP-адрес (IPv4/IPv6) с необязательным портом: «8.8.8.8», «8.8.8.8:53», «[2001:4860:4860::8888]:53». */
    fun parseDnsAddress(text: String): InetSocketAddress? {
        val t = text.trim()
        if (t.isEmpty()) return null
        var host = t
        var port = 53
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end < 0) return null
            host = t.substring(1, end)
            val rest = t.substring(end + 1)
            if (rest.isNotEmpty()) port = rest.removePrefix(":").toIntOrNull() ?: return null
        } else if (t.count { it == ':' } == 1) {
            host = t.substringBefore(':')
            port = t.substringAfter(':').toIntOrNull() ?: return null
        }
        if (port !in 1..65535) return null
        val ip = Net.parseIpLiteral(host) ?: return null
        return InetSocketAddress(ip, port)
    }

    fun isValidDnsAddress(text: String): Boolean = parseDnsAddress(text) != null

    fun isValidDohUrl(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("https://", ignoreCase = true)) return false
        return try {
            val u = java.net.URI(t)
            !u.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
