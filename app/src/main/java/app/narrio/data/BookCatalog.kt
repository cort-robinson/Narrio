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
    private val apple: AppleBooks? = null,
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
        fun relevant(book: BookDetails): Boolean {
            if (terms.isBlank()) return true
            val text = " ${BookIdentity.normalize(book.title + " " + book.authors.joinToString(" "))} "
            return BookIdentity.normalize(terms).split(' ').filter { it.isNotBlank() }.all { " $it " in text }
        }
        if (terms.isBlank() && apple != null) {
            val (found, covered) = unbranded(read { apple.popular(category) })
            val popular = collapse(found, now())
            val books = popular.filter { it.description.isNotBlank() && it.coverUrl.isNotBlank() }.ifEmpty { popular }
            if (books.size >= MIN_BOOKS) {
                synchronized(cache) { cache[key] = Cached(books, now() + if (covered) 6 * 60 * 60_000L else RETRY_MS) }
                return books
            }
        }
        val browseTerm = when (category) { "Wonder" -> "fantasy"; "All" -> "bestsellers"; else -> category.lowercase(Locale.ROOT) }
        // Apple's catalog leads typed searches: it lists widely published recordings, which listening sources usually carry.
        var appleFailed = false
        var candidates = if (terms.isNotBlank() && apple != null) {
            try { apple.search(terms) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { appleFailed = true; emptyList() }
        } else emptyList()
        // The Audible catalog fills gaps, such as store exclusives and smaller series.
        if (candidates.size < MIN_BOOKS) {
            var store = read { addonSearch?.invoke(terms.ifBlank { browseTerm }) ?: metadata.audible(terms.ifBlank { browseTerm }, 50) }
            // Keyword browsing is only a fallback; store exclusives rarely have another listening source.
            if (terms.isBlank()) store = store.filterNot { it.publisher.equals(STORE_EXCLUSIVE, ignoreCase = true) }
            candidates += store.filter(::relevant)
        }
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
        val (found, covered) = unbranded(candidates)
        val all = collapse(found, now())
        val books = all.filter { it.description.isNotBlank() && it.coverUrl.isNotBlank() }.ifEmpty { all }
        if (books.isEmpty() && (failed || appleFailed)) throw ProviderException("Book metadata is unavailable. Try again shortly, or listen from your saved shelf.")
        if (books.isNotEmpty() || !(failed || appleFailed)) synchronized(cache) {
            // A browse fallback stands in for unavailable charts only briefly, as do results missing Apple or clean covers.
            val brief = books.isEmpty() || (terms.isBlank() && apple != null) || appleFailed || !covered
            cache[key] = Cached(books, now() + if (brief) RETRY_MS else 24 * 60 * 60_000L)
        }
        return books
    }

    /**
     * Books whose only covers carry Audible's banner use their ebook's cover: one lookup per author, a few authors per list.
     * The flag reports whether every lookup completed, so a list with failed lookups is retried soon.
     */
    private suspend fun unbranded(candidates: List<BookDetails>): Pair<List<BookDetails>, Boolean> = coroutineScope {
        fun branded(book: BookDetails) = book.brandedCover && book.coverUrl.isNotBlank()
        val needed = candidates.groupBy(::identity).values
            .filter { books -> books.any(::branded) && books.none { it.coverUrl.isNotBlank() && !it.brandedCover } }.map { it.first(::branded) }
        val lookups = needed.groupBy { BookIdentity.authors(it.authors.first()) }.values.take(MAX_AUTHOR_LOOKUPS)
            .map { books -> async { metadata.unbranded(books) } }.awaitAll()
        val covers = lookups.filterNotNull().flatten().filterNot { it.brandedCover }.associate { identity(it) to it.coverUrl }
        candidates.map { book -> covers[identity(book)]?.takeIf { branded(book) }?.let { book.copy(coverUrl = it, brandedCover = false) } ?: book } to
            lookups.none { it == null }
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
        private const val MIN_BOOKS = 8
        private const val RETRY_MS = 5 * 60_000L
        private const val STORE_EXCLUSIVE = "Audible Originals"
        // Apple allows about 20 requests a minute.
        private const val MAX_AUTHOR_LOOKUPS = 4

        private fun identity(book: BookDetails) = BookIdentity.key(book.title, book.authors.joinToString(", "))

        internal fun collapse(candidates: List<BookDetails>, updatedAt: Long): List<Audiobook> = candidates
            .filter { it.title.isNotBlank() && it.authors.isNotEmpty() }
            .groupBy(::identity)
            .map { (identity, editions) ->
                val chosen = editions.maxBy { (if (it.description.isNotBlank()) 2 else 0) + (if (it.coverUrl.isNotBlank()) 1 else 0) }
                val covers = (listOf(chosen) + editions).filter { it.coverUrl.isNotBlank() }
                val narrators = editions.map { it.narrators }.distinct().singleOrNull().orEmpty()
                Audiobook("catalog:$identity", BookIdentity.title(chosen.title), chosen.authors.joinToString(", "),
                    narrator = narrators.joinToString(", ").ifBlank { "Narrator depends on source" },
                    language = "Language depends on source", description = chosen.description.ifBlank { editions.firstOrNull { it.description.isNotBlank() }?.description.orEmpty() },
                    coverUrl = (covers.firstOrNull { !it.brandedCover } ?: covers.firstOrNull())?.coverUrl.orEmpty(),
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
