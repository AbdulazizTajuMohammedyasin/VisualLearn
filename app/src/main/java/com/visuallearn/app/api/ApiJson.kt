package com.visuallearn.app.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire format from docs/server-api.md. HttpApi reads it; the offline store
 * writes the same shapes so cached data reads back through the same parsers.
 */
internal object ApiJson {

    fun parseTokens(o: JSONObject) = Tokens(
        o.getString("accessToken"),
        java.time.Instant.parse(o.getString("accessTokenExpiresAt")).toEpochMilli(),
        o.getString("refreshToken"),
    )

    fun parseUser(o: JSONObject) = User(
        o.getString("id"), o.optString("displayName"), o.optStringOrNull("email"), o.optStringOrNull("avatarUrl"),
        o.optString("role", "user"),
    )

    fun parseSummary(o: JSONObject) = ConversationSummary(o.getString("id"), o.optString("title"), o.optString("updatedAt"))

    fun parseConversation(o: JSONObject) =
        Conversation(o.getString("id"), o.optString("title"), o.getJSONArray("messages").objects().map(::parseMessage))

    fun parseMessage(o: JSONObject) = Message(
        id = o.getString("id"),
        role = if (o.optString("role") == "user") Role.USER else Role.ASSISTANT,
        status = when (o.optString("status")) {
            "streaming" -> MessageStatus.STREAMING
            "failed" -> MessageStatus.FAILED
            else -> MessageStatus.COMPLETE
        },
        parts = o.optJSONArray("parts")?.objects()?.mapNotNull(::parsePart).orEmpty(),
        error = o.optJSONObject("error")?.let(::parseError),
    )

    fun parsePart(o: JSONObject): Part? = when (o.optString("type")) {
        "text" -> Part.Text(o.optString("text"))
        "ui" -> Part.Ui(o.optString("program"), o.optString("libraryVersion"))
        "figure" -> Part.Figure(o.getString("figureId"))
        else -> null
    }

    fun parseFigure(o: JSONObject): Figure {
        val pages = o.optJSONObject("pages")
        return Figure(
            id = o.getString("id"),
            prompt = o.optString("prompt"),
            status = runCatching { FigureStatus.valueOf(o.getString("status").uppercase()) }.getOrDefault(FigureStatus.QUEUED),
            queuePosition = if (o.isNull("queuePosition")) null else o.optInt("queuePosition"),
            estimatedStartAt = o.optStringOrNull("estimatedStartAt"),
            stage = o.optStringOrNull("stage"),
            message = o.optStringOrNull("message"),
            progress = if (o.isNull("progress")) null else o.optDouble("progress"),
            title = o.optStringOrNull("title"),
            summary = o.optStringOrNull("summary"),
            conversationId = o.optStringOrNull("conversationId"),
            createdAt = o.optString("createdAt"),
            updatedAt = o.optString("updatedAt"),
            startedAt = o.optStringOrNull("startedAt"),
            finishedAt = o.optStringOrNull("finishedAt"),
            error = o.optJSONObject("error")?.let(::parseError),
            darkPage = pages?.optJSONObject("dark")?.let(::parsePage),
            lightPage = pages?.optJSONObject("light")?.let(::parsePage),
            previewUrl = o.optStringOrNull("previewUrl"),
        )
    }

    fun parsePage(o: JSONObject) = Page(o.getString("url"), o.getString("sha256"), o.optLong("bytes"))

    fun parseError(o: JSONObject) =
        ApiError(o.optString("code", "error"), o.optString("message", "Something went wrong."), o.optBoolean("retryable"))

    fun parseSync(o: JSONObject) = SyncResult(
        conversations = o.optJSONArray("conversations")?.objects()?.map(::parseSummary).orEmpty(),
        deletedConversationIds = o.optJSONArray("deletedConversationIds")?.strings().orEmpty(),
        figures = o.optJSONArray("figures")?.objects()?.map(::parseFigure).orEmpty(),
        deletedFigureIds = o.optJSONArray("deletedFigureIds")?.strings().orEmpty(),
        cursor = o.getString("cursor"),
    )

    // --- writing (offline store) ---

    fun write(s: ConversationSummary): JSONObject =
        JSONObject().put("id", s.id).put("title", s.title).put("updatedAt", s.updatedAt)

    fun write(c: Conversation): JSONObject =
        JSONObject().put("id", c.id).put("title", c.title).put("messages", JSONArray(c.messages.map(::write)))

    fun write(m: Message): JSONObject = JSONObject()
        .put("id", m.id)
        .put("role", m.role.name.lowercase())
        .put("status", m.status.name.lowercase())
        .put("parts", JSONArray(m.parts.map(::write)))
        .put("error", m.error?.let(::write) ?: JSONObject.NULL)

    fun write(p: Part): JSONObject = when (p) {
        is Part.Text -> JSONObject().put("type", "text").put("text", p.text)
        is Part.Ui -> JSONObject().put("type", "ui").put("program", p.program).put("libraryVersion", p.libraryVersion)
        is Part.Figure -> JSONObject().put("type", "figure").put("figureId", p.figureId)
    }

    fun write(f: Figure): JSONObject = JSONObject()
        .put("id", f.id)
        .put("prompt", f.prompt)
        .put("status", f.status.name.lowercase())
        .putOpt("queuePosition", f.queuePosition)
        .putOpt("estimatedStartAt", f.estimatedStartAt)
        .putOpt("stage", f.stage)
        .putOpt("message", f.message)
        .putOpt("progress", f.progress)
        .putOpt("title", f.title)
        .putOpt("summary", f.summary)
        .putOpt("conversationId", f.conversationId)
        .put("createdAt", f.createdAt)
        .put("updatedAt", f.updatedAt)
        .putOpt("startedAt", f.startedAt)
        .putOpt("finishedAt", f.finishedAt)
        .putOpt("error", f.error?.let(::write))
        .putOpt("pages", if (f.darkPage == null && f.lightPage == null) null else
            JSONObject().putOpt("dark", f.darkPage?.let(::write)).putOpt("light", f.lightPage?.let(::write)))
        .putOpt("previewUrl", f.previewUrl)

    fun write(p: Page): JSONObject = JSONObject().put("url", p.url).put("sha256", p.sha256).put("bytes", p.bytes)

    fun write(e: ApiError): JSONObject =
        JSONObject().put("code", e.code).put("message", e.message).put("retryable", e.retryable)
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

internal fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
