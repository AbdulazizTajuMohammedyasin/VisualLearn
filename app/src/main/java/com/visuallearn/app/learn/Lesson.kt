package com.visuallearn.app.learn

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lessons come from LearnHarness (harness/), reached through the server at
 * /v1/learn/api. A lesson record is LearnHarness's native message list; this
 * file turns it into what the phone shows. Field names follow
 * harness/src/core (native-messages.ts, question-core.ts, contracts/jobs.ts).
 */

/**
 * A piece of background work as LearnHarness reports it. Its result reaches
 * the lesson only on the teacher's next turn, so a finished job can still be
 * waiting there, for example behind a question the learner hasn't answered.
 */
data class JobProgress(
    /** running, done or error (harness/src/core/contracts/jobs.ts JobStatus). */
    val status: String,
    val activity: String,
    val error: String? = null,
) {
    val done get() = status == "done"
    val failed get() = status == "error"
}

/** One row of the lesson list. */
data class LessonSummary(
    val id: String,
    val title: String,
    val updatedAt: String,
    /** "teach" or "practice". */
    val kind: String,
    /** "probe", "plan" or "teach" while teaching. */
    val phase: String?,
    val pendingQuestion: Boolean,
    val busy: Boolean,
)

/** The questions LearnHarness poses through tools (core/pending.ts QUESTION_TOOLS). */
val QUESTION_TOOLS = setOf("quiz", "probe", "ask_user_question", "explain", "recall")

/** quiz/probe are graded choices; explain/recall are written answers; ask_user_question is a plain question. */
fun questionKind(tool: String): String = when (tool) {
    "quiz", "probe" -> "quiz"
    "explain", "recall" -> "explain"
    else -> "ask"
}

data class QuestionOption(val label: String, val value: String, val description: String?)

/** A learner's answer as recorded in the lesson (QuestionAnswerData), for showing it back. */
data class GivenAnswer(
    val dontKnow: Boolean,
    val labels: List<String>,
    val text: String?,
    val correct: Boolean?,
    val explanation: String?,
    /** explain/recall: the teacher's committed answer, shown after answering. */
    val committed: String?,
)

/** What the lesson shows, in order. */
sealed interface LessonItem {
    val key: String

    /** What the learner said, and the PDF they attached to it if any. */
    data class Learner(override val key: String, val text: String, val pdf: String? = null) : LessonItem
    data class Teacher(override val key: String, val markdown: String) : LessonItem

    data class Question(
        override val key: String,
        /** The tool call this answers: the answer input names it. */
        val callId: String,
        val tool: String,
        val prompt: String,
        val details: String?,
        val options: List<QuestionOption>,
        val multiSelect: Boolean,
        /** quiz/probe: option values that are right; explain/recall: none. */
        val correctValues: List<String>,
        val explanation: String?,
        val committed: String?,
        /** quiz/explain in teaching: "predict" (before the step) or "check" (after it). */
        val questionType: String?,
        /** Null while waiting for the learner. */
        val answer: GivenAnswer?,
    ) : LessonItem {
        val kind: String get() = questionKind(tool)
        val label: String
            get() = when (tool) {
                "probe" -> "Where you are"
                "recall" -> "In your own words"
                "explain" -> if (questionType == "predict") "Predict" else "Explain it"
                "quiz" -> if (questionType == "predict") "Predict" else "Check"
                else -> "Question"
            }
    }

    /** A 3D Anatomy figure the teacher had built; opened with the server's figure id. */
    data class Figure(override val key: String, val figureId: String?, val title: String, val status: String) : LessonItem

    /** A tested interactive demo: a self-contained HTML page. */
    data class Demo(override val key: String, val title: String, val html: String) : LessonItem

    /** A verified static figure as SVG. */
    data class Visual(override val key: String, val svg: String) : LessonItem

    /** Background work the teacher started that is still going (research, PDF reading, a figure, a demo). */
    data class Working(override val key: String, val kind: String, val title: String) : LessonItem

    /** A quiet line between phases, e.g. when probing ends and teaching begins. */
    data class Divider(override val key: String, val text: String) : LessonItem

    /** Something that went wrong in the lesson, in the teacher's or server's words. */
    data class Problem(override val key: String, val text: String) : LessonItem
}

/** A lesson as the phone shows it. */
data class Lesson(
    val id: String,
    val title: String,
    val items: List<LessonItem>,
    val plan: List<PlanNode>,
    /** The teacher is working right now. */
    val busy: Boolean,
) {
    /** The question waiting for the learner, if any: the lesson can't go on until it's answered. */
    val pendingQuestion: LessonItem.Question?
        get() = items.lastOrNull { it is LessonItem.Question && it.answer == null } as LessonItem.Question?
}

