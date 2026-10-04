package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*

/**
 * [recordings] are confidently matched, verified, and ranked best first. [possible] are other releases that
 * plausibly belong to the book but need the listener's review: weaker identity evidence or unverified files.
 */
data class BookSourceResults(val recordings: List<Audiobook>, val error: String? = null, val possible: List<Audiobook> = emptyList())

/** Only invoked from details; delivery checks never participate in book identification. */
class BookSourceDiscovery(
    private val archive: RecordingDiscovery,
    private val indexed: List<RecordingDiscovery>,
    private val account: suspend (String) -> List<Audiobook>,
    private val checkCached: suspend (List<Audiobook>) -> List<Audiobook>,
    private val loadFiles: suspend (Audiobook) -> Audiobook? = { null },
) {
    suspend fun search(book: Audiobook, connected: Boolean): BookSourceResults = coroutineScope {
        suspend fun read(block: suspend () -> List<Audiobook>): Pair<List<Audiobook>, String?> = try { block() to null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList<Audiobook>() to "Some source providers are unavailable. Try again to check them." }
        val titles = SourceQuality.searchTitles(book)
        val public = titles.map { title -> async { read { archive.search(title) } } }
        // Each index sees different trackers; the same release from several indexes shares one hash-based ID.
        val releases = if (connected) indexed.flatMap { index -> titles.map { title -> async { read { index.searchBook(book, title) } } } } else emptyList()
        // Read the account once: provider substring search cannot match catalog subtitles or punctuation reliably.
        val library = async { if (connected) read { account("") } else emptyList<Audiobook>() to null }
        val results = public.awaitAll() + releases.awaitAll() + library.await()
        val errors = results.mapNotNull { it.second }.toMutableList()
        // Cached filenames and hydrated public metadata can supply author evidence missing from release names.
        val candidates = results.flatMap { it.first }.distinctBy { it.id }.filter { SourceQuality.isCandidate(book, it) }
        val hydrated = candidates.filter { it.provider == "archive" }.take(12).chunked(4).flatMap { batch ->
            batch.map { recording -> async { read { listOf(if (recording.detailsLoaded) recording else archive.recording(recording.id)) } } }.awaitAll()
                .also { fetched -> errors += fetched.mapNotNull { it.second } }.flatMap { it.first }
        }
        var cloud = candidates.filter { it.provider != "archive" }
        val indexedBooks = cloud.filter { it.provider == "knaben" }
        if (indexedBooks.isNotEmpty()) {
            val checked = read { checkCached(indexedBooks) }
            errors += listOfNotNull(checked.second)
            cloud = cloud.filter { it.provider != "knaben" } + checked.first
        }
        var usable = SourceQuality.filter(book, cloud + hydrated)
        // Inspecting uncached torrent metadata is slow; skip it when a ready recording already exists.
        if (usable.none(SourceQuality::ready)) {
            // A cache miss says nothing about the recording's files. Inspect public torrent metadata
            // without adding a torrent, and leave preparation to the listener's explicit action.
            val uncached = cloud.filter { it.provider == "knaben" && it.cacheState == "uncached" && it.seeders > 0 }
                .sortedWith(compareBy<Audiobook> { !SourceQuality.matches(book, it) }.thenByDescending { it.seeders }).take(8)
            val verified = uncached.map { recording -> async { read { listOfNotNull(loadFiles(recording)) } } }.awaitAll()
                .also { fetched -> errors += fetched.mapNotNull { it.second } }.flatMap { it.first }.associateBy { it.id }
            cloud = cloud.map { verified[it.id] ?: it }
            usable = SourceQuality.filter(book, cloud + hydrated)
        }
        val chosen = usable.flatMap { listOf(it.id, it.torrentHash.lowercase()) }.filter(String::isNotBlank).toSet()
        val possible = (cloud + hydrated)
            .filter { it.id !in chosen && it.torrentHash.lowercase() !in chosen && SourceQuality.confidence(book, it) != MatchConfidence.NONE }
            .filter { it.provider != "knaben" || it.cacheState == "cached" || it.seeders > 0 }
            .distinctBy { it.torrentHash.takeIf(String::isNotBlank)?.lowercase() ?: it.id }
            .sortedWith(compareBy<Audiobook> { SourceQuality.confidence(book, it) != MatchConfidence.STRONG }.thenBy { !SourceQuality.ready(it) }.thenByDescending { it.seeders })
            .take(20)
        BookSourceResults(usable, errors.distinct().joinToString(" ").ifBlank { null }, possible)
    }
}

