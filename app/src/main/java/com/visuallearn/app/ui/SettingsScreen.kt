package com.visuallearn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visuallearn.app.api.User
import com.visuallearn.app.api.VisualLearnApi
import com.visuallearn.app.settings.Settings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    api: VisualLearnApi,
    settings: Settings,
    onBack: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val state by settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<User?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { account = runCatching { api.me() }.getOrNull() }

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
            Text("Account", style = MaterialTheme.typography.titleMedium)
            val a = account
            if (a == null) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(a.displayName, style = MaterialTheme.typography.bodyLarge)
                a.email?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Row {
                TextButton(onClick = { scope.launch { api.signOut() } }) { Text("Sign out") }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete account", color = MaterialTheme.colorScheme.error)
                }
            }
            deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

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
                headlineContent = { Text("Storage") },
                supportingContent = { Text("Figures saved on this phone for offline viewing") },
                trailingContent = { TextButton(onClick = onOpenStorage) { Text("Open") } },
            )
            ListItem(
                headlineContent = { Text("Open-source licences") },
                trailingContent = { TextButton(onClick = onOpenLicenses) { Text("Open") } },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = { Text("Your chats and figures are deleted from VisualLearn's servers and this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        runCatching { api.deleteAccount() }.onFailure { deleteError = "Couldn't delete the account. Try again." }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
