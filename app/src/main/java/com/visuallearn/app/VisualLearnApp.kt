package com.visuallearn.app

import android.app.Application
import com.visuallearn.app.api.Backend
import com.visuallearn.app.api.VisualLearnApi
import com.visuallearn.app.auth.TokenStore
import com.visuallearn.app.chat.ChatRepository
import com.visuallearn.app.figures.FiguresRepository
import com.visuallearn.app.gallery.Gallery
import com.visuallearn.app.settings.Settings
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

    override fun onCreate() {
        super.onCreate()
        tokens = TokenStore(this)
        api = Backend.create(this, tokens)
        settings = Settings(this)
        figures = FiguresRepository(this, api)
        chat = ChatRepository(api, figures)
        gallery = Gallery(this)
        removeOnDeviceEnvironment()
    }

    /** Builds before the server pivot kept a ~1.4 GB Debian guest and local chats in app storage. */
    private fun removeOnDeviceEnvironment() {
        val leftovers = listOf(File(filesDir, "linux"), File(filesDir, "chats")).filter { it.exists() }
        if (leftovers.isNotEmpty()) thread(name = "cleanup", priority = Thread.MIN_PRIORITY) {
            leftovers.forEach { it.deleteRecursively() }
        }
    }
}
