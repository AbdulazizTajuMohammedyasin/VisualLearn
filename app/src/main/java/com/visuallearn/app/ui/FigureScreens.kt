package com.visuallearn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visuallearn.app.api.FigureStatus
import com.visuallearn.app.figure.FigureView
import com.visuallearn.app.figures.FiguresRepository
import java.io.File

/** Full-screen viewer for a finished figure, with a light/dark switch. Pages come from the local cache. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FigureScreen(figures: FiguresRepository, figureId: String, preferDark: Boolean, onBack: () -> Unit) {
    val all by figures.figures.collectAsStateWithLifecycle()
    val figure = all[figureId]
    var dark by rememberSaveable { mutableStateOf(preferDark) }
    var showInfo by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<File?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(figureId, dark, figure?.status) {
        page = null
        failed = false
        if (figure == null || figure.status == FigureStatus.SUCCEEDED) {
            runCatching { figures.page(figureId, dark) }
                .onSuccess { page = it; failed = it == null }
                .onFailure { failed = true }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(figure?.title ?: "Figure", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { dark = !dark }) { Text(if (dark) "Light" else "Dark") }
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, "About this figure") }
                },
            )
        },
    ) { padding ->
        val file = page
        when {
            file != null -> FigureView(key = file.absolutePath, html = { file.readText() }, modifier = Modifier.padding(padding).fillMaxSize())
            figure != null && figure.status != FigureStatus.SUCCEEDED -> Centered(padding, "This figure isn't finished yet.")
            failed -> Centered(padding, "Couldn't load this figure. Check your connection.")
            else -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }

    if (showInfo && figure != null) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } },
            title = { Text(figure.title ?: "Figure") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    figure.summary?.let { Text(it) }
                    Text("Request: ${figure.prompt}", style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }
}

@Composable
private fun Centered(padding: PaddingValues, text: String) {
    Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every figure job, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiguresScreen(figures: FiguresRepository, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val all by figures.figures.collectAsStateWithLifecycle()
    val list = all.values.sortedByDescending { it.createdAt }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { figures.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My figures") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (list.isEmpty()) {
            Centered(padding, "No figures yet. Ask for one in a chat, or turn on Figure before sending.")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(list, key = { it.id }) { figure ->
                    Column {
                        FigureCard(
                            figure = figure,
                            onOpen = { onOpen(figure.id) },
                            onCancel = { figures.cancel(figure.id) },
                            onRetry = { figures.retry(figure.id) },
                        )
                        if (!figure.status.isActive) {
                            TextButton(onClick = { confirmDelete = figure.id }, modifier = Modifier.align(Alignment.End)) {
                                Icon(Icons.Filled.Delete, null)
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this figure?") },
            text = { Text("It is removed from your account and from this phone.") },
            confirmButton = { TextButton(onClick = { figures.delete(id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}
