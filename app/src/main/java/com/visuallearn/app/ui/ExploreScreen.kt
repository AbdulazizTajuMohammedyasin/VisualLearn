package com.visuallearn.app.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.visuallearn.app.figure.FigureView
import com.visuallearn.app.gallery.Gallery
import com.visuallearn.app.gallery.GalleryDetail
import com.visuallearn.app.gallery.GalleryFigure
import java.io.File

/** Browse figures from the Anatomy community gallery. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(gallery: Gallery, onOpen: (GalleryFigure) -> Unit, onBack: () -> Unit) {
    var figures by remember { mutableStateOf<List<GalleryFigure>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        runCatching { gallery.list() }
            .onSuccess { figures = it }
            .onFailure { error = "Couldn't load the gallery. Check your connection." }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Explore") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val list = figures
        when {
            list == null && error == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list == null -> Column(
                Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error.orEmpty())
                OutlinedButton(onClick = { attempt++ }) { Text("Try again") }
            }
            else -> {
                val q = query.trim().lowercase()
                val shown = if (q.isEmpty()) list else list.filter { f ->
                    f.title.lowercase().contains(q) || f.snippet.lowercase().contains(q) ||
                        f.tags.any { it.contains(q) } || f.author.lowercase().contains(q)
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(padding),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Figures made by the community with the Anatomy skill. Each one is shown with its author.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                leadingIcon = { Icon(Icons.Filled.Search, null) },
                                placeholder = { Text("Search figures, tags, authors") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    items(shown, key = { it.key }) { figure -> GalleryCard(figure, onClick = { onOpen(figure) }) }
                }
            }
        }
    }
}

@Composable
private fun GalleryCard(figure: GalleryFigure, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(14.dp)) {
        Column {
            AsyncImage(
                model = figure.imageUrl,
                contentDescription = figure.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
            )
            Column(Modifier.padding(10.dp)) {
                Text(figure.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${figure.kind} · @${figure.author}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One gallery figure, downloaded and shown in the sandboxed viewer, with credit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryFigureScreen(gallery: Gallery, figure: GalleryFigure, onBack: () -> Unit) {
    var detail by remember { mutableStateOf<GalleryDetail?>(null) }
    var page by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(figure.key) {
        runCatching {
            val d = gallery.detail(figure)
            detail = d
            page = gallery.page(figure, d)
        }.onFailure { error = "Couldn't download this figure." }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(figure.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("by @${figure.author}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, "About this figure") } },
            )
        },
    ) { padding ->
        val file = page
        when {
            file != null -> FigureView(key = file.absolutePath, html = { file.readText() }, modifier = Modifier.padding(padding).fillMaxSize())
            error != null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!) }
            else -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(figure.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("By @${figure.author} · ${detail?.license ?: "MIT"} licence")
                    Text(
                        listOfNotNull(figure.kind, figure.model, figure.minutes?.let { "$it min to make" }, figure.created)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    detail?.prompt?.let { Text("Prompt: $it", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } },
            dismissButton = detail?.let { d ->
                {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, d.sourceUrl.toUri())) }) {
                        Text("View source")
                    }
                }
            },
        )
    }
}
