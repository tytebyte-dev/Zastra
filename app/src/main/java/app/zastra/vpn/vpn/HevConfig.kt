package app.zastra.vpn.vpn

/**
 * Конфигурация hev-socks5-tunnel (tun2socks). Трафик из TUN уходит в локальный SOCKS5-сервер
 * Zastra (127.0.0.1) с логином/паролем. Встроенный «mapped DNS» отвечает приложениям
 * виртуальными адресами и передаёт в SOCKS5 имя хоста — по нему PAC принимает решение.
 */
object HevConfig {
    const val TUN_ADDRESS = "198.18.0.1"
    const val TUN_DNS = "198.18.0.2"
    const val MTU = 1500

    fun build(socksPort: Int, user: String, pass: String): String = """
        tunnel:
          mtu: $MTU
          ipv4: $TUN_ADDRESS
        socks5:
          port: $socksPort
          address: 127.0.0.1
          udp: 'udp'
          username: '$user'
          password: '$pass'
        mapdns:
          address: $TUN_DNS
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000
        misc:
          connect-timeout: 10000
          tcp-read-write-timeout: 300000
          udp-read-write-timeout: 60000
          log-file: stderr
          log-level: warn
    """.trimIndent() + "\n"
}
