package app.narrio.data

import app.narrio.domain.Audiobook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Book information enriches an existing release; it never creates or replaces an audio source. */
class BookMetadata(
    http: OkHttpClient,
    private val audibleUrl: String = "https://api.audible.com/1.0/catalog/",
    private val libraryUrl: String = "https://openlibrary.org/",
    private val now: () -> Long = System::currentTimeMillis,
    private val addonSearch: (suspend (String) -> List<BookDetails>)? = null,
    private val addonRevision: () -> Int = { 0 },
) {
    private val http = http.newBuilder().connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    private val requests = Semaphore(2)
    private data class Cached(val candidates: List<BookDetails>, val expires: Long)
    private val cache = object : LinkedHashMap<String, Cached>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) = size > 128
    }

    suspend fun enrich(book: Audiobook, force: Boolean = false): Audiobook {
        // Archive metadata belongs to the actual recording, including its readers and artwork.
        if (book.provider == "archive") return book
        if (!force && book.metadataSource.isNotBlank() && now() - book.metadataUpdatedAtMs in 0 until DAY) return book
        val query = cleanRelease(book.releaseTitle.ifBlank { book.title }).take(200)
        if (query.isBlank()) return book
        val key = "$query|${book.author.takeUnless(::unknown).orEmpty()}|${book.language}|${addonRevision()}".lowercase(Locale.ROOT)
        return try {
            val candidates = requests.withPermit {
                val cached = synchronized(cache) { cache[key]?.takeIf { it.expires > now() } }
                if (!force && cached != null) cached.candidates else {
                    var failed = false
                    suspend fun optional(block: suspend () -> List<BookDetails>): List<BookDetails> = try { block() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true; emptyList() }
                    var found = optional { addonSearch?.invoke(query) ?: audible(query) }
                    if (addonSearch == null && match(book, found) == null) found = optional { openLibrary(book, query) }
                    if (addonSearch != null) {
                        val selected = match(book, found)
                        if (selected?.provider == "Open Library") {
                            val hydrated = libraryDetails(selected)
                            found = found.map { if (it == selected) hydrated else it }
                        }
                    }
                    if (found.isNotEmpty() || !failed) synchronized(cache) {
                        cache[key] = Cached(found, now() + if (found.isEmpty()) 5 * 60_000 else DAY)
                    }
                    found
                }
            }
            val matched = match(book, candidates) ?: return book
            val eligible = candidates.filter { score(book, it) == score(book, matched) && sameAuthors(it.authors, matched.authors) }
            val narratorHint = eligible.firstOrNull { candidate ->
                candidate.narrators.isNotEmpty() && candidate.narrators.all { containsName(book.releaseTitle.ifBlank { book.title }, it) }
            }
            val narrators = narratorHint?.narrators ?: eligible.map { it.narrators }.distinct().singleOrNull().orEmpty()
            val chosen = narratorHint ?: matched
            val useNarrator = unknown(book.narrator) && narrators.isNotEmpty()
            book.copy(
                title = chosen.title,
                author = if (unknown(book.author)) chosen.authors.joinToString(", ") else book.author,
                narrator = if (useNarrator) narrators.joinToString(", ") else book.narrator,
                narratorFromCatalog = if (useNarrator) true else book.narratorFromCatalog,
                description = chosen.description.ifBlank { book.description },
                coverUrl = chosen.coverUrl.ifBlank { book.coverUrl },
                releaseTitle = book.releaseTitle.ifBlank { book.title },
                metadataSource = chosen.provider,
                metadataUrl = chosen.url,
                metadataUpdatedAtMs = now(),
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { book } // Metadata outages must not prevent listening or erase saved details.
    }

    internal suspend fun audible(query: String, count: Int = 20): List<BookDetails> {
        val url = (audibleUrl + "products").toHttpUrl().newBuilder()
            .addQueryParameter("keywords", query).addQueryParameter("num_results", count.toString())
            .addQueryParameter("products_sort_by", "Relevance")
            .addQueryParameter("response_groups", "product_desc,product_attrs,product_extended_attrs,contributors,media")
            .addQueryParameter("image_sizes", "500,1024").build()
        return get(url).objects("products").mapNotNull { product ->
            val asin = product.text("asin")
            val authors = product.objects("authors").map { it.text("name") }.filter(String::isNotBlank)
            if (product.text("content_type") != "Product" || product.text("title").isBlank() || !asin.matches(Regex("[A-Z0-9]{10}")) || authors.isEmpty()) return@mapNotNull null
            val images = product["product_images"] as? JsonObject
            BookDetails(product.text("title"), authors, product.objects("narrators").map { it.text("name") }.filter(String::isNotBlank),
                MetadataText.clean(product.text("publisher_summary").ifBlank { product.text("merchandising_summary") }.ifBlank { product.text("short_description") }),
                images?.entries?.sortedByDescending { it.key.toIntOrNull() ?: 0 }?.firstNotNullOfOrNull { (_, value) -> secureImage(value.stringValue()) }.orEmpty(),
                product.text("language"), "Audible", "https://www.audible.com/pd/$asin", product.text("publisher_name"))
        }
    }

    private suspend fun openLibrary(book: Audiobook, query: String): List<BookDetails> {
        val candidates = librarySearch(query)
        val selected = match(book, candidates) ?: return candidates
        val full = libraryDetails(selected)
        return candidates.map { if (it.url == selected.url) full else it }
    }

    internal suspend fun librarySearch(query: String): List<BookDetails> {
        val url = (libraryUrl + "search.json").toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("limit", "10")
            .addQueryParameter("fields", "key,title,author_name,cover_i").build()
        return get(url).objects("docs").mapNotNull { doc ->
            val key = doc.text("key").let { if (it.startsWith("/")) it else "/works/$it" }
            val authors = (doc["author_name"] as? JsonArray)?.map { it.stringValue() }?.filter(String::isNotBlank).orEmpty()
            if (!key.matches(Regex("/works/OL[0-9]+W")) || doc.text("title").isBlank() || authors.isEmpty()) return@mapNotNull null
            BookDetails(doc.text("title"), authors, emptyList(), "",
                doc.number("cover_i").takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg?default=false" }.orEmpty(),
                "", "Open Library", "https://openlibrary.org$key")
        }
    }

    internal suspend fun libraryDetails(selected: BookDetails): BookDetails {
        val work = try { get((libraryUrl.trimEnd('/') + selected.url.substringAfter("https://openlibrary.org") + ".json").toHttpUrl()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return selected }
        val description = work["description"].let { if (it is JsonObject) it.text("value") else it.stringValue() }
        return selected.copy(description = MetadataText.clean(description))
    }

    internal suspend fun get(url: HttpUrl): JsonObject = withContext(Dispatchers.IO) {
        val response = suspendCancellableCoroutine { continuation ->
            val call = http.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        response.use {
            if (!it.isSuccessful) throw IOException("Book metadata is unavailable.")
            NarrioJson.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
        }
    }

    private fun match(book: Audiobook, candidates: List<BookDetails>): BookDetails? {
        val ranked = candidates.map { it to score(book, it) }.filter { it.second > 0 }
        val best = ranked.filter { it.second == ranked.maxOfOrNull { pair -> pair.second } }.map { it.first }
        // A title shared by different authors is ambiguous without author evidence.
        if (best.isEmpty() || best.any { !sameAuthors(it.authors, best.first().authors) }) return null
        return best.first()
    }

    private fun score(book: Audiobook, candidate: BookDetails): Int {
        val title = normalized(cleanRelease(candidate.title))
        if (title.isBlank()) return 0
        if (!unknown(book.language) && candidate.language.isNotBlank() && normalizedLanguage(book.language) != normalizedLanguage(candidate.language)) return 0
        val release = normalized(cleanRelease(book.releaseTitle.ifBlank { book.title }))
        if (!unknown(book.author)) {
            if (!candidate.authors.any { sameAuthors(listOf(book.author), listOf(it)) }) return 0
            if (normalized(cleanRelease(book.title)) == title) return 4
        }
        if (release == title) return if (unknown(book.author)) 2 else 4
        // Remove the exact title, then require the remaining words to identify its author.
        // This rejects summaries, collections, sequels, and unrelated search results.
        if (!(" $release ").contains(" $title ")) return 0
        val remainder = (" $release ").replaceFirst(" $title ", " ").trim().removePrefix("by ")
        return if (candidate.authors.any { sameAuthors(listOf(remainder), listOf(it)) }) 4 else 0
    }

    private fun sameAuthors(a: List<String>, b: List<String>) =
        a.map { normalized(it).split(' ').filter(String::isNotBlank).sorted() }.toSet() ==
            b.map { normalized(it).split(' ').filter(String::isNotBlank).sorted() }.toSet()

    private fun containsName(text: String, name: String) = (" ${normalized(text)} ").contains(" ${normalized(name)} ")

    companion object {
        private const val DAY = 24 * 60 * 60_000L
        internal fun unknown(value: String) = value.isBlank() || value.startsWith("Author not ") || value.startsWith("Narrator not ") ||
            value.startsWith("Language not ") || value == "Your TorBox library"
        internal fun cleanRelease(value: String): String = value
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("\\((?:unabridged|abridged|audiobook|mp3|m4b|\\d{4})\\)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(?:mp3|m4b|m4a|flac|aac|audiobooks?|unabridged|abridged|\\d+\\s*kbps)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("[_.]"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', '\u2013', '\u2014')
            .let { text -> text.split(Regex("\\s+[-\u2013\u2014]\\s+")).joinToString(" - ") { it.substringBefore(" / ") } }
        private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        private fun normalizedLanguage(value: String) = when (value.lowercase(Locale.ROOT)) { "en", "eng", "english" -> "english"; else -> value.lowercase(Locale.ROOT) }
        private fun secureImage(url: String): String? = url.takeIf { it.startsWith("https://") }
    }
}

data class BookDetails(
    val title: String, val authors: List<String>, val narrators: List<String>, val description: String,
    val coverUrl: String, val language: String, val provider: String, val url: String, val publisher: String = "",
)

/** Plain text is shared by catalog and recording metadata, without requiring Android in contract tests. */
object MetadataText {
    private val entities = mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to " ", "lt" to "<", "gt" to ">",
        "ndash" to "\u2013", "mdash" to "\u2014", "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201c", "rdquo" to "\u201d", "hellip" to "\u2026")
    fun clean(value: String): String = value.replace(Regex("<(script|style)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("<(?:br\\b[^>]*|/(?:p|div|li|h[1-6]))>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[A-Za-z]+);")) { match ->
            val entity = match.groupValues[1]
            if (entity.startsWith('#')) {
                val code = if (entity.startsWith("#x", true)) entity.substring(2).toIntOrNull(16) else entity.substring(1).toIntOrNull()
                code?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }?.let { String(Character.toChars(it)) } ?: match.value
            } else entities[entity] ?: match.value
        }.replace(Regex("[ \\t]+"), " ").replace(Regex(" *\n *"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
}
