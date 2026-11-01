package com.visuallearn.app.gallery

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class GalleryFigure(
    val id: String,
    val author: String,
    val title: String,
    val snippet: String,
    val kind: String,
    val model: String?,
    val minutes: Int?,
    val tags: List<String>,
    val created: String,
    val imageUrl: String,
) {
    val key get() = "$author/$id"
}

data class GalleryDetail(val prompt: String, val license: String, val sourceUrl: String, val liveUrl: String)

/**
 * The Anatomy community gallery (github.com/wheresryan22/anatomy-community). Its
 * `previews` branch publishes a JSON feed and a built, self-contained page per figure.
 * Figures are MIT or CC-BY-4.0 and must be shown with their author.
 */
class Gallery(context: Context) {

    private val cacheDir = File(context.cacheDir, "gallery").apply { mkdirs() }
    @Volatile private var index: Map<String, GalleryFigure> = emptyMap()

    /** A figure from the last loaded list, by [GalleryFigure.key]. */
    fun find(key: String): GalleryFigure? = index[key]

    suspend fun list(): List<GalleryFigure> = withContext(Dispatchers.IO) {
        val json = cached("search.json", maxAgeMs = 6 * HOUR) { fetch("$RAW/feed/search.json") }
        val arr = JSONObject(json).getJSONArray("figures")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GalleryFigure(
                id = o.getString("id"),
                author = o.getString("author"),
                title = o.getString("title"),
                snippet = o.optString("snippet"),
                kind = o.optString("kind", "SVG"),
                model = o.optString("model").takeIf { it.isNotEmpty() && it != "null" },
                minutes = if (o.isNull("minutes")) null else o.optInt("minutes"),
                tags = o.optJSONArray("tags").strings(),
                created = o.optString("created"),
                imageUrl = "$RAW/${o.getString("image")}",
            )
        }.also { list -> index = list.associateBy { it.key } }
    }

    suspend fun detail(figure: GalleryFigure): GalleryDetail = withContext(Dispatchers.IO) {
        val json = cached("${figure.author}-${figure.id}.json", maxAgeMs = 24 * HOUR) {
            fetch("$RAW/feed/figures/${figure.author}/${figure.id}.json")
        }
        val o = JSONObject(json)
        GalleryDetail(
            prompt = o.optString("prompt", figure.snippet),
            license = o.optString("license", "MIT"),
            sourceUrl = "https://github.com/wheresryan22/anatomy-community/tree/main/${o.optString("source")}",
            liveUrl = "$RAW/${o.getString("live")}",
        )
    }

    /** The figure's built page, downloaded once and kept in the cache. */
    suspend fun page(figure: GalleryFigure, detail: GalleryDetail): File = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "${figure.author}-${figure.id}.html")
        if (!file.exists()) {
            val tmp = File(cacheDir, file.name + ".part")
            tmp.writeText(fetch(detail.liveUrl))
            tmp.renameTo(file)
        }
        file
    }

    private fun cached(name: String, maxAgeMs: Long, load: () -> String): String {
        val file = File(cacheDir, name)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeMs
        if (fresh) return file.readText()
        return try {
            load().also { file.writeText(it) }
        } catch (e: Exception) {
            if (file.exists()) file.readText() else throw e // offline: use a stale copy
        }
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode} for $url" }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private companion object {
        const val RAW = "https://raw.githubusercontent.com/wheresryan22/anatomy-community/previews"
        const val HOUR = 3_600_000L
    }
}
