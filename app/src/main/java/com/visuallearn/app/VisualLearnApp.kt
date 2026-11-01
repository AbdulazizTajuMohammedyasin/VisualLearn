package com.visuallearn.app

import android.app.Application
import com.visuallearn.app.linux.EnvController

class VisualLearnApp : Application() {
    lateinit var envController: EnvController
        private set

    override fun onCreate() {
        super.onCreate()
        envController = EnvController(this)
    }
}