enum class MatchConfidence { NONE, POSSIBLE, STRONG }

/** How a recording differs from other recordings of the same book. Blank fields are unknown. */
data class SourceEdition(val kind: String, val language: String, val narrator: String)

object SourceQuality {
    internal fun searchTitles(book: Audiobook): List<String> {
        val title = BookIdentity.title(book.title)
        return listOf(title, title.substringBefore(':').trim()).filter(String::isNotBlank).distinct()
    }

    internal fun isCandidate(book: Audiobook, recording: Audiobook): Boolean {
        if (BookMetadata.unknown(book.author) || !compatibleAuthor(book, recording)) return false
        val release = " ${BookIdentity.normalize(recording.releaseTitle.ifBlank { recording.title })} "
        return matchingTitle(book, recording) != null || searchTitles(book).any { " ${BookIdentity.normalize(it)} " in release } ||
            recording.sources.any { it.parts.any { part -> bookFile(book, part.name) } }
    }

    private fun compatibleAuthor(book: Audiobook, recording: Audiobook) = BookMetadata.unknown(recording.author) ||
        BookIdentity.authors(book.author) == BookIdentity.authors(recording.author)

    private fun hasAuthor(book: Audiobook, value: String): Boolean {
        val evidence = " ${BookIdentity.normalize(value)} "
        return BookIdentity.normalize(book.author).split(' ').filter(String::isNotBlank).all { " $it " in evidence }
    }

    /** A collection is usable only when its files identify this book independently of sibling titles. */
    private fun bookFile(book: Audiobook, name: String): Boolean = isBookAudioFile(name) && name.split('/', '\\').any { segment ->
        val title = BookIdentity.normalize(BookIdentity.title(if (isAudioFile(segment)) segment.substringBeforeLast('.') else segment))
            .let { if (BookIdentity.normalize(book.title).firstOrNull()?.isDigit() == true) it else it.replace(Regex("^0\\d{1,2}\\s+"), "") }
        searchTitles(book).any { title == BookIdentity.normalize(it) }
    }

    private fun selectBookFiles(book: Audiobook, recording: Audiobook): Audiobook? {
        if (matches(book, recording)) return recording
        if (!compatibleAuthor(book, recording) || BookMetadata.unknown(book.author)) return null
        val release = recording.releaseTitle.ifBlank { recording.title }
        val normalizedRelease = BookIdentity.normalize(release)
        if (searchTitles(book).any { title ->
            Regex("\\b${Regex.escape(BookIdentity.normalize(title))}\\s+(?:(?:book|volume|vol|part)\\s+)?\\d{1,3}\\b").containsMatchIn(normalizedRelease)
        }) return null
        val evidence = (" ${BookIdentity.normalize(release)} ").replaceFirst(" ${BookIdentity.normalize(book.title)} ", " ")
        if (Regex("\\b(?:summar(?:y|ies)|study guide|analysis|sequel|sample|trailer|preview)\\b").containsMatchIn(evidence)) return null
        val sources = recording.sources.mapNotNull { source ->
            val parts = source.parts.filter { bookFile(book, it.name) }
            if (parts.isEmpty()) null else source.copy(parts = parts)
        }
        if (sources.isEmpty()) return null
        fun explicitAuthor(value: String) = value.split(Regex("\\s+[-\u2013\u2014]\\s+|[/\\\\\\[\\]()]"))
            .any { BookIdentity.authors(it) == BookIdentity.authors(book.author) }
        val authorVerified = !BookMetadata.unknown(recording.author) || explicitAuthor(release) ||
            sources.any { it.parts.any { part -> explicitAuthor(part.name) } }
        if (!authorVerified) return null
        // Different books in one torrent must keep separate shelf and listening histories.
        val suffix = ":book:${BookIdentity.key(book.title, book.author)}"
        return recording.copy(id = if (recording.id.endsWith(suffix)) recording.id else recording.id + suffix, sources = sources, bookFilesSelected = true)
    }

