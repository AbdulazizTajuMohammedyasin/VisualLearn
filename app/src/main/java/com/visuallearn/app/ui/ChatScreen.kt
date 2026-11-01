package com.visuallearn.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visuallearn.app.api.Figure
import com.visuallearn.app.api.Message
import com.visuallearn.app.api.MessageStatus
import com.visuallearn.app.api.Part
import com.visuallearn.app.api.Role
import com.visuallearn.app.api.SendMode
import com.visuallearn.app.chat.ChatRepository
import com.visuallearn.app.figures.FiguresRepository
import com.visuallearn.app.openui.OpenUiView
import kotlinx.coroutines.launch
import java.io.File

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
    val previews by figures.previews.collectAsStateWithLifecycle()
    val darkTheme = isSystemInDarkTheme()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun closeDrawer() = scope.launch { drawer.close() }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp), drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Text("VisualLearn", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 16.dp))
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
                            Text(state.title.ifBlank { "VisualLearn" }, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!online) {
                                Text("Offline, showing the saved copy", style = MaterialTheme.typography.labelMedium,
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
                        state.messages.isEmpty() -> EmptyState(
                            enabled = online,
                            recent = figureMap.values.sortedByDescending { it.createdAt }.take(8),
                            previews = previews,
                            onOpenFigure = onOpenFigure,
                            onOpenFigures = onOpenFigures,
                            onSuggestion = { chat.send(it, SendMode.AUTO) },
                        )
                        else -> MessageList(
                            messages = state.messages,
                            figureCard = { id ->
                                figureMap[id]?.let { figure ->
                                    FigurePlate(
                                        figure,
                                        preview = previews[id],
                                        onOpen = { onOpenFigure(id) },
                                        onCancel = { figures.cancel(id) },
                                        onRetry = { figures.retry(id) },
                                    )
                                }
                            },
                            uiPart = { m, index, part ->
                                val key = "${m.id}-$index"
                                val saved = remember(key) { chat.uiState(m.id, index) }
                                OpenUiView(
                                    key = key,
                                    program = part.program,
                                    streaming = m.status == MessageStatus.STREAMING,
                                    dark = darkTheme,
                                    savedState = saved,
                                    onStateChange = { chat.saveUiState(m.id, index, it) },
                                    onAction = { chat.sendUiAction(m.id, it.name, it.message, it.context) },
                                    onOpenFigure = onOpenFigure,
                                )
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

/** A new chat: what the app does, the figures made so far, and questions to start from. */
@Composable
private fun EmptyState(
    enabled: Boolean,
    recent: List<Figure>,
    previews: Map<String, File>,
    onOpenFigure: (String) -> Unit,
    onOpenFigures: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    // Sits at the bottom, near the thumb and the composer; scrolls on short screens.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            TwoLineHeading(
                "See how things work.",
                "Ask, and watch it get built.",
                Modifier.padding(horizontal = 24.dp),
            )
            if (recent.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 28.dp, bottom = 4.dp),
                ) {
                    Text("Your figures", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenFigures) { Text("See all") }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(recent, key = { it.id }) { f ->
                        FigureTile(f, previews[f.id], onClick = { onOpenFigure(f.id) }, modifier = Modifier.width(196.dp))
                    }
                }
            }
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SUGGESTIONS.forEach { s -> SuggestionBubble(s, enabled) { onSuggestion(s) } }
            }
        }
    }
}

/** A question to start from, shaped like a message you could send. */
@Composable
private fun SuggestionBubble(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
    }
}

@Composable
private fun MessageList(
    messages: List<Message>,
    figureCard: @Composable (String) -> Unit,
    uiPart: @Composable (Message, Int, Part.Ui) -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Stay pinned to the bottom until the user scrolls away; scrolling back down pins it again.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (!scrolling && !listState.canScrollForward) follow = true }
    }
    val lastLength = messages.lastOrNull()?.parts?.sumOf { (it as? Part.Text)?.text?.length ?: 1 } ?: 0
    LaunchedEffect(messages.size) { follow = true }
    LaunchedEffect(messages.size, lastLength) {
        if (messages.isNotEmpty() && follow) listState.animateScrollToItem(messages.lastIndex, Int.MAX_VALUE / 2)
    }
    // Interactive parts and figure cards grow after they load, without any change to the text.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size }
            .collect {
                val last = listState.layoutInfo.totalItemsCount - 1
                if (follow && last >= 0 && listState.canScrollForward) listState.scrollToItem(last, Int.MAX_VALUE / 2)
            }
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
                    m.parts.forEachIndexed { index, part ->
                        when (part) {
                            is Part.Text -> if (part.text.isNotBlank()) MarkdownText(part.text)
                            is Part.Ui -> uiPart(m, index, part)
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

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(22.dp),
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
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
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
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                        FigureToggle(figureMode) { figureMode = !figureMode }
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

/** Asks for a figure with the next message, even when the question doesn't mention one. */
@Composable
private fun FigureToggle(on: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = if (on) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
        border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.semantics { role = SemanticsRole.Switch; stateDescription = if (on) "On" else "Off" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (on) StatusDot(StatusColors.Building) else Box(Modifier.size(8.dp).border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("Figure", style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
