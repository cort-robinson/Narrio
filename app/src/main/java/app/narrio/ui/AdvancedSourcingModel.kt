package app.narrio.ui

import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.narrio.data.*
import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Pasting a link: [working] while it's checked; [error] in plain words when it can't be used. */
data class LinkState(val working: Boolean = false, val error: String? = null)

/** The listener's TorBox downloads with audio, newest first. [attaching] is the item being added to the book. */
data class TorBoxLibraryState(
    val loading: Boolean = false, val loaded: Boolean = false, val items: List<TorBoxItem> = emptyList(),
    val error: String? = null, val attaching: TorBoxItem? = null,
)

/**
 * Choosing files in one release. [files] is every audio file of the layout in the order shown; [chosen] holds the
 * included files' keys ([FileChoices.fileKey]). [bookFiles] is "Only this book's files" as automatic matching would
 * pick them.
 */
data class ReleaseFilesState(
    val book: Audiobook? = null, val source: AudioSource? = null, val loading: Boolean = false, val error: String? = null,
    val files: List<AudioPart> = emptyList(), val chosen: Set<String> = emptySet(), val bookFiles: List<String> = emptyList(),
    val saved: Boolean = false, val saving: Boolean = false,
) {
    /** Included files' keys in listening order. */
    val selection: List<String> get() = files.map(FileChoices::fileKey).filter { it in chosen }
}

/** Adding audio files from the phone. */
data class LocalImportState(val working: Boolean = false, val error: String? = null)

/** How a different recording starts when the listener switches to it. */
enum class StartChoice { NEAR, RESUME, BEGINNING }

/**
 * The ways a different recording can start: [near] the old place (approximate; null when it can't be placed), [resume]
 * this recording's own saved place (null when it has none), or always the beginning.
 */
data class SwitchStart(val near: CarryOver?, val resume: AudioCursor?) {
    /** Near when it can be placed, else this recording's own place, else the beginning. */
    val default: StartChoice get() = if (near != null) StartChoice.NEAR else if (resume != null) StartChoice.RESUME else StartChoice.BEGINNING
}

/**
 * Where a recording starts. An [approximate] place gives way to an exact place mapped from the book's synced ebook;
 * otherwise the place is used as given.
 */
data class StartAt(val cursor: AudioCursor, val approximate: Boolean)

/**
 * Fix-it-yourself sourcing for listeners who know how releases work. Every action ends where ordinary listening does:
 * a release the listener brings in joins the book's results as the recording chooser's pick ([NarrioViewModel.addRecording]),
 * and Listen, Get it ready, and Download use the existing adoption, preparation, download, and position rules.
 *
 * Each slow operation belongs to the book it started on. Opening another book (or removing this one) cancels it, and
 * a result that arrives late for a book no longer open is discarded rather than attached to whatever is open now.
 */
class AdvancedSourcing(private val vm: NarrioViewModel) {
    private val graph = vm.graph
    private val scope get() = vm.viewModelScope

    val hidden: StateFlow<Map<String, List<HiddenRelease>>> = graph.hiddenReleases.hidden
    val link = MutableStateFlow(LinkState())
    val library = MutableStateFlow(TorBoxLibraryState())
    val files = MutableStateFlow(ReleaseFilesState())
    val local = MutableStateFlow(LocalImportState())
    /** The listener's choice for the next switch to another recording; null leaves playback's usual rule. */
    val startChoice = MutableStateFlow<StartChoice?>(null)
    private val jobs = mutableMapOf<String, Pair<String, Job>>()
    /** The account's download list isn't tied to a book, so leaving a book doesn't cancel it. */
    private var libraryList: Job? = null

    init {
        // Work for a book that's no longer open is cancelled; its results would otherwise land on another book.
        scope.launch { vm.selection.map { it.book?.id }.distinctUntilChanged().collect { open -> cancelExcept(open) } }
    }

    /** The book a choice attaches to: the catalog book whose sources are open, else the page's own book. */
    private fun parent(book: Audiobook): Audiobook = vm.sourceSearch.value.book?.takeIf { it.id == book.id } ?: book.catalogIdentity()

    private fun open(bookId: String) = vm.selection.value.book?.id == bookId

