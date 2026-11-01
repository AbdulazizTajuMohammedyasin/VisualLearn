package com.visuallearn.app.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.visuallearn.app.auth.TokenStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process stand-in for the VisualLearn server, for debug builds while the backend
 * is being built. Replies are canned; figure jobs are simulated and finish with
 * Anatomy's dial-indicator example (MIT, github.com/wheresryan22/anatomy) from assets/fake.
 */
class FakeApi(private val context: Context, private val tokens: TokenStore) : VisualLearnApi {

    private val conversations = ConcurrentHashMap<String, Conversation>()
    private val figures = ConcurrentHashMap<String, FakeFigure>()
    private val pendingReplies = ConcurrentHashMap<String, Script>()

    // For /sync: when each conversation last changed, and when things were deleted (epoch ms).
    private val conversationChangedAt = ConcurrentHashMap<String, Long>()
    private val deletedConversations = ConcurrentHashMap<String, Long>()
    private val deletedFigures = ConcurrentHashMap<String, Long>()

    private data class FakeFigure(val figure: Figure, val startedAtMs: Long, var cancelledAt: String? = null)
    private data class Script(val conversationId: String, val text: String, val mode: SendMode, val uiAction: UiAction? = null)

    @Volatile private var email: String = "dev@visuallearn.invalid"

    override suspend fun signInWithGoogle(idToken: String): Tokens = session()

    override suspend fun startEmailSignIn(email: String) {
        delay(400)
        if (!email.contains('@')) throw ApiException(ApiError("invalid_request", "Enter a valid email address.", false), 400)
        this.email = email
    }

    override suspend fun signInWithEmail(email: String, code: String): Tokens {
        if (code != FAKE_CODE) throw ApiException(ApiError("invalid_code", "That code isn't right. The fake server's code is $FAKE_CODE.", false), 400)
        return session()
    }

    private suspend fun session(): Tokens {
        delay(400)
        return Tokens("fake-access", System.currentTimeMillis() + 3_600_000, "fake-refresh").also(tokens::save)
    }

    override suspend fun signOut() = tokens.clear()

    override suspend fun me() = User("usr_dev", "Developer", email, null, "user")

    override suspend fun deleteAccount() {
        conversations.clear()
        figures.clear()
        conversationChangedAt.clear()
        deletedConversations.clear()
        deletedFigures.clear()
        tokens.clear()
    }

    override suspend fun sync(since: String?): SyncResult {
        requireOnline()
        val cursor = System.currentTimeMillis()
        val after = since?.toLongOrNull() ?: Long.MIN_VALUE
        return SyncResult(
            conversations = conversations.values.filter { (conversationChangedAt[it.id] ?: 0) > after }.map(::summary),
            deletedConversationIds = deletedConversations.filterValues { it > after }.keys.toList(),
            figures = figures.keys.map(::current).filter { Instant.parse(it.updatedAt).toEpochMilli() > after },
            deletedFigureIds = deletedFigures.filterValues { it > after }.keys.toList(),
            cursor = cursor.toString(),
        )
    }

    override suspend fun conversations(): List<ConversationSummary> {
        requireOnline()
        return conversations.values.map(::summary).sortedByDescending { it.updatedAt }
    }

    override suspend fun createConversation(): ConversationSummary {
        requireOnline()
        val c = Conversation("conv_" + uuid(), "New chat", emptyList())
        conversations[c.id] = c
        touch(c.id)
        return summary(c)
    }

    override suspend fun conversation(id: String): Conversation {
        requireOnline()
        return conversations[id] ?: throw ApiException(ApiError("not_found", "This chat no longer exists.", false), 404)
    }

    override suspend fun deleteConversation(id: String) {
        requireOnline()
        conversations.remove(id)
        conversationChangedAt.remove(id)
        deletedConversations[id] = System.currentTimeMillis()
    }

    private fun touch(conversationId: String) {
        conversationChangedAt[conversationId] = System.currentTimeMillis()
    }

    private fun summary(c: Conversation) =
        ConversationSummary(c.id, c.title, Instant.ofEpochMilli(conversationChangedAt[c.id] ?: 0).toString())

    override suspend fun sendMessage(conversationId: String, text: String, mode: SendMode, uiAction: UiAction?): Pair<Message, Message> {
        requireOnline()
        delay(200)
        val shown = text.ifBlank { uiAction?.let { "(${it.name})" }.orEmpty() }
        val user = Message("msg_" + uuid(), Role.USER, MessageStatus.COMPLETE, listOf(Part.Text(shown)))
        val assistant = Message("msg_" + uuid(), Role.ASSISTANT, MessageStatus.STREAMING, emptyList())
        conversations.computeIfPresent(conversationId) { _, c ->
            c.copy(title = if (c.messages.isEmpty()) shown.take(48) else c.title, messages = c.messages + user + assistant)
        }
        touch(conversationId)
        pendingReplies[assistant.id] = Script(conversationId, shown, mode, uiAction)
        return user to assistant
    }