    fun matches(book: Audiobook, recording: Audiobook): Boolean = confidence(book, recording) == MatchConfidence.STRONG

    /**
     * STRONG needs this exact title and author evidence; only STRONG recordings are chosen automatically.
     * POSSIBLE has the exact title without author evidence, so the listener decides.
     */
    fun confidence(book: Audiobook, recording: Audiobook): MatchConfidence {
        if (BookMetadata.unknown(book.author) || !compatibleAuthor(book, recording)) return MatchConfidence.NONE
        val release = recording.releaseTitle.ifBlank { recording.title }
        if (describesOtherWorks(book, release)) return MatchConfidence.NONE
        if (strictMatch(book, recording)) return MatchConfidence.STRONG
        val title = releaseTitle(book, release)
        if (title == TitleEvidence.NONE) return MatchConfidence.NONE
        val author = !BookMetadata.unknown(recording.author) || namesAuthor(book, release)
        return if (title == TitleEvidence.EXACT && author) MatchConfidence.STRONG else MatchConfidence.POSSIBLE
    }

    private fun strictMatch(book: Audiobook, recording: Audiobook): Boolean {
        val title = matchingTitle(book, recording) ?: return false
        if (!BookMetadata.unknown(recording.author)) return BookIdentity.authors(book.author) == BookIdentity.authors(recording.author)
        val names = BookIdentity.normalize(book.author).split(' ').filter(String::isNotBlank)
        val release = recording.releaseTitle.ifBlank { recording.title }
        if (hasAuthor(book, release)) return true
        if (recording.sources.flatMap { it.parts }.any { part ->
                isBookAudioFile(part.name) && " $title " in " ${BookIdentity.normalize(part.name)} " && hasAuthor(book, part.name)
            }) return true
        // A distinct title plus an explicit surname/initials author segment is sufficient release evidence.
        // Short/common titles still need the full name or authoritative recording metadata.
        if (title.replace(" ", "").length < 6 || names.size != 2 || ',' in book.author) return false
        val aliases = setOf(names.last(), "${names.first().first()} ${names.last()}")
        return release.split(Regex("\\s+[-–—]\\s+|[.\\[\\]()]"))
            .any { BookIdentity.normalize(it) in aliases }
    }

    private val otherWorks = Regex("\\b(?:summar(?:y|ies)|study guide|analysis|collection|box ?set|bundle|omnibus|anthology|sequel|sample|trailer|preview|excerpts?|" +
        "complete(?! (?:and )?unabridged)|(?:series|books?|volumes?) \\d{1,2} (?:to |and )?\\d{1,2}|\\d+ books|" +
        // Other books in a series, and partial uploads such as "(4 of 5)" or "CD 2 of 5".
        "(?:series|saga|trilogy|cycle|chronicles)(?! (?:book |volume |vol |part )?\\d)|(?<!(?:book|volume|vol) )\\d{1,2} of \\d{1,2})\\b|\\d+ книг")
    private val laterBook = Regex("\\b(?:book|volume|vol|part) (?:[2-9]|\\d{2,}|ii|iii|iv|v|two|three|four|five)\\b")

    private enum class TitleEvidence { NONE, AMBIGUOUS, EXACT }
    private val releaseNoise = Regex("\\b(?:audio ?books?|audio|books?|unabridged|abridged|dramati[sz]ed|chapteri[sz]ed|w|with chapters|chapters|for ipod|retail|english|eng|" +
        "mp3|m4b|m4a|flac|aac|ogg|opus|cbr|vbr|\\d+ ?kbps|\\d{2,3}k|(?:19|20)\\d{2}|recording|version)\\b")

