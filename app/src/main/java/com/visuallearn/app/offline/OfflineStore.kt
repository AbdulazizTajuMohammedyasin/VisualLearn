package com.visuallearn.app.offline

import android.content.Context
import com.visuallearn.app.api.ApiJson
import com.visuallearn.app.api.Conversation
import com.visuallearn.app.api.ConversationSummary
import com.visuallearn.app.api.Figure
import com.visuallearn.app.api.objects
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The phone's copy of the user's chats and figure records, so they can be read without
 * a connection. Stored as the server's own JSON; figure pages live in FiguresRepository's cache.
 */
class OfflineStore(context: Context) {

    private val dir = File(context.filesDir, "offline")
    private val conversationsDir = File(dir, "conversations")
    private val summariesFile = File(dir, "conversations.json")
    private val figuresFile = File(dir, "figures.json")
    private val cursorFile = File(dir, "sync-cursor")

    @Synchronized
    fun summaries(): List<ConversationSummary> =
        read(summariesFile)?.let { JSONArray(it).objects().map(ApiJson::parseSummary) }.orEmpty()

    @Synchronized
    fun saveSummaries(list: List<ConversationSummary>) = write(summariesFile, JSONArray(list.map(ApiJson::write)).toString())

    @Synchronized
    fun conversation(id: String): Conversation? =
        read(conversationFile(id))?.let { runCatching { ApiJson.parseConversation(JSONObject(it)) }.getOrNull() }

    @Synchronized
    fun saveConversation(c: Conversation) = write(conversationFile(c.id), ApiJson.write(c).toString())

    @Synchronized
    fun deleteConversation(id: String) {
        conversationFile(id).delete()
        saveSummaries(summaries().filterNot { it.id == id })
    }

    @Synchronized
    fun figures(): List<Figure> =
        read(figuresFile)?.let { JSONArray(it).objects().map(ApiJson::parseFigure) }.orEmpty()

    @Synchronized
    fun saveFigures(list: Collection<Figure>) = write(figuresFile, JSONArray(list.map(ApiJson::write)).toString())

    /** Saved answers and card positions of one interactive message part (OpenUI), as JSON. */
    @Synchronized
    fun uiState(key: String): String? = read(uiStateFile(key))

    @Synchronized
    fun saveUiState(key: String, json: String) = write(uiStateFile(key), json)

    /** Where the next /sync continues from; null until the first sync. */
    var cursor: String?
        @Synchronized get() = read(cursorFile)
        @Synchronized set(value) {
            if (value == null) cursorFile.delete() else write(cursorFile, value)
        }

    @Synchronized
    fun clear() {
        dir.deleteRecursively()
    }

    private fun conversationFile(id: String) = File(conversationsDir, safe(id) + ".json")

    private fun uiStateFile(key: String) = File(File(dir, "ui-state"), safe(key) + ".json")

    // Ids come from the server; keep them to safe file names.
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun read(file: File): String? = file.takeIf { it.exists() }?.readText()

    /** Writes through a temporary file so a crash never leaves half a file behind. */
    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
