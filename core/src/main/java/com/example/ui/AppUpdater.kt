package com.example.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Result of checking GitHub Releases for a newer version of this app. */
data class UpdateInfo(
    val latestVersion: String,
    val tagName: String,
    val apkUrl: String?,
    val releaseNotes: String?,
    val publishedAt: String?
)

/**
 * Built-in update system: queries the GitHub Releases of the project, compares semantic
 * versions, downloads the APK that matches this app (mobile or TV) and opens the system
 * package installer via FileProvider.
 */
object AppUpdater {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun normalize(version: String): List<Int> =
        version.trim().removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }

    /** True if [latest] is semantically newer than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        val l = normalize(latest)
        val c = normalize(current)
        val max = maxOf(l.size, c.size)
        for (i in 0 until max) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * Fetches the latest GitHub release of [repo] and returns an [UpdateInfo] only when it is
     * newer than [currentVersion] and contains an APK asset matching [assetMatches].
     */
    suspend fun checkLatest(
        repo: String,
        assetMatches: (String) -> Boolean,
        currentVersion: String
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val version = tag.removePrefix("v")
            if (version.isBlank() || !isNewer(version, currentVersion)) return@withContext null

            val assets = json.optJSONArray("assets") ?: JSONArray()
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true) && assetMatches(name)) {
                    apkUrl = asset.optString("browser_download_url").ifBlank { null }
                    break
                }
            }
            UpdateInfo(
                latestVersion = version,
                tagName = tag,
                apkUrl = apkUrl,
                releaseNotes = json.optString("body", "").ifBlank { null },
                publishedAt = json.optString("published_at", "").ifBlank { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Downloads the APK into the app's private updates folder. Progress in 0f..1f. */
    suspend fun downloadApk(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "updates")
            dir.mkdirs()
            val target = File(dir, fileName)
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body ?: return@withContext null
            val total = body.contentLength()
            var downloaded = 0L
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(downloaded.toFloat() / total)
                    }
                }
            }
            onProgress(1f)
            target
        } catch (_: Exception) {
            null
        }
    }

    /** Opens the system package installer for a downloaded APK. */
    fun promptInstall(context: Context, apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