data class PlanNode(val id: String, val title: String, val summary: String?)

object LessonJson {

    fun summary(o: JSONObject) = LessonSummary(
        id = o.getString("id"),
        title = o.optString("title").ifBlank { "Untitled lesson" },
        updatedAt = o.optString("updatedAt"),
        kind = o.optString("kind", "teach"),
        phase = o.optString("phase").ifBlank { null },
        pendingQuestion = o.optBoolean("pendingQuestion"),
        busy = o.optBoolean("busy"),
    )

    /** A lesson from its record; [live] is the assistant message being written right now, if any. */
    fun lesson(record: JSONObject, live: JSONObject? = null): Lesson {
        val messages = record.optJSONArray("messages") ?: JSONArray()
        val all = (0 until messages.length()).map { messages.getJSONObject(it) }.toMutableList()
        if (live != null) all += live
        return Lesson(
            id = record.getString("id"),
            title = record.optString("title").ifBlank { "Untitled lesson" },
            items = project(all),
            plan = plan(record.optJSONObject("tree")),
            busy = record.optBoolean("turnInFlight") || live != null,
        )
    }

    fun plan(tree: JSONObject?): List<PlanNode> {
        val nodes = tree?.optJSONArray("nodes") ?: return emptyList()
        return (0 until nodes.length()).map { nodes.getJSONObject(it) }.map { n ->
            PlanNode(n.getString("id"), n.optString("title"), n.optString("summary").ifBlank { null })
        }
    }

    /** Native messages → lesson items. Tool calls other than questions are the teacher's own work and stay hidden. */
    fun project(messages: List<JSONObject>): List<LessonItem> {
        // Answers (and other results) by the call they belong to.
        val results = HashMap<String, JSONObject>()
        for (m in messages) if (m.optString("role") == "toolResult") results[m.optString("toolCallId")] = m
        // Background jobs whose result has arrived, by the call that started them.
        val delivered = HashSet<String>()
        for (m in messages) {
            if (m.optString("role") == "custom" && m.optString("customType").startsWith("subagent-")) {
                m.optJSONObject("details")?.optString("toolCallId")?.let(delivered::add)
            }
        }

        val items = ArrayList<LessonItem>()
        messages.forEachIndexed { i, m ->
            val id = m.optString("id").ifBlank { "m$i" }
            when (m.optString("role")) {
                "user" -> text(m).takeIf { it.isNotBlank() }?.let { items += learner(id, it) }
                "assistant" -> {
                    val content = m.optJSONArray("content") ?: JSONArray()
                    for (c in 0 until content.length()) {
                        val part = content.getJSONObject(c)
                        when (part.optString("type")) {
                            "text" -> part.optString("text").takeIf { it.isNotBlank() }?.let { items += LessonItem.Teacher("$id-$c", it) }
                            "toolCall" -> {
                                val name = part.optString("name")
                                val callId = part.optString("id")
                                val args = part.optJSONObject("arguments") ?: JSONObject()
                                if (name in QUESTION_TOOLS) items += question(callId, name, args, results[callId])
                                else if (name in JOB_TOOLS && callId !in delivered) items += working(callId, name, args)
                            }
                        }
                    }
                    m.optString("errorMessage").takeIf { it.isNotBlank() }?.let { items += LessonItem.Problem("$id-error", problemText(it)) }
                }
                "custom" -> custom(id, m)?.let { items += it }
            }
        }
        return items
    }

    private val JOB_TOOLS = setOf("research", "demo", "visual", "pdf", "figure")

    private fun working(callId: String, tool: String, args: JSONObject) = LessonItem.Working(
        key = "job-$callId",
        kind = tool,
        title = when (tool) {
            "figure", "demo" -> args.optString("title").ifBlank { if (tool == "figure") "3D figure" else "Interactive demo" }
            "research" -> "Looking it up"
            "pdf" -> args.optString("path").substringAfterLast('/').ifBlank { "Your PDF" }
            else -> "Drawing a figure"
        },
    )

    /** A message that attached a PDF ([LessonInput.withPdf]) shows as the PDF and what was asked of it. */
    private fun learner(id: String, text: String): LessonItem.Learner {
        val m = PDF_MESSAGE.matchEntire(text) ?: return LessonItem.Learner(id, text)
        return LessonItem.Learner(id, m.groupValues[2], pdf = m.groupValues[1])
    }

