package com.visuallearn.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visuallearn.app.learn.KnowledgeIdea
import com.visuallearn.app.learn.KnowledgeRepository
import com.visuallearn.app.learn.MathText
import com.visuallearn.app.learn.NotesImport

/** What the learner knows: every idea from their lessons and notes, by topic. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(knowledge: KnowledgeRepository, onOpenLesson: (String) -> Unit, onBack: () -> Unit) {
    val state by knowledge.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { knowledge.import(pdfAttachment(context, it)) }
    }
    LaunchedEffect(Unit) { knowledge.refresh() }
    val k = state.knowledge

    Scaffold(
        topBar = { TopAppBar(title = {}, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                TwoLineHeading("What you know", "Built from your lessons and notes.", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.headlineMedium)
                Text(
                    when {
                        k == null || k.isEmpty -> "As you take lessons, each idea you meet is kept here as one plain statement, with what it follows from. You can also add your own notes."
                        else -> "${k.ideas} ${if (k.ideas == 1) "idea" else "ideas"} in ${k.topics.size} ${if (k.topics.size == 1) "topic" else "topics"}. Tap an idea to see what it follows from and where it came up."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 16.dp),
                )
                Button(
                    onClick = { pick.launch(arrayOf("application/pdf", "text/plain", "text/markdown")) },
                    enabled = state.importing == null,
                    contentPadding = PaddingValues(start = 18.dp, end = 22.dp, top = 14.dp, bottom = 14.dp),
                ) {
                    PageGlyph(size = 20.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Add notes")
                }
                Spacer(Modifier.size(16.dp))
                state.importing?.let { ImportPlate(it, null, onDismiss = null) }
                state.imported?.let { ImportPlate(it.name, it, onDismiss = knowledge::dismissImport) }
                if (state.loading && k == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            }
            k?.topics?.forEach { topic ->
                item(key = "topic-${topic.id}") {
                    Text(
                        topic.name ?: if (k.topics.size == 1) "Ideas" else "More ideas",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(topic.ideas, key = { "${topic.id}-${it.id}" }) { idea -> IdeaCard(idea, onOpenLesson) }
            }
        }
    }
}

@Composable
private fun IdeaCard(idea: KnowledgeIdea, onOpenLesson: (String) -> Unit) {
    var open by rememberSaveable(idea.id) { mutableStateOf(false) }
    Surface(onClick = { open = !open }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(MathText.plain(idea.name), style = MaterialTheme.typography.titleSmall)
            Text(
                MathText.plain(idea.statement),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (open) {
                idea.because.forEach { premises -> Related("Follows from", premises.joinToString(" + ") { MathText.plain(it) }) }
                if (idea.leadsTo.isNotEmpty()) Related("Leads to", idea.leadsTo.joinToString(", ") { MathText.plain(it) })
                if (idea.lessons.isNotEmpty() || idea.notes.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        idea.lessons.forEach { lesson ->
                            Surface(onClick = { onOpenLesson(lesson.id) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Text(lesson.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                        idea.notes.forEach { name ->
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    PageGlyph(size = 14.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Related(label: String, text: String) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A notes import: being read, or what came of it. */
@Composable
private fun ImportPlate(name: String, result: NotesImport?, onDismiss: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 6.dp)) {
            StatusDot(
                when {
                    result == null -> StatusColors.Building
                    result.ok -> StatusColors.Ready
                    else -> StatusColors.Failed
                },
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when {
                        result == null -> "Reading $name"
                        !result.ok -> "Couldn't read $name"
                        result.ideas == 0 -> "Nothing new in $name"
                        else -> "Added ${result.ideas} ${if (result.ideas == 1) "idea" else "ideas"} from $name"
                    },
                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    result?.message ?: if (result == null) "This takes a minute or two. You can leave this screen." else "Already known ideas are linked, not repeated.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            if (onDismiss != null) IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
