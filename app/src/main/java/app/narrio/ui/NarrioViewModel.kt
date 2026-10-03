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

data class CatalogState(val books: List<Audiobook> = emptyList(), val loading: Boolean = true, val error: String? = null, val notice: String? = null)
data class SelectionState(val book: Audiobook? = null, val loading: Boolean = false, val error: String? = null, val metadataLoading: Boolean = false)
data class SourceSearchState(val book: Audiobook? = null, val recordings: List<Audiobook> = emptyList(), val loading: Boolean = false, val searched: Boolean = false, val error: String? = null)
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
)

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
    val shelf = graph.library.observeShelf().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playback = graph.playback.state
    val bookText = MutableStateFlow(BookTextState())
    private var textSearchJob: Job? = null
    private val textJobs = mutableMapOf<String, Job>()
    private var textImportTarget: ListeningState? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var sourceSearchJob: Job? = null
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

    fun findSources(book: Audiobook) {
        sourceSearchJob?.cancel()
        sourceSearch.value = SourceSearchState(book = book, loading = true, searched = true)
        sourceSearchJob = viewModelScope.launch {
            try {
                val results = graph.bookSources.search(book, connected.value)
                sourceSearch.value = SourceSearchState(book, results.recordings, searched = true, error = results.error)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { sourceSearch.value = SourceSearchState(book, searched = true, error = friendly(error)) }
        }
    }

    fun chooseRecording(recording: Audiobook) {
        val book = sourceSearch.value.book ?: return
        if (recording !in sourceSearch.value.recordings) return
        open(SourceQuality.describe(recording, book), keepSources = true)
    }

    fun open(book: Audiobook, keepSources: Boolean = false) {
        playerOpen.value = false
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
                if (full.provider == "catalog") return@launch
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
    fun back() { if (playerOpen.value) playerOpen.value = false else if (sourceSearch.value.book?.provider == "catalog" && selection.value.book?.provider != "catalog") open(sourceSearch.value.book!!, keepSources = true) else { detailJob?.cancel(); detailMetadataJob?.cancel(); sourceSearchJob?.cancel(); selection.value = SelectionState(); sourceSearch.value = SourceSearchState() } }
    fun navigate(index: Int) { detailJob?.cancel(); detailMetadataJob?.cancel(); sourceSearchJob?.cancel(); sourceSearch.value = SourceSearchState(); destination.value = index; selection.value = SelectionState(); playerOpen.value = false }
    fun save(book: Audiobook) = viewModelScope.launch { graph.library.save(book); messages.emit("Saved to your shelf") }
    fun remove(book: Audiobook) = viewModelScope.launch {
        textJobs[book.id]?.cancel()
        if (playback.value.book?.id == book.id) graph.playback.service?.forget()
        downloads.value.filter { it.book.id == book.id }.forEach { graph.offline.remove(it.source) }
        graph.followAlong.remove(book.id)
        graph.library.remove(book.id); messages.emit("Removed from your shelf and phone downloads")
    }
    fun resume(entry: ShelfEntry) { entry.source()?.let { start(entry.book(), it, it.delivery) } ?: open(entry.book()) }

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
            connected.value = true; messages.emit("TorBox connected. Find sources from a book's details to check ready audio.")
        } catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }
    fun disconnect() {
        graph.playback.service?.disconnect(); graph.offline.disconnect(); graph.credentials.clear(); connected.value = false
        sourceSearchJob?.cancel()
        sourceSearch.value = SourceSearchState(book = selection.value.book)
        messages.tryEmit("TorBox disconnected; its credential has been removed.")
    }
    fun updateAppearance(value: AppearanceSettings) {
        val normalized = value.normalized()
        appearanceStore.save(normalized)
        appearanceState.value = normalized
    }
    fun bookmark() = viewModelScope.launch { graph.playback.service?.bookmark(); messages.emit("Moment bookmarked") }
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
        val id = playback.value.book?.id ?: return
        textSearchJob?.cancel()
        textSearchJob = viewModelScope.launch {
            bookText.value = bookText.value.copy(searching = true, searched = true, results = emptyList(), error = null)
            try {
                val results = graph.textDiscovery.search(query)
                if (bookText.value.bookId == id) bookText.value = bookText.value.copy(results = results)
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

    private fun textWork(target: ListeningState, action: suspend (Audiobook, AudioSource, AudioPart) -> BookText): Job? {
        val book = target.book ?: return null
        if (textJobs[book.id]?.isActive == true) return null
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
        bookText.value = bookText.value.copy(error = null)
    }

    fun seekTextLine(binding: TextBinding, index: Int) {
        val state = playback.value
        val document = bookText.value.document ?: return
        if (state.source?.id != binding.sourceId || state.part?.id != binding.partId || document.id != binding.documentId) return
        val time = FollowAlongTiming.timeline(document, binding, state.durationMs)?.starts?.getOrNull(index) ?: return
        if (state.durationMs > 0 && time >= state.durationMs) { messages.tryEmit("That timestamp is beyond this audio part. Check that the timing track matches."); return }
        graph.playback.service?.seek(time)
    }

    fun removeBookText() = viewModelScope.launch {
        val id = playback.value.book?.id ?: return@launch
        textJobs[id]?.cancel()
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
