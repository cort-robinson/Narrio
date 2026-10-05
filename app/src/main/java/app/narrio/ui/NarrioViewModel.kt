package app.narrio.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.narrio.*
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.playback.ListeningService
import app.narrio.playback.ListeningState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val SOURCE_RESULTS_MS = 10 * 60_000L

data class CatalogState(val books: List<Audiobook> = emptyList(), val loading: Boolean = true, val error: String? = null, val notice: String? = null)
data class SelectionState(val book: Audiobook? = null, val loading: Boolean = false, val error: String? = null, val metadataLoading: Boolean = false)
/**
 * [recordings] are verified and confidently matched, best first; [possible] need the listener's review.
 * The automatic [choice] is the best recording unless the listener picked another version.
 */
data class SourceSearchState(
    val book: Audiobook? = null, val recordings: List<Audiobook> = emptyList(), val loading: Boolean = false, val searched: Boolean = false,
    val error: String? = null, val possible: List<Audiobook> = emptyList(), val chosenId: String? = null,
) {
    val choice: Audiobook? get() = recordings.firstOrNull { it.id == chosenId } ?: recordings.firstOrNull()
    val versions: List<Audiobook> by lazy { SourceQuality.versions(recordings) }
    val results: List<Audiobook> get() = recordings + possible
}
data class BookTextState(
    val bookId: String = "",
    val document: BookText? = null,
    val bindings: List<TextBinding> = emptyList(),
    val loading: Boolean = false,
    val working: Boolean = false,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val results: List<BookTextSource> = emptyList(),
    val error: String? = null,
    // Automatic lookup when follow along opens without text.
    val finding: Boolean = false,
    val findingStep: String = "",
    val autoMissed: Boolean = false,
)
/** Find ebook for one book: every matching candidate, the provider being checked, and what's being added. */
data class EbookSearchState(
    val bookId: String = "", val searching: Boolean = false, val step: String = "", val searched: Boolean = false,
    val results: List<BookTextSource> = emptyList(), val incomplete: Boolean = false, val error: String? = null,
    /** Candidate id, or [FILE], while an edition is being added; [added] is set once it is. */
    val adding: String? = null, val added: String? = null,
) { companion object { const val FILE = "file" } }
/** Adding an ebook file from the shelf as a new book. */
data class EbookImportState(val working: Boolean = false, val error: String? = null)
/** The book, edition, and place Read opens. */
data class ReaderRequest(val book: Audiobook, val edition: EbookEdition, val place: PlaceSummary? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class NarrioViewModel(application: Application) : AndroidViewModel(application) {
    val graph = (application as NarrioApplication).graph
    val catalog = MutableStateFlow(CatalogState())
    val selection = MutableStateFlow(SelectionState())
    val query = MutableStateFlow("")
    val category = MutableStateFlow("All")
    val destination = MutableStateFlow(0)
    val playerOpen = MutableStateFlow(false)
    val preparation = MutableStateFlow<Preparation?>(null)
    val preferredFormat = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val connected = MutableStateFlow(graph.credentials.read() != null)
    private val appearanceStore = AppearanceStore(graph.preferences)
    private val appearanceState = MutableStateFlow(appearanceStore.read())
    val appearance = appearanceState.asStateFlow()
    val sourceSearch = MutableStateFlow(SourceSearchState())
    val downloads = graph.offline.books
    val wifiOnly = MutableStateFlow(graph.preferences.getBoolean("downloadWifi", true))
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emitted after Now playing is dismissed, so the shell can offer Undo. */
    val dismissedPlayback = MutableSharedFlow<ListeningState>(extraBufferCapacity = 1)
    val shelf = graph.library.observeShelf().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val shelfState = graph.library.observeShelfState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun editions(bookId: String): Flow<List<EbookEdition>> = combine(graph.library.observeEditions(bookId), graph.library.observeBookText(bookId)) { editions, active ->
        editions.map { it.edition(it.editionId == active?.documentId) }
    }
    fun sharedPosition(bookId: String) = graph.sharedPositions.observe(bookId)
    suspend fun importEbook(uri: Uri): Audiobook = graph.ebookImporter.import(uri)
    suspend fun activateEdition(bookId: String, editionId: String) = graph.followAlong.activateEdition(bookId, editionId)
    suspend fun removeEdition(bookId: String, editionId: String) = graph.followAlong.removeEdition(bookId, editionId)
    val playback = graph.playback.state
    val bookText = MutableStateFlow(BookTextState())
    /** Library data and sync seen by the shelf and details; replaceable so tests and integration can supply their own. */
    val readingLibrary = MutableStateFlow<ReadingLibrary>(InterimReadingLibrary(application, graph))
    val shelfFormats: StateFlow<Map<String, BookFormats>> = readingLibrary.flatMapLatest { it.observeShelf() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val detailFormats: StateFlow<BookFormats?> = combine(readingLibrary, selection.map { it.book }.distinctUntilChanged { a, b -> a?.id == b?.id && a?.sources == b?.sources }, ::Pair)
        .flatMapLatest { (library, book) -> if (book == null) flowOf(null) else library.observeBook(book) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val shelfFilter = MutableStateFlow(ShelfFilter.ALL)
    val ebookSearch = MutableStateFlow(EbookSearchState())
    val ebookImport = MutableStateFlow(EbookImportState())
    val reader = MutableStateFlow<ReaderRequest?>(null)
    /** Moves to the shared place large enough to offer Undo; the shell shows them with [showJump]. */
    val positionJumps = MutableSharedFlow<PositionJump>(extraBufferCapacity = 1)
    private var ebookJob: Job? = null
    /** The book an ebook file is being added to; null adds the file as a new book. */
    private var ebookImportTarget: Audiobook? = null
    val narrationSync = graph.narrationSync.status
    val readingSync = graph.readingSync.state
    val backgroundAlignment = MutableStateFlow(graph.bookAlignment.enabled())
    fun setBackgroundAlignment(value: Boolean) { graph.bookAlignment.setEnabled(value); backgroundAlignment.value = value }
    fun undoSyncJump() = graph.playback.service?.undoSyncJump()
    fun clearSyncJump() = graph.readingSync.clearJump()
    suspend fun resolveReadingStart(bookId: String, editionId: String, previous: ContentCursor?, pagesMoved: Int? = null) =
        graph.readingSync.readingStart(bookId, editionId, previous, pagesMoved)
    fun seekFromText(cursor: ContentCursor) = viewModelScope.launch { graph.playback.service?.seekFromText(cursor) }
    /** Finds ebooks and syncs them with narration without being asked. */
    val followAlongAuto = MutableStateFlow(graph.preferences.getBoolean("followAlongAuto", true))
    private val autoFindText get() = followAlongAuto.value
    private var syncJob: Job? = null
    private val autoTextTried = mutableSetOf<String>()
    private var textSearchJob: Job? = null
    private val textJobs = mutableMapOf<String, Job>()
    private val autoTextJobs = mutableMapOf<String, Job>()
    private var textImportTarget: ListeningState? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var sourceSearchJob: Job? = null
    private val sourceResults = mutableMapOf<String, Pair<Long, SourceSearchState>>()
    private var detailMetadataJob: Job? = null
    private var metadataRequest = 0
    private var controller: MediaController? = null
    private val controllerFuture = MediaController.Builder(application, SessionToken(application, ComponentName(application, ListeningService::class.java))).buildAsync()

    init {
        controllerFuture.addListener({ runCatching { controller = controllerFuture.get() } }, ContextCompat.getMainExecutor(application))
        search()
        viewModelScope.launch {
            playback.map { it.book?.id }.distinctUntilChanged().collectLatest { id ->
                textSearchJob?.cancel()
                bookText.value = BookTextState(bookId = id.orEmpty(), loading = id != null)
                if (id == null) return@collectLatest
                var loaded: BookText? = null
                combine(graph.library.observeBookText(id), graph.library.observeTextBindings(id)) { entry, bindings -> entry to bindings }
                    .collectLatest { (entry, bindings) ->
                        try {
                            loaded = if (entry == null) null else loaded?.takeIf { it.id == entry.documentId } ?: graph.followAlong.load(entry)
                            bookText.value = bookText.value.copy(document = loaded, bindings = bindings.map { it.binding() }, loading = false)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { bookText.value = bookText.value.copy(document = null, loading = false, error = textError(error)) }
                    }
            }
        }
    }

    /** The book open in the full reader, if any. */
    val reading = MutableStateFlow<String?>(null)
    fun read(bookId: String) { reading.value = bookId }
    fun closeReader() { reading.value = null }
    /** True when the book has an attached EPUB or text edition the reader can open (timing tracks aren't readable). */
    fun canRead(bookId: String): Flow<Boolean> = graph.library.observeBookText(bookId)
        .map { entry -> entry != null && graph.editionFiles.original(bookId, entry.documentId) != null }

    fun search(value: String = query.value, cat: String = category.value) {
        val browseCategory = if (value.isBlank()) cat else "All"
        query.value = value; category.value = browseCategory
        searchJob?.cancel()
        catalog.value = CatalogState(emptyList(), true)
        searchJob = viewModelScope.launch {
            delay(if (value.isBlank()) 0 else 350)
            try {
                catalog.value = CatalogState(graph.books.search(value, browseCategory), false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { catalog.value = CatalogState(emptyList(), false, friendly(error)) }
        }
    }

    fun addonsChanged() {
        sourceResults.clear()
        sourceSearchJob?.cancel()
        sourceSearch.value = SourceSearchState(book = sourceSearch.value.book)
        search()
    }

    /** Runs automatically when a book opens. Complete results are reused briefly so returning to a book is instant. */
    fun findSources(book: Audiobook, force: Boolean = false) {
        sourceSearchJob?.cancel()
        val key = "${book.id}|${connected.value}"
        sourceResults[key]?.takeIf { !force && System.currentTimeMillis() - it.first < SOURCE_RESULTS_MS }?.let { sourceSearch.value = it.second; return }
        sourceSearch.value = SourceSearchState(book = book, loading = true, searched = true)
        sourceSearchJob = viewModelScope.launch {
            try {
                val results = graph.bookSources.search(book, connected.value)
                sourceSearch.value = SourceSearchState(book, results.recordings, searched = true, error = results.error, possible = results.possible)
                if (results.error == null) sourceResults[key] = System.currentTimeMillis() to sourceSearch.value
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { sourceSearch.value = SourceSearchState(book, searched = true, error = friendly(error)) }
        }
    }

    /** Opens a recording's own page, where its formats, files, downloads, and preparation are available. */
    fun chooseRecording(recording: Audiobook) {
        val book = sourceSearch.value.book ?: return
        if (recording !in sourceSearch.value.results) return
        open(SourceQuality.describe(recording, book), keepSources = true)
    }

    fun chooseVersion(recording: Audiobook) {
        sourceSearch.update { state -> if (recording in state.recordings) state.copy(chosenId = recording.id) else state }
    }

    /** Plays the chosen recording directly when it is ready; otherwise its page offers explicit TorBox preparation. */
    fun listenToChoice() {
        val search = sourceSearch.value
        val book = search.book ?: return
        val recording = search.choice ?: return
        val described = SourceQuality.describe(recording, book)
        val formats = if (recording.provider == "archive") recording.sources else recording.sources.filter { it.format in recording.cachedFormats }
        if (!SourceQuality.ready(recording) || formats.isEmpty()) return open(described, keepSources = true)
        val saved = graph.preferences.getString("format:${described.id}", "")
        val source = formats.firstOrNull { it.format == saved } ?: formats.firstOrNull { it.format == "M4B" } ?: formats.first()
        start(described, source, if (recording.provider == "archive") "archive" else "torbox")
    }

    fun open(book: Audiobook, keepSources: Boolean = false) {
        playerOpen.value = false
        reader.value = null
        preparation.value = null
        preferredFormat.value = ""
        detailJob?.cancel()
        detailMetadataJob?.cancel()
        sourceSearchJob?.cancel()
        if (!keepSources) sourceSearch.value = SourceSearchState(book = book)
        selection.value = SelectionState(book, !book.detailsLoaded)
        detailJob = viewModelScope.launch {
            try {
                var full = if (book.detailsLoaded) book else graph.catalog.recording(book.id)
                if (!connected.value) full = full.copy(cacheState = "unchecked", cachedFormats = emptyList())
                selection.value = SelectionState(full)
                if (full.provider == "catalog") {
                    val search = sourceSearch.value
                    // An ebook-only book looks for a recording only when asked: Find audiobook.
                    val ebookOnly = readingLibrary.value.observeBook(full).first().let { it.ebook && !it.audio }
                    if (!ebookOnly && (search.book?.id != full.id || !search.searched || search.loading)) findSources(full)
                    return@launch
                }
                val saved = graph.library.find(full.id)
                saved?.book()?.takeIf { it.metadataUpdatedAtMs > full.metadataUpdatedAtMs }?.let { full = full.withMetadataFrom(it) }
                preferredFormat.value = saved?.pendingFormat?.takeIf { it.isNotBlank() } ?: graph.preferences.getString("format:${full.id}", saved?.source()?.format.orEmpty()).orEmpty()
                if (saved?.state == "preparing" || saved?.state == "ready") {
                    preparation.value = Preparation(saved.preparationId, saved.state == "ready", 0f, if (saved.state == "ready") "Ready to listen" else "Preparing in TorBox")
                    if (connected.value) {
                        updatePreparation(full, saved)
                        selection.value.book?.takeIf { it.id == full.id }?.let { full = it.withMetadataFrom(full) }
                    }
                } else if (connected.value && full.provider != "torbox" && full.torrentHash.isNotBlank()) {
                    full = graph.torbox.checkCached(listOf(full)).first()
                    if (selection.value.book?.id == full.id) selection.value = SelectionState(full)
                }
                if (selection.value.book?.id == full.id) {
                    selection.value = selection.value.copy(book = full)
                    loadMetadata(full)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { selection.value = SelectionState(book, false, friendly(error)) }
        }
    }

    private fun loadMetadata(book: Audiobook, force: Boolean = false) {
        if (book.provider == "archive" || book.provider == "catalog") return
        val request = ++metadataRequest
        detailMetadataJob?.cancel()
        selection.update { if (it.book?.id == book.id) it.copy(metadataLoading = true) else it }
        detailMetadataJob = viewModelScope.launch {
            try {
                val enriched = graph.metadata.enrich(book, force)
                val current = selection.value.book?.takeIf { it.id == book.id } ?: return@launch
                val updated = current.withMetadataFrom(enriched)
                selection.update { it.copy(book = updated, metadataLoading = false) }
                catalog.update { state -> state.copy(books = state.books.map { if (it.id == book.id) it.withMetadataFrom(updated) else it }) }
                graph.library.updateBookDetails(updated)
                if (force && enriched == book) messages.emit("No new matching book details found. Your current details are kept.")
            } finally {
                if (metadataRequest == request) selection.update { if (it.book?.id == book.id) it.copy(metadataLoading = false) else it }
            }
        }
    }

    fun refreshMetadata(book: Audiobook) = loadMetadata(book, true)
    fun back() { if (reader.value != null) reader.value = null else if (playerOpen.value) playerOpen.value = false else if (sourceSearch.value.book?.provider == "catalog" && selection.value.book?.provider != "catalog") open(sourceSearch.value.book!!, keepSources = true) else { detailJob?.cancel(); detailMetadataJob?.cancel(); sourceSearchJob?.cancel(); selection.value = SelectionState(); sourceSearch.value = SourceSearchState() } }
    fun navigate(index: Int) { detailJob?.cancel(); detailMetadataJob?.cancel(); sourceSearchJob?.cancel(); sourceSearch.value = SourceSearchState(); destination.value = index; selection.value = SelectionState(); playerOpen.value = false; reader.value = null }
    fun save(book: Audiobook) = viewModelScope.launch { graph.library.save(book); messages.emit("Saved to your shelf") }
    fun remove(book: Audiobook) = viewModelScope.launch {
        textJobs[book.id]?.cancel(); autoTextJobs[book.id]?.cancel()
        if (reader.value?.book?.id == book.id) reader.value = null
        if (playback.value.book?.id == book.id) graph.playback.service?.forget()
        downloads.value.filter { it.book.id == book.id }.forEach { graph.offline.remove(it.source) }
        graph.followAlong.remove(book.id)
        graph.library.remove(book.id); messages.emit("Removed from your shelf and phone downloads")
    }
    fun resume(entry: ShelfEntry) { entry.source()?.let { start(entry.book(), it, it.delivery) } ?: open(entry.book()) }

    /** Continue reopens whichever mode last moved the book's shared place. */
    fun continueBook(entry: ShelfEntry) {
        if (shelfFormats.value[entry.bookId]?.let { it.lastMode ?: it.leadingMode } == PositionOrigin.READING) read(entry.book()) else resume(entry)
    }

    /** Opens the active edition at the shared place: the reading place itself, or its mapped counterpart. */
    fun read(book: Audiobook) {
        val formats = detailFormats.value?.takeIf { it.bookId == book.id } ?: shelfFormats.value[book.id]
        val edition = formats?.activeEdition ?: return open(book)
        val place = if (formats.position?.origin == PositionOrigin.READING) placeSummary(formats) else counterpartPlace(formats)
        playerOpen.value = false
        reader.value = ReaderRequest(book, edition, place)
    }
    fun closeReader() { reader.value = null }
    fun setShelfFilter(filter: ShelfFilter) { shelfFilter.value = filter }
    fun announceJump(jump: PositionJump) { positionJumps.tryEmit(jump) }

    /** Lists matching ebooks from the recording's files, TorBox, cached ebook releases, and Project Gutenberg. */
    fun findEbooks(book: Audiobook) {
        ebookJob?.cancel()
        ebookSearch.value = EbookSearchState(book.id, searching = true, searched = true)
        fun update(change: (EbookSearchState) -> EbookSearchState) = ebookSearch.update { if (it.bookId == book.id) change(it) else it }
        ebookJob = viewModelScope.launch {
            try {
                val found = readingLibrary.value.findEditions(book, connected.value) { step -> update { it.copy(step = step) } }
                update { it.copy(results = found.results, incomplete = found.incomplete) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { update { it.copy(error = friendly(error)) } }
            finally { update { it.copy(searching = false, step = "") } }
        }
    }

    /** Opening Find ebook again reuses a complete lookup for the same book. */
    fun openEbookSearch(book: Audiobook) {
        val state = ebookSearch.value
        if (state.bookId != book.id || !state.searched || state.error != null || state.incomplete) findEbooks(book)
        else ebookSearch.update { it.copy(added = null) }
    }

    fun addEbook(book: Audiobook, candidate: BookTextSource) = editionWork(book, candidate.id) { readingLibrary.value.addEdition(book, candidate) }

    fun chooseEdition(book: Audiobook, editionId: String) = viewModelScope.launch {
        try { readingLibrary.value.activate(book.id, editionId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(textError(error)) }
    }

    /** [target] receives the chosen file as an edition; without one, the file becomes a new ebook-only book. */
    fun beginEbookImport(target: Audiobook?) {
        ebookImportTarget = target
        if (target == null) ebookImport.value = EbookImportState()
    }

    fun importEbook(uri: Uri?) {
        val target = ebookImportTarget
        ebookImportTarget = null
        if (uri == null) return
        if (target != null) { editionWork(target, EbookSearchState.FILE) { readingLibrary.value.importEdition(target, uri) }; return }
        viewModelScope.launch {
            ebookImport.value = EbookImportState(working = true)
            try {
                val book = readingLibrary.value.importBook(uri)
                ebookImport.value = EbookImportState()
                open(book)
                messages.emit("${book.title} is on your shelf, ready to read.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { ebookImport.value = EbookImportState(error = textError(error)) }
        }
    }
    fun dismissEbookImport() { ebookImport.value = EbookImportState() }

    private fun editionWork(book: Audiobook, adding: String, action: suspend () -> EbookEdition) = viewModelScope.launch {
        if (ebookSearch.value.adding != null) return@launch
        if (ebookSearch.value.bookId != book.id) ebookSearch.value = EbookSearchState(book.id)
        ebookSearch.update { it.copy(adding = adding, added = null, error = null) }
        try {
            val edition = action()
            ebookSearch.update { if (it.bookId == book.id) it.copy(added = edition.id) else it }
            messages.emit("Ebook added. Read opens it.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ebookSearch.update { if (it.bookId == book.id) it.copy(error = textError(error)) else it } }
        finally { ebookSearch.update { if (it.bookId == book.id) it.copy(adding = null) else it } }
    }

    fun start(book: Audiobook, source: AudioSource, delivery: String) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        try {
            val resolved = playableSource(book, source, delivery) ?: return@launch
            awaitService().load(book, resolved)
            val saved = graph.library.find(book.id)
            if (saved?.pendingFormat == resolved.format) graph.library.finishPreparation(book.id)
            playerOpen.value = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }

    private suspend fun playableSource(book: Audiobook, source: AudioSource, delivery: String): AudioSource? {
        if (graph.offline.complete(source)) return source
        if (delivery != "torbox") return source
        if (!connected.value) throw ProviderException("Connect TorBox in Settings to stream this source.")
        preferredFormat.value = source.format
        if (source.torrentId != null && source.torrentId > 0) {
            val prep = graph.torbox.status(source.torrentId)
            if (!prep.ready) throw ProviderException("This source is still being prepared in TorBox. Pick a cached recording to listen now.")
            return source
        }
        val saved = graph.library.find(book.id)
        var prep = if (saved?.state in listOf("preparing", "ready")) graph.torbox.refresh(book, saved!!.preparationId) else graph.torbox.prepareCached(book, source.format)
        if (!prep.ready && saved?.state !in listOf("preparing", "ready")) {
            var attempts = 0
            while (!prep.ready && attempts++ < 3) { delay(1000); prep = graph.torbox.refresh(book, prep.torrentId) }
        }
        if (selection.value.book?.id == book.id) preparation.value = prep
        graph.library.save(book); graph.library.preparing(book.id, prep.torrentId, source.format)
        if (!prep.ready) { messages.emit("This source isn't ready yet. Choose a cached release to listen now; its status is saved on your shelf."); return null }
        return graph.torbox.sources(book, prep.torrentId).firstOrNull { it.format == source.format }
            ?: throw ProviderException("The selected format is missing from this TorBox source. Choose another format.")
    }

    fun prepareUncached(book: Audiobook, format: String) = viewModelScope.launch {
        if (busy.value || !connected.value) return@launch
        busy.value = true
        try {
            val prep = if (book.provider == "torbox") graph.torbox.status(book.sources.first().torrentId!!) else graph.torbox.prepare(book)
            graph.library.save(book); graph.library.preparing(book.id, prep.torrentId, format)
            if (selection.value.book?.id == book.id) { preparation.value = prep; preferredFormat.value = format }
            updatePreparation(book, graph.library.find(book.id)!!)
            messages.emit(if (prep.ready) "Ready in TorBox. Choose your audio format to listen." else "Preparation stays in TorBox. It doesn't download audio to your phone.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }

    fun download(book: Audiobook, source: AudioSource, delivery: String) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        try {
            val resolved = playableSource(book, source, delivery) ?: return@launch
            graph.library.save(book); graph.offline.queue(book, resolved)
            messages.emit(if (wifiOnly.value) "Phone download queued. It will use Wi-Fi." else "Phone download queued for offline listening.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }
    fun setWifiOnly(value: Boolean) { wifiOnly.value = value; graph.preferences.edit().putBoolean("downloadWifi", value).apply(); graph.offline.setWifiOnly(value) }
    fun chooseFormat(book: Audiobook, format: String) { preferredFormat.value = format; graph.preferences.edit().putString("format:${book.id}", format).apply() }
    fun pauseDownload(download: OfflineBook) = graph.offline.pause(download.source)
    fun resumeDownload(download: OfflineBook) = graph.offline.resume(download.book, download.source)
    fun removeDownload(download: OfflineBook) {
        if (playback.value.source?.id == download.source.id && playback.value.playing) graph.playback.service?.toggle()
        graph.offline.remove(download.source)
    }

    fun refreshPreparation(book: Audiobook) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        try {
            val saved = graph.library.find(book.id) ?: return@launch
            updatePreparation(book, saved)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }

    private suspend fun updatePreparation(book: Audiobook, saved: ShelfEntry) {
        val prep = graph.torbox.refresh(book, saved.preparationId)
        if (prep.ready) {
            graph.library.state(book.id, "ready")
            val sources = graph.torbox.sources(book, prep.torrentId)
            val updated = book.copy(cacheState = "cached", cachedFormats = sources.map { it.format }, sources = if (book.provider == "archive") book.sources else sources)
            graph.library.save(updated)
            if (selection.value.book?.id == book.id) selection.value = SelectionState(updated)
        }
        if (selection.value.book?.id == book.id) preparation.value = prep
    }

    fun connect(key: String) = viewModelScope.launch {
        if (key.isBlank() || busy.value) return@launch
        busy.value = true
        try {
            graph.torbox.connect(key.trim()); graph.credentials.write(key.trim())
            connected.value = true; messages.emit("TorBox connected. Books now also check TorBox for ready audio.")
            selection.value.book?.takeIf { it.provider == "catalog" }?.let { findSources(it) }
        } catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }
    fun disconnect() {
        graph.playback.service?.disconnect(); graph.offline.disconnect(); graph.credentials.clear(); connected.value = false
        sourceSearchJob?.cancel()
        sourceSearch.value = SourceSearchState(book = selection.value.book)
        selection.value.book?.takeIf { it.provider == "catalog" }?.let { findSources(it) }
        messages.tryEmit("TorBox disconnected; its credential has been removed.")
    }
    fun updateAppearance(value: AppearanceSettings) {
        val normalized = value.normalized()
        appearanceStore.save(normalized)
        appearanceState.value = normalized
    }
    fun dismissPlayback() = viewModelScope.launch {
        val state = playback.value
        if (state.book == null || state.source == null) return@launch
        playerOpen.value = false
        graph.playback.service?.dismiss()
        dismissedPlayback.emit(state)
    }
    fun undoDismissPlayback(state: ListeningState) = viewModelScope.launch {
        val book = state.book ?: return@launch; val source = state.source ?: return@launch
        if (playback.value.book != null) return@launch
        awaitService().load(book, source, state.playing || state.buffering, state.part?.id, state.positionMs)
    }
    fun bookmark() = viewModelScope.launch { graph.playback.service?.bookmark(); messages.emit("Bookmark added") }
    fun deleteBookmark(id: Long) = viewModelScope.launch { graph.library.deleteBookmark(id) }
    fun jumpBookmark(bookmark: BookmarkEntry) = viewModelScope.launch {
        val state = playback.value
        if (state.source?.id == bookmark.sourceId) graph.playback.service?.part(resumeIndex(state.source.parts, bookmark.partId), bookmark.positionMs)
        else {
            val entry = graph.library.find(bookmark.bookId)
            val history = graph.library.position(bookmark.bookId, bookmark.sourceId)
            val source = history?.sourceJson?.let { NarrioJson.decodeFromString<AudioSource>(it) } ?: entry?.source()
            if (entry != null && source?.id == bookmark.sourceId) { awaitService().load(entry.book(), source, true, bookmark.partId, bookmark.positionMs); playerOpen.value = true }
            else messages.emit("This bookmark belongs to a different audio source. Resume that source first.")
        }
    }

    fun searchBookText(query: String) {
        val target = playback.value.book ?: return
        val id = target.id
        textSearchJob?.cancel()
        textSearchJob = viewModelScope.launch {
            bookText.value = bookText.value.copy(searching = true, searched = true, results = emptyList(), error = null)
            try {
                var failed = false
                suspend fun read(block: suspend () -> List<BookTextSource>): List<BookTextSource> = try { block() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true; emptyList() }
                val results = coroutineScope {
                    val public = async { read { graph.textDiscovery.search(query) } }
                    val cached = async { if (connected.value) read { graph.textFinder.cachedReleases(target) } else emptyList() }
                    cached.await() + public.await()
                }
                if (bookText.value.bookId == id) bookText.value = bookText.value.copy(results = results,
                    error = if (failed) "Some ebook providers are unavailable. Try again later or import book text." else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (bookText.value.bookId == id) bookText.value = bookText.value.copy(error = textError(error)) }
            finally { if (bookText.value.bookId == id) bookText.value = bookText.value.copy(searching = false) }
        }
    }

    fun beginTextImport() { textImportTarget = playback.value }
    fun importBookText(uri: Uri?) {
        val target = textImportTarget ?: return
        textImportTarget = null
        if (uri != null) textWork(target) { book, source, part -> graph.followAlong.import(uri, book, source.id, part.id) }
    }

    fun fetchBookText(candidate: BookTextSource) = textWork(playback.value) { book, source, part ->
        graph.followAlong.fetch(candidate, book, source.id, part.id)
    }

    /** Once per book and session: attach the best matching ebook without asking. Manual choices remain available. */
    fun autoFindBookText() {
        val target = playback.value
        val book = target.book ?: return
        val source = target.source ?: return
        val part = target.part ?: return
        val state = bookText.value
        if (state.bookId != book.id || state.loading || state.document != null || book.id in autoTextTried || textJobs[book.id]?.isActive == true || !autoFindText) return
        autoTextTried += book.id
        fun update(change: (BookTextState) -> BookTextState) { if (bookText.value.bookId == book.id) bookText.value = change(bookText.value) }
        val job = viewModelScope.launch {
            update { it.copy(finding = true, autoMissed = false, error = null) }
            try {
                val found = graph.textFinder.find(book, source, connected.value, { step -> update { it.copy(findingStep = step) } }) { candidate ->
                    graph.followAlong.fetch(candidate, book, source.id, part.id) { document ->
                        require(BookTextFinder.plausible(document, book, source)) { "This file is too short to be the book." }
                    }
                }
                if (found) messages.emit("Found the ebook. Follow along syncs with the narration as you listen.")
                else update { it.copy(autoMissed = true) }
            } finally { update { it.copy(finding = false, findingStep = "") } }
        }
        autoTextJobs[book.id] = job
        job.invokeOnCompletion { if (autoTextJobs[book.id] == job) autoTextJobs.remove(book.id) }
    }

    /** Narration sync runs only while follow along is on screen, bounding battery and data use. */
    fun followAlongVisible(visible: Boolean) {
        syncJob?.cancel()
        syncJob = if (!visible) null else viewModelScope.launch {
            graph.narrationSync.run({ syncTarget() }) { bookId, binding ->
                graph.followAlong.mergeNarration(bookId, binding, binding.anchors.filter { it.auto }, playback.value.durationMs)
                graph.mappingRepository.snapshot(bookId, binding.sourceId)?.let { graph.readingSync.refreshPairing(bookId, it) }
            }
        }
    }

    fun allowSyncModelDownload() = graph.narrationSync.allowModelDownload()
    fun setFollowAlongAuto(value: Boolean) { followAlongAuto.value = value; graph.preferences.edit().putBoolean("followAlongAuto", value).apply() }

    private fun syncTarget(): app.narrio.playback.SyncTarget? {
        if (!followAlongAuto.value) return null
        val state = playback.value
        val text = bookText.value
        val book = state.book ?: return null
        val source = state.source ?: return null
        val part = state.part ?: return null
        val document = text.document?.takeIf { text.bookId == book.id } ?: return null
        val binding = text.bindings.firstOrNull { it.documentId == document.id && it.sourceId == source.id && it.partId == part.id }
        return app.narrio.playback.SyncTarget(book, source, part, state.positionMs, state.durationMs, document, binding)
    }

    private fun textWork(target: ListeningState, action: suspend (Audiobook, AudioSource, AudioPart) -> BookText): Job? {
        val book = target.book ?: return null
        if (textJobs[book.id]?.isActive == true) return null
        // A listener's own choice replaces the automatic lookup.
        autoTextJobs.remove(book.id)?.cancel()
        val job = viewModelScope.launch {
            val source = target.source ?: return@launch
            val part = target.part ?: return@launch
            if (bookText.value.working) return@launch
            if (bookText.value.bookId == book.id) bookText.value = bookText.value.copy(working = true, error = null)
            try { action(book, source, part); messages.emit("Book text saved for follow along") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (bookText.value.bookId == book.id) bookText.value = bookText.value.copy(error = textError(error)) }
            finally { if (bookText.value.bookId == book.id) bookText.value = bookText.value.copy(working = false) }
        }
        textJobs[book.id] = job
        job.invokeOnCompletion { if (textJobs[book.id] == job) textJobs.remove(book.id) }
        return job
    }

    fun selectTextChapter(chapterId: String) = viewModelScope.launch {
        val state = playback.value
        val document = bookText.value.document ?: return@launch
        if (document.timedSourceId.isNotBlank() || FollowAlongTiming.passages(document, chapterId).isEmpty()) return@launch
        val source = state.source ?: return@launch
        val part = state.part ?: return@launch
        if (source.parts.size == 1 && chapterId != WHOLE_BOOK) {
            val binding = bookText.value.bindings.firstOrNull { it.documentId == document.id && it.sourceId == source.id && it.partId == part.id && it.chapterId == WHOLE_BOOK }
                ?: TextBinding(document.id, source.id, part.id, WHOLE_BOOK)
            val first = document.chapters.firstOrNull { it.id == chapterId }?.passages?.firstOrNull() ?: return@launch
            val index = FollowAlongTiming.passages(document, WHOLE_BOOK).indexOfFirst { it.id == first.id }
            seekTextLine(binding, index)
            return@launch
        }
        if (bookText.value.bindings.any { it.documentId == document.id && it.sourceId == source.id && it.partId == part.id && it.chapterId == chapterId }) return@launch
        try { graph.followAlong.bind(state.book!!.id, TextBinding(document.id, source.id, part.id, chapterId)) }
        catch (error: Exception) { bookText.value = bookText.value.copy(error = textError(error)) }
    }

    fun matchTextLine(binding: TextBinding, passageId: String) = viewModelScope.launch {
        val state = playback.value
        val document = bookText.value.document ?: return@launch
        if (state.source?.id != binding.sourceId || state.part?.id != binding.partId || document.id != binding.documentId) return@launch
        try {
            graph.followAlong.bind(state.book!!.id, FollowAlongTiming.addAnchor(document, binding, passageId, state.positionMs, state.durationMs))
            messages.emit("Timing matched to this line. Highlighting between matches is estimated.")
        } catch (error: Exception) { bookText.value = bookText.value.copy(error = textError(error)) }
    }

    fun resetTextTiming() = viewModelScope.launch {
        val state = playback.value
        val source = state.source ?: return@launch
        val part = state.part ?: return@launch
        val binding = bookText.value.bindings.firstOrNull { it.sourceId == source.id && it.partId == part.id }
        if (binding != null) graph.followAlong.bind(state.book!!.id, binding.copy(anchors = emptyList()))
        val snapshot = state.book?.let { graph.mappingRepository.snapshot(it.id, source.id) }
        if (snapshot != null) {
            val key = alignmentKey(state.book!!.id, snapshot, part.id)
            graph.alignmentJobs.save(AlignmentProgress(key))
            graph.bookAlignment.enqueue(key, graph.offline.complete(part))
        }
        graph.narrationSync.retry(part.id)
        bookText.value = bookText.value.copy(error = null)
    }

    fun seekTextLine(binding: TextBinding, index: Int) {
        val state = playback.value
        val document = bookText.value.document ?: return
        if (state.source?.id != binding.sourceId || state.part?.id != binding.partId || document.id != binding.documentId) return
        val time = FollowAlongTiming.timeline(document, binding, state.durationMs)?.starts?.getOrNull(index) ?: return
        if (time < 0 || (state.durationMs > 0 && time >= state.durationMs)) {
            messages.tryEmit(if (document.timedSourceId.isNotBlank()) "That timestamp is beyond this audio part. Check that the timing track matches." else "That passage is narrated in another audio part.")
            return
        }
        graph.playback.service?.seek(time)
    }

    fun removeBookText() = viewModelScope.launch {
        val id = playback.value.book?.id ?: return@launch
        textJobs[id]?.cancel(); autoTextJobs[id]?.cancel()
        graph.followAlong.remove(id)
        bookText.value = bookText.value.copy(error = null)
        messages.emit("Book text removed. Your audio and listening progress are saved.")
    }

    fun clearTextError() { bookText.value = bookText.value.copy(error = null) }
    private fun textError(error: Exception) = when (error) {
        is kotlinx.serialization.SerializationException -> "Book text couldn't be read. Choose another file or try the search again."
        is ProviderException, is IllegalArgumentException -> error.message.orEmpty()
        else -> "Couldn't open book text. Check the file or your connection, then try again."
    }

    private suspend fun awaitService(): ListeningService {
        repeat(50) { graph.playback.service?.takeIf { it.initialized }?.let { return it }; delay(100) }
        throw ProviderException("The player is still connecting. Try again in a moment.")
    }
    private fun friendly(error: Exception) = if (error is ProviderException) error.message.orEmpty() else "Couldn't reach this source. Check your internet connection and try again."
    override fun onCleared() { MediaController.releaseFuture(controllerFuture); super.onCleared() }

    companion object {
        val curated = listOf(
            Audiobook("secret_garden_1105_librivox", "The Secret Garden", "Frances Hodgson Burnett", "Ashleighjane", durationMs = 26_131_000),
            Audiobook("pride_prejudice_krs_librivox", "Pride and Prejudice", "Jane Austen", "Karen Savage", durationMs = 37_380_000),
            Audiobook("adventures_sherlock_holmes_rg_librivox", "The Adventures of Sherlock Holmes", "Arthur Conan Doyle", "Ruth Golding", durationMs = 48_567_000),
            Audiobook("greatgatsby_2101_librivox", "The Great Gatsby", "F. Scott Fitzgerald", "Kara Shallenberg", durationMs = 20_283_000),
            Audiobook("dracula_ks_1608_librivox", "Dracula", "Bram Stoker", "Kara Shallenberg"),
            Audiobook("alice_in_wonderland_librivox", "Alice's Adventures in Wonderland", "Lewis Carroll"),
        )
    }
}
