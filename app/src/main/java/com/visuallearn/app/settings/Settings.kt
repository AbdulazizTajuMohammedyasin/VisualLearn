package com.visuallearn.app.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class FigureModel(val id: String, val label: String) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5"),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5"),
}

data class SettingsState(
    val hasApiKey: Boolean,
    val figureModel: FigureModel,
    val darkFigures: Boolean?,
    val setupSkipped: Boolean,
)

/**
 * App settings. The Claude API key is encrypted with an AES-GCM key that never
 * leaves the Android Keystore; only the ciphertext is stored in preferences.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)?.let(::decrypt)
        set(value) {
            prefs.edit {
                if (value.isNullOrBlank()) remove(KEY_API) else putString(KEY_API, encrypt(value.trim()))
            }
            _state.value = read()
        }

    var figureModel: FigureModel
        get() = FigureModel.entries.firstOrNull { it.id == prefs.getString(KEY_MODEL, null) } ?: FigureModel.OPUS
        set(value) {
            prefs.edit { putString(KEY_MODEL, value.id) }
            _state.value = read()
        }

    /** The user chose to chat without setting up the figure environment. */
    var setupSkipped: Boolean
        get() = prefs.getBoolean(KEY_SETUP_SKIPPED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_SETUP_SKIPPED, value) }
            _state.value = read()
        }

    /** null follows the system theme. */
    var darkFigures: Boolean?
        get() = if (prefs.contains(KEY_DARK)) prefs.getBoolean(KEY_DARK, true) else null
        set(value) {
            prefs.edit { if (value == null) remove(KEY_DARK) else putBoolean(KEY_DARK, value) }
            _state.value = read()
        }

    private fun read() = SettingsState(
        hasApiKey = prefs.contains(KEY_API),
        figureModel = figureModel,
        darkFigures = darkFigures,
        setupSkipped = setupSkipped,
    )

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed, 0, 12))
        }
        String(cipher.doFinal(sealed, 12, sealed.size - 12))
    }.getOrNull()

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODEL = "figure_model"
        const val KEY_DARK = "dark_figures"
        const val KEY_SETUP_SKIPPED = "setup_skipped"
        const val KEY_ALIAS = "visuallearn_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
