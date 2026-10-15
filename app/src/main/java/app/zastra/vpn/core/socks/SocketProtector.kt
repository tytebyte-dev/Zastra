package app.zastra.vpn.core.socks

import java.net.DatagramSocket
import java.net.Socket

/**
 * Исключает сокет из VPN (на Android — VpnService.protect). Без защиты исходящие соединения
 * самого приложения зациклились бы через TUN.
 */
interface SocketProtector {
    fun protect(socket: Socket): Boolean
    fun protect(socket: DatagramSocket): Boolean

    object None : SocketProtector {
        override fun protect(socket: Socket) = true
        override fun protect(socket: DatagramSocket) = true
    }
}
