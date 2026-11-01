package com.visuallearn.app.figures

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.visuallearn.app.MainActivity
import com.visuallearn.app.R
import com.visuallearn.app.api.Figure
import com.visuallearn.app.api.FigureStatus
import com.visuallearn.app.api.Page
import com.visuallearn.app.api.VisualLearnApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Figure jobs run on the server; this keeps their latest state, follows active
 * ones live, and caches finished pages on the phone so they open offline.
 */
class FiguresRepository(private val context: Context, private val api: VisualLearnApi) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheDir = File(context.filesDir, "figures").apply { mkdirs() }
    private val watchers = mutableMapOf<String, Job>()
    private val _figures = MutableStateFlow<Map<String, Figure>>(emptyMap())
    val figures: StateFlow<Map<String, Figure>> = _figures.asStateFlow()

    fun refresh() = scope.launch {
        runCatching { api.figures() }.onSuccess { list ->
            _figures.update { current -> current + list.associateBy { it.id } }
            list.filter { it.status.isActive }.forEach { watch(it.id) }
        }.onFailure { Log.w(TAG, "refresh failed", it) }
    }

    /** Starts following a figure (e.g. one a chat reply just started). */
    fun watch(id: String) {
        synchronized(watchers) {
            if (watchers[id]?.isActive == true) return
            watchers[id] = scope.launch {
                runCatching {
                    if (_figures.value[id] == null) put(api.figure(id))
                    api.figureEvents(id).collect { figure ->
                        val previous = _figures.value[id]
                        put(figure)
                        if (previous?.status?.isActive != false && !figure.status.isActive) onFinished(figure)
                    }
                }.onFailure { Log.w(TAG, "watch $id failed", it) }
            }
        }
    }

    suspend fun create(prompt: String, theme: String, conversationId: String?): Figure {
        val figure = api.createFigure(prompt, theme, conversationId)
        put(figure)
        watch(figure.id)
        return figure
    }

    fun cancel(id: String) = scope.launch { runCatching { put(api.cancelFigure(id)) } }

    fun retry(id: String) = scope.launch {
        runCatching { put(api.retryFigure(id)); watch(id) }
    }

    fun delete(id: String) = scope.launch {
        runCatching { api.deleteFigure(id) }.onSuccess {
            _figures.update { it - id }
        }
    }

    /** The cached page for a finished figure, downloading and verifying it if needed. */
    suspend fun page(id: String, dark: Boolean): File? = withContext(Dispatchers.IO) {
        var figure = _figures.value[id] ?: runCatching { api.figure(id) }.getOrNull()?.also(::put) ?: return@withContext null
        val cached = (if (dark) figure.darkPage else figure.lightPage)?.let(::cachedFile)
        if (cached?.exists() == true) return@withContext cached
        // Signed URLs expire; fetch the figure again for fresh ones before downloading.
        figure = runCatching { api.figure(id) }.getOrDefault(figure).also(::put)
        val page = (if (dark) figure.darkPage else figure.lightPage) ?: return@withContext null
        download(page)
    }

    fun cacheBytes(): Long = cacheDir.listFiles().orEmpty().sumOf { it.length() }

    fun clearCache() {
        cacheDir.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun cachedFile(page: Page) = File(cacheDir, "${page.sha256}.html")

    private suspend fun download(page: Page): File {
        val bytes = api.downloadPage(page)
        val sum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(sum.equals(page.sha256, ignoreCase = true)) { "Figure page failed its checksum" }
        val file = cachedFile(page)
        File(cacheDir, file.name + ".part").apply { writeBytes(bytes); renameTo(file) }
        return file
    }

    private fun put(figure: Figure) = _figures.update { it + (figure.id to figure) }

    private suspend fun onFinished(figure: Figure) {
        if (figure.status == FigureStatus.SUCCEEDED) {
            runCatching { figure.darkPage?.let { download(it) }; figure.lightPage?.let { download(it) } }
        }
        if (figure.status != FigureStatus.CANCELLED) notify(figure)
    }

    private fun notify(figure: Figure) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Finished figures", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, figure.id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_FIGURE, figure.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val succeeded = figure.status == FigureStatus.SUCCEEDED
        manager.notify(
            figure.id.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (succeeded) "Figure ready" else "Figure stopped")
                .setContentText(if (succeeded) figure.title ?: figure.prompt else figure.error?.message ?: figure.prompt)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val TAG = "VLFigures"
        const val CHANNEL_ID = "figures"
    }
}
