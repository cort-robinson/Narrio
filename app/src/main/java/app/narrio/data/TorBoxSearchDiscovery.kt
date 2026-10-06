package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TorBox's own release index, which covers trackers Knaben doesn't. Results use Knaben's release shape and
 * hash-based ID, so the same release from either index is checked against the TorBox cache once.
 */
class TorBoxSearchDiscovery(
    private val http: OkHttpClient,
    private val credential: () -> String?,
    private val endpoint: String = "https://search-api.torbox.app/",
) : RecordingDiscovery {
    override suspend fun search(query: String, category: String): List<Audiobook> = withContext(Dispatchers.IO) {
        val key = credential()?.takeIf { it.isNotBlank() }
        if (key == null || query.isBlank()) return@withContext emptyList()
        val url = endpoint.toHttpUrl().newBuilder().addPathSegments("torrents/search").addPathSegment(query.trim().take(200))
            .addQueryParameter("metadata", "false").addQueryParameter("check_cache", "false").build()
        val root = http.readCancellable(Request.Builder().url(url).header("Authorization", "Bearer $key").build()) { response ->
            if (!response.isSuccessful) throw ProviderException("TorBox release search is unavailable. Other sources may still be available.")
            runCatching { NarrioJson.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }
                .getOrElse { throw ProviderException("TorBox returned an unreadable search response.") }
        }
        parse(root)
    }

    override suspend fun recording(id: String): Audiobook = throw ProviderException("Choose an indexed release from search to inspect its cached audio files.")

    companion object {
        private val notAudio = Regex("(?i)\\b(?:2160p|1080p|720p|480p|x26[45]|h\\.?26[45]|hevc|web-?dl|webrip|blu-?ray|bdrip|hdtv|dvdrip|epub|mobi|azw3?|pdf)\\b")

        fun parse(root: JsonObject): List<Audiobook> = ((root["data"] as? JsonObject)?.objects("torrents")).orEmpty().mapNotNull { hit ->
            val hash = hit.text("hash").lowercase()
            val title = hit.text("raw_title").ifBlank { hit.text("title") }.trim()
            if (!hash.matches(Regex("[0-9a-f]{40}")) || title.isBlank() || notAudio.containsMatchIn(title)) return@mapNotNull null
            Audiobook("knaben:$hash", title, "Author not verified", "Narrator not verified", "Language not verified",
                description = "Release indexed by TorBox search from ${hit.text("tracker").ifBlank { "a source provider" }}. Narration, language and abridgment are not verified. Check the release title and audio filenames before choosing this recording.",
                torrentHash = hash, provider = "knaben", detailsLoaded = true,
                magnetUri = hit.text("magnet").takeIf { it.startsWith("magnet:?", true) } ?: "magnet:?xt=urn:btih:$hash",
                releaseSizeBytes = hit.number("size"), seeders = hit.number("last_known_seeders").coerceAtLeast(0))
        }.distinctBy { it.torrentHash }
    }
}
