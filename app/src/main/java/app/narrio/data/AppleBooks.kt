package app.narrio.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.OffsetDateTime

/**
 * Apple's public book catalog: store-wide audiobook charts (and ebook charts for genres audiobooks lack) for browsing,
 * and audiobook search. Its catalog favors widely
 * published recordings, which listening sources usually carry, over store exclusives. An entry never implies an
 * available recording. Apple allows roughly 20 requests a minute, so callers cache results.
 */
class AppleBooks(
    private val metadata: BookMetadata,
    private val baseUrl: String = "https://itunes.apple.com/",
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Chart order is popularity order; one lookup adds descriptions and artwork for the whole page. */
    suspend fun popular(category: String): List<BookDetails> {
        val genre = genres[category]
        val ebooks = genre in ebookGenres
        val feed = if (ebooks) "toppaidebooks" else "topaudiobooks"
        val chart = metadata.get("${baseUrl}us/rss/$feed/limit=$CHART_SIZE${genre?.let { "/genre=$it" }.orEmpty()}/json".toHttpUrl())
        val entries = ((chart["feed"] as? JsonObject)?.get("entry") as? JsonArray).orEmpty().mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val id = (row["id"] as? JsonObject)?.objectAt("attributes")?.text("im:id").orEmpty()
            val name = row.objectAt("im:name")?.text("label").orEmpty()
            val artist = row.objectAt("im:artist")?.text("label").orEmpty()
            val released = row.objectAt("im:releaseDate")?.text("label").orEmpty()
            val releasedAt = runCatching { OffsetDateTime.parse(released).toInstant().toEpochMilli() }.getOrNull()
            // Preorders have no recording anywhere yet.
            if (!id.matches(Regex("[0-9]{1,15}")) || name.isBlank() || artist.isBlank() || excluded(name) || translated(name, artist) || (releasedAt ?: 0) > now()) null
            else Entry(id, name, artist, (row["im:image"] as? JsonArray)?.lastOrNull()?.let { (it as? JsonObject)?.text("label") }.orEmpty(),
                releasedAt != null && now() - releasedAt < NEW_RELEASE_MS)
        }.sortedBy { it.new } // Listening sources take a while to carry brand-new releases; list them after established books.
        if (entries.isEmpty()) return emptyList()
        val lookup = "${baseUrl}lookup".toHttpUrl().newBuilder().addQueryParameter("id", entries.joinToString(",") { it.id })
            .addQueryParameter("country", "us").build()
        // Descriptions are optional; a failed lookup still leaves the chart's titles and artwork.
        val details = try { metadata.get(lookup).objects("results") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        val found = details.associateBy { (if (ebooks) it.number("trackId") else it.number("collectionId")).toString() }
        return entries.map { entry ->
            val match = found[entry.id]
            val name = (if (ebooks) match?.text("trackName") else match?.text("collectionName")).orEmpty().ifBlank { entry.name }
            BookDetails(title(name), authors(match?.text("artistName").orEmpty().ifBlank { entry.artist }),
                emptyList(), MetadataText.clean(match?.text("description").orEmpty()),
                artwork(match?.text("artworkUrl100").orEmpty().ifBlank { entry.image }), "", SOURCE, link(entry.id, ebooks),
                publisher(match?.text("copyright").orEmpty()))
        }.filter { it.title.isNotBlank() && it.authors.isNotEmpty() }
    }

    /**
     * Audiobook search with descriptions and artwork in one request. Every search word must appear in the store title
     * or author; the store title keeps the series name that the shown title drops.
     */
    suspend fun search(terms: String): List<BookDetails> {
        val words = BookIdentity.normalize(terms).split(' ').filter(String::isNotBlank)
        val url = "${baseUrl}search".toHttpUrl().newBuilder().addQueryParameter("term", terms).addQueryParameter("media", "audiobook")
            .addQueryParameter("entity", "audiobook").addQueryParameter("country", "us").addQueryParameter("limit", "50").build()
        return metadata.get(url).objects("results").mapNotNull { result ->
            val id = result.number("collectionId").toString()
            val name = result.text("collectionName")
            val text = " ${BookIdentity.normalize(name + " " + result.text("artistName"))} "
            if (id == "0" || name.isBlank() || excluded(name) || translated(name, result.text("artistName")) || words.any { " $it " !in text }) null
            else BookDetails(title(name), authors(result.text("artistName")), emptyList(), MetadataText.clean(result.text("description")),
                artwork(result.text("artworkUrl100")), "", SOURCE, link(id, false), publisher(result.text("copyright")))
        }.filter { it.title.isNotBlank() && it.authors.isNotEmpty() }
    }

    private data class Entry(val id: String, val name: String, val artist: String, val image: String, val new: Boolean)

    companion object {
        const val SOURCE = "Apple Books"
        private const val CHART_SIZE = 60
        private const val NEW_RELEASE_MS = 14 * 24 * 60 * 60_000L
        private val ebookGenres = setOf(10048)
        /**
         * Browsing categories mapped to Apple genres; "All" is the store-wide chart. Apple pairs thrillers with mysteries in
         * one genre and has no horror audiobook genre, so Horror uses the horror ebook chart: those books are widely published.
         */
        val genres: Map<String, Int?> = linkedMapOf("All" to null, "Fiction" to 50000040, "Thriller & mystery" to 50000051,
            "Horror" to 10048, "Wonder" to 50000055, "Romance" to 50000069, "Comedy" to 50000046, "Classics" to 50000045, "Kids & teens" to 50000044,
            "Nonfiction" to 50000052, "Biography" to 50000042, "History" to 50000049, "Science" to 50000054,
            "Self-help" to 50000056, "Business" to 50000043, "Travel" to 50000059)

        private val parts = Regex("(?i)\\bdramati[sz]ed\\b|\\(\\s*\\d+ of \\d+\\s*\\)")
        private val marketing = Regex("(?i)^(?:an? |the )?(?:[\\w'’&.-]+ ){0,4}(?:novel|novella|memoir|thriller|mystery|romance)$|book club|\\b(?:sequel|prequel|companion) to\\b|\\b(?:book|volume|vol|part)\\.? ?\\d+$")
        private val translator = Regex("(?i)\\b(?:translator|traductor|traductora|traducteur|traduttore|traduttrice|übersetzer|übersetzerin)\\b")
        private val foreignSeries = Regex("(?i)\\b(?:libro|tomo|tome|band|teil|livro|deel)\\s+\\d")
        private val translation = Regex("(?i)\\([^)]*\\b(?:edition|version|ausgabe|edición|édition)\\)")

        /** Multi-part and dramatized adaptations are different recordings from the book itself. */
        internal fun excluded(name: String) = parts.containsMatchIn(name)

        /** The US store also sells translated recordings, which credit a translator or carry a translated series label. */
        internal fun translated(name: String, artist: String) =
            translator.containsMatchIn(artist) || otherEdition(name) || foreignSeries.containsMatchIn(name)

        /** A translated or special edition's cover is not the listener's book. */
        internal fun otherEdition(name: String) = translation.containsMatchIn(name)

        /**
         * Ebook art named by a non-English ISBN belongs to a translation; ebook art kept with audio artwork is the
         * audiobook's cover again. English ISBNs start 978-0, 978-1, or 979-8.
         */
        internal fun plainEbookArt(url: String) = "/Music" !in url &&
            Regex("/(97[89]\\d{10})\\.[a-z]+/").find(url)?.groupValues?.get(1)?.let { isbn -> listOf("9780", "9781", "9798").any(isbn::startsWith) } != false

        /** Store titles add series, award, and marketing labels; keep the book's own title and real subtitles. */
        internal fun title(name: String): String {
            val bare = name.replace(Regex("\\s*\\[[^]]*]|\\s*\\([^)]*\\)"), "").replace(Regex("\\s+:\\s*"), ": ").replace(Regex("\\s+"), " ").trim()
            val segments = bare.split(": ")
            return (listOf(segments.first()) + segments.drop(1).filterNot { marketing.containsMatchIn(it.trim()) }).joinToString(": ").trim()
        }

        // Apple joins co-authors with "&"; commas belong to names such as "W. Lee Warren, MD".
        internal fun authors(artist: String) = artist.split(Regex("\\s+&\\s+")).map(String::trim).filter(String::isNotBlank)

        internal fun artwork(url: String) = url.takeIf { it.startsWith("https://") }?.replace(Regex("/\\d+x\\d+bb\\.(jpg|png)$"), "/600x600bb.$1").orEmpty()

        /** "℗ 2013 Audible Studios" names the recording's producer. */
        internal fun publisher(copyright: String) = copyright.replace(Regex("^\\s*(?:[℗©]|\\([PC]\\))?\\s*\\d{4}\\s*"), "").trim()

        private fun link(id: String, ebook: Boolean) = "https://books.apple.com/us/${if (ebook) "book" else "audiobook"}/id$id"

        private fun JsonObject.objectAt(key: String) = this[key] as? JsonObject
    }
}
