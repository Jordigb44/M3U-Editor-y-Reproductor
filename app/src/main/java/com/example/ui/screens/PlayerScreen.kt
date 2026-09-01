package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.Channel
import com.example.data.EpgLoader
import com.example.data.EpgProgram
import com.example.data.XmltvParser
import com.example.ui.AdaptiveLoadControl
import com.example.ui.LocalIsTvMode
import com.example.ui.PlayerKeyRouter
import com.example.ui.components.TvFocusHighlightColor
import com.example.ui.components.tvFocusable
import com.example.ui.components.tvRing
import com.jordiguixbetancor.m3ueditor.R
import kotlinx.coroutines.delay
import java.util.Locale

/** Browser-like User-Agent so IPTV servers/CDNs accept stream requests. */
private const val PLAYER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 4_000L
private const val CHANNEL_BANNER_TIMEOUT_MS = 2_500L
private const val CONTROL_COUNT = 6

@OptIn(UnstableApi::class)
class FastReconnectErrorPolicy(private val maxRetries: Int = 5) : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        return 0L
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        return maxRetries
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    else String.format(Locale.getDefault(), "%02d:%02d", m, s)
}

fun launchExternalPlayer(
    context: Context,
    url: String = "",
    title: String = "",
    channelUrl: String = url,
    channelName: String = title,
    targetPackage: String? = null,
    targetActivity: String? = null
) {
    val finalUrl = channelUrl.ifBlank { url }
    val finalTitle = channelName.ifBlank { title }
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(finalUrl), "video/*")
            putExtra("title", finalTitle)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (!targetPackage.isNullOrBlank()) {
                if (!targetActivity.isNullOrBlank()) {
                    setClassName(targetPackage, targetActivity)
                } else {
                    setPackage(targetPackage)
                }
            }
        }
        if (!targetPackage.isNullOrBlank()) {
            context.startActivity(intent)
        } else {
            val chooser = Intent.createChooser(intent, context.getString(R.string.play_channel_with)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(chooser)
        }
    } catch (e: Exception) {
        Toast.makeText(
            context,
            context.getString(R.string.external_player_error, e.localizedMessage ?: ""),
            Toast.LENGTH_SHORT
        ).show()
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channels: List<Channel>,
    startIndex: Int,
    onBack: () -> Unit,
    epgUrl: String? = null,
    simplifiedMode: Boolean = false,
    onChannelChanged: ((Channel) -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isTvMode = LocalIsTvMode.current
    var showChannelList by remember { mutableStateOf(false) }

    // Intercept back button: close drawer first, or exit player if drawer is not open
    BackHandler {
        if (showChannelList) {
            showChannelList = false
        } else {
            onBack()
        }
    }

    // Prevent screen dimming or sleep mode during video playback
    DisposableEffect(Unit) {
        val window = activity?.window
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.setDecorFitsSystemWindows(window, true)
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val maxRetries = 5
    var retryCount by remember { mutableIntStateOf(0) }
    var isReconnecting by remember { mutableStateOf(false) }
    var connectionFailed by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var isPlaying by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FILL) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    var epgByChannel by remember { mutableStateOf<Map<String, List<EpgProgram>>?>(null) }
    var epgLoading by remember { mutableStateOf(false) }
    var selectedControl by remember { mutableIntStateOf(1) }

    var currentIndex by remember(startIndex) {
        mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0)))
    }
    val channel: Channel = channels[currentIndex]

    fun buildPlayer(): ExoPlayer {
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val loadControl = AdaptiveLoadControl()
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(PLAYER_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(FastReconnectErrorPolicy(maxRetries))

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .build()
    }

    val player = remember { buildPlayer() }

    fun seekRelative(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        player.seekTo(target)
        seekFeedback = (if (deltaMs > 0) "+" else "−") + (deltaMs / 1000) + "s"
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun cycleResizeMode() {
        resizeMode = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }

    fun activateControl(index: Int) {
        when (index) {
            0 -> seekRelative(-SEEK_STEP_MS)
            1 -> togglePlay()
            2 -> seekRelative(SEEK_STEP_MS)
            3 -> cycleResizeMode()
            4 -> launchExternalPlayer(context, channel.url, channel.name)
            5 -> showChannelList = true
        }
    }

    fun switchChannel(delta: Int) {
        if (channels.size <= 1) return
        currentIndex = (currentIndex + delta + channels.size) % channels.size
        showControls = true
    }

    fun epgNowNextFor(ch: Channel): Pair<EpgProgram?, EpgProgram?>? {
        val map = epgByChannel ?: return null
        val list = XmltvParser.findProgramsForChannel(
            map,
            ch.attributes["tvg-id"],
            ch.attributes["tvg-name"],
            ch.name
        ) ?: map[ch.id] ?: return null
        if (list.isEmpty()) return null
        return XmltvParser.nowAndNext(list, System.currentTimeMillis())
    }

    fun triggerReconnect() {
        connectionFailed = false
        isReconnecting = true
        player.setMediaItem(MediaItem.fromUri(channel.url))
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                lastError = error.errorCodeName + ": " + (error.message ?: "")
                if (retryCount < maxRetries) {
                    retryCount++
                    isReconnecting = true
                    player.setMediaItem(MediaItem.fromUri(channel.url))
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    isReconnecting = false
                    connectionFailed = true
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_READY) {
                    retryCount = 0
                    isReconnecting = false
                    connectionFailed = false
                    lastError = null
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }

        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(currentIndex) {
        retryCount = 0
        isReconnecting = false
        connectionFailed = false
        lastError = null
        showControls = true
        player.setMediaItem(MediaItem.fromUri(channel.url))
        player.prepare()
        player.playWhenReady = true

        try {
            val prefs = context.getSharedPreferences("pepe_editor_playlists", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("last_played_channel_id", channel.id)
                .putString("last_played_group", channel.groupTitle)
                .apply()
        } catch (_: Exception) {}
        onChannelChanged?.invoke(channel)
    }

    LaunchedEffect(epgUrl) {
        if (epgUrl.isNullOrBlank()) return@LaunchedEffect
        epgLoading = true
        val wanted = (channels.mapNotNull { it.attributes["tvg-id"] } +
            channels.mapNotNull { it.attributes["tvg-name"] } +
            channels.map { it.name } +
            channels.map { it.id }).filter { it.isNotBlank() }.toSet()
        if (wanted.isNotEmpty()) {
            epgByChannel = EpgLoader.load(epgUrl, wanted)
        }
        epgLoading = false
    }

    DisposableEffect(Unit) {
        PlayerKeyRouter.onZap = { switchChannel(it) }
        onDispose { PlayerKeyRouter.onZap = null }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    player.pause()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME,
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    }
                    player.playWhenReady = true
                    player.play()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(CONTROLS_TIMEOUT_MS)
            showControls = false
        }
    }

    LaunchedEffect(showControls) {
        while (showControls) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(900)
            seekFeedback = null
        }
    }

    val focusRequester = remember { FocusRequester() }
    val drawerFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(showChannelList) {
        if (showChannelList) {
            delay(100)
            try {
                drawerFocusRequester.requestFocus()
            } catch (_: Exception) {}
        } else {
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false

                if (showChannelList) {
                    return@onKeyEvent when (keyEvent.key) {
                        Key.Back, Key.Escape -> {
                            showChannelList = false
                            true
                        }
                        else -> false
                    }
                }

                when (keyEvent.key) {
                    Key.DirectionLeft -> {
                        if (showControls) {
                            selectedControl = (selectedControl + CONTROL_COUNT - 1) % CONTROL_COUNT
                        } else {
                            // Pressing Left while watching video: ONLY Way to Open Channel & Group Drawer!
                            showControls = false
                            showChannelList = true
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        if (showControls) {
                            selectedControl = (selectedControl + 1) % CONTROL_COUNT
                        } else {
                            // Pressing Right while watching video: Fast forward 10 seconds (does NOT open drawer)
                            seekRelative(10_000L)
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (showControls) {
                            activateControl(selectedControl)
                        } else {
                            // Pressing OK / Enter while watching video: ONLY opens player controls (Play/Pause, timeline, etc.)
                            showChannelList = false
                            selectedControl = 0
                            showControls = true
                        }
                        true
                    }
                    Key.Back, Key.Escape -> {
                        if (showControls) {
                            showControls = false
                            true
                        } else {
                            onBack()
                            true
                        }
                    }
                    Key.MediaPlayPause -> {
                        togglePlay()
                        true
                    }
                    Key.MediaPlay -> {
                        player.play()
                        true
                    }
                    Key.MediaPause -> {
                        player.pause()
                        true
                    }
                    Key.DirectionUp, Key.PageUp, Key.VolumeDown -> {
                        if (showControls) {
                            showControls = false
                        }
                        switchChannel(-1)
                        true
                    }
                    Key.DirectionDown, Key.PageDown, Key.VolumeUp -> {
                        if (showControls) {
                            showControls = false
                        }
                        switchChannel(1)
                        true
                    }
                    Key.Menu -> {
                        showChannelList = false
                        showControls = !showControls
                        true
                    }
                    else -> false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (showChannelList) {
                    showChannelList = false
                } else {
                    showControls = !showControls
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    this.resizeMode = resizeMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerView.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // Buffering / Reconnecting indicator
        if (playbackState == Player.STATE_BUFFERING || isReconnecting) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, TvFocusHighlightColor.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = TvFocusHighlightColor,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = if (isReconnecting) {
                                stringResource(R.string.reconnecting_count, retryCount)
                            } else {
                                stringResource(R.string.buffering)
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Connection Failed overlay
        if (connectionFailed) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color(0xFF181016).copy(alpha = 0.92f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.reconnect_failed),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (lastError != null) {
                            Text(
                                text = lastError ?: "",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { triggerReconnect() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.tvFocusable()
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                            OutlinedButton(
                                onClick = { launchExternalPlayer(context, channel.url, channel.name) },
                                modifier = Modifier.tvFocusable()
                            ) {
                                Text(stringResource(R.string.player_try_external_app))
                            }
                        }
                    }
                }
            }
        }

        // Seek feedback bubble
        seekFeedback?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 120.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, TvFocusHighlightColor)
                ) {
                    Text(
                        text = msg,
                        color = TvFocusHighlightColor,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // OSD Controls Overlay
        AnimatedVisibility(
            visible = showControls && !showChannelList,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.70f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.92f)
                            )
                        )
                    )
            ) {
                // Top header: Back button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .tvFocusable(shape = CircleShape)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Bottom control bar with TV Guide EPG info card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 28.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // EPG PROGRAM INFO CARD (Directly above the progress bar)
                    val epg = epgNowNextFor(channel)
                    val nowProg = epg?.first
                    val nextProg = epg?.second

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF0F172A).copy(alpha = 0.92f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Row 1: Channel logo + Channel name + LIVE badge + Category + Channel number
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (channel.logoUrl.isNotBlank()) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color.White.copy(alpha = 0.1f),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            AsyncImage(
                                                model = channel.logoUrl,
                                                contentDescription = channel.name,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .padding(2.dp)
                                            )
                                        }
                                    }
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = channel.name,
                                                color = Color.White,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Surface(
                                                color = Color(0xFFEF4444),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = if (durationMs > 0L) "VOD" else stringResource(R.string.tv_live_badge),
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Black,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        if (channel.groupTitle.isNotBlank()) {
                                            Text(
                                                text = channel.groupTitle,
                                                color = TvFocusHighlightColor,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }

                                // Channel number badge
                                Surface(
                                    color = Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "${currentIndex + 1} / ${channels.size}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            // Row 2: EPG Current Program & Next Program
                            if (nowProg != null) {
                                val nowStart = formatEpgTime(nowProg.startMs)
                                val nowStop = formatEpgTime(nowProg.stopMs)
                                val nowTotalMs = (nowProg.stopMs - nowProg.startMs).coerceAtLeast(1L)
                                val nowElapsedMs = (System.currentTimeMillis() - nowProg.startMs).coerceIn(0L, nowTotalMs)
                                val epgProgress = (nowElapsedMs.toFloat() / nowTotalMs.toFloat()).coerceIn(0f, 1f)

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "🔴 ${nowProg.title}",
                                            color = Color.White,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "$nowStart - $nowStop",
                                            color = Color(0xFFCBD5E1),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    // EPG Program progress bar
                                    LinearProgressIndicator(
                                        progress = { epgProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = TvFocusHighlightColor,
                                        trackColor = Color.White.copy(alpha = 0.15f),
                                    )

                                    if (nextProg != null) {
                                        Text(
                                            text = "⏭️ " + stringResource(R.string.epg_next, nextProg.title) + " (${formatEpgTime(nextProg.startMs)})",
                                            color = Color(0xFF94A3B8),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // VOD Time progress bar if duration is known
                    if (durationMs > 0L) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = formatMs(positionMs),
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            LinearProgressIndicator(
                                progress = { (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = TvFocusHighlightColor,
                                trackColor = Color.White.copy(alpha = 0.2f),
                            )
                            Text(
                                text = formatMs(durationMs),
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    // Action buttons bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Previous Channel
                        IconButton(
                            onClick = { switchChannel(-1) },
                            modifier = Modifier
                                .size(if (simplifiedMode) 56.dp else 48.dp)
                                .tvFocusable(shape = CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = stringResource(R.string.prev_channel),
                                tint = Color.White,
                                modifier = Modifier.size(if (simplifiedMode) 38.dp else 32.dp)
                            )
                        }

                        if (!simplifiedMode) {
                            Spacer(Modifier.width(8.dp))

                            // Fast Rewind
                            PlayerControlButton(
                                icon = Icons.Filled.FastRewind,
                                isSelected = selectedControl == 0,
                                onClick = { activateControl(0) }
                            )
                        }

                        Spacer(Modifier.width(if (simplifiedMode) 16.dp else 8.dp))

                        // Play / Pause
                        PlayerControlButton(
                            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            isSelected = selectedControl == 1,
                            isPrimary = true,
                            onClick = { activateControl(1) }
                        )

                        Spacer(Modifier.width(if (simplifiedMode) 16.dp else 8.dp))

                        if (!simplifiedMode) {
                            // Fast Forward
                            PlayerControlButton(
                                icon = Icons.Filled.FastForward,
                                isSelected = selectedControl == 2,
                                onClick = { activateControl(2) }
                            )

                            Spacer(Modifier.width(8.dp))
                        }

                        // Next Channel
                        IconButton(
                            onClick = { switchChannel(1) },
                            modifier = Modifier
                                .size(if (simplifiedMode) 56.dp else 48.dp)
                                .tvFocusable(shape = CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = stringResource(R.string.next_channel),
                                tint = Color.White,
                                modifier = Modifier.size(if (simplifiedMode) 38.dp else 32.dp)
                            )
                        }

                        Spacer(Modifier.width(if (simplifiedMode) 16.dp else 16.dp))

                        // Aspect Ratio Cycle
                        PlayerControlButton(
                            icon = Icons.Filled.AspectRatio,
                            isSelected = selectedControl == 3,
                            onClick = { activateControl(3) }
                        )

                        if (!simplifiedMode) {
                            Spacer(Modifier.width(8.dp))

                            // External Player
                            PlayerControlButton(
                                icon = Icons.AutoMirrored.Filled.OpenInNew,
                                isSelected = selectedControl == 4,
                                onClick = { activateControl(4) }
                            )

                            Spacer(Modifier.width(8.dp))

                            // Channel List Drawer
                            PlayerControlButton(
                                icon = Icons.AutoMirrored.Filled.List,
                                isSelected = selectedControl == 5,
                                onClick = { activateControl(5) }
                            )
                        }
                    }
                }
            }
        }

        // Quick Channel & Group Guide Drawer (Overlay panel)
        val playerGroups = remember(channels) {
            listOf<String?>(null) + channels.map { it.groupTitle }.filter { it.isNotBlank() }.distinct()
        }
        val groupCounts = remember(channels) {
            channels.groupingBy { it.groupTitle }.eachCount()
        }
        var selectedDrawerGroup by remember(channel.groupTitle) {
            mutableStateOf<String?>(channel.groupTitle.ifBlank { null })
        }
        val drawerChannels = remember(channels, selectedDrawerGroup) {
            if (selectedDrawerGroup == null) channels
            else channels.filter { it.groupTitle == selectedDrawerGroup }
        }

        AnimatedVisibility(
            visible = showChannelList && !showControls,
            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.55f),
                color = Color(0xFF0B0F19).copy(alpha = 0.98f),
                border = BorderStroke(1.5.dp, TvFocusHighlightColor.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                ) {
                    // Header with title and active group breadcrumb
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = TvFocusHighlightColor.copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.List,
                                        contentDescription = null,
                                        tint = TvFocusHighlightColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.epg_guide_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = (selectedDrawerGroup ?: stringResource(R.string.simplified_all_channels)) + " • ${drawerChannels.size} canales",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TvFocusHighlightColor.copy(alpha = 0.85f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        IconButton(onClick = { showChannelList = false }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.close),
                                tint = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.padding(vertical = 10.dp)
                    )

                    // 2-Column Split: Groups on Left (175dp), Channels on Right (Remaining)
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // LEFT: Groups list
                        Surface(
                            modifier = Modifier
                                .width(175.dp)
                                .fillMaxHeight(),
                            color = Color.White.copy(alpha = 0.04f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(playerGroups) { gName ->
                                    val isSelected = (gName == selectedDrawerGroup)
                                    val count = if (gName == null) channels.size else (groupCounts[gName] ?: 0)
                                    val gLabel = gName ?: stringResource(R.string.simplified_all_channels)

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .tvFocusable(
                                                shape = RoundedCornerShape(10.dp),
                                                onClick = {
                                                    selectedDrawerGroup = gName
                                                    try {
                                                        drawerFocusRequester.requestFocus()
                                                    } catch (_: Exception) {}
                                                }
                                            ),
                                        color = if (isSelected) TvFocusHighlightColor.copy(alpha = 0.25f) else Color.Transparent,
                                        border = if (isSelected) BorderStroke(1.5.dp, TvFocusHighlightColor) else null,
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (gName == null) Icons.Filled.FolderSpecial else Icons.Filled.Folder,
                                                    contentDescription = null,
                                                    tint = if (isSelected) TvFocusHighlightColor else Color.White.copy(alpha = 0.6f),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = gLabel,
                                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.8f),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Spacer(Modifier.width(4.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isSelected) TvFocusHighlightColor.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f)
                                            ) {
                                                Text(
                                                    text = count.toString(),
                                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // RIGHT: Channels list
                        val drawerListState = rememberLazyListState()
                        LaunchedEffect(selectedDrawerGroup) {
                            val activeIdxInGroup = drawerChannels.indexOfFirst { it.id == channel.id }
                            if (activeIdxInGroup >= 0) {
                                drawerListState.scrollToItem((activeIdxInGroup - 1).coerceAtLeast(0))
                            } else {
                                drawerListState.scrollToItem(0)
                            }
                        }

                        LazyColumn(
                            state = drawerListState,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(drawerChannels, key = { _, ch -> ch.id }) { idx, ch ->
                                val isCurrentChannel = ch.id == channel.id
                                val shouldHoldInitialFocus = (isCurrentChannel || (idx == 0 && drawerChannels.none { it.id == channel.id }))
                                val (nowProg, _) = epgNowNextFor(ch) ?: (null to null)

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(if (shouldHoldInitialFocus) Modifier.focusRequester(drawerFocusRequester) else Modifier)
                                        .tvFocusable(
                                            shape = RoundedCornerShape(10.dp),
                                            onClick = {
                                                val targetIdx = channels.indexOfFirst { it.id == ch.id }
                                                if (targetIdx >= 0) {
                                                    currentIndex = targetIdx
                                                    showChannelList = false
                                                }
                                            }
                                        ),
                                    color = if (isCurrentChannel) TvFocusHighlightColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f),
                                    border = if (isCurrentChannel) BorderStroke(1.5.dp, TvFocusHighlightColor) else null,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        val globalIdx = channels.indexOfFirst { it.id == ch.id }
                                        Text(
                                            text = "${globalIdx + 1}",
                                            color = if (isCurrentChannel) TvFocusHighlightColor else Color(0xFF94A3B8),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.width(26.dp)
                                        )

                                        // Channel logo or icon
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color.White.copy(alpha = 0.08f),
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            if (ch.logoUrl.isNotBlank()) {
                                                AsyncImage(
                                                    model = ch.logoUrl,
                                                    contentDescription = ch.name,
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .padding(2.dp)
                                                )
                                            } else {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Tv,
                                                        contentDescription = null,
                                                        tint = Color(0xFF94A3B8),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = ch.name,
                                                color = if (isCurrentChannel) Color.White else Color(0xFFF8FAFC),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isCurrentChannel) FontWeight.Bold else FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (nowProg != null) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    modifier = Modifier.padding(top = 2.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(6.dp)
                                                            .background(Color(0xFFEF4444), CircleShape)
                                                    )
                                                    Text(
                                                        text = "${nowProg.title} (${formatEpgTime(nowProg.startMs)} - ${formatEpgTime(nowProg.stopMs)})",
                                                        color = TvFocusHighlightColor,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            } else if (selectedDrawerGroup == null && ch.groupTitle.isNotBlank()) {
                                                Text(
                                                    text = ch.groupTitle,
                                                    color = Color(0xFF94A3B8),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        if (isCurrentChannel) {
                                            Icon(
                                                Icons.Filled.PlayArrow,
                                                contentDescription = null,
                                                tint = TvFocusHighlightColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerControlButton(
    icon: ImageVector,
    isSelected: Boolean,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = when {
            isPrimary -> TvFocusHighlightColor
            isSelected -> Color.White.copy(alpha = 0.3f)
            else -> Color.White.copy(alpha = 0.12f)
        },
        border = tvRing(isSelected, width = 2.5.dp, color = TvFocusHighlightColor),
        modifier = Modifier
            .size(if (isPrimary) 56.dp else 44.dp)
            .tvFocusable(shape = CircleShape)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isPrimary) Color.Black else Color.White,
                modifier = Modifier.size(if (isPrimary) 30.dp else 22.dp)
            )
        }
    }
}

private fun formatEpgTime(timeMs: Long): String {
    val date = java.util.Date(timeMs)
    val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return format.format(date)
}
