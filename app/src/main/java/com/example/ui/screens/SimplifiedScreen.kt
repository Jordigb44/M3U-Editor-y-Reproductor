package com.example.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.Channel
import com.example.ui.AppViewMode
import com.example.ui.DefaultPlayerMode
import com.example.ui.EditorState
import com.example.ui.EditorViewModel
import com.example.ui.PlayerSession
import com.example.ui.components.AppSettingsDialog
import com.example.ui.components.ParentalPinDialog
import com.example.ui.components.PlayChoiceDialog
import com.example.ui.components.TvFocusHighlightColor
import com.example.ui.components.tvFocusable
import com.example.ui.components.tvRing
import com.jordiguixbetancor.m3ueditor.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatEpgTime(timeMs: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timeMs))
}

private fun Channel.matchesSearch(query: String): Boolean =
    query.isBlank() ||
        name.contains(query, ignoreCase = true) ||
        groupTitle.contains(query, ignoreCase = true) ||
        url.contains(query, ignoreCase = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimplifiedScreen(
    state: EditorState,
    viewModel: EditorViewModel,
    onSwitchToAdvanced: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var playerSession by remember { mutableStateOf<PlayerSession?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var hasAutoPlayedOnStart by rememberSaveable { mutableStateOf(false) }
    var previewChannelId by remember { mutableStateOf<String?>(state.lastPlayedChannelId) }
    var currentTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }

    // Live EPG clock ticker every 30s
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            currentTimeMs = System.currentTimeMillis()
        }
    }

    // Collect EPG state from ViewModel
    val epgByChannel by viewModel.epgByChannel.collectAsState()

    // Intercept back button: close player if active, or close search
    BackHandler {
        if (playerSession != null) {
            playerSession = null
        } else if (isSearching) {
            isSearching = false
            searchQuery = ""
        }
    }

    // Active channels in currently selected group
    val activeChannels = remember(state.channels, state.selectedGroup, searchQuery) {
        state.channels.filter { ch ->
            (state.selectedGroup == null || ch.groupTitle == state.selectedGroup) &&
                ch.matchesSearch(searchQuery)
        }
    }

    // Distinct groups with their channel counts
    val groupCounts = remember(state.channels) {
        state.channels.groupingBy { it.groupTitle }.eachCount()
    }

    val groupsList = remember(state.groups, groupCounts) {
        listOf<String?>(null) + state.groups.filter { it.isNotBlank() }
    }

    // Channel list scroll state
    val channelListState = rememberLazyListState()
    val groupListState = rememberLazyListState()

    // Automatically resume playback of last channel on start/open
    LaunchedEffect(state.channels, state.lastPlayedChannelId) {
        if (!hasAutoPlayedOnStart && state.channels.isNotEmpty()) {
            val lastId = state.lastPlayedChannelId
            if (!lastId.isNullOrBlank()) {
                val targetIndex = activeChannels.indexOfFirst { it.id == lastId }
                if (targetIndex >= 0) {
                    hasAutoPlayedOnStart = true
                    playerSession = PlayerSession(
                        channels = activeChannels,
                        index = targetIndex
                    )
                } else {
                    val allIndex = state.channels.indexOfFirst { it.id == lastId }
                    if (allIndex >= 0) {
                        hasAutoPlayedOnStart = true
                        val targetGroup = state.channels[allIndex].groupTitle
                        viewModel.selectGroup(targetGroup)
                        val groupChannels = state.channels.filter { it.groupTitle == targetGroup }
                        val indexInGroup = groupChannels.indexOfFirst { it.id == lastId }.coerceAtLeast(0)
                        playerSession = PlayerSession(
                            channels = groupChannels,
                            index = indexInGroup
                        )
                    }
                }
            }
        }
    }

    // Restore scroll position to last played channel on first composition or group change
    LaunchedEffect(state.selectedGroup, state.lastPlayedChannelId) {
        val lastId = state.lastPlayedChannelId
        if (!lastId.isNullOrBlank() && activeChannels.isNotEmpty()) {
            val targetIndex = activeChannels.indexOfFirst { it.id == lastId }
            if (targetIndex >= 0) {
                channelListState.scrollToItem((targetIndex - 1).coerceAtLeast(0))
            }
        }
    }

    // Restore scroll position in groups list
    LaunchedEffect(state.selectedGroup) {
        val currentGroup = state.selectedGroup
        val groupIndex = groupsList.indexOf(currentGroup)
        if (groupIndex >= 0) {
            groupListState.scrollToItem((groupIndex - 1).coerceAtLeast(0))
        }
    }

    if (playerSession != null) {
        val session = playerSession!!
        val channelTarget = session.channels.getOrNull(session.index)
        val fullListIndex = state.channels.indexOfFirst { it.id == channelTarget?.id }.coerceAtLeast(0)
        PlayerScreen(
            channels = state.channels,
            startIndex = fullListIndex,
            epgUrl = viewModel.activeEpgUrl(),
            simplifiedMode = true,
            onChannelChanged = { ch ->
                viewModel.saveLastPlayedChannel(context, ch.id, ch.groupTitle)
            },
            onBack = {
                playerSession = null
            }
        )
        return
    }

    var pendingChoiceChannel by remember { mutableStateOf<Channel?>(null) }

    fun playChannel(channel: Channel, index: Int) {
        previewChannelId = channel.id
        viewModel.saveLastPlayedChannel(context, channel.id, state.selectedGroup)
        when (state.defaultPlayerMode) {
            DefaultPlayerMode.INTERNAL -> {
                playerSession = PlayerSession(
                    channels = activeChannels,
                    index = index
                )
            }
            DefaultPlayerMode.EXTERNAL -> {
                launchExternalPlayer(
                    context = context,
                    channelUrl = channel.url,
                    channelName = channel.name,
                    targetPackage = state.preferredExternalPackage,
                    targetActivity = state.preferredExternalActivity
                )
            }
            DefaultPlayerMode.ASK -> {
                pendingChoiceChannel = channel
            }
        }
    }

    if (pendingChoiceChannel != null) {
        val ch = pendingChoiceChannel!!
        PlayChoiceDialog(
            channel = ch,
            onDismiss = { pendingChoiceChannel = null },
            onPlayInternal = {
                val idx = activeChannels.indexOfFirst { it.id == ch.id }
                if (idx >= 0) {
                    playerSession = PlayerSession(channels = activeChannels, index = idx)
                }
                pendingChoiceChannel = null
            },
            onPlayExternal = {
                launchExternalPlayer(
                    context = context,
                    channelUrl = ch.url,
                    channelName = ch.name,
                    targetPackage = state.preferredExternalPackage,
                    targetActivity = state.preferredExternalActivity
                )
                pendingChoiceChannel = null
            },
            onSetDefaultMode = { mode, pkg, act, name ->
                viewModel.setDefaultPlayerMode(context, mode, pkg, act, name)
            }
        )
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }

    if (showPinDialog) {
        ParentalPinDialog(
            viewModel = viewModel,
            onSuccess = {
                showPinDialog = false
                showSettingsDialog = true
            },
            onDismiss = { showPinDialog = false }
        )
    }

    if (showSettingsDialog) {
        AppSettingsDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = { showSettingsDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Tv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = if (state.activePlaylistName.isNotBlank()) state.activePlaylistName else stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.channels_count, state.channels.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Search toggle
                    IconButton(
                        onClick = { isSearching = !isSearching },
                        modifier = Modifier.tvFocusable(shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isSearching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = stringResource(R.string.search_placeholder)
                        )
                    }

                    // Settings Button (Parental Control / Player / Modes)
                    IconButton(
                        onClick = {
                            if (state.parentalControlEnabled && !state.isParentalUnlocked) {
                                showPinDialog = true
                            } else {
                                showSettingsDialog = true
                            }
                        },
                        modifier = Modifier.tvFocusable(shape = CircleShape)
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings)
                        )
                    }

                    // Switch to Advanced Mode Button (Hidden if mode switch is locked by parental control)
                    if (!state.parentalControlEnabled || !state.lockModeSwitch) {
                        FilledTonalButton(
                            onClick = onSwitchToAdvanced,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .tvFocusable(shape = RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                Icons.Filled.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.editor),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Optional Search Bar
            if (isSearching) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            if (state.channels.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Filled.TvOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = stringResource(R.string.simplified_no_playlist),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        Button(
                            onClick = onSwitchToAdvanced,
                            modifier = Modifier.tvFocusable()
                        ) {
                            Text(stringResource(R.string.mode_switch_to_advanced))
                        }
                    }
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    val isWideScreen = maxWidth >= 720.dp
                    val previewChannel = activeChannels.firstOrNull { it.id == previewChannelId }
                        ?: activeChannels.firstOrNull { it.id == state.lastPlayedChannelId }
                        ?: activeChannels.firstOrNull()

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // LEFT COLUMN: GROUPS / CATEGORIES (22% on wide, 34% on mobile)
                        Surface(
                            modifier = Modifier
                                .weight(if (isWideScreen) 0.22f else 0.34f)
                                .fillMaxHeight(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                                Text(
                                    text = stringResource(R.string.groups).uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )

                                LazyColumn(
                                    state = groupListState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    itemsIndexed(groupsList) { _, groupName ->
                                        val isSelected = (groupName == state.selectedGroup)
                                        val count = if (groupName == null) state.channels.size else (groupCounts[groupName] ?: 0)
                                        val label = groupName ?: stringResource(R.string.simplified_all_channels)

                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .tvFocusable(shape = RoundedCornerShape(12.dp))
                                                .clickable {
                                                    viewModel.selectGroup(groupName)
                                                },
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                                                ) {
                                                    Text(
                                                        text = count.toString(),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // CENTER COLUMN: CHANNELS LIST (44% on wide, 66% on mobile)
                        Surface(
                            modifier = Modifier
                                .weight(if (isWideScreen) 0.44f else 0.66f)
                                .fillMaxHeight(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = (state.selectedGroup ?: stringResource(R.string.simplified_all_channels)).uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = stringResource(R.string.channels_count, activeChannels.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                LazyColumn(
                                    state = channelListState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    itemsIndexed(activeChannels, key = { _, ch -> ch.id }) { index, channel ->
                                        val isLastPlayed = channel.id == state.lastPlayedChannelId
                                        val isPreviewSelected = isWideScreen && channel.id == previewChannel?.id
                                        val (nowProg, _) = viewModel.getNowAndNext(channel, currentTimeMs)

                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged {
                                                    if (it.isFocused) {
                                                        previewChannelId = channel.id
                                                    }
                                                }
                                                .tvFocusable(shape = RoundedCornerShape(14.dp))
                                                .clickable {
                                                    playChannel(channel, index)
                                                },
                                            shape = RoundedCornerShape(14.dp),
                                            color = when {
                                                isPreviewSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                                isLastPlayed -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                                else -> MaterialTheme.colorScheme.surface
                                            },
                                            border = when {
                                                isPreviewSelected -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                                                isLastPlayed -> BorderStroke(1.5.dp, TvFocusHighlightColor)
                                                else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                // Channel Number
                                                Text(
                                                    text = "${index + 1}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isLastPlayed) TvFocusHighlightColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.width(32.dp)
                                                )

                                                // Channel Name + Live Program info
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = channel.name,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = if (isLastPlayed || isPreviewSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )

                                                    // EPG Now Playing Line
                                                    if (nowProg != null) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                            modifier = Modifier.padding(top = 2.dp)
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(7.dp)
                                                                    .background(Color(0xFFE53935), CircleShape)
                                                            )
                                                            Text(
                                                                text = "${nowProg.title} (${formatEpgTime(nowProg.startMs)} - ${formatEpgTime(nowProg.stopMs)})",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.Medium,
                                                                color = MaterialTheme.colorScheme.primary,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    } else if (state.selectedGroup == null && channel.groupTitle.isNotBlank()) {
                                                        Text(
                                                            text = channel.groupTitle,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }

                                                // Play icon
                                                Icon(
                                                    imageVector = Icons.Filled.PlayArrow,
                                                    contentDescription = stringResource(R.string.play),
                                                    tint = if (isLastPlayed) TvFocusHighlightColor else MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // RIGHT COLUMN: EPG TV GUIDE PREVIEW PANEL (34% width on wide screens / TV)
                        if (isWideScreen && previewChannel != null) {
                            val (nowProg, nextProg) = viewModel.getNowAndNext(previewChannel, currentTimeMs)
                            val previewIndex = activeChannels.indexOfFirst { it.id == previewChannel.id }.coerceAtLeast(0)

                            Surface(
                                modifier = Modifier
                                    .weight(0.34f)
                                    .fillMaxHeight(),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Channel Header Card
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(42.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "${previewIndex + 1}",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = previewChannel.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (previewChannel.groupTitle.isNotBlank()) {
                                                Text(
                                                    text = previewChannel.groupTitle,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }

                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                    // EPG Program Details
                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // NOW PLAYING
                                        item {
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(12.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(8.dp)
                                                                .background(Color(0xFFE53935), CircleShape)
                                                        )
                                                        Text(
                                                            text = stringResource(R.string.epg_now_playing),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFFE53935)
                                                        )
                                                        if (nowProg != null) {
                                                            Spacer(Modifier.weight(1f))
                                                            Text(
                                                                text = "${formatEpgTime(nowProg.startMs)} - ${formatEpgTime(nowProg.stopMs)}",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }

                                                    if (nowProg != null) {
                                                        Text(
                                                            text = nowProg.title,
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )

                                                        // Progress Bar
                                                        val totalDuration = (nowProg.stopMs - nowProg.startMs).coerceAtLeast(1L)
                                                        val elapsed = (currentTimeMs - nowProg.startMs).coerceIn(0L, totalDuration)
                                                        val progress = elapsed.toFloat() / totalDuration.toFloat()

                                                        LinearProgressIndicator(
                                                            progress = { progress },
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(4.dp)
                                                                .clip(RoundedCornerShape(2.dp)),
                                                            color = MaterialTheme.colorScheme.primary,
                                                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                                        )

                                                        if (nowProg.description.isNotBlank()) {
                                                            Text(
                                                                text = nowProg.description,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 4,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    } else {
                                                        Text(
                                                            text = stringResource(R.string.epg_no_info),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // UP NEXT
                                        item {
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(12.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.SkipNext,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Text(
                                                            text = stringResource(R.string.epg_up_next),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        if (nextProg != null) {
                                                            Spacer(Modifier.weight(1f))
                                                            Text(
                                                                text = "${formatEpgTime(nextProg.startMs)} - ${formatEpgTime(nextProg.stopMs)}",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }

                                                    if (nextProg != null) {
                                                        Text(
                                                            text = nextProg.title,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        if (nextProg.description.isNotBlank()) {
                                                            Text(
                                                                text = nextProg.description,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 3,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    } else {
                                                        Text(
                                                            text = stringResource(R.string.epg_no_info),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Play Actions (Integrated & External)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = {
                                                playChannel(previewChannel, previewIndex)
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .tvFocusable(shape = RoundedCornerShape(12.dp)),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.play_channel),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                viewModel.saveLastPlayedChannel(context, previewChannel.id, state.selectedGroup)
                                                launchExternalPlayer(
                                                    context = context,
                                                    channelUrl = previewChannel.url,
                                                    channelName = previewChannel.name,
                                                    targetPackage = state.preferredExternalPackage,
                                                    targetActivity = state.preferredExternalActivity
                                                )
                                            },
                                            modifier = Modifier
                                                .height(48.dp)
                                                .tvFocusable(shape = RoundedCornerShape(12.dp)),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = stringResource(R.string.open_external_player),
                                                tint = MaterialTheme.colorScheme.primary
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
