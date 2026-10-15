package app.zastra.vpn.core

import app.zastra.vpn.core.pac.Hop
import app.zastra.vpn.core.pac.PacEngine
import app.zastra.vpn.core.pac.PacNative
import app.zastra.vpn.core.pac.PacParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PacTests {
    @Test
    fun parserHandlesRealisticResults() {
        val hops = PacParser.parse("HTTPS 176.123.8.97:8443; PROXY n.thenewone.lol:29976; DIRECT")
        assertEquals(
            listOf(Hop.HttpProxy("176.123.8.97", 8443, true), Hop.HttpProxy("n.thenewone.lol", 29976, false), Hop.Direct),
            hops,
        )
        assertEquals(listOf<Hop>(Hop.Direct), PacParser.parse(""))
        assertEquals(listOf<Hop>(Hop.Direct), PacParser.parse(null))
        assertEquals(listOf<Hop>(Hop.Direct), PacParser.parse("SOCKS4 1.2.3.4:1080"))
        assertEquals(listOf<Hop>(Hop.Socks("[::1]".trim('[', ']'), 1080), Hop.Direct), PacParser.parse("socks5 [::1]:1080 ; direct"))
        assertEquals(listOf<Hop>(Hop.Direct), PacParser.parse("PROXY broken"))
    }

    /** Нужна нативная библиотека для хоста: -Dzastra.pac.lib=/path/libzastra-pac.so */
    @Test
    fun realPacScriptRoutesBlockedAndOpenSites() {
        val lib = System.getProperty("zastra.pac.lib")
        assumeTrue(lib != null && File(lib).exists())
        PacNative.libraryLoader = { System.load(lib) }
        val pac = File("src/main/assets/proxy.pac")
        assumeTrue(pac.exists())
        PacEngine().use { e ->
            e.load(pac.readText())
            fun route(host: String, ip: String? = null) = PacParser.parse(e.findProxy("https://$host/", host, ip))
            assertTrue(route("rutracker.org").first() is Hop.HttpProxy)
            assertTrue(route("www.rutracker.org").first() is Hop.HttpProxy)
            assertTrue(route("example.com").first().isDirect)
            assertTrue(route("yandex.ru").first().isDirect)
            // IP-ветка: хост-заглушка + известный заблокированный IP
            val blockedIp = route("zastra-ip.invalid", "3.8.209.38")
            assertTrue(blockedIp.first() is Hop.HttpProxy)
            assertTrue(route("zastra-ip.invalid", "93.184.216.34").first().isDirect)
            // хост с «подозрительными» символами не должен ломать скрипт
            assertTrue(e.findProxy("https://a\"b/", "a\"b\\", null).isNotEmpty())
        }
    }
}
