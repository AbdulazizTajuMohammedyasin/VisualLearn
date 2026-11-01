package com.visuallearn.app.figure

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * Shows an Anatomy figure. Figures are single self-contained HTML files, so the
 * page gets JavaScript but no file access, no network and no navigation.
 * [key] identifies the content; the page reloads only when it changes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FigureView(key: String, html: () -> String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setGeolocationEnabled(false)
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                webViewClient = SandboxClient()
            }
        },
        update = { webView ->
            if (webView.tag != key) {
                webView.tag = key
                webView.loadDataWithBaseURL(BASE_URL, html(), "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

private const val BASE_URL = "https://figure.visuallearn.invalid/"

private class SandboxClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.url.scheme == "data" || request.url.scheme == "blob") return null
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
