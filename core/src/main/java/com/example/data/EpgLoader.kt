package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

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
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private var cacheUrl: String? = null
    private var cache: Map<String, List<EpgProgram>>? = null

    /** Returns channelId -> programmes (sorted by start), or null on failure. */
    suspend fun load(url: String, wantedChannelIds: Set<String>): Map<String, List<EpgProgram>>? =
        withContext(Dispatchers.IO) {
            if (cacheUrl == url && cache != null) return@withContext cache
            val grouped = try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    null
                } else {
                    val body = response.body ?: null
                    if (body == null) {
                        null
                    } else {
                        body.byteStream().use { stream ->
                            val input = if (url.endsWith(".gz", ignoreCase = true)) {
                                GZIPInputStream(stream)
                            } else {
                                stream
                            }
                            XmltvParser.parse(input, wantedChannelIds).groupBy { it.channelId }
                        }
                    }
                }
            } catch (_: Exception) {
                null
            }
            if (grouped != null) {
                cacheUrl = url
                cache = grouped
            }
            grouped
        }
}
