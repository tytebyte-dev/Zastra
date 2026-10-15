package app.zastra.vpn.core.dns

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/** Разбор и сборка DNS-сообщений (RFC 1035) — ровно столько, сколько нужно клиенту. */
object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_AAAA = 28
    const val RCODE_NOERROR = 0
    const val RCODE_NXDOMAIN = 3

    class Answer(val name: String, val type: Int, val ttl: Long, val rdata: ByteArray)

    class Parsed(
        val id: Int,
        val flags: Int,
        val questionName: String,
        val questionType: Int,
        val answers: List<Answer>,
    ) {
        val isResponse: Boolean get() = (flags and 0x8000) != 0
        val truncated: Boolean get() = (flags and 0x0200) != 0
        val rcode: Int get() = flags and 0x000f
    }

    /** Собирает запрос с RD=1 и EDNS0 (размер UDP-ответа 1232 байта). */
    fun buildQuery(name: String, type: Int, id: Int): ByteArray {
        val out = ByteArrayOutputStream(64)
        fun u16(v: Int) {
            out.write((v shr 8) and 0xff)
            out.write(v and 0xff)
        }
        u16(id and 0xffff)
        u16(0x0100)
        u16(1)
        u16(0)
        u16(0)
        u16(1)
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            require(b.isNotEmpty() && b.size <= 63) { "Недопустимое имя для DNS: $name" }
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        u16(type)
        u16(1)
        // OPT RR
        out.write(0)
        u16(41)
        u16(1232)
        u16(0)
        u16(0)
        u16(0)
        return out.toByteArray()
    }

    fun readId(msg: ByteArray): Int =
        if (msg.size < 2) 0 else ((msg[0].toInt() and 0xff) shl 8) or (msg[1].toInt() and 0xff)

    fun withId(msg: ByteArray, id: Int): ByteArray {
        val copy = msg.copyOf()
        if (copy.size >= 2) {
            copy[0] = (id shr 8).toByte()
            copy[1] = id.toByte()
        }
        return copy
    }

    /** Разбирает сообщение; null, если оно повреждено. */
    fun parse(msg: ByteArray): Parsed? {
        try {
            if (msg.size < 12) return null
            val id = u16(msg, 0)
            val flags = u16(msg, 2)
            val qd = u16(msg, 4)
            val an = u16(msg, 6)
            var pos = 12
            var qName = ""
            var qType = 0
            for (i in 0 until qd) {
                val (n, next) = readName(msg, pos)
                pos = next
                if (pos + 4 > msg.size) return null
                if (i == 0) {
                    qName = n
                    qType = u16(msg, pos)
                }
                pos += 4
            }
            val answers = ArrayList<Answer>(an)
            for (i in 0 until an) {
                val (n, next) = readName(msg, pos)
                pos = next
                if (pos + 10 > msg.size) return null
                val type = u16(msg, pos)
                val ttl = (u16(msg, pos + 4).toLong() shl 16) or u16(msg, pos + 6).toLong()
                val rdLen = u16(msg, pos + 8)
                pos += 10
                if (pos + rdLen > msg.size) return null
                answers.add(Answer(n, type, ttl, msg.copyOfRange(pos, pos + rdLen)))
                pos += rdLen
            }
            return Parsed(id, flags, qName, qType, answers)
        } catch (_: IndexOutOfBoundsException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    /** IP-адреса из записей A и AAAA (IPv4 — первыми). */
    fun addresses(p: Parsed): List<InetAddress> {
        val v4 = ArrayList<InetAddress>()
        val v6 = ArrayList<InetAddress>()
        for (a in p.answers) {
            try {
                if (a.type == TYPE_A && a.rdata.size == 4) v4.add(InetAddress.getByAddress(a.rdata))
                else if (a.type == TYPE_AAAA && a.rdata.size == 16) v6.add(InetAddress.getByAddress(a.rdata))
            } catch (_: Exception) {
            }
        }
        return v4 + v6
    }

    fun minTtl(p: Parsed): Long {
        var min = Long.MAX_VALUE
        for (a in p.answers) if (a.type == TYPE_A || a.type == TYPE_AAAA) min = minOf(min, a.ttl)
        return if (min == Long.MAX_VALUE) 0 else min
    }

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    /** Читает имя (с поддержкой сжатия). Возвращает имя и позицию сразу после него в исходном потоке. */
    private fun readName(msg: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var pos = start
        var end = -1
        var jumps = 0
        while (true) {
            val len = msg[pos].toInt() and 0xff
            if (len == 0) {
                if (end < 0) end = pos + 1
                break
            }
            if ((len and 0xc0) == 0xc0) {
                val ptr = ((len and 0x3f) shl 8) or (msg[pos + 1].toInt() and 0xff)
                if (end < 0) end = pos + 2
                if (++jumps > 20) throw IllegalArgumentException("слишком много переходов в имени")
                pos = ptr
                continue
            }
            if ((len and 0xc0) != 0) throw IllegalArgumentException("недопустимая метка")
            if (pos + 1 + len > msg.size) throw IllegalArgumentException("метка за границей")
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(msg, pos + 1, len, Charsets.ISO_8859_1))
            pos += 1 + len
        }
        return sb.toString() to end
    }
}
