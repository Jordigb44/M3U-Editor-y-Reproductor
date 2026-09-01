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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.example.ui.EditorState
import com.example.ui.EditorViewModel
import com.example.ui.PlayerSession
import com.example.ui.components.TvFocusHighlightColor
import com.example.ui.components.tvFocusable
import com.example.ui.components.tvRing
import com.jordiguixbetancor.m3ueditor.R
import kotlinx.coroutines.launch

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
        PlayerScreen(
            channels = session.channels,
            startIndex = session.index,
            epgUrl = viewModel.activeEpgUrl(),
            simplifiedMode = true,
            onBack = {
                playerSession = null
            }
        )
        return
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

                    // Switch to Advanced Mode Button
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
                // Main Split-Screen: Left (Groups) | Right (Channels)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // LEFT COLUMN: GROUPS / CATEGORIES (30% - 35% width)
                    Surface(
                        modifier = Modifier
                            .weight(0.32f)
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

                    // RIGHT COLUMN: CHANNELS (65% - 70% width)
                    Surface(
                        modifier = Modifier
                            .weight(0.68f)
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
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(activeChannels, key = { _, ch -> ch.id }) { index, channel ->
                                    val isLastPlayed = channel.id == state.lastPlayedChannelId

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .tvFocusable(shape = RoundedCornerShape(12.dp))
                                            .clickable {
                                                viewModel.saveLastPlayedChannel(context, channel.id, state.selectedGroup)
                                                playerSession = PlayerSession(
                                                    channels = activeChannels,
                                                    index = index
                                                )
                                            },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isLastPlayed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                                        border = if (isLastPlayed) BorderStroke(1.5.dp, TvFocusHighlightColor) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            // Channel Number
                                            Text(
                                                text = "${index + 1}",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isLastPlayed) TvFocusHighlightColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.width(36.dp)
                                            )

                                            // Channel Name
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = channel.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = if (isLastPlayed) FontWeight.Bold else FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (state.selectedGroup == null && channel.groupTitle.isNotBlank()) {
                                                    Text(
                                                        text = channel.groupTitle,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            // Play icon / Last played badge
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
                }
            }
        }
    }
}
