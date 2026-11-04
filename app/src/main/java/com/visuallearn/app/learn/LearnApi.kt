package com.visuallearn.app.learn

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * Lessons on LearnHarness, through the server's `/v1/learn` gateway
 * (docs/server-api.md, "Lessons"). Records and stream events stay in
 * LearnHarness's own JSON; [LessonJson] reads them.
 */
interface LearnApi {
    suspend fun lessons(): List<LessonSummary>

    /** Starts a lesson (or a practice session) and returns its id. */
    suspend fun createLesson(title: String, practice: Boolean = false): String
    suspend fun lessonRecord(id: String): JSONObject

    /** A message, an answer or a retry (see [LessonInput]); the teacher's turn then runs on the server. */
    suspend fun sendToLesson(id: String, input: JSONObject)
    suspend fun deleteLesson(id: String)

    /** Puts a PDF where the teacher can read it; returns the path to mention, e.g. `~/notes.pdf`. */
    suspend fun uploadLessonFile(name: String, pdf: ByteArray): String

    /** The running turn's agent events; completes when the turn is over. */
    fun lessonEvents(id: String): Flow<JSONObject>
}
