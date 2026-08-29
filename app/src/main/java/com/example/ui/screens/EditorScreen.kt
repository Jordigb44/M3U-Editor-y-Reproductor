package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.Channel
import com.example.ui.DefaultPlayerMode
import com.example.ui.EditorState
import com.example.ui.EditorViewModel
import com.example.ui.PlayerSession
import com.example.ui.components.DefaultPlayerDialog
import com.example.ui.components.ExportDialog
import com.example.ui.components.PlayChoiceDialog
import com.example.ui.components.TvFocusHighlightColor
import com.example.ui.components.dpadFocusable

/** Advanced search: matches channel name, group or stream URL (case-insensitive). */
private fun Channel.matchesSearch(query: String): Boolean =
    query.isBlank() ||
        name.contains(query, ignoreCase = true) ||
        groupTitle.contains(query, ignoreCase = true) ||
        url.contains(query, ignoreCase = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorState,
    viewModel: EditorViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var selectedTab by remember { mutableStateOf(0) }
    var playerSession by remember { mutableStateOf<PlayerSession?>(null) }

    BackHandler {
        if (playerSession != null) {
            playerSession = null
        } else if (state.selectedChannelIds.isNotEmpty()) {
            viewModel.clearSelection()
        } else if (state.selectedGroups.isNotEmpty()) {
            viewModel.clearGroupSelection()
        } else {
            onBack()
        }
    }
    
    var exportSuccessMessage by remember { mutableStateOf<String?>(null) }
    var exportErrorMessage by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showPlayerSettingsDialog by remember { mutableStateOf(false) }

    // Request WRITE_EXTERNAL_STORAGE at runtime (needed on Android 9 / Fire TV SDK 28)
    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showExportDialog = true
        else exportErrorMessage = context.getString(R.string.editor_write_permission_denied)
    }

    fun performExport() {
        // Android 10+ uses scoped storage, no runtime permission needed for MediaStore
        // Android 9 and below (Fire TV SDK 28) needs WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        showExportDialog = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        if (state.selectedChannelIds.isNotEmpty() && selectedTab == 0) {
                            Text(stringResource(R.string.selected_count, state.selectedChannelIds.size))
                        } else if (state.selectedGroups.isNotEmpty() && selectedTab == 1) {
                            Text(stringResource(R.string.selected_count, state.selectedGroups.size))
                        } else {
                            Text(if (state.activePlaylistName.isNotBlank()) stringResource(R.string.editor_with_name, state.activePlaylistName) else stringResource(R.string.editor)) 
                        }
                    },
                    navigationIcon = {
                        if ((state.selectedChannelIds.isNotEmpty() && selectedTab == 0) || (state.selectedGroups.isNotEmpty() && selectedTab == 1)) {
                            IconButton(onClick = { 
                                if (selectedTab == 0) viewModel.clearSelection() else viewModel.clearGroupSelection()
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_selection))
                            }
                        } else {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        }
                    },
                    actions = {
                        if (selectedTab == 0) {
                            val filteredChannels = state.channels.filter {
                                (state.selectedGroup == null || it.groupTitle == state.selectedGroup) &&
                                it.matchesSearch(state.searchQuery)
                            }
                            val allSelected = filteredChannels.isNotEmpty() && filteredChannels.all { state.selectedChannelIds.contains(it.id) }
                            
                            IconButton(onClick = { viewModel.toggleSelectAllChannels(filteredChannels.map { it.id }) }) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                                    contentDescription = if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all)
                                )
                            }
                            
                            if (state.selectedChannelIds.isNotEmpty()) {
                                var showMoveDialog by remember { mutableStateOf(false) }
                                IconButton(onClick = { showMoveDialog = true }) {
                                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.move))
                                }
                                IconButton(onClick = { viewModel.deleteSelectedChannels(context) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                                }
                                
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
                            } else {
                                IconButton(
                                    onClick = { showPlayerSettingsDialog = true },
                                    modifier = Modifier.dpadFocusable(shape = RoundedCornerShape(24.dp))
                                ) {
                                    Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.editor_default_player))
                                }
                                IconButton(
                                    onClick = { performExport() },
                                    modifier = Modifier.dpadFocusable(shape = RoundedCornerShape(24.dp))
                                ) {
                                    Icon(Icons.Filled.SaveAlt, contentDescription = stringResource(R.string.export))
                                }
                            }
                        } else {
                            val allSelected = state.groups.isNotEmpty() && state.groups.all { state.selectedGroups.contains(it) }
                            
                            IconButton(
                                onClick = { viewModel.toggleSelectAllGroups(state.groups) },
                                modifier = Modifier.dpadFocusable(shape = RoundedCornerShape(24.dp))
                            ) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                                    contentDescription = if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all)
                                )
                            }
                            
                            if (state.selectedGroups.isNotEmpty()) {
                                IconButton(
                                    onClick = { viewModel.deleteSelectedGroups(context) },
                                    modifier = Modifier.dpadFocusable(shape = RoundedCornerShape(24.dp))
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_groups))
                                }
                            } else {
                                IconButton(
                                    onClick = { performExport() },
                                    modifier = Modifier.dpadFocusable(shape = RoundedCornerShape(24.dp))
                                ) {
                                    Icon(Icons.Filled.SaveAlt, contentDescription = stringResource(R.string.export))
                                }
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.channels)) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.groups)) })
                }
                if (selectedTab == 0) {
                    ChannelsTab(
                        state,
                        viewModel,
                        onPlayChannel = { list, index ->
                            playerSession = PlayerSession(list, index, viewModel.activeEpgUrl())
                        }
                    )
                } else {
                    GroupsTab(state, viewModel)
                }
            }
        }

        val session = playerSession
        if (session != null && session.channels.isNotEmpty()) {
            PlayerScreen(
                channels = session.channels,
                startIndex = session.index.coerceIn(0, session.channels.lastIndex),
                onBack = { playerSession = null }
            )
        }

        if (showExportDialog) {
            ExportDialog(
                onDismiss = { showExportDialog = false },
                onConfirmSave = { name, folderPath ->
                    showExportDialog = false
                    viewModel.saveExportFile(
                        context = context,
                        fileName = name,
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

        if (exportSuccessMessage != null) {
            AlertDialog(
                onDismissRequest = { exportSuccessMessage = null },
                title = { Text(stringResource(R.string.export), fontWeight = FontWeight.Bold) },
                text = { Text(exportSuccessMessage!!) },
                confirmButton = {
                    Button(
                        onClick = { exportSuccessMessage = null },
                        modifier = Modifier.dpadFocusable()
                    ) {
                        Text(stringResource(R.string.accept))
                    }
                }
            )
        }

        if (exportErrorMessage != null) {
            AlertDialog(
                onDismissRequest = { exportErrorMessage = null },
                title = { Text(stringResource(R.string.export_error_title), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) },
                text = { Text(exportErrorMessage!!) },
                confirmButton = {
                    Button(
                        onClick = { exportErrorMessage = null },
                        modifier = Modifier.dpadFocusable()
                    ) {
                        Text(stringResource(R.string.accept))
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChannelsTab(
    state: EditorState,
    viewModel: EditorViewModel,
    onPlayChannel: (List<Channel>, Int) -> Unit
) {
    val context = LocalContext.current
    var showGroupFilterDialog by remember { mutableStateOf(false) }
    var pendingChoiceChannel by remember { mutableStateOf<Channel?>(null) }
    val defaultGroupText = stringResource(R.string.all_groups)
    val currentGroupText = state.selectedGroup ?: defaultGroupText

    val filteredChannels = state.channels.filter {
        (state.selectedGroup == null || it.groupTitle == state.selectedGroup) &&
            it.matchesSearch(state.searchQuery)
    }

    fun handleChannelClick(channel: Channel) {
        when (state.defaultPlayerMode) {
            DefaultPlayerMode.INTERNAL -> {
                val index = filteredChannels.indexOfFirst { it.id == channel.id }
                if (index >= 0) onPlayChannel(filteredChannels, index)
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

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text(stringResource(R.string.search_placeholder), style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                ),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_placeholder), modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            
            Surface(
                onClick = { showGroupFilterDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .dpadFocusable(shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                color = if (state.selectedGroup != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (state.selectedGroup != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = currentGroupText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (state.selectedGroup != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = if (state.selectedGroup != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (showGroupFilterDialog) {
            GroupFilterDialog(
                groups = state.groups,
                selectedGroup = state.selectedGroup,
                totalChannelsCount = state.channels.size,
                channelCountsByGroup = remember(state.channels) {
                    state.channels.groupingBy { it.groupTitle.ifBlank { context.getString(R.string.ungrouped) } }.eachCount()
                },
                onSelectGroup = { viewModel.selectGroup(it) },
                onDismiss = { showGroupFilterDialog = false }
            )
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val allSelected = filteredChannels.isNotEmpty() && filteredChannels.all { state.selectedChannelIds.contains(it.id) }
            Text(
                text = stringResource(R.string.channels_count, filteredChannels.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilterChip(
                selected = allSelected,
                onClick = { viewModel.toggleSelectAllChannels(filteredChannels.map { it.id }) },
                label = { Text(if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all)) },
                leadingIcon = {
                    Icon(
                        imageVector = if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 88.dp, top = 8.dp)
        ) {
            items(filteredChannels, key = { it.id }) { channel ->
                val isSelected = state.selectedChannelIds.contains(channel.id)
                ListItem(
                    headlineContent = { Text(channel.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
                    supportingContent = { Text(channel.groupTitle, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .dpadFocusable(shape = RoundedCornerShape(16.dp))
                        .combinedClickable(
                            onClick = { 
                                if (state.selectedChannelIds.isNotEmpty()) {
                                    viewModel.toggleChannelSelection(channel.id)
                                } else {
                                    handleChannelClick(channel)
                                }
                            },
                            onLongClick = { viewModel.toggleChannelSelection(channel.id) }
                        )
                        .background(
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    trailingContent = {
                        if (isSelected) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.selected), tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = { launchExternalPlayer(context, channel.url, channel.name) },
                                    modifier = Modifier.dpadFocusable(shape = RoundedCornerShape(20.dp))
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = stringResource(R.string.play_with_external_app),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { handleChannelClick(channel) },
                                    modifier = Modifier.dpadFocusable(shape = RoundedCornerShape(20.dp))
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = stringResource(R.string.play),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }

        val choiceChannel = pendingChoiceChannel
        if (choiceChannel != null) {
            PlayChoiceDialog(
                channel = choiceChannel,
                onDismiss = { pendingChoiceChannel = null },
                onPlayInternal = {
                    val index = filteredChannels.indexOfFirst { it.id == choiceChannel.id }
                    if (index >= 0) onPlayChannel(filteredChannels, index)
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
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GroupsTab(state: EditorState, viewModel: EditorViewModel) {
    val context = LocalContext.current
    var showAddGroup by remember { mutableStateOf(false) }
    var showRenameGroup by remember { mutableStateOf<String?>(null) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val allSelected = state.groups.isNotEmpty() && state.groups.all { state.selectedGroups.contains(it) }
            Text(
                text = stringResource(R.string.groups_count, state.groups.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilterChip(
                selected = allSelected,
                onClick = { viewModel.toggleSelectAllGroups(state.groups) },
                label = { Text(if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all)) },
                leadingIcon = {
                    Icon(
                        imageVector = if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 88.dp, top = 8.dp)
            ) {
                items(state.groups, key = { it }) { group ->
                    val isSelected = state.selectedGroups.contains(group)
                    val channelCount = state.channels.count { it.groupTitle == group }
                    
                    ListItem(
                        headlineContent = { Text(group, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(stringResource(R.string.channels_count, channelCount), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .dpadFocusable(shape = RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = { 
                                    if (state.selectedGroups.isNotEmpty()) {
                                        viewModel.toggleGroupSelection(group)
                                    } else {
                                        showRenameGroup = group
                                    }
                                },
                                onLongClick = { viewModel.toggleGroupSelection(group) }
                            )
                            .background(
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        trailingContent = {
                            if (isSelected) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.selected), tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Text(stringResource(R.string.edit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }
            }
            
            FloatingActionButton(
                onClick = { showAddGroup = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_group))
            }
        }
    }
    
    if (showAddGroup) {
        NameDialog(
            title = stringResource(R.string.add_new_group),
            initialValue = "",
            onDismiss = { showAddGroup = false },
            onConfirm = { 
                viewModel.addNewGroup(it, context)
                showAddGroup = false
            }
        )
    }
    
    if (showRenameGroup != null) {
        NameDialog(
            title = stringResource(R.string.rename_group),
            initialValue = showRenameGroup!!,
            onDismiss = { showRenameGroup = null },
            onConfirm = { 
                viewModel.renameGroup(showRenameGroup!!, it, context)
                showRenameGroup = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToGroupDialog(
    groups: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newGroupName by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_group)) },
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
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
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
            TextButton(
                onClick = { if (newGroupName.isNotBlank()) onConfirm(newGroupName) },
                enabled = newGroupName.isNotBlank()
            ) {
                Text(stringResource(R.string.move))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun NameDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialValue) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && name != initialValue) onConfirm(name) else onDismiss() }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun GroupFilterDialog(
    groups: List<String>,
    selectedGroup: String?,
    totalChannelsCount: Int,
    channelCountsByGroup: Map<String, Int>,
    onSelectGroup: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredGroups = remember(groups, searchQuery) {
        if (searchQuery.isBlank()) groups
        else groups.filter { it.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.filter_by_group),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.groups_count, groups.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (groups.size > 5) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.search_group_placeholder), style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvFocusHighlightColor
                        )
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (searchQuery.isBlank()) {
                        item {
                            val isSelected = selectedGroup == null
                            Surface(
                                onClick = {
                                    onSelectGroup(null)
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .dpadFocusable(shape = RoundedCornerShape(12.dp)),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
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
                                            text = stringResource(R.string.all_groups),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.channels_count, totalChannelsCount),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    items(filteredGroups, key = { it }) { group ->
                        val isSelected = selectedGroup == group
                        val count = channelCountsByGroup[group] ?: 0
                        Surface(
                            onClick = {
                                onSelectGroup(group)
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .dpadFocusable(shape = RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
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
                                        text = group,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.channels_count, count),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
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
                modifier = Modifier.dpadFocusable()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
