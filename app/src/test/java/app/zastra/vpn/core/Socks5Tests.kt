package app.zastra.vpn.core

import app.zastra.vpn.core.dns.DnsMessage
import app.zastra.vpn.core.dns.DnsService
import app.zastra.vpn.core.socks.Dialer
import app.zastra.vpn.core.socks.RouteDecider
import app.zastra.vpn.core.socks.SocketProtector
import app.zastra.vpn.core.socks.Socks5Server
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class Socks5Tests {
    private val user = "zastra_user"
    private val pass = "s3cr3t-Pass"

    private lateinit var echo: EchoServer
    private lateinit var proxy: FakeConnectProxy
    private lateinit var server: Socks5Server
    private lateinit var pac: FakePac
    private var closedPort = 0

    private val dns = DnsService(
        FakeDnsTransport(
            mapOf(
                "direct.test" to "127.0.0.1",
                "blocked.test" to "127.0.0.1",
                "fallback.test" to "127.0.0.1",
            ),
        ),
    )

    @Before
    fun setUp() {
        echo = EchoServer()
        proxy = FakeConnectProxy(echo.port)
        closedPort = freePort()
        pac = FakePac { host, _ ->
            when (host) {
                "blocked.test" -> "PROXY 127.0.0.1:${proxy.port}; DIRECT"
                "fallback.test" -> "PROXY 127.0.0.1:$closedPort; DIRECT"
                else -> "DIRECT"
            }
        }
        val dialer = Dialer(RouteDecider(pac, dns), dns, SocketProtector.None, connectTimeoutMs = 3000, proxyTimeoutMs = 3000)
        server = Socks5Server(user, pass, dialer, dns, SocketProtector.None, rejectedIps = setOf("198.18.0.2"))
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        echo.close()
        proxy.close()
    }

    // ---- клиентские помощники ----

    private fun connectSocks(login: String = user, password: String = pass): Pair<Socket, Int> {
        val s = Socket("127.0.0.1", server.port)
        s.soTimeout = 5000
        val o = s.getOutputStream(); val i = s.getInputStream()
        o.write(byteArrayOf(5, 1, 2)); o.flush()
        assertArrayEquals(byteArrayOf(5, 2), readN(i, 2))
        val u = login.toByteArray(); val p = password.toByteArray()
        o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); o.flush()
        val auth = readN(i, 2)
        return s to auth[1].toInt()
    }

    private fun readN(i: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = i.read(b, off, n - off); if (r < 0) error("EOF"); off += r }
        return b
    }

    private fun request(s: Socket, cmd: Int, atyp: Int, addr: ByteArray, port: Int): Triple<Int, Int, ByteArray> {
        val o = s.getOutputStream()
        o.write(byteArrayOf(5, cmd.toByte(), 0, atyp.toByte()) + addr + byteArrayOf((port shr 8).toByte(), port.toByte())); o.flush()
        val head = readN(s.getInputStream(), 4)
        val len = when (head[3].toInt()) { 1 -> 4; 4 -> 16; else -> error("atyp") }
        val rest = readN(s.getInputStream(), len + 2)
        return Triple(head[1].toInt(), head[3].toInt(), rest)
    }

    private fun domain(name: String) = byteArrayOf(name.length.toByte()) + name.toByteArray()

    private fun echoRoundTrip(s: Socket, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        s.getOutputStream().write(bytes); s.getOutputStream().flush()
        assertEquals(text, String(readN(s.getInputStream(), bytes.size), Charsets.UTF_8))
    }

    // ---- тесты ----

    @Test
    fun rejectsBadCredentialsAndMissingAuthMethod() {
        val (s, code) = connectSocks(password = "wrong")
        assertEquals(1, code)
        s.close()
        val n = Socket("127.0.0.1", server.port)
        n.soTimeout = 3000
        n.getOutputStream().write(byteArrayOf(5, 1, 0)); n.getOutputStream().flush()
        assertArrayEquals(byteArrayOf(5, 0xff.toByte()), readN(n.getInputStream(), 2))
        n.close()
    }

    @Test
    fun directDomainConnectResolvesViaDnsAndRelays() {
        val (s, auth) = connectSocks()
        assertEquals(0, auth)
        val (rep, _, _) = request(s, 1, 3, domain("direct.test"), echo.port)
        assertEquals(0, rep)
        echoRoundTrip(s, "привет, мир")
        s.close()
        assertTrue(proxy.requests.isEmpty())
    }

    @Test
    fun blockedDomainGoesThroughConnectProxyWithHostname() {
        val (s, _) = connectSocks()
        val (rep, _, _) = request(s, 1, 3, domain("blocked.test"), 443)
        assertEquals(0, rep)
        echoRoundTrip(s, "через прокси")
        s.close()
        assertEquals(listOf("blocked.test:443"), proxy.requests.toList())
    }

    @Test
    fun deadProxyFallsBackToDirect() {
        val (s, _) = connectSocks()
        val (rep, _, _) = request(s, 1, 3, domain("fallback.test"), echo.port)
        assertEquals(0, rep)
        echoRoundTrip(s, "запасной маршрут")
        s.close()
    }

    @Test
    fun ipLiteralConnectAndRejectedDnsAddress() {
        val (s, _) = connectSocks()
        val (rep, _, _) = request(s, 1, 1, byteArrayOf(127, 0, 0, 1), echo.port)
        assertEquals(0, rep)
        echoRoundTrip(s, "ip")
        s.close()
        val (s2, _) = connectSocks()
        val (rep2, _, _) = request(s2, 1, 1, byteArrayOf(198.toByte(), 18, 0, 2), 853)
        assertEquals(5, rep2)
        s2.close()
    }

    @Test
    fun unresolvableHostReportsHostUnreachable() {
        val (s, _) = connectSocks()
        val (rep, _, _) = request(s, 1, 3, domain("nonexistent.test"), 80)
        assertEquals(4, rep)
        s.close()
    }

    @Test
    fun unsupportedCommandAndBadHostname() {
        val (s, _) = connectSocks()
        val (rep, _, _) = request(s, 2, 1, byteArrayOf(127, 0, 0, 1), 80) // BIND
        assertEquals(7, rep)
        s.close()
        val (s2, _) = connectSocks()
        val (rep2, _, _) = request(s2, 1, 3, domain("bad host!"), 80)
        assertEquals(8, rep2)
        s2.close()
    }

    @Test
    fun udpAssociateRelaysDirectDnsAndDropsProxiedTraffic() {
        val udpEcho = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        Thread {
            try {
                val b = ByteArray(2048)
                while (true) {
                    val p = DatagramPacket(b, b.size); udpEcho.receive(p)
                    udpEcho.send(DatagramPacket(b.copyOf(p.length), p.length, p.socketAddress))
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()

        val (ctl, _) = connectSocks()
        val (rep, _, rest) = request(ctl, 3, 1, byteArrayOf(0, 0, 0, 0), 0)
        assertEquals(0, rep)
        val relayPort = ((rest[4].toInt() and 0xff) shl 8) or (rest[5].toInt() and 0xff)
        val c = DatagramSocket()
        c.soTimeout = 1500
        val relay = InetSocketAddress("127.0.0.1", relayPort)

        fun frame(atyp: Int, addr: ByteArray, port: Int, payload: ByteArray) =
            byteArrayOf(0, 0, 0, atyp.toByte()) + addr + byteArrayOf((port shr 8).toByte(), port.toByte()) + payload

        // 1) прямой UDP по IP
        val f1 = frame(1, byteArrayOf(127, 0, 0, 1), udpEcho.localPort, "ping".toByteArray())
        c.send(DatagramPacket(f1, f1.size, relay))
        val buf = ByteArray(2048)
        val r1 = DatagramPacket(buf, buf.size); c.receive(r1)
        assertEquals("ping", String(buf, 10, r1.length - 10))
        assertEquals(1, buf[3].toInt())

        // 2) прямой UDP по доменному имени (fake-DNS hev)
        val f2 = frame(3, domain("direct.test"), udpEcho.localPort, "pong".toByteArray())
        c.send(DatagramPacket(f2, f2.size, relay))
        val r2 = DatagramPacket(buf, buf.size); c.receive(r2)
        assertEquals("pong", String(buf, 10, r2.length - 10))

        // 3) UDP/53 обслуживается настроенным DNS
        val q = DnsMessage.buildQuery("direct.test", DnsMessage.TYPE_A, 77)
        val f3 = frame(1, byteArrayOf(8, 8, 8, 8), 53, q)
        c.send(DatagramPacket(f3, f3.size, relay))
        val r3 = DatagramPacket(buf, buf.size); c.receive(r3)
        val dnsResp = DnsMessage.parse(buf.copyOfRange(10, r3.length))!!
        assertEquals(77, dnsResp.id)
        assertEquals("127.0.0.1", DnsMessage.addresses(dnsResp).first().hostAddress)

        // 4) адресат, которому по PAC нужен прокси, — датаграмма отбрасывается
        val f4 = frame(3, domain("blocked.test"), udpEcho.localPort, "quic".toByteArray())
        c.send(DatagramPacket(f4, f4.size, relay))
        c.soTimeout = 500
        var dropped = false
        try { c.receive(DatagramPacket(buf, buf.size)) } catch (_: SocketTimeoutException) { dropped = true }
        assertTrue(dropped)

        c.close(); ctl.close(); udpEcho.close()
    }

    @Test
    fun httpsProxyHopVerifiesCertificateAndFallsBack() {
        val ks = File.createTempFile("zastra-test", ".p12").apply { delete(); deleteOnExit() }
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        assumeTrue(keytool.exists())
        val pr = ProcessBuilder(
            keytool.path, "-genkeypair", "-alias", "z", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=zastra-test", "-ext", "san=ip:127.0.0.1", "-keystore", ks.path,
            "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        pr.inputStream.readBytes()
        assumeTrue(pr.waitFor() == 0)
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val serverCtx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val clientCtx = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }

        FakeConnectProxy(echo.port, serverCtx).use { tlsProxy ->
            val tlsPac = FakePac { host, _ ->
                when (host) {
                    "tls.test" -> "HTTPS 127.0.0.1:${tlsProxy.port}; DIRECT"
                    "mismatch.test" -> "HTTPS localhost:${tlsProxy.port}; DIRECT" // в сертификате только IP-адрес
                    else -> "DIRECT"
                }
            }
            val dns2 = DnsService(FakeDnsTransport(mapOf("tls.test" to "127.0.0.1", "mismatch.test" to "127.0.0.1")))
            val dialer = Dialer(
                RouteDecider(tlsPac, dns2), dns2, SocketProtector.None,
                sslFactory = { clientCtx.socketFactory }, connectTimeoutMs = 3000, proxyTimeoutMs = 3000,
            )
            val srv = Socks5Server(user, pass, dialer, dns2, SocketProtector.None)
            srv.start()
            try {
                fun open(host: String): Int {
                    val s = Socket("127.0.0.1", srv.port); s.soTimeout = 6000
                    val o = s.getOutputStream(); val i = s.getInputStream()
                    o.write(byteArrayOf(5, 1, 2)); readN(i, 2)
                    val u = user.toByteArray(); val p = pass.toByteArray()
                    o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); readN(i, 2)
                    o.write(byteArrayOf(5, 1, 0, 3) + domain(host) + byteArrayOf((echo.port shr 8).toByte(), echo.port.toByte()))
                    val head = readN(i, 4); readN(i, 6)
                    if (head[1].toInt() == 0) echoRoundTrip(s, "tls-ok")
                    s.close()
                    return head[1].toInt()
                }
                assertEquals(0, open("tls.test"))
                assertEquals(listOf("tls.test:${echo.port}"), tlsProxy.requests.toList())
                // имя «localhost» не совпадает с сертификатом → TLS-прокси отвергнут, сработал DIRECT
                assertEquals(0, open("mismatch.test"))
                assertEquals(1, tlsProxy.requests.size)
            } finally {
                srv.stop()
            }
        }
    }
}
