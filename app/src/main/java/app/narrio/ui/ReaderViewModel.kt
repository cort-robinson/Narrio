package app.narrio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.narrio.NarrioApplication
import app.narrio.data.ProviderException
import app.narrio.reader.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

sealed interface ReaderState {
    data object Opening : ReaderState
    data object Closed : ReaderState
    data class Failed(val message: String) : ReaderState
    data class Ready(val session: ReaderSession) : ReaderState
}

/** Opens one book's active edition for reading and keeps the reader's preferences and pace on this phone. */
class ReaderViewModel(application: Application, val bookId: String) : AndroidViewModel(application) {
    private val graph = (application as NarrioApplication).graph
    private val _state = MutableStateFlow<ReaderState>(ReaderState.Opening)
    val state = _state.asStateFlow()
    private val _settings = MutableStateFlow(ReaderSettingsCodec.decode(graph.preferences.getString(SETTINGS, null)))
    val settings = _settings.asStateFlow()
    private var book: ReaderBook? = null
    private var session: ReaderSession? = null
    private var sessionScope: CoroutineScope? = null
    /** The reader's place when the screen last closed, and when; reused if nothing moved the shared position since. */
    private var released: Pair<app.narrio.domain.ContentCursor, Long>? = null

    private val paceStore = object : PaceStore {
        override fun read() = ReadingPace(graph.preferences.getFloat(PACE_CPM, ReadingPace.DEFAULT_CPM.toFloat()).toDouble(), graph.preferences.getInt(PACE_SAMPLES, 0))
        override fun write(pace: ReadingPace) { graph.preferences.edit().putFloat(PACE_CPM, pace.charsPerMinute.toFloat()).putInt(PACE_SAMPLES, pace.samples).apply() }
    }

    /** Opens the edition unless it's already open; the reader screen calls this whenever it appears. */
    fun ensureOpen() { if (_state.value == ReaderState.Closed) open() }

    init { open() }

    fun open() {
        _state.value = ReaderState.Opening
        viewModelScope.launch {
            try {
                val editions = graph.editionFiles.editions(bookId)
                val edition = editions.firstOrNull { it.active } ?: editions.firstOrNull()
                    ?: throw ProviderException("This book has no ebook to read yet. Add its text from Follow along.")
                val file = graph.editionFiles.original(bookId, edition.id) ?: throw ProviderException("This book's text is missing. Add it again from Follow along.")
                val opened = ReaderBook.open(getApplication(), bookId, edition.id, file, edition.format, edition.title, edition.author, File(getApplication<Application>().cacheDir, "reader"))
                book = opened
                val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
                sessionScope = scope
                val controller = ReaderController(opened, scope)
                val audioPlaying = graph.playback.state.map { it.playing && it.book?.id == bookId }
                val session = ReaderSession(controller, graph.sharedPositions, audioPlaying, paceStore, scope)
                val shared = graph.sharedPositions.current(bookId)
                session.restore()
                released?.let { (cursor, at) -> if ((shared?.updatedAtMs ?: 0) <= at && cursor.editionId == edition.id) controller.restore(cursor) }
                this@ReaderViewModel.session = session
                scope.launch { runCatching { opened.prepare() } }
                _state.value = ReaderState.Ready(session)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _state.value = ReaderState.Failed(error.message ?: "This book couldn't be opened.") }
        }
    }

    fun update(change: ReaderSettings.() -> ReaderSettings) {
        val next = change(_settings.value).normalized()
        _settings.value = next
        graph.preferences.edit().putString(SETTINGS, ReaderSettingsCodec.encode(next)).apply()
    }

    /** Closes the publication when the reader leaves the screen, keeping the reader's place for a quick return. */
    fun release() {
        session?.controller?.cursor?.value?.let { released = it to System.currentTimeMillis() }
        session = null
        sessionScope?.cancel()
        sessionScope = null
        book?.publication?.close()
        book = null
        _state.value = ReaderState.Closed
    }

    override fun onCleared() { book?.publication?.close() }

    companion object {
        private const val SETTINGS = "readerSettings"
        private const val PACE_CPM = "readerPaceCpm"
        private const val PACE_SAMPLES = "readerPaceSamples"

        fun factory(bookId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                ReaderViewModel(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!, bookId) as T
        }
    }
}
