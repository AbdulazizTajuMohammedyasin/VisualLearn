package com.visuallearn.app.learn

import android.util.Log
import com.visuallearn.app.api.ApiException
import com.visuallearn.app.chat.MAX_PDF_BYTES
import com.visuallearn.app.chat.PdfAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KnowledgeState(
    val knowledge: Knowledge? = null,
    val loading: Boolean = false,
    /** The notes file being read in, while it is. */
    val importing: String? = null,
    /** What the last import did, until it's dismissed. */
    val imported: NotesImport? = null,
    val error: String? = null,
)

/** What the learner knows, and reading notes into it. */
class KnowledgeRepository(private val api: LearnApi) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(KnowledgeState())
    val state: StateFlow<KnowledgeState> = _state.asStateFlow()

    fun refresh() = scope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { api.knowledge() }
            .onSuccess { k -> _state.update { it.copy(knowledge = k, loading = false) } }
            .onFailure { e ->
                Log.w(TAG, "knowledge", e)
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
    }

    /** Reads [notes] into the graph; the extractor takes a minute or more. */
    fun import(notes: PdfAttachment) {
        if (_state.value.importing != null) return
        if (notes.bytes > MAX_PDF_BYTES) {
            _state.update { it.copy(imported = NotesImport(notes.name, false, 0, "${notes.name} is too big. The limit is 25 MB.")) }
            return
        }
        _state.update { it.copy(importing = notes.name, imported = null) }
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { api.importNotes(notes.name, notes.read()) } }
                .getOrElse { e -> NotesImport(notes.name, false, 0, e.userMessage()) }
            _state.update { it.copy(importing = null, imported = result) }
            refresh()
        }
    }

    fun dismissImport() = _state.update { it.copy(imported = null) }

    private fun Throwable.userMessage(): String =
        (this as? ApiException)?.error?.message ?: "Couldn't reach the server. Check your connection."

    private companion object {
        const val TAG = "VLKnowledge"
    }
}
