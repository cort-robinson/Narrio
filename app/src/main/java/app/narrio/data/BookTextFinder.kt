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
    private val webAccountText: suspend () -> List<Pair<String, BookTextSource>> = { emptyList() },
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
            if (attempt(read { accountMatches(book).strong() })) return true
            if (attempt(read { webAccountMatches(book).strong() })) return true
            step("Checking TorBox for a cached ebook")
            if (attempt(read { cachedReleases(book).strong() })) return true
        }
        step("Checking Project Gutenberg")
        return attempt(read { publicMatches(book).strong() })
    }

    /**
     * Ebook files already in the TorBox account's torrents, with how well each names this book. The reader's own
     * search [words], when given, also admit files that carry them, as possible matches.
     */
    suspend fun accountMatches(book: Audiobook, words: String = "") = rated(book, torbox.accountText(), words)

    /** Ready ebooks among the account's web downloads. */
    suspend fun webAccountMatches(book: Audiobook, words: String = "") = rated(book, webAccountText(), words)

    private fun rated(book: Audiobook, files: List<Pair<String, BookTextSource>>, words: String) = files.map { (release, file) ->
        EbookCandidate(file, maxOf(EbookMatch.confidence(book, release, words), EbookMatch.confidence(book, file.title, words)))
    }.filter { it.confidence != MatchConfidence.NONE }.sortedBy { if (it.source.format == "EPUB") 0 else 1 }

    /** Gutenberg searched by the book's title and author, or by the reader's own [words]. */
    suspend fun publicMatches(book: Audiobook, words: String = "") = gutenberg.search(words.ifBlank { "${BookIdentity.title(book.title)} ${book.author}" }.trim()).map {
        EbookCandidate(it, EbookMatch.confidence(book, "${it.title.substringBefore(';')} - ${it.author}", words))
    }.filter { it.confidence != MatchConfidence.NONE }

    private fun List<EbookCandidate>.strong() = filter { it.confidence == MatchConfidence.STRONG }.map { it.source }

    /** Cached ebook releases from every enabled ebook add-on, for the automatic attach above. */
    suspend fun cachedReleases(book: Audiobook): List<EbookCandidate> =
        cachedFiles(book, SourceQuality.searchTitles(book).flatMap { ebookSearch("$it ${book.author}") })

    /**
     * The releases in [found] whose ebook files TorBox already has, confirmed or possible matches only. Asking TorBox
     * whether a release is cached never adds it to the account.
     */
    suspend fun cachedFiles(book: Audiobook, found: List<Audiobook>, words: String = ""): List<EbookCandidate> {
        val releases = found.distinctBy { it.torrentHash }.map { it to EbookMatch.confidence(book, it.title, words) }.filter { it.second != MatchConfidence.NONE }
            .sortedWith(compareBy<Pair<Audiobook, MatchConfidence>> { it.second != MatchConfidence.STRONG }.thenByDescending { it.first.seeders }).take(20)
        if (releases.isEmpty()) return emptyList()
        val cached = torbox.cachedText(releases.map { it.first.torrentHash })
        return releases.mapNotNull { (release, confidence) ->
            val files = cached[release.torrentHash.lowercase()].orEmpty()
            val (file, size) = files.firstOrNull { textFileFormat(it.first) == "EPUB" } ?: files.singleOrNull { textFileFormat(it.first) == "TXT" } ?: return@mapNotNull null
            EbookCandidate(BookTextSource("knaben:${release.torrentHash}:$file", release.title, format = textFileFormat(file)!!, provider = "torbox-cache",
                attribution = "Cached ebook release via TorBox", torrentHash = release.torrentHash, magnetUri = release.magnetUri, fileName = file, sizeBytes = size), confidence)
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

    fun matches(book: Audiobook, release: String): Boolean = confidence(book, release) == MatchConfidence.STRONG

    /**
     * As [confidence], but the reader's own search [words] can also name a possible match: the release carries every
     * one of them (author names may be left out) and nothing unrelated they didn't ask for. Words alone are never
     * [MatchConfidence.STRONG], since they don't establish the book's identity.
     */
    fun confidence(book: Audiobook, release: String, words: String): MatchConfidence {
        val named = confidence(book, release)
        if (words.isBlank() || named == MatchConfidence.STRONG || release.isBlank()) return named
        val asked = BookIdentity.normalize(words)
        val name = " " + BookIdentity.normalize(release.substringAfterLast('/').replace('_', ' ').replace(extension, "").replace(noise, " ")) + " "
        if (unrelated.containsMatchIn(name) && !unrelated.containsMatchIn(asked)) return named
        val authors = BookIdentity.normalize(book.author).split(' ').toSet()
        val needed = asked.split(' ').filter { it.isNotBlank() && it !in authors }
        return if (needed.isNotEmpty() && needed.all { " $it " in name }) maxOf(named, MatchConfidence.POSSIBLE) else named
    }

    /**
     * [MatchConfidence.STRONG] names the exact title and every author name. [MatchConfidence.POSSIBLE] names the exact
     * title with part of the author, nothing else at all ("Pride and Prejudice [EPUB]"), or a book whose author is
     * unverified, so the reader checks it.
     */
    fun confidence(book: Audiobook, release: String): MatchConfidence {
        if (release.isBlank()) return MatchConfidence.NONE
        val cleaned = release.substringAfterLast('/').replace('_', ' ').replace(extension, "").replace(bracketed, " ").replace(noise, " ")
        val titles = SourceQuality.searchTitles(book).map(BookIdentity::normalize).filter(String::isNotBlank)
        val segments = cleaned.split(separators).map(BookIdentity::normalize).filter(String::isNotBlank)
        val title = segments.firstOrNull { it in titles } ?: return MatchConfidence.NONE
        val rest = " " + segments.filter { it != title }.joinToString(" ") + " "
        if (unrelated.containsMatchIn(rest)) return MatchConfidence.NONE
        val names = if (BookMetadata.unknown(book.author)) emptyList() else BookIdentity.normalize(book.author).split(' ').filter(String::isNotBlank)
        val named = names.count { " $it " in rest }
        return when {
            names.isNotEmpty() && named == names.size -> MatchConfidence.STRONG
            // An unverified author can't rule a release in or out.
            names.isEmpty() || named > 0 || rest.isBlank() -> MatchConfidence.POSSIBLE
            else -> MatchConfidence.NONE
        }
    }
}

/** A found ebook and how closely it names the book. */
data class EbookCandidate(val source: BookTextSource, val confidence: MatchConfidence)
