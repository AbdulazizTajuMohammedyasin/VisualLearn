package com.visuallearn.app

import android.app.Application
import com.visuallearn.app.api.Backend
import com.visuallearn.app.api.VisualLearnApi
import com.visuallearn.app.auth.TokenStore
import com.visuallearn.app.chat.ChatRepository
import com.visuallearn.app.figures.FigureCheckWorker
import com.visuallearn.app.figures.FiguresRepository
import com.visuallearn.app.gallery.Gallery
import com.visuallearn.app.learn.LearnApi
import com.visuallearn.app.learn.LessonRepository
import com.visuallearn.app.offline.Connectivity
import com.visuallearn.app.offline.OfflineStore
import com.visuallearn.app.offline.SyncManager
import com.visuallearn.app.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.thread

class VisualLearnApp : Application() {
    lateinit var tokens: TokenStore
        private set
    lateinit var api: VisualLearnApi
        private set
    lateinit var settings: Settings
        private set
    lateinit var figures: FiguresRepository
        private set
    lateinit var chat: ChatRepository
        private set
    lateinit var gallery: Gallery
        private set
    lateinit var connectivity: Connectivity
        private set
    lateinit var sync: SyncManager
        private set

    /** Lessons on LearnHarness; null without a real server (the debug fake has none). */
    var lessons: LessonRepository? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        tokens = TokenStore(this)
        api = Backend.create(this, tokens)
        settings = Settings(this)
        val store = OfflineStore(this)
        figures = FiguresRepository(this, api, store)
        chat = ChatRepository(api, figures, store)
        lessons = (api as? LearnApi)?.let { LessonRepository(it, figures) }
        gallery = Gallery(this)
        connectivity = Connectivity(this)
        sync = SyncManager(api, store, chat, figures, scope)
        sync.start(connectivity.online, tokens.signedIn)
        clearOnSignOut(store)
        checkFiguresInBackground()
        removeOnDeviceEnvironment()
    }

    /** Background checks run only while some figure is unfinished. */
    private fun checkFiguresInBackground() = scope.launch {
        figures.figures.map { figures.hasActive(it) }.distinctUntilChanged().collect { active ->
            if (active) FigureCheckWorker.schedule(this@VisualLearnApp) else FigureCheckWorker.cancel(this@VisualLearnApp)
        }
    }

    /** Another person may sign in next on this phone; they must not see the last one's data. */
    private fun clearOnSignOut(store: OfflineStore) = scope.launch {
        tokens.signedIn.drop(1).filter { !it }.collect {
            chat.clear()
            lessons?.close()
            figures.clear()
            store.clear()
        }
    }

    /** Builds before the server pivot kept a ~1.4 GB Debian guest and local chats in app storage. */
    private fun removeOnDeviceEnvironment() {
        val leftovers = listOf(File(filesDir, "linux"), File(filesDir, "chats")).filter { it.exists() }
        if (leftovers.isNotEmpty()) thread(name = "cleanup", priority = Thread.MIN_PRIORITY) {
            leftovers.forEach { it.deleteRecursively() }
        }
    }
}
