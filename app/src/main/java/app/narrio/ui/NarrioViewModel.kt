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

data class CatalogState(val books: List<Audiobook> = emptyList(), val loading: Boolean = true, val error: String? = null)
data class SelectionState(val book: Audiobook? = null, val loading: Boolean = false, val error: String? = null)

class NarrioViewModel(application: Application) : AndroidViewModel(application) {
    val graph = (application as NarrioApplication).graph
    val catalog = MutableStateFlow(CatalogState())
    val selection = MutableStateFlow(SelectionState())
    val query = MutableStateFlow("")
    val category = MutableStateFlow("All")
    val destination = MutableStateFlow(0)
    val playerOpen = MutableStateFlow(false)
    val preparation = MutableStateFlow<Preparation?>(null)
    val busy = MutableStateFlow(false)
    val connected = MutableStateFlow(graph.credentials.read() != null)
    val theme = MutableStateFlow(graph.preferences.getString("theme", "Night") ?: "Night")
    val sourceScope = MutableStateFlow("Catalog")
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val shelf = graph.library.observeShelf().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playback = graph.playback.state
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var controller: MediaController? = null
    private val controllerFuture = MediaController.Builder(application, SessionToken(application, ComponentName(application, ListeningService::class.java))).buildAsync()

    init {
        controllerFuture.addListener({ runCatching { controller = controllerFuture.get() } }, ContextCompat.getMainExecutor(application))
        search()
    }

    fun search(value: String = query.value, cat: String = category.value, scope: String = sourceScope.value) {
        query.value = value; category.value = cat; sourceScope.value = scope
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(if (value.isBlank()) 0 else 350)
            catalog.value = CatalogState(catalog.value.books, true)
            try {
                val books = if (scope == "TorBox") graph.torbox.library(value) else if (value.isBlank() && cat == "All") curatedBooks() else graph.catalog.search(value, cat)
                catalog.value = CatalogState(books, false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { catalog.value = CatalogState(emptyList(), false, friendly(error)) }
        }
    }

    private suspend fun curatedBooks(): List<Audiobook> = coroutineScope {
        val results = curated.map { book -> async { runCatching { graph.catalog.recording(book.id) }.getOrElse { book } } }.awaitAll()
        if (results.none { it.detailsLoaded }) throw ProviderException("Your connection to the audiobook catalog is unavailable. Retry to load recordings, or listen from your saved shelf.")
        results
    }

    fun open(book: Audiobook) {
        playerOpen.value = false
        preparation.value = null
        detailJob?.cancel()
        selection.value = SelectionState(book, !book.detailsLoaded)
        detailJob = viewModelScope.launch {
            try {
                val full = if (book.detailsLoaded) book else graph.catalog.recording(book.id)
                selection.value = SelectionState(full)
                val saved = graph.library.find(full.id)
                if (saved?.state == "preparing" || saved?.state == "ready") preparation.value = Preparation(saved.preparationId, saved.state == "ready", 0f, if (saved.state == "ready") "Ready to listen" else "Preparing in TorBox")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { selection.value = SelectionState(book, false, friendly(error)) }
        }
    }

    fun back() { if (playerOpen.value) playerOpen.value = false else selection.value = SelectionState() }
    fun navigate(index: Int) { destination.value = index; selection.value = SelectionState(); playerOpen.value = false }
    fun save(book: Audiobook) = viewModelScope.launch { graph.library.save(book); messages.emit("Saved to your shelf") }
    fun remove(book: Audiobook) = viewModelScope.launch {
        if (playback.value.book?.id == book.id) graph.playback.service?.forget()
        graph.library.remove(book.id); messages.emit("Removed from your shelf")
    }
    fun resume(entry: ShelfEntry) { entry.source()?.let { start(entry.book(), it, it.delivery) } ?: open(entry.book()) }

    fun start(book: Audiobook, source: AudioSource, delivery: String) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        try {
            val service = awaitService()
            if (delivery == "torbox" && source.delivery != "torbox") {
                if (!connected.value) { navigate(2); messages.emit("Connect TorBox to stream through your account."); return@launch }
                graph.library.save(book)
                val saved = graph.library.find(book.id)
                val prep = if (saved?.state == "preparing" || saved?.state == "ready") graph.torbox.refresh(book, saved.preparationId) else graph.torbox.prepare(book)
                preparation.value = prep
                graph.library.preparing(book.id, prep.torrentId)
                if (!prep.ready) { messages.emit("Source saved. Return to your shelf to check preparation."); return@launch }
                val options = graph.torbox.sources(book, prep.torrentId)
                val resolved = options.firstOrNull { it.format == source.format } ?: throw ProviderException("The chosen audio format is missing from this TorBox source. Select another format.")
                service.load(book, resolved)
            } else service.load(book, source)
            playerOpen.value = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }

    fun refreshPreparation(book: Audiobook) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        try {
            val saved = graph.library.find(book.id) ?: return@launch
            preparation.value = graph.torbox.refresh(book, saved.preparationId)
            if (preparation.value?.ready == true) graph.library.state(book.id, "ready")
        } catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }

    fun connect(key: String) = viewModelScope.launch {
        if (key.isBlank() || busy.value) return@launch
        busy.value = true
        try {
            graph.torbox.connect(key.trim()); graph.credentials.write(key.trim())
            connected.value = true; messages.emit("TorBox connected. Your key is protected on this device.")
        } catch (error: Exception) { messages.emit(friendly(error)) }
        finally { busy.value = false }
    }
    fun disconnect() {
        graph.playback.service?.disconnect(); graph.credentials.clear(); connected.value = false
        if (sourceScope.value == "TorBox") search(scope = "Catalog")
        messages.tryEmit("TorBox disconnected; its credential has been removed.")
    }
    fun setTheme(value: String) { theme.value = value; graph.preferences.edit().putString("theme", value).apply() }
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
