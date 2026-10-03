package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*

data class BookSourceResults(val recordings: List<Audiobook>, val error: String? = null)

/** Only invoked from details; delivery checks never participate in book identification. */
class BookSourceDiscovery(
    private val archive: RecordingDiscovery,
    private val indexed: RecordingDiscovery,
    private val account: suspend (String) -> List<Audiobook>,
    private val checkCached: suspend (List<Audiobook>) -> List<Audiobook>,
    private val loadFiles: suspend (Audiobook) -> Audiobook? = { null },
) {
    suspend fun search(book: Audiobook, connected: Boolean): BookSourceResults = coroutineScope {
        suspend fun read(block: suspend () -> List<Audiobook>): Pair<List<Audiobook>, String?> = try { block() to null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList<Audiobook>() to "Some source providers are unavailable. Try again to check them." }
        val titles = SourceQuality.searchTitles(book)
        val public = async { titles.map { title -> read { archive.search(title) } } }
        val releases = async { if (connected) titles.map { title -> read { indexed.search(title) } } else emptyList() }
        // Read the account once: provider substring search cannot match catalog subtitles or punctuation reliably.
        val library = async { if (connected) read { account("") } else emptyList<Audiobook>() to null }
        val results = public.await() + releases.await() + library.await()
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
        // A cache miss says nothing about the recording's files. Inspect public torrent metadata
        // without adding a torrent, and leave preparation to the listener's explicit action.
        val uncached = cloud.filter { it.provider == "knaben" && it.cacheState == "uncached" && it.seeders > 0 }
            .sortedBy { !SourceQuality.matches(book, it) }.take(12)
        val verified = uncached.chunked(4).flatMap { batch ->
            batch.map { recording -> async { read { listOfNotNull(loadFiles(recording)) } } }.awaitAll()
                .also { fetched -> errors += fetched.mapNotNull { it.second } }.flatMap { it.first }
        }.associateBy { it.id }
        cloud = cloud.map { verified[it.id] ?: it }
        BookSourceResults(SourceQuality.filter(book, cloud + hydrated), errors.distinct().joinToString(" ").ifBlank { null })
    }
}

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
        return recording.copy(id = if (recording.id.endsWith(suffix)) recording.id else recording.id + suffix, sources = sources)
    }

    fun matches(book: Audiobook, recording: Audiobook): Boolean {
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
        .sortedBy { it.cacheState != "cached" }

    /** Apply work metadata without claiming the catalog narrator read this particular recording. */
    fun describe(recording: Audiobook, book: Audiobook) = recording.copy(
        title = book.title, author = book.author, description = book.description.ifBlank { recording.description },
        coverUrl = book.coverUrl.ifBlank { recording.coverUrl }, releaseTitle = recording.releaseTitle.ifBlank { recording.title },
        metadataSource = book.metadataSource, metadataUrl = book.metadataUrl, metadataUpdatedAtMs = book.metadataUpdatedAtMs,
    )
}
