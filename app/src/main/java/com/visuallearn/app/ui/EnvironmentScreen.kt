package com.visuallearn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visuallearn.app.linux.EnvController

/** Maintenance view of the Debian guest: re-run setup, checks, and see the log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentScreen(controller: EnvController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val busy = state.runningTask != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Linux environment") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize()) {
            Text(
                when {
                    busy -> "Running: ${state.runningTask}…"
                    state.lastResult != null -> state.lastResult!!
                    state.toolsReady -> "Ready"
                    state.installed -> "Debian installed; tools not set up"
                    else -> "Not installed"
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = controller::setUpEverything, enabled = !busy) { Text("Run setup") }
                OutlinedButton(onClick = controller::runChecks, enabled = !busy && state.installed) { Text("Run checks") }
                OutlinedButton(onClick = controller::installRootfs, enabled = !busy) { Text("Reinstall Debian") }
            }
            LogView(state.log, Modifier.padding(vertical = 8.dp).fillMaxWidth().weight(1f))
        }
    }
}

@Composable
fun LogView(lines: List<String>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
    LazyColumn(
        state = listState,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
    ) {
        items(lines) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp)
        }
    }
}
