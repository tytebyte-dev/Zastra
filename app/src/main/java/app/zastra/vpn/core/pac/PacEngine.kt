package app.zastra.vpn.core.pac

import app.zastra.vpn.core.util.ZLog
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Источник решений PAC: реализуется [PacEngine], в тестах подменяется заглушкой. */
interface PacEvaluator {
    /** Меняется при каждой (пере)загрузке PAC — по нему сбрасываются кэши решений. */
    val generation: Int

    /**
     * Возвращает «сырой» результат FindProxyForURL.
     * @param ip IPv4-адрес, который вернёт dnsResolve(host), либо null (тогда dnsResolve вернёт null).
     */
    fun findProxy(url: String, host: String, ip: String?): String
}

/**
 * Исполняет PAC-сценарий (JavaScript) в QuickJS.
 *
 * Все обращения к движку сериализуются в одном выделенном потоке с большим стеком
 * (контекст QuickJS не потокобезопасен). PAC-файл исполняется как есть, без изменений:
 * мы лишь подставляем стандартные функции PAC (dnsResolve, isInNet, …).
 *
 * dnsResolve(host) не делает сетевых запросов: возвращает IP, который мы передали вызывающему
 * ([findProxy]), и только для проверяемого хоста — так решение не блокируется на DNS.
 */
class PacEngine : PacEvaluator, AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "zastra-pac", 8L * 1024 * 1024).also { it.isDaemon = true }
    }

    // Доступ только из потока executor.
    private var handle: Long = 0L

    @Volatile
    private var closed = false

    @Volatile
    override var generation: Int = 0
        private set

    /** Загружает (или перезагружает) PAC. Блокирует до готовности; бросает исключение при ошибке. */
    fun load(script: String) {
        val started = System.nanoTime()
        call(LOAD_TIMEOUT_SEC) {
            PacNative.ensureLoaded()
            val fresh = PacNative.create()
            try {
                PacNative.eval(fresh, PRELUDE, "prelude.js")
                PacNative.eval(fresh, script, "proxy.pac")
                val type = PacNative.eval(fresh, "typeof FindProxyForURL", "check.js")
                if (type != "function") throw IllegalStateException("В PAC-сценарии нет функции FindProxyForURL")
                // «Прогрев»: большие PAC лениво распаковывают список доменов при первом вызове.
                PacNative.eval(fresh, buildCall("https://example.com/", "example.com", null), "warmup.js")
            } catch (t: Throwable) {
                PacNative.destroy(fresh)
                throw t
            }
            val old = handle
            handle = fresh
            if (old != 0L) PacNative.destroy(old)
            generation++
        }
        ZLog.i(TAG, "PAC загружен за " + (System.nanoTime() - started) / 1_000_000 + " мс")
    }

    override fun findProxy(url: String, host: String, ip: String?): String {
        return call(CALL_TIMEOUT_SEC) {
            if (handle == 0L) throw IllegalStateException("PAC не загружен")
            PacNative.eval(handle, buildCall(url, host, ip), "call.js") ?: "DIRECT"
        }
    }

    private fun <T> call(timeoutSec: Long, block: () -> T): T {
        if (closed) throw IllegalStateException("PAC-движок закрыт")
        val future = executor.submit(Callable { block() })
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException("PAC-движок не ответил за " + timeoutSec + " с")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            executor.submit {
                if (handle != 0L) {
                    PacNative.destroy(handle)
                    handle = 0L
                }
            }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "PAC"
        private const val LOAD_TIMEOUT_SEC = 60L
        private const val CALL_TIMEOUT_SEC = 10L

        internal fun jsString(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c.code < 0x20 || c.code > 0x7e -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        internal fun buildCall(url: String, host: String, ip: String?): String =
            "__zr_host=" + jsString(host) + ";__zr_ip=" + (if (ip == null) "null" else jsString(ip)) +
                ";FindProxyForURL(" + jsString(url) + "," + jsString(host) + ")"

        /** Стандартные функции PAC. Определения dnsResolve/isInNet не требуют сети. */
        internal const val PRELUDE = """
var __zr_host = null, __zr_ip = null;
function dnsResolve(h) { return (h === __zr_host && __zr_ip) ? __zr_ip : null; }
function isResolvable(h) { return dnsResolve(h) !== null; }
function myIpAddress() { return "127.0.0.1"; }
function isPlainHostName(h) { return h.indexOf(".") < 0; }
function dnsDomainIs(h, d) { return h.length >= d.length && h.substring(h.length - d.length) === d; }
function localHostOrDomainIs(h, hd) { return h === hd || (h.indexOf(".") < 0 && hd.indexOf(h + ".") === 0); }
function dnsDomainLevels(h) { return h.split(".").length - 1; }
function __zr_addr(ip) {
  var p = String(ip).split(".");
  if (p.length !== 4) return null;
  var r = 0;
  for (var i = 0; i < 4; i++) {
    if (!/^\d{1,3}${'$'}/.test(p[i])) return null;
    var n = parseInt(p[i], 10);
    if (n > 255) return null;
    r = r * 256 + n;
  }
  return r;
}
function isInNet(ip, pattern, mask) {
  var a = __zr_addr(ip), b = __zr_addr(pattern), m = __zr_addr(mask);
  if (a === null || b === null || m === null) return false;
  return ((a & m) >>> 0) === ((b & m) >>> 0);
}
function shExpMatch(s, g) {
  var re = "^" + String(g).replace(/[.+^${'$'}{}()|[\]\\]/g, "\\${'$'}&").replace(/\*/g, ".*").replace(/\?/g, ".") + "${'$'}";
  return new RegExp(re).test(s);
}
function weekdayRange(a, b, c) {
  var d = ["SUN","MON","TUE","WED","THU","FRI","SAT"], t = new Date(), w = (c === "GMT") ? t.getUTCDay() : t.getDay();
  var i = d.indexOf(a), j = (b && b !== "GMT") ? d.indexOf(b) : i;
  if (i < 0 || j < 0) return false;
  return i <= j ? (w >= i && w <= j) : (w >= i || w <= j);
}
"""
    }
}
