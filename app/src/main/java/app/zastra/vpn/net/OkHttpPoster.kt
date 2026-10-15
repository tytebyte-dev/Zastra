package app.zastra.vpn.net

import app.zastra.vpn.core.dns.HttpPoster
import app.zastra.vpn.data.DnsPresets
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * HTTP-клиент для DoH на OkHttp (HTTP/2, keep-alive). Адреса известных провайдеров берутся
 * из встроенной таблицы — чтобы не зависеть от системного DNS при начальной загрузке.
 */
class OkHttpPoster : HttpPoster {
    private val bootstrap: Map<String, List<InetAddress>> = DnsPresets.DOH.associate { p ->
        p.host to p.bootstrap.map { InetAddress.getByName(it) }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                bootstrap[hostname.lowercase()] ?: Dns.SYSTEM.lookup(hostname)
        })
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Throws(IOException::class)
    override fun post(url: String, contentType: String, body: ByteArray): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", contentType)
            .post(body.toRequestBody(contentType.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("DoH: HTTP " + response.code)
            return response.body?.bytes() ?: throw IOException("DoH: пустой ответ")
        }
    }
}
