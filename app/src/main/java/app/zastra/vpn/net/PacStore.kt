package app.zastra.vpn.net

import android.content.Context
import app.zastra.vpn.core.pac.PacEngine
import app.zastra.vpn.core.util.ZLog
import app.zastra.vpn.data.Settings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Хранилище PAC-сценария: встроенный файл (assets/proxy.pac) или скачанная свежая версия.
 * Сервис-источник просит не запрашивать PAC чаще раза в минуту — это соблюдается.
 */
class PacStore(private val context: Context, private val settings: Settings) {

    sealed class UpdateResult {
        class Updated(val script: String) : UpdateResult()
        class RateLimited(val waitSeconds: Int) : UpdateResult()
        class Failed(val message: String) : UpdateResult()
    }

    private val file: File get() = File(context.filesDir, "proxy.pac")

    /** Возвращает текст актуального PAC: скачанный (если есть и корректен) или встроенный. */
    fun loadScript(): String {
        val f = file
        if (settings.pacDownloaded && f.isFile && f.length() > MIN_SIZE) {
            try {
                val text = f.readText(Charsets.UTF_8)
                if (looksLikePac(text)) return text
            } catch (e: IOException) {
                ZLog.w(TAG, "не удалось прочитать скачанный PAC", e)
            }
        }
        return context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** Блокирующая операция: вызывать из фонового потока. */
    fun download(): UpdateResult {
        val now = System.currentTimeMillis()
        val sinceLast = now - settings.pacLastAttempt
        if (sinceLast in 0 until MIN_INTERVAL_MS) {
            return UpdateResult.RateLimited(((MIN_INTERVAL_MS - sinceLast) / 1000).toInt() + 1)
        }
        settings.pacLastAttempt = now

        val url = settings.pacUrl
        if (!url.startsWith("https://", ignoreCase = true)) {
            return UpdateResult.Failed("Адрес PAC должен начинаться с https://")
        }
        val text: String
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 40_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "*/*")
                val code = conn.responseCode
                if (code != 200) return UpdateResult.Failed("Сервер ответил: HTTP $code")
                val out = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_SIZE) return UpdateResult.Failed("Файл PAC слишком большой")
                    }
                }
                text = out.toString("UTF-8")
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            ZLog.w(TAG, "загрузка PAC не удалась", e)
            return UpdateResult.Failed(e.message ?: e.javaClass.simpleName)
        }

        if (text.length < MIN_SIZE || !looksLikePac(text)) {
            return UpdateResult.Failed("Ответ сервера не похож на PAC-сценарий")
        }
        // Проверяем, что сценарий действительно исполняется, прежде чем заменять рабочий.
        try {
            PacEngine().use { it.load(text) }
        } catch (e: Throwable) {
            ZLog.w(TAG, "новый PAC не прошёл проверку", e)
            return UpdateResult.Failed("PAC не прошёл проверку: " + (e.message ?: e.javaClass.simpleName))
        }
        try {
            val tmp = File(context.filesDir, "proxy.pac.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("не удалось сохранить файл")
            }
        } catch (e: IOException) {
            return UpdateResult.Failed("Не удалось сохранить PAC: " + e.message)
        }
        settings.pacDownloaded = true
        settings.pacUpdatedAt = System.currentTimeMillis()
        ZLog.i(TAG, "PAC обновлён (" + text.length / 1024 + " КБ)")
        return UpdateResult.Updated(text)
    }

    /** Возвращает к встроенному PAC. */
    fun resetToBuiltin() {
        file.delete()
        settings.pacDownloaded = false
        settings.pacUpdatedAt = 0L
    }

    private fun looksLikePac(text: String) = text.contains("FindProxyForURL")

    companion object {
        private const val TAG = "PAC"
        private const val ASSET_NAME = "proxy.pac"
        private const val MIN_SIZE = 1000L
        private const val MAX_SIZE = 8 * 1024 * 1024
        private const val MIN_INTERVAL_MS = 60_000L

        // Сервис отдаёт разные варианты PAC в зависимости от User-Agent и блокирует «странные».
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
