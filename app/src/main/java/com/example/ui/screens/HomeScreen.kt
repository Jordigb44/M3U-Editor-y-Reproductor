package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FeaturedPlayList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.SavedPlaylist
import com.example.ui.EditorState
import com.example.ui.components.FilePickerDialog
import com.example.ui.components.dpadFocusable

private fun openExternalOrInternalFilePicker(
    context: Context,
    externalLauncher: ActivityResultLauncher<Intent>,
    onFallbackInternal: () -> Unit
) {
    val pm = context.packageManager

    val candidateIntents = listOf(
        Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        },
        Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" },
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        },
        Intent(Intent.ACTION_PICK).apply { type = "*/*" }
    )

    for (intent in candidateIntents) {
        val resolves = pm.queryIntentActivities(intent, 0).filter {
            it.activityInfo != null &&
            it.activityInfo.packageName != context.packageName
        }

        // On Xiaomi TV OS and Fire OS, system stubs (android, com.android.*, com.google.android.*)
        // cause infinite ResolverActivity window focus freezes.
        // We filter for real 3rd party file managers (File Manager Plus, X-Plore, Total Commander, etc.)
        val realFileManagers = resolves.filter { ri ->
            val pkg = ri.activityInfo.packageName
            pkg != "android" &&
            !pkg.startsWith("com.android.") &&
            !pkg.startsWith("com.google.android.")
        }

        if (realFileManagers.isNotEmpty()) {
            try {
                val app = realFileManagers.first()
                val explicitIntent = Intent(intent).apply {
                    setPackage(app.activityInfo.packageName)
                }
                externalLauncher.launch(explicitIntent)
                return
            } catch (_: Throwable) {
                continue
            }
        }
    }

    // No 3rd party file manager installed — fall back to built-in explorer
    onFallbackInternal()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: EditorState,
    onSelectPlaylist: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onLoadFile: (Uri) -> Unit,
    onLoadUrl: (String) -> Unit,
    onErrorDismiss: () -> Unit
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var isUrlFocused by remember { mutableStateOf(false) }
    var showFilePickerDialog by remember { mutableStateOf(false) }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        showFilePickerDialog = true
    }

    fun openInternalPickerWithPermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                return
            }
        }
        showFilePickerDialog = true
    }
    
    var playlistToRename by remember { mutableStateOf<SavedPlaylist?>(null) }
    var playlistToDelete by remember { mutableStateOf<SavedPlaylist?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val externalFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            uri?.let { onLoadFile(it) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
            ) {
                // Header Logo
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tv,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // MIS LISTAS GUARDADAS SECTION
                if (state.playlists.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.home_my_playlists_count, state.playlists.size),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    items(state.playlists, key = { it.id }) { playlist ->
                        val isActive = playlist.id == state.activePlaylistId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .dpadFocusable(shape = RoundedCornerShape(16.dp))
                                .clickable { onSelectPlaylist(playlist.id) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isActive) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.FeaturedPlayList,
                                    contentDescription = null,
                                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = playlist.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isActive) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.home_in_use),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = stringResource(R.string.channels_count, playlist.channelCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(
                                    onClick = { playlistToRename = playlist },
                                    modifier = Modifier.dpadFocusable(shape = RoundedCornerShape(20.dp))
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.home_rename_playlist), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                IconButton(
                                    onClick = { playlistToDelete = playlist },
                                    modifier = Modifier.dpadFocusable(shape = RoundedCornerShape(20.dp))
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.home_delete_playlist), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }

                // AÑADIR NUEVA LISTA SECTION
                item {
                    Text(
                        text = stringResource(R.string.home_add_new_m3u_list),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { 
                                openExternalOrInternalFilePicker(
                                    context = context,
                                    externalLauncher = externalFileLauncher,
                                    onFallbackInternal = { openInternalPickerWithPermission() }
                                )
                            },
                            enabled = !state.isLoading,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .dpadFocusable(shape = RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.home_open_with_external_app), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { openInternalPickerWithPermission() },
                            enabled = !state.isLoading,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .dpadFocusable(shape = RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                        ) {
                            Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.home_internal_explorer), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.or),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
                    )
                }

                item {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        enabled = !state.isLoading,
                        placeholder = { Text(stringResource(R.string.playlist_url_placeholder)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isUrlFocused = it.isFocused }
                            .border(
                                width = if (isUrlFocused) 4.dp else 0.dp,
                                color = if (isUrlFocused) com.example.ui.components.TvFocusHighlightColor else Color.Transparent,
                                shape = RoundedCornerShape(16.dp)
                            ),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (url.isNotBlank()) onLoadUrl(url) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedBorderColor = com.example.ui.components.TvFocusHighlightColor
                        )
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { if (url.isNotBlank()) onLoadUrl(url) },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .dpadFocusable(shape = RoundedCornerShape(16.dp)),
                            enabled = url.isNotBlank() && !state.isLoading,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                        ) {
                            Text(stringResource(R.string.load_url), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    url = clip
                                }
                            },
                            enabled = !state.isLoading,
                            modifier = Modifier
                                .height(50.dp)
                                .dpadFocusable(shape = RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                        ) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.paste), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.paste), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                item {
                    Button(
                        onClick = {
                            val demoUrl = "https://iptv-org.github.io/iptv/index.m3u"
                            url = demoUrl
                            onLoadUrl(demoUrl)
                        },
                        enabled = !state.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .dpadFocusable(shape = RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
                    ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.home_load_demo_playlist), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Full Screen Loading Overlay
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
                        modifier = Modifier.size(56.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp
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

        // Descriptive Error Dialog
        val currentError = state.error
        if (currentError != null) {
            AlertDialog(
                onDismissRequest = onErrorDismiss,
                title = {
                    Text(
                        text = stringResource(R.string.error_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                text = {
                    Text(
                        text = currentError,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = onErrorDismiss,
                        modifier = Modifier.dpadFocusable()
                    ) {
                        Text(stringResource(R.string.accept))
                    }
                }
            )
        }

        // Internal File Picker Dialog
        if (showFilePickerDialog) {
            FilePickerDialog(
                onDismiss = { showFilePickerDialog = false },
                onFileSelected = { uri ->
                    showFilePickerDialog = false
                    onLoadFile(uri)
                }
            )
        }

        // Rename Playlist Dialog
        playlistToRename?.let { playlist ->
            var newName by remember { mutableStateOf(playlist.name) }
            AlertDialog(
                onDismissRequest = { playlistToRename = null },
                title = { Text(stringResource(R.string.home_rename_playlist), fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.home_playlist_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newName.isNotBlank()) {
                                onRenamePlaylist(playlist.id, newName.trim())
                                playlistToRename = null
                            }
                        },
                        modifier = Modifier.dpadFocusable()
                    ) {
                        Text(stringResource(R.string.save))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { playlistToRename = null },
                        modifier = Modifier.dpadFocusable()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // Delete Playlist Confirmation Dialog
        playlistToDelete?.let { playlist ->
            AlertDialog(
                onDismissRequest = { playlistToDelete = null },
                title = { Text(stringResource(R.string.home_delete_playlist), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.home_delete_playlist_confirm, playlist.name)) },
                confirmButton = {
                    Button(
                        onClick = {
                            onDeletePlaylist(playlist.id)
                            playlistToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.dpadFocusable()
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { playlistToDelete = null },
                        modifier = Modifier.dpadFocusable()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}
