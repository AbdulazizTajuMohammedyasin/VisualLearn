package com.visuallearn.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visuallearn.app.figure.FigureView
import com.visuallearn.app.linux.EnvController
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = (application as VisualLearnApp).envController
        runDebugTask(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                EnvScreen(controller)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        runDebugTask(intent)
    }

    /** Debug builds only: `adb shell am start -n com.visuallearn.app/.MainActivity --es task checks` */
    private fun runDebugTask(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val app = application as VisualLearnApp
        val controller = app.envController
        when (intent?.getStringExtra("task")) {
            "install" -> controller.installRootfs()
            "tools" -> controller.installTools()
            "setup" -> controller.setUpEverything()
            "checks" -> controller.runChecks()
            "demo" -> controller.buildDemo()
            "figure" -> intent.getStringExtra("prompt")?.let { app.figureJobs.create(it, "dark") }
        }
    }

    companion object {
        const val EXTRA_JOB = "job"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvScreen(controller: EnvController) {
    val state by controller.state.collectAsStateWithLifecycle()
    var openFigure by remember { mutableStateOf<File?>(null) }

    openFigure?.let { figure ->
        BackHandler { openFigure = null }
        Scaffold(topBar = { TopAppBar(title = { Text(figure.nameWithoutExtension) }) }) { padding ->
            FigureView(figure, Modifier.padding(padding).fillMaxSize())
        }
        return
    }

    Scaffold(topBar = { TopAppBar(title = { Text("VisualLearn · Linux environment") }) }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 12.dp).fillMaxSize()) {
            val busy = state.runningTask != null
            Text(
                when {
                    busy -> "Running: ${state.runningTask}…"
                    state.lastResult != null -> state.lastResult!!
                    state.installed -> "Debian installed"
                    else -> "Debian not installed"
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = controller::installRootfs, enabled = !busy) { Text("1 · Install Debian") }
                Button(onClick = controller::installTools, enabled = !busy && state.installed) { Text("2 · Install tools") }
                Button(onClick = controller::runChecks, enabled = !busy && state.installed) { Text("3 · Checks") }
                Button(onClick = controller::buildDemo, enabled = !busy && state.installed) { Text("4 · Build demo") }
            }
            if (state.figures.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.figures.forEach { f ->
                        OutlinedButton(onClick = { openFigure = f }) { Text("Open ${f.nameWithoutExtension}") }
                    }
                }
            }
            LogView(state.log, Modifier.padding(vertical = 8.dp).fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun LogView(lines: List<String>, modifier: Modifier) {
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
