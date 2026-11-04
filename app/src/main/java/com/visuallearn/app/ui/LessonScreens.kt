package com.visuallearn.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visuallearn.app.chat.PdfAttachment
import com.visuallearn.app.figure.FigureView
import com.visuallearn.app.figures.FiguresRepository
import com.visuallearn.app.learn.AnswerChoice
import com.visuallearn.app.learn.Conclusion
import com.visuallearn.app.learn.JobProgress
import com.visuallearn.app.learn.LessonItem
import com.visuallearn.app.learn.LessonRepository
import com.visuallearn.app.learn.LessonSummary
import com.visuallearn.app.learn.MathText
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ─── the lesson list ────────────────────────────────────────────────────────

/** Lessons: probing what you know, a plan, teaching one step at a time with checks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonsScreen(lessons: LessonRepository, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val list by lessons.lessons.collectAsStateWithLifecycle()
    var starting by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<LessonSummary?>(null) }
    LaunchedEffect(Unit) { lessons.refreshLessons() }

    Scaffold(
        topBar = { TopAppBar(title = {}, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                TwoLineHeading("Lessons", "Taught from what you already know.", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Each lesson first finds where your understanding ends, plans the shortest way to your goal, and teaches one step at a time with a check after each.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 16.dp),
                )
                Button(onClick = { starting = true }, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start a lesson")
                }
                Spacer(Modifier.height(20.dp))
            }
            items(list, key = { it.id }) { lesson ->
                Surface(
                    onClick = { onOpen(lesson.id) },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 6.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(lesson.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (lesson.pendingQuestion) {
                                    StatusDot(StatusColors.Waiting, size = 6.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(lessonStatus(lesson), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { confirmDelete = lesson }) {
                            Icon(Icons.Filled.Delete, "Delete ${lesson.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (starting) StartLessonSheet(lessons, onStarted = { id -> starting = false; onOpen(id) }, onDismiss = { starting = false })
    confirmDelete?.let { lesson ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this lesson?") },
            text = { Text("\"${lesson.title}\" and its answers are removed. What you learned stays in your knowledge graph.") },
            confirmButton = { TextButton(onClick = { lessons.delete(lesson.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

private fun lessonStatus(l: LessonSummary): String = when {
    l.busy -> "The teacher is working"
    l.pendingQuestion -> "Waiting for your answer"
    l.kind == "practice" -> "Practice"
    l.phase == "probe" -> "Finding where you are"
    l.phase == "plan" -> "Planning"
    else -> "Teaching"
}

/** What to learn, with an optional PDF the teacher reads first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartLessonSheet(lessons: LessonRepository, onStarted: (String) -> Unit, onDismiss: () -> Unit, initialPdf: PdfAttachment? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var goal by rememberSaveable { mutableStateOf("") }
    var pdf by remember { mutableStateOf(initialPdf) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { pdf = pdfAttachment(context, it) } }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TwoLineHeading("What do you want to understand?", "Say it in your own words.", style = MaterialTheme.typography.titleLarge)
            TextField(
                value = goal,
                onValueChange = { goal = it },
                placeholder = { Text(if (pdf == null) "How a four-stroke engine turns fuel into rotation" else "What you want from it, or leave empty") },
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )
            val attached = pdf
            if (attached != null) {
                DocumentChip(attached.name, sizeLabel(attached.bytes), onRemove = { pdf = null })
            } else {
                OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf")) }) {
                    PageGlyph(size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Add a PDF to learn from")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { lessons.start(goal.trim(), pdf) }
                            .onSuccess(onStarted)
                            .onFailure { error = it.message ?: "Couldn't start the lesson." }
                        busy = false
                    }
                },
                enabled = !busy && (goal.isNotBlank() || pdf != null),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Start the lesson")
            }
        }
    }
}

// ─── one lesson ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonScreen(lessons: LessonRepository, figures: FiguresRepository, lessonId: String, onOpenFigure: (String) -> Unit, onBack: () -> Unit) {
    // Followed live only while on screen; a figure opened on top closes it until we're back.
    DisposableEffect(lessonId) {
        lessons.open(lessonId)
        onDispose { lessons.close() }
    }
    val state by lessons.state.collectAsStateWithLifecycle()
    val figureMap by figures.figures.collectAsStateWithLifecycle()
    val previews by figures.previews.collectAsStateWithLifecycle()
    val lesson = state.lesson
    var showPlan by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lesson?.title ?: "Lesson", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (lesson?.plan?.isNotEmpty() == true) TextButton(onClick = { showPlan = true }) { Text("Plan") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val listState = rememberLazyListState()
            val count = lesson?.items?.size ?: 0
            LaunchedEffect(count, lesson?.items?.lastOrNull()) { if (count > 0) listState.animateScrollToItem(count - 1, Int.MAX_VALUE / 2) }
            Box(Modifier.weight(1f)) {
                if (lesson == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (state.loading) CircularProgressIndicator() else Text(state.error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(lesson.items, key = { it.key }) { item ->
                            when (item) {
                                is LessonItem.Learner -> LearnerBubble(item.text, item.pdf)
                                is LessonItem.Teacher -> MarkdownText(MathText.plain(item.markdown))
                                is LessonItem.Question -> if (item.answer != null) AnsweredQuestion(item) else PendingMarker(item)
                                is LessonItem.Divider -> PhaseDivider(item.text)
                                is LessonItem.Working -> WorkingPlate(item, state.activity["${item.key.removePrefix("job-")}"])
                                is LessonItem.Figure -> {
                                    val figure = item.figureId?.let { figureMap[it] }
                                    if (figure != null) {
                                        FigurePlate(figure, previews[figure.id], onOpen = { onOpenFigure(figure.id) }, onCancel = { figures.cancel(figure.id) }, onRetry = { figures.retry(figure.id) })
                                    } else if (item.figureId != null) {
                                        WorkingPlate(LessonItem.Working(item.key, "figure", item.title), JobProgress("running", "Loading the figure"))
                                    } else {
                                        Text("The 3D figure \"${item.title}\" couldn't be built.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                is LessonItem.Demo -> ArtifactFrame(item.key, item.title.ifBlank { "Interactive demo" }) { item.html }
                                is LessonItem.Visual -> ArtifactFrame(item.key, null) { "<!doctype html><meta name=viewport content='width=device-width'><body style='margin:0;background:#fff'>${item.svg}</body>" }
                                is LessonItem.Problem -> ProblemLine(item.text, onRetry = lessons::retry)
                                is LessonItem.Exercise -> ExerciseCard(item)
                                is LessonItem.ExerciseResult -> ExerciseResultCard(item)
                                is LessonItem.Attempt -> LearnerBubble(item.text, null, label = "Your working")
                            }
                        }
                        if (lesson.busy) item("thinking") { ThinkingLine() }
                    }
                }
            }
            state.error?.takeIf { lesson != null }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            val pending = lesson?.pendingQuestion
            val exercise = lesson?.activeExercise
            if (pending != null && !lesson.busy) {
                QuestionDock(pending, sending = state.sending) { choice -> lessons.answer(pending, choice) }
            } else if (exercise != null) {
                // Working a problem: what's typed is the learner's working, which the practitioner reads and answers.
                LessonComposer(enabled = !lesson.busy && !state.sending, placeholder = "Your working or answer") { lessons.work(exercise, it) }
            } else if (lesson != null) {
                if (lesson.betweenProblems) {
                    Button(
                        onClick = lessons::nextProblem,
                        enabled = !state.sending,
                        modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                    ) { Text("Next problem") }
                }
                LessonComposer(enabled = !lesson.busy && !state.sending, onSend = lessons::say)
            }
        }
    }

    if (showPlan && lesson != null) {
        ModalBottomSheet(onDismissRequest = { showPlan = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TwoLineHeading("The plan", "From what you know to your goal.", style = MaterialTheme.typography.titleLarge)
                lesson.plan.forEachIndexed { i, node ->
                    Row {
                        Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(node.title, style = MaterialTheme.typography.titleSmall)
                            node.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LearnerBubble(text: String, pdf: String?, label: String? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) }
        pdf?.let { DocumentChip(it, "PDF") }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(22.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun PhaseDivider(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ThinkingLine() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(StatusColors.Building)
        Spacer(Modifier.width(10.dp))
        Text("The teacher is thinking", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProblemLine(text: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

/** A question card in the transcript points down to the answer card while it waits. */
@Composable
private fun PendingMarker(q: LessonItem.Question) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(StatusColors.Waiting)
        Spacer(Modifier.width(10.dp))
        Text("${q.label}: answer below", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AnsweredQuestion(q: LessonItem.Question) {
    val a = q.answer ?: return
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(q.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                when (a.correct) {
                    true -> Verdict("Right", StatusColors.Ready)
                    false -> Verdict(if (a.dontKnow) "Not sure" else "Not quite", if (a.dontKnow) StatusColors.Waiting else StatusColors.Failed)
                    null -> {}
                }
            }
            Text(MathText.plain(q.prompt), style = MaterialTheme.typography.titleSmall)
            val given = when {
                a.dontKnow -> "I don't know"
                a.labels.isNotEmpty() -> a.labels.joinToString(", ")
                else -> a.text
            }
            given?.let { Text("You: ${MathText.plain(it)}", style = MaterialTheme.typography.bodyMedium) }
            (a.explanation ?: a.committed)?.let {
                Text(MathText.plain(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Verdict(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, size = 7.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** A practice problem, marked with how it ended once it has. */
@Composable
private fun ExerciseCard(x: LessonItem.Exercise) {
    val c = x.conclusion
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (c == null) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (x.mode) {
                        "discrimination" -> "Problem: tell them apart"
                        "composition" -> "Problem: put it together"
                        else -> "Problem: from memory"
                    },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (c == null) Verdict("Your turn", StatusColors.Waiting) else Outcome(c)
            }
            MarkdownText(MathText.plain(x.statement))
        }
    }
}

/** The verdict on the learner's working, and the solution the practitioner committed to before posing the problem. */
@Composable
private fun ExerciseResultCard(r: LessonItem.ExerciseResult) {
    var showSolution by rememberSaveable(r.key) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Outcome(r.conclusion)
            Text(MathText.plain(r.conclusion.verdict), style = MaterialTheme.typography.bodyMedium)
            if (r.solution != null) {
                TextButton(onClick = { showSolution = !showSolution }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showSolution) "Hide the solution" else "Show the solution")
                }
                if (showSolution) MarkdownText(MathText.plain(r.solution))
            }
        }
    }
}

