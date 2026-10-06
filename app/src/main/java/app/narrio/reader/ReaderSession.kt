package app.narrio.reader

import app.narrio.domain.ContentCursor
import app.narrio.domain.MappingConfidence
import app.narrio.domain.PositionOrigin
import app.narrio.domain.PositionUpdate
import app.narrio.domain.SharedPositionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the reader chrome shows about the current page. */
data class ReaderLocation(
    val chapter: String? = null,
    val pageLabel: String? = null,
    val lastPageLabel: String? = null,
    val progression: Double = 0.0,
    val minutesLeftInChapter: Int? = null,
    val paceMeasured: Boolean = false,
)

/** Reading speed persistence; kept on this phone. */
interface PaceStore {
    fun read(): ReadingPace
    fun write(pace: ReadingPace)
}

/**
 * Connects a [ReaderController] to the book's shared position. Opening never commits; the reader's own navigation
 * commits through [SharedPositionStore] after a dwell or the second page turn ([ReadingActivity]); while audio of
 * this book plays, audio owns the position and reading commits nothing.
 */
class ReaderSession(
    val controller: ReaderController,
    private val positions: SharedPositionStore,
    audioPlaying: Flow<Boolean>,
    private val paceStore: PaceStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val audioFor: suspend (ContentCursor) -> app.narrio.domain.MappedAudio? = { null },
    private val onCommitted: (ContentCursor) -> Unit = {},
) {
    val book: ReaderBook get() = controller.book
    private val activity = ReadingActivity<ContentCursor>()
    private val pace = MutableStateFlow(paceStore.read())
    private var observedSequence = 0L
    private var tickJob: Job? = null
    private var lastMove: Pair<ContentCursor, Long>? = null
    var navigationVersion: Long = 0
        private set

    val location: StateFlow<ReaderLocation> = combine(controller.visible, book.layout, book.contents, book.pages, pace) { visible, layout, contents, pages, pace ->
        locate(visible?.first ?: controller.cursor.value, layout, contents, pages, pace)
    }.stateIn(scope, SharingStarted.Eagerly, ReaderLocation())

    init {
        scope.launch { audioPlaying.distinctUntilChanged().collect { activity.audio(it) } }
        scope.launch { controller.events.filterIsInstance<ReaderEvent.Moved>().collect { moved(it) } }
    }

    /** The place to open at: the shared position when it's in this edition. Never commits. */
    suspend fun restore(): ContentCursor? {
        val shared = positions.current(book.bookId)
        val cursor = shared?.text?.takeIf { it.editionId == book.editionId && book.linkFor(it.resource, it.offset) != null }
        restoreAt(cursor)
        return cursor
    }

    /** Mode starts and Undo are restoration, never reading activity. */
    suspend fun restoreAt(cursor: ContentCursor?) {
        tickJob?.cancel()
        observedSequence = positions.current(book.bookId)?.sequence ?: 0L
        lastMove = null
        cursor?.let(activity::restored)
        controller.restore(cursor)
    }
    suspend fun undo(cursor: ContentCursor) {
        restoreAt(cursor)
        controller.goTo(cursor)
    }

    private fun moved(event: ReaderEvent.Moved) {
        navigationVersion++
        val now = clock()
        val previous = lastMove
        lastMove = event.cursor to now
        val layout = book.layout.value
        if (!event.jump && previous != null && layout != null) {
            val distance = layout.distance(previous.first.resource, previous.first.offset, event.cursor.resource, event.cursor.offset)
            if (distance != null && distance > 0) pace.value = pace.value.sample(distance, now - previous.second).also(paceStore::write)
        }
        activity.moved(event.cursor, now)?.let(::commit)
        tickJob?.cancel()
        activity.dwellRemaining(now)?.let { wait ->
            tickJob = scope.launch { delay(wait); activity.tick(clock())?.let(::commit) }
        }
    }

    /**
     * An explicit "listen from this page" (starting read along from the reader) makes [cursor] the shared place
     * right away instead of waiting for the reading dwell. Returns false when audio owns the position or the
     * shared place moved meanwhile.
     */
    suspend fun commitNow(cursor: ContentCursor): Boolean {
        tickJob?.cancel()
        observedSequence = positions.current(book.bookId)?.sequence ?: observedSequence
        commit(cursor).join()
        val shared = positions.current(book.bookId)
        return shared?.origin == PositionOrigin.READING && shared.text?.resource == cursor.resource && shared.text.offset == cursor.offset
    }

    private fun commit(cursor: ContentCursor): Job = run {
        val expected = observedSequence
        scope.launch {
            val withLocator = cursor.copy(
                progression = book.layout.value?.progression(cursor.resource, cursor.offset) ?: cursor.progression,
                locatorJson = book.locator(cursor)?.toJSON()?.toString().orEmpty(),
            )
            val mapped = audioFor(withLocator)
            val committed = positions.commit(PositionUpdate(book.bookId, PositionOrigin.READING, text = withLocator, audio = mapped?.audio,
                textConfidence = MappingConfidence.EXACT, audioConfidence = mapped?.confidence ?: MappingConfidence.UNMAPPED, basedOnSequence = expected))
            if (committed != null) onCommitted(withLocator)
            // A stale write means listening moved the position meanwhile; the next reading activity builds on that.
            observedSequence = committed?.sequence ?: positions.current(book.bookId)?.sequence ?: observedSequence
        }
    }

    /** "p. 118" when the edition has a page list, otherwise a percentage; null until the edition is measured. */
    fun label(place: BookPlace): String? {
        val pages = book.pages.value
        // A printed edition has no page before its first page mark; don't mix percentages into its page numbers.
        if (pages.isNotEmpty()) return pages.lastOrNull { (book.compare(it.place, place) ?: 1) <= 0 }?.let { "p. ${it.label}" }
        val layout = book.layout.value ?: return null
        return "${(layout.progression(place.resource, place.offset) * 100).toInt()}%"
    }

    /** Fraction of the edition at [place], for the scrubber's chapter marks. */
    fun fraction(place: BookPlace): Double? = book.layout.value?.progression(place.resource, place.offset)

    /** The place at [fraction] of the edition. */
    fun placeAt(fraction: Double): BookPlace? = book.layout.value?.place(fraction)?.let { BookPlace(it.first, it.second) }

    private fun locate(cursor: ContentCursor?, layout: EditionLayout?, contents: List<ContentsEntry>, pages: List<PageMark>, pace: ReadingPace): ReaderLocation {
        cursor ?: return ReaderLocation(paceMeasured = pace.measured)
        val here = BookPlace(cursor.resource, cursor.offset)
        fun atOrBefore(place: BookPlace?) = place != null && (book.compare(place, here) ?: 1) <= 0
        val chapterIndex = contents.indexOfLast { atOrBefore(it.place) }
        val chapter = contents.getOrNull(chapterIndex)
        val next = contents.drop(chapterIndex + 1).firstOrNull { it.place != null && (book.compare(it.place, here) ?: 0) > 0 }?.place
        val remaining = layout?.let { geometry ->
            if (next != null) geometry.distance(cursor.resource, cursor.offset, next.resource, next.offset)
            else geometry.position(cursor.resource, cursor.offset)?.let { geometry.total - it }
        }
        return ReaderLocation(
            chapter = chapter?.title,
            pageLabel = pages.lastOrNull { atOrBefore(it.place) }?.label,
            lastPageLabel = pages.lastOrNull()?.label,
            progression = layout?.progression(cursor.resource, cursor.offset) ?: 0.0,
            minutesLeftInChapter = remaining?.let(pace::minutesFor),
            paceMeasured = pace.measured,
        )
    }
}
