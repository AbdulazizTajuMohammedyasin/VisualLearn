package com.visuallearn.app.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(val darkFigures: Boolean?)

/** Local display preferences. Everything about the account lives on the server. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** null follows the system theme. */
    var darkFigures: Boolean?
        get() = if (prefs.contains(KEY_DARK)) prefs.getBoolean(KEY_DARK, true) else null
        set(value) {
            prefs.edit { if (value == null) remove(KEY_DARK) else putBoolean(KEY_DARK, value) }
            _state.value = read()
        }

    private fun read() = SettingsState(darkFigures = darkFigures)

    private companion object {
        const val KEY_DARK = "dark_figures"
    }
}