    /**
     * Release names put the title in its own segment beside the author, narrator, subtitle, uploader, or
     * format tags. One segment must name this title; no part of the name may describe other works.
     * "Title: More words" is ambiguous unless the catalog's subtitle confirms it: series names precede
     * book titles the same way ("The Hunger Games: Catching Fire").
     */
    private fun releaseTitle(book: Audiobook, release: String): TitleEvidence {
        val trimmed = release.replace(Regex("\\s*(?:\\.\\.\\.|…)\\s*$"), "")
        val authorTokens = authorForms(book).flatMap { it.split(' ') }.toSet()
        val titles = searchTitles(book).map { clean(it, emptySet()) }.filter(String::isNotBlank)
        val subtitle = clean(BookIdentity.title(book.title).substringAfter(':', ""), emptySet())
        fun named(words: String) = titles.any { title -> words == title || Regex("^${Regex.escape(title)} (?:by|read by|narrated by)\\b").containsMatchIn(words) }
        // Bracketed and parenthesized tags describe a release, not its title; a truncated tag runs to the end.
        val body = trimmed.replace(Regex("\\[[^]]*]?|\\([^)]*\\)?|\\{[^}]*}?"), " ")
        var evidence = TitleEvidence.NONE
        for (segment in body.split(Regex("\\s+[-–—/|]\\s+|_|(?<=\\p{L}{2})\\.(?=\\p{L})|\\s+-(?=\\S)"))) {
            if (named(clean(segment, authorTokens))) return TitleEvidence.EXACT
            val parts = segment.split(Regex("\\s*:\\s+"), limit = 2)
            if (parts.size < 2) continue
            val head = clean(parts[0], authorTokens); val tail = clean(parts[1], authorTokens)
            if (head.isBlank() && named(tail)) return TitleEvidence.EXACT
            if (!named(head)) continue
            if (subtitle.isNotBlank() && (tail.startsWith(subtitle) || subtitle.startsWith(tail))) return TitleEvidence.EXACT
            if (!laterBook.containsMatchIn(BookIdentity.normalize(release))) evidence = TitleEvidence.AMBIGUOUS
        }
        return evidence
    }

    /** Summaries, collections, other books of a series, and partial uploads; the book's own title is ignored. */
    private fun describesOtherWorks(book: Audiobook, release: String): Boolean {
        val titles = searchTitles(book).map(BookIdentity::normalize).filter(String::isNotBlank)
        return otherWorks.containsMatchIn(titles.fold(" ${BookIdentity.normalize(release)} ") { text, title -> text.replaceFirst(" $title ", " ") })
    }

    private fun clean(value: String, authorTokens: Set<String>) = BookIdentity.normalize(value).replace(releaseNoise, " ")
        .split(' ').filter { it.isNotBlank() && it !in authorTokens }.joinToString(" ")

    /** Full name, first and last name, initials with surname, or joined initials ("JRR Tolkien"). */
    private fun authorForms(book: Audiobook): List<String> {
        val names = BookIdentity.normalize(book.author).split(' ').filter(String::isNotBlank)
        if (names.size < 2 || ',' in book.author) return listOf(names.joinToString(" "))
        val initials = names.dropLast(1).takeIf { given -> given.all { it.length == 1 } }?.joinToString("")?.let { "$it ${names.last()}" }
        return listOfNotNull(names.joinToString(" "), "${names.first()} ${names.last()}", initials)
    }

    private fun namesAuthor(book: Audiobook, release: String): Boolean {
        val evidence = " ${BookIdentity.normalize(release)} "
        return hasAuthor(book, release) || authorForms(book).drop(1).any { form -> form.split(' ').all { " $it " in evidence } }
    }

