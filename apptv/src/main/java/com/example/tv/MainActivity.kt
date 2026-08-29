package com.example.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tv.ui.screens.EditorScreen
import com.example.tv.ui.screens.HomeScreen
import com.example.tv.ui.theme.TvTheme
import com.example.ui.EditorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TvTheme {
                TvApp()
            }
        }
    }
}

@Composable
fun TvApp() {
    val viewModel: EditorViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var currentScreen by rememberSaveable { mutableStateOf("home") }

    LaunchedEffect(Unit) {
        val hasSaved = viewModel.loadSavedPlaylist(context)
        if (hasSaved) {
            currentScreen = "editor"
        }
    }

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
