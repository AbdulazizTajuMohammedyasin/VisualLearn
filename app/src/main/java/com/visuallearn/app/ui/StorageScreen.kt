package com.visuallearn.app.ui

import android.content.Context
import android.os.StatFs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.visuallearn.app.figures.FiguresRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private data class Usage(val figures: Long, val gallery: Long, val free: Long)

/** What VisualLearn keeps on the phone. Everything here can be downloaded again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(figures: FiguresRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    var usage by remember { mutableStateOf<Usage?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    LaunchedEffect(refresh) {
        usage = withContext(Dispatchers.IO) {
            Usage(figures.cacheBytes(), galleryDirs(context).sumOf(::sizeOf), StatFs(context.filesDir.absolutePath).availableBytes)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val u = usage
            if (u == null) {
                CircularProgressIndicator(Modifier.padding(24.dp))
                return@Column
            }
            ListItem(
                headlineContent = { Text("Saved figures") },
                supportingContent = { Text("Kept for offline viewing; downloaded again when opened") },
                trailingContent = {
                    TextButton(onClick = { confirm = "Remove saved figure pages from this phone?" to figures::clearCache }) {
                        Text(size(u.figures))
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Gallery cache") },
                trailingContent = {
                    TextButton(onClick = {
                        confirm = "Clear downloaded gallery figures and images?" to { galleryDirs(context).forEach { it.deleteRecursively() } }
                    }) { Text(size(u.gallery)) }
                },
            )
            HorizontalDivider()
            ListItem(headlineContent = { Text("Free on this phone") }, trailingContent = { Text(size(u.free)) })
        }
    }

    confirm?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { action(); confirm = null; refresh++ }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private fun galleryDirs(context: Context) = listOf(File(context.cacheDir, "gallery"), File(context.cacheDir, "image_cache"))

private fun sizeOf(file: File): Long = if (!file.exists()) 0 else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun size(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
}
