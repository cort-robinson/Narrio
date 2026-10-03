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
) {
    suspend fun search(book: Audiobook, connected: Boolean): BookSourceResults = coroutineScope {
        suspend fun read(block: suspend () -> List<Audiobook>): Pair<List<Audiobook>, String?> = try { block() to null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList<Audiobook>() to "Some source providers are unavailable. Try again to check them." }
        val title = BookIdentity.title(book.title)
        val public = async { read { archive.search(title) } }
        val releases = async { if (connected) read { indexed.search(title) } else emptyList<Audiobook>() to null }
        val library = async { if (connected) read { account(title) } else emptyList<Audiobook>() to null }
        val results = listOf(public.await(), releases.await(), library.await())
        val errors = results.mapNotNull { it.second }.toMutableList()
        val candidates = results.flatMap { it.first }.filter { SourceQuality.matches(book, it) }
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
        BookSourceResults(SourceQuality.filter(book, cloud + hydrated), errors.distinct().joinToString(" ").ifBlank { null })
    }
}

object SourceQuality {
    fun matches(book: Audiobook, recording: Audiobook): Boolean {
        if (BookMetadata.unknown(book.author)) return false
        val title = BookIdentity.normalize(BookIdentity.title(book.title))
        val evidence = (" ${BookIdentity.normalize(recording.releaseTitle.ifBlank { recording.title })} ").replaceFirst(" $title ", " ")
        if (Regex("\\b(?:summar(?:y|ies)|study guide|analysis|collection|box ?set|bundle|omnibus|anthology|sequel|complete series|sample|trailer|preview)\\b").containsMatchIn(evidence)) return false
        var release = BookIdentity.normalize(BookIdentity.title(recording.releaseTitle.ifBlank { recording.title })
            .replace(Regex("\\((?:version \\d+(?: dramatic reading)?|version by [^)]*|dramatic reading|solo)\\)", RegexOption.IGNORE_CASE), " "))
        if (title.isBlank() || !(" $release ").contains(" $title ")) return false
        if (!BookMetadata.unknown(recording.author)) {
            if (BookIdentity.authors(book.author) != BookIdentity.authors(recording.author)) return false
        } else {
            val names = BookIdentity.normalize(book.author).split(' ').filter(String::isNotBlank)
            if (!names.all { " $it " in " $release " }) return false
        }
        release = (" $release ").replaceFirst(" $title ", " ")
        // Every remaining word needs recording evidence; a sequel, summary, or collection fails.
        val names = listOf(book.author, recording.author) + listOf(book.narrator, recording.narrator).filter { !BookMetadata.unknown(it) && !it.contains("depends on source") }
        val allowed = names.flatMap { BookIdentity.normalize(it).split(' ') }.toSet() +
            setOf("by", "read", "narrated", "narrator", "english", "en", "eng", "retail", "edition", "audio", "book", "complete")
        return release.trim().split(' ').filter(String::isNotBlank).all { it in allowed || it.matches(Regex("(?:19|20)\\d{2}")) }
    }

    fun filter(book: Audiobook, recordings: List<Audiobook>): List<Audiobook> = recordings
        .filter { matches(book, it) }
        .mapNotNull { recording ->
            val sources = recording.sources.filter { source ->
                source.parts.isNotEmpty() && source.parts.all { part ->
                    isBookAudioFile(part.name) &&
                        (source.delivery != "archive" || part.archiveUrl.startsWith("https://"))
                } && (recording.provider == "archive" || recording.cacheState == "cached" && source.format in recording.cachedFormats)
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
