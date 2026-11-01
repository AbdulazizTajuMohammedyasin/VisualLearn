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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.remember
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
import com.visuallearn.app.chat.ChatController
import com.visuallearn.app.chat.ChatMessage
import com.visuallearn.app.chat.ChatStore
import com.visuallearn.app.chat.Role
import com.visuallearn.app.jobs.FigureJobs
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "How does a pin tumbler lock open?",
    "Show me how a token bucket rate limiter works",
    "Why does a mechanical watch tick?",
    "Explain how a CDN edge cache decides hit or miss",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: ChatController,
    store: ChatStore,
    jobs: FigureJobs,
    hasApiKey: Boolean,
    onOpenFigure: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExplore: (() -> Unit)?,
    onOpenFigures: () -> Unit,
) {
    val state by chat.state.collectAsStateWithLifecycle()
    val summaries by store.summaries.collectAsStateWithLifecycle()
    val jobMap by jobs.jobs.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
                Text(
                    "VisualLearn",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("New chat") },
                    icon = { Icon(Icons.Filled.Add, null) },
                    selected = false,
                    onClick = { chat.newChat(); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                onOpenExplore?.let { open ->
                    NavigationDrawerItem(
                        label = { Text("Explore figures") },
                        icon = { Icon(Icons.Filled.Search, null) },
                        selected = false,
                        onClick = { scope.launch { drawer.close() }; open() },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                NavigationDrawerItem(
                    label = { Text("My figures") },
                    icon = { Icon(Icons.Filled.Star, null) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; onOpenFigures() },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 24.dp))
                Text(
                    "Chats",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(summaries, key = { it.id }) { s ->
                        NavigationDrawerItem(
                            label = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = s.id == state.conversation.id,
                            onClick = { chat.open(s.id); scope.launch { drawer.close() } },
                            badge = {
                                IconButton(onClick = { chat.delete(s.id) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Delete, "Delete chat", Modifier.size(18.dp))
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; onOpenSettings() },
                    modifier = Modifier.padding(12.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (state.conversation.messages.isEmpty()) "VisualLearn" else state.conversation.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menu") }
                    },
                    actions = {
                        IconButton(onClick = chat::newChat) { Icon(Icons.Filled.Add, "New chat") }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                val messages = state.conversation.messages
                Box(Modifier.weight(1f)) {
                    if (messages.isEmpty() && !state.isStreaming) {
                        EmptyState(hasApiKey, onOpenSettings) { chat.send(it, figureMode = false) }
                    } else {
                        MessageList(
                            messages = messages,
                            streamingText = state.streamingText,
                            pendingFigureId = state.pendingFigureId,
                            figureCard = { id ->
                                jobMap[id]?.let { job ->
                                    FigureCard(
                                        job = job,
                                        onOpen = { onOpenFigure(id) },
                                        onCancel = { jobs.cancel(id) },
                                        onResume = { jobs.resume(id) },
                                    )
                                }
                            },
                            onRetry = chat::retry,
                        )
                    }
                }
                Composer(
                    streaming = state.isStreaming,
                    onSend = chat::send,
                    onStop = chat::stop,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(hasApiKey: Boolean, onOpenSettings: () -> Unit, onSuggestion: (String) -> Unit) {
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
        if (!hasApiKey) {
            OutlinedButton(onClick = onOpenSettings) { Text("Add your Claude API key") }
        } else {
            SUGGESTIONS.forEach { s ->
                AssistChip(onClick = { onSuggestion(s) }, label = { Text(s) }, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    streamingText: String?,
    pendingFigureId: String?,
    figureCard: @Composable (String) -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    val itemCount = messages.size + if (streamingText != null) 1 else 0
    LaunchedEffect(itemCount, streamingText?.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1, Int.MAX_VALUE / 2)
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(messages, key = { it.id }) { m ->
            when (m.role) {
                Role.USER -> UserBubble(m.text)
                Role.ASSISTANT -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (m.text.isNotBlank()) MarkdownText(m.text)
                    m.figureJobId?.let { figureCard(it) }
                    m.error?.let { error ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = onRetry) { Text("Retry") }
                        }
                    }
                }
            }
        }
        if (streamingText != null) {
            item(key = "streaming") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (streamingText.isEmpty() && pendingFigureId == null) TypingDots()
                    else if (streamingText.isNotEmpty()) MarkdownText(streamingText)
                    pendingFigureId?.let { figureCard(it) }
                }
            }
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

@Composable
private fun Composer(streaming: Boolean, onSend: (String, Boolean) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var figureMode by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
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
                                onClick = {
                                    onSend(text, figureMode)
                                    text = ""
                                    figureMode = false
                                },
                                enabled = text.isNotBlank(),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                        }
                    }
                }
            }
        }
    }
}
