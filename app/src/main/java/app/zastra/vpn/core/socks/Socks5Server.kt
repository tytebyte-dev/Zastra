package app.zastra.vpn.core.socks

import app.zastra.vpn.core.dns.DnsService
import app.zastra.vpn.core.util.Net
import app.zastra.vpn.core.util.ZLog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Локальный SOCKS5-сервер Zastra (только 127.0.0.1) с обязательной аутентификацией
 * по логину и паролю (RFC 1929). Поддерживает CONNECT и UDP ASSOCIATE.
 *
 * Куда идёт соединение, решает PAC (см. [Dialer]); обычные TCP-соединения идут напрямую
 * или через HTTPS/HTTP-прокси из PAC. UDP передаётся только напрямую (для адресатов,
 * которые по PAC должны идти через прокси, датаграммы отбрасываются — приложения
 * переходят с QUIC на TCP). UDP/53 обрабатывается через настроенный DNS (DoH или обычный).
 */
class Socks5Server(
    username: String,
    password: String,
    private val dialer: Dialer,
    private val dns: DnsService,
    private val protector: SocketProtector,
    private val bindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
    private val requestedPort: Int = 0,
    /** Адреса, TCP-соединения к которым отклоняются (виртуальный DNS-адрес TUN). */
    private val rejectedIps: Set<String> = emptySet(),
) {
    private val userBytes = username.toByteArray(Charsets.UTF_8)
    private val passBytes = password.toByteArray(Charsets.UTF_8)

    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(null, r, "zastra-socks", 512L * 1024).also { it.isDaemon = true }
    }
    private val open: MutableSet<Socket> = Collections.synchronizedSet(HashSet())

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    private val active = AtomicInteger(0)

    /** Число активных SOCKS-соединений (для статистики). */
    val activeConnections: Int get() = active.get()

    /** Порт, на котором сервер слушает (после [start]). */
    val port: Int get() = server?.localPort ?: 0

    @Throws(IOException::class)
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindAddress, requestedPort), 256)
        server = s
        running = true
        watchdog.scheduleWithFixedDelay({ sweepSessions() }, 10, 10, java.util.concurrent.TimeUnit.SECONDS)
        executor.execute { acceptLoop(s) }
        ZLog.i(TAG, "SOCKS5 слушает " + bindAddress.hostAddress + ":" + s.localPort)
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        val snapshot = synchronized(open) { open.toList() }
        for (s in snapshot) closeQuietly(s)
        watchdog.shutdownNow()
        executor.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            try {
                val c = s.accept()
                executor.execute { handle(c) }
            } catch (e: IOException) {
                if (!running) return
                ZLog.w(TAG, "accept", e)
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                return
            }
        }
    }

    // ------------------------------------------------------------------ сессия

    private fun handle(client: Socket) {
        open.add(client)
        active.incrementAndGet()
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            // 1. выбор метода
            if (inp.read() != 5) return
            val nMethods = inp.read()
            if (nMethods <= 0) return
            val methods = readFully(inp, nMethods)
            if (methods.none { it.toInt() == 2 }) {
                out.write(byteArrayOf(5, 0xff.toByte()))
                out.flush()
                return
            }
            out.write(byteArrayOf(5, 2))
            out.flush()

            // 2. логин/пароль (RFC 1929)
            if (inp.read() != 1) return
            val user = readFully(inp, inp.read().coerceAtLeast(0))
            val pass = readFully(inp, inp.read().coerceAtLeast(0))
            val userOk = MessageDigest.isEqual(user, userBytes)
            val passOk = MessageDigest.isEqual(pass, passBytes)
            if (!(userOk && passOk)) {
                out.write(byteArrayOf(1, 1))
                out.flush()
                ZLog.w(TAG, "отклонён клиент с неверным логином/паролем")
                return
            }
            out.write(byteArrayOf(1, 0))
            out.flush()

            // 3. запрос
            if (inp.read() != 5) return
            val cmd = inp.read()
            inp.read() // RSV
            val atyp = inp.read()
            var host: String? = null
            var ip: InetAddress? = null
            when (atyp) {
                1 -> ip = InetAddress.getByAddress(readFully(inp, 4))
                4 -> ip = InetAddress.getByAddress(readFully(inp, 16))
                3 -> {
                    val len = inp.read()
                    if (len <= 0) return
                    host = Net.normalizeHost(String(readFully(inp, len), Charsets.US_ASCII))
                    if (!Net.isSafeHostname(host)) {
                        reply(out, REP_ADDR_UNSUPPORTED)
                        return
                    }
                }
                else -> {
                    reply(out, REP_ADDR_UNSUPPORTED)
                    return
                }
            }
            val portBytes = readFully(inp, 2)
            val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)

            when (cmd) {
                1 -> handleConnect(client, out, host, ip, port)
                3 -> handleUdpAssociate(client, inp, out)
                else -> reply(out, REP_CMD_UNSUPPORTED)
            }
        } catch (e: SocketTimeoutException) {
            ZLog.d(TAG, "таймаут рукопожатия")
        } catch (e: IOException) {
            ZLog.d(TAG, "соединение завершено: " + e.message)
        } catch (e: Exception) {
            ZLog.w(TAG, "ошибка обработки клиента", e)
        } finally {
            active.decrementAndGet()
            open.remove(client)
            closeQuietly(client)
        }
    }

    // ------------------------------------------------------------------ CONNECT

    private fun handleConnect(client: Socket, out: OutputStream, host: String?, ip: InetAddress?, port: Int) {
        if (ip != null && rejectedIps.contains(ip.hostAddress)) {
            reply(out, REP_REFUSED)
            return
        }
        val target = if (host != null) Target.ofHost(host, port) else Target.ofIp(ip!!, port)
        val upstream: Socket = try {
            dialer.connect(target)
        } catch (e: Exception) {
            ZLog.d(TAG, "не удалось подключиться к $target: ${e.message}")
            reply(out, replyCodeFor(e))
            return
        }
        open.add(upstream)
        try {
            reply(out, REP_SUCCESS)
            client.soTimeout = 0
            relay(client, upstream)
        } finally {
            open.remove(upstream)
            closeQuietly(upstream)
        }
    }

    /** Пара сокетов одного туннеля; нужна сторожу для закрытия «зависших» половинно закрытых сессий. */
    private class Session(val client: Socket, val upstream: Socket) {
        @Volatile
        var lastActivityNanos: Long = System.nanoTime()

        @Volatile
        var halfClosed: Boolean = false
    }

    private val sessions: MutableSet<Session> = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "zastra-socks-watchdog").also { it.isDaemon = true }
    }

    /** Закрывает сессии, у которых одна сторона уже закрыла передачу, а обмен давно прекратился. */
    private fun sweepSessions() {
        val now = System.nanoTime()
        for (s in sessions) {
            if (s.halfClosed && now - s.lastActivityNanos > HALF_CLOSE_GRACE_MS * 1_000_000L) {
                closeQuietly(s.client)
                closeQuietly(s.upstream)
            }
        }
    }

    private fun relay(client: Socket, upstream: Socket) {
        val session = Session(client, upstream)
        sessions.add(session)
        try {
            val down = executor.submit { pump(upstream, client, session) }
            pump(client, upstream, session)
            try {
                down.get()
            } catch (_: Exception) {
            }
        } finally {
            sessions.remove(session)
        }
    }

    /** Копирует данные from → to; по EOF полузакрывает to, при ошибке закрывает обе стороны. */
    private fun pump(from: Socket, to: Socket, session: Session) {
        val buf = ByteArray(16 * 1024)
        var failed = false
        try {
            val inp = from.getInputStream()
            val outp = to.getOutputStream()
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                session.lastActivityNanos = System.nanoTime()
                outp.write(buf, 0, n)
            }
            outp.flush()
        } catch (_: IOException) {
            failed = true
        }
        if (failed) {
            closeQuietly(from)
            closeQuietly(to)
        } else {
            session.lastActivityNanos = System.nanoTime()
            session.halfClosed = true
            try {
                to.shutdownOutput()
            } catch (_: Exception) {
                // SSLSocket не поддерживает полузакрытие — сессию закроет сторож после простоя
            }
        }
    }

    // ------------------------------------------------------------------ UDP

    private fun handleUdpAssociate(control: Socket, inp: InputStream, out: OutputStream) {
        val assoc = UdpAssociation()
        try {
            assoc.start()
            replyAddr(out, REP_SUCCESS, bindAddress, assoc.port)
            control.soTimeout = 0
            val tmp = ByteArray(64)
            while (inp.read(tmp) >= 0) { /* ждём закрытия управляющего соединения */ }
        } finally {
            assoc.close()
        }
    }

    private inner class UdpAssociation {
        private val relay = DatagramSocket(InetSocketAddress(bindAddress, 0))

        @Volatile
        private var closed = false

        @Volatile
        private var client: SocketAddress? = null

        private var outSock: DatagramSocket? = null

        val port: Int get() = relay.localPort

        fun start() {
            executor.execute { receiveLoop() }
        }

        fun close() {
            closed = true
            try {
                relay.close()
            } catch (_: Exception) {
            }
            try {
                outSock?.close()
            } catch (_: Exception) {
            }
        }

        private fun receiveLoop() {
            val buf = ByteArray(65535)
            val pkt = DatagramPacket(buf, buf.size)
            try {
                while (!closed) {
                    pkt.setLength(buf.size)
                    relay.receive(pkt)
                    val from = pkt.socketAddress
                    val known = client
                    if (known == null) client = from else if (known != from) continue
                    try {
                        handleDatagram(buf, pkt.length)
                    } catch (e: Exception) {
                        ZLog.d(TAG, "UDP: ${e.message}")
                    }
                }
            } catch (_: IOException) {
                // сокет закрыт
            }
        }

        private fun handleDatagram(b: ByteArray, len: Int) {
            if (len < 10 || b[2].toInt() != 0) return // фрагментация не поддерживается
            var pos = 3
            var host: String? = null
            var ip: InetAddress? = null
            when (b[pos++].toInt() and 0xff) {
                1 -> {
                    if (pos + 4 > len) return
                    ip = InetAddress.getByAddress(b.copyOfRange(pos, pos + 4))
                    pos += 4
                }
                4 -> {
                    if (pos + 16 > len) return
                    ip = InetAddress.getByAddress(b.copyOfRange(pos, pos + 16))
                    pos += 16
                }
                3 -> {
                    val n = b[pos++].toInt() and 0xff
                    if (n == 0 || pos + n > len) return
                    host = Net.normalizeHost(String(b, pos, n, Charsets.US_ASCII))
                    if (!Net.isSafeHostname(host)) return
                    pos += n
                }
                else -> return
            }
            if (pos + 2 > len) return
            val port = ((b[pos].toInt() and 0xff) shl 8) or (b[pos + 1].toInt() and 0xff)
            pos += 2
            val payload = b.copyOfRange(pos, len)

            if (port == 53) {
                val resp = try {
                    dns.exchange(payload)
                } catch (e: IOException) {
                    ZLog.d(TAG, "DNS-запрос не выполнен: ${e.message}")
                    return
                }
                sendToClient(resp, ip ?: ANY_V4, 53)
                return
            }

            val target = if (host != null) Target.ofHost(host, port) else Target.ofIp(ip!!, port)
            if (!dialer.udpDirectAllowed(target)) {
                ZLog.d(TAG, "UDP к $target отброшен (маршрут через прокси)")
                return
            }
            val dest = ip ?: dns.resolve(host!!).firstOrNull() ?: return
            outSocket().send(DatagramPacket(payload, payload.size, dest, port))
        }

        @Synchronized
        private fun outSocket(): DatagramSocket {
            outSock?.let { return it }
            val s = DatagramSocket()
            protector.protect(s)
            outSock = s
            executor.execute { outLoop(s) }
            return s
        }

        private fun outLoop(s: DatagramSocket) {
            val buf = ByteArray(65535)
            val pkt = DatagramPacket(buf, buf.size)
            try {
                while (!closed) {
                    pkt.setLength(buf.size)
                    s.receive(pkt)
                    sendToClient(buf.copyOf(pkt.length), pkt.address, pkt.port)
                }
            } catch (_: IOException) {
                // сокет закрыт
            }
        }

        private fun sendToClient(payload: ByteArray, src: InetAddress, srcPort: Int) {
            val dst = client ?: return
            val addr = src.address
            val frame = ByteArray(4 + addr.size + 2 + payload.size)
            frame[3] = if (addr.size == 4) 1 else 4
            System.arraycopy(addr, 0, frame, 4, addr.size)
            frame[4 + addr.size] = (srcPort shr 8).toByte()
            frame[5 + addr.size] = srcPort.toByte()
            System.arraycopy(payload, 0, frame, 6 + addr.size, payload.size)
            try {
                relay.send(DatagramPacket(frame, frame.size, dst))
            } catch (_: IOException) {
            }
        }
    }

    // ------------------------------------------------------------------ утилиты

    private fun reply(out: OutputStream, code: Int) = replyAddr(out, code, ANY_V4, 0)

    private fun replyAddr(out: OutputStream, code: Int, addr: InetAddress, port: Int) {
        val a = addr.address
        val r = ByteArray(6 + a.size)
        r[0] = 5
        r[1] = code.toByte()
        r[3] = if (a.size == 4) 1 else 4
        System.arraycopy(a, 0, r, 4, a.size)
        r[4 + a.size] = (port shr 8).toByte()
        r[5 + a.size] = port.toByte()
        out.write(r)
        out.flush()
    }

    private fun replyCodeFor(e: Exception): Int = when (e) {
        is UnknownHostException -> REP_HOST_UNREACHABLE
        is NoRouteToHostException -> REP_HOST_UNREACHABLE
        is ConnectException -> REP_REFUSED
        is SocketTimeoutException -> REP_TTL_EXPIRED
        else -> REP_FAILURE
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) throw IOException("неожиданный конец потока")
            off += r
        }
        return b
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "Socks5"
        const val HANDSHAKE_TIMEOUT_MS = 20_000
        const val HALF_CLOSE_GRACE_MS = 30_000
        const val REP_SUCCESS = 0
        const val REP_FAILURE = 1
        const val REP_HOST_UNREACHABLE = 4
        const val REP_REFUSED = 5
        const val REP_TTL_EXPIRED = 6
        const val REP_CMD_UNSUPPORTED = 7
        const val REP_ADDR_UNSUPPORTED = 8
        val ANY_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
    }
}
