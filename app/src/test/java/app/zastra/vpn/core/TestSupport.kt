package app.zastra.vpn.core

import app.zastra.vpn.core.dns.DnsMessage
import app.zastra.vpn.core.dns.DnsTransport
import app.zastra.vpn.core.pac.PacEvaluator
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext

object TestDns {
    /** Собирает DNS-ответ на [query]: A-запись, если [ip] != null, иначе NXDOMAIN. */
    fun response(query: ByteArray, ip: String?, aType: Boolean = true): ByteArray {
        var pos = 12
        while (query[pos].toInt() != 0) pos += (query[pos].toInt() and 0xff) + 1
        pos += 1 + 4 // нулевая метка + QTYPE + QCLASS
        val question = query.copyOfRange(12, pos)
        val out = ByteArrayOutputStream()
        out.write(query[0].toInt()); out.write(query[1].toInt())
        val answer = ip != null && aType
        out.write(0x81); out.write(if (ip == null) 0x83 else 0x80)
        out.write(0); out.write(1)
        out.write(0); out.write(if (answer) 1 else 0)
        out.write(0); out.write(0); out.write(0); out.write(0)
        out.write(question)
        if (answer) {
            out.write(0xc0); out.write(0x0c)
            out.write(0); out.write(1); out.write(0); out.write(1)
            out.write(0); out.write(0); out.write(0); out.write(60)
            out.write(0); out.write(4)
            out.write(InetAddress.getByName(ip).address)
        }
        return out.toByteArray()
    }
}

class FakeDnsTransport(private val table: Map<String, String>) : DnsTransport {
    val queries = AtomicInteger()
    override fun exchange(query: ByteArray): ByteArray {
        queries.incrementAndGet()
        val p = DnsMessage.parse(query)!!
        val ip = table[p.questionName]
        return TestDns.response(query, ip, aType = p.questionType == DnsMessage.TYPE_A)
    }
}

class FakePac(private val rules: (String, String?) -> String) : PacEvaluator {
    override var generation: Int = 1
    val calls = CopyOnWriteArrayList<String>()
    override fun findProxy(url: String, host: String, ip: String?): String {
        calls.add("$host/$ip")
        return rules(host, ip)
    }
}

/** TCP-эхо-сервер. */
class EchoServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    init {
        Thread {
            while (!server.isClosed) {
                try {
                    val c = server.accept()
                    Thread {
                        try {
                            val i = c.getInputStream(); val o = c.getOutputStream()
                            val b = ByteArray(4096)
                            while (true) {
                                val n = i.read(b); if (n < 0) break
                                o.write(b, 0, n); o.flush()
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { c.close() } catch (_: Exception) {}
                        }
                    }.apply { isDaemon = true }.start()
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun close() = server.close()
}

/** HTTP CONNECT-прокси (опционально поверх TLS). Все назначения перенаправляет на [echoPort]. */
class FakeConnectProxy(private val echoPort: Int, tls: SSLContext? = null) : AutoCloseable {
    private val server: ServerSocket =
        if (tls == null) ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        else tls.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val requests = CopyOnWriteArrayList<String>()

    init {
        Thread {
            while (!server.isClosed) {
                try {
                    val c = server.accept()
                    Thread { serve(c) }.apply { isDaemon = true }.start()
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(c: Socket) {
        try {
            val i = c.getInputStream(); val o = c.getOutputStream()
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val b = i.read(); if (b < 0) return
                sb.append(b.toChar())
            }
            val line = sb.lineSequence().first()
            requests.add(line.split(' ')[1])
            val up = Socket("127.0.0.1", echoPort)
            o.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); o.flush()
            val t = Thread { copy(up.getInputStream(), o) }.apply { isDaemon = true }
            t.start()
            copy(i, up.getOutputStream())
            t.join(2000)
            up.close()
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun copy(i: InputStream, o: OutputStream) {
        try {
            val b = ByteArray(4096)
            while (true) {
                val n = i.read(b); if (n < 0) break
                o.write(b, 0, n); o.flush()
            }
        } catch (_: Exception) {
        }
    }

    override fun close() = server.close()
}

fun freePort(): Int = ServerSocket(0).use { it.localPort }

fun loopback(): InetSocketAddress = InetSocketAddress("127.0.0.1", 0)
