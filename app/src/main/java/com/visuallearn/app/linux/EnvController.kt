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
    val runningTask: String? = null,
    val lastResult: String? = null,
    val log: List<String> = emptyList(),
    val figures: List<File> = emptyList(),
)

/**
 * Runs one Linux task at a time and keeps the process alive with a foreground
 * service while it does. Lives for the whole app process.
 */
class EnvController(private val appContext: Context) {

    val env = LinuxEnv(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(EnvState())
    val state: StateFlow<EnvState> = _state.asStateFlow()

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

    private suspend fun runAsset(name: String): Int {
        val target = env.guestFile("/opt/visuallearn/$name")
        target.parentFile?.mkdirs()
        appContext.assets.open("linux/$name").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return env.run("sh /opt/visuallearn/$name", ::log)
    }

    private fun launchTask(name: String, block: suspend () -> Int) {
        if (_state.value.runningTask != null) return
        _state.update { it.copy(runningTask = name, lastResult = null, log = it.log + "── $name ──") }
        ContextCompat.startForegroundService(appContext, Intent(appContext, EnvService::class.java))
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
            appContext.stopService(Intent(appContext, EnvService::class.java))
        }
    }

    private fun refresh() {
        val figures = env.guestFile("/root/figures").listFiles { f -> f.extension == "html" }
            ?.sortedBy { it.name }.orEmpty()
        _state.update { it.copy(installed = env.isInstalled, figures = figures) }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        _state.update { s -> s.copy(log = (s.log + line).takeLast(MAX_LOG_LINES)) }
    }

    private companion object {
        const val MAX_LOG_LINES = 600
        const val TAG = "VLEnv"
    }
}
