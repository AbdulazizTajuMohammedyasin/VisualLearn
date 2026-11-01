package com.visuallearn.app.chat

import com.visuallearn.app.api.ApiException
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

/** The open conversation; replies stream from the server as message parts. */
class ChatRepository(private val api: VisualLearnApi, private val figures: FiguresRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _summaries = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val summaries: StateFlow<List<ConversationSummary>> = _summaries.asStateFlow()
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()
    private var streamJob: Job? = null

    fun refreshList() = scope.launch {
        runCatching { api.conversations() }.onSuccess { _summaries.value = it }
    }

    fun newChat() {
        stop()
        _state.value = ChatState()
    }

    fun open(id: String) {
        stop()
        _state.value = ChatState(conversationId = id, loading = true)
        scope.launch {
            runCatching { api.conversation(id) }
                .onSuccess { c ->
                    _state.value = ChatState(c.id, c.title, c.messages)
                    c.messages.flatMap { it.parts }.filterIsInstance<Part.Figure>().forEach { figures.watch(it.figureId) }
                    c.messages.lastOrNull { it.status == MessageStatus.STREAMING }?.let { follow(it.id) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.userMessage()) } }
        }
    }

    fun delete(id: String) = scope.launch {
        runCatching { api.deleteConversation(id) }.onSuccess {
            if (_state.value.conversationId == id) newChat()
            refreshList()
        }
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
            }
            is MessageEvent.Failed -> mutate(messageId) { it.copy(status = MessageStatus.FAILED, error = event.error) }
        }
    }

    private fun mutate(id: String, transform: (Message) -> Message) =
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) transform(it) else it }) }

    private fun replace(message: Message) = mutate(message.id) { message }

    private fun List<Part>.setAt(index: Int, part: Part): List<Part> =
        if (index < size) toMutableList().also { it[index] = part } else this + List(index - size) { Part.Text("") } + part

    private fun Throwable.userMessage() =
        (this as? ApiException)?.error?.message ?: "Couldn't reach VisualLearn. Check your connection."
}
