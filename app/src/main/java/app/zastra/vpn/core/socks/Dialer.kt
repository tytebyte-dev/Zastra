package app.zastra.vpn.core.socks

import app.zastra.vpn.core.dns.DnsService
import app.zastra.vpn.core.pac.Hop
import app.zastra.vpn.core.util.Net
import app.zastra.vpn.core.util.ZLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Устанавливает исходящее TCP-соединение по цепочке из PAC: пробует элементы по порядку
 * (HTTPS-прокси, PROXY, SOCKS5, DIRECT). Прокси, к которому не удалось подключиться,
 * на несколько минут отодвигается в конец очереди (как это делают браузеры).
 */
class Dialer(
    private val router: RouteDecider,
    private val dns: DnsService,
    private val protector: SocketProtector,
    private val sslFactory: () -> SSLSocketFactory = { SSLSocketFactory.getDefault() as SSLSocketFactory },
    private val connectTimeoutMs: Int = 10_000,
    private val proxyTimeoutMs: Int = 8_000,
) {
    private val badUntil = ConcurrentHashMap<Hop, Long>()

    /** Разрешено ли отправлять UDP к этому адресату напрямую (через прокси UDP не передать). */
    fun udpDirectAllowed(target: Target): Boolean {
        val hops = hopsFor(target)
        return hops.first().isDirect
    }

    @Throws(IOException::class)
    fun connect(target: Target): Socket {
        val hops = order(hopsFor(target))
        var last: IOException? = null
        for (hop in hops) {
            try {
                val s = when (hop) {
                    is Hop.Direct -> connectDirect(target)
                    is Hop.HttpProxy -> connectViaHttp(hop, target)
                    is Hop.Socks -> connectViaSocks(hop, target)
                }
                if (!hop.isDirect) badUntil.remove(hop)
                ZLog.d(TAG, "${target.authority} -> $hop")
                return s
            } catch (e: IOException) {
                last = e
                if (!hop.isDirect) {
                    badUntil[hop] = System.currentTimeMillis() + BAD_PROXY_MS
                    ZLog.w(TAG, "$hop недоступен для ${target.authority}", e)
                } else {
                    ZLog.d(TAG, "прямое подключение к ${target.authority}: ${e.message}")
                }
            }
        }
        throw last ?: NoRouteToHostException("нет маршрута")
    }

    private fun hopsFor(target: Target): List<Hop> =
        if (target.host != null) router.forHost(target.host, target.port) else router.forIp(target.ip!!, target.port)

    /** Недавно сбойнувшие прокси — в конец, порядок остальных сохраняется. */
    private fun order(hops: List<Hop>): List<Hop> {
        if (hops.size < 2) return hops
        val now = System.currentTimeMillis()
        val good = ArrayList<Hop>(hops.size)
        val bad = ArrayList<Hop>(0)
        for (h in hops) {
            val until = badUntil[h]
            if (until != null && until > now) bad.add(h) else good.add(h)
        }
        return good + bad
    }

    // ---------- DIRECT ----------

    private fun connectDirect(target: Target): Socket {
        if (target.ip != null) return openSocket(InetSocketAddress(target.ip, target.port), connectTimeoutMs)
        val addrs = dns.resolve(target.host!!)
        if (addrs.isEmpty()) throw UnknownHostException(target.host)
        var last: IOException? = null
        val deadline = System.currentTimeMillis() + connectTimeoutMs + 2000L
        for ((i, a) in addrs.withIndex()) {
            if (i >= MAX_ADDRESSES) break
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left <= 0) break
            try {
                return openSocket(InetSocketAddress(a, target.port), minOf(connectTimeoutMs, left))
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("не удалось подключиться к ${target.host}")
    }

    // ---------- HTTP / HTTPS CONNECT ----------

    private fun connectViaHttp(hop: Hop.HttpProxy, target: Target): Socket {
        var sock: Socket = openProxySocket(hop.host, hop.port)
        try {
            if (hop.tls) sock = wrapTls(sock, hop.host, hop.port)
            sock.soTimeout = proxyTimeoutMs
            val authority = target.authority
            val request = "CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n" +
                "Proxy-Connection: Keep-Alive\r\nUser-Agent: Zastra\r\n\r\n"
            sock.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val head = readHttpHead(sock.getInputStream())
            val statusLine = head.substringBefore("\r\n")
            val parts = statusLine.split(' ', limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull()
            if (code == null || code !in 200..299) {
                throw IOException("прокси ответил: " + statusLine.take(80))
            }
            sock.soTimeout = 0
            return sock
        } catch (e: Throwable) {
            closeQuietly(sock)
            throw e as? IOException ?: IOException(e)
        }
    }

    private fun wrapTls(plain: Socket, host: String, port: Int): SSLSocket {
        val ssl = sslFactory().createSocket(plain, host, port, true) as SSLSocket
        try {
            val params = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS" // проверка имени/IP в сертификате
            ssl.sslParameters = params
            ssl.soTimeout = proxyTimeoutMs
            ssl.startHandshake()
            return ssl
        } catch (e: Throwable) {
            closeQuietly(ssl)
            throw e as? IOException ?: IOException(e)
        }
    }

    private fun readHttpHead(input: InputStream): String {
        val buf = ByteArrayOutputStream(256)
        var state = 0 // сопоставление "\r\n\r\n"
        while (buf.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) throw IOException("прокси закрыл соединение")
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) return buf.toString("ISO-8859-1")
        }
        throw IOException("слишком длинный ответ прокси")
    }

    // ---------- SOCKS5 (без аутентификации) ----------

    private fun connectViaSocks(hop: Hop.Socks, target: Target): Socket {
        val sock = openProxySocket(hop.host, hop.port)
        try {
            sock.soTimeout = proxyTimeoutMs
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            val greeting = readFully(inp, 2)
            if (greeting[0].toInt() != 5 || greeting[1].toInt() != 0) throw IOException("SOCKS5: метод не принят")
            val req = ByteArrayOutputStream()
            req.write(byteArrayOf(5, 1, 0))
            if (target.host != null) {
                val name = target.host.toByteArray(Charsets.US_ASCII)
                req.write(3)
                req.write(name.size)
                req.write(name)
            } else {
                val raw = target.ip!!.address
                req.write(if (raw.size == 4) 1 else 4)
                req.write(raw)
            }
            req.write(target.port shr 8)
            req.write(target.port and 0xff)
            out.write(req.toByteArray())
            out.flush()
            val head = readFully(inp, 4)
            if (head[1].toInt() != 0) throw IOException("SOCKS5: ошибка " + (head[1].toInt() and 0xff))
            val skip = when (head[3].toInt()) {
                1 -> 4
                4 -> 16
                3 -> (readFully(inp, 1)[0].toInt() and 0xff)
                else -> throw IOException("SOCKS5: неверный тип адреса")
            }
            readFully(inp, skip + 2)
            sock.soTimeout = 0
            return sock
        } catch (e: Throwable) {
            closeQuietly(sock)
            throw e as? IOException ?: IOException(e)
        }
    }

    // ---------- вспомогательное ----------

    private fun openProxySocket(host: String, port: Int): Socket {
        val addrs: List<InetAddress> = Net.parseIpLiteral(host)?.let { listOf(it) } ?: run {
            val fromOurDns = try {
                dns.resolve(host)
            } catch (_: IOException) {
                emptyList()
            }
            if (fromOurDns.isNotEmpty()) fromOurDns else InetAddress.getAllByName(host).toList()
        }
        var last: IOException? = null
        for ((i, a) in addrs.withIndex()) {
            if (i >= MAX_ADDRESSES) break
            try {
                return openSocket(InetSocketAddress(a, port), proxyTimeoutMs)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: UnknownHostException(host)
    }

    private fun openSocket(addr: InetSocketAddress, timeoutMs: Int): Socket {
        val s = Socket()
        try {
            protector.protect(s)
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(addr, timeoutMs)
            return s
        } catch (e: Throwable) {
            closeQuietly(s)
            throw e as? IOException ?: IOException(e)
        }
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw IOException("неожиданный конец потока")
            off += r
        }
        return b
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "Dialer"
        const val BAD_PROXY_MS = 3 * 60_000L
        const val MAX_ADDRESSES = 3
        const val MAX_HEAD = 16 * 1024
    }
}
