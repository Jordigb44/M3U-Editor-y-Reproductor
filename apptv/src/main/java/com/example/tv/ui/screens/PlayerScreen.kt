package com.example.tv.ui.screens

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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.media3.exoplayer.DefaultLoadControl
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
import com.example.tv.PlayerKeyRouter
import com.example.tv.R
import com.example.tv.ui.components.TvFocusHighlightColor
import com.example.tv.ui.components.tvFocusable
import kotlinx.coroutines.delay
import java.util.Locale

/** Browser-like User-Agent so IPTV servers/CDNs accept the stream requests. */
private const val PLAYER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 4_000L
private const val CHANNEL_BANNER_TIMEOUT_MS = 2_500L

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

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channels: List<Channel>,
    startIndex: Int,
    onBack: () -> Unit,
    epgUrl: String? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Back always closes the player and returns to the editor, regardless of focus.
    BackHandler(onBack = onBack)

    // Prevent screen dimming or sleep mode during video playback.
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
    var channelBanner by remember { mutableStateOf<String?>(null) }
    var epgByChannel by remember { mutableStateOf<Map<String, List<EpgProgram>>?>(null) }
    var epgLoading by remember { mutableStateOf(false) }

    var currentIndex by remember(startIndex) {
        mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0)))
    }
    // Recomputed on every recomposition (currentIndex is State), so it follows zapping.
    val channel: Channel = channels[currentIndex]

    fun buildPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 5000, 500, 1000)
            .build()

        // Browser User-Agent + cross-protocol redirects (https->http) are required by many
        // IPTV servers/CDNs; without them valid channels show a black screen.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(PLAYER_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(FastReconnectErrorPolicy(maxRetries))

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
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

    /** Zapping: switch to the next/previous channel in the list. */
    fun switchChannel(delta: Int) {
        if (channels.size <= 1) return
        currentIndex = (currentIndex + delta + channels.size) % channels.size
        showControls = true
    }

    /** (now playing, next) for a channel, from the loaded EPG, or null if unavailable. */
    fun epgNowNextFor(ch: Channel): Pair<EpgProgram?, EpgProgram?>? {
        val map = epgByChannel ?: return null
        val id = ch.attributes["tvg-id"] ?: return null
        val list = map[id] ?: return null
        if (list.isEmpty()) return null
        return XmltvParser.nowAndNext(list, System.currentTimeMillis())
    }

    fun buildChannelBanner(): String {
        val ch = channels[currentIndex]
        val base = context.getString(R.string.tv_channel_banner, ch.name, currentIndex + 1, channels.size)
        val epg = epgNowNextFor(ch)
        if (epg == null) return base
        val now = epg.first?.title?.let { context.getString(R.string.epg_now, it) }
        val next = epg.second?.title?.let { context.getString(R.string.epg_next, it) }
        return listOfNotNull(base, now, next).joinToString("\n")
    }

    fun triggerReconnect() {
        connectionFailed = false
        isReconnecting = true
        player.setMediaItem(MediaItem.fromUri(channel.url))
        player.prepare()
        player.playWhenReady = true
    }

    // One-time player listener (reads the current channel dynamically on errors).
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

    // Load / switch the active channel.
    LaunchedEffect(currentIndex) {
        retryCount = 0
        isReconnecting = false
        connectionFailed = false
        lastError = null
        channelBanner = buildChannelBanner()
        player.setMediaItem(MediaItem.fromUri(channel.url))
        player.prepare()
        player.playWhenReady = true
    }

    // Load the EPG guide once per player session (only for channels in this list).
    LaunchedEffect(epgUrl) {
        if (epgUrl.isNullOrBlank()) return@LaunchedEffect
        epgLoading = true
        val wanted = channels.mapNotNull { it.attributes["tvg-id"] }
            .filter { it.isNotBlank() }.toSet()
        if (wanted.isNotEmpty()) {
            epgByChannel = EpgLoader.load(epgUrl, wanted)
        }
        epgLoading = false
    }

    // Route volume-button presses (handled at the Activity level) into zapping.
    DisposableEffect(Unit) {
        PlayerKeyRouter.onZap = { switchChannel(it) }
        onDispose { PlayerKeyRouter.onZap = null }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE ||
                event == androidx.lifecycle.Lifecycle.Event.ON_STOP
            ) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-hide controls after a few seconds of inactivity.
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(CONTROLS_TIMEOUT_MS)
            showControls = false
        }
    }

    // Poll playback position while the controls are visible.
    LaunchedEffect(showControls) {
        while (showControls) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    // Auto-clear the seek feedback bubble.
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(900)
            seekFeedback = null
        }
    }

    // Auto-clear the channel-change banner.
    LaunchedEffect(channelBanner) {
        if (channelBanner != null) {
            delay(CHANNEL_BANNER_TIMEOUT_MS)
            channelBanner = null
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    showControls = true
                    when (keyEvent.key) {
                        Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
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
                        Key.DirectionLeft -> {
                            seekRelative(-SEEK_STEP_MS)
                            true
                        }
                        Key.DirectionRight -> {
                            seekRelative(SEEK_STEP_MS)
                            true
                        }
                        Key.DirectionUp, Key.PageUp, Key.VolumeDown -> {
                            switchChannel(-1)
                            true
                        }
                        Key.DirectionDown, Key.PageDown, Key.VolumeUp -> {
                            switchChannel(1)
                            true
                        }
                        Key.Back, Key.Escape -> {
                            onBack()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        // ---------- Video surface ----------
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    this.resizeMode = resizeMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = resizeMode
                view.keepScreenOn = true
            },
            modifier = Modifier.fillMaxSize()
        )

        // ---------- Buffering indicator ----------
        if (!isReconnecting && !connectionFailed && playbackState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp),
                color = TvFocusHighlightColor,
                strokeWidth = 5.dp
            )
        }

        // ---------- Channel-change banner ----------
        val banner = channelBanner
        if (banner != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.8f),
                contentColor = Color.White
            ) {
                Text(
                    text = banner,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 26.dp, vertical = 12.dp)
                )
            }
        }

        // ---------- Controls overlay (auto-hides) ----------
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar with gradient scrim
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent)
                            )
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.tvFocusable(shape = CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (channel.groupTitle.isNotBlank()) {
                                Text(
                                    text = channel.groupTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val nowTitle = remember(epgByChannel, currentIndex) {
                                epgNowNextFor(channels[currentIndex])?.first?.title
                            }
                            if (nowTitle != null) {
                                Text(
                                    text = stringResource(R.string.epg_now, nowTitle),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else if (epgLoading) {
                                Text(
                                    text = stringResource(R.string.tv_epg_loading),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f),
                                    maxLines = 1
                                )
                            }
                        }
                        if (durationMs <= 0L) {
                            Surface(
                                color = MaterialTheme.colorScheme.error,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.tv_live_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Bottom bar with gradient scrim
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                            )
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp, vertical = 22.dp)
                    ) {
                        // ---------- Progress + times ----------
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatMs(positionMs),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.22f))
                            ) {
                                val progress =
                                    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(progress)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(Color(0xFF6D5EF0), TvFocusHighlightColor)
                                            )
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = if (durationMs > 0) formatMs(durationMs) else "—",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // ---------- Control buttons (D-pad friendly) ----------
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ControlButton(
                                icon = Icons.Filled.FastRewind,
                                contentDescription = "−10s",
                                onClick = { seekRelative(-SEEK_STEP_MS) }
                            )
                            Spacer(modifier = Modifier.width(24.dp))

                            // Big play/pause button
                            Surface(
                                onClick = { togglePlay() },
                                shape = CircleShape,
                                color = Color.White,
                                modifier = Modifier
                                    .size(76.dp)
                                    .tvFocusable(shape = CircleShape)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (isPlaying) stringResource(R.string.playing) else stringResource(R.string.play),
                                        tint = Color.Black,
                                        modifier = Modifier.size(42.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(24.dp))

                            ControlButton(
                                icon = Icons.Filled.FastForward,
                                contentDescription = "+10s",
                                onClick = { seekRelative(SEEK_STEP_MS) }
                            )
                            Spacer(modifier = Modifier.width(48.dp))

                            ControlButton(
                                icon = Icons.Filled.AspectRatio,
                                contentDescription = stringResource(R.string.player_aspect_ratio),
                                onClick = {
                                    resizeMode = when (resizeMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(16.dp))

                            ControlButton(
                                icon = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.play_with_external_app),
                                onClick = { launchExternalPlayer(context, channel.url, channel.name) }
                            )
                        }
                    }
                }
            }
        }

        // ---------- Seek feedback bubble ----------
        val feedback = seekFeedback
        if (feedback != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 120.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.75f),
                contentColor = Color.White
            ) {
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
                )
            }
        }

        // ---------- Reconnecting overlay ----------
        if (isReconnecting) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.85f),
                contentColor = Color.White
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator(color = TvFocusHighlightColor, strokeWidth = 4.dp)
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.reconnecting_count, retryCount),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else if (connectionFailed) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.92f),
                contentColor = Color.White
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.reconnect_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    val errorDetail = lastError
                    if (errorDetail != null) {
                        Text(
                            text = errorDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = {
                                retryCount = 0
                                triggerReconnect()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.tvFocusable()
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.retry), fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                launchExternalPlayer(context, channel.url, channel.name)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.tvFocusable()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.player_try_external_app), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.14f),
        modifier = Modifier
            .size(66.dp)
            .tvFocusable(shape = RoundedCornerShape(18.dp))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

