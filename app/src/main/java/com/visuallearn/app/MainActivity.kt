package com.visuallearn.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visuallearn.app.ui.ChatScreen
import com.visuallearn.app.ui.ExploreScreen
import com.visuallearn.app.ui.FigureScreen
import com.visuallearn.app.ui.FiguresScreen
import com.visuallearn.app.ui.GalleryFigureScreen
import com.visuallearn.app.ui.LessonScreen
import com.visuallearn.app.ui.KnowledgeScreen
import com.visuallearn.app.ui.LessonsScreen
import com.visuallearn.app.ui.LicensesScreen
import com.visuallearn.app.ui.SettingsScreen
import com.visuallearn.app.ui.SignInScreen
import com.visuallearn.app.ui.StorageScreen
import com.visuallearn.app.ui.VisualLearnTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** A figure to open, from a notification tap. */
    private val pendingFigure = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as VisualLearnApp
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Figures finish long after the user leaves; the notification is how they find out.
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            VisualLearnTheme {
                AppNavigation(app, pendingFigure)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_FIGURE)?.let { pendingFigure.value = it }
    }

    companion object {
        const val EXTRA_FIGURE = "figure"
    }
}

private sealed interface Screen {
    data object Chat : Screen
    data class Figure(val id: String) : Screen
    data object Figures : Screen
    data object Settings : Screen
    data object Explore : Screen
    data class GalleryFigure(val key: String) : Screen
    data object Storage : Screen
    data object Licenses : Screen
    data object Lessons : Screen
    data class Lesson(val id: String) : Screen
    data object Knowledge : Screen
}

private fun Screen.encode(): String = when (this) {
    Screen.Chat -> "chat"
    is Screen.Figure -> "figure:$id"
    Screen.Figures -> "figures"
    Screen.Settings -> "settings"
    Screen.Explore -> "explore"
    is Screen.GalleryFigure -> "gallery:$key"
    Screen.Storage -> "storage"
    Screen.Licenses -> "licenses"
    Screen.Lessons -> "lessons"
    is Screen.Lesson -> "lesson:$id"
    Screen.Knowledge -> "knowledge"
}

private fun decode(s: String): Screen = when {
    s.startsWith("figure:") -> Screen.Figure(s.removePrefix("figure:"))
    s.startsWith("gallery:") -> Screen.GalleryFigure(s.removePrefix("gallery:"))
    s.startsWith("lesson:") -> Screen.Lesson(s.removePrefix("lesson:"))
    s == "lessons" -> Screen.Lessons
    s == "knowledge" -> Screen.Knowledge
    s == "figures" -> Screen.Figures
    s == "settings" -> Screen.Settings
    s == "explore" -> Screen.Explore
    s == "storage" -> Screen.Storage
    s == "licenses" -> Screen.Licenses
    else -> Screen.Chat
}

@Composable
private fun AppNavigation(app: VisualLearnApp, pendingFigure: MutableStateFlow<String?>) {
    val signedIn by app.tokens.signedIn.collectAsStateWithLifecycle()
    if (!signedIn) {
        SignInScreen(app.api)
        return
    }

    val stack = rememberSaveable(
        saver = listSaver(save = { list -> list.map { it.encode() } }, restore = { saved -> mutableStateListOf(*saved.map(::decode).toTypedArray()) }),
    ) { mutableStateListOf<Screen>(Screen.Chat) }
    fun push(screen: Screen) = stack.add(screen)
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    LaunchedEffect(Unit) {
        app.chat.refreshList()
        app.figures.refresh()
    }
    val openFigure by pendingFigure.collectAsStateWithLifecycle()
    LaunchedEffect(openFigure) {
        openFigure?.let { id ->
            pendingFigure.value = null
            push(Screen.Figure(id))
        }
    }

    val settingsState by app.settings.state.collectAsStateWithLifecycle()
    val online by app.connectivity.online.collectAsStateWithLifecycle()
    val darkFigures = settingsState.darkFigures ?: isSystemInDarkTheme()

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val screen = stack.last()) {
        Screen.Chat -> ChatScreen(
            chat = app.chat,
            figures = app.figures,
            online = online,
            onOpenFigure = { push(Screen.Figure(it)) },
            onOpenSettings = { push(Screen.Settings) },
            onOpenExplore = { push(Screen.Explore) },
            onOpenFigures = { push(Screen.Figures) },
            lessons = app.lessons,
            onOpenLessons = { push(Screen.Lessons) },
            onOpenLesson = { push(Screen.Lesson(it)) },
            onOpenKnowledge = if (app.knowledge != null) ({ push(Screen.Knowledge) }) else null,
        )
        is Screen.Figure -> FigureScreen(app.figures, screen.id, darkFigures, onBack = ::pop)
        Screen.Figures -> FiguresScreen(app.figures, onOpen = { push(Screen.Figure(it)) }, onBack = ::pop)
        Screen.Settings -> SettingsScreen(
            api = app.api,
            settings = app.settings,
            onBack = ::pop,
            onOpenStorage = { push(Screen.Storage) },
            onOpenLicenses = { push(Screen.Licenses) },
        )
        Screen.Explore -> ExploreScreen(app.gallery, onOpen = { push(Screen.GalleryFigure(it.key)) }, onBack = ::pop)
        is Screen.GalleryFigure -> app.gallery.find(screen.key)
            ?.let { GalleryFigureScreen(app.gallery, it, onBack = ::pop) }
            ?: ExploreScreen(app.gallery, onOpen = { push(Screen.GalleryFigure(it.key)) }, onBack = ::pop)
        Screen.Storage -> StorageScreen(app.figures, onBack = ::pop)
        Screen.Licenses -> LicensesScreen(onBack = ::pop)
        Screen.Lessons -> app.lessons?.let { LessonsScreen(it, onOpen = { id -> push(Screen.Lesson(id)) }, onBack = ::pop) } ?: pop()
        is Screen.Lesson -> app.lessons?.let {
            LessonScreen(it, app.figures, screen.id, onOpenFigure = { id -> push(Screen.Figure(id)) }, onBack = ::pop)
        } ?: pop()
        Screen.Knowledge -> app.knowledge?.let { KnowledgeScreen(it, app.lessons, onOpenLesson = { id -> push(Screen.Lesson(id)) }, onBack = ::pop) } ?: pop()
    }
}
