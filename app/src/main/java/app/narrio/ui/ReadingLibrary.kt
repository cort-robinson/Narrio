package app.narrio.ui

import android.net.Uri
import app.narrio.AppGraph
import app.narrio.data.*
import app.narrio.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Library data, pairing and mapped places consumed by shelf/details. */
interface ReadingLibrary {
    /** Formats, editions, shared place, and pairing for each shelf book, keyed by book id. */
    fun observeShelf(): Flow<Map<String, BookFormats>>
    /** The same for one book, whether or not it's on the shelf. */
    fun observeBook(book: Audiobook): Flow<BookFormats>
    fun observePairing(book: Audiobook): Flow<PairingStatus> = observeBook(book).map { it.pairing }.distinctUntilChanged()
    /** Adds an EPUB or TXT file as an ebook-only book, or returns the shelf book that already has this file. */
    suspend fun importBook(uri: Uri): Audiobook
    /** Adds an EPUB or TXT file to [book] as an edition. */
    suspend fun importEdition(book: Audiobook, uri: Uri): EbookEdition
    /** Adds a found ebook to [book] as an edition; [step] describes a download that takes a while. */
    suspend fun addEdition(book: Audiobook, candidate: BookTextSource, step: (String) -> Unit = {}): EbookEdition
    /** Acquires a user-initiated website download, trying TorBox before the browser session. */
    suspend fun downloadWebsiteEbook(book: Audiobook, request: EbookDownloadRequest, connected: Boolean, step: (String) -> Unit): EbookEdition =
        throw ProviderException("Website ebook downloads are unavailable.")
    /** Makes [editionId] the edition used for reading and sync. */
    suspend fun activate(bookId: String, editionId: String)
    /**
     * Every enabled ebook source looks for [book] at once; each one's section fills in as it answers. [words] are the
     * reader's own search words; blank searches by the book's title and author.
     */
    suspend fun searchEditions(book: Audiobook, connected: Boolean, scope: CoroutineScope, words: String = ""): EbookSearchSession
}

