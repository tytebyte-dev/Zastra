package hev.htproxy

/**
 * JNI-мост к hev-socks5-tunnel (libhev-socks5-tunnel.so).
 * Имя пакета и класса зашито в нативную библиотеку (hev/htproxy/TProxyService) — не переименовывать.
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** Запускает туннель в отдельном потоке; [fd] — дескриптор TUN от VpnService. */
    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    /** [пакеты ↑, байты ↑, пакеты ↓, байты ↓]. */
    @JvmStatic
    external fun TProxyGetStats(): LongArray
}
