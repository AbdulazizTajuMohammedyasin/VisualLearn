package com.visuallearn.app.offline

import com.visuallearn.app.api.Conversation
import com.visuallearn.app.api.ConversationSummary
import com.visuallearn.app.api.Message
import com.visuallearn.app.api.MessageStatus
import com.visuallearn.app.api.Part
import com.visuallearn.app.api.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private fun store() = OfflineStore(folder.root.resolve("offline"))

    @Test
    fun savedConversationsSurviveANewStore() {
        val conversation = Conversation("conv_1", "Locks", listOf(Message("m1", Role.USER, MessageStatus.COMPLETE, listOf(Part.Text("How?")))))
        store().apply {
            saveConversation(conversation)
            saveSummaries(listOf(ConversationSummary("conv_1", "Locks", "2026-11-01T12:00:00Z")))
        }
        val reopened = store()
        assertEquals(conversation, reopened.conversation("conv_1"))
        assertEquals("Locks", reopened.summaries().single().title)
    }

    @Test
    fun deletingAConversationRemovesItAndItsSummary() {
        val s = store()
        s.saveConversation(Conversation("conv_1", "A", emptyList()))
        s.saveSummaries(listOf(ConversationSummary("conv_1", "A", "t"), ConversationSummary("conv_2", "B", "t")))
        s.deleteConversation("conv_1")
        assertNull(s.conversation("conv_1"))
        assertEquals(listOf("conv_2"), s.summaries().map { it.id })
    }

    @Test
    fun idsCantEscapeTheStoreFolder() {
        val s = store()
        s.saveConversation(Conversation("../../evil", "x", emptyList()))
        assertEquals(false, folder.root.resolve("evil.json").exists())
        assertEquals("x", s.conversation("../../evil")?.title)
    }

    @Test
    fun cursorUiStateAndClear() {
        val s = store()
        assertNull(s.cursor)
        s.cursor = "123"
        s.saveUiState("msg_1-2", """{"mc:q1":1}""")
        assertEquals("123", store().cursor)
        assertEquals("""{"mc:q1":1}""", store().uiState("msg_1-2"))
        s.clear()
        assertNull(store().cursor)
        assertNull(store().uiState("msg_1-2"))
    }
}