/** Room editions and positions, with pairing evidence from the sync engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomReadingLibrary(private val graph: AppGraph) : ReadingLibrary {
    private val library get() = graph.library
    private val documents = object : LinkedHashMap<Pair<String, String>, BookText>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, BookText>) = size > 32
    }
    override fun observeShelf(): Flow<Map<String, BookFormats>> = library.observeShelf()
        .map { entries -> entries.map { it.bookId } }.distinctUntilChanged().flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap()) else combine(ids.map { id ->
                library.observeShelf().mapNotNull { entries -> entries.firstOrNull { it.bookId == id }?.book() }
                    .distinctUntilChanged().flatMapLatest(::observeBook)
            }) { formats -> formats.associateBy { it.bookId } }
        }

    override fun observeBook(book: Audiobook): Flow<BookFormats> = library.observeShelf()
        .map { entries -> entries.firstOrNull { it.bookId == book.id } }.distinctUntilChanged().flatMapLatest { entry ->
            combine(library.observeEditions(book.id), library.observeBookText(book.id),
                graph.sharedPositions.observe(book.id), library.observePositions(book.id)) { editions, active, shared, histories ->
                LibraryReadingState(entry, editions, active, shared, histories)
            }.flatMapLatest { state ->
                val active = state.active?.documentId
                if (active == null) flowOf(state to emptyList()) else
                    combine(library.observeTextBindings(book.id), library.alignmentJobs(book.id, active)) { _, jobs -> state to jobs }
            }.map { (state, _) -> formats(book, state) }
        }

    private suspend fun formats(book: Audiobook, state: LibraryReadingState): BookFormats {
        // Hydrates old v4 metadata without changing bytes or locators.
        val editions = graph.editionFiles.editions(book.id).filter { it.format in setOf("EPUB", "TXT") }
        val stored = state.entry?.book() ?: book
        val sources = (stored.sources + book.sources + listOfNotNull(state.entry?.source()) +
            state.histories.map { NarrioJson.decodeFromString<AudioSource>(it.sourceJson) }).distinctBy { it.id }
        val shared = state.shared
        val source = sources.firstOrNull { it.id == shared?.audio?.sourceId } ?: state.entry?.source() ?: sources.firstOrNull()
        val snapshot = source?.let { graph.mappingRepository.snapshot(book.id, it.id) }
        val pairing = snapshot?.let { graph.readingSync.pairing(book.id, it) } ?: PairingStatus.UNCHECKED
        val position = if (shared == null || pairing == PairingStatus.MISMATCH) shared else when (shared.origin) {
            PositionOrigin.READING -> shared.text?.let { cursor -> source?.let { graph.positionMapper.audioFor(book.id, cursor, it.id) } }
                .let { mapped -> shared.copy(audio = mapped?.audio, audioConfidence = mapped?.confidence ?: MappingConfidence.UNMAPPED) }
            PositionOrigin.LISTENING -> shared.audio?.let { graph.positionMapper.textFor(book.id, it) }
                .let { mapped -> shared.copy(text = mapped?.text, textConfidence = mapped?.confidence ?: MappingConfidence.UNMAPPED) }
        }
        val chapter = position?.text?.let { chapterOf(book.id, it) }
        return BookFormats(book.id, sources.any { it.parts.isNotEmpty() } || state.entry?.hasAudio == true,
            editions, pairing, position, chapter, snapshot?.source ?: source)
    }

    /** 1-based parser chapter containing the durable cursor; no Readium JSON/progression inference. */
    suspend fun chapterOf(bookId: String, cursor: ContentCursor): Int? {
        val key = bookId to cursor.editionId
        val doc = synchronized(documents) { documents[key] } ?: try {
            graph.followAlong.load(BookTextEntry(bookId, cursor.editionId)).also { synchronized(documents) { documents[key] = it } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        if (cursor.normalizationVersion != doc.normalizationVersion) return null
        return doc.chapters.indexOfLast { chapter -> chapter.passages.any {
            it.resource == cursor.resource && cursor.offset in it.offset..it.offset + it.text.length
        } }.takeIf { it >= 0 }?.plus(1)
    }

    override suspend fun importBook(uri: Uri): Audiobook = graph.ebookImporter.import(uri)
    override suspend fun importEdition(book: Audiobook, uri: Uri): EbookEdition = graph.ebookImporter.importEdition(uri, book)
    override suspend fun addEdition(book: Audiobook, candidate: BookTextSource, step: (String) -> Unit): EbookEdition {
        // Anna's Archive gives no lasting file link, so a download is found when the reader chooses the result.
        if (candidate.provider == AnnasArchive.PROVIDER) {
            val downloaded = graph.webEbookAcquisition.acquire(graph.annasArchive.download(candidate, step), connected = false, step)
            step("Adding this ebook to your library")
            val document = graph.followAlong.importDownloaded(downloaded.bytes, downloaded.format, book, downloaded.attribution, downloaded.provider)
            return graph.editionFiles.editions(book.id).first { it.id == document.id }
        }
        val doc = graph.followAlong.fetch(candidate, book, "", "")
        return graph.editionFiles.editions(book.id).first { it.id == doc.id }
    }
    override suspend fun downloadWebsiteEbook(book: Audiobook, request: EbookDownloadRequest, connected: Boolean, step: (String) -> Unit): EbookEdition {
        val downloaded = graph.webEbookAcquisition.acquire(request, connected, step)
        step("Adding this ebook to your library")
        val document = graph.followAlong.importDownloaded(downloaded.bytes, downloaded.format, book, downloaded.attribution, downloaded.provider)
        return graph.editionFiles.editions(book.id).first { it.id == document.id }
    }
    override suspend fun activate(bookId: String, editionId: String) = graph.followAlong.activateEdition(bookId, editionId)
    override suspend fun searchEditions(book: Audiobook, connected: Boolean, scope: CoroutineScope, words: String): EbookSearchSession {
        val saved = library.find(book.id)?.book()
        val narrated = NarrationMatch.recordingBook(book, saved)
        return graph.streamingEbookSearch.start(narrated, narrated.sources, connected, scope, words)
    }
}

private data class LibraryReadingState(val entry: ShelfEntry?, val editions: List<EbookEditionEntry>,
    val active: BookTextEntry?, val shared: SharedPosition?, val histories: List<SourcePosition>)