    override fun messageEvents(messageId: String): Flow<MessageEvent> = flow {
        val script = pendingReplies.remove(messageId) ?: run {
            conversations.values.flatMap { it.messages }.firstOrNull { it.id == messageId }?.let { emit(MessageEvent.Done(it)) }
            return@flow
        }
        val lower = script.text.lowercase()
        val parts = mutableListOf<Part>()
        suspend fun streamText(index: Int, text: String) {
            emit(MessageEvent.PartStart(index, Part.Text("")))
            text.split(" ").forEach { word ->
                delay(35)
                emit(MessageEvent.TextDelta(index, "$word "))
            }
            parts += Part.Text(text)
        }
        delay(500)
        when {
            script.uiAction?.name == "review_mistakes" -> streamText(0, reviewMistakes(script.uiAction))
            script.mode == SendMode.FIGURE || listOf("show me", "figure", "visualize", "draw").any { it in lower } -> {
                streamText(0, "Good one to see in motion. I'm building an interactive figure of it now; the card below shows its progress, and you'll get a notification when it's ready.")
                val figure = startFigure(script.text, script.conversationId)
                emit(MessageEvent.PartStart(1, Part.Figure(figure.id)))
                parts += Part.Figure(figure.id)
            }
            listOf("quiz", "test me", "flashcard", "mcq").any { it in lower } -> {
                streamText(0, "Here's a short quiz. Answer each question to see the explanation.")
                emit(MessageEvent.PartStart(1, Part.Ui("", UI_LIBRARY_VERSION)))
                SAMPLE_QUIZ.lines().forEach { line ->
                    delay(60)
                    emit(MessageEvent.UiDelta(1, line + "\n"))
                }
                parts += Part.Ui(SAMPLE_QUIZ, UI_LIBRARY_VERSION)
            }
            else -> streamText(0, SAMPLE_ANSWER)
        }
        val done = Message(messageId, Role.ASSISTANT, MessageStatus.COMPLETE, parts)
        conversations.computeIfPresent(script.conversationId) { _, c ->
            c.copy(messages = c.messages.map { if (it.id == messageId) done else it })
        }
        touch(script.conversationId)
        emit(MessageEvent.Done(done))
    }

    override suspend fun stopMessage(messageId: String) {
        pendingReplies.remove(messageId)
    }

    override suspend fun figures(): List<Figure> {
        requireOnline()
        return figures.keys.map(::current).sortedByDescending { it.createdAt }
    }

    override suspend fun figure(id: String): Figure {
        requireOnline()
        if (!figures.containsKey(id)) throw ApiException(ApiError("not_found", "Figure not found.", false), 404)
        return current(id)
    }

    override suspend fun createFigure(prompt: String, theme: String, conversationId: String?): Figure {
        requireOnline()
        return startFigure(prompt, conversationId)
    }

    override fun figureEvents(id: String): Flow<Figure> = flow {
        var last: Figure? = null
        while (true) {
            val f = figure(id)
            if (f != last) emit(f)
            last = f
            if (!f.status.isActive) return@flow
            delay(500)
        }
    }

    override suspend fun cancelFigure(id: String): Figure {
        requireOnline()
        figures[id]?.cancelledAt = now()
        return figure(id)
    }

    override suspend fun retryFigure(id: String): Figure {
        requireOnline()
        val old = figures[id] ?: throw ApiException(ApiError("not_found", "Figure not found.", false), 404)
        figures[id] = FakeFigure(old.figure, System.currentTimeMillis())
        return figure(id)
    }

    override suspend fun deleteFigure(id: String) {
        requireOnline()
        figures.remove(id)
        deletedFigures[id] = System.currentTimeMillis()
    }

    override suspend fun downloadPage(page: Page): ByteArray {
        requireOnline()
        delay(300)
        return context.assets.open(page.url.removePrefix(ASSET_PREFIX)).use { it.readBytes() }
    }

