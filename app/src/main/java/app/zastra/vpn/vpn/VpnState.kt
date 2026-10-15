package app.zastra.vpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

data class VpnSnapshot(val status: VpnStatus = VpnStatus.DISCONNECTED, val error: String? = null)

data class Traffic(val uploadBytes: Long = 0, val downloadBytes: Long = 0, val connections: Int = 0)

/** Состояние VPN, общее для сервиса и интерфейса (оба работают в одном процессе). */
object VpnState {
    val snapshot = MutableStateFlow(VpnSnapshot())
    val traffic = MutableStateFlow(Traffic())
}
