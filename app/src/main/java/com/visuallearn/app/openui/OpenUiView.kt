package com.visuallearn.app.openui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** A follow-up an interactive part sends to the assistant (OpenUI `@ToAssistant`, or "Explain my mistakes"). */
data class OpenUiAction(val name: String, val message: String, val context: Map<String, Any?>)

/**
 * Renders one OpenUI Lang program with the bundled VisualLearn component library
 * (assets/openui/index.html, built from ui-library/). The page runs with no network
 * and no file access; it talks to the app only through the `VLBridge` messages below.
 *
 * [key] identifies the message part; [savedState] restores answers and card positions.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OpenUiView(
    key: String,
    program: String,
    streaming: Boolean,
    dark: Boolean,
    savedState: String?,
    onStateChange: (String) -> Unit,
    onAction: (OpenUiAction) -> Unit,
    onOpenFigure: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var heightCss by remember(key) { mutableIntStateOf(knownHeights[key] ?: 0) }
    val latest by rememberUpdatedState(Callbacks(onStateChange, onAction, onOpenFigure))
    // The page owns its state once loaded; only the first render restores it.
    val initialState = remember(key) { savedState }
    val render = JSONObject()
        .put("program", program)
        .put("streaming", streaming)
        .put("dark", dark)
        .put("state", initialState?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject())
        .toString()

    AndroidView(
        modifier = modifier.fillMaxWidth().height(if (heightCss > 0) heightCss.dp else 120.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setGeolocationEnabled(false)
                isVerticalScrollBarEnabled = false
                webViewClient = SandboxClient()
                val bridge = Bridge(this) { message ->
                    when (message.optString("type")) {
                        "ready" -> {
                            tag = Loaded(ready = true, rendered = null)
                            push(this)
                        }
                        "height" -> message.optInt("height").takeIf { it > 0 }?.let {
                            heightCss = it
                            knownHeights[key] = it
                        }
                        "state" -> message.optJSONObject("state")?.let { latest.onStateChange(it.toString()) }
                        "action" -> latest.onAction(
                            OpenUiAction(
                                name = message.optString("name"),
                                message = message.optString("message"),
                                context = message.optJSONObject("context")?.toMap().orEmpty(),
                            ),
                        )
                        "openFigure" -> message.optString("figureId").takeIf { it.isNotEmpty() }?.let(latest.onOpenFigure)
                    }
                }
                addJavascriptInterface(bridge, "VLBridge")
                tag = Loaded(ready = false, rendered = null)
                loadDataWithBaseURL(BASE_URL, page(context), "text/html", "utf-8", null)
            }
        },
        update = { webView ->
            webView.setTag(PENDING, render)
            push(webView)
        },
        onRelease = { it.destroy() },
    )
}

private data class Callbacks(
    val onStateChange: (String) -> Unit,
    val onAction: (OpenUiAction) -> Unit,
    val onOpenFigure: (String) -> Unit,
)

private data class Loaded(val ready: Boolean, val rendered: String?)

/** Sends the latest render input once the page is ready, skipping repeats. */
private fun push(webView: WebView) {
    val loaded = webView.tag as? Loaded ?: return
    val pending = webView.getTag(PENDING) as? String ?: return
    if (!loaded.ready || loaded.rendered == pending) return
    webView.tag = loaded.copy(rendered = pending)
    webView.evaluateJavascript("window.VisualLearn.render($pending)", null)
}

private class Bridge(private val webView: WebView, private val onMessage: WebView.(JSONObject) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(json: String) {
        val message = runCatching { JSONObject(json) }.getOrNull() ?: return
        main.post { webView.onMessage(message) }
    }
}

private class SandboxClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.url.scheme == "data" || request.url.scheme == "blob") return null
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}

private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { k -> opt(k).takeUnless { it == JSONObject.NULL } }

private const val BASE_URL = "https://openui.visuallearn.invalid/"
private val PENDING = com.visuallearn.app.R.id.openui_pending

/** Last known content heights, so a part scrolled back into view doesn't jump. */
private val knownHeights = mutableMapOf<String, Int>()

@Volatile private var cachedPage: String? = null

private fun page(context: Context): String =
    cachedPage ?: context.assets.open("openui/index.html").bufferedReader().use { it.readText() }.also { cachedPage = it }
