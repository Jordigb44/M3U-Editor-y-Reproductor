package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.EditorViewModel
import com.jordiguixbetancor.m3ueditor.R

@Composable
fun ParentalPinDialog(
    viewModel: EditorViewModel,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    fun submitPin(pin: String) {
        if (viewModel.verifyParentalPin(pin)) {
            viewModel.unlockParentalSession()
            onSuccess()
        } else {
            isError = true
            enteredPin = ""
        }
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
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = stringResource(R.string.parental_pin_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.parental_pin_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Masked PIN display dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { idx ->
                        val filled = idx < enteredPin.length
                        Surface(
                            shape = CircleShape,
                            color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (filled) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(18.dp)
                        ) {}
                    }
                }

                if (isError) {
                    Text(
                        text = stringResource(R.string.parental_pin_incorrect),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Numeric Keypad (1 to 9, 0, Backspace)
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val rows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("C", "0", "DEL")
                    )

                    for (row in rows) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (key in row) {
                                when (key) {
                                    "C" -> {
                                        OutlinedButton(
                                            onClick = {
                                                enteredPin = ""
                                                isError = false
                                            },
                                            modifier = Modifier
                                                .size(56.dp)
                                                .tvFocusable(shape = RoundedCornerShape(14.dp)),
                                            shape = RoundedCornerShape(14.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("C", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        }
                                    }
                                    "DEL" -> {
                                        OutlinedButton(
                                            onClick = {
                                                if (enteredPin.isNotEmpty()) {
                                                    enteredPin = enteredPin.dropLast(1)
                                                    isError = false
                                                }
                                            },
                                            modifier = Modifier
                                                .size(56.dp)
                                                .tvFocusable(shape = RoundedCornerShape(14.dp)),
                                            shape = RoundedCornerShape(14.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Backspace,
                                                contentDescription = "Borrar",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    else -> {
                                        FilledTonalButton(
                                            onClick = {
                                                if (enteredPin.length < 4) {
                                                    val newPin = enteredPin + key
                                                    enteredPin = newPin
                                                    isError = false
                                                    if (newPin.length == 4) {
                                                        submitPin(newPin)
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(56.dp)
                                                .tvFocusable(shape = RoundedCornerShape(14.dp)),
                                            shape = RoundedCornerShape(14.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text(
                                                text = key,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 20.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
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
