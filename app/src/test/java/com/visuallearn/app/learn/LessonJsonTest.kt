package com.visuallearn.app.learn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Records shaped like LearnHarness's (harness/src/core/native-messages.ts), as seen through /v1/learn. */
class LessonJsonTest {

    private fun json(s: String) = JSONObject(s)

    private val record = json(
        """
        {"id":"chat_1","title":"Engines","turnInFlight":false,
         "tree":{"nodes":[{"id":"strokes","title":"The four strokes","summary":"Intake to exhaust."}],"edges":[],"version":1},
         "messages":[
          {"id":"u1","role":"user","content":"Teach me how a four-stroke engine works."},
          {"id":"a1","role":"assistant","content":[
             {"type":"thinking","thinking":"..."},
             {"type":"toolCall","id":"call_ask","name":"ask_user_question","arguments":{"question":"How deep?","options":[{"label":"Mechanical","value":"mech"},{"label":"Thermodynamics","value":"thermo"}]}}]},
          {"id":"r1","role":"toolResult","toolCallId":"call_ask","toolName":"ask_user_question",
           "details":{"kind":"ask","questionText":"How deep?","dontKnow":false,"answers":[{"label":"Mechanical","value":"mech","index":1}]},
           "content":[{"type":"text","text":"{}"}]},
          {"id":"h1","role":"custom","customType":"probe-handoff","content":"Next: plan...","details":{"chatId":"chat_1"}},
          {"id":"a2","role":"assistant","content":[
             {"type":"text","text":"I'm having a 3D figure built."},
             {"type":"toolCall","id":"call_fig","name":"figure","arguments":{"title":"One cylinder","brief":"..."}},
             {"type":"toolCall","id":"call_demo","name":"demo","arguments":{"title":"Crank angle","brief":"..."}},
             {"type":"toolCall","id":"call_list","name":"list_sessions","arguments":{}}]},
          {"id":"s1","role":"custom","customType":"subagent-steer","content":"The 3D figure...",
           "details":{"jobId":"job_1","kind":"figure","toolCallId":"call_fig","result":{"figureId":"fig_9","title":"One cylinder"}}},
          {"id":"a3","role":"assistant","content":[
             {"type":"toolCall","id":"call_quiz","name":"quiz","arguments":{"type":"check","nodeId":"strokes","question":"Which stroke delivers work?",
               "options":[{"label":"Intake","value":"intake"},{"label":"Power","value":"power"}],"explanation":"Only the power stroke pushes.","correctAnswer":"power"}}]},
          {"id":"a4","role":"assistant","content":[],"errorMessage":"{\"error\":{\"message\":\"{\\n \\\"error\\\": {\\n \\\"code\\\": 503, \\\"message\\\": \\\"This model is currently experiencing high demand.\\\"}}\"}}"}
         ]}
        """.trimIndent(),
    )

    @Test
    fun aRecordBecomesWhatTheLearnerSees() {
        val lesson = LessonJson.lesson(record)
        val kinds = lesson.items.map { it::class.simpleName }
        assertEquals(listOf("Learner", "Question", "Divider", "Teacher", "Working", "Figure", "Question", "Problem"), kinds)

        val ask = lesson.items[1] as LessonItem.Question
        assertEquals("ask", ask.kind)
        assertEquals(listOf("Mechanical"), ask.answer!!.labels)

        // The figure arrived, so it shows as a figure, not as work in progress; the demo is still being built.
        val working = lesson.items[4] as LessonItem.Working
        assertEquals("demo", working.kind)
        assertEquals("Crank angle", working.title)
        val figure = lesson.items[5] as LessonItem.Figure
        assertEquals("fig_9", figure.figureId)

        val quiz = lesson.pendingQuestion!!
        assertEquals("call_quiz", quiz.callId)
        assertEquals("Check", quiz.label)
        assertEquals(listOf("power"), quiz.correctValues)
        assertNull(quiz.answer)

        assertEquals("The AI service is busy right now. Try again in a minute.", (lesson.items.last() as LessonItem.Problem).text)
        assertEquals(listOf(PlanNode("strokes", "The four strokes", "Intake to exhaust.")), lesson.plan)
        assertFalse(lesson.busy)
    }

