package com.visuallearn.app.learn

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** What the learner did with a question card. */
data class AnswerChoice(
    /** Selected option values (quiz, probe, ask_user_question). */
    val values: List<String> = emptyList(),
    val dontKnow: Boolean = false,
    /** explain/recall: the written answer; ask: an "Other" answer. */
    val text: String? = null,
    /** Their reasoning, in their own words: the most useful thing the teacher reads. */
    val thinking: String? = null,
    /** quiz/probe: how sure they are, 0 to 100. */
    val confidence: Int? = null,
)

/**
 * The learner's answer as LearnHarness records it (core/question-core.ts
 * buildAnswer): choices are graded here, as its own question dock does, and
 * the teacher reads the result. Sent as `{ type: "answer", toolCallId, answer }`.
 */
fun buildAnswer(q: LessonItem.Question, choice: AnswerChoice, now: Instant = Instant.now()): JSONObject {
    val kind = q.kind
    val o = JSONObject()
        .put("kind", kind)
        .put("tool", q.tool)
        .put("questionText", q.prompt)
        .put("options", JSONArray(q.options.mapIndexed { i, opt -> JSONObject().put("index", i + 1).put("label", opt.label) }))
        .put("dontKnow", choice.dontKnow)
        .put("answeredAt", now.toString())
    q.questionType?.let { o.put("questionType", it) }

    val selected = if (choice.dontKnow) emptyList() else q.options.withIndex().filter { it.value.value in choice.values }
    o.put("answers", JSONArray(selected.map { (i, opt) -> JSONObject().put("label", opt.label).put("value", opt.value).put("index", i + 1) }))
    choice.text?.trim()?.takeIf { it.isNotEmpty() }?.let { o.put(if (kind == "ask" && q.options.isNotEmpty()) "other" else "text", it) }
    choice.thinking?.trim()?.takeIf { it.isNotEmpty() }?.let { o.put("thinking", it) }

    if (kind == "quiz") {
        val correctIndices = q.options.withIndex().filter { it.value.value in q.correctValues }.map { it.index + 1 }
        o.put("correctIndices", JSONArray(correctIndices))
        q.explanation?.let { o.put("explanation", it) }
        o.put("correct", !choice.dontKnow && selected.map { it.index + 1 }.toSet() == correctIndices.toSet() && correctIndices.isNotEmpty())
        if (!choice.dontKnow) choice.confidence?.let { o.put("confidence", it.coerceIn(0, 100)) }
    }
    if (kind == "explain") q.committed?.let { o.put("committed", it) }
    return o
}

/** The lesson inputs the phone sends (harness/src/core/input-events.ts ChatInput). */
object LessonInput {
    fun text(text: String): JSONObject = JSONObject().put("type", "text").put("text", text)
    /** Tells the teacher where the learner's PDF is; the lesson shows it as the PDF, not this sentence. */
    fun withPdf(path: String, ask: String): JSONObject = text("I've attached my PDF at $path. $ask")
    fun answer(q: LessonItem.Question, choice: AnswerChoice): JSONObject =
        JSONObject().put("type", "answer").put("toolCallId", q.callId).put("answer", buildAnswer(q, choice))
    fun retry(): JSONObject = JSONObject().put("type", "retry")
    /** The learner's working or answer on the open practice problem. */
    fun thinking(problemCallId: String, text: String): JSONObject = JSONObject().put("type", "thinking").put("problemCallId", problemCallId).put("text", text)
    /** Go on to the next practice problem. */
    fun next(): JSONObject = JSONObject().put("type", "next")
}
