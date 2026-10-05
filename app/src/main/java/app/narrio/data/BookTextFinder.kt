package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.CancellationException

/**
 * Finds book text for a recording without asking, in order of evidence: files shipped with the
 * recording, ebooks already in the TorBox account, TorBox-cached ebook releases, then public domain
 * text. Every candidate must name the same title and author; the first that parses is attached.
 */
class BookTextFinder(
    private val gutenberg: GutenbergTextDiscovery,
    private val knaben: KnabenDiscovery,
    private val torbox: TorBoxDelivery,
    private val ebookSearch: suspend (String) -> List<Audiobook> = knaben::ebooks,
) {
    suspend fun find(book: Audiobook, source: AudioSource?, connected: Boolean, step: (String) -> Unit, attach: suspend (BookTextSource) -> Unit): Boolean {
        suspend fun attempt(candidates: List<BookTextSource>): Boolean {
            for (candidate in candidates.take(MAX_ATTEMPTS)) {
                try { attach(candidate); return true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A broken or unreadable edition: try the next one. */ }
            }
            return false
        }
        suspend fun read(block: suspend () -> List<BookTextSource>) = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }

        step("Checking this recording's files")
        if (attempt(companions(source).sortedBy { !EbookMatch.matches(book, it.title) })) return true
        if (connected && !BookMetadata.unknown(book.author)) {
            step("Checking your TorBox ebooks")
            if (attempt(read { torbox.accountText().filter { (release, file) -> EbookMatch.matches(book, release) || EbookMatch.matches(book, file.title) }.map { it.second }.preferEpub() })) return true
            step("Checking TorBox for a cached ebook")
            if (attempt(read { cachedReleases(book) })) return true
        }
        step("Checking Project Gutenberg")
        return attempt(read { gutenberg.search("${BookIdentity.title(book.title)} ${book.author}".trim()).filter { EbookMatch.matches(book, "${it.title.substringBefore(';')} - ${it.author}") } })
    }

    suspend fun cachedReleases(book: Audiobook): List<BookTextSource> {
        val releases = SourceQuality.searchTitles(book).flatMap { ebookSearch("$it ${book.author}") }
            .distinctBy { it.torrentHash }.filter { EbookMatch.matches(book, it.title) }.sortedByDescending { it.seeders }.take(20)
        if (releases.isEmpty()) return emptyList()
        val cached = torbox.cachedTextFiles(releases.map { it.torrentHash })
        return releases.mapNotNull { release ->
            val files = cached[release.torrentHash.lowercase()].orEmpty()
            val file = files.firstOrNull { textFileFormat(it) == "EPUB" } ?: files.singleOrNull { textFileFormat(it) == "TXT" } ?: return@mapNotNull null
            BookTextSource("knaben:${release.torrentHash}:$file", release.title, format = textFileFormat(file)!!, provider = "torbox-cache",
                attribution = "Cached ebook release via TorBox", torrentHash = release.torrentHash, magnetUri = release.magnetUri, fileName = file)
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val MIN_WORDS = 2000
        /** Spoken words per millisecond at a brisk narration pace. */
        private const val WORDS_PER_MS = 2.6 / 1000

        /**
         * Rejects files that only accompany a book (cover scans, track lists, excerpts): an automatically
         * chosen text must hold at least a quarter of the words the recording's length implies.
         */
        fun plausible(document: BookText, book: Audiobook, source: AudioSource?): Boolean {
            val words = document.chapters.sumOf { chapter -> chapter.passages.sumOf { it.weight.toLong() } }
            val duration = source?.parts?.sumOf { it.durationMs }?.takeIf { it > 0 } ?: book.durationMs
            return words >= maxOf(MIN_WORDS.toLong(), (duration * WORDS_PER_MS / 4).toLong())
        }

        /** Companion text in the playing recording; timing tracks are bound to one part and need a choice. */
        fun companions(source: AudioSource?): List<BookTextSource> = source?.textFiles.orEmpty().filter { it.format != "VTT" }.preferEpub()

        private fun List<BookTextSource>.preferEpub() = sortedBy { if (it.format == "EPUB") 0 else 1 }
    }
}

/** Release names are free text, so an ebook must carry the exact title and every author name. */
object EbookMatch {
    private val noise = Regex("(?i)\\b(?:e-?books?|epub|mobi|azw3?|kfx|pdf|txt|retail|ebook ?collection)\\b")
    private val unrelated = Regex("\\b(?:summar(?:y|ies)|study guide|analysis|workbook|cliffs ?notes|spark ?notes|collection|box ?set|bundle|omnibus|anthology|complete (?:series|works)|sample|sequel|companion)\\b")
    private val extension = Regex("(?i)\\.(?:epub|txt|mobi|azw3?|pdf)$")
    // Escaped for Android's ICU engine, which rejects the bare brackets the JVM accepts.
    private val bracketed = Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}")
    private val separators = Regex("\\s+[-\u2013\u2014]\\s+|\\s+by\\s+|\\s*:\\s+")

    fun matches(book: Audiobook, release: String): Boolean {
        if (BookMetadata.unknown(book.author) || release.isBlank()) return false
        val cleaned = release.substringAfterLast('/').replace('_', ' ').replace(extension, "").replace(bracketed, " ").replace(noise, " ")
        val titles = SourceQuality.searchTitles(book).map(BookIdentity::normalize).filter(String::isNotBlank)
        val segments = cleaned.split(separators).map(BookIdentity::normalize).filter(String::isNotBlank)
        val title = segments.firstOrNull { it in titles } ?: return false
        val rest = " " + segments.filter { it != title }.joinToString(" ") + " "
        if (unrelated.containsMatchIn(rest)) return false
        return BookIdentity.normalize(book.author).split(' ').filter(String::isNotBlank).all { " $it " in rest }
    }
}