    private val PDF_MESSAGE = Regex("""I've attached my PDF at ~/(.+?\.pdf)\. (.*)""", RegexOption.DOT_MATCHES_ALL)

    private fun custom(id: String, m: JSONObject): LessonItem? {
        val details = m.optJSONObject("details") ?: JSONObject()
        return when (m.optString("customType")) {
            "probe-handoff" -> LessonItem.Divider(id, "Planning the lesson from what you know")
            "subagent-abort" -> LessonItem.Problem(id, "Cancelled.")
            "subagent-steer" -> {
                val result = details.optJSONObject("result") ?: return null
                when (details.optString("kind")) {
                    "figure" -> LessonItem.Figure(
                        id,
                        result.optString("figureId").ifBlank { null },
                        result.optString("title").ifBlank { "3D figure" },
                        if (result.optString("figureId").isBlank()) "failed" else "succeeded",
                    )
                    "demo" -> result.optString("html").takeIf { it.isNotBlank() }?.let { LessonItem.Demo(id, result.optString("title"), it) }
                    "visual" -> result.optString("svg").takeIf { it.isNotBlank() }?.let { LessonItem.Visual(id, it) }
                    // Research and PDF briefs feed the teacher, who tells the learner what matters.
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun question(callId: String, tool: String, args: JSONObject, result: JSONObject?): LessonItem.Question {
        val optionsJson = args.optJSONArray("options") ?: JSONArray()
        val options = (0 until optionsJson.length()).map { optionsJson.getJSONObject(it) }.map {
            QuestionOption(it.optString("label"), it.optString("value", it.optString("label")), it.optString("description").ifBlank { null })
        }
        val correct = when (val c = args.opt("correctAnswer")) {
            is JSONArray -> (0 until c.length()).map { c.getString(it) }
            is String -> if (questionKind(tool) == "quiz") listOf(c) else emptyList()
            else -> emptyList()
        }
        return LessonItem.Question(
            key = "q-$callId",
            callId = callId,
            tool = tool,
            prompt = args.optString("question"),
            details = args.optString("details").ifBlank { null },
            options = options,
            multiSelect = args.optBoolean("multiSelect"),
            correctValues = correct,
            explanation = args.optString("explanation").ifBlank { null },
            committed = if (questionKind(tool) == "explain") args.optString("correctAnswer").ifBlank { null } else null,
            questionType = args.optString("type").ifBlank { null },
            answer = result?.let(::givenAnswer),
        )
    }

    /** A question's result is the learner's answer only once it carries the graded record (details). */
    private fun givenAnswer(result: JSONObject): GivenAnswer? {
        val d = result.optJSONObject("details") ?: return null
        if (!d.has("answeredAt") && !d.has("answers")) return null
        val answers = d.optJSONArray("answers") ?: JSONArray()
        return GivenAnswer(
            dontKnow = d.optBoolean("dontKnow"),
            labels = (0 until answers.length()).map { answers.getJSONObject(it).optString("label") },
            text = d.optString("text").ifBlank { d.optString("other").ifBlank { null } },
            correct = if (d.has("correct")) d.optBoolean("correct") else null,
            explanation = d.optString("explanation").ifBlank { null },
            committed = d.optString("committed").ifBlank { null },
        )
    }

    private fun text(m: JSONObject): String = when (val c = m.opt("content")) {
        is String -> c
        is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.takeIf { p -> p.optString("type") == "text" }?.optString("text") }.joinToString("\n")
        else -> ""
    }

    private fun problemText(raw: String): String =
        ProviderErrors.known(raw) ?: "The teacher couldn't answer: ${ProviderErrors.message(raw).take(200)}"
}

/** Model provider errors, which LearnHarness passes on as raw JSON, as plain sentences. */
object ProviderErrors {
    /** The innermost message out of the JSON the error arrives in. */
    fun message(raw: String): String {
        val message = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(raw).map { it.groupValues[1] }.lastOrNull()
        return (message ?: raw).replace("\\n", " ").replace("\\\"", "\"").trim()
    }

    /** Usage limits and overload in words; null for any other error. */
    fun known(raw: String): String? {
        val text = message(raw)
        return when {
            Regex("quota|RESOURCE_EXHAUSTED|billing", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "The AI service has reached its usage limit for now. Try again later."
            Regex("high demand|overloaded|503|unavailable", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "The AI service is busy right now. Try again in a minute."
            else -> null
        }
    }
}
