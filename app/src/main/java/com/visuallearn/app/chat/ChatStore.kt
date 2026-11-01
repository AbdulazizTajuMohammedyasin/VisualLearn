package com.visuallearn.app.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val figureJobId: String? = null,
    val error: String? = null,
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val messages: List<ChatMessage> = emptyList(),
)

data class ConversationSummary(val id: String, val title: String, val updatedAt: Long)

/** Conversations as one JSON file each under filesDir/chats. */
class ChatStore(context: Context) {

    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    private val _summaries = MutableStateFlow(scan())
    val summaries: StateFlow<List<ConversationSummary>> = _summaries.asStateFlow()

    fun load(id: String): Conversation? = runCatching { decode(JSONObject(File(dir, "$id.json").readText())) }.getOrNull()

    fun save(conversation: Conversation) {
        val file = File(dir, "${conversation.id}.json")
        val tmp = File(dir, "${conversation.id}.json.tmp")
        tmp.writeText(encode(conversation).toString())
        tmp.renameTo(file)
        _summaries.value = scan()
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        _summaries.value = scan()
    }

    private fun scan(): List<ConversationSummary> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                ConversationSummary(o.getString("id"), o.getString("title"), o.getLong("updatedAt"))
            }.getOrNull()
        }.sortedByDescending { it.updatedAt }

    private fun encode(c: Conversation) = JSONObject()
        .put("id", c.id).put("title", c.title).put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)
        .put("messages", JSONArray().apply {
            c.messages.forEach { m ->
                put(JSONObject().put("id", m.id).put("role", m.role.name).put("text", m.text)
                    .putOpt("figureJobId", m.figureJobId).putOpt("error", m.error))
            }
        })

    private fun decode(o: JSONObject): Conversation {
        val arr = o.getJSONArray("messages")
        val messages = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            ChatMessage(
                id = m.getString("id"),
                role = Role.valueOf(m.getString("role")),
                text = m.getString("text"),
                figureJobId = m.optString("figureJobId").takeIf { it.isNotEmpty() },
                error = m.optString("error").takeIf { it.isNotEmpty() },
            )
        }
        return Conversation(o.getString("id"), o.getString("title"), o.getLong("createdAt"), o.getLong("updatedAt"), messages)
    }
}