    /** Starts [block] as the one [kind] of work for [bookId], replacing an earlier one. */
    private fun launch(kind: String, bookId: String, block: suspend CoroutineScope.() -> Unit): Job {
        jobs[kind]?.second?.cancel()
        val job = scope.launch(block = block)
        jobs[kind] = bookId to job
        job.invokeOnCompletion { if (jobs[kind]?.second === job) jobs.remove(kind) }
        return job
    }

    private fun cancelExcept(bookId: String?) {
        jobs.entries.filter { it.value.first != bookId }.forEach { it.value.second.cancel() }
        if (link.value.working) link.value = LinkState()
        if (local.value.working) local.value = LocalImportState()
        if (library.value.attaching != null) library.update { it.copy(attaching = null) }
        if (files.value.book?.id != null && files.value.book?.id != bookId) files.value = ReleaseFilesState()
    }

    // Custom search words

    fun savedWords(bookId: String): String? = graph.sourceWords.get(bookId)
    fun variants(book: Audiobook): List<WordsVariant> = SourceWords.variants(parent(book))

    /** Searches every source with [words] and remembers them for this book. */
    fun searchWords(book: Audiobook, words: String) {
        val clean = SourceWords.clean(words) ?: return
        graph.sourceWords.set(book.id, clean)
        vm.findSources(parent(book), force = true, words = clean)
    }

    /** Back to the automatic search; the remembered words stay offered in the field. */
    fun automaticSearch(book: Audiobook) = vm.findSources(parent(book), force = true)

    fun forgetWords(book: Audiobook) { graph.sourceWords.forget(book.id) }

    // Not this book: the view model refilters the open results whenever the hidden list changes.

    fun hide(book: Audiobook, recording: Audiobook, provider: String = "") {
        graph.hiddenReleases.hide(book.id, recording, provider)
        vm.messages.tryEmit("Hidden from this book. Find it under Hidden in Advanced to undo.")
    }

    fun unhide(book: Audiobook, key: String) = graph.hiddenReleases.unhide(book.id, key)

    // Paste a link

