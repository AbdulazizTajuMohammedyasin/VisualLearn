package com.visuallearn.app.ui

import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visuallearn.app.linux.EnvController
import java.util.Locale

private const val REQUIRED_BYTES = 2_500_000_000L

private data class SetupStep(val title: String, val detail: String)

private val STEPS = listOf(
    SetupStep("Download Debian", "A 34 MB Linux userland, checked against its SHA-256."),
    SetupStep("Install the toolchain", "Chromium, Python and Node.js from Debian and nodejs.org."),
    SetupStep("Install Anatomy and the figure agent", "The figure skill and Claude's Python SDK."),
)

/** First-run onboarding: installs the on-device Linux environment figures are built in. */
@Composable
fun SetupScreen(controller: EnvController, onSkip: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var showLog by remember { mutableStateOf(false) }
    val running = state.runningTask != null
    val failed = !running && state.lastResult?.let { !it.endsWith("OK") } == true
    val freeBytes = remember { StatFs(controller.env.baseDir.parentFile!!.absolutePath).availableBytes }
    val enoughSpace = freeBytes >= REQUIRED_BYTES || state.installed

    val log = state.log
    val currentStep = when {
        state.toolsReady -> STEPS.size
        log.any { it.startsWith("==> Anatomy") || it.startsWith("==> Figure agent") } -> 2
        log.any { it.startsWith("==> ") } || state.installed -> 1
        else -> 0
    }
    val detail = log.lastOrNull { it.isNotBlank() && !it.startsWith("──") }.orEmpty()

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Set up the figure workshop", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Figures are built on this phone, in a small Linux environment that runs inside the app. " +
                    "Setup downloads about 500 MB and takes 15 to 25 minutes. Use Wi-Fi and keep the phone charging.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            STEPS.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    when {
                        index < currentStep -> Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.secondary)
                        index == currentStep && running -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        else -> Text("${index + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(step.title, style = MaterialTheme.typography.titleSmall)
                        Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (failed) {
                Text(
                    "Setup stopped: ${state.lastResult}. Check the connection and try again; finished steps are kept.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!enoughSpace) {
                Text(
                    "Not enough free space: ${gb(freeBytes)} free, about ${gb(REQUIRED_BYTES)} needed.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            if (showLog) LogView(state.log, Modifier.fillMaxWidth().height(200.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide details" else "Show details") }
                Spacer(Modifier.weight(1f))
                if (!running) TextButton(onClick = onSkip) { Text("Chat only") }
                Button(onClick = controller::setUpEverything, enabled = !running && enoughSpace) {
                    Text(if (failed) "Try again" else "Set up")
                }
            }
        }
    }
}

private fun gb(bytes: Long) = String.format(Locale.US, "%.1f GB", bytes / 1e9)
