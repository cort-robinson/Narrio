package app.narrio.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.narrio.AppGraph
import app.narrio.data.*
import app.narrio.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * What the shelf and book details need from the library data (workstream A) and sync (workstream C).
 * The UI depends only on this; [InterimReadingLibrary] stands in until those land.
 */
interface ReadingLibrary {
    /** Formats, editions, shared place, and pairing for each shelf book, keyed by book id. */
    fun observeShelf(): Flow<Map<String, BookFormats>>
    /** The same for one book, whether or not it's on the shelf. */
    fun observeBook(book: Audiobook): Flow<BookFormats>
    /** Adds an EPUB or TXT file as an ebook-only book, or returns the shelf book that already has this file. */
    suspend fun importBook(uri: Uri): Audiobook
    /** Adds an EPUB or TXT file to [book] as an edition. */
    suspend fun importEdition(book: Audiobook, uri: Uri): EbookEdition
    /** Adds a found ebook to [book] as an edition. */
    suspend fun addEdition(book: Audiobook, candidate: BookTextSource): EbookEdition
    /** Makes [editionId] the edition used for reading and sync. */
    suspend fun activate(bookId: String, editionId: String)
    /** Matching ebooks from the existing providers, reporting each provider as it's checked. */
    suspend fun findEditions(book: Audiobook, connected: Boolean, step: (String) -> Unit): BookTextFinder.Candidates
}

/**
 * TEMPORARY adapter until workstreams A and C land; replace it with `SharedPositionStore`, the edition queries,
 * local import, and pairing status at integration. It reads today's single follow-along text as the book's only
 * edition, today's listening progress as the shared place, and reports every pair as unchecked. No schema changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterimReadingLibrary(private val context: Context, private val graph: AppGraph) : ReadingLibrary {
    private val library get() = graph.library
    /** Edition details from parsed documents, so details don't re-read a whole book to show its title and format. */
    private val described = object : LinkedHashMap<String, EbookEdition>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EbookEdition>) = size > 32
    }

    // Text lookups follow the set of shelf books, not every progress save.
    override fun observeShelf(): Flow<Map<String, BookFormats>> {
        val shelf = library.observeShelf()
        val texts = shelf.map { entries -> entries.map { it.bookId } }.distinctUntilChanged().flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap()) else combine(ids.map { library.observeBookText(it) }) { found -> ids.zip(found).toMap() }
        }
        return combine(shelf, texts) { entries, text -> entries.associate { it.bookId to formats(it.book(), it, text[it.bookId], describe = false) } }
    }

    override fun observeBook(book: Audiobook): Flow<BookFormats> =
        combine(library.observeShelf().map { shelf -> shelf.firstOrNull { it.bookId == book.id } }.distinctUntilChanged(), library.observeBookText(book.id)) { entry, text ->
            formats(book, entry, text, describe = true)
        }

    private suspend fun formats(book: Audiobook, entry: ShelfEntry?, text: BookTextEntry?, describe: Boolean): BookFormats {
        val source = entry?.source()
        val edition = text?.let { saved ->
            described[saved.documentId] ?: if (!describe) EbookEdition(saved.documentId, book.id, book.title, book.author, "", active = true)
            else try { describe(book.id, graph.followAlong.load(saved)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { EbookEdition(saved.documentId, book.id, book.title, book.author, "", active = true) }
        }
        val position = if (entry != null && entry.playedAt > 0 && source != null) SharedPosition(book.id, PositionOrigin.LISTENING,
            audio = AudioCursor(source.id, entry.partId, entry.positionMs), audioConfidence = MappingConfidence.EXACT,
            sequence = entry.playedAt, updatedAtMs = entry.playedAt) else null
        return BookFormats(book.id, audio = source != null || book.sources.isNotEmpty(), editions = listOfNotNull(edition), position = position,
            audioSource = source ?: book.sources.firstOrNull())
    }

    private fun describe(bookId: String, document: BookText): EbookEdition =
        EbookEdition(document.id, bookId, document.title, document.author, document.format, document.language, document.attribution, active = true)
            .also { described[document.id] = it }

    override suspend fun importBook(uri: Uri): Audiobook {
        val (name, format) = describeFile(uri)
        val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { BookTextParser.readBounded(it) } }
            ?: throw ProviderException("This file couldn't be opened. Choose it again.")
        val fallbackTitle = name.substringBeforeLast('.').replace('_', ' ').trim().ifBlank { "Untitled book" }
        val document = withContext(Dispatchers.Default) { BookTextParser.parse(bytes, format, fallbackTitle, "Author not listed") }
        val id = "ebook-${document.id.take(32)}"
        library.find(id)?.let { return it.book() }
        val book = enrich(Audiobook(id, document.title, document.author, provider = "catalog", detailsLoaded = true))
        graph.followAlong.import(uri, book, "", "")
        return book
    }

    /** Cover and description from the catalog when it knows this exact title and author; the file's own details otherwise. */
    private suspend fun enrich(book: Audiobook): Audiobook = if (BookMetadata.unknown(book.author)) book else try {
        val title = BookIdentity.normalize(BookIdentity.title(book.title))
        val authors = BookIdentity.authors(book.author)
        graph.books.search("${book.title} ${book.author}".trim()).firstOrNull {
            BookIdentity.normalize(BookIdentity.title(it.title)) == title && BookIdentity.authors(it.author) == authors
        }?.let { match ->
            book.copy(description = match.description, coverUrl = match.coverUrl, subjects = match.subjects,
                metadataSource = match.metadataSource, metadataUrl = match.metadataUrl, metadataUpdatedAtMs = match.metadataUpdatedAtMs)
        } ?: book
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { book }

    override suspend fun importEdition(book: Audiobook, uri: Uri): EbookEdition {
        describeFile(uri)
        return describe(book.id, graph.followAlong.import(uri, book, "", ""))
    }

    override suspend fun addEdition(book: Audiobook, candidate: BookTextSource): EbookEdition =
        describe(book.id, graph.followAlong.fetch(candidate, book, "", "")).copy(provider = candidate.provider)

    // Today's storage keeps one edition per book, which is always the active one.
    override suspend fun activate(bookId: String, editionId: String) = Unit

    override suspend fun findEditions(book: Audiobook, connected: Boolean, step: (String) -> Unit) = graph.textFinder.candidates(book, book.sources, connected, step)

    /** Ebooks are EPUB or UTF-8 text; a timing track or another ebook format gets a specific answer. */
    private suspend fun describeFile(uri: Uri): Pair<String, String> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            .orEmpty().ifBlank { uri.lastPathSegment?.substringAfterLast('/').orEmpty() }
        val format = textFileFormat(name) ?: when (resolver.getType(uri)) { "application/epub+zip" -> "EPUB"; "text/plain" -> "TXT"; else -> null }
        when {
            format == "VTT" -> throw ProviderException("That's a timing track, not an ebook. Add it from Follow along while its audio part plays.")
            format == null -> throw ProviderException(unsupportedEbook(name))
        }
        name to format!!
    }
}

/** Says which formats work and, for a recognizable ebook format, why this one doesn't. */
fun unsupportedEbook(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mobi", "azw", "azw3", "kfx" -> "Kindle files (MOBI, AZW3) can't be opened. Choose an EPUB or a UTF-8 .txt file."
    "pdf" -> "PDF reading isn't available yet. Choose an EPUB or a UTF-8 .txt file."
    "cbz", "cbr" -> "Comic archives can't be opened. Choose an EPUB or a UTF-8 .txt file."
    else -> "Choose an EPUB or a UTF-8 .txt ebook."
}