    private fun matchingTitle(book: Audiobook, recording: Audiobook): String? {
        if (BookMetadata.unknown(book.author)) return null
        val raw = recording.releaseTitle.ifBlank { recording.title }
        var release = BookIdentity.normalize(BookIdentity.title(raw)
            .replace(Regex("\\((?:version \\d+(?: dramatic reading)?|version by [^)]*|dramatic reading|solo)\\)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\([^)]*\\b(?:book|volume|part|cycle|series|saga|trilogy)\\b[^)]*\\)", RegexOption.IGNORE_CASE), " "))
        if (BookIdentity.normalize(book.title).firstOrNull()?.isDigit() != true) {
            release = release.replace(Regex("^0\\d{1,2}\\s+"), "")
        }
        val names = listOf(book.author, recording.author) + listOf(book.narrator, recording.narrator).filter { !BookMetadata.unknown(it) && !it.contains("depends on source") }
        val authorWords = BookIdentity.normalize(book.author).split(' ').filter(String::isNotBlank)
        val allowed = names.flatMap { BookIdentity.normalize(it).split(' ') }.toSet() + authorWords.dropLast(1).map { it.take(1) } +
            setOf("by", "read", "narrated", "narrator", "english", "en", "eng", "retail", "edition", "audio", "book", "complete",
                "audible", "cbr", "vbr", "stereo", "mono", "kbps")
        for (title in searchTitles(book).map(BookIdentity::normalize)) {
            if (title.isBlank() || !(" $release ").contains(" $title ")) continue
            val evidence = (" ${BookIdentity.normalize(raw)} ").replaceFirst(" $title ", " ")
            if (Regex("\\b(?:summar(?:y|ies)|study guide|analysis|collection|box ?set|bundle|omnibus|anthology|sequel|complete series|sample|trailer|preview)\\b").containsMatchIn(evidence)) continue
            val extra = (" $release ").replaceFirst(" $title ", " ").trim().split(' ').filter(String::isNotBlank)
                .filterNot { it in allowed || it.matches(Regex("(?:19|20)\\d{2}|\\d{2,3}k")) }.joinToString(" ")
            // Keep a named series and its position, but reject extra book titles and bare sequel numbers.
            if (extra.isBlank()) return title
            val series = Regex("(?:[\\p{L}][\\p{L}\\p{N}]* ){1,8}(?:cycle|series|saga|trilogy|chronicles?) (?:book |volume |vol |part )?\\d{1,3}")
            val seriesLabels = raw.split(Regex("\\s+[-–—]\\s+|[.\\[\\]():]"))
                .map(BookIdentity::normalize).filter { series.matches(it) && " $title " !in " $it " }
                .map { label -> label.split(' ').filterNot { it in allowed }.joinToString(" ") }
            if (extra in seriesLabels) return title
        }
        return null
    }

    fun filter(book: Audiobook, recordings: List<Audiobook>): List<Audiobook> = recordings
        .mapNotNull { selectBookFiles(book, it) }
        .mapNotNull { recording ->
            val sources = recording.sources.filter { source ->
                source.parts.isNotEmpty() && source.parts.all { part ->
                    isBookAudioFile(part.name) &&
                        (source.delivery != "archive" || part.archiveUrl.startsWith("https://"))
                } && (recording.provider == "archive" || recording.cacheState == "cached" && source.format in recording.cachedFormats ||
                    recording.provider == "knaben" && recording.cacheState == "uncached" && recording.filesVerified && recording.seeders > 0 &&
                        recording.torrentHash.matches(Regex("[a-fA-F0-9]{40}")) && recording.magnetUri.startsWith("magnet:?"))
            }
            if (sources.isEmpty()) null else recording.copy(sources = sources, cachedFormats = recording.cachedFormats.filter { format -> sources.any { it.format == format } })
        }
        .distinctBy { it.torrentHash.takeIf(String::isNotBlank)?.lowercase() ?: it.id }
        .sortedWith(rank(book))

    /** Playable now without preparation. */
    fun ready(recording: Audiobook) = recording.provider == "archive" || recording.cacheState == "cached"

    /** Ready audio first, then the book's language, a complete unabridged reading, whole-book files, and healthy releases. */
    private fun rank(book: Audiobook) = compareBy<Audiobook>(
        { if (it.cacheState == "cached") 0 else if (it.provider == "archive") 1 else 2 },
        { if (sameLanguage(book, edition(it).language)) 0 else 1 },
        { when (edition(it).kind) { "" -> 0; DRAMATIZED -> 1; else -> 2 } },
        { if (it.sources.any { source -> source.format == "M4B" }) 0 else 1 },
        { -it.seeders },
    )

    private fun sameLanguage(book: Audiobook, language: String) = language.isBlank() || BookMetadata.unknown(book.language) ||
        language.equals(book.language, true)

