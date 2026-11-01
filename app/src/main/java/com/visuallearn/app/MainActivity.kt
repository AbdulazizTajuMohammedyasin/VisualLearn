package com.visuallearn.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visuallearn.app.ui.ChatScreen
import com.visuallearn.app.ui.EnvironmentScreen
import com.visuallearn.app.ui.FigureScreen
import com.visuallearn.app.ui.FiguresScreen
import com.visuallearn.app.ui.SettingsScreen
import com.visuallearn.app.ui.VisualLearnTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** A figure job to open, from a notification tap. */
    private val pendingJob = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as VisualLearnApp
        handleIntent(intent)
        setContent {
            VisualLearnTheme {
                AppNavigation(app, pendingJob)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_JOB)?.let { pendingJob.value = it }
        runDebugTask(intent)
    }

    /** Debug builds only: `adb shell am start -n com.visuallearn.app/.MainActivity --es task checks` */
    private fun runDebugTask(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val app = application as VisualLearnApp
        val controller = app.envController
        when (intent?.getStringExtra("task")) {
            "install" -> controller.installRootfs()
            "tools" -> controller.installTools()
            "setup" -> controller.setUpEverything()
            "checks" -> controller.runChecks()
            "demo" -> controller.buildDemo()
            "figure" -> intent.getStringExtra("prompt")?.let { app.figureJobs.create(it, "dark") }
        }
    }

    companion object {
        const val EXTRA_JOB = "job"
    }
}

private sealed interface Screen {
    data object Chat : Screen
    data class Figure(val jobId: String) : Screen
    data object Figures : Screen
    data object Settings : Screen
    data object Environment : Screen
}

private fun Screen.encode(): String = when (this) {
    Screen.Chat -> "chat"
    is Screen.Figure -> "figure:$jobId"
    Screen.Figures -> "figures"
    Screen.Settings -> "settings"
    Screen.Environment -> "environment"
}

private fun decode(s: String): Screen = when {
    s.startsWith("figure:") -> Screen.Figure(s.removePrefix("figure:"))
    s == "figures" -> Screen.Figures
    s == "settings" -> Screen.Settings
    s == "environment" -> Screen.Environment
    else -> Screen.Chat
}

@Composable
private fun AppNavigation(app: VisualLearnApp, pendingJob: MutableStateFlow<String?>) {
    val stack = rememberSaveable(
        saver = listSaver(save = { list -> list.map { it.encode() } }, restore = { saved -> mutableStateListOf(*saved.map(::decode).toTypedArray()) }),
    ) { mutableStateListOf<Screen>(Screen.Chat) }
    fun push(screen: Screen) = stack.add(screen)
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    val openJob by pendingJob.collectAsStateWithLifecycle()
    openJob?.let { id ->
        pendingJob.value = null
        push(Screen.Figure(id))
    }

    val settingsState by app.settings.state.collectAsStateWithLifecycle()
    val darkFigures = settingsState.darkFigures ?: isSystemInDarkTheme()

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val screen = stack.last()) {
        Screen.Chat -> ChatScreen(
            chat = app.chat,
            store = app.chatStore,
            jobs = app.figureJobs,
            hasApiKey = settingsState.hasApiKey,
            onOpenFigure = { push(Screen.Figure(it)) },
            onOpenSettings = { push(Screen.Settings) },
            onOpenExplore = null,
            onOpenFigures = { push(Screen.Figures) },
        )
        is Screen.Figure -> FigureScreen(app.figureJobs, screen.jobId, darkFigures, onBack = ::pop)
        Screen.Figures -> FiguresScreen(app.figureJobs, onOpen = { push(Screen.Figure(it)) }, onBack = ::pop)
        Screen.Settings -> SettingsScreen(
            settings = app.settings,
            onBack = ::pop,
            onOpenEnvironment = { push(Screen.Environment) },
        )
        Screen.Environment -> EnvironmentScreen(app.envController, onBack = ::pop)
    }
}
