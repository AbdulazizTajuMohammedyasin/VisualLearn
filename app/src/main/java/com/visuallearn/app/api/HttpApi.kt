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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
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
) : VisualLearnApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val streamClient = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

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

    // --- transport ---

    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        idempotent: Boolean = false,
        raw: RequestBody? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val idempotencyKey = if (idempotent) UUID.randomUUID().toString() else null
        repeat(2) { attempt ->
            client.newCall(request(method, path, raw ?: body?.toString()?.toRequestBody(JSON), auth, idempotencyKey)).execute().use { response ->
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
        val error = runCatching { parseError(JSONObject(text).getJSONObject("error")) }.getOrElse {
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
