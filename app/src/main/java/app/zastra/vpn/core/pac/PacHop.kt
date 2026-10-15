package app.zastra.vpn.core.pac

/** Один элемент результата FindProxyForURL: DIRECT / PROXY / HTTPS / SOCKS. */
sealed class Hop {
    object Direct : Hop() {
        override fun toString() = "DIRECT"
    }

    /** HTTP CONNECT-прокси; [tls] = true для «HTTPS host:port» (CONNECT внутри TLS). */
    data class HttpProxy(val host: String, val port: Int, val tls: Boolean) : Hop() {
        override fun toString() = (if (tls) "HTTPS " else "PROXY ") + host + ":" + port
    }

    /** SOCKS5-прокси без аутентификации. */
    data class Socks(val host: String, val port: Int) : Hop() {
        override fun toString() = "SOCKS5 $host:$port"
    }

    val isDirect: Boolean get() = this === Direct
}

object PacParser {
    /**
     * Разбирает строку вида «HTTPS 1.2.3.4:8443; PROXY host:3128; DIRECT».
     * Неизвестные и неподдерживаемые (SOCKS4) элементы пропускаются.
     * Пустой результат трактуется как DIRECT.
     */
    fun parse(result: String?): List<Hop> {
        if (result.isNullOrBlank()) return listOf(Hop.Direct)
        val hops = ArrayList<Hop>(4)
        for (raw in result.split(';')) {
            val token = raw.trim()
            if (token.isEmpty()) continue
            val space = token.indexOf(' ')
            val kind = (if (space < 0) token else token.substring(0, space)).uppercase()
            val arg = if (space < 0) "" else token.substring(space + 1).trim()
            when (kind) {
                "DIRECT" -> hops.add(Hop.Direct)
                "PROXY", "HTTP" -> hostPort(arg)?.let { hops.add(Hop.HttpProxy(it.first, it.second, false)) }
                "HTTPS" -> hostPort(arg)?.let { hops.add(Hop.HttpProxy(it.first, it.second, true)) }
                "SOCKS", "SOCKS5" -> hostPort(arg)?.let { hops.add(Hop.Socks(it.first, it.second)) }
                else -> Unit // SOCKS4 и прочее не поддерживаются
            }
        }
        return if (hops.isEmpty()) listOf(Hop.Direct) else hops
    }

    private fun hostPort(s: String): Pair<String, Int>? {
        if (s.isEmpty()) return null
        val host: String
        val portStr: String
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            host = s.substring(1, end)
            portStr = s.substring(end + 1).removePrefix(":")
        } else {
            val colon = s.lastIndexOf(':')
            if (colon < 0) return null
            host = s.substring(0, colon)
            portStr = s.substring(colon + 1)
        }
        val port = portStr.toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }
}
