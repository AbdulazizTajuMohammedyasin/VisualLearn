package com.visuallearn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visuallearn.app.api.Message
import com.visuallearn.app.api.MessageStatus
import com.visuallearn.app.api.Part
import com.visuallearn.app.api.Role
import com.visuallearn.app.api.SendMode
import com.visuallearn.app.chat.ChatRepository
import com.visuallearn.app.figures.FiguresRepository
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "How does a pin tumbler lock open?",
    "Show me how a token bucket rate limiter works",
    "Quiz me on how zippers work",
    "Explain how a CDN edge cache decides hit or miss",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: ChatRepository,
    figures: FiguresRepository,
    online: Boolean,
    onOpenFigure: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExplore: () -> Unit,
    onOpenFigures: () -> Unit,
) {
    val state by chat.state.collectAsStateWithLifecycle()
    val summaries by chat.summaries.collectAsStateWithLifecycle()
    val figureMap by figures.figures.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun closeDrawer() = scope.launch { drawer.close() }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
                Text("VisualLearn", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 12.dp))
                DrawerItem("New chat", Icons.Filled.Add) { chat.newChat(); closeDrawer() }
                DrawerItem("Explore figures", Icons.Filled.Search) { closeDrawer(); onOpenExplore() }
                DrawerItem("My figures", Icons.Filled.Star) { closeDrawer(); onOpenFigures() }
                HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 24.dp))
                Text("Chats", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, bottom = 4.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(summaries, key = { it.id }) { s ->
                        NavigationDrawerItem(
                            label = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = s.id == state.conversationId,
                            onClick = { chat.open(s.id); closeDrawer() },
                            badge = {
                                if (online) IconButton(onClick = { chat.delete(s.id) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Delete, "Delete chat", Modifier.size(18.dp))
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                DrawerItem("Settings", Icons.Filled.Settings, Modifier.padding(vertical = 12.dp)) { closeDrawer(); onOpenSettings() }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(state.title.ifBlank { "VisualLearn" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!online) {
                                Text("Offline · showing saved copy", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menu") } },
                    actions = { IconButton(onClick = chat::newChat) { Icon(Icons.Filled.Add, "New chat") } },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                Box(Modifier.weight(1f)) {
                    when {
                        state.loading && state.messages.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.messages.isEmpty() -> EmptyState(enabled = online) { chat.send(it, SendMode.AUTO) }
                        else -> MessageList(
                            messages = state.messages,
                            figureCard = { id ->
                                figureMap[id]?.let { figure ->
                                    FigureCard(figure, onOpen = { onOpenFigure(id) }, onCancel = { figures.cancel(id) }, onRetry = { figures.retry(id) })
                                }
                            },
                            onRetry = chat::retry,
                        )
                    }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (online) {
                    Composer(
                        streaming = state.isStreaming,
                        onSend = { text, figureMode -> chat.send(text, if (figureMode) SendMode.FIGURE else SendMode.AUTO) },
                        onStop = chat::stop,
                    )
                } else {
                    OfflineNotice()
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, null) },
        selected = false,
        onClick = onClick,
        modifier = modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun EmptyState(enabled: Boolean, onSuggestion: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Home, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(12.dp))
        Text("What should we take apart today?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Ask anything. When an idea needs seeing, VisualLearn builds a working figure of it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        SUGGESTIONS.forEach { s ->
            AssistChip(onClick = { onSuggestion(s) }, label = { Text(s) }, enabled = enabled, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun MessageList(messages: List<Message>, figureCard: @Composable (String) -> Unit, onRetry: () -> Unit) {
    val listState = rememberLazyListState()
    val lastLength = messages.lastOrNull()?.parts?.sumOf { (it as? Part.Text)?.text?.length ?: 1 } ?: 0
    LaunchedEffect(messages.size, lastLength) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex, Int.MAX_VALUE / 2)
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(messages, key = { it.id }) { m ->
            when (m.role) {
                Role.USER -> UserBubble(m.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
                Role.ASSISTANT -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (m.parts.isEmpty() && m.status == MessageStatus.STREAMING) TypingDots()
                    m.parts.forEach { part ->
                        when (part) {
                            is Part.Text -> if (part.text.isNotBlank()) MarkdownText(part.text)
                            is Part.Ui -> InteractivePartPlaceholder(part)
                            is Part.Figure -> figureCard(part.figureId)
                        }
                    }
                    if (m.status == MessageStatus.FAILED) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.error?.message ?: "This reply didn't finish.", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = onRetry) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
}

/** Stand-in for OpenUI content until the interactive renderer lands. */
@Composable
private fun InteractivePartPlaceholder(part: Part.Ui) {
    val components = Regex("""=\s*([A-Z][A-Za-z]+)\(""").findAll(part.program).map { it.groupValues[1] }.toList()
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Interactive: ${components.firstOrNull() ?: "content"}", style = MaterialTheme.typography.titleSmall)
            Text(
                "${components.size} components. The interactive renderer arrives with the OpenUI component library.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun TypingDots() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        repeat(3) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), CircleShape))
        }
    }
}

/** Takes the composer's place without a connection: saved chats and figures stay readable. */
@Composable
private fun OfflineNotice() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        ) {
            Text(
                "You're offline. Saved chats and figures still open; new questions need a connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun Composer(streaming: Boolean, onSend: (String, Boolean) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var figureMode by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("Ask VisualLearn") },
                        maxLines = 6,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = 2.dp, bottom = 2.dp)) {
                        FilterChip(
                            selected = figureMode,
                            onClick = { figureMode = !figureMode },
                            label = { Text("Figure") },
                            leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) },
                        )
                        Spacer(Modifier.weight(1f))
                        if (streaming) {
                            FilledIconButton(onClick = onStop) { Icon(Icons.Filled.Close, "Stop") }
                        } else {
                            FilledIconButton(
                                onClick = { onSend(text, figureMode); text = ""; figureMode = false },
                                enabled = text.isNotBlank(),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                        }
                    }
                }
            }
        }
    }
}
