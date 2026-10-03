package app.narrio.data

import app.narrio.domain.BookTextSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

class GutenbergTextDiscovery(private val http: OkHttpClient, private val baseUrl: String = "https://gutendex.com/") {
    suspend fun search(query: String): List<BookTextSource> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = (baseUrl + "books/").toHttpUrl().newBuilder().addQueryParameter("search", query.trim().take(200))
            .addQueryParameter("copyright", "false").build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw ProviderException("Book-text search is unavailable. Try again, or choose a file from your device.")
            val bytes = BookTextParser.readBounded(response.body?.byteStream() ?: throw ProviderException("Book-text search returned no results."), 2 * 1024 * 1024)
            parseResults(NarrioJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
        }
    }

    companion object {
        fun parseResults(root: JsonObject): List<BookTextSource> = root.objects("results").mapNotNull { item ->
            if (item["copyright"] != JsonPrimitive(false) || item.text("media_type") != "Text") return@mapNotNull null
            val formats = item["formats"] as? JsonObject ?: return@mapNotNull null
            val epub = formats.entries.filter { it.key == "application/epub+zip" }.firstOrNull()
            val plain = formats.entries.filter { it.key.startsWith("text/plain") && (it.key.contains("utf-8", true) || !it.key.contains("charset")) }.firstOrNull()
            val chosen = epub ?: plain ?: return@mapNotNull null
            val url = chosen.value.stringValue().replaceFirst(Regex("^http://"), "https://")
            val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return@mapNotNull null
            if (!url.startsWith("https://") || (host != "gutenberg.org" && !host.endsWith(".gutenberg.org"))) return@mapNotNull null
            BookTextSource("gutenberg:${item.number("id")}", item.text("title"),
                item.objects("authors").joinToString(", ") { it.text("name") }, if (epub != null) "EPUB" else "TXT", "gutenberg", url,
                attribution = "Project Gutenberg · ebook ${item.number("id")}", language = item.text("languages"))
        }
    }
}
