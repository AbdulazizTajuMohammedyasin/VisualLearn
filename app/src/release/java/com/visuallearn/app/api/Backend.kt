package com.visuallearn.app.api

import android.content.Context
import com.visuallearn.app.BuildConfig
import com.visuallearn.app.auth.TokenStore

/** Release builds always talk to the real server. */
object Backend {
    val usesFake = false

    fun create(context: Context, tokens: TokenStore): VisualLearnApi =
        HttpApi(BuildConfig.API_BASE_URL, tokens, BuildConfig.VERSION_NAME, BuildConfig.UI_LIBRARY_VERSION)

    // The identity provider is still an open decision (docs/server-api.md, section 10).
    val signInLabel = "Sign in"
    suspend fun signIn(api: VisualLearnApi) {
        throw ApiException(ApiError("not_configured", "Sign-in isn't available in this build yet.", false))
    }
}
