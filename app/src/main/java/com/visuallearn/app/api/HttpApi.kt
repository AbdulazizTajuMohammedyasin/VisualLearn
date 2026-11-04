package com.visuallearn.app.api

import com.visuallearn.app.api.ApiJson.parseConversation
import com.visuallearn.app.api.ApiJson.parseError
import com.visuallearn.app.api.ApiJson.parseFigure
import com.visuallearn.app.api.ApiJson.parseMessage
import com.visuallearn.app.api.ApiJson.parsePart
import com.visuallearn.app.api.ApiJson.parseSummary
import com.visuallearn.app.api.ApiJson.parseSync
import com.visuallearn.app.api.ApiJson.parseTokens
import com.visuallearn.app.api.ApiJson.parseUser
import com.visuallearn.app.auth.TokenStore
import com.visuallearn.app.learn.JobProgress
import com.visuallearn.app.learn.Knowledge
import com.visuallearn.app.learn.KnowledgeIdea
import com.visuallearn.app.learn.KnowledgeJson
import com.visuallearn.app.learn.LearnApi
import com.visuallearn.app.learn.LessonJson
import com.visuallearn.app.learn.LessonSummary
import com.visuallearn.app.learn.NotesImport
import com.visuallearn.app.learn.ProviderErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** [VisualLearnApi] over HTTPS, following docs/server-api.md. */
class HttpApi(
    private val baseUrl: String,
    private val tokens: TokenStore,
    private val appVersion: String,
    private val uiLibraryVersion: String,
) : VisualLearnApi, LearnApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val streamClient = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    private val slowClient = client.newBuilder().readTimeout(10, TimeUnit.MINUTES).build()

    override suspend fun signInWithGoogle(idToken: String): Tokens =
        session(JSONObject().put("provider", "google").put("idToken", idToken))

    override suspend fun startEmailSignIn(email: String) {
        call("POST", "/auth/email/start", JSONObject().put("email", email), auth = false)
    }

    override suspend fun signInWithEmail(email: String, code: String): Tokens =
        session(JSONObject().put("provider", "email").put("email", email).put("code", code))

    private suspend fun session(body: JSONObject): Tokens =
        parseTokens(call("POST", "/auth/session", body, auth = false)).also(tokens::save)

    override suspend fun deleteAccount() {
        call("DELETE", "/me")
        tokens.clear()
    }

    override suspend fun signOut() {
        val refresh = tokens.current()?.refreshToken
        tokens.clear()
        if (refresh != null) runCatching { call("DELETE", "/auth/session", JSONObject().put("refreshToken", refresh), auth = false) }
    }

    override suspend fun me(): User = parseUser(call("GET", "/me").getJSONObject("user"))

    override suspend fun conversations(): List<ConversationSummary> =
        call("GET", "/conversations?limit=100").getJSONArray("items").objects().map(::parseSummary)

    override suspend fun createConversation(): ConversationSummary =
        parseSummary(call("POST", "/conversations", JSONObject()))

    override suspend fun conversation(id: String): Conversation {
        return parseConversation(call("GET", "/conversations/$id"))
    }

    override suspend fun deleteConversation(id: String) {
        call("DELETE", "/conversations/$id")
    }

    override suspend fun uploadDocument(conversationId: String, name: String, pdf: ByteArray): UploadedDocument {
        val path = "/conversations/$conversationId/documents?name=" + java.net.URLEncoder.encode(name, "UTF-8")
        val o = call("POST", path, raw = pdf.toRequestBody(PDF))
        return UploadedDocument(o.getString("id"), o.getString("name"), o.getInt("pages"), o.getLong("bytes"))
    }

    override suspend fun sendMessage(
        conversationId: String,
        text: String,
        mode: SendMode,
        uiAction: UiAction?,
        documentIds: List<String>,
    ): Pair<Message, Message> {
        val body = JSONObject()
            .put("text", text)
            .put("mode", mode.name.lowercase())
            .put("uiAction", uiAction?.let {
                JSONObject().put("sourceMessageId", it.sourceMessageId).put("name", it.name).put("context", JSONObject(it.context))
            } ?: JSONObject.NULL)
            .put("documentIds", JSONArray(documentIds))
        val o = call("POST", "/conversations/$conversationId/messages", body, idempotent = true)
        return parseMessage(o.getJSONObject("userMessage")) to parseMessage(o.getJSONObject("assistantMessage"))
    }

    override fun messageEvents(messageId: String): Flow<MessageEvent> = sse("/messages/$messageId/events") { event, data ->
        when (event) {
            "part.start" -> MessageEvent.PartStart(data.getInt("partIndex"), parsePart(data) ?: Part.Text(""))
            "text.delta" -> MessageEvent.TextDelta(data.getInt("partIndex"), data.getString("text"))
            "ui.delta" -> MessageEvent.UiDelta(data.getInt("partIndex"), data.getString("program"))
            "message.done" -> MessageEvent.Done(parseMessage(data.getJSONObject("message")))
            "message.error" -> MessageEvent.Failed(parseError(data.getJSONObject("error")))
            else -> null
        }
    }.transformWhile { event ->
        emit(event)
        event !is MessageEvent.Done && event !is MessageEvent.Failed
    }

    override suspend fun stopMessage(messageId: String) {
        call("POST", "/messages/$messageId/stop", JSONObject())
    }

    override suspend fun sync(since: String?): SyncResult =
        parseSync(call("GET", "/sync" + (since?.let { "?since=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")))

    override suspend fun figures(): List<Figure> =
        call("GET", "/figures?limit=100").getJSONArray("items").objects().map(::parseFigure)

    override suspend fun figure(id: String): Figure = parseFigure(call("GET", "/figures/$id"))

    override suspend fun createFigure(prompt: String, theme: String, conversationId: String?): Figure {
        val body = JSONObject().put("prompt", prompt).put("theme", theme).put("conversationId", conversationId ?: JSONObject.NULL)
        return parseFigure(call("POST", "/figures", body, idempotent = true))
    }

    override fun figureEvents(id: String): Flow<Figure> = sse("/figures/$id/events") { event, data ->
        if (event == "figure.update") parseFigure(data.optJSONObject("figure") ?: data) else null
    }

    override suspend fun cancelFigure(id: String): Figure = parseFigure(call("POST", "/figures/$id/cancel", JSONObject()))

    override suspend fun retryFigure(id: String): Figure = parseFigure(call("POST", "/figures/$id/retry", JSONObject()))

    override suspend fun deleteFigure(id: String) {
        call("DELETE", "/figures/$id")
    }

    override suspend fun downloadPage(page: Page): ByteArray = download(page.url)

    override suspend fun downloadPreview(url: String): ByteArray = download(url)

    private suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(ApiError("download_failed", "Couldn't download the figure.", true), response.code)
            response.body!!.bytes()
        }
    }

    // --- lessons (LearnHarness through /v1/learn) ---

    override suspend fun lessons(): List<LessonSummary> =
        call("GET", "/learn/api/chats").getJSONArray("chats").objects().map(LessonJson::summary)

    override suspend fun createLesson(title: String, practice: Boolean, ideas: List<KnowledgeIdea>): String {
        val body = JSONObject().put("title", title).apply { if (practice) put("kind", "practice") }
        // The practice session's material: the picked ideas as the learner saw them.
        if (ideas.isNotEmpty()) {
            val picks = JSONArray(ideas.map { JSONObject().put("id", it.id).put("name", it.name).put("statement", it.statement) })
            body.put("graphContext", JSONObject().put("propositions", picks))
        }
        return call("POST", "/learn/api/chats", body).getString("id")
    }

    override suspend fun lessonRecord(id: String): JSONObject = call("GET", "/learn/api/chats/$id")

    override suspend fun sendToLesson(id: String, input: JSONObject) {
        call("POST", "/learn/api/chat", JSONObject().put("chatId", id).put("input", input))
    }

    override suspend fun lessonActivity(id: String): Map<String, JobProgress> =
        call("GET", "/learn/api/jobs?chatId=" + java.net.URLEncoder.encode(id, "UTF-8")).optJSONArray("jobs")?.objects()
            ?.associate { job ->
                job.optString("toolCallId") to
                    JobProgress(job.optString("status"), job.optString("activity"), job.optString("error").takeIf { it.isNotBlank() && it != "null" })
            }
            .orEmpty()

    override suspend fun deleteLesson(id: String) {
        // What the lesson taught stays in the learner's knowledge graph.
        call("DELETE", "/learn/api/chats/$id?mode=preserve-graph")
    }

    override suspend fun knowledge(): Knowledge = KnowledgeJson.knowledge(call("GET", "/learn/api/graph"))

    override suspend fun importNotes(name: String, bytes: ByteArray): NotesImport {
        val type = if (name.endsWith(".pdf", ignoreCase = true)) PDF else "text/plain".toMediaType()
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("files", name, bytes.toRequestBody(type)).build()
        val response = call("POST", "/learn/api/graph/import", raw = form, slow = true)
        val result = response.optJSONArray("results")?.optJSONObject(0)
            ?: return NotesImport(name, false, 0, response.optString("error").ifBlank { "Nothing was imported." })
        return NotesImport(
            name = name,
            ok = result.optBoolean("ok"),
            ideas = result.optJSONObject("stats")?.optInt("nodes") ?: 0,
            // LearnHarness warns about scanner errors for every PDF; only skipped pages are worth saying.
            message = if (result.optBoolean("ok")) result.optString("notice").removePrefix("Scanned pages may have errors.").trim().ifBlank { null }
            else result.optString("error").let { ProviderErrors.known(it) ?: ProviderErrors.message(it).take(300) }.ifBlank { null },
        )
    }

    override suspend fun uploadLessonFile(name: String, pdf: ByteArray): String {
        val path = "/learn/files?name=" + java.net.URLEncoder.encode(name, "UTF-8")
        return call("POST", path, raw = pdf.toRequestBody(PDF)).getString("path")
    }

    /** LearnHarness's turn stream: plain `data:` lines of agent events, ending with `[DONE]`. */
    override fun lessonEvents(id: String): Flow<JSONObject> = flow {
        repeat(2) { attempt ->
            val builder = Request.Builder().url(baseUrl.trimEnd('/') + "/learn/api/chats/$id/stream").header("Accept", "text/event-stream")
            tokens.current()?.let { builder.header("Authorization", "Bearer ${it.accessToken}") }
            streamClient.newCall(builder.build()).execute().use { response ->
                if (response.code == 401 && attempt == 0 && refresh()) return@use
                if (!response.isSuccessful) parse(response)
                val source = response.body!!.source()
                while (true) {
                    val line = source.readUtf8Line() ?: return@flow
                    if (!line.startsWith("data:")) continue
                    val data = line.substringAfter(':').trim()
                    if (data == "[DONE]") return@flow
                    runCatching { JSONObject(data) }.getOrNull()?.let { emit(it) }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // --- transport ---

    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        idempotent: Boolean = false,
        raw: RequestBody? = null,
        /** For calls that wait on model work, like a notes import. */
        slow: Boolean = false,
    ): JSONObject = withContext(Dispatchers.IO) {
        val idempotencyKey = if (idempotent) UUID.randomUUID().toString() else null
        repeat(2) { attempt ->
            (if (slow) slowClient else client).newCall(request(method, path, raw ?: body?.toString()?.toRequestBody(JSON), auth, idempotencyKey)).execute().use { response ->
                // One retry after refreshing an expired access token.
                if (response.code == 401 && auth && attempt == 0 && refresh()) return@use
                return@withContext parse(response)
            }
        }
        throw ApiException(ApiError("unauthenticated", "Please sign in again.", false), 401)
    }

    private fun request(method: String, path: String, requestBody: RequestBody?, auth: Boolean, idempotencyKey: String?): Request {
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("X-App-Version", appVersion)
            .header("X-UI-Library-Version", uiLibraryVersion)
        if (auth) tokens.current()?.let { builder.header("Authorization", "Bearer ${it.accessToken}") }
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        return builder.method(method, if (method == "GET") null else requestBody ?: "{}".toRequestBody(JSON)).build()
    }

    private fun parse(response: Response): JSONObject {
        val text = response.body?.string().orEmpty()
        if (response.isSuccessful) return if (text.isBlank()) JSONObject() else JSONObject(text)
        val error = runCatching {
            // Lessons (LearnHarness) answer `{ "error": "message" }`; this server answers an error object.
            when (val e = JSONObject(text).get("error")) {
                is String -> ApiError("http_${response.code}", e, response.code >= 500 || response.code == 409)
                else -> parseError(e as JSONObject)
            }
        }.getOrElse {
            ApiError("http_${response.code}", "The server returned an error (${response.code}).", response.code >= 500)
        }
        throw ApiException(error, response.code)
    }

    /** Exchanges the refresh token; returns false if the session is over. */
    private fun refresh(): Boolean {
        val current = tokens.current() ?: return false
        val body = JSONObject().put("refreshToken", current.refreshToken).toString().toRequestBody(JSON)
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/auth/refresh").post(body).build()
        return runCatching {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) { tokens.clear(); return false }
                tokens.save(parseTokens(JSONObject(r.body!!.string())))
                true
            }
        }.getOrDefault(false)
    }

    /** Server-Sent Events with reconnection from the last seen event id. */
    private fun <T : Any> sse(path: String, map: (String, JSONObject) -> T?): Flow<T> = flow {
        var lastEventId: String? = null
        var attempts = 0
        while (true) {
            val builder = Request.Builder().url(baseUrl.trimEnd('/') + path).header("Accept", "text/event-stream")
            tokens.current()?.let { builder.header("Authorization", "Bearer ${it.accessToken}") }
            lastEventId?.let { builder.header("Last-Event-ID", it) }
            try {
                streamClient.newCall(builder.build()).execute().use { response ->
                    if (response.code == 401 && refresh()) return@use
                    if (!response.isSuccessful) parse(response)
                    attempts = 0
                    val source = response.body!!.source()
                    var event = "message"
                    val data = StringBuilder()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) map(event, JSONObject(data.toString()))?.let { emit(it) }
                                event = "message"; data.clear()
                            }
                            line.startsWith("event:") -> event = line.substringAfter(':').trim()
                            line.startsWith("data:") -> data.append(line.substringAfter(':').trimStart())
                            line.startsWith("id:") -> lastEventId = line.substringAfter(':').trim()
                        }
                    }
                }
                return@flow // the server closed the stream: finished
            } catch (e: IOException) {
                if (++attempts > 5) throw e
                kotlinx.coroutines.delay(1000L * attempts)
            }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val PDF = "application/pdf".toMediaType()
    }
}