    /** [added] receives the release once it's one of the book's results, so the chooser can make it the pick. */
    fun addLink(book: Audiobook, text: String, added: (Audiobook) -> Unit = {}) {
        link.value = LinkState(working = true)
        launch("link", book.id) {
            try {
                val parsed = ReleaseLinks.parse(text)
                val recording = graph.linkedReleases.resolve(parsed, vm.connected.value)
                if (!open(book.id)) return@launch
                attach(book, recording)?.let(added)
                link.value = LinkState()
                vm.messages.emit(if (SourceQuality.ready(recording)) "Release added. It's ready to listen." else "Release added. Get it ready to listen.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (open(book.id)) link.value = LinkState(error = message(error)) }
        }
    }

    fun clearLinkError() { link.update { it.copy(error = null) } }

    // Pick from my TorBox library

    fun loadLibrary(force: Boolean = false) {
        if (!vm.connected.value) { library.value = TorBoxLibraryState(error = "Connect TorBox to choose from your library."); return }
        if (!force && (library.value.loading || library.value.loaded)) return
        library.update { it.copy(loading = true, error = null) }
        libraryList?.cancel()
        libraryList = scope.launch {
            try { library.value = TorBoxLibraryState(loaded = true, items = graph.torboxLibrary.items()) }
            catch (cancelled: CancellationException) { library.update { it.copy(loading = false) }; throw cancelled }
            catch (error: Exception) { library.update { it.copy(loading = false, error = message(error)) } }
        }
    }

    fun chooseFromLibrary(book: Audiobook, item: TorBoxItem, added: (Audiobook) -> Unit = {}) {
        if (library.value.attaching != null) return
        library.update { it.copy(attaching = item, error = null) }
        launch("library", book.id) {
            try {
                if (!item.ready && item.kind != TorBoxKind.TORRENT) throw ProviderException("TorBox is still downloading this. Choose it once it's ready.")
                val recording = graph.torboxLibrary.recording(item)
                if (!open(book.id)) return@launch
                attach(book, recording)?.let(added)
                library.update { it.copy(attaching = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { library.update { it.copy(attaching = null, error = message(error)) } }
        }
    }

    // Choose files in a release

    /** Lists every audio file of [source]'s layout for [book] (the book's recording), with the current choice. */
    fun openFiles(book: Audiobook, source: AudioSource) {
        files.value = ReleaseFilesState(book, source, loading = true)
        launch("files", book.id) {
            try {
                val full = graph.releaseFiles.fullLayout(book, source)
                val saved = graph.releaseFiles.chosen(book.id, book, source)
                val chosen = saved ?: source.parts.map(FileChoices::fileKey)
                files.value = ReleaseFilesState(book, source, files = FileChoices.arrange(full, chosen),
                    chosen = chosen.mapNotNull { FileChoices.locate(full.parts, it) }.map(FileChoices::fileKey).toSet(),
                    bookFiles = FileChoices.bookFiles(parent(book), book, full), saved = saved != null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // The layout itself can still be chosen from when the full release can't be listed.
                val keys = source.parts.map(FileChoices::fileKey)
                files.value = ReleaseFilesState(book, source, files = source.parts, chosen = keys.toSet(), bookFiles = keys,
                    error = "Only the files already found are shown. ${message(error)}")
            }
        }
    }

    fun toggleFile(key: String) = files.update { state ->
        state.copy(chosen = if (key in state.chosen) state.chosen - key else state.chosen + key)
    }

    fun moveFile(key: String, by: Int) = files.update { state ->
        val from = state.files.indexOfFirst { FileChoices.fileKey(it) == key }
        val to = (from + by).coerceIn(0, state.files.lastIndex)
        if (from < 0 || from == to) state else state.copy(files = state.files.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun onlyBookFiles() = files.update { state ->
        state.copy(chosen = state.bookFiles.toSet(), files = FileChoices.arrange(AudioSource("", "", "", state.files), state.bookFiles))
    }
    fun allFiles() = files.update { it.copy(chosen = it.files.map(FileChoices::fileKey).toSet()) }
    fun naturalOrder() = files.update { it.copy(files = FileChoices.natural(it.files)) }

    /** Saves the choice; playback and this recording's phone download switch to it, at the same file when it's chosen. */
    fun saveFiles() {
        val state = files.value
        if (state.selection.isEmpty()) { files.update { it.copy(error = "Choose at least one file to listen to.") }; return }
        save(state, state.selection, "Files saved. Playback and downloads use them.")
    }

    /**
     * Back to the files Narrio found: this book's files from a bundle, else every file, in name order. It is saved as a
     * choice of its own, so files left out earlier come back even where a narrower layout was played.
     */
    fun resetFiles() {
        val state = files.value
        val keys = state.bookFiles.ifEmpty { state.files.map(FileChoices::fileKey) }
        files.update { it.copy(chosen = keys.toSet(), files = FileChoices.arrange(AudioSource("", "", "", it.files), keys)) }
        save(state, keys, "Back to the files Narrio found.")
    }

    private fun save(state: ReleaseFilesState, keys: List<String>, done: String) {
        val book = state.book ?: return
        val source = state.source ?: return
        files.update { it.copy(saving = true, error = null) }
        launch("save-files", book.id) {
            try {
                graph.releaseFiles.save(book.id, book, source, keys)
                files.update { it.copy(saving = false, saved = true) }
                reloadPlaying(book, source)
                vm.reconcileDownloads(book, source)
                vm.messages.emit(done)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { files.update { it.copy(saving = false, error = message(error)) } }
        }
    }

    fun closeFiles() { jobs["files"]?.second?.cancel(); files.value = ReleaseFilesState() }

    /** The playing layout restarts from the release's full file list, so the new choice applies; the place is kept. */
    private suspend fun reloadPlaying(book: Audiobook, source: AudioSource) {
        val state = vm.playback.value
        val playing = state.source ?: return
        val playingBook = state.book?.takeIf { it.id == book.id } ?: return
        if (FileChoices.key(book.id, playingBook, playing) != FileChoices.key(book.id, book, source)) return
        val service = graph.playback.service ?: return
        val full = try { graph.releaseFiles.fullLayout(playingBook, playing) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { playing }
        service.load(playingBook, full, state.playing || state.buffering, state.part?.id, state.positionMs)
    }

    // Start near where you were

    /**
     * The ways [source] (of the recording [book], adopted for its book) can start when the listener switches to it from
     * the recording the book last played: near the old place, at its own saved place, or the beginning. Null when it is
     * that recording.
     */
    suspend fun switchStart(book: Audiobook, source: AudioSource): SwitchStart? {
        val entry = graph.library.find(book.id) ?: return null
        val old = entry.source() ?: return null
        if (old.id == source.id) return null
        val target = try { graph.releaseFiles.apply(book, source) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { source }
        val near = RecordingCarryOver.map(old.parts, entry.partId, entry.positionMs, target.parts, parent(book).durationMs.takeIf { it > 0 } ?: book.durationMs)
        val resume = graph.library.position(book.id, source.id)?.takeIf { history -> history.positionMs > 0 || target.parts.firstOrNull()?.id != history.partId }
            ?.let { AudioCursor(source.id, it.partId, it.positionMs) }
        return SwitchStart(near, resume)
    }

    /**
     * Where another recording should start when the listener switches to it, following [startChoice]: near their place
     * in the recording they were listening to (approximate, mapped onto the layout that actually plays once TorBox has
     * prepared it, and giving way to an exact place from a synced ebook), or its beginning. Null keeps playback's usual
     * rule, which resumes the recording's own place.
     */
    suspend fun switchPlace(book: Audiobook): ((AudioSource) -> StartAt?)? {
        val choice = startChoice.value ?: return null
        if (choice == StartChoice.RESUME) return null
        val entry = graph.library.find(book.id) ?: return null
        val old = entry.source() ?: return null
        val length = parent(book).durationMs.takeIf { it > 0 } ?: book.durationMs
        return { resolved ->
            when {
                resolved.id == old.id -> null
                choice == StartChoice.BEGINNING -> resolved.parts.firstOrNull()?.let { StartAt(AudioCursor(resolved.id, it.id, 0), approximate = false) }
                else -> RecordingCarryOver.map(old.parts, entry.partId, entry.positionMs, resolved.parts, length)
                    ?.let { StartAt(AudioCursor(resolved.id, it.partId, it.positionMs), approximate = true) }
            }
        }
    }

    // Audio files from this phone

    fun addPhoneFiles(book: Audiobook, uris: List<Uri>, added: (Audiobook) -> Unit = {}) {
        if (uris.isEmpty()) return
        importLocal(book, added) { graph.localAudio.fromDocuments(uris) }
    }

    fun addPhoneFolder(book: Audiobook, tree: Uri?, added: (Audiobook) -> Unit = {}) {
        if (tree == null) return
        importLocal(book, added) { graph.localAudio.fromFolder(tree) }
    }

    private fun importLocal(book: Audiobook, added: (Audiobook) -> Unit, read: suspend () -> LocalImport) {
        local.value = LocalImportState(working = true)
        launch("local", book.id) {
            var import: LocalImport? = null
            var kept = false
            try {
                import = read()
                if (import.files.isEmpty()) throw ProviderException("No audio files found. Choose MP3, M4B, M4A, AAC, FLAC, OGG, Opus, or WAV files.")
                if (!open(book.id)) return@launch
                val recording = LocalAudio.recording(parent(book), import.files, import.folder)
                // Access and the complete file list are kept with the book before the recording can be chosen.
                graph.localAudio.commit(book.id, import)
                graph.localManifests.put(book.id, recording.sources.single())
                kept = true
                attach(book, recording)?.let(added)
                local.value = LocalImportState()
                vm.messages.emit("${import.files.size} ${if (import.files.size == 1) "file" else "files"} added. They play from your phone and are never uploaded.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (open(book.id)) local.value = LocalImportState(error = message(error)) }
            finally { if (!kept) import?.let(graph.localAudio::discard) }
        }
    }

    fun clearLocalError() { local.update { it.copy(error = null) } }

    /**
     * Removing a book removes its hidden releases, search words, file choices, phone file lists, and phone file access.
     * Its unfinished work is cancelled first, so a late import can't take access again afterwards.
     */
    suspend fun forget(bookId: String) {
        jobs.values.filter { it.first == bookId }.map { it.second }.forEach { it.cancelAndJoin() }
        graph.hiddenReleases.forget(bookId)
        graph.sourceWords.forget(bookId)
        graph.releaseFiles.forget(bookId)
        graph.localAudio.forget(bookId)
    }

    /** The release joins the open book's results as the chosen one; Listen adopts it like any found recording. */
    private fun attach(book: Audiobook, recording: Audiobook): Audiobook? {
        val described = SourceQuality.describe(recording, parent(book))
        return described.takeIf { vm.addRecording(book.id, it) }
    }

    private fun message(error: Exception) = when (error) {
        is ProviderException, is IllegalArgumentException -> error.message.orEmpty().ifBlank { "That didn't work. Try again." }
        else -> "Couldn't reach TorBox. Check your connection and try again."
    }
}
