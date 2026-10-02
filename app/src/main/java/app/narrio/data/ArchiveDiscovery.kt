package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

class ArchiveDiscovery(private val http: OkHttpClient) : RecordingDiscovery {
    override suspend fun search(query: String, category: String): List<Audiobook> = withContext(Dispatchers.IO) {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(12)
        val clauses = terms.joinToString(" AND ") { term ->
            val clean = term.replace(Regex("[^\\p{L}\\p{N}'-]"), " ").trim()
            "(title:\"$clean\" OR creator:\"$clean\")"
        }
        val categoryFilter = when (category) {
            "Mystery" -> " AND subject:(mystery OR detective OR crime)"
            "Fiction" -> " AND subject:(fiction OR literature OR novel)"
            "Wonder" -> " AND subject:(children OR fantasy OR fairy)"
            "Nonfiction" -> " AND subject:(history OR philosophy OR biography OR nonfiction)"
            else -> ""
        }
        val q = "collection:librivoxaudio AND mediatype:audio" + if (clauses.isNotEmpty()) " AND ($clauses)$categoryFilter" else categoryFilter
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("output", "json")
            .addQueryParameter("rows", "50").addQueryParameter("sort[]", "downloads desc")
        listOf("identifier", "title", "creator", "language", "runtime", "description", "subject").forEach { url.addQueryParameter("fl[]", it) }
        val json = get(url.build().toString())
        (json["response"] as? JsonObject)?.objects("docs").orEmpty().map { parseBook(it, false) }
    }

    override suspend fun recording(id: String): Audiobook = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[A-Za-z0-9_.-]+"))) { "This recording identifier is invalid." }
        parseRecording(get("https://archive.org/metadata/$id"))
    }

    private fun get(url: String): JsonObject = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw ProviderException("The audiobook catalog is unavailable (${r.code}). Try again in a moment.")
        val root = NarrioJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
        if (root.containsKey("error")) throw ProviderException("This recording is unavailable. Choose another edition.")
        root
    }

    companion object {
        fun cleanHtml(value: String): String = value.replace(Regex("<(br|/p|/div)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&nbsp;", " ").replace(Regex("\n[ \\t]*\n+"), "\n\n").trim()

        fun parseBook(meta: JsonObject, loaded: Boolean): Audiobook {
            val description = cleanHtml(meta.text("description"))
            val narrator = Regex("Read (?:in [A-Za-z]+ )?by\\s+([^\\n.]+)", RegexOption.IGNORE_CASE)
                .find(description)?.groupValues?.get(1)?.trim()?.take(150) ?: "Narrator not listed"
            val lang = meta.text("language").let { when (it.lowercase()) { "eng", "en", "english" -> "English"; "" -> "Language not listed"; else -> it } }
            return Audiobook(
                id = meta.text("identifier"), title = meta.text("title").ifBlank { "Untitled recording" },
                author = meta.text("creator").ifBlank { "Author not listed" }, narrator = narrator, language = lang,
                description = description, durationMs = parseDuration(meta.text("runtime")),
                coverUrl = "https://archive.org/services/img/${meta.text("identifier")}",
                subjects = meta.text("subject").split(';', ',').map { it.trim() }.filter { it.isNotBlank() }.take(10),
                detailsLoaded = loaded,
            )
        }

        fun parseRecording(root: JsonObject): Audiobook {
            val meta = root["metadata"] as? JsonObject ?: throw ProviderException("This recording is unavailable.")
            val book = parseBook(meta, true)
            val all = root.objects("files")
            val torrent = all.firstOrNull { it.text("name").endsWith("_archive.torrent") } ?: all.firstOrNull { it.text("name").endsWith(".torrent") }
            fun url(name: String) = "https://archive.org/download/${book.id}/" + name.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            fun part(file: JsonObject) = AudioPart(
                id = "${book.id}:${file.text("name")}", name = file.text("name"),
                title = file.text("title").ifBlank { file.text("name").substringAfterLast('/').substringBeforeLast('.').replace('_', ' ') },
                durationMs = parseDuration(file.text("length")), archiveUrl = url(file.text("name")),
            )
            val mp3s = all.filter { it.text("name").endsWith(".mp3", true) }
            val highQuality = mp3s.filter { !it.text("name").contains("64kb", true) && !it.text("format").contains("64Kbps", true) }
                .ifEmpty { mp3s }
            val originals = highQuality.filter { it.text("source") == "original" }.ifEmpty { highQuality }
            val multipart = originals.sortedWith { a, b -> AudioOrdering.compare(a.text("name"), b.text("name")) }.map(::part)
            val m4b = all.filter { it.text("name").endsWith(".m4b", true) }.sortedWith { a, b -> AudioOrdering.compare(a.text("name"), b.text("name")) }.map(::part)
            val sources = buildList {
                if (multipart.isNotEmpty()) add(AudioSource("${book.id}:mp3", "Chapter files", "MP3", multipart))
                if (m4b.isNotEmpty()) add(AudioSource("${book.id}:m4b", "Whole-book audio", "M4B", m4b))
            }
            return book.copy(sources = sources, torrentUrl = torrent?.let { url(it.text("name")) }.orEmpty(), torrentHash = torrent?.text("btih").orEmpty())
        }
    }
}

class ProviderException(message: String) : java.io.IOException(message)