@Composable
private fun Outcome(c: Conclusion) = when (c.outcome) {
    "solved" -> Verdict("Solved", StatusColors.Ready)
    "solvedWithHints" -> Verdict("Solved with hints", StatusColors.Ready)
    "movedOn" -> Verdict("Moved on", StatusColors.Waiting)
    "dismissed" -> Verdict("Set aside", MaterialTheme.colorScheme.outline)
    else -> Verdict("Not solved", StatusColors.Failed)
}

/** Background work in progress: research, a PDF being read, a demo or 3D figure being built. */
@Composable
private fun WorkingPlate(item: LessonItem.Working, progress: JobProgress?) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF121214))) {
        if (item.kind == "figure" || item.kind == "demo") IsoGrid(Modifier.matchParentSize(), moving = true)
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    when {
                        progress?.done == true -> StatusColors.Ready
                        progress?.failed == true -> StatusColors.Failed
                        else -> StatusColors.Building
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (progress?.done == true) "Ready" else if (progress?.failed == true) "Didn't finish" else when (item.kind) {
                        "figure" -> "Building a 3D figure"
                        "demo" -> "Building an interactive demo"
                        "visual" -> "Drawing a figure"
                        "pdf" -> "Reading your PDF"
                        else -> "Looking it up"
                    },
                    style = MaterialTheme.typography.labelMedium, color = Color(0xFFF4F4F1),
                )
            }
            Text(item.title, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF4F4F1))
            Text(
                if (progress?.done == true) "The teacher brings it in at the next step."
                else if (progress?.failed == true) progress.error ?: "The teacher will carry on without it."
                else progress?.activity?.takeIf { it.isNotBlank() } ?: if (item.kind == "figure") "This takes 20 to 60 minutes. You'll be told when it's ready; the lesson goes on meanwhile." else "The lesson goes on meanwhile.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF8B8B92), maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A demo or visual: the teacher's own page, shown with no network. */
@Composable
private fun ArtifactFrame(key: String, title: String?, html: () -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        Box(Modifier.fillMaxWidth().aspectRatio(0.9f).clip(RoundedCornerShape(20.dp)).background(Color.White)) {
            FigureView(key = key, html = html, modifier = Modifier.fillMaxSize())
        }
    }
}

