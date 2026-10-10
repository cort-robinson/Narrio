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

/** [query] is the search [books] answer, which can trail the field while the next search loads. */
data class CatalogState(val books: List<Audiobook> = emptyList(), val loading: Boolean = true, val error: String? = null, val notice: String? = null, val query: String = "")
data class SelectionState(val book: Audiobook? = null, val loading: Boolean = false, val error: String? = null, val metadataLoading: Boolean = false)
data class EbookWebsiteRequest(val book: Audiobook, val link: EbookSearchLink)
data class EbookWebsiteState(val request: EbookWebsiteRequest? = null, val working: Boolean = false, val step: String = "", val error: String? = null)
/**
 * [recordings] are verified and confidently matched, best first; [possible] need the listener's review.
 * The automatic [choice] is the best recording unless the listener picked another version.
 */
data class SourceSearchState(
    val book: Audiobook? = null, val recordings: List<Audiobook> = emptyList(), val loading: Boolean = false, val searched: Boolean = false,
    val error: String? = null, val possible: List<Audiobook> = emptyList(), val chosenId: String? = null,
    val streamed: StreamedSourceSearch? = null,
    /** The listener's pick carried into a new search of the same book until its results include it again. */
    val kept: Audiobook? = null,
    /** The listener's own search words for this lookup; null for the automatic search. */
    val words: String? = null,
    /** Releases the listener brought in themselves (a link, their TorBox library, phone files); kept across searches. */
    val added: List<Audiobook> = emptyList(),
) {
    /** The listener may pick any match, including a possible one; otherwise the best verified recording leads. */
    val choice: Audiobook? get() = results.firstOrNull { it.id == chosenId } ?: streamed?.best?.recording ?: recordings.firstOrNull()
    val versions: List<Audiobook> by lazy { SourceQuality.versions(recordings) }
    val results: List<Audiobook> get() = (recordings + possible + added).distinctBy { it.id }

    /** Without releases the listener said aren't this book: in every section, the best match, added ones, and the pick. */
    fun withoutHidden(keys: Set<String>): SourceSearchState {
        if (keys.isEmpty()) return this
        fun hidden(recording: Audiobook) = HiddenReleases.hidden(recording, keys)
        val pick = results.firstOrNull { it.id == chosenId }
        return copy(recordings = recordings.filterNot(::hidden), possible = possible.filterNot(::hidden), added = added.filterNot(::hidden),
            chosenId = chosenId.takeUnless { pick != null && hidden(pick) },
            streamed = streamed?.let { it.copy(groups = HiddenReleases.exclude(it.groups, keys), best = it.best?.takeUnless { best -> hidden(best.recording) }) })
    }

    fun withSnapshot(snapshot: StreamedSourceSearch): SourceSearchState {
        val all = snapshot.groups.flatMap { it.recordings }
        val best = snapshot.best?.recording
        val recordings = if (best == null) all else listOf(best) + all.filter { it.id != best.id }
        val possible = snapshot.groups.flatMap { it.possible }
        val picked = results.firstOrNull { it.id == chosenId } ?: kept?.takeIf { it.id == chosenId }
        val chosen = (recordings + possible + added).firstOrNull {
            picked != null && (it.id == picked.id || picked.torrentHash.isNotBlank() && it.torrentHash.equals(picked.torrentHash, true))
        }?.id ?: chosenId
        return SourceSearchState(snapshot.book, recordings, loading = !snapshot.complete, searched = true,
            error = snapshot.groups.filter { it.status == SourceGroupStatus.FAILED }.mapNotNull { it.message }.distinct().joinToString(" ").ifBlank { null },
            possible = possible, chosenId = chosen, streamed = snapshot, kept = picked, words = words, added = added)
    }

    /**
     * Searching the same book again (after connecting TorBox, or Search again) keeps the listener's own pick, matched
     * by recording id or torrent hash as results arrive; only when it doesn't come back does the best match lead.
     */
    fun keepingChoiceOf(previous: SourceSearchState): SourceSearchState {
        if (previous.book?.id != book?.id || previous.chosenId == null) return this
        val picked = previous.results.firstOrNull { it.id == previous.chosenId } ?: previous.kept?.takeIf { it.id == previous.chosenId } ?: return this
        val match = results.firstOrNull { it.id == picked.id || picked.torrentHash.isNotBlank() && it.torrentHash.equals(picked.torrentHash, true) }
        return copy(chosenId = match?.id ?: picked.id, kept = match ?: picked)
    }
}
/** The playing book's active text and its timing bindings, for narration sync and read along. */
data class BookTextState(
    val bookId: String = "",
    val document: BookText? = null,
    val bindings: List<TextBinding> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
/**
 * Find ebook for one book: the latest per-source snapshot, and what's being added. [searchError] means the lookup
 * itself couldn't start; [error] is a failed add.
 */
data class EbookSearchState(
    val bookId: String = "", val searching: Boolean = false, val searched: Boolean = false,
    val streamed: StreamedEbookSearch? = null, val searchError: String? = null, val error: String? = null,
    /** Candidate id, or [FILE], while an edition is being added; [added] is set once it is. */
    val adding: String? = null, val added: String? = null,
    /** What a slow add is doing right now, such as waiting for a website's download server. */
    val step: String = "",
    /** The reader's own search words this search used; blank searched by the book's title and author. */
    val words: String = "",
    /** [NarrationContext.key] of the recording this search judged ebooks against; blank without one. */
    val narration: String = "",
) {
    val results: List<BookTextSource> get() = streamed?.groups.orEmpty().flatMap { it.editions + it.possible }
    /** A source couldn't be checked, so the results may be missing something. */
    val incomplete: Boolean get() = streamed?.groups.orEmpty().any { it.status == SourceGroupStatus.FAILED }
    companion object { const val FILE = "file" }
}
/** Adding an ebook file from the shelf as a new book. */
data class EbookImportState(val working: Boolean = false, val error: String? = null)
/**
 * The book, edition, and place Read opens. [together] reads along with the narration; [fromListening] means it was
 * opened from the Listening room, where Back returns.
 */
data class ReaderRequest(val book: Audiobook, val edition: EbookEdition, val place: PlaceSummary? = null, val together: Boolean = false, val fromListening: Boolean = false,
                         val opened: Long = System.nanoTime())

@OptIn(ExperimentalCoroutinesApi::class)
class NarrioViewModel @JvmOverloads constructor(
    application: Application,
    private val sourceEngine: StreamingSourceSearch = (application as NarrioApplication).graph.streamingSourceSearch,
    /** Checks a TorBox key and stores it; replaceable so tests never need a real account. */
    private val torBoxSave: suspend (String) -> Unit = (application as NarrioApplication).graph.let { graph -> { key -> graph.torbox.connect(key); graph.credentials.write(key) } },
) : AndroidViewModel(application) {
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
    /** True only while a tapped Listen is loading its audio, so the button can say so. */
    val starting = MutableStateFlow(false)
    val connected = MutableStateFlow(graph.credentials.read() != null)
    private val torBoxConnector = TorBoxConnector(viewModelScope, torBoxSave) { torBoxConnected() }
    /** The Connect TorBox sheet and its attempt; see [requestTorBoxConnect]. */
    val torBox = torBoxConnector.state
    /** Changes whenever the TorBox key does, so results found with another account (or none) are never reused. */
    private var torBoxAccount = 0
    /** Settings shows Sources & add-ons instead of its home; see [openSourceSettings]. */
    val sourceSettingsOpen = MutableStateFlow(false)
    private val appearanceStore = AppearanceStore(graph.preferences)
    private val appearanceState = MutableStateFlow(appearanceStore.read())
    val appearance = appearanceState.asStateFlow()
    private val bookThemeStore = BookThemeStore(graph.preferences)
    private val bookThemesState = MutableStateFlow(bookThemeStore.read())
    val bookThemes = bookThemesState.asStateFlow()
    private val coverLoads = java.util.Collections.synchronizedSet(HashSet<String>())
    val sourceSearch = MutableStateFlow(SourceSearchState())
    /** The recording each book was last played from, so its page resumes it rather than another match. */
    val listeningRecordings = graph.listeningRecordings.all
    /** The recording a book is getting ready, kept apart from the one it's listened to from; null when none is. */
    suspend fun preparationRecord(bookId: String): PreparationRecord? = graph.preparations.current(bookId)
    val sourceProviderSettings: SourceProviderSettings = graph.sourceProviderSettings
    val streamedSourceSearch: StateFlow<StreamedSourceSearch?> = sourceSearch.map { it.streamed }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val downloads = graph.offline.books
    val wifiOnly = MutableStateFlow(graph.preferences.getBoolean("downloadWifi", true))
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Asks the shell to request notification permission (once per install), so a TorBox preparation can say when it's ready. */
    val notificationsWanted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted after Now playing is dismissed, so the shell can offer Undo. */
    val dismissedPlayback = MutableSharedFlow<ListeningState>(extraBufferCapacity = 1)
    val shelf = graph.library.observeShelf().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val shelfState = graph.library.observeShelfState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun editions(bookId: String): Flow<List<EbookEdition>> = combine(graph.library.observeEditions(bookId), graph.library.observeBookText(bookId)) { editions, active ->
        editions.map { it.edition(it.editionId == active?.documentId) }
    }
    fun sharedPosition(bookId: String) = graph.sharedPositions.observe(bookId)
    suspend fun activateEdition(bookId: String, editionId: String) = graph.followAlong.activateEdition(bookId, editionId)
    suspend fun removeEdition(bookId: String, editionId: String) = graph.followAlong.removeEdition(bookId, editionId)
    val playback = graph.playback.state
    val bookText = MutableStateFlow(BookTextState())
    /** Library data and sync seen by the shelf and details; replaceable so tests and integration can supply their own. */
    val readingLibrary = MutableStateFlow<ReadingLibrary>(RoomReadingLibrary(graph))
    val shelfFormats: StateFlow<Map<String, BookFormats>> = readingLibrary.flatMapLatest { it.observeShelf() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val detailFormats: StateFlow<BookFormats?> = combine(readingLibrary, selection.map { it.book }.distinctUntilChanged { a, b -> a?.id == b?.id && a?.sources == b?.sources }, ::Pair)
        .flatMapLatest { (library, book) -> if (book == null) flowOf(null) else library.observeBook(book) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val shelfFilter = MutableStateFlow(ShelfFilter.ALL)
    val ebookSearch = MutableStateFlow(EbookSearchState())
    val ebookProviderSettings: SourceProviderSettings = graph.ebookProviderSettings
    private var ebookSession: EbookSearchSession? = null
    private var ebookBook: Audiobook? = null
    /** The recording the latest ebook search for a book followed; later searches for that book reuse it. */
    private var ebookNarration: Pair<String, NarrationContext?> = "" to null
    private val ebookResults = RecentSourceResults<EbookSearchState>()
    val ebookWebsite = MutableStateFlow(EbookWebsiteState())
    private var ebookWebsiteJob: Job? = null
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
    fun undoReadingJump(session: app.narrio.reader.ReaderSession, previous: ContentCursor, navigationVersion: Long) = viewModelScope.launch {
        if (reader.value?.book?.id != session.book.bookId || session.navigationVersion != navigationVersion) return@launch
        session.undo(previous); clearSyncJump()
    }
    fun clearSyncJump() = graph.readingSync.clearJump()
    suspend fun resolveReadingStart(bookId: String, editionId: String, previous: ContentCursor?, pagesMoved: Int? = null) =
        graph.readingSync.readingStart(bookId, editionId, previous, pagesMoved)
    /** An explicit sentence tap: plays from [cursor], keeping play/pause. False when that text isn't mapped to audio. */
    suspend fun seekFromText(cursor: ContentCursor): Boolean = withContext(Dispatchers.Main.immediate) { graph.playback.service?.seekFromText(cursor) == true }
    /** Recognizes narration on the phone while reading along, keeping the highlight in step. Saved under its original key. */
    val readAlongSync = MutableStateFlow(graph.preferences.getBoolean("followAlongAuto", true))
    private var syncJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var sourceSearchJob: Job? = null
    private var sourceSession: SourceSearchSession? = null
    private val sourceResults = RecentSourceResults<SourceSearchState>()
    private var detailMetadataJob: Job? = null
    private var metadataRequest = 0
    /** Fix-it-yourself sourcing on the Advanced layer: search words, hidden releases, links, files, phone audio. */
    val advanced = AdvancedSourcing(this)
    private var controller: MediaController? = null
    private val controllerFuture = MediaController.Builder(application, SessionToken(application, ComponentName(application, ListeningService::class.java))).buildAsync()

    init {
        controllerFuture.addListener({ runCatching { controller = controllerFuture.get() } }, ContextCompat.getMainExecutor(application))
        search()
        viewModelScope.launch {
            downloads.map { list -> list.filter { it.complete }.map { it.source.id }.toSet() }.distinctUntilChanged().drop(1)
                .collect { sourceResults.clear() }
        }
        // A running search re-ranks itself when releases are hidden; remembered results must not show them again.
        viewModelScope.launch { graph.hiddenReleases.hidden.drop(1).collect { sourceResults.clear(); refilterHidden() } }
        viewModelScope.launch {
            combine(sourceProviderSettings.providers.map { list -> list.map { listOf(it.id, it.enabled, it.order) } }.distinctUntilChanged(),
                graph.addons.installed) { providers, addons -> providers to addons }.drop(1).collect {
                val book = sourceSearch.value.book
                val active = sourceSearch.value.searched
                sourceResults.clear()
                sourceSearchJob?.cancel(); sourceSession = null
                sourceSearch.value = SourceSearchState(book = book)
                if (active && book != null && selection.value.book?.id == book.id) findSources(book, force = true)
            }
        }
        viewModelScope.launch {
            combine(ebookProviderSettings.providers.map { list -> list.map { listOf(it.id, it.enabled, it.order) } }.distinctUntilChanged(),
                graph.addons.installed) { providers, addons -> providers to addons }.drop(1).collect {
                ebookResults.clear()
                val book = ebookBook
                if (book != null && ebookSearch.value.searched && ebookSearch.value.adding == null) {
                    if (selection.value.book?.id == book.id) findEbooks(book, force = true) else stopEbookSearch(reset = true)
                }
            }
        }
        viewModelScope.launch {
            // Each notification request is handled once, by whichever screen takes it first.
            graph.preparationRequests.filterNotNull().collect { request -> if (graph.preparationRequests.compareAndSet(request, null)) handle(request) }
        }
        viewModelScope.launch {
            // Background checks notify only while Narrio is away; on screen, say it here.
            graph.preparations.changes.collect { change ->
                if (graph.playback.visible) messages.emit(if (change.ready) "${change.book.title} is ready to listen." else "${change.book.title} couldn't get ready. Try another recording.")
            }
        }
        viewModelScope.launch {
            readingSync.map { it.audioJump }.distinctUntilChanged().collect { jump ->
                val target = jump?.destination
                if (jump?.offerUndo == true && target != null) {
                    val state = readingSync.value
                    val source = graph.mappingRepository.snapshot(state.bookId, target.sourceId)?.source
                    announceJump(PositionJump(state.bookId, jump.from ?: PositionOrigin.READING,
                        listeningPlace(target, source).label, jump.confidence, ::undoSyncJump))
                }
            }
        }
        viewModelScope.launch {
            playback.map { it.book?.id }.distinctUntilChanged().collectLatest { id ->
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
    fun read(bookId: String) = viewModelScope.launch { graph.library.find(bookId)?.book()?.let { read(it) } }
    /** True when the book has an attached EPUB or text edition the reader can open (timing tracks aren't readable). */
    fun canRead(bookId: String): Flow<Boolean> = graph.library.observeBookText(bookId)
        .map { entry -> entry != null && graph.editionFiles.original(bookId, entry.documentId) != null }

    /** Opens the playing book's reader reading along with the narration; Back returns to the Listening room. */
    fun readAlong() { playback.value.book?.let { read(it, together = true, fromListening = true) } }

    /** Turns reading along on or off inside the open reader, keeping the page. */
    fun setReadAlong(on: Boolean) { reader.update { it?.copy(together = on, fromListening = it.fromListening && on) } }

    /**
     * Starting read along from a page: that page becomes the shared place, then the book's recording starts there
     * (paused), loading it first when another book or nothing is playing. Moves over 30 s offer Undo.
     */
    fun listenFromPage(session: app.narrio.reader.ReaderSession) = viewModelScope.launch {
        val bookId = session.book.bookId
        val page = session.controller.visible.value?.first ?: session.controller.cursor.value ?: return@launch
        val committed = session.commitNow(page)
        val service = graph.playback.service
        if (playback.value.book?.id == bookId && service != null) { if (committed) service.alignToSharedPosition(); return@launch }
        val entry = graph.library.find(bookId)
        val played = entry?.let { graph.recordingFor(it) }
        val source = entry?.source() ?: entry?.book()?.takeIf { it.provider != "catalog" }?.sources?.firstOrNull()
        if (entry == null || source == null) { messages.emit("This book has no recording on your shelf yet."); return@launch }
        try { awaitService().load(played ?: entry.book(), source, autoplay = false) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)) }
    }

    /** Leaving read along for the Listening room starts the audio where reading left the shared place. */
    private fun returnToListening() = viewModelScope.launch { graph.playback.service?.alignToSharedPosition() }

    fun search(value: String = query.value, cat: String = category.value) {
        val browseCategory = if (value.isBlank()) cat else "All"
        query.value = value; category.value = browseCategory
        searchJob?.cancel()
        // The last results stay on screen while the next ones load, so typing never blanks the list.
        catalog.value = CatalogState(catalog.value.books, true, query = catalog.value.query)
        searchJob = viewModelScope.launch {
            delay(if (value.isBlank()) 0 else 350)
            try {
                catalog.value = CatalogState(graph.books.search(value, browseCategory), false, query = value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { catalog.value = CatalogState(emptyList(), false, friendly(error)) }
        }
    }

    fun addonsChanged() {
        sourceResults.clear()
        ebookResults.clear()
        sourceSearchJob?.cancel()
        sourceSession = null
        sourceSearch.value = SourceSearchState(book = sourceSearch.value.book)
        search()
    }

    /**
     * Runs when a book without a recording of its own opens, and when the recording chooser opens on one that has
     * one. Complete results are reused briefly so returning to a book is instant. [words] searches every source with
     * the listener's own words instead of the book's title variants.
     */
    fun findSources(book: Audiobook, force: Boolean = false, words: String? = null) {
        sourceSearchJob?.cancel()
        sourceSession = null
        val query = SourceWords.clean(words)
        val key = "${book.id}|${connected.value}|$torBoxAccount|${graph.listeningRecordings.keys(book.id).sorted().joinToString(",")}|${query.orEmpty()}"
        val previous = sourceSearch.value
        // Releases the listener brought in for this book stay through every search of it.
        val added = previous.takeIf { it.book?.id == book.id }?.added.orEmpty()
        sourceResults.get(key, force)?.let { sourceSearch.value = it.copy(added = added).keepingChoiceOf(previous); return }
        sourceSearch.value = SourceSearchState(book = book, loading = true, searched = true, words = query, added = added).keepingChoiceOf(previous)
        sourceSearchJob = viewModelScope.launch {
            try {
                val engine = sourceEngine
                sourceSession = if (query != null && engine is CustomWordsSourceSearch) engine.start(book, connected.value, this, query)
                    else sourceEngine.start(book, connected.value, this)
                sourceSession!!.state.collect { snapshot ->
                    sourceSearch.update { it.withSnapshot(snapshot) }
                    if (snapshot.complete && sourceSearch.value.error == null) sourceResults.put(key, sourceSearch.value.copy(added = emptyList()))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { sourceSearch.value = SourceSearchState(book, searched = true, error = friendly(error), words = query, added = added) }
        }
    }

    fun retrySource(providerId: String) { sourceSession?.retry(providerId) }

    /**
     * The recording chooser opened: a book with its own recording looks for the others now, once. A search that
     * already ran stays as it is; failed sources keep their own Retry, and Try again searches afresh.
     */
    fun findRecordings() {
        val book = selection.value.book?.catalogIdentity() ?: return
        val search = sourceSearch.value
        if (search.book?.id != book.id || !search.searched) findSources(book)
    }

    /**
     * A release the listener brought in for [bookId] becomes one of its results and the chosen one; false when that book
     * isn't open any more. Bringing it in again undoes an earlier Not this book.
     */
    fun addRecording(bookId: String, recording: Audiobook): Boolean {
        if (sourceSearch.value.book?.id != bookId || selection.value.book?.id != bookId) return false
        graph.hiddenReleases.keys(bookId).filter { HiddenReleases.hidden(recording, setOf(it)) }.forEach { graph.hiddenReleases.unhide(bookId, it) }
        sourceSearch.update { state -> state.copy(added = listOf(recording) + state.added.filterNot { sameRecording(it, recording) }, chosenId = recording.id) }
        return true
    }

    /**
     * Hidden releases leave the open results at once. A running search re-ranks itself; results reused from the recent
     * cache, which have no running search, are looked up again so the best match is chosen without them (or with an
     * unhidden release back).
     */
    private fun refilterHidden() {
        val state = sourceSearch.value
        val book = state.book ?: return
        sourceSearch.value = state.withoutHidden(graph.hiddenReleases.keys(book.id))
        if (sourceSession == null && state.searched && !state.loading) findSources(book, force = true, words = state.words)
    }

    fun chooseVersion(recording: Audiobook) {
        sourceSearch.update { state -> if (recording in state.results) state.copy(chosenId = recording.id) else state }
    }

    /** The audio format remembered for a book, chosen in the recording chooser. */
    fun savedFormat(bookId: String): String = graph.preferences.getString("format:$bookId", "").orEmpty()

    /** The book whose recordings are being chosen: the search's book when it is the open one, else the page's identity. */
    private fun parentBook(): Audiobook? {
        val open = selection.value.book
        return sourceSearch.value.book?.takeIf { open == null || it.id == open.id } ?: open?.catalogIdentity()
    }

    /** [recording] with its book's details, attached to the book; what was kept under its own id moves with it. */
    private suspend fun adopt(recording: Audiobook, book: Audiobook): Audiobook {
        val adopted = graph.followAlong.adoptRecording(SourceQuality.describe(recording, book), book)
        if (recording.id != book.id) graph.listeningRecordings.move(recording.id, book.id)
        return adopted
    }

    private suspend fun currentFor(book: Audiobook, entry: ShelfEntry?): CurrentRecording? = entry?.let {
        currentRecording(selection.value.book?.takeIf { open -> open.id == book.id } ?: book, it, graph.listeningRecordings[book.id], downloads.value, graph.preparations.current(book.id))
    }

    /** The audio [recording] last played for [bookId], with its place: from its own history, or its unchanged source ids. */
    private suspend fun earlierPlay(bookId: String, recording: Audiobook): SourcePosition? =
        (graph.listeningRecordings.playedSources(bookId, recording) + recording.sources.map { it.id }).distinct()
            .mapNotNull { graph.library.position(bookId, it) }.maxByOrNull { it.updatedAt }

    /** Where [recording] would pick up for [bookId], when it has a saved place; the chooser says so before switching. */
    suspend fun savedPlace(bookId: String, recording: Audiobook): PlaceSummary? {
        val earlier = earlierPlay(bookId, recording) ?: return null
        val source = runCatching { NarrioJson.decodeFromString<AudioSource>(earlier.sourceJson) }.getOrNull()
        if (earlier.positionMs <= 0 && earlier.partId == source?.parts?.firstOrNull()?.id) return null
        return listeningPlace(AudioCursor(earlier.sourceId, earlier.partId, earlier.positionMs), source)
    }

    /** A recording TorBox finished getting ready for [entry], with the audio it prepared; null when [recording] isn't it. */
    private suspend fun prepared(entry: ShelfEntry?, recording: Audiobook, described: Audiobook): Audiobook? {
        if (entry?.state != "ready") return null
        val pending = preparingRecording(entry, graph.preparations.current(entry.bookId))?.takeIf { sameRecording(it, recording) } ?: return null
        if (pending.cacheState == "cached" && pending.sources.any { it.torrentId == entry.preparationId }) return pending.withMetadataFrom(described)
        return try {
            val sources = graph.torbox.sources(pending, entry.preparationId)
            described.copy(cacheState = "cached", cachedFormats = sources.map { it.format }, sources = sources)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)); null }
    }

    /**
     * Listens to [recording] for the open book. Its own last-played audio resumes, from its phone copy when that's
     * downloaded; otherwise a phone copy plays, then a ready stream in the remembered, M4B, or first ready format.
     * An explicit [format] plays only in that format, never another, and is remembered only once it plays. Every
     * recording keeps its own place, so switching never loses one. [start] is the chooser's start choice, captured as
     * Listen is tapped: closing the chooser clears it before this work gets to it.
     */
    fun listenTo(recording: Audiobook, format: String? = null, delivery: String? = null, start: StartChoice? = advanced.startChoice.value) = viewModelScope.launch {
        val book = parentBook() ?: return@launch
        val entry = graph.library.find(book.id)
        val mine = currentFor(book, entry)?.takeIf { sameRecording(it.recording, recording) }
        val described = adopt(recording, book)
        // Another recording than the listener's own starts where they chose: near their place, its own, or the beginning.
        val place = if (mine == null) advanced.switchPlace(book, start) else null
        fun play(target: Audiobook, source: AudioSource, how: String) { if (format != null) chooseFormat(described, format); start(target, source, how, place) }
        mine?.offline?.takeIf { format == null || it.source.format == format }?.let { play(described, it.source, it.source.delivery); return@launch }
        val earlier = mine?.source ?: earlierPlay(book.id, recording)?.let { runCatching { NarrioJson.decodeFromString<AudioSource>(it.sourceJson) }.getOrNull() }
        earlier?.takeIf { format == null || it.format == format }?.let { play(described, it, delivery ?: it.delivery); return@launch }
        val wanted = format ?: savedFormat(book.id)
        val copies = downloads.value.filter { it.complete && it.book.id == book.id && sameRecording(it.book, recording) }
        val copy = copies.firstOrNull { it.source.format == wanted } ?: copies.firstOrNull()?.takeIf { format == null }
        if (copy != null) { play(described, copy.source, copy.source.delivery); return@launch }
        val playing = prepared(entry, recording, described) ?: described
        val ready = readyFormats(playing).let { formats -> playing.sources.filter { it.format in formats } }
        val source = if (format != null) ready.firstOrNull { it.format == format }
            else ready.firstOrNull { it.format == wanted } ?: ready.firstOrNull { it.format == entry?.pendingFormat } ?: ready.firstOrNull { it.format == "M4B" } ?: ready.firstOrNull()
        if (!SourceQuality.ready(playing) || source == null) {
            messages.emit(if (format != null) "$format isn't ready for this recording yet. Get it ready, or choose a format that plays now." else "This recording needs time to get ready first.")
            return@launch
        }
        play(playing, source, delivery ?: if (playing.provider == "archive") "archive" else "torbox")
    }

    /** Listens to the page's automatic or chosen recording. */
    fun listenToChoice(format: String? = null) { sourceSearch.value.choice?.let { listenTo(it, format) } }

    /** Asks TorBox to get [recording] ready, in one tap; its progress then shows on the book's page. */
    fun getReady(recording: Audiobook, format: String? = null) = viewModelScope.launch {
        val book = parentBook() ?: return@launch
        val described = adopt(recording, book)
        prepareUncached(described, format ?: defaultFormat(described, savedFormat(book.id)).orEmpty())
    }

    /** Saves [recording]'s audio to the phone in [format]; the listener's own recording saves the audio it plays. */
    fun downloadRecording(recording: Audiobook, format: String? = null, delivery: String? = null) = viewModelScope.launch {
        val book = parentBook() ?: return@launch
        val played = currentFor(book, graph.library.find(book.id))?.takeIf { sameRecording(it.recording, recording) }?.source?.takeIf { format == null || it.format == format }
        val described = adopt(recording, book)
        val source = played ?: described.sources.firstOrNull { it.format == (format ?: defaultFormat(described, savedFormat(book.id))) } ?: return@launch
        if (format != null) chooseFormat(described, format)
        download(described, source, delivery ?: played?.delivery ?: if (described.provider == "archive") "archive" else "torbox")
    }

    /**
     * Opens a book's one page, whichever way the listener arrives. A book without a recording of its own looks for
     * one right away; one with a recording keeps it, and looks for others when the recording chooser opens.
     */
    fun open(book: Audiobook, keepSources: Boolean = false) {
        playerOpen.value = false
        reader.value = null
        preparation.value = null
        preferredFormat.value = ""
        detailJob?.cancel()
        detailMetadataJob?.cancel()
        sourceSearchJob?.cancel()
        if (!keepSources) sourceSearch.value = SourceSearchState(book = book.catalogIdentity())
        selection.value = SelectionState(book, !book.detailsLoaded)
        detailJob = viewModelScope.launch {
            try {
                var full = if (book.detailsLoaded) book else graph.catalog.recording(book.recordingId.ifBlank { book.id }).forBook(book)
                if (!connected.value) full = full.copy(cacheState = "unchecked", cachedFormats = emptyList())
                selection.value = SelectionState(full)
                val saved = graph.library.find(full.id)
                val savedRecording = saved?.book()?.takeIf { it.provider != "catalog" }
                if (saved != null) rememberOlderShelf(saved)
                if (full.provider == "catalog" && savedRecording == null) {
                    val search = sourceSearch.value
                    // Catalog books have no audio until discovery runs. An attached ebook must not suppress it
                    // or make lookup wait for edition hydration and reading-position mapping.
                    if (search.book?.id != full.id || !search.searched || search.loading) findSources(full)
                }
                if (full.provider != "catalog") saved?.book()?.takeIf { it.metadataUpdatedAtMs > full.metadataUpdatedAtMs }?.let { full = full.withMetadataFrom(it) }
                preferredFormat.value = saved?.pendingFormat?.takeIf { it.isNotBlank() } ?: graph.preferences.getString("format:${full.id}", saved?.source()?.format.orEmpty()).orEmpty()
                val placeholder = saved?.let { graph.preparations.placeholder(it) }
                if (saved != null && placeholder != null) {
                    preparation.value = placeholder
                    if (connected.value) {
                        updatePreparation(full)
                        selection.value.book?.takeIf { it.id == full.id }?.let { full = it.withMetadataFrom(full) }
                    }
                } else if (connected.value && full.provider != "catalog" && full.provider != "torbox" && full.torrentHash.isNotBlank()) {
                    full = graph.torbox.checkCached(listOf(full)).first()
                    if (selection.value.book?.id == full.id) selection.value = SelectionState(full)
                }
                if (full.provider != "catalog" && selection.value.book?.id == full.id) {
                    selection.value = selection.value.copy(book = full)
                    loadMetadata(full)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { selection.value = SelectionState(book, false, friendly(error)) }
        }
    }

    private fun loadMetadata(book: Audiobook, force: Boolean = false) {
        // Phone audio keeps the book's own details; its folder name isn't a release to look up.
        if (book.provider == "archive" || book.provider == "catalog" || book.provider == LocalAudio.PROVIDER) return
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
    fun back() { val open = reader.value; if (open?.together == true && open.fromListening) { reader.value = null; playerOpen.value = true; returnToListening() } else if (open?.together == true) setReadAlong(false) else if (open != null) reader.value = null else if (playerOpen.value) playerOpen.value = false else { detailJob?.cancel(); detailMetadataJob?.cancel(); sourceSearchJob?.cancel(); stopEbookSearch(); selection.value = SelectionState(); sourceSearch.value = SourceSearchState() } }
    fun navigate(index: Int) { detailJob?.cancel(); detailMetadataJob?.cancel(); sourceSearchJob?.cancel(); sourceSearch.value = SourceSearchState(); stopEbookSearch(); destination.value = index; selection.value = SelectionState(); playerOpen.value = false; reader.value = null; sourceSettingsOpen.value = false }
    /** Settings, opened straight on Sources & add-ons. */
    fun openSourceSettings() { navigate(2); sourceSettingsOpen.value = true }
    fun save(book: Audiobook) = viewModelScope.launch { graph.library.save(book); messages.emit("Saved to your shelf") }
    fun remove(book: Audiobook) = viewModelScope.launch {
        if (reader.value?.book?.id == book.id) reader.value = null
        if (playback.value.book?.id == book.id) graph.playback.service?.forget()
        downloads.value.filter { it.book.id == book.id }.forEach { graph.offline.remove(it.source) }
        advanced.forget(book.id)
        graph.followAlong.remove(book.id)
        graph.listeningRecordings.remove(book.id)
        graph.library.remove(book.id); graph.preparations.forget(book.id); app.narrio.preparation.PreparationNotifications.clear(getApplication(), book.id)
        messages.emit("Removed from your shelf and phone downloads")
    }
    /** Continue plays the audio last played, as the recording that played it, never one being got ready since. */
    fun resume(entry: ShelfEntry) {
        val source = entry.source() ?: return open(entry.book())
        viewModelScope.launch { start(graph.recordingFor(entry) ?: entry.book(), source, source.delivery) }
    }

    /**
     * Shelves saved before the played and preparing recordings were kept apart remember only the row's book. It is
     * the played recording unless it's being got ready; then it's the preparation's.
     */
    private suspend fun rememberOlderShelf(entry: ShelfEntry) {
        if (entry.source() != null && graph.listeningRecordings[entry.bookId] == null)
            playedRecording(entry, null, graph.preparations.current(entry.bookId))?.takeIf { !it.recordingId.startsWith("played:") }?.let(graph.listeningRecordings::adoptExisting)
    }

    /** Continue reopens whichever mode last moved the book's shared place. */
    fun continueBook(entry: ShelfEntry) {
        if (shelfFormats.value[entry.bookId]?.let { it.lastMode ?: it.leadingMode } == PositionOrigin.READING) read(entry.book()) else resume(entry)
    }

    /** Opens the active edition at the shared place: the reading place itself, or its mapped counterpart. */
    fun read(book: Audiobook, together: Boolean = false, fromListening: Boolean = false) = viewModelScope.launch {
        val formats = readingLibrary.value.observeBook(book).first()
        val edition = formats.activeEdition ?: return@launch open(book)
        if (!edition.active) readingLibrary.value.activate(book.id, edition.id)
        val place = if (formats.position?.origin == PositionOrigin.READING) placeSummary(formats) else counterpartPlace(formats)
        playerOpen.value = false
        reader.value = ReaderRequest(book, edition, place, together, fromListening)
    }
    fun closeReader() { reader.value = null }
    /** A place the reader should go to once it opens, such as a reading bookmark chosen while listening. */
    val readerTarget = MutableStateFlow<ContentCursor?>(null)
    fun readAt(bookId: String, cursor: ContentCursor) { readerTarget.value = cursor; read(bookId) }
    fun setShelfFilter(filter: ShelfFilter) { shelfFilter.value = filter }
    /** The shelf's order, kept on this phone. */
    val shelfSort = MutableStateFlow(ShelfSort.from(graph.preferences.getString(ShelfSort.PREFERENCE, null)))
    fun setShelfSort(sort: ShelfSort) { shelfSort.value = sort; graph.preferences.edit().putString(ShelfSort.PREFERENCE, sort.name).apply() }
    /** Words to find on the shelf by title or author. */
    val shelfQuery = MutableStateFlow("")
    fun setShelfQuery(value: String) { shelfQuery.value = value }
    /** A finished book's Listen again: no longer finished, and its recording plays from the start. */
    fun listenAgain(bookId: String) = viewModelScope.launch {
        val entry = graph.library.find(bookId) ?: return@launch
        val source = entry.source() ?: return@launch open(entry.book())
        graph.library.unfinished(bookId)
        try { awaitService().load(graph.recordingFor(entry) ?: entry.book(), source, true, source.parts.first().id, 0); playerOpen.value = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)) }
    }

    /** Moves a book into or out of the shelf's Finished section; finished books leave Continue. */
    fun setFinished(bookId: String, finished: Boolean) = viewModelScope.launch {
        if (finished) graph.library.finished(bookId) else graph.library.unfinished(bookId)
        messages.emit(if (finished) "Marked as finished" else "Marked as not finished")
    }
    /** Recent Discover searches, newest first; stored only on this phone. */
    val recentSearches = MutableStateFlow(RecentSearches.decode(graph.preferences.getString(RecentSearches.PREFERENCE, null)))
    /** Remembers a submitted search, or the one a result was opened from. */
    fun rememberSearch(value: String = query.value) = saveRecentSearches(RecentSearches.add(recentSearches.value, value))
    fun forgetSearch(value: String) = saveRecentSearches(RecentSearches.remove(recentSearches.value, value))
    fun clearRecentSearches() = saveRecentSearches(emptyList())
    private fun saveRecentSearches(next: List<String>) {
        if (next == recentSearches.value) return
        recentSearches.value = next
        graph.preferences.edit().putString(RecentSearches.PREFERENCE, RecentSearches.encode(next)).apply()
    }
    private var announcedJump: PositionJump? = null
    fun announceJump(jump: PositionJump) { announcedJump = jump; positionJumps.tryEmit(jump) }
    fun finishJump(jump: PositionJump) { if (announcedJump === jump) { announcedJump = null; clearSyncJump() } }

    /**
     * Every enabled ebook source looks for [book] at once, as listening sources do. Complete, error-free results are
     * reused for ten minutes; changing ebook sources invalidates them.
     */
    fun findEbooks(book: Audiobook, force: Boolean = false, narration: NarrationContext? = ebookNarration.takeIf { it.first == book.id }?.second) {
        ebookJob?.cancel(); ebookSession = null
        ebookBook = book
        ebookNarration = book.id to narration
        val words = ebookWords(book.id)
        val heard = narration?.key.orEmpty()
        // Another recording, file choice, or set of words is a different search, never served from these results.
        val key = "${book.id}|${connected.value}|$torBoxAccount|${book.sources.joinToString(",") { it.id }}|$words|$heard"
        ebookResults.get(key, force)?.let { ebookSearch.value = it; return }
        ebookSearch.value = EbookSearchState(book.id, searching = true, searched = true, words = words, narration = heard)
        fun update(change: (EbookSearchState) -> EbookSearchState) = ebookSearch.update { if (it.bookId == book.id) change(it) else it }
        ebookJob = viewModelScope.launch {
            try {
                val session = readingLibrary.value.searchEditions(book, connected.value, this, words, narration)
                ebookSession = session
                session.state.collect { snapshot ->
                    update { it.copy(streamed = snapshot, searching = !snapshot.complete) }
                    if (snapshot.complete && snapshot.groups.none { it.status == SourceGroupStatus.FAILED })
                        ebookResults.put(key, EbookSearchState(book.id, searched = true, streamed = snapshot, words = words, narration = heard))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { update { it.copy(searching = false, searchError = friendly(error)) } }
        }
    }

    fun retryEbookSource(providerId: String) { ebookSession?.retry(providerId) }

    /** The reader's own ebook search words for a book, kept on this phone until reset; blank uses its title and author. */
    fun ebookWords(bookId: String): String = graph.preferences.getString("ebookWords:$bookId", "").orEmpty()

    /** Searches every ebook source with [words], kept for this book's later searches and retries; blank resets to its details. */
    fun searchEbooksWith(book: Audiobook, words: String) {
        val kept = words.trim().take(200)
        graph.preferences.edit().apply { if (kept.isBlank()) remove("ebookWords:${book.id}") else putString("ebookWords:${book.id}", kept) }.apply()
        findEbooks(book, force = true)
    }

    /**
     * Opening Find ebook again reuses a running or complete lookup for the same book and recording; a failed one, or
     * one for another recording or file choice, starts over. [narration] is the recording on screen, if any.
     */
    fun openEbookSearch(book: Audiobook, narration: NarrationContext? = null) {
        val state = ebookSearch.value
        if (state.bookId != book.id || state.narration != narration?.key.orEmpty() || !state.searched || state.searchError != null ||
            !state.searching && state.incomplete) findEbooks(book, narration = narration)
        else ebookSearch.update { it.copy(added = null, error = null) }
    }

    /** Leaving a book stops its lookup; a [reset] or unfinished one starts fresh next time. */
    private fun stopEbookSearch(reset: Boolean = false) {
        if (ebookSearch.value.adding != null) return
        ebookJob?.cancel(); ebookSession = null
        if (reset || ebookSearch.value.searching) ebookSearch.value = EbookSearchState()
    }

    /** Adds a found ebook as the book's edition; [then] runs once it's added, such as opening it to read. */
    fun addEbook(book: Audiobook, candidate: BookTextSource, then: () -> Unit = {}) =
        editionWork(book, candidate.id, then) {
            try { readingLibrary.value.addEdition(book, candidate) { step -> ebookSearch.update { if (it.bookId == book.id && it.adding == candidate.id) it.copy(step = step) else it } } }
            catch (check: BrowserCheckException) {
                // The website asks the reader to verify; its own page then offers the download Narrio intercepts.
                openEbookWebsite(book, EbookSearchLink(candidate.attribution.ifBlank { "Ebook website" }, check.url))
                throw ProviderException(check.message.orEmpty())
            }
        }

    fun openEbookWebsite(book: Audiobook, link: EbookSearchLink) {
        AddonManifest.secureUrl(link.url)
        ebookWebsiteJob?.cancel()
        ebookWebsite.value = EbookWebsiteState(EbookWebsiteRequest(book, link))
    }
    fun closeEbookWebsite() {
        ebookWebsiteJob?.cancel()
        val book = ebookWebsite.value.request?.book
        ebookWebsite.value = EbookWebsiteState()
        // A browser check passed there lets a source that asked for one search by itself again.
        ebookSearch.value.streamed?.takeIf { it.book.id == book?.id }?.groups
            ?.filter { it.status == SourceGroupStatus.FAILED && it.checkUrl != null }?.forEach { retryEbookSource(it.providerId) }
    }
    fun downloadWebsiteEbook(target: EbookWebsiteRequest, request: EbookDownloadRequest) {
        if (ebookWebsite.value.request != target || ebookWebsite.value.working) return
        ebookWebsite.value = EbookWebsiteState(target, working = true, step = "Checking this ebook download")
        ebookWebsiteJob = viewModelScope.launch {
            try {
                val edition = readingLibrary.value.downloadWebsiteEbook(target.book, request, connected.value) { step ->
                    ebookWebsite.update { if (it.request == target) it.copy(step = step) else it }
                }
                if (ebookWebsite.value.request != target) return@launch
                ebookSearch.update { if (it.bookId == target.book.id) it.copy(added = edition.id) else EbookSearchState(target.book.id, searched = true, added = edition.id) }
                ebookWebsite.value = EbookWebsiteState()
                messages.emit("Ebook added. Read opens it.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val message = if (error is WebEbookPendingException) error.message else textError(error)
                ebookWebsite.update { if (it.request == target) it.copy(working = false, step = "", error = message) else it }
            }
        }
    }

    /** Removes one edition and its timing; audio, bookmarks, and listening history stay. */
    fun removeEbookEdition(book: Audiobook, edition: EbookEdition) = viewModelScope.launch {
        try { removeEdition(book.id, edition.id); messages.emit("Ebook removed. Your audio and progress are saved.") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(textError(error)) }
    }

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

    private fun editionWork(book: Audiobook, adding: String, then: () -> Unit = {}, action: suspend () -> EbookEdition) = viewModelScope.launch {
        if (ebookSearch.value.adding != null) return@launch
        if (ebookSearch.value.bookId != book.id) ebookSearch.value = EbookSearchState(book.id)
        ebookSearch.update { it.copy(adding = adding, added = null, error = null) }
        try {
            val edition = action()
            ebookSearch.update { if (it.bookId == book.id) it.copy(added = edition.id) else it }
            messages.emit("Ebook added. Read opens it.")
            then()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ebookSearch.update { if (it.bookId == book.id) it.copy(error = textError(error)) else it } }
        finally { ebookSearch.update { if (it.bookId == book.id) it.copy(adding = null, step = "") else it } }
    }

    /** [place] chooses where the resolved source starts, such as near the place in another recording. */
    fun start(book: Audiobook, source: AudioSource, delivery: String, place: ((AudioSource) -> StartAt?)? = null) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true; starting.value = true
        try {
            val resolved = playableSource(book, source, delivery)?.let { graph.releaseFiles.apply(book, it) } ?: return@launch
            // Loading replaces the row's book, so the recording being got ready is pinned down first.
            graph.library.find(book.id)?.let { rememberOlderShelf(it) }
            val at = place?.invoke(resolved)
            if (at == null || at.approximate) awaitService().load(book, resolved, near = at?.cursor)
            else awaitService().load(book, resolved, partId = at.cursor.partId, positionMs = at.cursor.positionMs)
            // Only the prepared recording itself completes its preparation; another one (or phone audio) leaves it pending.
            if (resolved.delivery != LocalAudio.DELIVERY && graph.preparations.played(book.id, resolved)) app.narrio.preparation.PreparationNotifications.clear(getApplication(), book.id)
            if (book.provider != "catalog") graph.listeningRecordings.played(book, resolved.id)
            playerOpen.value = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false; starting.value = false }
    }

    private suspend fun playableSource(book: Audiobook, source: AudioSource, delivery: String): AudioSource? {
        if (graph.offline.complete(source)) return source
        if (source.delivery == LocalAudio.DELIVERY) {
            if (!graph.localAudio.available(source)) throw ProviderException("Narrio can no longer open these audio files. They may have been moved or deleted, or access was removed. Add them again in Advanced.")
            return source
        }
        // Usenet and web downloads from the TorBox library are listed only once they're ready.
        // Without TorBox, its sheet opens over the current screen; nothing plays or downloads until it's connected.
        if (source.delivery != "torbox" && source.delivery.startsWith("torbox")) {
            if (!connected.value) { requestTorBoxConnect(); return null }
            return source
        }
        if (delivery != "torbox") return source
        if (!connected.value) { requestTorBoxConnect(); return null }
        preferredFormat.value = source.format
        if (source.torrentId != null && source.torrentId > 0) {
            val prep = graph.torbox.status(source.torrentId)
            if (!prep.ready) throw ProviderException("This recording is still getting ready in TorBox. Choose one that's ready now to listen right away.")
            return source
        }
        val saved = graph.library.find(book.id)
        // A preparation on the shelf is this recording's only when it's the one being got ready.
        val pendingHere = saved != null && saved.state in listOf(PreparationStates.PREPARING, PreparationStates.READY) &&
            graph.preparations.current(book.id)?.recording?.let { sameRecording(it, book) } == true
        var prep = if (pendingHere) graph.torbox.refresh(book, saved!!.preparationId) else graph.torbox.prepareCached(book, source.format)
        if (!prep.ready && !pendingHere) {
            var attempts = 0
            while (!prep.ready && attempts++ < 3) { delay(1000); prep = graph.torbox.refresh(book, prep.torrentId) }
        }
        // A recording that streams right away leaves another recording's preparation as it was.
        val replaces = pendingHere || !prep.ready || saved?.state !in PreparationStates.TRACKED
        if (selection.value.book?.id == book.id && replaces) preparation.value = prep
        when {
            // This recording's own preparation finished: it settles as ready, never starts over as getting ready.
            pendingHere && prep.ready -> if (saved!!.state != PreparationStates.READY) updatePreparation(book)
            replaces -> graph.preparations.begin(book, prep, source.format)
            else -> graph.library.save(book)
        }
        if (!prep.ready) { notificationsWanted.tryEmit(Unit); messages.emit("Getting ready in TorBox. Choose a recording that's ready to listen now; your shelf shows its progress."); return null }
        return graph.torbox.sources(book, prep.torrentId).firstOrNull { it.format == source.format }
            ?: throw ProviderException("The selected format is missing from this TorBox source. Choose another format.")
    }

    fun prepareUncached(book: Audiobook, format: String) = viewModelScope.launch {
        if (busy.value || !connected.value) return@launch
        busy.value = true
        try {
            val prep = if (book.provider == "torbox") graph.torbox.status(book.sources.first().torrentId!!) else graph.torbox.prepare(book)
            graph.preparations.begin(book, prep, format)
            if (selection.value.book?.id == book.id) { preparation.value = prep; preferredFormat.value = format }
            if (!prep.ready) notificationsWanted.tryEmit(Unit)
            // A preparation that's already finished is announced by the check itself.
            if (updatePreparation(book)?.change == null)
                messages.emit(if (prep.ready) "Ready to listen." else "Getting ready in TorBox. Nothing downloads to your phone; Narrio tells you when it's ready.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }

    fun download(book: Audiobook, source: AudioSource, delivery: String) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        try {
            if (source.delivery == LocalAudio.DELIVERY) { messages.emit("These audio files are already on your phone."); return@launch }
            val resolved = playableSource(book, source, delivery)?.let { graph.releaseFiles.apply(book, it) } ?: return@launch
            graph.library.save(book); graph.offline.queue(book, resolved)
            messages.emit(if (wifiOnly.value) "Phone download queued. It will use Wi-Fi." else "Phone download queued for offline listening.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }
    fun setWifiOnly(value: Boolean) { wifiOnly.value = value; graph.preferences.edit().putBoolean("downloadWifi", value).apply(); graph.offline.setWifiOnly(value) }
    fun chooseFormat(book: Audiobook, format: String) { preferredFormat.value = format; graph.preferences.edit().putString("format:${book.id}", format).apply(); sourceResults.clear() }
    fun pauseDownload(download: OfflineBook) = graph.offline.pause(download.source)
    /** Resumes or retries a phone download with the recording's files as chosen now. */
    fun resumeDownload(download: OfflineBook) = viewModelScope.launch {
        try { graph.offline.resume(download.book, reconcile(download.book, download.source)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)) }
    }

    /**
     * After the listener changes a recording's files, its phone download follows: chosen files are queued (under the
     * download's usual network rules) and files no longer chosen are removed. Nothing happens without a download.
     */
    suspend fun reconcileDownloads(book: Audiobook, source: AudioSource) {
        val download = downloads.value.firstOrNull { it.book.id == book.id && FileChoices.key(book.id, it.book, it.source) == FileChoices.key(book.id, book, source) } ?: return
        graph.offline.resume(download.book, reconcile(download.book, download.source))
    }

    /** The download's layout as chosen now; queued files no longer chosen are removed. */
    private suspend fun reconcile(book: Audiobook, queued: AudioSource): AudioSource {
        val chosen = graph.releaseFiles.apply(book, queued)
        graph.offline.removeParts(queued.parts.filter { part -> chosen.parts.none { it.id == part.id } })
        return chosen
    }
    fun removeDownload(download: OfflineBook) {
        if (playback.value.source?.id == download.source.id && playback.value.playing) graph.playback.service?.toggle()
        graph.offline.remove(download.source)
    }

    fun refreshPreparation(book: Audiobook) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        try {
            updatePreparation(book)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }

    /** Checks TorBox through the same path the background checks use, so both keep one shelf state. Null when nothing is being prepared. */
    private suspend fun updatePreparation(book: Audiobook): PreparationUpdate? {
        // The recording being got ready, never the page's catalog book; a row without a record names it itself.
        val entry = graph.library.find(book.id)
        val shown = entry?.let { preparingRecording(it, graph.preparations.current(book.id)) } ?: book.takeIf { it.provider != "catalog" }
        val update = graph.preparations.check(book.id, shown) ?: return null
        if (update.book != null && selection.value.book?.id == book.id) selection.value = SelectionState(update.book)
        if (selection.value.book?.id == book.id) preparation.value = update.preparation
        return update
    }

    /** A preparation notification, handed over by its non-exported activity. */
    private fun handle(request: app.narrio.preparation.PreparationRequest) = viewModelScope.launch {
        val entry = graph.library.find(request.bookId) ?: return@launch messages.emit("This book is no longer on your shelf.")
        val record = graph.preparations.current(request.bookId)?.takeIf { it.generation == request.generation }
        val ready = record?.ready?.takeIf { entry.state == PreparationStates.READY }
        if (!request.listen || ready == null) {
            open(record?.ready ?: entry.book())
            // An older notification's Listen: a newer preparation, or listening, has replaced the one it announced.
            if (request.listen) messages.emit("This book has changed since that notification. Check its page before listening.")
            return@launch
        }
        val wanted = record.format.ifBlank { savedFormat(request.bookId) }
        val source = ready.sources.firstOrNull { it.format == wanted } ?: ready.sources.firstOrNull { it.format == "M4B" } ?: ready.sources.first()
        start(ready, source, source.delivery)
    }

    /** Opens the Connect TorBox sheet over the current screen; the open book stays selected. */
    fun requestTorBoxConnect() = torBoxConnector.request()
    /** Closing the sheet also cancels an attempt it started; that key is never saved. */
    fun dismissTorBoxConnect() = torBoxConnector.dismiss()
    fun clearTorBoxError() = torBoxConnector.clearError()

    /** A failure stays under the key field; success closes the sheet and the current book looks again with TorBox. */
    fun connect(key: String) = torBoxConnector.connect(key)

    private suspend fun torBoxConnected() {
        torBoxAccount++; connected.value = true; graph.preparations.resumeAll()
        messages.emit("TorBox connected. Books now also check TorBox for ready audio.")
        refreshForTorBox()
    }

    /** In place, after connecting: the open recording checks its cache, and its book's sources and an ebook lookup run again, fresh. */
    private fun refreshForTorBox() {
        val book = selection.value.book
        val search = sourceSearch.value
        if (book != null && book.provider != "catalog" && !playerOpen.value && reader.value == null) open(book, keepSources = true)
        search.book?.takeIf { it.provider == "catalog" && search.searched }?.let { findSources(it, force = true) }
        val ebooks = ebookSearch.value
        ebookBook?.takeIf { it.id == ebooks.bookId && ebooks.searched && ebooks.adding == null && (it.id == book?.id || it.id == playback.value.book?.id) }?.let { findEbooks(it, force = true) }
    }
    fun disconnect() {
        graph.playback.service?.disconnect(); graph.offline.disconnect(); graph.credentials.clear(); torBoxAccount++; connected.value = false
        // Preparations keep their place but say why checking stopped; reconnecting resumes them.
        graph.preparationChecks.cancel(); viewModelScope.launch { graph.preparations.pauseAll(PreparationPolicy.DISCONNECTED) }
        sourceSearchJob?.cancel()
        val searched = sourceSearch.value.searched
        sourceSearch.value = SourceSearchState(book = selection.value.book?.catalogIdentity())
        selection.value.book?.takeIf { it.provider == "catalog" || searched }?.let { findSources(it.catalogIdentity()) }
        messages.tryEmit("TorBox disconnected; its credential has been removed.")
    }
    fun updateAppearance(value: AppearanceSettings) {
        val normalized = value.normalized()
        appearanceStore.save(normalized)
        appearanceState.value = normalized
    }
    fun setBookTheme(book: Audiobook, theme: BookTheme) {
        val next = bookThemesState.value.with(book.id, theme)
        bookThemeStore.save(next)
        bookThemesState.value = next
        ensureCoverColours(book)
    }
    /** Derives the book's cover palette once, when the book is to be coloured by its cover. */
    fun ensureCoverColours(book: Audiobook) {
        val themes = bookThemesState.value
        if (themes.choice(book.id, appearance.value).colours != BookColours.COVER || book.id in themes.covers || book.coverUrl.isBlank()) return
        if (!coverLoads.add(book.id)) return
        viewModelScope.launch {
            try {
                val cover = withContext(Dispatchers.Default) { coverPixels(getApplication(), book.coverUrl)?.let(CoverColours::theme) } ?: return@launch
                val next = bookThemesState.value.withCover(book.id, cover)
                bookThemeStore.save(next)
                bookThemesState.value = next
            } finally { coverLoads.remove(book.id) }
        }
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
    /** Plays from a bookmark's listening place, stored or mapped from where it was read. */
    fun jumpBookmark(bookId: String, audio: AudioCursor) = viewModelScope.launch {
        val state = playback.value
        val excluded = "This bookmark is in a file that isn't chosen for this recording. Include it again in Choose files to go there."
        if (state.source?.id == audio.sourceId) {
            val index = state.source.parts.indexOfFirst { it.id == audio.partId }
            if (index < 0) messages.emit(excluded) else graph.playback.service?.part(index, audio.positionMs)
        } else {
            val entry = graph.library.find(bookId)
            val history = graph.library.position(bookId, audio.sourceId)
            val source = history?.sourceJson?.let { NarrioJson.decodeFromString<AudioSource>(it) } ?: entry?.source()
            if (entry == null || source?.id != audio.sourceId) { messages.emit("This bookmark belongs to a different audio source. Resume that source first."); return@launch }
            // The bookmark's audio is the played recording's, or else an earlier one's: by name when it's remembered
            // (or its phone copy says), otherwise described as it is.
            val saved = entry.book()
            val recording = graph.recordingFor(entry)?.takeIf { entry.source()?.id == source.id }
                ?: (graph.listeningRecordings.recordingOf(bookId, source.id) ?: downloads.value.firstOrNull { it.book.id == bookId && it.source.id == source.id }?.book)
                    ?.copy(id = bookId, title = saved.title, author = saved.author, coverUrl = saved.coverUrl, description = saved.description, sources = listOf(source))
                ?: playedAudio(saved, source)
            // The layout as the listener chose its files now (reading the full release only for files it lacks).
            val playable = try { graph.releaseFiles.apply(recording, source) }
                catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { messages.emit(friendly(error)); return@launch }
            if (playable.parts.none { it.id == audio.partId }) { messages.emit(excluded); return@launch }
            awaitService().load(recording, playable, true, audio.partId, audio.positionMs)
            if (recording.provider != "catalog") graph.listeningRecordings.played(recording, source.id)
            playerOpen.value = true
        }
    }

    /** Narration sync runs only while read along is on screen, bounding battery and data use. */
    fun narrationSyncVisible(visible: Boolean) {
        syncJob?.cancel()
        syncJob = if (!visible) null else viewModelScope.launch {
            graph.narrationSync.run({ syncTarget() }) { bookId, binding ->
                graph.followAlong.mergeNarration(bookId, binding, binding.anchors.filter { it.auto }, playback.value.durationMs)
                graph.mappingRepository.snapshot(bookId, binding.sourceId)?.let { graph.readingSync.refreshPairing(bookId, it) }
            }
        }
    }

    fun allowSyncModelDownload() = graph.narrationSync.allowModelDownload()
    fun setReadAlongSync(value: Boolean) { readAlongSync.value = value; graph.preferences.edit().putBoolean("followAlongAuto", value).apply() }

    private fun syncTarget(): app.narrio.playback.SyncTarget? {
        if (!readAlongSync.value) return null
        val state = playback.value
        val text = bookText.value
        val book = state.book ?: return null
        val source = state.source ?: return null
        val part = state.part ?: return null
        val document = text.document?.takeIf { text.bookId == book.id } ?: return null
        val binding = text.bindings.firstOrNull { it.documentId == document.id && it.sourceId == source.id && it.partId == part.id }
        return app.narrio.playback.SyncTarget(book, source, part, state.positionMs, state.durationMs, document, binding)
    }

    /** Places the playing audio part at a text chapter when its layout is ambiguous. */
    fun selectTextChapter(chapterId: String) = viewModelScope.launch {
        val state = playback.value
        val document = bookText.value.document ?: return@launch
        if (document.timedSourceId.isNotBlank() || FollowAlongTiming.passages(document, chapterId).isEmpty()) return@launch
        val source = state.source ?: return@launch
        val part = state.part ?: return@launch
        if (bookText.value.bindings.any { it.documentId == document.id && it.sourceId == source.id && it.partId == part.id && it.chapterId == chapterId }) return@launch
        try { graph.followAlong.bind(state.book!!.id, TextBinding(document.id, source.id, part.id, chapterId)) }
        catch (cancelled: CancellationException) { throw cancelled }
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
