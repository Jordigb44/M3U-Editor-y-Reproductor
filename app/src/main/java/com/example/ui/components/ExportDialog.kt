package com.example.ui.components

import android.os.Environment
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import java.io.File

data class ExportFolder(val label: String, val path: String)

@Composable
fun ExportDialog(
    onDismiss: () -> Unit,
    onConfirmSave: (String, String) -> Unit  // filename, folderPath
) {
    val context = LocalContext.current
    var fileName by remember { mutableStateOf("playlist_editada") }
    var isFieldFocused by remember { mutableStateOf(false) }

    val folders = remember {
        buildList {
            // Fire TV primary download path
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            add(ExportFolder(context.getString(R.string.folder_downloads, dl.absolutePath), dl.absolutePath))

            // Root of internal storage
            val root = Environment.getExternalStorageDirectory()
            add(ExportFolder(context.getString(R.string.folder_internal_storage, root.absolutePath), root.absolutePath))

            // Movies folder
            val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (movies.exists()) add(ExportFolder(context.getString(R.string.folder_movies, movies.absolutePath), movies.absolutePath))

            // Documents folder (may not exist on Fire TV but fallback)
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .dpadFocusable(shape = RoundedCornerShape(10.dp)),
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        onClick = { selectedFolder = folder }
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
                modifier = Modifier.dpadFocusable()
            ) {
                Text(stringResource(R.string.save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.dpadFocusable()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