fun launchExternalPlayer(
    context: Context,
    channelUrl: String,
    channelName: String,
    targetPackage: String? = null,
    targetActivity: String? = null
) {
    try {
        val uri = Uri.parse(channelUrl)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            putExtra("title", channelName)
            putExtra("displayName", channelName)
            putExtra("poster", channelName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (!targetPackage.isNullOrBlank()) {
            if (!targetActivity.isNullOrBlank()) {
                intent.setClassName(targetPackage, targetActivity)
            } else {
                intent.setPackage(targetPackage)
            }
            context.startActivity(intent)
            return
        }

        val pm = context.packageManager
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val validApps = resolveInfos.filter {
            it.activityInfo != null && it.activityInfo.packageName != context.packageName
        }

        if (validApps.isNotEmpty()) {
            if (validApps.size == 1) {
                val app = validApps.first()
                val explicitIntent = Intent(intent).apply {
                    setClassName(app.activityInfo.packageName, app.activityInfo.name)
                }
                context.startActivity(explicitIntent)
            } else {
                val chooserIntent = Intent.createChooser(intent, context.getString(R.string.play_channel_with))
                chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooserIntent)
            }
        } else {
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                putExtra("title", channelName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooserIntent = Intent.createChooser(fallbackIntent, context.getString(R.string.play_channel_with))
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooserIntent)
        }
    } catch (e: Exception) {
        Toast.makeText(
            context,
            context.getString(R.string.external_player_error, e.message),
            Toast.LENGTH_LONG
        ).show()
    }
}
