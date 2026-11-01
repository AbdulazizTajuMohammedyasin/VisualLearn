package com.visuallearn.app.linux

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class EnvState(
    val installed: Boolean = false,
    val toolsReady: Boolean = false,
    val runningTask: String? = null,
    val lastResult: String? = null,
    val log: List<String> = emptyList(),
    val figures: List<File> = emptyList(),
)

/**
 * Runs one Linux maintenance task at a time (install, setup, checks) and keeps the
 * process in the foreground while any task or figure job is running.
 */
class EnvController(private val appContext: Context) {

    val env = LinuxEnv(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(EnvState())
    val state: StateFlow<EnvState> = _state.asStateFlow()
    private var foregroundHolds = 0

    init {
        refresh()
    }

    fun installRootfs() = launchTask("Install Debian") {
        env.installRootfs(::log)
        0
    }

    fun installTools() = launchTask("Install tools") { runAsset("setup.sh") }

    fun runChecks() = launchTask("Run checks") { runAsset("checks.sh") }

    fun buildDemo() = launchTask("Build demo figure") { runAsset("build-demo.sh") }

    /** Installs Debian if needed, then the tools: the whole first-run setup in one task. */
    fun setUpEverything() = launchTask("Set up") {
        if (!env.isInstalled) env.installRootfs(::log)
        runAsset("setup.sh")
    }

    /** Debug builds: runs [command] in the guest and logs its output. */
    fun runDebugCommand(command: String) = launchTask("Shell") {
        env.run(command, onLine = ::log)
    }

    fun isToolsReady(): Boolean = env.guestFile(TOOLS_MARKER).exists()

    fun installAsset(asset: String, guestPath: String) {
        val target = env.guestFile(guestPath)
        target.parentFile?.mkdirs()
        appContext.assets.open("linux/$asset").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }

    /** Deletes the whole guest, figure jobs included. */
    fun removeEnvironment() {
        if (_state.value.runningTask != null) return
        env.baseDir.deleteRecursively()
        refresh()
    }

    @Synchronized
    fun acquireForeground(text: String) {
        foregroundHolds++
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, EnvService::class.java).putExtra(EnvService.EXTRA_TEXT, text),
        )
    }

    @Synchronized
    fun releaseForeground() {
        foregroundHolds = (foregroundHolds - 1).coerceAtLeast(0)
        if (foregroundHolds == 0) appContext.stopService(Intent(appContext, EnvService::class.java))
    }

    private suspend fun runAsset(name: String): Int {
        installAsset(name, "/opt/visuallearn/$name")
        if (name == "setup.sh") installAsset("agent/requirements.txt", "/opt/visuallearn/requirements.txt")
        return env.run("sh /opt/visuallearn/$name", onLine = ::log)
    }

    private fun launchTask(name: String, block: suspend () -> Int) {
        if (_state.value.runningTask != null) return
        _state.update { it.copy(runningTask = name, lastResult = null, log = it.log + "── $name ──") }
        acquireForeground("$name…")
        scope.launch {
            val started = System.currentTimeMillis()
            val result = try {
                val code = block()
                if (code == 0) "$name: OK" else "$name: failed (exit $code)"
            } catch (e: Exception) {
                log("ERROR: ${e.message ?: e::class.java.simpleName}")
                "$name: error"
            }
            val secs = (System.currentTimeMillis() - started) / 1000
            log("$result in ${secs}s")
            _state.update { it.copy(runningTask = null, lastResult = result) }
            refresh()
            releaseForeground()
        }
    }

    private fun refresh() {
        val figures = env.guestFile("/root/figures").listFiles { f -> f.extension == "html" }
            ?.sortedBy { it.name }.orEmpty()
        _state.update { it.copy(installed = env.isInstalled, toolsReady = isToolsReady(), figures = figures) }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        _state.update { s -> s.copy(log = (s.log + line).takeLast(MAX_LOG_LINES)) }
    }

    private companion object {
        const val MAX_LOG_LINES = 600
        const val TAG = "VLEnv"
        const val TOOLS_MARKER = "/opt/visuallearn/.tools-ready"
    }
}
