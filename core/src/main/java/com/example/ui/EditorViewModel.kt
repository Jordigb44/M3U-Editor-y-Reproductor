package com.example.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.Channel
import com.example.data.EpgLoader
import com.example.data.EpgProgram
import com.example.data.M3uParser
import com.example.data.ParsedM3u
import com.example.data.SavedPlaylist
import com.example.data.XmltvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

enum class DefaultPlayerMode {
    INTERNAL,
    EXTERNAL,
    ASK
}

enum class AppViewMode {
    UNSET,
    SIMPLIFIED,
    ADVANCED
}

data class EditorState(
    val playlists: List<SavedPlaylist> = emptyList(),
    val activePlaylistId: String? = null,
    val activePlaylistName: String = "",
    val channels: List<Channel> = emptyList(),
    val groups: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val selectedGroup: String? = null,
    val searchQuery: String = "",
    val selectedChannelIds: Set<String> = emptySet(),
    val selectedGroups: Set<String> = emptySet(),
    val error: String? = null,
    val defaultPlayerMode: DefaultPlayerMode = DefaultPlayerMode.INTERNAL,
    val preferredExternalPackage: String? = null,
    val preferredExternalActivity: String? = null,
    val preferredExternalAppName: String? = null,
    val appViewMode: AppViewMode = AppViewMode.UNSET,
    val appThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val isPreferencesLoaded: Boolean = false,
    val lastPlayedChannelId: String? = null,
    val lastPlayedGroup: String? = null,
    val parentalControlEnabled: Boolean = false,
    val parentalPin: String = "",
    val lockModeSwitch: Boolean = false,
    val isParentalUnlocked: Boolean = false
)

class EditorViewModel : ViewModel() {
    private companion object {
        /** Upper bound for playlists downloaded from a URL (bytes) to avoid OOM on huge lists. */
        const val MAX_PLAYLIST_BYTES = 100L * 1024 * 1024
        const val DEFAULT_HEADER = "#EXTM3U"
    }

    private val _playlists = MutableStateFlow<List<SavedPlaylist>>(emptyList())
    private val _activePlaylistId = MutableStateFlow<String?>(null)
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _selectedGroup = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _selectedChannelIds = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedGroups = MutableStateFlow<Set<String>>(emptySet())
    private val _error = MutableStateFlow<String?>(null)
    private val _customGroups = MutableStateFlow<Set<String>>(emptySet())
    private val _defaultPlayerMode = MutableStateFlow(DefaultPlayerMode.INTERNAL)
    private val _preferredExternalPackage = MutableStateFlow<String?>(null)
    private val _preferredExternalActivity = MutableStateFlow<String?>(null)
    private val _preferredExternalAppName = MutableStateFlow<String?>(null)
    private val _appViewMode = MutableStateFlow(AppViewMode.UNSET)
    private val _appThemeMode = MutableStateFlow(AppThemeMode.SYSTEM)
    private val _isPreferencesLoaded = MutableStateFlow(false)
    private val _lastPlayedChannelId = MutableStateFlow<String?>(null)
    private val _lastPlayedGroup = MutableStateFlow<String?>(null)
    private val _parentalControlEnabled = MutableStateFlow(false)
    private val _parentalPin = MutableStateFlow("")
    private val _lockModeSwitch = MutableStateFlow(false)
    private val _isParentalUnlocked = MutableStateFlow(false)
    private var parentalAutoLockJob: kotlinx.coroutines.Job? = null

    private val _epgByChannel = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val epgByChannel: StateFlow<Map<String, List<EpgProgram>>> = _epgByChannel.asStateFlow()
    private val _isEpgLoading = MutableStateFlow(false)
    val isEpgLoading: StateFlow<Boolean> = _isEpgLoading.asStateFlow()

    /** Raw #EXTM3U header of the active playlist, preserved so exports keep playlist-level
     *  attributes (e.g. url-tvg / x-tvg-url). Not part of the UI state. */
    private var currentHeader: String = DEFAULT_HEADER

