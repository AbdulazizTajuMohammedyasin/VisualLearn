package com.visuallearn.app.api

import kotlinx.coroutines.flow.Flow

/**
 * The VisualLearn server API (docs/server-api.md). Debug builds use an in-process
 * fake; release builds talk HTTP to the configured server.
 */
interface VisualLearnApi {
    // Auth
    suspend fun signInWithGoogle(idToken: String): Tokens

    /** Emails a 6-digit sign-in code. */
    suspend fun startEmailSignIn(email: String)
    suspend fun signInWithEmail(email: String, code: String): Tokens
    suspend fun signOut()
    suspend fun me(): User

    /** Deletes the account and all its data, then signs out. */
    suspend fun deleteAccount()

    // Chat
    suspend fun conversations(): List<ConversationSummary>
    suspend fun createConversation(): ConversationSummary
    suspend fun conversation(id: String): Conversation
    suspend fun deleteConversation(id: String)

    /** Returns the stored user message and the assistant message that will stream. */
    suspend fun sendMessage(
        conversationId: String,
        text: String,
        mode: SendMode,
        uiAction: UiAction? = null,
    ): Pair<Message, Message>

    /** The assistant reply as it streams; completes after Done or Failed. */
    fun messageEvents(messageId: String): Flow<MessageEvent>
    suspend fun stopMessage(messageId: String)

    // Figures
    suspend fun figures(): List<Figure>
    suspend fun figure(id: String): Figure
    suspend fun createFigure(prompt: String, theme: String, conversationId: String?): Figure

    /** Every change to the figure until it reaches a terminal status. */
    fun figureEvents(id: String): Flow<Figure>
    suspend fun cancelFigure(id: String): Figure
    suspend fun retryFigure(id: String): Figure
    suspend fun deleteFigure(id: String)

    /** Downloads a figure page (a signed URL from [Figure.darkPage] / [Figure.lightPage]). */
    suspend fun downloadPage(page: Page): ByteArray
}
