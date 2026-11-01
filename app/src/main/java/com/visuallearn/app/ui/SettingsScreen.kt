package com.visuallearn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visuallearn.app.settings.FigureModel
import com.visuallearn.app.settings.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onBack: () -> Unit,
    onOpenEnvironment: () -> Unit,
    onOpenStorage: (() -> Unit)? = null,
    onOpenLicenses: (() -> Unit)? = null,
) {
    val state by settings.state.collectAsStateWithLifecycle()
    var keyInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Claude API key", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.hasApiKey) "A key is saved, encrypted with the Android Keystore."
                else "Chat and figures use your own key from console.anthropic.com. It stays on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(if (state.hasApiKey) "Replace key" else "sk-ant-…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { settings.apiKey = keyInput; keyInput = "" }, enabled = keyInput.startsWith("sk-")) {
                    Text("Save key")
                }
                if (state.hasApiKey) TextButton(onClick = { settings.apiKey = null }) { Text("Remove key") }
            }

            HorizontalDivider()
            Text("Figure model", style = MaterialTheme.typography.titleMedium)
            Text(
                "Figures are long agent sessions. Opus gives the best figures; Sonnet costs about half.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FigureModel.entries.forEach { model ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.figureModel == model, onClick = { settings.figureModel = model })
                    Text(model.label)
                }
            }

            HorizontalDivider()
            Text("Figure theme", style = MaterialTheme.typography.titleMedium)
            listOf(null to "Follow system", true to "Dark", false to "Light").forEach { (value, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.darkFigures == value, onClick = { settings.darkFigures = value })
                    Text(label)
                }
            }

            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Linux environment") },
                supportingContent = { Text("Debian, Node, Chromium and the Anatomy skill") },
                modifier = Modifier.fillMaxWidth().padding(0.dp),
                trailingContent = { TextButton(onClick = onOpenEnvironment) { Text("Open") } },
            )
            onOpenStorage?.let { open ->
                ListItem(
                    headlineContent = { Text("Storage") },
                    supportingContent = { Text("Space used by figures and the Linux environment") },
                    trailingContent = { TextButton(onClick = open) { Text("Open") } },
                )
            }
            onOpenLicenses?.let { open ->
                ListItem(
                    headlineContent = { Text("Open-source licences") },
                    trailingContent = { TextButton(onClick = open) { Text("Open") } },
                )
            }
        }
    }
}
