package com.visuallearn.app.jobs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.visuallearn.app.MainActivity
import com.visuallearn.app.R
import com.visuallearn.app.linux.EnvController
import com.visuallearn.app.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED, INTERRUPTED }

data class FigureJob(
    val id: String,
    val prompt: String,
    val theme: String,
    val createdAt: Long,
    val state: JobState,
    val stage: String? = null,
    val message: String? = null,
    val lastTool: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val costUsd: Double = 0.0,
    val elapsedSec: Int = 0,
    val error: String? = null,
) {
    val isActive get() = state == JobState.QUEUED || state == JobState.RUNNING
}

/**
 * Figure jobs run the Python agent (assets/linux/agent/agent.py) inside the Debian
 * guest, one at a time. Each job lives in /root/jobs/<id> in the guest, so jobs
 * survive app restarts and can be resumed from their saved conversation.
 */
class FigureJobs(
    private val context: Context,
    private val env: EnvController,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val processes = mutableMapOf<String, Process>()
    private val _jobs = MutableStateFlow<Map<String, FigureJob>>(emptyMap())
    val jobs: StateFlow<Map<String, FigureJob>> = _jobs.asStateFlow()

    init {
        scope.launch { _jobs.value = loadJobs() }
        scope.launch { for (id in queue) runJob(id) }
    }

    fun jobDir(id: String): File = env.env.guestFile("/root/jobs/$id")
    fun darkPage(id: String) = File(jobDir(id), "out/figure.html")
    fun lightPage(id: String) = File(jobDir(id), "out/figure-light.html")

    /** Creates and queues a job; returns its id. */
    fun create(prompt: String, theme: String): String {
        val id = UUID.randomUUID().toString().take(12)
        val createdAt = System.currentTimeMillis()
        jobDir(id).mkdirs()
        File(jobDir(id), "job.json").writeText(
            JSONObject().put("prompt", prompt).put("theme", theme).put("created", createdAt).toString()
        )
        put(FigureJob(id, prompt, theme, createdAt, JobState.QUEUED, message = "Waiting to start…"))
        queue.trySend(id)
        return id
    }

    fun resume(id: String) {
        val job = _jobs.value[id] ?: return
        if (job.isActive) return
        put(job.copy(state = JobState.QUEUED, error = null, message = "Waiting to resume…"))
        queue.trySend(id)
    }

    fun cancel(id: String) {
        val job = _jobs.value[id] ?: return
        processes.remove(id)?.destroy()
        if (job.isActive) {
            put(job.copy(state = JobState.CANCELLED, message = "Cancelled"))
            writeStatus(id, "cancelled")
        }
    }

    fun delete(id: String) {
        cancel(id)
        jobDir(id).deleteRecursively()
        _jobs.update { it - id }
    }

    private suspend fun runJob(id: String) {
        val job = _jobs.value[id] ?: return
        if (job.state != JobState.QUEUED) return
        val apiKey = settings.apiKey
        if (apiKey == null) {
            put(job.copy(state = JobState.FAILED, error = "Add your Claude API key in Settings first."))
            return
        }
        if (!env.isToolsReady()) {
            put(job.copy(state = JobState.FAILED, error = "Finish setting up the Linux environment first."))
            return
        }
        put(job.copy(state = JobState.RUNNING, message = "Starting…"))
        env.acquireForeground("Building “${job.prompt.take(40)}”")
        try {
            env.installAsset("agent/agent.py", "/opt/visuallearn/agent.py")
            val code = env.env.run(
                script = "exec /opt/vlagent/bin/python -u /opt/visuallearn/agent.py /root/jobs/$id",
                extraEnv = mapOf(
                    "ANTHROPIC_API_KEY" to apiKey,
                    "VL_MODEL" to settings.figureModel.id,
                ),
                onStart = { processes[id] = it },
                onLine = { line -> onAgentLine(id, line) },
            )
            processes.remove(id)
            val current = _jobs.value[id] ?: return
            if (current.state == JobState.RUNNING) {
                // Exited without a done/error event: treat as interrupted so it can be resumed.
                put(current.copy(state = JobState.INTERRUPTED, error = "The agent stopped (exit $code)."))
            }
        } catch (e: Exception) {
            put((_jobs.value[id] ?: job).copy(state = JobState.FAILED, error = e.message))
        } finally {
            env.releaseForeground()
            _jobs.value[id]?.let { notifyFinished(it) }
        }
    }

    private fun onAgentLine(id: String, line: String) {
        if (!line.startsWith("@@VL ")) {
            Log.d(TAG, "[$id] $line")
            return
        }
        val event = runCatching { JSONObject(line.removePrefix("@@VL ")) }.getOrNull() ?: return
        val job = _jobs.value[id] ?: return
        put(
            when (event.optString("type")) {
                "progress" -> job.copy(stage = event.optString("stage"), message = event.optString("message"))
                "tool" -> job.copy(lastTool = "${event.optString("name")} ${event.optString("detail")}".trim())
                "usage" -> job.copy(costUsd = event.optDouble("cost_usd"), elapsedSec = event.optInt("elapsed_sec"))
                "done" -> job.copy(
                    state = JobState.DONE,
                    title = event.optString("title"),
                    summary = event.optString("summary"),
                    costUsd = event.optDouble("cost_usd", job.costUsd),
                    message = "Finished",
                )
                "error" -> job.copy(state = JobState.FAILED, error = event.optString("message"))
                else -> job
            }
        )
    }

    private fun loadJobs(): Map<String, FigureJob> {
        val root = env.env.guestFile("/root/jobs")
        return root.listFiles().orEmpty().mapNotNull { dir ->
            runCatching {
                val spec = JSONObject(File(dir, "job.json").readText())
                val status = File(dir, "status.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
                val result = status?.optJSONObject("result")
                val state = when (status?.optString("state")) {
                    "done" -> JobState.DONE
                    "failed" -> JobState.FAILED
                    "cancelled" -> JobState.CANCELLED
                    else -> JobState.INTERRUPTED // was running when the app died, or never started
                }
                FigureJob(
                    id = dir.name,
                    prompt = spec.getString("prompt"),
                    theme = spec.optString("theme", "dark"),
                    createdAt = spec.optLong("created", dir.lastModified()),
                    state = state,
                    title = result?.optString("title"),
                    summary = result?.optString("summary"),
                    costUsd = status?.optDouble("cost_usd", 0.0) ?: 0.0,
                    error = status?.optString("error")?.takeIf { it.isNotEmpty() },
                )
            }.getOrNull()
        }.associateBy { it.id }
    }

    private fun writeStatus(id: String, state: String) {
        val file = File(jobDir(id), "status.json")
        val status = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        file.writeText(status.put("state", state).toString())
    }

    private fun put(job: FigureJob) = _jobs.update { it + (job.id to job) }

    private fun notifyFinished(job: FigureJob) {
        val text = when (job.state) {
            JobState.DONE -> job.title ?: "Your figure is ready"
            JobState.CANCELLED -> return
            else -> job.error ?: "The figure could not be finished"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Finished figures", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, job.id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_JOB, job.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (job.state == JobState.DONE) "Figure ready" else "Figure stopped")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(job.id.hashCode(), notification)
    }

    private companion object {
        const val TAG = "VLJobs"
        const val CHANNEL_ID = "figures"
    }
}
