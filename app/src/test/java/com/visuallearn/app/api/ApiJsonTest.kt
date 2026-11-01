package com.visuallearn.app.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The offline cache writes the server's JSON and reads it back through the same parsers. */
class ApiJsonTest {

    private val figure = Figure(
        id = "fig_1",
        prompt = "How a pin tumbler lock opens",
        status = FigureStatus.SUCCEEDED,
        queuePosition = null,
        estimatedStartAt = null,
        stage = "polish",
        message = "Finished.",
        progress = 1.0,
        title = "Pin tumbler lock",
        summary = "Pins line up at the shear line.",
        conversationId = "conv_1",
        createdAt = "2026-11-01T12:00:00Z",
        updatedAt = "2026-11-01T12:40:00Z",
        startedAt = "2026-11-01T12:01:00Z",
        finishedAt = "2026-11-01T12:40:00Z",
        error = null,
        darkPage = Page("https://api.example.com/v1/pages/aa.html?sig=x", "aa", 1234),
        lightPage = Page("https://api.example.com/v1/pages/bb.html?sig=y", "bb", 1235),
        previewUrl = null,
    )

    @Test
    fun figureRoundTrips() {
        assertEquals(figure, ApiJson.parseFigure(JSONObject(ApiJson.write(figure).toString())))
    }

    @Test
    fun queuedFigureKeepsItsPlaceAndHasNoPages() {
        val queued = figure.copy(
            status = FigureStatus.QUEUED, queuePosition = 3, estimatedStartAt = "2026-11-01T14:20:00Z",
            stage = null, progress = null, title = null, summary = null, darkPage = null, lightPage = null, finishedAt = null,
        )
        val json = ApiJson.write(queued)
        assertEquals(false, json.has("pages"))
        assertEquals(queued, ApiJson.parseFigure(JSONObject(json.toString())))
    }

    @Test
    fun conversationWithEveryPartTypeRoundTrips() {
        val conversation = Conversation(
            "conv_1",
            "Zippers",
            listOf(
                Message("m1", Role.USER, MessageStatus.COMPLETE, listOf(Part.Text("Quiz me"))),
                Message(
                    "m2", Role.ASSISTANT, MessageStatus.COMPLETE,
                    listOf(Part.Text("Here you go."), Part.Ui("root = Quiz(\"Z\", [q1])\n", "1.0.0"), Part.Figure("fig_1")),
                ),
                Message("m3", Role.ASSISTANT, MessageStatus.FAILED, emptyList(), ApiError("model_error", "Try again.", true)),
            ),
        )
        assertEquals(conversation, ApiJson.parseConversation(JSONObject(ApiJson.write(conversation).toString())))
    }

    @Test
    fun syncResultParsesDeletions() {
        val sync = ApiJson.parseSync(
            JSONObject(
                """{"conversations":[{"id":"c1","title":"T","updatedAt":"2026-11-01T12:00:00Z"}],
                   "deletedConversationIds":["c2"],"figures":[],"deletedFigureIds":["f9"],"cursor":"1793551643404"}""",
            ),
        )
        assertEquals(listOf(ConversationSummary("c1", "T", "2026-11-01T12:00:00Z")), sync.conversations)
        assertEquals(listOf("c2"), sync.deletedConversationIds)
        assertEquals(listOf("f9"), sync.deletedFigureIds)
        assertEquals("1793551643404", sync.cursor)
    }

    @Test
    fun unknownPartTypesAreSkipped() {
        val message = ApiJson.parseMessage(
            JSONObject("""{"id":"m","role":"assistant","status":"complete","parts":[{"type":"audio"},{"type":"text","text":"hi"}]}"""),
        )
        assertEquals(listOf<Part>(Part.Text("hi")), message.parts)
        assertNull(message.error)
    }
}