    /** The fake runs in-process, so it checks the phone's connection to behave like a remote server. */
    private fun requireOnline() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) throw IOException("No connection")
    }

    private fun startFigure(prompt: String, conversationId: String?): Figure {
        val now = now()
        val f = Figure(
            id = "fig_" + uuid(), prompt = prompt, status = FigureStatus.QUEUED, queuePosition = JOBS_AHEAD,
            estimatedStartAt = null, stage = null, message = "Waiting for a free worker.", progress = null, title = null,
            summary = null, conversationId = conversationId, createdAt = now, updatedAt = now, startedAt = null,
            finishedAt = null, error = null, darkPage = null, lightPage = null, previewUrl = null,
        )
        figures[f.id] = FakeFigure(f, System.currentTimeMillis())
        return current(f.id)
    }

    /**
     * Derives the job's state from elapsed time: it waits behind [JOBS_AHEAD] other jobs
     * ([QUEUE_SECONDS] in all), then spends [STAGE_SECONDS] on each stage.
     */
    private fun current(id: String): Figure {
        val fake = figures.getValue(id)
        val queued = fake.figure
        val base = queued.copy(queuePosition = null)
        fun at(seconds: Double) = Instant.ofEpochMilli(fake.startedAtMs + (seconds * 1000).toLong()).toString()
        fake.cancelledAt?.let { return base.copy(status = FigureStatus.CANCELLED, message = "Cancelled", finishedAt = it, updatedAt = it) }
        val elapsed = (System.currentTimeMillis() - fake.startedAtMs) / 1000.0
        val perJob = QUEUE_SECONDS / JOBS_AHEAD
        val stageIndex = ((elapsed - QUEUE_SECONDS) / STAGE_SECONDS).toInt()
        return when {
            elapsed < QUEUE_SECONDS -> {
                val done = (elapsed / perJob).toInt()
                queued.copy(queuePosition = JOBS_AHEAD - done, estimatedStartAt = at(QUEUE_SECONDS), updatedAt = at(done * perJob))
            }
            stageIndex < STAGES.size -> base.copy(
                status = FigureStatus.RUNNING,
                stage = STAGES[stageIndex].first,
                message = STAGES[stageIndex].second,
                progress = (stageIndex + 1.0) / (STAGES.size + 1),
                startedAt = at(QUEUE_SECONDS),
                updatedAt = at(QUEUE_SECONDS + stageIndex * STAGE_SECONDS),
            )
            else -> base.copy(
                status = FigureStatus.SUCCEEDED, stage = "polish", message = "Finished", progress = 1.0,
                title = "Dial indicator on gauge blocks",
                summary = "Example figure from the fake server: a dial gauge on a stand reads three gauge blocks as the carriage slides under its plunger.",
                startedAt = at(QUEUE_SECONDS), finishedAt = at(QUEUE_SECONDS + STAGES.size * STAGE_SECONDS),
                updatedAt = at(QUEUE_SECONDS + STAGES.size * STAGE_SECONDS),
                darkPage = page("fake/dial-indicator.html"), lightPage = page("fake/dial-indicator-light.html"),
            )
        }
    }

    /** Answers the quiz's "Explain my mistakes" from the context the component sent. */
    private fun reviewMistakes(action: UiAction): String {
        val mistakes = action.context["mistakes"] as? org.json.JSONArray ?: return SAMPLE_ANSWER
        val lines = (0 until mistakes.length()).map { i ->
            val m = mistakes.getJSONObject(i)
            "- **${m.optString("question")}** You picked *${m.optString("chosen")}*; the answer is *${m.optString("correct")}*."
        }
        return "You got ${action.context["score"]} of ${action.context["total"]}. Here's what tripped you up:\n\n" +
            lines.joinToString("\n") + "\n\nAsk me to *quiz you again* when you're ready."
    }

    private fun page(asset: String): Page {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return Page(ASSET_PREFIX + asset, sha, bytes.size.toLong())
    }

    private fun uuid() = UUID.randomUUID().toString().take(12)
    private fun now() = Instant.now().toString()

    private companion object {
        const val ASSET_PREFIX = "asset:///"
        const val FAKE_CODE = "123456"
        const val UI_LIBRARY_VERSION = "1.0.0"
        const val STAGE_SECONDS = 4.0
        const val QUEUE_SECONDS = 9.0
        const val JOBS_AHEAD = 3
        val STAGES = listOf(
            "research" to "Collecting the real sizes and readings.",
            "design" to "Choosing a machine whose working is the idea.",
            "parts" to "Writing the parts list.",
            "geometry" to "Building the base, stand and carriage.",
            "build" to "Writing the page.",
            "motion" to "Adding the carriage motion and live readout.",
            "verify" to "Checking joints and line ends in screenshots.",
            "polish" to "Final touches.",
        )
        val SAMPLE_ANSWER = """
            Here's the short version, from the fake development server:

            **How it works.** The idea breaks into a few parts that each do one job:

            1. Something stores the state.
            2. Something reads it.
            3. A rule decides what happens next.

            Ask me to *show* it and I'll start a figure, or say *quiz me* for a quick check.
        """.trimIndent()
        val SAMPLE_QUIZ = """
            root = Quiz("Zippers", [q1, q2, q3])
            q1 = MultipleChoice("What holds two zipper teeth together?", ["Magnets", "A bump on one tooth seated in the hollow of the next", "Glue on the tape", "Friction alone"], 1, "Each tooth has a bump on top and a hollow underneath; the slider presses one into the other.")
            q2 = MultipleChoice("What does the slider's Y-shaped channel do?", ["Heats the teeth", "Guides the two rows together at the right angle", "Cuts loose threads", "Counts the teeth"], 1, "The channel brings the rows in at an angle so each bump can slip into the next hollow.")
            q3 = MultipleChoice("Why doesn't a closed zipper pull apart sideways?", ["The teeth are interlocked in a chain", "The tape is elastic", "The slider is still holding them", "Static electricity"], 0, "Once seated, every tooth is locked by its neighbours on both sides.")
        """.trimIndent()
    }
}
