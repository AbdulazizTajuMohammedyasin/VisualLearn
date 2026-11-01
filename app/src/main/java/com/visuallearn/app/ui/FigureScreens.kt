package com.visuallearn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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

/** Full-screen viewer for a finished figure, in its dark or light drawing. Pages come from the local cache. */
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
                title = {
                    Text(figure?.title ?: "Figure", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, "About this figure") } },
            )
        },
        bottomBar = {
            // The drawing comes in two inks; switching is a choice between them, not a setting.
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                SingleChoiceSegmentedButtonRow {
                    listOf(true to "Dark", false to "Light").forEachIndexed { i, (isDark, label) ->
                        SegmentedButton(
                            selected = dark == isDark,
                            onClick = { dark = isDark },
                            shape = SegmentedButtonDefaults.itemShape(i, 2, CircleShape),
                            icon = {},
                        ) { Text(label) }
                    }
                }
            }
        },
    ) { padding ->
        val file = page
        when {
            file != null -> FigureView(key = file.absolutePath, html = { file.readText() }, modifier = Modifier.padding(padding).fillMaxSize())
            figure != null && figure.status != FigureStatus.SUCCEEDED -> Centered(padding, "This figure isn't finished yet.")
            failed -> Centered(padding, "Couldn't load this figure. Check your connection and open it again.")
            else -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }

    if (showInfo && figure != null) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } },
            title = { Text(figure.title ?: "Figure") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    figure.summary?.let { Text(it) }
                    Text("You asked: ${figure.prompt}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Every figure, newest first, as a gallery of previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiguresScreen(figures: FiguresRepository, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val all by figures.figures.collectAsStateWithLifecycle()
    val previews by figures.previews.collectAsStateWithLifecycle()
    val list = all.values.sortedByDescending { it.createdAt }
    var selected by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { figures.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val building = list.count { it.status.isActive }
        val ready = list.count { it.status == FigureStatus.SUCCEEDED }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TwoLineHeading(
                    "Your figures",
                    when {
                        list.isEmpty() -> "None yet. Ask for one in a chat."
                        building > 0 -> "$ready ready, $building being built."
                        else -> if (ready == 1) "1 ready to open." else "$ready ready to open."
                    },
                    Modifier.padding(start = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            items(list, key = { it.id }) { figure ->
                Box {
                    FigureTile(
                        figure,
                        previews[figure.id],
                        onClick = { if (figure.status == FigureStatus.SUCCEEDED) onOpen(figure.id) else selected = figure.id },
                        onLongClick = { selected = figure.id },
                    )
                    DropdownMenu(expanded = selected == figure.id, onDismissRequest = { selected = null }) {
                        when (figure.status) {
                            FigureStatus.QUEUED, FigureStatus.RUNNING ->
                                DropdownMenuItem(text = { Text("Cancel") }, onClick = { figures.cancel(figure.id); selected = null })
                            FigureStatus.FAILED, FigureStatus.CANCELLED ->
                                DropdownMenuItem(text = { Text("Try again") }, onClick = { figures.retry(figure.id); selected = null })
                            FigureStatus.SUCCEEDED -> {}
                        }
                        if (!figure.status.isActive) {
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { confirmDelete = figure.id; selected = null })
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
