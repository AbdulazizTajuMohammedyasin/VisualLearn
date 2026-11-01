package com.visuallearn.app

import android.app.Application
import com.visuallearn.app.chat.ChatController
import com.visuallearn.app.chat.ChatStore
import com.visuallearn.app.gallery.Gallery
import com.visuallearn.app.jobs.FigureJobs
import com.visuallearn.app.linux.EnvController
import com.visuallearn.app.settings.Settings

class VisualLearnApp : Application() {
    lateinit var envController: EnvController
        private set
    lateinit var settings: Settings
        private set
    lateinit var figureJobs: FigureJobs
        private set
    lateinit var chatStore: ChatStore
        private set
    lateinit var chat: ChatController
        private set
    lateinit var gallery: Gallery
        private set

    override fun onCreate() {
        super.onCreate()
        envController = EnvController(this)
        settings = Settings(this)
        figureJobs = FigureJobs(this, envController, settings)
        chatStore = ChatStore(this)
        chat = ChatController(chatStore, figureJobs, settings)
        gallery = Gallery(this)
    }
}
