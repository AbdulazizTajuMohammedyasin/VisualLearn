package com.visuallearn.app.ui

import android.content.Context
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.visuallearn.app.jobs.FigureJobs
import com.visuallearn.app.linux.EnvController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private data class Usage(
    val linux: Long,
    val figures: Long,
    val buildFiles: Long,
    val galleryCache: Long,
    val chats: Long,
    val free: Long,
)

private fun sizeOf(file: File): Long =
    if (!file.exists()) 0 else file.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        .filter { it.isFile }.sumOf { it.length() }

private fun measure(context: Context, env: EnvController): Usage {
    val jobsDir = env.env.guestFile("/root/jobs")
    val jobDirs = jobsDir.listFiles().orEmpty()
    val outputs = jobDirs.sumOf { sizeOf(File(it, "out")) }
    val build = jobDirs.sumOf { sizeOf(File(it, "work")) + sizeOf(File(it, "messages.json")) }
    return Usage(
        linux = sizeOf(env.env.rootfs) - outputs - build,
        figures = outputs,
        buildFiles = build,
        galleryCache = sizeOf(File(context.cacheDir, "gallery")) + sizeOf(File(context.cacheDir, "image_cache")),
        chats = sizeOf(File(context.filesDir, "chats")),
        free = StatFs(context.filesDir.absolutePath).availableBytes,
    )
}

/** What VisualLearn stores on the phone, with ways to free space. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(env: EnvController, jobs: FigureJobs, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<Usage?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }

    LaunchedEffect(refresh) {
        usage = null
        usage = withContext(Dispatchers.IO) { measure(context, env) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val u = usage
            if (u == null) {
                CircularProgressIndicator(Modifier.padding(24.dp))
                return@Column
            }
            ListItem(
                headlineContent = { Text("Linux environment") },
                supportingContent = { Text("Debian, Chromium, Node.js, Python and the Anatomy skill") },
                trailingContent = { Text(size(u.linux)) },
            )
            ListItem(
                headlineContent = { Text("Finished figures") },
                trailingContent = { Text(size(u.figures)) },
            )
            ListItem(
                headlineContent = { Text("Figure build files") },
                supportingContent = { Text("Source and conversation kept so builds can resume") },
                trailingContent = {
                    TextButton(onClick = {
                        confirm = "Delete build files of finished figures? Their pages stay." to {
                            withContext(Dispatchers.IO) { jobs.clearBuildFiles() }
                        }
                    }) { Text(size(u.buildFiles)) }
                },
            )
            ListItem(
                headlineContent = { Text("Gallery cache") },
                trailingContent = {
                    TextButton(onClick = {
                        confirm = "Clear downloaded gallery figures and images?" to {
                            withContext(Dispatchers.IO) {
                                File(context.cacheDir, "gallery").deleteRecursively()
                                File(context.cacheDir, "image_cache").deleteRecursively()
                            }
                        }
                    }) { Text(size(u.galleryCache)) }
                },
            )
            ListItem(headlineContent = { Text("Chats") }, trailingContent = { Text(size(u.chats)) })
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Free on this phone") },
                trailingContent = { Text(size(u.free)) },
            )
            HorizontalDivider()
            TextButton(
                onClick = {
                    confirm = "Remove the Linux environment? Figures you made are deleted too, and setup must run again " +
                        "before the next figure." to {
                        withContext(Dispatchers.IO) {
                            if (!jobs.hasActiveJobs) {
                                env.removeEnvironment()
                                jobs.reload()
                            }
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Remove Linux environment", color = MaterialTheme.colorScheme.error) }
        }
    }

    confirm?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch { action(); refresh++ }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private fun size(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
}
