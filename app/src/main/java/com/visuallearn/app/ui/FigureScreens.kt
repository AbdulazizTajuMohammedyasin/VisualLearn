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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.visuallearn.app.figure.FigureView
import com.visuallearn.app.jobs.FigureJobs
import com.visuallearn.app.jobs.JobState

/** Full-screen viewer for a finished figure job, with a light/dark switch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FigureScreen(jobs: FigureJobs, jobId: String, preferDark: Boolean, onBack: () -> Unit) {
    val jobMap by jobs.jobs.collectAsStateWithLifecycle()
    val job = jobMap[jobId]
    var dark by rememberSaveable { mutableStateOf(preferDark) }
    var showInfo by remember { mutableStateOf(false) }
    val page = if (dark) jobs.darkPage(jobId) else jobs.lightPage(jobId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(job?.title ?: "Figure", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { dark = !dark }) { Text(if (dark) "Light" else "Dark") }
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, "About this figure") }
                },
            )
        },
    ) { padding ->
        if (page.exists()) {
            FigureView(key = page.absolutePath, html = { page.readText() }, modifier = Modifier.padding(padding).fillMaxSize())
        } else {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This figure isn't built yet.")
            }
        }
    }

    if (showInfo && job != null) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } },
            title = { Text(job.title ?: "Figure") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    job.summary?.let { Text(it) }
                    Text("Request: ${job.prompt}", style = MaterialTheme.typography.bodySmall)
                    if (job.costUsd > 0) Text("API cost: ${formatCost(job.costUsd)}", style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }
}

/** Every figure job, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiguresScreen(jobs: FigureJobs, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val jobMap by jobs.jobs.collectAsStateWithLifecycle()
    val list = jobMap.values.sortedByDescending { it.createdAt }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My figures") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (list.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No figures yet. Ask for one in a chat, or turn on Figure before sending.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(list, key = { it.id }) { job ->
                    Column {
                        FigureCard(
                            job = job,
                            onOpen = { onOpen(job.id) },
                            onCancel = { jobs.cancel(job.id) },
                            onResume = { jobs.resume(job.id) },
                        )
                        if (!job.isActive) {
                            TextButton(onClick = { confirmDelete = job.id }, modifier = Modifier.align(Alignment.End)) {
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
            text = { Text("Its pages and build files are removed from the phone.") },
            confirmButton = { TextButton(onClick = { jobs.delete(id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}
