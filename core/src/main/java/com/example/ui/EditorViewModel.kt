package com.example.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.Channel
import com.example.data.M3uParser
import com.example.data.ParsedM3u
import com.example.data.SavedPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    val preferredExternalAppName: String? = null
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

    /** Raw #EXTM3U header of the active playlist, preserved so exports keep playlist-level
     *  attributes (e.g. url-tvg / x-tvg-url). Not part of the UI state. */
    private var currentHeader: String = DEFAULT_HEADER

    /** EPG (XMLTV) URL declared in the active playlist header, if any. */
    fun activeEpgUrl(): String? {
        val header = currentHeader
        if (header.isBlank()) return null
        return Regex("(?:url-tvg|x-tvg-url|tvg-url)=\"([^\"]+)\"")
            .find(header)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private val client: OkHttpClient by lazy { createOkHttpClient() }

    private fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .header("Accept", "*/*")
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
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                        .header("Accept", "*/*")
                        .build()
                    chain.proceed(request)
                }
                .build()
        } catch (_: Exception) {
            client
        }
    }

    /** Executes with strict validation; on a TLS/cert error retries once leniently. */
    private fun executeWithFallback(request: Request): Response {
        return try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            if (isTrustError(e)) {
                lenientClient.newCall(request).execute()
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
        _playlists, _activePlaylistId, _channels, _isLoading, _selectedGroup, _searchQuery, _selectedChannelIds, _selectedGroups, _error, _customGroups, _groups, _defaultPlayerMode, _preferredExternalPackage, _preferredExternalActivity, _preferredExternalAppName
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val playlists = args[0] as List<SavedPlaylist>
        val activeId = args[1] as String?
        @Suppress("UNCHECKED_CAST")
        val channels = args[2] as List<Channel>
        val isLoading = args[3] as Boolean
        val group = args[4] as String?
        val search = args[5] as String
        @Suppress("UNCHECKED_CAST")
        val selectedIds = args[6] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val selectedGrps = args[7] as Set<String>
        val error = args[8] as String?
        @Suppress("UNCHECKED_CAST")
        val customGroups = args[9] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val allGroups = args[10] as List<String>
        val playerMode = args[11] as DefaultPlayerMode
        val extPkg = args[12] as String?
        val extAct = args[13] as String?
        val extName = args[14] as String?

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
            preferredExternalAppName = extName
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
                    targetUrl = "https://$targetUrl"
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
                } else {
                    _error.value = "La URL no contiene una lista M3U válida o está vacía."
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: e.message ?: "Error al descargar desde la URL"
            } finally {
                _isLoading.value = false
            }
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
        } catch (_: Exception) {}
        return@withContext false
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
                _selectedGroup.value = null
                _selectedChannelIds.value = emptySet()
                _selectedGroups.value = emptySet()

                val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
                prefs.edit().putString("active_playlist_id", playlistId).apply()
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

    private suspend fun fetchAndParseM3u(urlStr: String): ParsedM3u = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(urlStr)
            .build()

        executeWithFallback(request).use {
            if (!it.isSuccessful) {
                throw Exception("HTTP ${it.code}: ${it.message.ifBlank { "Error al descargar la lista" }}")
            }
            val body = it.body ?: throw Exception("Respuesta vacía del servidor")
            val contentLength = body.contentLength()
            if (contentLength > MAX_PLAYLIST_BYTES) {
                throw Exception("La lista es demasiado grande (máximo ${MAX_PLAYLIST_BYTES / (1024 * 1024)} MB).")
            }
            body.byteStream().use { stream ->
                M3uParser.parse(stream)
            }
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
                    // If a specific folder was provided by the user, write there directly (File API)
                    if (!folderPath.isNullOrBlank()) {
                        val dir = File(folderPath).apply { mkdirs() }
                        val file = File(dir, safeName)
                        writeM3uFile(file, _channels.value, header)
                        return@withContext file.absolutePath
                    }

                    // Default: write to Downloads via File API (works on Fire TV SDK 28)
                    var savedFileLocation = ""
                    try {
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        downloadsDir.mkdirs()
                        val file = File(downloadsDir, safeName)
                        writeM3uFile(file, _channels.value, header)
                        savedFileLocation = file.absolutePath
                    } catch (_: Exception) {}

                    // Fallback to MediaStore (Android 10+)
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
                        } catch (_: Exception) {}
                    }

                    // Last resort: app private dir
                    if (savedFileLocation.isBlank()) {
                        val appExtDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                        appExtDir.mkdirs()
                        val fallbackFile = File(appExtDir, safeName)
                        writeM3uFile(fallbackFile, _channels.value, header)
                        savedFileLocation = fallbackFile.absolutePath
                    }

                    savedFileLocation
                }
                onSuccess(savedPath)
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Error al guardar la lista")
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
