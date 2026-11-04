package com.visuallearn.app.learn

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the learner knows: the ideas LearnHarness's knowledge graph holds,
 * read from GET /api/graph (harness/src/core/contracts/graph.ts GraphSnapshot).
 * The graph keeps every idea as one true statement, the reasoning chains
 * that derive ideas from others, and where each idea came up: a lesson or
 * imported notes. The phone shows it as topics of ideas, not as a canvas.
 */
data class Knowledge(val topics: List<KnowledgeTopic>) {
    val ideas get() = topics.sumOf { it.ideas.size }
    val isEmpty get() = topics.isEmpty()
}

/** A cluster of ideas that came up together; [name] is null until LearnHarness has named it. */
data class KnowledgeTopic(val id: String, val name: String?, val ideas: List<KnowledgeIdea>)

data class KnowledgeIdea(
    val id: String,
    /** A short label, like a glossary headword. */
    val name: String,
    /** The idea itself, at full precision. */
    val statement: String,
    /** Each way the graph derives this idea: the names of premises that together imply it. */
    val because: List<List<String>>,
    /** Ideas this one helps derive. */
    val leadsTo: List<String>,
    /** Lessons it came up in. */
    val lessons: List<LessonRef>,
    /** Imported notes it came from, by file name. */
    val notes: List<String>,
)

data class LessonRef(val id: String, val title: String)

object KnowledgeJson {

    fun knowledge(graph: JSONObject): Knowledge {
        val sessions = graph.optJSONObject("sessions").strings()
        val files = graph.optJSONObject("fileSources").strings()
        // Propositions only: the canvas's derived implication nodes aren't ideas.
        val nodes = graph.optJSONArray("propositions").objects()
        val names = nodes.associate { it.getString("id") to it.optString("name").ifBlank { it.optString("statement") } }
        val chains = graph.optJSONArray("chains").objects().map { c -> c.optJSONArray("premises").stringList() to c.optString("conclusion") }

        val ideas = nodes.associate { n ->
            val id = n.getString("id")
            val sources = n.optJSONArray("provenance").objects().map { it.optString("chatId") }.distinct()
            id to KnowledgeIdea(
                id = id,
                name = names.getValue(id),
                statement = n.optString("statement"),
                because = chains.filter { it.second == id }.map { (premises, _) -> premises.mapNotNull(names::get) }.filter { it.isNotEmpty() },
                leadsTo = chains.filter { id in it.first }.mapNotNull { names[it.second] }.distinct(),
                lessons = sources.mapNotNull { s -> sessions[s]?.let { LessonRef(s, it) } },
                notes = sources.mapNotNull { s -> files[s]?.substringAfterLast('/')?.substringAfterLast('\\') },
            )
        }

        val clustered = mutableSetOf<String>()
        val topics = graph.optJSONArray("clusters").objects().mapNotNull { c ->
            val members = c.optJSONArray("members").stringList().mapNotNull(ideas::get)
            if (members.isEmpty()) return@mapNotNull null
            clustered += members.map { it.id }
            KnowledgeTopic(c.optString("id"), c.optString("label").takeIf { it.isNotBlank() && it != "null" }, members.sortedBy { it.name.lowercase() })
        }.sortedWith(compareBy({ it.name == null }, { -it.ideas.size }))
        val rest = ideas.values.filter { it.id !in clustered }.sortedBy { it.name.lowercase() }
        return Knowledge(if (rest.isEmpty()) topics else topics + KnowledgeTopic("other", null, rest))
    }

    private fun JSONObject?.strings(): Map<String, String> =
        this?.keys()?.asSequence()?.associateWith { getString(it) }.orEmpty()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.stringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}

/** What importing one file of notes did. */
data class NotesImport(val name: String, val ok: Boolean, val ideas: Int, val message: String?)