    /** EPG (XMLTV) URLs declared in the active playlist header, if any. */
    fun activeEpgUrls(): List<String> {
        val header = currentHeader
        if (header.isBlank()) return emptyList()
        val regex = Regex("""(?i)(?:url-tvg|x-tvg-url|tvg-url)=(?:"([^"]+)"|'([^']+)'|([^\s,]+))""")
        val matches = regex.findAll(header)
        val urls = mutableListOf<String>()
        for (m in matches) {
            val raw = (m.groupValues[1].ifBlank { null }
                ?: m.groupValues[2].ifBlank { null }
                ?: m.groupValues[3]).trim()
            raw.split(',').map { it.trim() }.filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }.forEach { urls.add(it) }
        }
        return urls.distinct()
    }

    fun activeEpgUrl(): String? = activeEpgUrls().firstOrNull()

    fun loadEpgForActivePlaylist() {
        val urls = activeEpgUrls()
        if (urls.isEmpty()) return
        val channels = _channels.value
        if (channels.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isEpgLoading.value = true
            try {
                val wantedIds = (channels.mapNotNull { it.attributes["tvg-id"] } +
                    channels.mapNotNull { it.attributes["tvg-name"] } +
                    channels.map { it.name } +
                    channels.map { it.id }).filter { it.isNotBlank() }.toSet()

                val aggregated = mutableMapOf<String, List<EpgProgram>>()
                for (url in urls) {
                    val loaded = EpgLoader.load(url, wantedIds)
                    if (loaded != null && loaded.isNotEmpty()) {
                        for ((k, v) in loaded) {
                            aggregated[k] = (aggregated[k].orEmpty() + v).distinctBy { it.startMs to it.title }
                        }
                    }
                }
                if (aggregated.isNotEmpty()) {
                    _epgByChannel.value = aggregated
                }
            } catch (_: Exception) {} finally {
                _isEpgLoading.value = false
            }
        }
    }

    fun getNowAndNext(channel: Channel, nowMs: Long = System.currentTimeMillis()): Pair<EpgProgram?, EpgProgram?> {
        val epgMap = _epgByChannel.value
        if (epgMap.isEmpty()) return null to null
        val programs = XmltvParser.findProgramsForChannel(
            epgMap,
            channel.attributes["tvg-id"],
            channel.attributes["tvg-name"],
            channel.name
        ) ?: epgMap[channel.id] ?: return null to null
        return XmltvParser.nowAndNext(programs, nowMs)
    }

    private val client: OkHttpClient by lazy { createOkHttpClient() }

    private fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "IPTVSmartersPlayer/1.0.0 (Linux; Android)")
                    .header("Accept", "*/*")
                    .header("Connection", "keep-alive")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    /** Lenient client used only as a fallback when a playlist's TLS certificate chain is
     *  rejected (common with IPTV providers, proxies or devices with clock issues). */
    private val lenientClient: OkHttpClient by lazy { createLenientOkHttpClient() }

    private fun createLenientOkHttpClient(): OkHttpClient {
        return try {
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
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(180, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "IPTVSmartersPlayer/1.0.0 (Linux; Android)")
                        .header("Accept", "*/*")
                        .header("Connection", "keep-alive")
                        .build()
                    chain.proceed(request)
                }
                .build()
        } catch (_: Exception) {
            client
        }
    }

    /** Executes with strict validation; on a TLS/cert error retries once leniently. Also retries once on connection timeout. */
    private fun executeWithFallback(request: Request): Response {
        return try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            if (isTrustError(e)) {
                lenientClient.newCall(request).execute()
            } else if (e is java.net.SocketTimeoutException || e is java.net.ConnectException) {
                // Retry once with lenient client on timeout
                try {
                    Thread.sleep(1000)
                    lenientClient.newCall(request).execute()
                } catch (_: Exception) {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    private fun isTrustError(e: Exception): Boolean {
        val msg = e.message ?: ""
        return e is SSLException ||
            msg.contains("trust anchor", ignoreCase = true) ||
            msg.contains("certificate", ignoreCase = true) ||
            msg.contains("cert", ignoreCase = true) && msg.contains("path", ignoreCase = true) ||
            msg.contains("PKIX", ignoreCase = true) ||
            msg.contains("SSL", ignoreCase = true)
    }

    /** Derived group list: only recomputed when channels or custom groups actually change
     *  (not on every UI state emission, which made big playlists sluggish). */
    private val _groups: StateFlow<List<String>> = combine(_channels, _customGroups) { channels, customGroups ->
        (channels.map { it.groupTitle } + customGroups).distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<EditorState> = combine(
        combine(_playlists, _activePlaylistId, _channels, _isLoading, _selectedGroup) { a, b, c, d, e -> arrayOf<Any?>(a, b, c, d, e) },
        combine(_searchQuery, _selectedChannelIds, _selectedGroups, _error, _customGroups) { a, b, c, d, e -> arrayOf<Any?>(a, b, c, d, e) },
        combine(_groups, _defaultPlayerMode, _preferredExternalPackage, _preferredExternalActivity, _preferredExternalAppName) { a, b, c, d, e -> arrayOf<Any?>(a, b, c, d, e) },
        combine(_appViewMode, _lastPlayedChannelId, _lastPlayedGroup, _parentalControlEnabled, _parentalPin) { a, b, c, d, e -> arrayOf<Any?>(a, b, c, d, e) },
        combine(_lockModeSwitch, _isParentalUnlocked, _appThemeMode, _isPreferencesLoaded) { a, b, c, d -> arrayOf<Any?>(a, b, c, d) }
    ) { group1, group2, group3, group4, group5 ->
        @Suppress("UNCHECKED_CAST")
        val playlists = group1[0] as List<SavedPlaylist>
        val activeId = group1[1] as String?
        @Suppress("UNCHECKED_CAST")
        val channels = group1[2] as List<Channel>
        val isLoading = group1[3] as Boolean
        val group = group1[4] as String?

        val search = group2[0] as String
        @Suppress("UNCHECKED_CAST")
        val selectedIds = group2[1] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val selectedGrps = group2[2] as Set<String>
        val error = group2[3] as String?
        @Suppress("UNCHECKED_CAST")
        val customGroups = group2[4] as Set<String>

        @Suppress("UNCHECKED_CAST")
        val allGroups = group3[0] as List<String>
        val playerMode = group3[1] as DefaultPlayerMode
        val extPkg = group3[2] as String?
        val extAct = group3[3] as String?
        val extName = group3[4] as String?

        val viewMode = group4[0] as AppViewMode
        val lastChannelId = group4[1] as String?
        val lastGroup = group4[2] as String?
        val parentalEnabled = group4[3] as Boolean
        val pin = group4[4] as String

        val lockMode = group5[0] as Boolean
        val unlocked = group5[1] as Boolean
        val themeMode = group5[2] as AppThemeMode
        val isPrefsLoaded = group5[3] as Boolean

        val activeName = playlists.find { it.id == activeId }?.name ?: ""

        EditorState(
            playlists = playlists,
            activePlaylistId = activeId,
            activePlaylistName = activeName,
            channels = channels,
            groups = allGroups,
            isLoading = isLoading,
            selectedGroup = group,
            searchQuery = search,
            selectedChannelIds = selectedIds,
            selectedGroups = selectedGrps,
            error = error,
            defaultPlayerMode = playerMode,
            preferredExternalPackage = extPkg,
            preferredExternalActivity = extAct,
            preferredExternalAppName = extName,
            appViewMode = viewMode,
            appThemeMode = themeMode,
            isPreferencesLoaded = isPrefsLoaded,
            lastPlayedChannelId = lastChannelId,
            lastPlayedGroup = lastGroup,
            parentalControlEnabled = parentalEnabled,
            parentalPin = pin,
            lockModeSwitch = lockMode,
            isParentalUnlocked = unlocked
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EditorState())

    private fun getPlaylistFile(context: Context, playlistId: String): File {
        val dir = File(context.filesDir, "playlists")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "playlist_$playlistId.m3u")
    }

    private fun savePlaylistsIndex(context: Context, playlists: List<SavedPlaylist>) {
        val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
        prefs.edit().putString("playlists_index", serializePlaylists(playlists)).apply()
    }

    private fun serializePlaylists(playlists: List<SavedPlaylist>): String {
        val array = JSONArray()
        playlists.forEach { p ->
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            obj.put("sourceUrlOrPath", p.sourceUrlOrPath ?: "")
            obj.put("channelCount", p.channelCount)
            obj.put("createdAt", p.createdAt)
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializePlaylists(json: String): List<SavedPlaylist> {
        if (json.isBlank()) return emptyList()
        val list = mutableListOf<SavedPlaylist>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    SavedPlaylist(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        sourceUrlOrPath = obj.optString("sourceUrlOrPath").ifBlank { null },
                        channelCount = obj.optInt("channelCount"),
                        createdAt = obj.optLong("createdAt")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun saveCustomGroups(context: Context) {
        val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_groups", JSONArray(_customGroups.value.toList()).toString()).apply()
    }

    private fun loadCustomGroups(prefs: android.content.SharedPreferences) {
        val savedGroups = prefs.getString("custom_groups", "")
        if (!savedGroups.isNullOrBlank()) {
            try {
                val arr = JSONArray(savedGroups)
                val groups = mutableSetOf<String>()
                for (i in 0 until arr.length()) groups.add(arr.getString(i))
                _customGroups.value = groups
            } catch (_: Exception) {}
        }
    }

    private fun openInputStreamSafely(context: Context, uri: Uri): java.io.InputStream? {
        try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream != null) return stream
        } catch (_: Exception) {}

        val rawPath = Uri.decode(uri.toString())
        val candidatePaths = mutableListOf<String>()

        uri.path?.let { candidatePaths.add(Uri.decode(it)) }
        candidatePaths.add(rawPath)

        if (rawPath.startsWith("file://")) {
            candidatePaths.add(rawPath.substring(7))
        }

        val emulatedIndex = rawPath.indexOf("/storage/emulated/")
        if (emulatedIndex != -1) {
            candidatePaths.add(rawPath.substring(emulatedIndex))
        }
        val sdcardIndex = rawPath.indexOf("/sdcard/")
        if (sdcardIndex != -1) {
            candidatePaths.add("/storage/emulated/0/" + rawPath.substring(sdcardIndex + 8))
        }

        for (path in candidatePaths.distinct()) {
            if (path.isNotBlank()) {
                try {
                    val file = File(path)
                    if (file.exists() && file.isFile) {
                        return file.inputStream()
                    }
                } catch (_: Exception) {}
            }
        }

        if (uri.scheme == "content") {
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    return java.io.FileInputStream(pfd.fileDescriptor)
                }
            } catch (_: Exception) {}
        }

        return null
    }

    /** Removes exact duplicates (same name, url and group) keeping the first occurrence. */
    private fun deduplicate(channels: List<Channel>): List<Channel> =
        channels.distinctBy { Triple(it.name, it.url, it.groupTitle) }

    private fun writeM3uFile(file: File, channels: List<Channel>, header: String) {
        file.outputStream().use { stream ->
            OutputStreamWriter(stream).use { writer ->
                writer.write(header + "\n")
                channels.forEach { ch -> writer.write(ch.toM3uString() + "\n") }
            }
        }
    }

    fun loadFromFile(context: Context, uri: Uri, customName: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val parsedM3u = withContext(Dispatchers.IO) {
                    openInputStreamSafely(context, uri)?.use { stream ->
                        M3uParser.parse(stream)
                    }
                }
                val parsed = parsedM3u?.channels ?: emptyList()
                val channels = deduplicate(parsed)
                if (channels.isNotEmpty()) {
                    val defaultName = customName ?: uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".m3u")?.removeSuffix(".m3u8") ?: "Lista Archivo"
                    val newPlaylist = SavedPlaylist(
                        name = defaultName.ifBlank { "Lista Archivo" },
                        sourceUrlOrPath = uri.toString(),
                        channelCount = channels.size
                    )

                    withContext(Dispatchers.IO) {
                        val file = getPlaylistFile(context, newPlaylist.id)
                        writeM3uFile(file, channels, parsedM3u!!.header)
                    }

                    val updatedList = _playlists.value + newPlaylist
                    _playlists.value = updatedList
                    savePlaylistsIndex(context, updatedList)

                    currentHeader = parsedM3u!!.header
                    _channels.value = channels
                    _activePlaylistId.value = newPlaylist.id
                    _customGroups.value = emptySet()
                    _selectedGroup.value = null
                    _selectedChannelIds.value = emptySet()
                    _selectedGroups.value = emptySet()

                    val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
                    prefs.edit().putString("active_playlist_id", newPlaylist.id).apply()
                    loadEpgForActivePlaylist()
                } else {
                    _error.value = "No se encontraron canales válidos en el archivo."
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Error al leer el archivo"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadFromUrl(context: Context, urlString: String, customName: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                var targetUrl = urlString.trim()
                if (!targetUrl.startsWith("http://", ignoreCase = true) &&
                    !targetUrl.startsWith("https://", ignoreCase = true)
                ) {
                    targetUrl = if (targetUrl.contains(":8080") || targetUrl.contains(":8000") || targetUrl.contains(":80") || targetUrl.contains(":30000")) {
                        "http://$targetUrl"
                    } else {
                        "https://$targetUrl"
                    }
                }

                val parsedM3u = withContext(Dispatchers.IO) {
                    fetchAndParseM3u(targetUrl)
                }

                val channels = deduplicate(parsedM3u.channels)
                if (channels.isNotEmpty()) {
                    val hostName = try { URL(targetUrl).host } catch (_: Exception) { "IPTV" }
                    val defaultName = customName ?: "Lista $hostName"
                    val newPlaylist = SavedPlaylist(
                        name = defaultName.ifBlank { "Lista IPTV" },
                        sourceUrlOrPath = targetUrl,
                        channelCount = channels.size
                    )

                    withContext(Dispatchers.IO) {
                        val file = getPlaylistFile(context, newPlaylist.id)
                        writeM3uFile(file, channels, parsedM3u.header)
                    }

                    val updatedList = _playlists.value + newPlaylist
                    _playlists.value = updatedList
                    savePlaylistsIndex(context, updatedList)

                    currentHeader = parsedM3u.header
                    _channels.value = channels
                    _activePlaylistId.value = newPlaylist.id
                    _customGroups.value = emptySet()
                    _selectedGroup.value = null
                    _selectedChannelIds.value = emptySet()
                    _selectedGroups.value = emptySet()

                    val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
                    prefs.edit().putString("active_playlist_id", newPlaylist.id).apply()
                    loadEpgForActivePlaylist()
                } else {
                    _error.value = "No se encontraron canales válidos en la lista IPTV."
                }
            } catch (e: Exception) {
                Log.e("EditorViewModel", "Failed to load from URL: $urlString", e)
                val rawMsg = e.localizedMessage ?: e.message ?: "Error al descargar la lista"
                _error.value = when {
                    rawMsg.contains("15000ms", ignoreCase = true) || rawMsg.contains("timeout", ignoreCase = true) || rawMsg.contains("timed out", ignoreCase = true) ->
                        "Tiempo de espera agotado al conectar al servidor IPTV. Inténtalo de nuevo."
                    rawMsg.contains("Failed to connect", ignoreCase = true) || rawMsg.contains("Connection refused", ignoreCase = true) ->
                        "No se pudo conectar al servidor IPTV. Comprueba si la URL es correcta o si el servidor está caído."
                    else -> rawMsg
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun fetchAndParseM3u(urlStr: String): ParsedM3u = withContext(Dispatchers.IO) {
        var currentUrl = urlStr
        var response: Response? = null
        var redirectCount = 0

        while (redirectCount < 5) {
            val request = Request.Builder()
                .url(currentUrl)
                .build()

            val res = executeWithFallback(request)
            if (res.isRedirect) {
                val location = res.header("Location")
                res.close()
                if (!location.isNullOrBlank()) {
                    currentUrl = if (location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)) {
                        location
                    } else {
                        val baseUri = Uri.parse(currentUrl)
                        "${baseUri.scheme}://${baseUri.host}${if (baseUri.port != -1) ":${baseUri.port}" else ""}$location"
                    }
                    redirectCount++
                    continue
                }
            }
            response = res
            break
        }

        val it = response ?: throw Exception("Demasiados redireccionamientos de red.")
        it.use {
            if (!it.isSuccessful) {
                throw Exception("HTTP ${it.code}: ${it.message.ifBlank { "Error al descargar la lista" }}")
            }
            val body = it.body ?: throw Exception("Respuesta vacía del servidor")
            val contentLength = body.contentLength()
            if (contentLength > MAX_PLAYLIST_BYTES) {
                throw Exception("La lista es demasiado grande (máximo ${MAX_PLAYLIST_BYTES / (1024 * 1024)} MB).")
            }

            val parsed = body.byteStream().use { stream -> M3uParser.parse(stream) }
            if (parsed.channels.isNotEmpty() && parsed.channels.all { ch -> ch.name == "Unknown Channel" }) {
                throw Exception("La URL no devuelve una lista M3U válida. Puede estar bloqueada por tu red o proveedor.")
            }
            parsed
        }
    }

    suspend fun loadSavedPlaylist(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
            val json = prefs.getString("playlists_index", "") ?: ""
            val list = deserializePlaylists(json).toMutableList()

            val savedModeName = prefs.getString("default_player_mode", DefaultPlayerMode.INTERNAL.name)
            _defaultPlayerMode.value = try { DefaultPlayerMode.valueOf(savedModeName!!) } catch (_: Exception) { DefaultPlayerMode.INTERNAL }
            _preferredExternalPackage.value = prefs.getString("preferred_external_package", "")?.ifBlank { null }
            _preferredExternalActivity.value = prefs.getString("preferred_external_activity", "")?.ifBlank { null }
            _preferredExternalAppName.value = prefs.getString("preferred_external_app_name", "")?.ifBlank { null }

            val savedViewMode = prefs.getString("app_view_mode", AppViewMode.UNSET.name)
            _appViewMode.value = try { AppViewMode.valueOf(savedViewMode!!) } catch (_: Exception) { AppViewMode.UNSET }

            val savedThemeMode = prefs.getString("app_theme_mode", AppThemeMode.SYSTEM.name)
            _appThemeMode.value = try { AppThemeMode.valueOf(savedThemeMode!!) } catch (_: Exception) { AppThemeMode.SYSTEM }

            val lastChannelId = prefs.getString("last_played_channel_id", "")?.ifBlank { null }
            val lastGroup = prefs.getString("last_played_group", "")?.ifBlank { null }
            _lastPlayedChannelId.value = lastChannelId
            _lastPlayedGroup.value = lastGroup
            if (!lastGroup.isNullOrBlank()) {
                _selectedGroup.value = lastGroup
            }

            val parentalEnabled = prefs.getBoolean("parental_control_enabled", false)
            val pin = prefs.getString("parental_pin", "") ?: ""
            val lockMode = prefs.getBoolean("lock_mode_switch", false)
            _parentalControlEnabled.value = parentalEnabled
            _parentalPin.value = pin
            _lockModeSwitch.value = lockMode
            _isParentalUnlocked.value = false

            loadCustomGroups(prefs)

            // Legacy Single Playlist Migration Check
            val legacyFile = File(context.filesDir, "saved_playlist.m3u")
            if (list.isEmpty() && legacyFile.exists() && legacyFile.length() > 0) {
                val legacyParsed = legacyFile.inputStream().use { M3uParser.parse(it) }
                if (legacyParsed.channels.isNotEmpty()) {
                    val legacyPlaylist = SavedPlaylist(name = "Lista Principal", channelCount = legacyParsed.channels.size)
                    val playlistFile = getPlaylistFile(context, legacyPlaylist.id)
                    legacyFile.copyTo(playlistFile, overwrite = true)
                    list.add(legacyPlaylist)
                    savePlaylistsIndex(context, list)
                }
            }

            _playlists.value = list
            val lastActiveId = prefs.getString("active_playlist_id", null) ?: list.firstOrNull()?.id
            if (lastActiveId != null && list.any { it.id == lastActiveId }) {
                switchPlaylistInternal(context, lastActiveId)
                return@withContext true
            }
        } catch (_: Exception) {} finally {
            _isPreferencesLoaded.value = true
        }
        return@withContext false
    }

    fun setAppViewMode(context: Context, mode: AppViewMode) {
        _appViewMode.value = mode
        val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
        prefs.edit().putString("app_view_mode", mode.name).commit()
    }

    fun setAppThemeMode(context: Context, mode: AppThemeMode) {
        _appThemeMode.value = mode
        val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
        prefs.edit().putString("app_theme_mode", mode.name).apply()
    }

    fun setParentalControl(context: Context, enabled: Boolean, pin: String, lockModeSwitch: Boolean) {
        _parentalControlEnabled.value = enabled
        _parentalPin.value = pin
        _lockModeSwitch.value = lockModeSwitch
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("parental_control_enabled", enabled)
                .putString("parental_pin", pin)
                .putBoolean("lock_mode_switch", lockModeSwitch)
                .apply()
        }
    }

    fun verifyParentalPin(pin: String): Boolean {
        val currentPin = _parentalPin.value.ifBlank { "0000" }
        return pin == currentPin
    }

    fun unlockParentalSession() {
        _isParentalUnlocked.value = true
        resetParentalAutoLockTimer()
    }

    fun onParentalActivity() {
        if (_isParentalUnlocked.value) {
            resetParentalAutoLockTimer()
        }
    }

    fun lockParentalSession() {
        parentalAutoLockJob?.cancel()
        _isParentalUnlocked.value = false
    }

    private fun resetParentalAutoLockTimer() {
        parentalAutoLockJob?.cancel()
        parentalAutoLockJob = viewModelScope.launch {
            kotlinx.coroutines.delay(10_000)
            _isParentalUnlocked.value = false
        }
    }

    fun saveLastPlayedChannel(context: Context, channelId: String, group: String?) {
        _lastPlayedChannelId.value = channelId
        _lastPlayedGroup.value = group
        if (group != null) {
            _selectedGroup.value = group
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("last_played_channel_id", channelId)
                    .putString("last_played_group", group ?: "")
                    .apply()
            } catch (_: Exception) {}
        }
    }

    fun switchPlaylist(context: Context, playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            switchPlaylistInternal(context, playlistId)
        }
    }

    private suspend fun switchPlaylistInternal(context: Context, playlistId: String) {
        val file = getPlaylistFile(context, playlistId)
        if (file.exists() && file.length() > 0) {
            try {
                val parsedM3u = file.inputStream().use { M3uParser.parse(it) }
                _channels.value = parsedM3u.channels
                currentHeader = parsedM3u.header
                _activePlaylistId.value = playlistId

                val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
                val lastGroup = prefs.getString("last_played_group", "")?.ifBlank { null }
                if (!lastGroup.isNullOrBlank() && parsedM3u.channels.any { it.groupTitle == lastGroup }) {
                    _selectedGroup.value = lastGroup
                } else {
                    _selectedGroup.value = null
                }
                _selectedChannelIds.value = emptySet()
                _selectedGroups.value = emptySet()

                prefs.edit().putString("active_playlist_id", playlistId).apply()
                loadEpgForActivePlaylist()
            } catch (e: Exception) {
                _error.value = "No se pudo cargar la lista guardada: ${e.localizedMessage ?: "archivo no válido"}"
            }
        } else {
            _error.value = "La lista guardada no existe o está vacía."
        }
    }

    fun renamePlaylist(context: Context, playlistId: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = _playlists.value.map {
                if (it.id == playlistId) it.copy(name = newName) else it
            }
            _playlists.value = updated
            savePlaylistsIndex(context, updated)
        }
    }

    fun deletePlaylist(context: Context, playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = getPlaylistFile(context, playlistId)
            if (file.exists()) file.delete()

            val updated = _playlists.value.filter { it.id != playlistId }
            _playlists.value = updated
            savePlaylistsIndex(context, updated)

            if (_activePlaylistId.value == playlistId) {
                val nextActive = updated.firstOrNull()
                if (nextActive != null) {
                    switchPlaylistInternal(context, nextActive.id)
                } else {
                    _activePlaylistId.value = null
                    _channels.value = emptyList()
                }
            }
        }
    }

    private fun saveActivePlaylistToCache(context: Context) {
        val activeId = _activePlaylistId.value ?: return
        val channels = _channels.value
        val header = currentHeader
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = getPlaylistFile(context, activeId)
                writeM3uFile(file, channels, header)
                val updated = _playlists.value.map {
                    if (it.id == activeId) it.copy(channelCount = channels.size) else it
                }
                _playlists.value = updated
                savePlaylistsIndex(context, updated)
            } catch (_: Exception) {}
        }
    }



    fun saveExportFile(
        context: Context,
        fileName: String,
        folderPath: String? = null,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val safeName = fileName.ifBlank { "playlist_editada.m3u" }
                val header = currentHeader
                val savedPath = withContext(Dispatchers.IO) {
                    var savedFileLocation = ""

                    // Stage 1: Try writing to user's chosen folder if provided
                    if (!folderPath.isNullOrBlank()) {
                        try {
                            val dir = File(folderPath).apply { mkdirs() }
                            val file = File(dir, safeName)
                            writeM3uFile(file, _channels.value, header)
                            savedFileLocation = file.absolutePath
                        } catch (e: Exception) {
                            Log.w("EditorViewModel", "Failed to write directly to $folderPath: ${e.message}")
                        }
                    }

                    // Stage 2: Try standard Downloads directory
                    if (savedFileLocation.isBlank()) {
                        try {
                            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                            downloadsDir.mkdirs()
                            val file = File(downloadsDir, safeName)
                            writeM3uFile(file, _channels.value, header)
                            savedFileLocation = file.absolutePath
                        } catch (e: Exception) {
                            Log.w("EditorViewModel", "Failed to write to public Downloads: ${e.message}")
                        }
                    }

                    // Stage 3: Try MediaStore insertion (Android 10+ / API 29+) - Requires 0 permissions!
                    if (savedFileLocation.isBlank() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        try {
                            val contentValues = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl")
                                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                            }
                            val resolver = context.contentResolver
                            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                            if (uri != null) {
                                resolver.openOutputStream(uri)?.use { stream ->
                                    OutputStreamWriter(stream).use { writer ->
                                        writer.write(header + "\n")
                                        _channels.value.forEach { ch ->
                                            writer.write(ch.toM3uString() + "\n")
                                        }
                                    }
                                }
                                savedFileLocation = "Descargas / $safeName"
                            }
                        } catch (e: Exception) {
                            Log.w("EditorViewModel", "Failed to write to MediaStore: ${e.message}")
                        }
                    }

                    // Stage 4: App External Files directory (Always writable without permissions on all Android versions)
                    if (savedFileLocation.isBlank()) {
                        try {
                            val appExtDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                            appExtDir.mkdirs()
                            val fallbackFile = File(appExtDir, safeName)
                            writeM3uFile(fallbackFile, _channels.value, header)
                            savedFileLocation = fallbackFile.absolutePath
                        } catch (e: Exception) {
                            Log.e("EditorViewModel", "Failed to write to app external files dir: ${e.message}")
                        }
                    }

                    savedFileLocation
                }

                if (savedPath.isNotBlank()) {
                    onSuccess(savedPath)
                } else {
                    onError("No se pudo escribir el archivo. Verifica los permisos de almacenamiento de tu dispositivo.")
                }
            } catch (e: Exception) {
                onError("Error al exportar: ${e.localizedMessage ?: e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectGroup(group: String?) {
        _selectedGroup.value = group
        _selectedChannelIds.value = emptySet()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleChannelSelection(id: String) {
        val current = _selectedChannelIds.value
        _selectedChannelIds.value = if (current.contains(id)) current - id else current + id
    }

    fun toggleSelectAllChannels(filteredChannelIds: List<String>) {
        val current = _selectedChannelIds.value
        val allSelected = filteredChannelIds.isNotEmpty() && filteredChannelIds.all { current.contains(it) }
        _selectedChannelIds.value = if (allSelected) current - filteredChannelIds.toSet() else current + filteredChannelIds.toSet()
    }

    fun clearSelection() {
        _selectedChannelIds.value = emptySet()
    }

    fun deleteSelectedChannels(context: Context) {
        val selected = _selectedChannelIds.value
        if (selected.isNotEmpty()) {
            val updated = _channels.value.filter { !selected.contains(it.id) }
            _channels.value = updated
            _selectedChannelIds.value = emptySet()
            saveActivePlaylistToCache(context)
        }
    }

    fun renameChannel(id: String, newName: String, context: Context) {
        val updated = _channels.value.map {
            if (it.id == id) it.copy(name = newName) else it
        }
        _channels.value = updated
        saveActivePlaylistToCache(context)
    }

    fun moveSelectedToGroup(newGroupTitle: String, context: Context) {
        val selected = _selectedChannelIds.value
        if (selected.isNotEmpty()) {
            val updated = _channels.value.map {
                if (selected.contains(it.id)) it.copy(groupTitle = newGroupTitle) else it
            }
            _channels.value = updated
            _selectedChannelIds.value = emptySet()
            saveActivePlaylistToCache(context)
        }
    }

    fun createNewGroup(groupName: String, context: Context) {
        if (groupName.isNotBlank()) {
            _customGroups.value = _customGroups.value + groupName.trim()
            saveCustomGroups(context)
        }
    }

    fun toggleGroupSelection(group: String) {
        val current = _selectedGroups.value
        _selectedGroups.value = if (current.contains(group)) current - group else current + group
    }

    fun toggleSelectAllGroups(allGroups: List<String>) {
        val current = _selectedGroups.value
        val allSelected = allGroups.isNotEmpty() && allGroups.all { current.contains(it) }
        _selectedGroups.value = if (allSelected) current - allGroups.toSet() else current + allGroups.toSet()
    }

    fun clearGroupSelection() {
        _selectedGroups.value = emptySet()
    }

    fun deleteSelectedGroups(context: Context) {
        val selected = _selectedGroups.value
        if (selected.isNotEmpty()) {
            val updated = _channels.value.filter { !selected.contains(it.groupTitle) }
            _channels.value = updated
            _customGroups.value = _customGroups.value - selected
            _selectedGroups.value = emptySet()
            saveActivePlaylistToCache(context)
            saveCustomGroups(context)
        }
    }

    fun renameGroup(oldName: String, newName: String, context: Context) {
        if (newName.isNotBlank() && oldName != newName) {
            val updated = _channels.value.map {
                if (it.groupTitle == oldName) it.copy(groupTitle = newName) else it
            }
            _channels.value = updated
            if (_customGroups.value.contains(oldName)) {
                _customGroups.value = (_customGroups.value - oldName) + newName
            }
            saveActivePlaylistToCache(context)
            saveCustomGroups(context)
        }
    }

    fun addNewGroup(groupName: String, context: Context) {
        createNewGroup(groupName, context)
    }

    fun setDefaultPlayerMode(
        context: Context,
        mode: DefaultPlayerMode,
        targetPackage: String? = null,
        targetActivity: String? = null,
        appName: String? = null
    ) {
        _defaultPlayerMode.value = mode
        _preferredExternalPackage.value = targetPackage
        _preferredExternalActivity.value = targetActivity
        _preferredExternalAppName.value = appName

        val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("default_player_mode", mode.name)
            .putString("preferred_external_package", targetPackage ?: "")
            .putString("preferred_external_activity", targetActivity ?: "")
            .putString("preferred_external_app_name", appName ?: "")
            .apply()
    }

    fun clearError() {
        _error.value = null
    }
}