    @Test
    fun theMessageBeingWrittenShowsAsTheTeacherTypes() {
        val live = json("""{"role":"assistant","content":[{"type":"text","text":"Each stroke is half a"}]}""")
        val lesson = LessonJson.lesson(record, live)
        assertTrue(lesson.busy)
        assertEquals("Each stroke is half a", (lesson.items.last() as LessonItem.Teacher).markdown)
    }

    @Test
    fun aQuizAnswerIsGradedTheWayLearnHarnessGradesIt() {
        val quiz = LessonJson.lesson(record).pendingQuestion!!
        val now = Instant.parse("2026-11-04T10:00:00Z")
        val right = buildAnswer(quiz, AnswerChoice(values = listOf("power"), thinking = " It's the one with the spark. ", confidence = 80), now)
        assertEquals("quiz", right.getString("kind"))
        assertEquals("quiz", right.getString("tool"))
        assertEquals("check", right.getString("questionType"))
        assertEquals(true, right.getBoolean("correct"))
        assertEquals(80, right.getInt("confidence"))
        assertEquals("It's the one with the spark.", right.getString("thinking"))
        assertEquals("[2]", right.getJSONArray("correctIndices").toString())
        assertEquals("Power", right.getJSONArray("answers").getJSONObject(0).getString("label"))
        assertEquals(2, right.getJSONArray("answers").getJSONObject(0).getInt("index"))
        assertEquals("2026-11-04T10:00:00Z", right.getString("answeredAt"))

        val wrong = buildAnswer(quiz, AnswerChoice(values = listOf("intake"), confidence = 60), now)
        assertEquals(false, wrong.getBoolean("correct"))

        val unsure = buildAnswer(quiz, AnswerChoice(dontKnow = true, confidence = 50), now)
        assertEquals(false, unsure.getBoolean("correct"))
        assertFalse("no confidence without an answer", unsure.has("confidence"))
        assertEquals(0, unsure.getJSONArray("answers").length())

        val input = LessonInput.answer(quiz, AnswerChoice(values = listOf("power")))
        assertEquals("answer", input.getString("type"))
        assertEquals("call_quiz", input.getString("toolCallId"))
    }

    @Test
    fun aMessageWithAPdfShowsThePdfAndTheAsk() {
        val sent = LessonInput.withPdf("~/four-stroke-engine.pdf", "Teach me what's in it.").getString("text")
        val items = LessonJson.project(
            listOf(
                json(JSONObject().put("role", "user").put("content", sent).toString()),
                json("""{"role":"assistant","content":[{"type":"toolCall","id":"p","name":"pdf","arguments":{"path":"~/four-stroke-engine.pdf"}}]}"""),
            ),
        )
        assertEquals(LessonItem.Learner(items[0].key, "Teach me what's in it.", pdf = "four-stroke-engine.pdf"), items[0])
        assertEquals("four-stroke-engine.pdf", (items[1] as LessonItem.Working).title)
    }

    @Test
    fun aWrittenAnswerCarriesTheTextAndTheCommittedAnswer() {
        val explain = LessonJson.project(
            listOf(json("""{"role":"assistant","content":[{"type":"toolCall","id":"c","name":"recall","arguments":{"question":"Why a flywheel?","correctAnswer":"It carries the crank through the three unpowered strokes."}}]}""")),
        ).single() as LessonItem.Question
        assertEquals("explain", explain.kind)
        assertEquals("In your own words", explain.label)
        val a = buildAnswer(explain, AnswerChoice(text = "It stores energy."))
        assertEquals("It stores energy.", a.getString("text"))
        assertEquals("It carries the crank through the three unpowered strokes.", a.getString("committed"))
        assertFalse(a.has("correct"))
        assertEquals(JSONArray().toString(), a.getJSONArray("options").toString())
    }
}
