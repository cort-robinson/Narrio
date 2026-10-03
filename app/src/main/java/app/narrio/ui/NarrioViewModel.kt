package app.narrio.ui

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.narrio.*
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.playback.ListeningService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CatalogState(val books: List<Audiobook> = emptyList(), val loading: Boolean = true, val error: String? = null, val notice: String? = null)
data class SelectionState(val book: Audiobook? = null, val loading: Boolean = false, val error: String? = null, val metadataLoading: Boolean = false)

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
    val sourceScope = MutableStateFlow(if (connected.value) "Cached" else "Public")
    val downloads = graph.offline.books
    val wifiOnly = MutableStateFlow(graph.preferences.getBoolean("downloadWifi", true))
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val shelf = graph.library.observeShelf().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playback = graph.playback.state
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var catalogMetadataJob: Job? = null
    private var detailMetadataJob: Job? = null
    private var metadataRequest = 0
    private var controller: MediaController? = null
    private val controllerFuture = MediaController.Builder(application, SessionToken(application, ComponentName(application, ListeningService::class.java))).buildAsync()

    init {
        controllerFuture.addListener({ runCatching { controller = controllerFuture.get() } }, ContextCompat.getMainExecutor(application))
        search()
    }

    fun search(value: String = query.value, cat: String = category.value, scope: String = sourceScope.value) {
        query.value = value; category.value = cat; sourceScope.value = scope
        searchJob?.cancel()
        catalogMetadataJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(if (value.isBlank()) 0 else 350)
            catalog.value = CatalogState(emptyList(), true, notice = if (scope == "Cached") "Finding releases and checking TorBox's cache…" else "Finding recordings…")
            try {
                if (scope == "TorBox" || scope == "Public") {
                    val books = if (scope == "TorBox") graph.torbox.library(value) else if (value.isBlank() && cat == "All") curatedBooks() else graph.catalog.search(value, cat)
                    catalog.value = CatalogState(books, false)
                } else searchSources(value, scope == "Cached")
                enrichCatalog()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { catalog.value = CatalogState(emptyList(), false, friendly(error)) }
        }
    }

    private fun enrichCatalog() {
        catalogMetadataJob?.cancel()
        catalogMetadataJob = viewModelScope.launch {
            // Publish playable results first, then enrich in order with bounded provider requests.
            for (book in catalog.value.books.filter { it.provider != "archive" }) {
                val saved = graph.library.find(book.id)?.book()
                val base = if (saved != null && saved.metadataUpdatedAtMs > book.metadataUpdatedAtMs) book.withMetadataFrom(saved) else book
                val enriched = graph.metadata.enrich(base)
                if (enriched != book) {
                    catalog.update { state -> state.copy(books = state.books.map { if (it.id == book.id) it.withMetadataFrom(enriched) else it }) }
                    graph.library.updateBookDetails(enriched)
                }
            }
        }
    }

    private suspend fun searchSources(value: String, cachedOnly: Boolean) = coroutineScope {
        val failures = mutableListOf<String>()
        suspend fun read(block: suspend () -> List<Audiobook>): List<Audiobook> = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failures += friendly(error); emptyList() }
        val account = async { if (connected.value) read { graph.torbox.library(value) } else emptyList() }
        val archive = async { read { if (value.isBlank()) curatedBooks() else graph.catalog.search(value) } }
        var books = read { graph.indexedCatalog.search(value) }
        if (connected.value && books.isNotEmpty()) {
            val original = books
            val checked = read { graph.torbox.checkCached(original) }
            books = checked.ifEmpty { original }
        }
        books = account.await() + books
        fun publish(loading: Boolean) {
            val previous = catalog.value.books.associateBy { it.id }
            val shown = books.distinctBy { it.id }.filter { !cachedOnly || it.cacheState == "cached" }.sortedBy { it.cacheState != "cached" }
                .map { book -> previous[book.id]?.takeIf { it.metadataUpdatedAtMs > book.metadataUpdatedAtMs }?.let { book.withMetadataFrom(it) } ?: book }
            catalog.value = CatalogState(shown, loading, failures.distinct().joinToString(" ").takeIf { it.isNotBlank() }, if (loading) "Checking audiobook files and cached availability…" else null)
        }
        publish(true)
        enrichCatalog()
        val publicBooks = archive.await()
        if (!connected.value) books += publicBooks
        else publicBooks.chunked(6).forEach { batch ->
            val full = batch.map { book -> async { read { listOf(if (book.detailsLoaded) book else graph.catalog.recording(book.id)) } } }.awaitAll().flatten()
            books += read { graph.torbox.checkCached(full) }
            publish(true)
        }
        publish(false)
    }

    private suspend fun curatedBooks(): List<Audiobook> = coroutineScope {
        val results = curated.map { book -> async { runCatching { graph.catalog.recording(book.id) }.getOrElse { book } } }.awaitAll()
        if (results.none { it.detailsLoaded }) throw ProviderException("Your connection to the audiobook catalog is unavailable. Retry to load recordings, or listen from your saved shelf.")
        results
    }

    fun open(book: Audiobook) {
        playerOpen.value = false
        preparation.value = null
        preferredFormat.value = ""
        detailJob?.cancel()
        detailMetadataJob?.cancel()
        selection.value = SelectionState(book, !book.detailsLoaded)
        detailJob = viewModelScope.launch {
            try {
                var full = if (book.detailsLoaded) book else graph.catalog.recording(book.id)
                if (!connected.value) full = full.copy(cacheState = "unchecked", cachedFormats = emptyList())
                selection.value = SelectionState(full)
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
        if (book.provider == "archive") return
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
    fun back() { if (playerOpen.value) playerOpen.value = false else { detailJob?.cancel(); detailMetadataJob?.cancel(); selection.value = SelectionState() } }
    fun navigate(index: Int) { detailJob?.cancel(); detailMetadataJob?.cancel(); destination.value = index; selection.value = SelectionState(); playerOpen.value = false }
    fun save(book: Audiobook) = viewModelScope.launch { graph.library.save(book); messages.emit("Saved to your shelf") }
    fun remove(book: Audiobook) = viewModelScope.launch {
        if (playback.value.book?.id == book.id) graph.playback.service?.forget()
        downloads.value.filter { it.book.id == book.id }.forEach { graph.offline.remove(it.source) }
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
            connected.value = true; search(scope = "Cached"); messages.emit("TorBox connected. Search now prioritizes audio ready to stream.")
        } catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }
    fun disconnect() {
        graph.playback.service?.disconnect(); graph.offline.disconnect(); graph.credentials.clear(); connected.value = false
        search(scope = "Public")
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
