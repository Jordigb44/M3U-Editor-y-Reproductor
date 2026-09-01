package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Downloads and caches the XMLTV guide of the active playlist (per app run).
 * Only programmes for the channels present in the playlist are kept, to bound memory.
 */
object EpgLoader {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private val lenientClient: OkHttpClient by lazy {
        try {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "*/*")
                        .build()
                    chain.proceed(request)
                }
                .build()
        } catch (_: Exception) {
            client
        }
    }

    private var cacheUrl: String? = null
    private var cache: Map<String, List<EpgProgram>>? = null

    /** Returns channelId -> programmes (sorted by start), or null on failure. */
    suspend fun load(url: String, wantedChannelIds: Set<String>): Map<String, List<EpgProgram>>? =
        withContext(Dispatchers.IO) {
            if (cacheUrl == url && cache != null) return@withContext cache

            // Try standard client first, then fallback to lenient client
            var result = tryDownloadAndParse(client, url, wantedChannelIds)
            if (result == null) {
                result = tryDownloadAndParse(lenientClient, url, wantedChannelIds)
            }

            if (result != null && result.isNotEmpty()) {
                cacheUrl = url
                cache = result
            }
            result
        }

    private fun tryDownloadAndParse(
        httpClient: OkHttpClient,
        url: String,
        wantedChannelIds: Set<String>
    ): Map<String, List<EpgProgram>>? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body ?: return null

            body.byteStream().use { rawStream ->
                val decodedStream = wrapStream(rawStream, url)
                XmltvParser.parse(decodedStream, wantedChannelIds)
                    .groupBy { it.channelId }
                    .mapValues { (_, list) -> list.sortedBy { it.startMs } }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Wraps stream in GZIPInputStream if GZIP header magic bytes (0x1f 0x8b) are present or url ends in .gz */
    private fun wrapStream(rawStream: InputStream, url: String): InputStream {
        val buffered = BufferedInputStream(rawStream, 8192)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        val isGzipMagic = (b1 == 0x1f && b2 == 0x8b)
        val isGzipUrl = url.endsWith(".gz", ignoreCase = true)

        return if (isGzipMagic || isGzipUrl) {
            try {
                GZIPInputStream(buffered)
            } catch (_: Exception) {
                buffered.reset()
                buffered
            }
        } else {
            buffered
        }
    }
}
