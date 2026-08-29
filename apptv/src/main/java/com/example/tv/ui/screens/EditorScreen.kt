package com.example.tv.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tv.R
import com.example.data.Channel
import com.example.tv.ui.components.TvFocusHighlightColor
import com.example.tv.ui.components.tvFocusable
import com.example.ui.DefaultPlayerMode
import com.example.ui.EditorState
import com.example.ui.EditorViewModel
import com.example.ui.PlayerSession

/** Advanced search: matches channel name, group or stream URL (case-insensitive). */
private fun Channel.matchesSearch(query: String): Boolean =
    query.isBlank() ||
        name.contains(query, ignoreCase = true) ||
        groupTitle.contains(query, ignoreCase = true) ||
        url.contains(query, ignoreCase = true)

private data class ExternalPlayerApp(
    val packageName: String,
    val activityName: String,
    val appName: String
)

private fun getInstalledExternalPlayers(context: Context): List<ExternalPlayerApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse("http://example.com/video.mp4"), "video/*")
    }
    val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

    return resolveInfos.mapNotNull { ri ->
        val pkg = ri.activityInfo.packageName
        if (pkg != context.packageName) {
            ExternalPlayerApp(
                packageName = pkg,
                activityName = ri.activityInfo.name,
                appName = ri.loadLabel(pm).toString()
            )
        } else null
    }.distinctBy { it.packageName }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    state: EditorState,
    viewModel: EditorViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var playerSession by remember { mutableStateOf<PlayerSession?>(null) }
    var searchVisible by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    var groupSelectMode by remember { mutableStateOf(false) }
    var showDeleteGroupsConfirm by remember { mutableStateOf(false) }
    var showDeleteChannelsConfirm by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showPlayerSettingsDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showGroupManageDialog by remember { mutableStateOf(false) }
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var pendingChoiceChannel by remember { mutableStateOf<Channel?>(null) }
    var renameGroupTarget by remember { mutableStateOf<String?>(null) }
    var exportSuccessMessage by remember { mutableStateOf<String?>(null) }
    var exportErrorMessage by remember { mutableStateOf<String?>(null) }

    fun handleBack() {
        if (playerSession != null) {
            playerSession = null
        } else if (state.selectedChannelIds.isNotEmpty()) {
            viewModel.clearSelection()
        } else if (selectMode) {
            selectMode = false
        } else if (state.selectedGroups.isNotEmpty()) {
            viewModel.clearGroupSelection()
        } else if (groupSelectMode) {
            groupSelectMode = false
        } else {
            onBack()
        }
    }

    BackHandler { handleBack() }

    val filteredChannels = state.channels.filter {
        (state.selectedGroup == null || it.groupTitle == state.selectedGroup) &&
            it.matchesSearch(state.searchQuery)
    }
    val filteredChannelIds = filteredChannels.map { it.id }

    fun handleChannelClick(channel: Channel) {
        when (state.defaultPlayerMode) {
            DefaultPlayerMode.INTERNAL -> {
                val index = filteredChannels.indexOfFirst { it.id == channel.id }
                if (index >= 0) {
                    playerSession = PlayerSession(filteredChannels, index, viewModel.activeEpgUrl())
                }
            }
            DefaultPlayerMode.EXTERNAL -> launchExternalPlayer(
                context = context,
                channelUrl = channel.url,
                channelName = channel.name,
                targetPackage = state.preferredExternalPackage,
                targetActivity = state.preferredExternalActivity
            )
            DefaultPlayerMode.ASK -> pendingChoiceChannel = channel
        }
    }

    val title = when {
        state.selectedChannelIds.isNotEmpty() ->
            stringResource(R.string.selected_count, state.selectedChannelIds.size)
        state.selectedGroups.isNotEmpty() ->
            stringResource(R.string.selected_count, state.selectedGroups.size)
        state.activePlaylistName.isNotBlank() ->
            stringResource(R.string.editor_with_name, state.activePlaylistName)
        else -> stringResource(R.string.editor)
    }

    Surface(
        modifier = Modifier
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---------- Top bar ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { handleBack() },
                    modifier = Modifier.tvFocusable(shape = RoundedCornerShape(24.dp))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = { searchVisible = !searchVisible },
                    modifier = Modifier.tvFocusable(shape = RoundedCornerShape(24.dp))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.search_placeholder),
                        modifier = Modifier.size(26.dp)
                    )
                }
                IconButton(
                    onClick = { selectMode = !selectMode },
                    modifier = Modifier.tvFocusable(shape = RoundedCornerShape(24.dp))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Checklist,
                        contentDescription = stringResource(R.string.select_all),
                        modifier = Modifier.size(26.dp)
                    )
                }
                IconButton(
                    onClick = { viewModel.toggleSelectAllChannels(filteredChannelIds) },
                    modifier = Modifier.tvFocusable(shape = RoundedCornerShape(24.dp))
                ) {
                    Icon(
                        imageVector = Icons.Filled.SelectAll,
                        contentDescription = stringResource(R.string.select_all),
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = { showExportDialog = true },
                    modifier = Modifier.tvFocusable(shape = RoundedCornerShape(24.dp))
                ) {
                    Icon(
                        imageVector = Icons.Filled.SaveAlt,
                        contentDescription = stringResource(R.string.export),
                        modifier = Modifier.size(26.dp)
                    )
                }
                IconButton(
                    onClick = { showPlayerSettingsDialog = true },
                    modifier = Modifier.tvFocusable(shape = RoundedCornerShape(24.dp))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.editor_default_player),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // ---------- Body: groups left, channels right ----------
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Left: group panel (filter + mass selection)
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.groups),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FilterChip(
                            selected = groupSelectMode,
                            onClick = {
                                groupSelectMode = !groupSelectMode
                                if (!groupSelectMode) viewModel.clearGroupSelection()
                            },
                            label = { Text(stringResource(R.string.tv_group_multiselect), style = MaterialTheme.typography.labelMedium) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (groupSelectMode) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            modifier = Modifier.tvFocusable(shape = RoundedCornerShape(16.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    if (groupSelectMode) {
                        // -------- Mass selection mode --------
                        Text(
                            text = stringResource(R.string.tv_group_multiselect_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            item(key = "select_all_groups") {
                                GroupChip(
                                    label = stringResource(R.string.select_all),
                                    count = state.groups.size,
                                    countText = stringResource(R.string.groups_count, state.groups.size),
                                    selected = state.groups.isNotEmpty() && state.groups.all { state.selectedGroups.contains(it) },
                                    onClick = { viewModel.toggleSelectAllGroups(state.groups) }
                                )
                            }
                            items(state.groups, key = { it }) { group ->
                                GroupChip(
                                    label = group,
                                    count = state.channels.count { it.groupTitle == group },
                                    selected = state.selectedGroups.contains(group),
                                    onClick = { viewModel.toggleGroupSelection(group) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.tv_groups_selected_count, state.selectedGroups.size),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (state.selectedGroups.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { showDeleteGroupsConfirm = true },
                            enabled = state.selectedGroups.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .tvFocusable(shape = RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.delete_groups), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.clearGroupSelection() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .tvFocusable(shape = RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.clear_selection), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        // -------- Filter mode --------
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            item(key = "all_groups") {
                                GroupChip(
                                    label = stringResource(R.string.all_groups),
                                    count = state.channels.size,
                                    selected = state.selectedGroup == null,
                                    onClick = { viewModel.selectGroup(null) }
                                )
                            }
                            items(state.groups, key = { it }) { group ->
                                GroupChip(
                                    label = group,
                                    count = state.channels.count { it.groupTitle == group },
                                    selected = state.selectedGroup == group,
                                    onClick = { viewModel.selectGroup(group) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showGroupManageDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .tvFocusable(shape = RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Text(stringResource(R.string.tv_manage_groups), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { showAddGroupDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .tvFocusable(shape = RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.add_new_group), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Right: channel list
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 8.dp, top = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.channels_count, filteredChannels.size),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FilterChip(
                            selected = selectMode,
                            onClick = {
                                selectMode = !selectMode
                                if (!selectMode) viewModel.clearSelection()
                            },
                            label = { Text(stringResource(R.string.tv_channel_multiselect), style = MaterialTheme.typography.labelMedium) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (selectMode) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            modifier = Modifier.tvFocusable(shape = RoundedCornerShape(16.dp))
                        )
                    }

                    if (selectMode) {
                        Text(
                            text = stringResource(R.string.tv_channel_multiselect_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }

                    if (searchVisible) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = state.searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(24.dp)) },
                                trailingIcon = {
                                    if (state.searchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { viewModel.setSearchQuery("") },
                                            modifier = Modifier.tvFocusable(shape = RoundedCornerShape(20.dp))
                                        ) {
                                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear), modifier = Modifier.size(24.dp))
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = TvFocusHighlightColor
                                )
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
                        ) {
                            items(filteredChannels, key = { it.id }) { channel ->
                                ChannelRow(
                                    channel = channel,
                                    isSelected = state.selectedChannelIds.contains(channel.id),
                                    onClick = {
                                        if (selectMode || state.selectedChannelIds.isNotEmpty()) {
                                            viewModel.toggleChannelSelection(channel.id)
                                        } else {
                                            handleChannelClick(channel)
                                        }
                                    },
                                    onLongClick = { viewModel.toggleChannelSelection(channel.id) },
                                    onPlay = { handleChannelClick(channel) },
                                    onPlayExternal = {
                                        launchExternalPlayer(context, channel.url, channel.name)
                                    }
                                )
                            }
                        }

                        // Bottom action bar: mass channel operations
                        if (selectMode || state.selectedChannelIds.isNotEmpty()) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .tvFocusable(shape = RoundedCornerShape(20.dp)),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                shadowElevation = 8.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.tv_channels_selected_count, state.selectedChannelIds.size),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (state.selectedChannelIds.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = { showMoveDialog = true },
                                        enabled = state.selectedChannelIds.isNotEmpty(),
                                        modifier = Modifier
                                            .height(52.dp)
                                            .tvFocusable(shape = RoundedCornerShape(16.dp)),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.move_to_group), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = { showDeleteChannelsConfirm = true },
                                        enabled = state.selectedChannelIds.isNotEmpty(),
                                        modifier = Modifier
                                            .height(52.dp)
                                            .tvFocusable(shape = RoundedCornerShape(16.dp)),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        )
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.delete), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    }
                                    IconButton(
                                        onClick = { viewModel.clearSelection() },
                                        modifier = Modifier.tvFocusable(shape = RoundedCornerShape(20.dp))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.clear_selection),
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

        // ---------- Error dialog ----------
        val currentError = state.error
        if (currentError != null) {
            AlertDialog(
                onDismissRequest = { viewModel.clearError() },
                title = {
                    Text(
                        text = stringResource(R.string.error_dialog_title),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(text = currentError, style = MaterialTheme.typography.bodyMedium)
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.clearError() },
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.accept))
                    }
                }
            )
        }

        // ---------- Loading overlay ----------
        if (state.isLoading) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(72.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 6.dp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.loading_playlist),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.loading_playlist_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------- Export dialog ----------
        if (showExportDialog) {
            ExportDialog(
                onDismiss = { showExportDialog = false },
                onConfirmSave = { fileName, folderPath ->
                    showExportDialog = false
                    viewModel.saveExportFile(
                        context = context,
                        fileName = fileName,
                        folderPath = folderPath,
                        onSuccess = { path ->
                            exportSuccessMessage = context.getString(R.string.export_success_path, path)
                        },
                        onError = { err ->
                            exportErrorMessage = err
                        }
                    )
                }
            )
        }

        // ---------- Default player settings ----------
        if (showPlayerSettingsDialog) {
            DefaultPlayerDialog(
                currentMode = state.defaultPlayerMode,
                preferredPackage = state.preferredExternalPackage,
                onDismiss = { showPlayerSettingsDialog = false },
                onSelectMode = { mode, pkg, act, name ->
                    viewModel.setDefaultPlayerMode(context, mode, pkg, act, name)
                }
            )
        }

        // ---------- Move selected channels to group ----------
        if (showMoveDialog) {
            MoveToGroupDialog(
                groups = state.groups,
                onDismiss = { showMoveDialog = false },
                onConfirm = { newGroup ->
                    viewModel.moveSelectedToGroup(newGroup, context)
                    showMoveDialog = false
                }
            )
        }

        // ---------- Group management ----------
        if (showGroupManageDialog) {
            GroupManageDialog(
                groups = state.groups,
                counts = remember(state.channels) { state.channels.groupingBy { it.groupTitle }.eachCount() },
                onRename = { group -> renameGroupTarget = group },
                onDelete = { group ->
                    viewModel.toggleGroupSelection(group)
                    viewModel.deleteSelectedGroups(context)
                },
                onAdd = { name -> viewModel.addNewGroup(name, context) },
                onDismiss = { showGroupManageDialog = false }
            )
        }

        // ---------- Delete selected channels (mass) ----------
        if (showDeleteChannelsConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteChannelsConfirm = false },
                title = {
                    Text(
                        text = stringResource(R.string.delete),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.tv_delete_channels_confirm, state.selectedChannelIds.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteSelectedChannels(context)
                            showDeleteChannelsConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.delete), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteChannelsConfirm = false },
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // ---------- Delete selected groups (mass) ----------
        if (showDeleteGroupsConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteGroupsConfirm = false },
                title = {
                    Text(
                        text = stringResource(R.string.delete_groups),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.tv_delete_groups_confirm, state.selectedGroups.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteSelectedGroups(context)
                            showDeleteGroupsConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.delete), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteGroupsConfirm = false },
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // ---------- Add group (left panel button) ----------
        if (showAddGroupDialog) {
            NameDialog(
                title = stringResource(R.string.add_new_group),
                initialValue = "",
                onDismiss = { showAddGroupDialog = false },
                onConfirm = { name ->
                    viewModel.addNewGroup(name, context)
                    showAddGroupDialog = false
                }
            )
        }

        // ---------- Rename group ----------
        renameGroupTarget?.let { target ->
            NameDialog(
                title = stringResource(R.string.rename_group),
                initialValue = target,
                onDismiss = { renameGroupTarget = null },
                onConfirm = { newName ->
                    viewModel.renameGroup(target, newName, context)
                    renameGroupTarget = null
                }
            )
        }

        // ---------- Play choice (ASK mode) ----------
        val choiceChannel = pendingChoiceChannel
        if (choiceChannel != null) {
            PlayChoiceDialog(
                channel = choiceChannel,
                onDismiss = { pendingChoiceChannel = null },
                onPlayInternal = {
                    val index = filteredChannels.indexOfFirst { it.id == choiceChannel.id }
                    if (index >= 0) {
                        playerSession = PlayerSession(filteredChannels, index, viewModel.activeEpgUrl())
                    }
                },
                onPlayExternal = {
                    launchExternalPlayer(
                        context = context,
                        channelUrl = choiceChannel.url,
                        channelName = choiceChannel.name,
                        targetPackage = state.preferredExternalPackage,
                        targetActivity = state.preferredExternalActivity
                    )
                },
                onSetDefaultMode = { mode, pkg, act, name ->
                    viewModel.setDefaultPlayerMode(context, mode, pkg, act, name)
                }
            )
        }

        // ---------- Export result dialogs ----------
        if (exportSuccessMessage != null) {
            AlertDialog(
                onDismissRequest = { exportSuccessMessage = null },
                title = { Text(stringResource(R.string.export), fontWeight = FontWeight.Bold) },
                text = { Text(exportSuccessMessage!!, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    Button(
                        onClick = { exportSuccessMessage = null },
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.accept))
                    }
                }
            )
        }

        if (exportErrorMessage != null) {
            AlertDialog(
                onDismissRequest = { exportErrorMessage = null },
                title = {
                    Text(
                        text = stringResource(R.string.export_error_title),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = { Text(exportErrorMessage!!, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    Button(
                        onClick = { exportErrorMessage = null },
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.accept))
                    }
                }
            )
        }

        // ---------- Internal player on top ----------
        playerSession?.let { session ->
            if (session.channels.isNotEmpty()) {
                PlayerScreen(
                    channels = session.channels,
                    startIndex = session.index.coerceIn(0, session.channels.lastIndex),
                    onBack = { playerSession = null }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
    onPlayExternal: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (channel.groupTitle.isNotBlank()) {
                    Text(
                        text = channel.groupTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            IconButton(
                onClick = onPlay,
                modifier = Modifier
                    .size(56.dp)
                    .tvFocusable(shape = RoundedCornerShape(20.dp))
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.play),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(
                onClick = onPlayExternal,
                modifier = Modifier
                    .size(56.dp)
                    .tvFocusable(shape = RoundedCornerShape(20.dp))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.play_with_external_app),
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun GroupChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    countText: String? = null
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = countText ?: stringResource(R.string.channels_count, count),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PlayChoiceDialog(
    channel: Channel,
    onDismiss: () -> Unit,
    onPlayInternal: () -> Unit,
    onPlayExternal: () -> Unit,
    onSetDefaultMode: (DefaultPlayerMode, String?, String?, String?) -> Unit
) {
    var rememberChoice by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.play_choice_question),
                    style = MaterialTheme.typography.bodyMedium
                )

                Button(
                    onClick = {
                        if (rememberChoice) {
                            onSetDefaultMode(DefaultPlayerMode.INTERNAL, null, null, null)
                        }
                        onPlayInternal()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .tvFocusable(shape = RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.play_choice_internal), fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        if (rememberChoice) {
                            onSetDefaultMode(DefaultPlayerMode.EXTERNAL, null, null, null)
                        }
                        onPlayExternal()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .tvFocusable(shape = RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.play_choice_external), fontWeight = FontWeight.Bold)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { rememberChoice = !rememberChoice }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                    Text(stringResource(R.string.play_choice_remember), style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.tvFocusable()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private data class ExportFolder(val label: String, val path: String)

@Composable
private fun ExportDialog(
    onDismiss: () -> Unit,
    onConfirmSave: (String, String) -> Unit
) {
    val context = LocalContext.current
    var fileName by remember { mutableStateOf("playlist_editada") }
    var isFieldFocused by remember { mutableStateOf(false) }

    val folders = remember {
        buildList {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            add(ExportFolder(context.getString(R.string.folder_downloads, dl.absolutePath), dl.absolutePath))

            val root = Environment.getExternalStorageDirectory()
            add(ExportFolder(context.getString(R.string.folder_internal_storage, root.absolutePath), root.absolutePath))

            val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (movies.exists()) {
                add(ExportFolder(context.getString(R.string.folder_movies, movies.absolutePath), movies.absolutePath))
            }

            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            add(ExportFolder(context.getString(R.string.folder_documents, docs.absolutePath), docs.absolutePath))
        }
    }

    var selectedFolder by remember { mutableStateOf(folders.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.export_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.export_file_name_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.export_file_name_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFieldFocused = it.isFocused }
                        .border(
                            width = if (isFieldFocused) 3.dp else 0.dp,
                            color = if (isFieldFocused) TvFocusHighlightColor else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvFocusHighlightColor
                    )
                )

                HorizontalDivider()

                Text(
                    text = stringResource(R.string.export_destination_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                folders.forEach { folder ->
                    val isSelected = folder == selectedFolder
                    Surface(
                        onClick = { selectedFolder = folder },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(shape = RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = folder.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (fileName.isNotBlank()) {
                        val finalName = if (
                            fileName.endsWith(".m3u", ignoreCase = true) ||
                            fileName.endsWith(".m3u8", ignoreCase = true)
                        ) fileName else "$fileName.m3u"
                        onConfirmSave(finalName, selectedFolder.path)
                    }
                },
                modifier = Modifier.tvFocusable()
            ) {
                Text(stringResource(R.string.save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.tvFocusable()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DefaultPlayerDialog(
    currentMode: DefaultPlayerMode,
    preferredPackage: String?,
    onDismiss: () -> Unit,
    onSelectMode: (DefaultPlayerMode, String?, String?, String?) -> Unit
) {
    val context = LocalContext.current
    val installedPlayers = remember { getInstalledExternalPlayers(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.player_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Built-in player
                    item {
                        val isSelected = currentMode == DefaultPlayerMode.INTERNAL
                        PlayerOptionRow(
                            selected = isSelected,
                            title = stringResource(R.string.player_dialog_integrated_title),
                            description = stringResource(R.string.player_dialog_integrated_desc),
                            onClick = {
                                onSelectMode(DefaultPlayerMode.INTERNAL, null, null, null)
                                onDismiss()
                            }
                        )
                    }

                    // Ask every time
                    item {
                        val isSelected = currentMode == DefaultPlayerMode.ASK
                        PlayerOptionRow(
                            selected = isSelected,
                            title = stringResource(R.string.player_dialog_ask_title),
                            description = stringResource(R.string.player_dialog_ask_desc),
                            onClick = {
                                onSelectMode(DefaultPlayerMode.ASK, null, null, null)
                                onDismiss()
                            }
                        )
                    }

                    // External players section header
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = stringResource(R.string.player_dialog_external_section),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Generic system chooser
                    item {
                        val isSelected = currentMode == DefaultPlayerMode.EXTERNAL && preferredPackage == null
                        PlayerOptionRow(
                            selected = isSelected,
                            title = stringResource(R.string.player_dialog_chooser_title),
                            description = stringResource(R.string.player_dialog_chooser_desc),
                            onClick = {
                                onSelectMode(DefaultPlayerMode.EXTERNAL, null, null, null)
                                onDismiss()
                            }
                        )
                    }

                    if (installedPlayers.isEmpty()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.player_dialog_no_external),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    } else {
                        items(installedPlayers, key = { it.packageName }) { app ->
                            val isSelected = currentMode == DefaultPlayerMode.EXTERNAL && preferredPackage == app.packageName
                            Surface(
                                onClick = {
                                    onSelectMode(DefaultPlayerMode.EXTERNAL, app.packageName, app.activityName, app.appName)
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusable(shape = RoundedCornerShape(12.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.Tv,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.appName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.open_directly_in, app.appName, app.packageName),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.tvFocusable()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PlayerOptionRow(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveToGroupDialog(
    groups: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newGroupName by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.move_to_group),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text(stringResource(R.string.group_name)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        singleLine = true
                    )
                    if (groups.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            groups.forEach { grp ->
                                DropdownMenuItem(
                                    text = { Text(grp) },
                                    onClick = {
                                        newGroupName = grp
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (newGroupName.isNotBlank()) onConfirm(newGroupName) },
                enabled = newGroupName.isNotBlank(),
                modifier = Modifier.tvFocusable()
            ) {
                Text(stringResource(R.string.move))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.tvFocusable()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun GroupManageDialog(
    groups: List<String>,
    counts: Map<String, Int>,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var addDialogVisible by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.tv_manage_groups),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(groups, key = { it }) { group ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusable(shape = RoundedCornerShape(12.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = group,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = stringResource(R.string.channels_count, counts[group] ?: 0),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(
                                    onClick = { onRename(group) },
                                    modifier = Modifier.tvFocusable(shape = RoundedCornerShape(12.dp))
                                ) {
                                    Text(stringResource(R.string.rename_group), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                                TextButton(
                                    onClick = { deleteTarget = group },
                                    modifier = Modifier.tvFocusable(shape = RoundedCornerShape(12.dp))
                                ) {
                                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { addDialogVisible = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .tvFocusable(shape = RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.add_new_group), fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.tvFocusable()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )

    // Add group (inside manage dialog)
    if (addDialogVisible) {
        NameDialog(
            title = stringResource(R.string.add_new_group),
            initialValue = "",
            onDismiss = { addDialogVisible = false },
            onConfirm = { name ->
                onAdd(name)
                addDialogVisible = false
            }
        )
    }

    // Delete group confirmation
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = {
                Text(
                    text = stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.tv_delete_group_confirm, target),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(target)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.tvFocusable()
                ) {
                    Text(stringResource(R.string.delete), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    modifier = Modifier.tvFocusable()
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.tvFocusable()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.tvFocusable()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
