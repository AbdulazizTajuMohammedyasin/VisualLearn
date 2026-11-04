package com.visuallearn.app.learn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A graph shaped like LearnHarness's GET /api/graph (harness/src/core/contracts/graph.ts GraphSnapshot). */
class KnowledgeJsonTest {

    private val graph = JSONObject(
        """
        {"nodes":[{"id":"arg_1","name":"","statement":"","aliases":[],"createdAt":"","provenance":[]}],
         "propositions":[
          {"id":"n_power","name":"Power stroke","statement":"Only the power stroke pushes the crankshaft.","aliases":[],"createdAt":"",
           "provenance":[{"chatId":"chat_1","messageId":"a1"},{"chatId":"src_9","messageId":""}]},
          {"id":"n_store","name":"Flywheel","statement":"A flywheel stores rotational energy.","aliases":[],"createdAt":"",
           "provenance":[{"chatId":"chat_1","messageId":"a2"}]},
          {"id":"n_smooth","name":"Smooth rotation","statement":"The crankshaft keeps turning through the unpowered strokes.","aliases":[],"createdAt":"",
           "provenance":[{"chatId":"chat_1","messageId":"a3"}]},
          {"id":"n_tdc","name":"Top dead centre","statement":"TDC is the piston's highest point.","aliases":[],"createdAt":"",
           "provenance":[{"chatId":"src_9","messageId":""}]}
         ],
         "edges":[],"logicalEdges":[],"impLinks":[],
         "chains":[{"id":"arg_1","premises":["n_power","n_store"],"conclusion":"n_smooth","provenance":[],"createdAt":""}],
         "sessions":{"chat_1":"How an engine turns"},
         "fileSources":{"src_9":"notes/four-stroke-engine.pdf"},
         "nodeSessions":{},
         "clusters":[{"id":"lab_1","label":"Four-stroke cycle","members":["n_power","n_smooth","n_store"]}]}
        """.trimIndent(),
    )

    @Test
    fun ideasComeGroupedByTopicWithTheirReasoningAndSources() {
        val k = KnowledgeJson.knowledge(graph)
        assertEquals(listOf("Four-stroke cycle", null), k.topics.map { it.name })
        assertEquals(4, k.ideas)

        val cycle = k.topics[0]
        assertEquals(listOf("Flywheel", "Power stroke", "Smooth rotation"), cycle.ideas.map { it.name })

        val smooth = cycle.ideas.single { it.id == "n_smooth" }
        assertEquals(listOf(listOf("Power stroke", "Flywheel")), smooth.because)
        assertEquals(listOf(LessonRef("chat_1", "How an engine turns")), smooth.lessons)

        val power = cycle.ideas.single { it.id == "n_power" }
        assertEquals(listOf("Smooth rotation"), power.leadsTo)
        assertEquals(listOf("four-stroke-engine.pdf"), power.notes)

        // An idea outside every cluster still shows, in a topic of its own.
        val rest = k.topics[1]
        assertNull(rest.name)
        assertEquals(listOf("Top dead centre"), rest.ideas.map { it.name })
    }

    @Test
    fun aQuotaErrorFromAnImportReadsAsASentence() {
        val raw = "{\"error\":{\"message\":\"{\\n  \\\"error\\\": {\\n    \\\"code\\\": 429,\\n    \\\"message\\\": \\\"You exceeded your current quota, please check your plan.\\\"}}\"}}"
        assertEquals("The AI service has reached its usage limit for now. Try again later.", ProviderErrors.known(raw))
        assertNull(ProviderErrors.known("Could not open this PDF."))
        assertEquals("Could not open this PDF.", ProviderErrors.message("Could not open this PDF."))
    }

    @Test
    fun anEmptyGraphIsEmpty() {
        assertTrue(KnowledgeJson.knowledge(JSONObject("""{"nodes":[],"propositions":[],"chains":[],"sessions":{},"fileSources":{},"clusters":[]}""")).isEmpty)
    }
}
