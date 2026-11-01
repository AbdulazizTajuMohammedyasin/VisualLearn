package com.visuallearn.app.chat

import com.visuallearn.app.api.ApiException
import com.visuallearn.app.api.Conversation
import com.visuallearn.app.api.ConversationSummary
import com.visuallearn.app.api.Message
import com.visuallearn.app.api.MessageEvent
import com.visuallearn.app.api.MessageStatus
import com.visuallearn.app.api.Part
import com.visuallearn.app.api.Role
import com.visuallearn.app.api.SendMode
import com.visuallearn.app.api.UiAction
import com.visuallearn.app.api.VisualLearnApi
import com.visuallearn.app.figures.FiguresRepository
import com.visuallearn.app.offline.OfflineStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatState(
    val conversationId: String? = null,
    val title: String = "",
    val messages: List<Message> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val isStreaming get() = messages.any { it.status == MessageStatus.STREAMING }
}

/**
 * The open conversation; replies stream from the server as message parts. Finished
 * conversations are kept in [store] so they can be read offline.
 */
class ChatRepository(
    private val api: VisualLearnApi,
    private val figures: FiguresRepository,
    private val store: OfflineStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _summaries = MutableStateFlow(store.summaries())
    val summaries: StateFlow<List<ConversationSummary>> = _summaries.asStateFlow()
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()
    private var streamJob: Job? = null

    fun refreshList() = scope.launch {
        runCatching { api.conversations() }.onSuccess {
            _summaries.value = it
            store.saveSummaries(it)
        }
    }

    fun newChat() {
        stop()
        _state.value = ChatState()
    }

    /** Shows the saved copy at once, then the server's latest when there is a connection. */
    fun open(id: String) {
        stop()
        val saved = store.conversation(id)
        _state.value = saved?.let { ChatState(it.id, it.title, it.messages, loading = true) }
            ?: ChatState(conversationId = id, loading = true)
        scope.launch {
            runCatching { api.conversation(id) }
                .onSuccess { c ->
                    if (_state.value.conversationId != id) return@onSuccess
                    _state.value = ChatState(c.id, c.title, c.messages)
                    save()
                    c.messages.flatMap { it.parts }.filterIsInstance<Part.Figure>().forEach { figures.watch(it.figureId) }
                    c.messages.lastOrNull { it.status == MessageStatus.STREAMING }?.let { follow(it.id) }
                }
                .onFailure { e ->
                    if ((e as? ApiException)?.httpStatus == 404) removeLocal(id)
                    // Offline with a saved copy: just show it.
                    val error = if (saved != null && e !is ApiException) null else e.userMessage()
                    _state.update { if (it.conversationId == id) it.copy(loading = false, error = error) else it }
                }
        }
    }

    fun delete(id: String) = scope.launch {
        runCatching { api.deleteConversation(id) }
            .onSuccess { removeLocal(id) }
            .onFailure { e -> _state.update { it.copy(error = e.userMessage()) } }
    }

    /** A conversation changed on the server (another device, or a reply that finished). */
    suspend fun applyRemote(summary: ConversationSummary) {
        _summaries.update { list -> (listOf(summary) + list.filterNot { it.id == summary.id }).sortedByDescending { it.updatedAt } }
        store.saveSummaries(_summaries.value)
        val open = _state.value
        if (open.conversationId == summary.id && open.isStreaming) return
        runCatching { api.conversation(summary.id) }.onSuccess { c ->
            store.saveConversation(c)
            _state.update { if (it.conversationId == c.id && !it.isStreaming) it.copy(title = c.title, messages = c.messages) else it }
        }
    }

    /** Removes a conversation from the phone, e.g. after it was deleted on the server. */
    fun removeLocal(id: String) {
        store.deleteConversation(id)
        _summaries.update { list -> list.filterNot { it.id == id } }
        if (_state.value.conversationId == id) newChat()
    }

    /** Forgets everything on sign-out. */
    fun clear() {
        stop()
        _summaries.value = emptyList()
        _state.value = ChatState()
    }

    fun send(text: String, mode: SendMode, uiAction: UiAction? = null) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && uiAction == null) || _state.value.isStreaming) return
        scope.launch {
            _state.update { it.copy(error = null) }
            runCatching {
                val conversationId = _state.value.conversationId ?: api.createConversation().id.also { id ->
                    _state.update { it.copy(conversationId = id, title = trimmed.lineSequence().first().take(48)) }
                }
                val (user, assistant) = api.sendMessage(conversationId, trimmed, mode, uiAction)
                _state.update { it.copy(messages = it.messages + user + assistant) }
                refreshList()
                follow(assistant.id)
            }.onFailure { e -> _state.update { it.copy(error = e.userMessage()) } }
        }
    }

    fun stop() {
        val streaming = _state.value.messages.lastOrNull { it.status == MessageStatus.STREAMING }
        streamJob?.cancel()
        streamJob = null
        if (streaming != null) {
            scope.launch { runCatching { api.stopMessage(streaming.id) } }
            replace(streaming.copy(status = MessageStatus.COMPLETE))
        }
    }

    /** Resends the last user message after a failure. */
    fun retry() {
        val lastUser = _state.value.messages.lastOrNull { it.role == Role.USER } ?: return
        val text = lastUser.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        _state.update { s -> s.copy(messages = s.messages.dropLastWhile { it.role == Role.ASSISTANT && it.status == MessageStatus.FAILED }) }
        send(text, SendMode.AUTO)
    }

    private fun follow(messageId: String) {
        streamJob?.cancel()
        streamJob = scope.launch {
            runCatching {
                api.messageEvents(messageId).collect { event -> apply(messageId, event) }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                mutate(messageId) { it.copy(status = MessageStatus.FAILED) }
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    private fun apply(messageId: String, event: MessageEvent) {
        when (event) {
            is MessageEvent.PartStart -> {
                (event.part as? Part.Figure)?.let { figures.watch(it.figureId) }
                mutate(messageId) { m -> m.copy(parts = m.parts.setAt(event.index, event.part)) }
            }
            is MessageEvent.TextDelta -> mutate(messageId) { m ->
                val current = m.parts.getOrNull(event.index) as? Part.Text ?: Part.Text("")
                m.copy(parts = m.parts.setAt(event.index, Part.Text(current.text + event.text)))
            }
            is MessageEvent.UiDelta -> mutate(messageId) { m ->
                val current = m.parts.getOrNull(event.index) as? Part.Ui ?: Part.Ui("", "")
                m.copy(parts = m.parts.setAt(event.index, current.copy(program = current.program + event.program)))
            }
            is MessageEvent.Done -> {
                event.message.parts.filterIsInstance<Part.Figure>().forEach { figures.watch(it.figureId) }
                replace(event.message)
                save()
            }
            is MessageEvent.Failed -> {
                mutate(messageId) { it.copy(status = MessageStatus.FAILED, error = event.error) }
                save()
            }
        }
    }

    private fun save() {
        val s = _state.value
        val id = s.conversationId ?: return
        store.saveConversation(Conversation(id, s.title, s.messages))
    }

    private fun mutate(id: String, transform: (Message) -> Message) =
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) transform(it) else it }) }

    private fun replace(message: Message) = mutate(message.id) { message }

    private fun List<Part>.setAt(index: Int, part: Part): List<Part> =
        if (index < size) toMutableList().also { it[index] = part } else this + List(index - size) { Part.Text("") } + part

    private fun Throwable.userMessage() =
        (this as? ApiException)?.error?.message ?: "Couldn't reach VisualLearn. Check your connection."
}
