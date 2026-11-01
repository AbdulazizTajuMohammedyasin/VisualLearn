package com.visuallearn.app.api

import android.content.Context
import com.visuallearn.app.BuildConfig
import com.visuallearn.app.auth.TokenStore

/** Release builds always talk to the real server. */
object Backend {
    val usesFake = false

    fun create(context: Context, tokens: TokenStore): VisualLearnApi =
        HttpApi(BuildConfig.API_BASE_URL, tokens, BuildConfig.VERSION_NAME, BuildConfig.UI_LIBRARY_VERSION)
}
