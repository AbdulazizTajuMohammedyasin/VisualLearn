package com.visuallearn.app.api

/** Types mirroring docs/server-api.md. */

data class User(val id: String, val displayName: String, val email: String?, val avatarUrl: String?, val role: String)

data class Tokens(val accessToken: String, val accessTokenExpiresAt: Long, val refreshToken: String)

data class ConversationSummary(val id: String, val title: String, val updatedAt: String)

enum class Role { USER, ASSISTANT }

enum class MessageStatus { STREAMING, COMPLETE, FAILED }

sealed interface Part {
    data class Text(val text: String) : Part
    data class Ui(val program: String, val libraryVersion: String) : Part
    data class Figure(val figureId: String) : Part
}

data class Message(
    val id: String,
    val role: Role,
    val status: MessageStatus,
    val parts: List<Part>,
    val error: ApiError? = null,
)

data class Conversation(val id: String, val title: String, val messages: List<Message>)

/** Sent with a message when an OpenUI component asks for a follow-up (@ToAssistant). */
data class UiAction(val sourceMessageId: String, val name: String, val context: Map<String, Any?>)

enum class SendMode { AUTO, FIGURE }

sealed interface MessageEvent {
    data class PartStart(val index: Int, val part: Part) : MessageEvent
    data class TextDelta(val index: Int, val text: String) : MessageEvent
    data class UiDelta(val index: Int, val program: String) : MessageEvent
    data class Done(val message: Message) : MessageEvent
    data class Failed(val error: ApiError) : MessageEvent
}

enum class FigureStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;
    val isActive get() = this == QUEUED || this == RUNNING
}

data class Page(val url: String, val sha256: String, val bytes: Long)

data class Figure(
    val id: String,
    val prompt: String,
    val status: FigureStatus,
    /** 1 = next to start; set only while queued. */
    val queuePosition: Int?,
    /** Server's estimate, recomputed as capacity changes; set only while queued. */
    val estimatedStartAt: String?,
    val stage: String?,
    val message: String?,
    val progress: Double?,
    val title: String?,
    val summary: String?,
    val conversationId: String?,
    val createdAt: String,
    val updatedAt: String,
    val startedAt: String?,
    val finishedAt: String?,
    val error: ApiError?,
    val darkPage: Page?,
    val lightPage: Page?,
    val previewUrl: String?,
)

/** The server's error body; [message] is safe to show to the user. */
data class ApiError(val code: String, val message: String, val retryable: Boolean)

class ApiException(val error: ApiError, val httpStatus: Int = 0) : Exception(error.message)
