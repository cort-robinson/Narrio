package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Descriptive book discovery. A catalog entry never implies an available recording. */
class BookCatalog(
    private val metadata: BookMetadata,
    private val googleUrl: String = "https://www.googleapis.com/books/v1/volumes",
    private val now: () -> Long = System::currentTimeMillis,
    private val addonSearch: (suspend (String) -> List<BookDetails>)? = null,
    private val addonRevision: () -> Int = { 0 },
) {
    private data class Cached(val books: List<Audiobook>, val expires: Long)
    private val cache = object : LinkedHashMap<String, Cached>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) = size > 32
    }

    suspend fun search(query: String, category: String = "All"): List<Audiobook> {
        val terms = query.trim().take(200)
        val key = "${BookIdentity.normalize(terms)}|$category|${addonRevision()}"
        synchronized(cache) { cache[key]?.takeIf { it.expires > now() } }?.let { return it.books }
        var failed = false
        suspend fun read(block: suspend () -> List<BookDetails>) = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true; emptyList() }
        val browseTerm = when (category) { "Wonder" -> "fantasy"; "All" -> "bestsellers"; else -> category.lowercase(Locale.ROOT) }
        var candidates = read { addonSearch?.invoke(terms.ifBlank { browseTerm }) ?: metadata.audible(terms.ifBlank { browseTerm }, 50) }
        fun relevant(book: BookDetails): Boolean {
            if (terms.isBlank()) return true
            val text = " ${BookIdentity.normalize(book.title + " " + book.authors.joinToString(" "))} "
            return BookIdentity.normalize(terms).split(' ').filter { it.isNotBlank() }.all { " $it " in text }
        }
        candidates = candidates.filter(::relevant)
        if (candidates.isEmpty() || candidates.any { it.description.isBlank() || it.coverUrl.isBlank() }) {
            candidates += read { google(terms, category, browseTerm) }.filter(::relevant)
        }
        if (candidates.none { it.description.isNotBlank() && it.coverUrl.isNotBlank() }) {
            // Work descriptions are separate requests; keep fallback hydration small and paced.
            val works = (if (addonSearch == null) read { metadata.librarySearch(terms.ifBlank { browseTerm }) } else candidates.filter { it.provider == "Open Library" }).filter(::relevant).take(6)
            for ((index, work) in works.withIndex()) {
                if (index > 0) delay(1_000)
                candidates += metadata.libraryDetails(work)
            }
        }
        val all = collapse(candidates, now())
        val books = all.filter { it.description.isNotBlank() && it.coverUrl.isNotBlank() }.ifEmpty { all }
        if (books.isEmpty() && failed) throw ProviderException("Book metadata is unavailable. Try again shortly, or listen from your saved shelf.")
        if (books.isNotEmpty() || !failed) synchronized(cache) {
            cache[key] = Cached(books, now() + if (books.isEmpty()) 5 * 60_000 else 24 * 60 * 60_000L)
        }
        return books
    }

    private suspend fun google(query: String, category: String, browseTerm: String): List<BookDetails> {
        val subject = when (category) { "Wonder" -> "fantasy"; "All" -> ""; else -> category.lowercase(Locale.ROOT) }
        val terms = query.ifBlank { if (subject.isBlank()) browseTerm else "subject:$subject" }
        val url = googleUrl.toHttpUrl().newBuilder().addQueryParameter("q", terms)
            .addQueryParameter("printType", "books").addQueryParameter("maxResults", "40").build()
        return metadata.get(url).objects("items").mapNotNull { item ->
                val info = item["volumeInfo"] as? JsonObject ?: return@mapNotNull null
                val authors = (info["authors"] as? JsonArray)?.map { it.stringValue() }?.filter(String::isNotBlank).orEmpty()
                if (info.text("title").isBlank() || authors.isEmpty() || item.text("id").isBlank()) return@mapNotNull null
                val images = info["imageLinks"] as? JsonObject
                val image = images?.text("thumbnail").orEmpty().ifBlank { images?.text("smallThumbnail").orEmpty() }
                BookDetails(info.text("title"), authors, emptyList(), MetadataText.clean(info.text("description")),
                    image.replaceFirst("http://", "https://").takeIf { it.startsWith("https://") }.orEmpty(),
                    info.text("language"), "Google Books", "https://books.google.com/books?id=${java.net.URLEncoder.encode(item.text("id"), "UTF-8")}")
        }
    }

    companion object {
        internal fun collapse(candidates: List<BookDetails>, updatedAt: Long): List<Audiobook> = candidates
            .filter { it.title.isNotBlank() && it.authors.isNotEmpty() }
            .groupBy { BookIdentity.key(it.title, it.authors.joinToString(", ")) }
            .map { (identity, editions) ->
                val chosen = editions.maxBy { (if (it.description.isNotBlank()) 2 else 0) + (if (it.coverUrl.isNotBlank()) 1 else 0) }
                val narrators = editions.map { it.narrators }.distinct().singleOrNull().orEmpty()
                Audiobook("catalog:$identity", BookIdentity.title(chosen.title), chosen.authors.joinToString(", "),
                    narrator = narrators.joinToString(", ").ifBlank { "Narrator depends on source" },
                    language = "Language depends on source", description = chosen.description.ifBlank { editions.firstOrNull { it.description.isNotBlank() }?.description.orEmpty() },
                    coverUrl = chosen.coverUrl.ifBlank { editions.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty() },
                    provider = "catalog", detailsLoaded = true, metadataSource = chosen.provider, metadataUrl = chosen.url,
                    metadataUpdatedAtMs = updatedAt, narratorFromCatalog = narrators.isNotEmpty())
            }.sortedByDescending { (if (it.description.isNotBlank()) 2 else 0) + (if (it.coverUrl.isNotBlank()) 1 else 0) }
    }
}

/** Provider-independent identity collapses editions, while keeping different authors and sequels apart. */
internal object BookIdentity {
    private const val EDITION = "(?:penguin (?:modern )?classics|oxford world'?s classics|modern library(?: classics)?|(?:\\d+(?:st|nd|rd|th) )?anniversary(?: edition)?|(?:special|revised|illustrated|deluxe|collector'?s|annotated|expanded|large print) edition)"
    fun title(value: String) = BookMetadata.cleanRelease(value)
        .replace(Regex("\\s*\\($EDITION\\)\\s*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*[:\\-]\\s*$EDITION\\s*$", RegexOption.IGNORE_CASE), "").trim()
    fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun authors(value: String) = normalize(value).split(' ').filter(String::isNotBlank).sorted().joinToString(" ")
    fun key(title: String, author: String): String = MessageDigest.getInstance("SHA-256")
        .digest("${normalize(title(title))}|${authors(author)}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
