package app.zastra.vpn.core

import app.zastra.vpn.core.dns.DnsMessage
import app.zastra.vpn.core.dns.DnsService
import app.zastra.vpn.core.dns.DohTransport
import app.zastra.vpn.core.dns.FailoverDnsTransport
import app.zastra.vpn.core.dns.HttpPoster
import app.zastra.vpn.core.dns.UdpDnsTransport
import app.zastra.vpn.core.socks.SocketProtector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class DnsTests {
    @Test
    fun queryRoundTripAndCompressedAnswer() {
        val q = DnsMessage.buildQuery("Example.COM", DnsMessage.TYPE_A, 0x1234)
        val pq = DnsMessage.parse(q)!!
        assertEquals(0x1234, pq.id)
        assertEquals("Example.COM", pq.questionName)
        assertEquals(DnsMessage.TYPE_A, pq.questionType)
        val resp = TestDns.response(q, "93.184.216.34")
        val pr = DnsMessage.parse(resp)!!
        assertTrue(pr.isResponse)
        assertEquals(0, pr.rcode)
        assertEquals(listOf("93.184.216.34"), DnsMessage.addresses(pr).map { it.hostAddress })
        assertEquals(60, DnsMessage.minTtl(pr))
        val nx = DnsMessage.parse(TestDns.response(q, null))!!
        assertEquals(DnsMessage.RCODE_NXDOMAIN, nx.rcode)
    }

    @Test
    fun malformedMessagesAreRejected() {
        assertEquals(null, DnsMessage.parse(ByteArray(5)))
        val q = DnsMessage.buildQuery("a.test", 1, 1)
        assertEquals(null, DnsMessage.parse(q.copyOf(15)))
        // петля из указателей сжатия
        val loop = byteArrayOf(0, 1, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 0, 0, 0, 0, 0, 0xc0.toByte(), 12, 0, 1, 0, 1)
        assertEquals(null, DnsMessage.parse(loop))
    }

    @Test
    fun serviceCachesAndResolvesLiterals() {
        val t = FakeDnsTransport(mapOf("a.test" to "10.1.2.3"))
        val s = DnsService(t)
        assertEquals("10.1.2.3", s.resolve("a.test").first().hostAddress)
        assertEquals("10.1.2.3", s.resolve("A.TEST.").first().hostAddress)
        assertEquals(1, t.queries.get()) // второй вызов — из кэша
        assertEquals("1.2.3.4", s.resolve("1.2.3.4").first().hostAddress)
        assertEquals(1, t.queries.get())
        assertTrue(s.resolve("nxdomain.test").isEmpty())
    }

    @Test
    fun dohUsesZeroIdOnWireAndRestoresId() {
        var wireId = -1
        val poster = object : HttpPoster {
            override fun post(url: String, contentType: String, body: ByteArray): ByteArray {
                assertEquals("application/dns-message", contentType)
                wireId = DnsMessage.readId(body)
                return TestDns.response(body, "9.9.9.9")
            }
        }
        val q = DnsMessage.buildQuery("doh.test", 1, 0x4242)
        val r = DohTransport("https://dns.example/dns-query", poster).exchange(q)
        assertEquals(0, wireId)
        assertEquals(0x4242, DnsMessage.readId(r))
    }

    @Test
    fun failoverFallsBackToSecondary() {
        val bad = object : app.zastra.vpn.core.dns.DnsTransport {
            override fun exchange(query: ByteArray): ByteArray = throw IOException("DoH недоступен")
        }
        val good = FakeDnsTransport(mapOf("x.test" to "7.7.7.7"))
        val s = DnsService(FailoverDnsTransport(bad, good, "DoH"))
        assertEquals("7.7.7.7", s.resolve("x.test").first().hostAddress)
    }

    @Test
    fun udpTransportAnswersAndFallsBackToTcpOnTruncation() {
        val udp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port = udp.localPort
        val tcp = ServerSocket(port, 5, InetAddress.getByName("127.0.0.1"))
        Thread {
            try {
                val buf = ByteArray(1500)
                while (true) {
                    val p = DatagramPacket(buf, buf.size)
                    udp.receive(p)
                    val q = buf.copyOf(p.length)
                    val name = DnsMessage.parse(q)!!.questionName
                    var resp = TestDns.response(q, "5.5.5.5")
                    if (name == "big.test") resp = resp.copyOf().also { it[2] = (it[2].toInt() or 0x02).toByte() } // TC
                    udp.send(DatagramPacket(resp, resp.size, p.socketAddress))
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
        Thread {
            try {
                while (true) {
                    val c = tcp.accept()
                    val i = DataInputStream(c.getInputStream())
                    val len = i.readUnsignedShort(); val q = ByteArray(len); i.readFully(q)
                    val resp = TestDns.response(q, "6.6.6.6")
                    c.getOutputStream().write(byteArrayOf((resp.size shr 8).toByte(), resp.size.toByte()) + resp)
                    c.close()
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()

        val tr = UdpDnsTransport(listOf(InetSocketAddress("127.0.0.1", port)), SocketProtector.None, 1500)
        val s = DnsService(tr)
        assertEquals("5.5.5.5", s.resolve("small.test").first().hostAddress)
        assertEquals("6.6.6.6", s.resolve("big.test").first().hostAddress)
        udp.close(); tcp.close()
    }

    @Test
    fun udpTransportTimesOutAndReportsError() {
        val dead = DatagramSocket(0, InetAddress.getByName("127.0.0.1")) // никто не отвечает
        val tr = UdpDnsTransport(listOf(InetSocketAddress("127.0.0.1", dead.localPort)), SocketProtector.None, 300)
        try {
            DnsService(tr).resolve("never.test")
            fail("ожидалось исключение")
        } catch (e: IOException) {
            assertNotNull(e.message)
        } finally {
            dead.close()
        }
    }
}
