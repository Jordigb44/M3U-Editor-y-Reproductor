package com.example

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AppViewMode
import com.example.ui.DeviceMode
import com.example.ui.EditorViewModel
import com.example.ui.LocalIsTvMode
import com.example.ui.PlayerKeyRouter
import com.example.ui.components.ModeSelectionDialog
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SimplifiedScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isTv = DeviceMode.isTv(this)
        setContent {
            CompositionLocalProvider(LocalIsTvMode provides isTv) {
                MyApplicationTheme {
                    M3uEditorApp()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> -1
            else -> null
        }
        if (delta != null && PlayerKeyRouter.onZap != null) {
            PlayerKeyRouter.onZap?.invoke(delta)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun M3uEditorApp() {
    val viewModel: EditorViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    
    var currentScreen by rememberSaveable { mutableStateOf("home") }
    var showInitialModeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val hasSaved = viewModel.loadSavedPlaylist(context)
        if (hasSaved) {
            currentScreen = "editor"
        }
    }

    LaunchedEffect(state.appViewMode) {
        if (state.appViewMode == AppViewMode.UNSET) {
            showInitialModeDialog = true
        }
    }

    // Initial Mode Selection Dialog (First time user launches app)
    if (showInitialModeDialog) {
        ModeSelectionDialog(
            onSelectMode = { mode ->
                viewModel.setAppViewMode(context, mode)
                showInitialModeDialog = false
            }
        )
    }

    if (state.appViewMode == AppViewMode.SIMPLIFIED) {
        SimplifiedScreen(
            state = state,
            viewModel = viewModel,
            onSwitchToAdvanced = {
                viewModel.setAppViewMode(context, AppViewMode.ADVANCED)
                if (state.channels.isNotEmpty()) {
                    currentScreen = "editor"
                } else {
                    currentScreen = "home"
                }
            }
        )
    } else {
        // Advanced Mode
        if (currentScreen == "home") {
            HomeScreen(
                state = state,
                onSelectPlaylist = { id ->
                    viewModel.switchPlaylist(context, id)
                    currentScreen = "editor"
                },
                onRenamePlaylist = { id, newName ->
                    viewModel.renamePlaylist(context, id, newName)
                },
                onDeletePlaylist = { id ->
                    viewModel.deletePlaylist(context, id)
                },
                onLoadFile = { uri ->
                    viewModel.loadFromFile(context, uri)
                    currentScreen = "editor"
                },
                onLoadUrl = { url ->
                    viewModel.loadFromUrl(context, url)
                    currentScreen = "editor"
                },
                onErrorDismiss = { viewModel.clearError() }
            )
        } else {
            EditorScreen(
                state = state,
                viewModel = viewModel,
                onBack = { currentScreen = "home" }
            )
        }
    }
}