    const val DRAMATIZED = "Dramatized"
    const val ABRIDGED = "Abridged"
    private val languages = mapOf("english" to "English", "английский" to "English", "spanish" to "Spanish", "español" to "Spanish",
        "audiolibro" to "Spanish", "german" to "German", "deutsch" to "German", "hörbuch" to "German", "немецкий" to "German", "french" to "French",
        "français" to "French", "французский" to "French", "italian" to "Italian", "italiano" to "Italian", "russian" to "Russian",
        "русский" to "Russian", "ukrainian" to "Ukrainian", "украинский" to "Ukrainian", "polish" to "Polish", "polski" to "Polish",
        "dutch" to "Dutch", "portuguese" to "Portuguese", "português" to "Portuguese").mapKeys { BookIdentity.normalize(it.key) }
    private val nameWords = "\\p{Lu}[\\p{L}'’.-]*(?:\\s+\\p{Lu}[\\p{L}'’.-]*){1,3}"
    private val narratorPatterns = listOf(
        Regex("(?:(?i:read|narrated|narration|performed)\\s+(?i:by)|(?i:narrator)[:\\s]|(?i:audio ?book)\\s+(?i:with|by))\\s+($nameWords)"),
        // Russian trackers list the narrator first in the trailing bracket: [George Guidall, 1993, MP3, 128 kbps].
        Regex("\\[($nameWords)(?:,|\\s+(?i:and)\\s|])"),
    )
    private val notNarrators = Regex("(?i)\\b(?:audio\\w*|graphic|macmillan|audible|unabridged|abridged|chapteri[sz]ed|retail|edition|recording|mp3|m4b|full cast|cast|bbc|npr|radio)\\b")

    /** Labels come from provider metadata, then the release name. They group versions; they are not verified. */
    fun edition(recording: Audiobook): SourceEdition {
        val name = listOf(recording.releaseTitle, recording.title).filter(String::isNotBlank).distinct().joinToString(" ")
        val words = " ${BookIdentity.normalize(name)} "
        val kind = when {
            Regex(" (?:dramati[sz](?:ed|ation)|full cast|radio (?:drama|play|dramati[sz]ation)|audio drama|graphic ?audio|dramatic reading|bbc radio) ").containsMatchIn(words) -> DRAMATIZED
            Regex(" (?:abridged|abr) ").containsMatchIn(words) -> ABRIDGED
            else -> ""
        }
        val language = if (recording.provider == "archive") recording.language.takeUnless(BookMetadata::unknown).orEmpty()
            else words.split(' ').firstNotNullOfOrNull { languages[it] }.orEmpty()
        val author = BookIdentity.authors(recording.author)
        val narrator = recording.narrator.takeUnless { BookMetadata.unknown(it) || recording.provider != "archive" }
            ?: narratorPatterns.firstNotNullOfOrNull { pattern ->
                pattern.findAll(name).map { it.groupValues[1].trim().trimEnd('.') }
                    .firstOrNull { !notNarrators.containsMatchIn(it) && BookIdentity.authors(it) != author }
            }
        return SourceEdition(kind, language, narrator.orEmpty())
    }

    /**
     * The best recording of each distinct version: narrator, dramatization/abridgment, language, or a public
     * LibriVox reading. Releases without a named narrator join the best release of the same kind and language.
     */
    fun versions(ranked: List<Audiobook>): List<Audiobook> {
        fun base(recording: Audiobook) = edition(recording).let { "${if (recording.provider == "archive") "public" else "release"}|${it.kind}|${it.language.lowercase()}" }
        val named = ranked.filter { edition(it).narrator.isNotBlank() }.map { "${base(it)}|${BookIdentity.authors(edition(it).narrator)}" }
        return ranked.groupBy { recording ->
            val narrator = edition(recording).narrator
            if (narrator.isNotBlank()) "${base(recording)}|${BookIdentity.authors(narrator)}"
            else named.firstOrNull { it.startsWith("${base(recording)}|") } ?: "${base(recording)}|"
        }.values.map { it.first() }
    }

    /** Apply work metadata without claiming the catalog narrator read this particular recording. */
    fun describe(recording: Audiobook, book: Audiobook) = recording.copy(
        title = book.title, author = book.author, description = book.description.ifBlank { recording.description },
        coverUrl = book.coverUrl.ifBlank { recording.coverUrl }, releaseTitle = recording.releaseTitle.ifBlank { recording.title },
        metadataSource = book.metadataSource, metadataUrl = book.metadataUrl, metadataUpdatedAtMs = book.metadataUpdatedAtMs,
    )
}
