package com.visuallearn.app.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.visuallearn.app.api.Tokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Session tokens, encrypted with an AES-GCM key that never leaves the Android
 * Keystore; only ciphertext is written to preferences.
 */
class TokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    @Volatile private var cached: Tokens? = load()
    private val _signedIn = MutableStateFlow(cached != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    fun current(): Tokens? = cached

    fun save(tokens: Tokens) {
        val json = JSONObject()
            .put("access", tokens.accessToken)
            .put("expires", tokens.accessTokenExpiresAt)
            .put("refresh", tokens.refreshToken)
        prefs.edit { putString(KEY, encrypt(json.toString())) }
        cached = tokens
        _signedIn.value = true
    }

    fun clear() {
        prefs.edit { remove(KEY) }
        cached = null
        _signedIn.value = false
    }

    private fun load(): Tokens? = prefs.getString(KEY, null)?.let(::decrypt)?.let {
        runCatching {
            val o = JSONObject(it)
            Tokens(o.getString("access"), o.getLong("expires"), o.getString("refresh"))
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed, 0, 12))
        }
        String(cipher.doFinal(sealed, 12, sealed.size - 12))
    }.getOrNull()

    private companion object {
        const val KEY = "tokens"
        const val ALIAS = "visuallearn_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
