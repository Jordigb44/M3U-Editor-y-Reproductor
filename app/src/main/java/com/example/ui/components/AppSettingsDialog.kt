package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.example.ui.AppViewMode
import com.example.ui.DefaultPlayerMode
import com.example.ui.EditorState
import com.example.ui.EditorViewModel
import com.jordiguixbetancor.m3ueditor.R

@Composable
fun AppSettingsDialog(
    state: EditorState,
    viewModel: EditorViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var showChangePinDialog by remember { mutableStateOf(false) }
    var showPlayerPicker by remember { mutableStateOf(false) }

    var parentalEnabled by remember(state.parentalControlEnabled) { mutableStateOf(state.parentalControlEnabled) }
    var lockMode by remember(state.lockModeSwitch) { mutableStateOf(state.lockModeSwitch) }

    // Touch/interaction refreshes the 10-second auto-lock timer
    LaunchedEffect(Unit) {
        viewModel.onParentalActivity()
    }

    if (showPlayerPicker) {
        DefaultPlayerDialog(
            currentMode = state.defaultPlayerMode,
            preferredPackage = state.preferredExternalPackage,
            onDismiss = { showPlayerPicker = false },
            onSelectMode = { mode, pkg, act, name ->
                viewModel.setDefaultPlayerMode(context, mode, pkg, act, name)
                viewModel.onParentalActivity()
            }
        )
    }

    if (showChangePinDialog) {
        ChangePinDialog(
            onSavePin = { newPin ->
                viewModel.setParentalControl(context, enabled = true, pin = newPin, lockModeSwitch = lockMode)
                showChangePinDialog = false
                viewModel.onParentalActivity()
            },
            onDismiss = { showChangePinDialog = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = stringResource(R.string.settings),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Notice about 10s auto-lock
                if (state.parentalControlEnabled) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.parental_auto_lock_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Section 1: View Mode Selection
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.mode_dialog_title).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isSimplified = (state.appViewMode == AppViewMode.SIMPLIFIED)
                            
                            Button(
                                onClick = {
                                    viewModel.setAppViewMode(context, AppViewMode.SIMPLIFIED)
                                    viewModel.onParentalActivity()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSimplified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (isSimplified) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .tvFocusable(shape = RoundedCornerShape(12.dp))
                            ) {
                                Text(
                                    text = "Simplificado",
                                    fontWeight = if (isSimplified) FontWeight.Bold else FontWeight.Normal
                                )
                            }

                            Button(
                                onClick = {
                                    viewModel.setAppViewMode(context, AppViewMode.ADVANCED)
                                    viewModel.onParentalActivity()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!isSimplified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (!isSimplified) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .tvFocusable(shape = RoundedCornerShape(12.dp))
                            ) {
                                Text(
                                    text = stringResource(R.string.editor),
                                    fontWeight = if (!isSimplified) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // Section 2: Default Player
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.editor_default_player).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        FilledTonalButton(
                            onClick = {
                                showPlayerPicker = true
                                viewModel.onParentalActivity()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusable(shape = RoundedCornerShape(12.dp))
                        ) {
                            val playerTitle = when (state.defaultPlayerMode) {
                                DefaultPlayerMode.INTERNAL -> stringResource(R.string.player_dialog_integrated_title)
                                DefaultPlayerMode.EXTERNAL -> state.preferredExternalAppName ?: stringResource(R.string.player_dialog_chooser_title)
                                DefaultPlayerMode.ASK -> stringResource(R.string.player_dialog_ask_title)
                            }
                            Text(playerTitle, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Section 3: App Theme (Claro / Oscuro / Sistema)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.app_theme_title).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val themeOptions = listOf(
                                com.example.ui.AppThemeMode.SYSTEM to (stringResource(R.string.theme_system) to Icons.Filled.BrightnessAuto),
                                com.example.ui.AppThemeMode.DARK to (stringResource(R.string.theme_dark) to Icons.Filled.DarkMode),
                                com.example.ui.AppThemeMode.LIGHT to (stringResource(R.string.theme_light) to Icons.Filled.LightMode)
                            )

                            themeOptions.forEach { (mode, pair) ->
                                val (title, icon) = pair
                                val isSelected = (state.appThemeMode == mode)
                                Button(
                                    onClick = {
                                        viewModel.setAppThemeMode(context, mode)
                                        viewModel.onParentalActivity()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .tvFocusable(shape = RoundedCornerShape(12.dp)),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 4: Parental Control
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.parental_control).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Toggle Parental Control
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.parental_control),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.parental_control_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = parentalEnabled,
                                onCheckedChange = { isChecked ->
                                    parentalEnabled = isChecked
                                    val currentPin = state.parentalPin.ifBlank { "0000" }
                                    viewModel.setParentalControl(context, enabled = isChecked, pin = currentPin, lockModeSwitch = lockMode)
                                    viewModel.onParentalActivity()
                                    if (isChecked && state.parentalPin.isBlank()) {
                                        showChangePinDialog = true
                                    }
                                },
                                modifier = Modifier.tvFocusable()
                            )
                        }

                        if (parentalEnabled) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Lock Mode Switch (Hide button to change modes)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.parental_lock_mode_switch),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.parental_lock_mode_switch_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = lockMode,
                                    onCheckedChange = { isChecked ->
                                        lockMode = isChecked
                                        viewModel.setParentalControl(context, enabled = true, pin = state.parentalPin.ifBlank { "0000" }, lockModeSwitch = isChecked)
                                        viewModel.onParentalActivity()
                                    },
                                    modifier = Modifier.tvFocusable()
                                )
                            }

                            // Change PIN Button
                            OutlinedButton(
                                onClick = {
                                    showChangePinDialog = true
                                    viewModel.onParentalActivity()
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusable(shape = RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.parental_change_pin))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.tvFocusable()
            ) {
                Text(stringResource(R.string.accept))
            }
        }
    )
}

@Composable
fun ChangePinDialog(
    onSavePin: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = stringResource(R.string.parental_change_pin),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) newPin = it },
                    label = { Text(stringResource(R.string.parental_new_pin)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) confirmPin = it },
                    label = { Text(stringResource(R.string.parental_confirm_pin)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newPin.length != 4) {
                        errorMsg = "El PIN debe tener 4 dígitos"
                    } else if (newPin != confirmPin) {
                        errorMsg = "Los códigos PIN no coinciden"
                    } else {
                        onSavePin(newPin)
                    }
                },
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
