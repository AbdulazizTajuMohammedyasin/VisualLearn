package com.visuallearn.app.chat

import com.visuallearn.app.jobs.FigureJobs
import com.visuallearn.app.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversation: Conversation = Conversation(),
    val streamingText: String? = null,
    val pendingFigureId: String? = null,
    val error: String? = null,
) {
    val isStreaming get() = streamingText != null
}

/** The open conversation and its in-flight reply. */
class ChatController(
    private val store: ChatStore,
    private val jobs: FigureJobs,
    settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = ChatEngine { settings.apiKey }
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private var replyJob: Job? = null

    fun newChat() {
        stop()
        _state.value = ChatUiState()
    }

    fun open(id: String) {
        stop()
        store.load(id)?.let { _state.value = ChatUiState(conversation = it) }
    }

    fun delete(id: String) {
        if (_state.value.conversation.id == id) newChat()
        store.delete(id)
    }

    fun send(text: String, figureMode: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.isStreaming) return
        val user = ChatMessage(role = Role.USER, text = trimmed)
        update { c ->
            c.copy(
                title = if (c.messages.isEmpty()) trimmed.lineSequence().first().take(48) else c.title,
                messages = c.messages + user,
            )
        }
        respond(figureMode)
    }

    /** Re-runs the last reply after an error. */
    fun retry() {
        val messages = _state.value.conversation.messages
        if (_state.value.isStreaming || messages.isEmpty()) return
        if (messages.last().role == Role.ASSISTANT && messages.last().error != null) {
            update { it.copy(messages = it.messages.dropLast(1)) }
        }
        respond(figureMode = false)
    }

    fun stop() {
        engine.stop()
        replyJob?.cancel()
    }

    private fun respond(figureMode: Boolean) {
        _state.update { it.copy(streamingText = "", error = null) }
        replyJob = scope.launch {
            var figureJobId: String? = null
            val history = _state.value.conversation.messages
            val result = runCatching {
                engine.reply(
                    history = history,
                    figureMode = figureMode,
                    onText = { text -> _state.update { it.copy(streamingText = text) } },
                    onFigure = { request ->
                        val id = jobs.create(request.prompt, request.theme)
                        figureJobId = id
                        _state.update { it.copy(pendingFigureId = id) }
                        "Figure job $id started. It runs in the background on the phone; " +
                            "the user sees a progress card in this chat and gets a notification when it is ready."
                    },
                )
            }
            val reply = ChatMessage(
                role = Role.ASSISTANT,
                text = result.getOrNull() ?: _state.value.streamingText.orEmpty(),
                figureJobId = figureJobId,
                error = result.exceptionOrNull()?.let { it.message ?: "Something went wrong." },
            )
            update { it.copy(messages = it.messages + reply) }
            _state.update { it.copy(streamingText = null, pendingFigureId = null) }
        }
    }

    private fun update(transform: (Conversation) -> Conversation) {
        _state.update { s ->
            val c = transform(s.conversation).copy(updatedAt = System.currentTimeMillis())
            s.copy(conversation = c)
        }
        store.save(_state.value.conversation)
    }
}
