package app.zastra.vpn.core.dns

import app.zastra.vpn.core.socks.SocketProtector
import app.zastra.vpn.core.util.ZLog
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Обмен «сырыми» DNS-сообщениями с вышестоящим сервером. */
interface DnsTransport {
    @Throws(IOException::class)
    fun exchange(query: ByteArray): ByteArray
}

/** Минимальный HTTP-клиент для DoH (на Android — поверх OkHttp). */
interface HttpPoster {
    @Throws(IOException::class)
    fun post(url: String, contentType: String, body: ByteArray): ByteArray
}

/** Обычный DNS: UDP/53 с переходом на TCP при усечённом ответе. Серверы опрашиваются по очереди. */
class UdpDnsTransport(
    private val servers: List<InetSocketAddress>,
    private val protector: SocketProtector,
    private val timeoutMs: Int = 2500,
) : DnsTransport {

    override fun exchange(query: ByteArray): ByteArray {
        var last: IOException? = null
        for (server in servers) {
            try {
                return exchangeWith(server, query)
            } catch (e: IOException) {
                last = e
                ZLog.d(TAG, "DNS $server: ${e.message}")
            }
        }
        throw last ?: IOException("DNS-серверы не заданы")
    }

    private fun exchangeWith(server: InetSocketAddress, query: ByteArray): ByteArray {
        val id = DnsMessage.readId(query)
        val udpResponse = DatagramSocket().use { s ->
            protector.protect(s)
            s.soTimeout = timeoutMs
            s.connect(server)
            s.send(DatagramPacket(query, query.size))
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            val buf = ByteArray(4096)
            var result: ByteArray? = null
            while (result == null) {
                val left = (deadline - System.nanoTime()) / 1_000_000L
                if (left <= 0) throw SocketTimeoutException("DNS: таймаут")
                s.soTimeout = left.toInt()
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val data = buf.copyOf(p.length)
                val parsed = DnsMessage.parse(data)
                if (parsed != null && parsed.isResponse && parsed.id == id) result = data
            }
            result
        }
        val parsed = DnsMessage.parse(udpResponse)
        if (parsed != null && parsed.truncated) return exchangeTcp(server, query)
        return udpResponse
    }

    private fun exchangeTcp(server: InetSocketAddress, query: ByteArray): ByteArray {
        Socket().use { s ->
            protector.protect(s)
            s.soTimeout = timeoutMs * 2
            s.connect(server, timeoutMs)
            val out = s.getOutputStream()
            val framed = ByteArrayOutputStream(query.size + 2)
            framed.write((query.size shr 8) and 0xff)
            framed.write(query.size and 0xff)
            framed.write(query)
            out.write(framed.toByteArray())
            out.flush()
            val inp = DataInputStream(s.getInputStream())
            val len = inp.readUnsignedShort()
            val resp = ByteArray(len)
            inp.readFully(resp)
            return resp
        }
    }

    companion object {
        private const val TAG = "DNS"
    }
}

/** DNS поверх HTTPS (RFC 8484, POST application/dns-message). */
class DohTransport(private val url: String, private val poster: HttpPoster) : DnsTransport {
    override fun exchange(query: ByteArray): ByteArray {
        val id = DnsMessage.readId(query)
        // RFC 8484 §4.1: рекомендуется ID = 0 (дружелюбно к HTTP-кэшам); исходный ID возвращаем в ответе.
        val response = poster.post(url, "application/dns-message", DnsMessage.withId(query, 0))
        if (response.size < 12) throw IOException("DoH: пустой или повреждённый ответ")
        return DnsMessage.withId(response, id)
    }
}

/** Пробует основной транспорт, при сбое — запасной. */
class FailoverDnsTransport(
    private val primary: DnsTransport,
    private val secondary: DnsTransport,
    private val label: String,
) : DnsTransport {
    @Volatile
    private var lastWarn = 0L

    override fun exchange(query: ByteArray): ByteArray {
        try {
            return primary.exchange(query)
        } catch (e: IOException) {
            val now = System.currentTimeMillis()
            if (now - lastWarn > 60_000) {
                lastWarn = now
                ZLog.w("DNS", "$label недоступен, используется обычный DNS", e)
            }
            return secondary.exchange(query)
        }
    }
}