// ─── answering ──────────────────────────────────────────────────────────────

/** The question the teacher is waiting on: choose or write, say how sure, add your thinking. */
@Composable
private fun QuestionDock(q: LessonItem.Question, sending: Boolean, onAnswer: (AnswerChoice) -> Unit) {
    var selected by remember(q.callId) { mutableStateOf(setOf<String>()) }
    var text by remember(q.callId) { mutableStateOf("") }
    var thinking by remember(q.callId) { mutableStateOf("") }
    var confidence by remember(q.callId) { mutableFloatStateOf(50f) }
    val graded = q.kind == "quiz"
    val written = q.kind == "explain" || q.options.isEmpty()

    Surface(color = MaterialTheme.colorScheme.background) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        ) {
            Column {
                // The question scrolls; the actions below it stay in view, even with the keyboard up.
                Column(Modifier.heightInMax().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(StatusColors.Waiting)
                        Spacer(Modifier.width(8.dp))
                        Text(q.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(MathText.plain(q.prompt), style = MaterialTheme.typography.titleMedium)
                    q.details?.let { Text(MathText.plain(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (written) {
                        DockField(text, { text = it }, "Your answer, in your own words", minLines = 3)
                    } else {
                        q.options.forEach { opt ->
                            val on = opt.value in selected
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (on) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = if (on) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .semantics { this.selected = on; role = if (q.multiSelect) Role.Checkbox else Role.RadioButton }
                                    .clickable {
                                        selected = if (q.multiSelect) (if (on) selected - opt.value else selected + opt.value) else setOf(opt.value)
                                    },
                            ) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Text(MathText.plain(opt.label), style = MaterialTheme.typography.bodyLarge)
                                    opt.description?.let { Text(MathText.plain(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                        if (q.multiSelect) Text("Choose all that apply.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (graded && selected.isNotEmpty()) {
                        Text("How sure are you? ${confidence.roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                        Slider(value = confidence, onValueChange = { confidence = it }, valueRange = 0f..100f, steps = 9)
                    }
                    DockField(thinking, { thinking = it }, "Your thinking (optional): why, or what's unclear", minLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 18.dp, top = 8.dp, bottom = 14.dp)) {
                    if (graded) TextButton(onClick = { onAnswer(AnswerChoice(dontKnow = true, thinking = thinking)) }, enabled = !sending) { Text("I don't know") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            onAnswer(AnswerChoice(values = selected.toList(), text = text.takeIf { written }, thinking = thinking, confidence = if (graded) confidence.roundToInt() else null))
                        },
                        enabled = !sending && (if (written) text.isNotBlank() else selected.isNotEmpty()),
                    ) {
                        if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("Answer")
                    }
                }
            }
        }
    }
}

/** Keeps a long question from pushing the transcript off screen. */
private fun Modifier.heightInMax() = this.then(Modifier.heightIn(max = 400.dp))

@Composable
private fun DockField(value: String, onChange: (String) -> Unit, hint: String, minLines: Int) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint, style = MaterialTheme.typography.bodyMedium) },
        minLines = minLines,
        maxLines = 6,
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LessonComposer(enabled: Boolean, placeholder: String = "Ask or tell the teacher something", onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.background) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(placeholder) },
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(onClick = { onSend(text); text = "" }, enabled = enabled && text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}
