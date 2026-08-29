package com.example.tv.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jordiguixbetancor.m3ueditor.tv.BuildConfig
import com.jordiguixbetancor.m3ueditor.tv.R
import com.example.data.SavedPlaylist
import com.example.tv.ui.components.FilePickerDialog
import com.example.tv.ui.components.TvFocusHighlightColor
import com.example.tv.ui.components.tvFocusable
import com.example.ui.AppUpdater
import com.example.ui.EditorState
import com.example.ui.UpdateInfo
import kotlinx.coroutines.launch

private const val UPDATE_REPO = "Jordigb44/M3U-Editor-y-Reproductor"

/**
 * Tries to launch a real 3rd-party file manager first (ACTION_GET_CONTENT / OPEN_DOCUMENT / PICK).
 * On Xiaomi TV OS and Fire OS, system stubs (android, com.android.*, com.google.android.*)
 * cause infinite ResolverActivity window focus freezes, so they are filtered out.
 * Falls back to the built-in FilePickerDialog when no external manager is available.
 */
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

    onFallbackInternal()
}

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
    val clipboardManager = LocalClipboardManager.current

    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var showFilePickerDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<SavedPlaylist?>(null) }
    var playlistToDelete by remember { mutableStateOf<SavedPlaylist?>(null) }

    // ---------- In-app update check ----------
    val updateScope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var showUnknownSourcesInfo by remember { mutableStateOf(false) }

    suspend fun fetchUpdate(): UpdateInfo? = AppUpdater.checkLatest(
        repo = UPDATE_REPO,
        assetMatches = { it.contains("tv", ignoreCase = true) },
        currentVersion = BuildConfig.VERSION_NAME
    )

    // Silent check every time the home screen is shown.
    LaunchedEffect(Unit) {
        checkingUpdate = true
        updateInfo = fetchUpdate()
        checkingUpdate = false
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        showFilePickerDialog = true
    }

    val externalFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { onLoadFile(it) }
        }
    }

    fun openInternalPickerWithPermission() {
        // Android <= 9 (Fire TV SDK 28) needs READ_EXTERNAL_STORAGE before browsing files.
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

    Surface(
        modifier = Modifier
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---------- Header ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 36.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.tv_home_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            // ---------- Big action buttons ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { showUrlDialog = true },
                    enabled = !state.isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp)
                        .tvFocusable(shape = RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.load_url), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }

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
                        .height(72.dp)
                        .tvFocusable(shape = RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.tv_load_from_file), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { onLoadUrl("https://iptv-org.github.io/iptv/index.m3u") },
                    enabled = !state.isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp)
                        .tvFocusable(shape = RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                ) {
                    Icon(Icons.Filled.Tv, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.tv_load_demo), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }

            // ---------- Check for updates ----------
            Button(
                onClick = {
                    updateScope.launch {
                        checkingUpdate = true
                        val info = fetchUpdate()
                        checkingUpdate = false
                        if (info != null) {
                            updateInfo = info
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                enabled = !checkingUpdate && !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .height(56.dp)
                    .tvFocusable(shape = RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.update_check), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }

            // ---------- My playlists ----------
            Text(
                text = stringResource(R.string.home_my_playlists_count, state.playlists.size),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, top = 28.dp, bottom = 4.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(state.playlists, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        isActive = playlist.id == state.activePlaylistId,
                        onSelect = { onSelectPlaylist(playlist.id) },
                        onRename = { playlistToRename = playlist },
                        onDelete = { playlistToDelete = playlist }
                    )
                }
            }
        }

        // ---------- URL dialog ----------
        if (showUrlDialog) {
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = {
                    Text(
                        text = stringResource(R.string.load_url),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            placeholder = { Text(stringResource(R.string.playlist_url_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TvFocusHighlightColor
                            )
                        )
                        Button(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) urlInput = clip
                            },
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
                            Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.paste), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.paste), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (urlInput.isNotBlank()) {
                                showUrlDialog = false
                                onLoadUrl(urlInput.trim())
                            }
                        },
                        enabled = urlInput.isNotBlank() && !state.isLoading,
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.load_url), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showUrlDialog = false },
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // ---------- Internal file picker ----------
        if (showFilePickerDialog) {
            FilePickerDialog(
                onDismiss = { showFilePickerDialog = false },
                onFileSelected = { uri ->
                    showFilePickerDialog = false
                    onLoadFile(uri)
                }
            )
        }

        // ---------- Rename playlist dialog ----------
        playlistToRename?.let { playlist ->
            var newName by remember(playlist.id) { mutableStateOf(playlist.name) }
            AlertDialog(
                onDismissRequest = { playlistToRename = null },
                title = {
                    Text(
                        text = stringResource(R.string.home_rename_playlist),
                        fontWeight = FontWeight.Bold
                    )
                },
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
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.save))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { playlistToRename = null },
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // ---------- Delete playlist confirmation ----------
        playlistToDelete?.let { playlist ->
            AlertDialog(
                onDismissRequest = { playlistToDelete = null },
                title = {
                    Text(
                        text = stringResource(R.string.home_delete_playlist),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.home_delete_playlist_confirm, playlist.name),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onDeletePlaylist(playlist.id)
                            playlistToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { playlistToDelete = null },
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // ---------- Error dialog ----------
        val currentError = state.error
        if (currentError != null) {
            AlertDialog(
                onDismissRequest = onErrorDismiss,
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
                        onClick = onErrorDismiss,
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.accept))
                    }
                }
            )
        }

        // ---------- Update available dialog ----------
        updateInfo?.let { info ->
            AlertDialog(
                onDismissRequest = { updateInfo = null },
                title = {
                    Text(
                        text = stringResource(R.string.update_available_title, info.latestVersion),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.update_available_body, BuildConfig.VERSION_NAME, info.releaseNotes ?: ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val url = info.apkUrl
                            updateInfo = null
                            if (url == null) {
                                Toast.makeText(context, context.getString(R.string.update_install_error), Toast.LENGTH_LONG).show()
                            } else if (!AppUpdater.canInstall(context)) {
                                showUnknownSourcesInfo = true
                            } else {
                                updateScope.launch {
                                    downloadingUpdate = true
                                    downloadProgress = 0f
                                    val apk = AppUpdater.downloadApk(context, url, "update-${info.latestVersion}.apk") { p ->
                                        downloadProgress = p
                                    }
                                    downloadingUpdate = false
                                    if (apk == null || !AppUpdater.promptInstall(context, apk)) {
                                        Toast.makeText(context, context.getString(R.string.update_install_error), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.update_download_install), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { updateInfo = null },
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.update_not_now))
                    }
                }
            )
        }

        // ---------- Unknown sources permission dialog ----------
        if (showUnknownSourcesInfo) {
            AlertDialog(
                onDismissRequest = { showUnknownSourcesInfo = false },
                title = {
                    Text(stringResource(R.string.update_check), fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(stringResource(R.string.update_need_unknown_sources), style = MaterialTheme.typography.bodyMedium)
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showUnknownSourcesInfo = false
                            AppUpdater.openInstallPermissionSettings(context)
                        },
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.update_open_settings), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showUnknownSourcesInfo = false },
                        modifier = Modifier.tvFocusable()
                    ) {
                        Text(stringResource(R.string.update_not_now))
                    }
                }
            )
        }

        // ---------- Download progress dialog ----------
        if (downloadingUpdate) {
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Text(stringResource(R.string.update_check), fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.update_downloading, (downloadProgress * 100).toInt()),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {}
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
    }
}

@Composable
private fun PlaylistCard(
    playlist: SavedPlaylist,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
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
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.channels_count, playlist.channelCount),
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onRename,
                    modifier = Modifier
                        .size(56.dp)
                        .tvFocusable(shape = RoundedCornerShape(20.dp))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.home_rename_playlist),
                        tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(56.dp)
                        .tvFocusable(shape = RoundedCornerShape(20.dp))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.home_delete_playlist),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
