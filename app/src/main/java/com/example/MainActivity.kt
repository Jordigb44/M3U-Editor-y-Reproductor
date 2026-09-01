package com.example

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.ImageLoader
import coil.decode.SvgDecoder
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import com.example.ui.AppThemeMode
import com.example.ui.AppViewMode
import com.example.ui.DeviceMode
import com.example.ui.EditorState
import com.example.ui.EditorViewModel
import com.example.ui.LocalIsTvMode
import com.example.ui.PlayerKeyRouter
import com.example.ui.components.ModeSelectionDialog
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SimplifiedScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Configure resilient Coil ImageLoader with SVG support, Disk Cache, and permissive SSL for IPTV logos
        val imageLoader = ImageLoader.Builder(this)
            .components {
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .okHttpClient {
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
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .addInterceptor { chain ->
                            val request = chain.request().newBuilder()
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                                .header("Accept", "image/*,*/*;q=0.8")
                                .build()
                            chain.proceed(request)
                        }
                        .build()
                } catch (_: Exception) {
                    OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build()
                }
            }
            .crossfade(true)
            .build()
        Coil.setImageLoader(imageLoader)

        val isTv = DeviceMode.isTv(this)
        setContent {
            val viewModel: EditorViewModel = viewModel()
            val state by viewModel.state.collectAsState()
            val isDark = when (state.appThemeMode) {
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            CompositionLocalProvider(LocalIsTvMode provides isTv) {
                MyApplicationTheme(darkTheme = isDark) {
                    M3uEditorApp(viewModel = viewModel, state = state)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> -1
            else -> null
        }
        if (delta != null && PlayerKeyRouter.onZap != null) {
            PlayerKeyRouter.onZap?.invoke(delta)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun M3uEditorApp(
    viewModel: EditorViewModel = viewModel(),
    state: EditorState = viewModel.state.collectAsState().value
) {
    val context = LocalContext.current
    
    var currentScreen by rememberSaveable { mutableStateOf("home") }
    var showInitialModeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val hasSaved = viewModel.loadSavedPlaylist(context)
        if (hasSaved) {
            currentScreen = "editor"
        }
    }

    LaunchedEffect(state.isPreferencesLoaded, state.appViewMode) {
        if (state.isPreferencesLoaded) {
            showInitialModeDialog = (state.appViewMode == AppViewMode.UNSET)
        }
    }

    // Initial Mode Selection Dialog (First time user launches app)
    if (showInitialModeDialog && state.isPreferencesLoaded) {
        ModeSelectionDialog(
            onSelectMode = { mode ->
                viewModel.setAppViewMode(context, mode)
                showInitialModeDialog = false
            }
        )
    }

    if (state.appViewMode == AppViewMode.SIMPLIFIED) {
        SimplifiedScreen(
            state = state,
            viewModel = viewModel,
            onSwitchToAdvanced = {
                viewModel.setAppViewMode(context, AppViewMode.ADVANCED)
                if (state.channels.isNotEmpty()) {
                    currentScreen = "editor"
                } else {
                    currentScreen = "home"
                }
            }
        )
    } else {
        // Advanced Mode
        if (currentScreen == "home") {
            HomeScreen(
                state = state,
                onSelectPlaylist = { id ->
                    viewModel.switchPlaylist(context, id)
                    currentScreen = "editor"
                },
                onRenamePlaylist = { id, newName ->
                    viewModel.renamePlaylist(context, id, newName)
                },
                onDeletePlaylist = { id ->
                    viewModel.deletePlaylist(context, id)
                },
                onLoadFile = { uri ->
                    viewModel.loadFromFile(context, uri)
                    currentScreen = "editor"
                },
                onLoadUrl = { url ->
                    viewModel.loadFromUrl(context, url)
                    currentScreen = "editor"
                },
                onErrorDismiss = { viewModel.clearError() }
            )
        } else {
            EditorScreen(
                state = state,
                viewModel = viewModel,
                onBack = { currentScreen = "home" }
            )
        }
    }
}
