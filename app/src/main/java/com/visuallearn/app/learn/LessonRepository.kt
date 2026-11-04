package com.visuallearn.app.learn

import android.util.Log
import com.visuallearn.app.api.ApiException
import com.visuallearn.app.chat.MAX_PDF_BYTES
import com.visuallearn.app.chat.PdfAttachment
import com.visuallearn.app.figures.FiguresRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LessonState(
    val lesson: Lesson? = null,
    val loading: Boolean = false,
    /** Sending a message, an answer or a PDF. */
    val sending: Boolean = false,
    val error: String? = null,
    /** What the teacher's background work is doing, by the call that started it. */
    val activity: Map<String, String> = emptyMap(),
)

/**
 * The open lesson and the lesson list. Turns run on the server; this follows
 * them live while the screen is open and checks back while background work
 * (a figure, a demo, research) is still going, because LearnHarness wakes the
 * teacher on its own when that work finishes.
 */
class LessonRepository(private val api: LearnApi, private val figures: FiguresRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _lessons = MutableStateFlow<List<LessonSummary>>(emptyList())
    val lessons: StateFlow<List<LessonSummary>> = _lessons.asStateFlow()
    private val _state = MutableStateFlow(LessonState())
    val state: StateFlow<LessonState> = _state.asStateFlow()

    private var openId: String? = null
    private var record: JSONObject? = null
    private var follow: Job? = null
    private var watch: Job? = null

    fun refreshLessons() = scope.launch {
        runCatching { api.lessons() }.onSuccess { _lessons.value = it }.onFailure { Log.w(TAG, "lessons", it) }
    }

    /** Opens a lesson and keeps it current while it's open. */
    fun open(id: String) {
        if (openId == id && _state.value.lesson != null) return
        close()
        openId = id
        _state.value = LessonState(loading = true)
        scope.launch {
            reload(id)
            if (record?.optBoolean("turnInFlight") == true) followTurn(id)
        }
        watch = scope.launch { watchBackgroundWork(id) }
    }

    fun close() {
        follow?.cancel()
        watch?.cancel()
        openId = null
        record = null
        _state.value = LessonState()
    }

    /** Starts a lesson toward [goal], with a PDF the teacher can read if given; returns its id. */
    suspend fun start(goal: String, pdf: PdfAttachment? = null): String {
        pdf?.let { if (it.bytes > MAX_PDF_BYTES) throw IllegalArgumentException("${it.name} is too big. The limit is 25 MB.") }
        val title = goal.lineSequence().first().take(60).ifBlank { pdf?.name?.removeSuffix(".pdf") ?: "New lesson" }
        val id = api.createLesson(title)
        val text = if (pdf == null) goal else {
            val path = withContext(Dispatchers.IO) { api.uploadLessonFile(pdf.name, pdf.read()) }
            val ask = goal.ifBlank { "Teach me what's in it." }
            "I've attached my PDF at $path. $ask"
        }
        open(id)
        send(LessonInput.text(text))
        refreshLessons()
        return id
    }

    fun say(text: String) {
        if (text.isNotBlank()) send(LessonInput.text(text.trim()))
    }

    fun answer(question: LessonItem.Question, choice: AnswerChoice) = send(LessonInput.answer(question, choice))

    fun retry() = send(LessonInput.retry())

    fun delete(id: String) = scope.launch {
        runCatching { api.deleteLesson(id) }.onSuccess {
            if (openId == id) close()
            refreshLessons()
        }.onFailure { e -> _state.update { it.copy(error = e.userMessage()) } }
    }

    private fun send(input: JSONObject) {
        val id = openId ?: return
        _state.update { it.copy(sending = true, error = null) }
        scope.launch {
            runCatching { api.sendToLesson(id, input) }
                .onSuccess {
                    _state.update { it.copy(sending = false) }
                    followTurn(id)
                }
                .onFailure { e -> _state.update { it.copy(sending = false, error = e.userMessage()) } }
        }
    }

    /** Shows the teacher's message as it's written, then the settled record. */
    private fun followTurn(id: String) {
        follow?.cancel()
        follow = scope.launch {
            try {
                api.lessonEvents(id).collect { event ->
                    when (event.optString("type")) {
                        "message_start", "message_update" -> event.optJSONObject("message")
                            ?.takeIf { it.optString("role") == "assistant" }
                            ?.let { live -> show(id, live) }
                        // A message or a turn is complete: the record has it now.
                        "message_end", "turn_end" -> reload(id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "lesson stream", e)
            }
            reload(id)
        }
    }

    /** While background work runs, the teacher may be woken without the phone asking: check every few seconds. */
    private suspend fun watchBackgroundWork(id: String) {
        while (scope.isActive && openId == id) {
            delay(8_000)
            val lesson = _state.value.lesson ?: continue
            val working = lesson.items.filterIsInstance<LessonItem.Working>()
            if (working.isEmpty() && !lesson.busy) continue
            runCatching { api.lessonActivity(id) }.onSuccess { activity -> _state.update { it.copy(activity = activity) } }
            if (follow?.isActive != true) {
                reload(id)
                if (record?.optBoolean("turnInFlight") == true) followTurn(id)
            }
        }
    }

    private suspend fun reload(id: String) {
        runCatching { api.lessonRecord(id) }
            .onSuccess { r ->
                if (openId != id) return
                record = r
                show(id, null)
            }
            .onFailure { e -> if (openId == id) _state.update { it.copy(loading = false, error = e.userMessage()) } }
    }

    private fun show(id: String, live: JSONObject?) {
        val r = record ?: return
        if (openId != id) return
        val lesson = LessonJson.lesson(r, live)
        _state.update { it.copy(lesson = lesson, loading = false) }
        // Figures the teacher had built: make sure their cards have the latest state and preview.
        lesson.items.filterIsInstance<LessonItem.Figure>().mapNotNull { it.figureId }
            .filter { figures.figures.value[it] == null }
            .forEach { figures.watch(it) }
    }

    private fun Throwable.userMessage(): String =
        (this as? ApiException)?.error?.message ?: "Couldn't reach the lesson. Check your connection."

    private companion object {
        const val TAG = "VLLessons"
    }
}
