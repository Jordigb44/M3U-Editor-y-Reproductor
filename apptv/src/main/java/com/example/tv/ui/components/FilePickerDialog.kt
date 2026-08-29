package com.example.tv.ui.components

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Refresh
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FilePickerDialog(
    onDismiss: () -> Unit,
    onFileSelected: (Uri) -> Unit
) {
    val context = LocalContext.current
    val rootStorage = remember {
        try {
            Environment.getExternalStorageDirectory()
        } catch (_: Exception) {
            File("/sdcard")
        }
    }
    val initialDir = remember {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (dl.exists() && dl.isDirectory) dl else rootStorage
    }

    var currentDir by remember { mutableStateOf(initialDir) }
    var currentItems by remember { mutableStateOf<List<File>>(emptyList()) }
    var scannedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isScanMode by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var customPath by remember { mutableStateOf("") }
    var isPathFocused by remember { mutableStateOf(false) }

    // Load current directory items
    LaunchedEffect(currentDir, isScanMode) {
        if (!isScanMode) {
            currentItems = withContext(Dispatchers.IO) {
                listDirContents(currentDir)
            }
        }
    }

    // Trigger full storage scan if scan mode selected
    fun triggerScan() {
        isScanMode = true
        isScanning = true
        scannedFiles = emptyList()
    }

    LaunchedEffect(isScanMode) {
        if (isScanMode) {
            scannedFiles = withContext(Dispatchers.IO) {
                deepScanM3uFiles(context)
            }
            isScanning = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderSpecial,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isScanMode) stringResource(R.string.file_picker_title_scan) else stringResource(R.string.file_picker_title_browse),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Navigation Mode Buttons (Navegar Carpetas vs Escanear Todo)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { isScanMode = false },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .tvFocusable(shape = RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isScanMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (!isScanMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(stringResource(R.string.file_picker_folders), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { triggerScan() },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .tvFocusable(shape = RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isScanMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isScanMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.file_picker_scan_all), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }

                if (!isScanMode) {
                    // Current Path Bar
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.file_picker_current_path, currentDir.absolutePath),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Option to go up one folder level if parent exists
                        if (currentDir.parentFile != null && currentDir.absolutePath != rootStorage.parentFile?.absolutePath) {
                            item {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusable(shape = RoundedCornerShape(10.dp))
                                        .clickable {
                                            currentDir.parentFile?.let { currentDir = it }
                                        },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = stringResource(R.string.file_picker_go_up),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                        }

                        if (currentItems.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.file_picker_empty_folder),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        } else {
                            items(currentItems, key = { it.absolutePath }) { file ->
                                val isDir = file.isDirectory
                                val isM3u = isM3uFile(file)

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusable(shape = RoundedCornerShape(10.dp))
                                        .clickable {
                                            if (isDir) {
                                                currentDir = file
                                            } else if (isM3u) {
                                                onFileSelected(Uri.fromFile(file))
                                            }
                                        },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isM3u) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isDir) Icons.Filled.Folder else Icons.Filled.Description,
                                            contentDescription = null,
                                            tint = if (isM3u) MaterialTheme.colorScheme.primary else if (isDir) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = file.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isM3u) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isM3u) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!isDir) {
                                                Text(
                                                    text = stringResource(R.string.file_picker_file_size_kb, file.length() / 1024),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Deep Scan Mode
                    if (isScanning) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(stringResource(R.string.file_picker_scanning), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else if (scannedFiles.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.file_picker_no_m3u_found),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(scannedFiles, key = { it.absolutePath }) { file ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusable(shape = RoundedCornerShape(10.dp))
                                        .clickable {
                                            onFileSelected(Uri.fromFile(file))
                                        },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = file.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = file.parent ?: file.absolutePath,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
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

                if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable(shape = RoundedCornerShape(10.dp))
                            .clickable {
                                try {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    context.startActivity(intent)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.file_picker_grant_all_files),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Direct Path Input at bottom
                OutlinedTextField(
                    value = customPath,
                    onValueChange = { customPath = it },
                    placeholder = { Text(stringResource(R.string.file_picker_paste_path)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isPathFocused = it.isFocused }
                        .border(
                            width = if (isPathFocused) 3.dp else 0.dp,
                            color = if (isPathFocused) TvFocusHighlightColor else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        ),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvFocusHighlightColor
                    )
                )
            }
        },
        confirmButton = {
            if (customPath.isNotBlank()) {
                Button(
                    onClick = {
                        val file = File(customPath.trim())
                        if (file.exists()) {
                            onFileSelected(Uri.fromFile(file))
                        }
                    },
                    modifier = Modifier.tvFocusable()
                ) {
                    Text(stringResource(R.string.file_picker_load))
                }
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

private fun isM3uFile(file: File): Boolean {
    if (file.isDirectory || file.name.startsWith(".")) return false
    val name = file.name.lowercase()
    if (name.endsWith(".m3u") || name.endsWith(".m3u8") || name.endsWith(".txt") ||
        name.contains(".m3u") || name.contains(".m3u8")) {
        return true
    }
    val nonTextExts = setOf("apk", "png", "jpg", "jpeg", "mp4", "mkv", "avi", "zip", "rar", "pdf")
    val ext = name.substringAfterLast('.', "")
    if (ext !in nonTextExts && file.length() in 10..25_000_000) {
        try {
            file.useLines { lines ->
                val first = lines.firstOrNull()?.trim() ?: ""
                return first.startsWith("#EXTM3U", ignoreCase = true) || first.startsWith("#EXTINF", ignoreCase = true)
            }
        } catch (_: Exception) {}
    }
    return false
}

private fun listDirContents(dir: File): List<File> {
    if (!dir.exists() || !dir.isDirectory) return emptyList()
    return try {
        val list = dir.listFiles() ?: return emptyList()
        list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    } catch (_: Exception) {
        emptyList()
    }
}

private fun deepScanM3uFiles(context: Context): List<File> {
    val results = mutableListOf<File>()
    val rootsToScan = mutableSetOf<File>()

    try {
        rootsToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
    } catch (_: Exception) {}
    try {
        rootsToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
    } catch (_: Exception) {}
    try {
        rootsToScan.add(Environment.getExternalStorageDirectory())
    } catch (_: Exception) {}
    try {
        context.getExternalFilesDir(null)?.let { rootsToScan.add(it) }
    } catch (_: Exception) {}

    fun scanRecursively(currentDir: File, depth: Int) {
        if (depth > 3 || !currentDir.exists() || !currentDir.isDirectory) return
        try {
            currentDir.listFiles()?.forEach { file ->
                if (file.isDirectory && !file.name.startsWith(".")) {
                    scanRecursively(file, depth + 1)
                } else if (file.isFile && isM3uFile(file)) {
                    if (!results.any { it.absolutePath == file.absolutePath }) {
                        results.add(file)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    rootsToScan.forEach { root ->
        scanRecursively(root, 0)
    }

    return results
}
