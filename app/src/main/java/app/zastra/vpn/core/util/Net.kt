package app.zastra.vpn.core.util

import java.net.Inet4Address
import java.net.InetAddress

/** Небольшие сетевые утилиты, не зависящие от Android. */
object Net {
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

    fun isIpv4Literal(s: String): Boolean = IPV4.matches(s)

    fun isIpv6Literal(s: String): Boolean {
        if (!s.contains(':')) return false
        val body = s.removePrefix("[").removeSuffix("]")
        if (!body.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return false
        return try {
            InetAddress.getByName(body) is java.net.Inet6Address
        } catch (_: Exception) {
            false
        }
    }

    fun isIpLiteral(s: String): Boolean = isIpv4Literal(s) || isIpv6Literal(s)

    /** Разбирает литерал IPv4/IPv6 без обращения к DNS. */
    fun parseIpLiteral(s: String): InetAddress? {
        val body = s.removePrefix("[").removeSuffix("]")
        if (!isIpLiteral(body)) return null
        return try {
            InetAddress.getByName(body)
        } catch (_: Exception) {
            null
        }
    }

    /** Нормализует имя хоста: нижний регистр, без точки в конце. */
    fun normalizeHost(h: String): String = h.trim().trimEnd('.').lowercase()

    /** Допустимое DNS-имя (буквы, цифры, '-', '_', '.') — защита от инъекций в JS и CONNECT. */
    fun isSafeHostname(h: String): Boolean {
        if (h.isEmpty() || h.length > 253) return false
        for (c in h) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.'
            if (!ok) return false
        }
        return true
    }

    fun hostPort(host: String, port: Int): String =
        if (host.contains(':') && !host.startsWith("[")) "[" + host + "]:" + port else host + ":" + port

    fun ipv4String(a: InetAddress?): String? = (a as? Inet4Address)?.hostAddress
}
