package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Release discovery only. Availability is checked independently against TorBox. */
class KnabenDiscovery(private val http: OkHttpClient, private val endpoint: String = "https://api.knaben.org/v2/") : RecordingDiscovery {
    override suspend fun search(query: String, category: String): List<Audiobook> = withContext(Dispatchers.IO) {
        val url = (endpoint + if (query.isBlank()) "browse" else "search").toHttpUrl().newBuilder()
            .addQueryParameter("c", "1003000").addQueryParameter("s", "60")
            .addQueryParameter("o", "seeders").addQueryParameter("dead", null)
        if (query.isNotBlank()) url.addQueryParameter("q", query.trim().take(250))
        val root = http.newCall(Request.Builder().url(url.build()).build()).execute().use { response ->
            if (!response.isSuccessful) throw ProviderException("Knaben audiobook search is unavailable. Other sources may still be available.")
            runCatching { NarrioJson.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }
                .getOrElse { throw ProviderException("Knaben returned an unreadable search response.") }
        }
        parse(root)
    }

    override suspend fun recording(id: String): Audiobook = throw ProviderException("Choose an indexed release from search to inspect its cached audio files.")

    companion object {
        fun parse(root: JsonObject): List<Audiobook> = root.objects("hits").mapNotNull { hit ->
            val categories = (hit["categoryId"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
            val hash = hit.text("hash").lowercase()
            if (1003000 !in categories || !hash.matches(Regex("[0-9a-f]{40}"))) return@mapNotNull null
            val title = hit.text("title").trim()
            if (title.isBlank()) return@mapNotNull null
            // A release name is not authoritative narration or language metadata.
            Audiobook("knaben:$hash", title, "Author not verified", "Narrator not verified", "Language not verified",
                description = "Audiobook release indexed by Knaben from ${hit.text("tracker").ifBlank { "a source provider" }}. Narration, language and abridgment are not verified. Check the release title and audio filenames before choosing this recording.",
                torrentHash = hash, provider = "knaben", detailsLoaded = true,
                magnetUri = hit.text("magnetUrl").takeIf { it.startsWith("magnet:?", true) }
                    ?: "magnet:?xt=urn:btih:$hash", releaseSizeBytes = hit.number("bytes"), seeders = hit.number("seeders").coerceAtLeast(0))
        }.distinctBy { it.torrentHash }
    }
}
