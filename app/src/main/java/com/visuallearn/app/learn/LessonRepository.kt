package com.visuallearn.app.learn

import android.util.Log
import com.visuallearn.app.api.ApiException
import com.visuallearn.app.chat.MAX_PDF_BYTES
import com.visuallearn.app.chat.PdfAttachment
import com.visuallearn.app.figures.FiguresRepository
import com.visuallearn.app.offline.OfflineStore
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
import org.json.JSONArray
import org.json.JSONObject

data class LessonState(
    val lesson: Lesson? = null,
    val loading: Boolean = false,
    /** Sending a message, an answer or a PDF. */
    val sending: Boolean = false,
    val error: String? = null,
    /** What the teacher's background work is doing, by the call that started it. */
    val activity: Map<String, JobProgress> = emptyMap(),
    /** Showing the copy kept on the phone: the server couldn't be reached. */
    val offline: Boolean = false,
)

/**
 * The open lesson and the lesson list. Turns run on the server; this follows
 * them live while the screen is open and checks back while background work
 * (a figure, a demo, research) is still going, because LearnHarness wakes the
 * teacher on its own when that work finishes.
 */
class LessonRepository(private val api: LearnApi, private val figures: FiguresRepository, private val store: OfflineStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _lessons = MutableStateFlow<List<LessonSummary>>(emptyList())
    val lessons: StateFlow<List<LessonSummary>> = _lessons.asStateFlow()
    private val _state = MutableStateFlow(LessonState())
    val state: StateFlow<LessonState> = _state.asStateFlow()

    private var openId: String? = null
    private var record: JSONObject? = null
    private var follow: Job? = null
    private var watch: Job? = null

    init {
        scope.launch { if (_lessons.value.isEmpty()) _lessons.value = savedLessons() }
    }

    fun refreshLessons() = scope.launch {
        runCatching { api.lessons() }
            .onSuccess { list ->
                _lessons.value = list
                store.saveLessonList(JSONArray(list.map(LessonJson::write)).toString())
            }
            .onFailure { Log.w(TAG, "lessons", it) }
    }

    private fun savedLessons(): List<LessonSummary> =
        runCatching { store.lessonList()?.let { JSONArray(it) }?.let { a -> (0 until a.length()).map { LessonJson.summary(a.getJSONObject(it)) } } }
            .getOrNull().orEmpty()

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

    /** Forgets the lesson list (sign-out); the files go with the offline store. */
    fun clear() {
        close()
        _lessons.value = emptyList()
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
        // The PDF goes up first, so a failed upload doesn't leave an empty lesson behind.
        val input = if (pdf == null) LessonInput.text(goal) else {
            val path = withContext(Dispatchers.IO) { api.uploadLessonFile(pdf.name, pdf.read()) }
            LessonInput.withPdf(path, goal.ifBlank { "Teach me what's in it." })
        }
        val id = api.createLesson(title)
        open(id)
        send(input)
        refreshLessons()
        return id
    }

    /** Starts practising [ideas] (a topic of what the learner knows); returns the session's id. */
    suspend fun practise(topic: String, ideas: List<KnowledgeIdea>): String {
        val id = api.createLesson("Practice: $topic".take(72), practice = true, ideas = ideas)
        open(id)
        send(LessonInput.text("Give me practice problems on $topic."))
        refreshLessons()
        return id
    }

    fun say(text: String) {
        if (text.isNotBlank()) send(LessonInput.text(text.trim()))
    }

    fun answer(question: LessonItem.Question, choice: AnswerChoice) = send(LessonInput.answer(question, choice))

    fun retry() = send(LessonInput.retry())

    /** The learner's working or answer on the open practice problem. */
    fun work(exercise: LessonItem.Exercise, text: String) {
        if (text.isNotBlank()) send(LessonInput.thinking(exercise.callId, text.trim()))
    }

    fun nextProblem() = send(LessonInput.next())

    fun delete(id: String) = scope.launch {
        runCatching { api.deleteLesson(id) }.onSuccess {
            store.deleteLesson(id)
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
                _state.update { it.copy(offline = false) }
                show(id, null)
                withContext(Dispatchers.IO) { store.saveLesson(id, r.toString()) }
            }
            .onFailure { e ->
                if (openId != id) return
                // Out of reach: the copy kept on the phone still reads, though the lesson can't go on.
                val saved = if (record == null) store.lesson(id)?.let { runCatching { JSONObject(it) }.getOrNull() } else null
                if (saved != null) record = saved
                _state.update { it.copy(loading = false, offline = record != null, error = if (record != null) null else e.userMessage()) }
                if (saved != null) show(id, null)
            }
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
