package com.example.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tv.ui.screens.EditorScreen
import com.example.tv.ui.screens.HomeScreen
import com.example.tv.ui.theme.TvTheme
import com.example.ui.EditorViewModel

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

        setContent {
            TvTheme {
                TvApp()
            }
        }
    }

    // Volume-button channel zapping while the player is open: VOLUME_UP = next channel,
    // VOLUME_DOWN = previous. Consumed only when the player is active (router registered).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
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
fun TvApp() {
    val viewModel: EditorViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var currentScreen by rememberSaveable { mutableStateOf("home") }

    LaunchedEffect(Unit) {
        val hasSaved = viewModel.loadSavedPlaylist(context)
        if (hasSaved) {
            currentScreen = "editor"
        }
    }

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
