package com.visuallearn.app.offline

import android.util.Log
import com.visuallearn.app.api.VisualLearnApi
import com.visuallearn.app.chat.ChatRepository
import com.visuallearn.app.figures.FiguresRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the phone's copy in step with the server through /sync: on start, after
 * signing in and whenever the connection comes back. Deletions made elsewhere
 * (another device, or an admin) are removed from the phone too.
 */
class SyncManager(
    private val api: VisualLearnApi,
    private val store: OfflineStore,
    private val chat: ChatRepository,
    private val figures: FiguresRepository,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    fun start(online: StateFlow<Boolean>, signedIn: StateFlow<Boolean>) {
        scope.launch {
            combine(online, signedIn) { o, s -> o && s }
                .distinctUntilChanged()
                .filter { it }
                .collect { syncNow() }
        }
    }

    fun syncNow() = scope.launch { syncOnce() }

    /** One sync; false when it failed (offline, server unreachable). */
    suspend fun syncOnce(): Boolean = mutex.withLock {
        runCatching {
            val result = api.sync(store.cursor)
            result.deletedConversationIds.forEach(chat::removeLocal)
            result.conversations.forEach { chat.applyRemote(it) }
            figures.applySync(result.figures, result.deletedFigureIds)
            store.cursor = result.cursor
        }.onFailure { Log.w(TAG, "sync failed", it) }.isSuccess
    }

    private companion object {
        const val TAG = "VLSync"
    }
}
