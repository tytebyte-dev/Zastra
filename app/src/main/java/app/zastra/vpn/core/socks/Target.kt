package app.zastra.vpn.core.socks

import app.zastra.vpn.core.util.Net
import java.net.InetAddress

/** Назначение соединения: либо имя хоста (fake-DNS hev / внешний клиент), либо IP-литерал. */
class Target private constructor(val host: String?, val ip: InetAddress?, val port: Int) {
    val authority: String
        get() = if (host != null) Net.hostPort(host, port) else Net.hostPort(ip!!.hostAddress ?: "0.0.0.0", port)

    override fun toString() = authority

    companion object {
        fun ofHost(host: String, port: Int) = Target(host, null, port)
        fun ofIp(ip: InetAddress, port: Int) = Target(null, ip, port)
    }
}
