package com.visuallearn.app.api

import android.content.Context
import com.visuallearn.app.BuildConfig
import com.visuallearn.app.auth.TokenStore

/** Debug builds: the in-process fake unless `-Pvisuallearn.apiBaseUrl=…` points at a real server. */
object Backend {
    val usesFake get() = BuildConfig.API_BASE_URL.isBlank()

    fun create(context: Context, tokens: TokenStore): VisualLearnApi =
        if (usesFake) FakeApi(context, tokens)
        else HttpApi(BuildConfig.API_BASE_URL, tokens, BuildConfig.VERSION_NAME, BuildConfig.UI_LIBRARY_VERSION)
}
