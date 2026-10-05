package app.narrio.ui

import app.narrio.AppGraph
import app.narrio.data.BookmarkEntry
import app.narrio.data.NarrioJson
import app.narrio.domain.AudioCursor
import app.narrio.domain.AudioSource
import app.narrio.domain.formatTime
import app.narrio.domain.resumeIndex
import app.narrio.domain.ContentCursor
import app.narrio.playback.BookmarkMapping
import app.narrio.playback.BookmarkPlace
import app.narrio.playback.inReadingOrder
import app.narrio.playback.newBookmark
import app.narrio.reader.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.util.UUID

/** A bookmark as the reader lists it: both places, a line of text from its page, and whether it's in this edition. */
data class ReaderBookmark(val place: BookmarkPlace, val snippet: String, val inEdition: Boolean, val listening: String? = null) {
    val cursor: ContentCursor? get() = place.text?.takeIf { inEdition }
}

/**
 * Highlights, notes, bookmarks, and search for one opened edition. Everything is stored through the library's
 * annotation and bookmark rows; this only orders, maps, and edits them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderMarks(private val graph: AppGraph, val book: ReaderBook, private val scope: CoroutineScope) {
    private val library get() = graph.library
    val search = BookSearch(book, scope)
    private val order: Map<String, Int> = book.readingOrder.map(book::resourceName).distinct().withIndex().associate { it.value to it.index }

    /** Edition order: reading-order resource, then offset. Null for a place outside this edition. */
    fun position(cursor: ContentCursor): Long? =
        if (cursor.editionId != book.editionId) null else order[cursor.resource]?.let { it.toLong() shl 32 or cursor.offset.toLong().coerceAtLeast(0) }

    /** This edition's highlights and notes in book order. */
    val highlights: StateFlow<List<Highlight>> = library.annotations(book.bookId, book.editionId)
        .map { entries -> entries.mapNotNull(Highlight::of).sortedWith(compareBy({ position(it.range.start) ?: Long.MAX_VALUE }, { it.createdAtMs })) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every bookmark of the book, from either mode, in book order. */
    val bookmarks: StateFlow<List<ReaderBookmark>> = library.bookmarks(book.bookId).mapLatest { entries ->
        val places = withContext(Dispatchers.IO) { BookmarkMapping(graph, book.bookId).resolve(entries) }
        val sources = HashMap<String, AudioSource?>()
        places.inReadingOrder(::position).map { place ->
            val inEdition = place.text?.editionId == book.editionId
            val snippet = place.text?.takeIf { inEdition }?.let { runCatching { book.snippet(it) }.getOrNull() }.orEmpty()
            val listening = place.audio?.let { audio -> bookmarkTime(audio, sources.getOrPut(audio.sourceId) { source(audio.sourceId) }) }
            ReaderBookmark(place, snippet.ifBlank { place.entry.label }, inEdition, listening)
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private suspend fun source(id: String): AudioSource? = withContext(Dispatchers.IO) {
        library.position(book.bookId, id)?.let { runCatching { NarrioJson.decodeFromString<AudioSource>(it.sourceJson) }.getOrNull() }
            ?: library.find(book.bookId)?.let { entry -> entry.source()?.takeIf { it.id == id } ?: entry.book().sources.firstOrNull { it.id == id } }
    }

    /** The colour new highlights use: the one chosen last. */
    var lastColor: HighlightColor
        get() = HighlightColor.of(graph.preferences.getString(LAST_COLOR, null).orEmpty())
        set(value) { graph.preferences.edit().putString(LAST_COLOR, value.key).apply() }

    /** Saves a highlight over [range]; returns it, or null when the range holds no text. */
    suspend fun highlight(range: CursorRange, color: HighlightColor = lastColor, note: String = ""): Highlight? {
        if (range.start.editionId != book.editionId || (position(range.end) ?: 0) <= (position(range.start) ?: 0)) return null
        val text = book.text(range).ifBlank { return null }
        val now = System.currentTimeMillis()
        val highlight = Highlight(UUID.randomUUID().toString(), book.bookId, range, color, note.trim(), text, now, now)
        library.putAnnotation(highlight.entry())
        lastColor = color
        return highlight
    }

    suspend fun update(highlight: Highlight, color: HighlightColor = highlight.color, note: String = highlight.note) {
        if (color == highlight.color && note.trim() == highlight.note) return
        library.putAnnotation(highlight.copy(color = color, note = note.trim(), updatedAtMs = System.currentTimeMillis()).entry())
        lastColor = color
    }

    suspend fun remove(highlight: Highlight) = library.deleteAnnotation(highlight.id)
    suspend fun restore(highlight: Highlight) = library.putAnnotation(highlight.entry())

    /** Bookmarks shown on [visible]: made on this page while reading, or mapped onto it from listening. */
    fun onPage(visible: VisibleRange?, marks: List<ReaderBookmark> = bookmarks.value): List<ReaderBookmark> =
        if (visible == null) emptyList() else marks.filter { mark -> mark.cursor?.let(visible::contains) == true }

    /** Bookmarks the page at [first]; the listening place is kept when narration confirms it exactly. */
    suspend fun addBookmark(first: ContentCursor) {
        val cursor = book.cursor(first.resource, first.offset, book.locator(first)?.toJSON()?.toString().orEmpty())
        val mapped = withContext(Dispatchers.IO) { runCatching { BookmarkMapping(graph, book.bookId).audioFor(cursor) }.getOrNull() }
        library.bookmark(newBookmark(book.bookId, book.snippet(cursor, 80).ifBlank { "Reading bookmark" }, text = cursor, mappedAudio = mapped))
    }

    suspend fun removeBookmarks(marks: List<ReaderBookmark>) = marks.forEach { library.deleteBookmark(it.place.id) }
    suspend fun restoreBookmarks(entries: List<BookmarkEntry>) = entries.forEach { library.bookmark(it) }

    private companion object { const val LAST_COLOR = "readerHighlightColor" }
}

/** A bookmark's listening place: "Part 2 · 14:05" in a multipart recording, otherwise the time. */
fun bookmarkTime(audio: AudioCursor, source: AudioSource?): String {
    val parts = source?.parts.orEmpty()
    return if (parts.size > 1) "Part ${resumeIndex(parts, audio.partId) + 1} · ${formatTime(audio.positionMs)}" else formatTime(audio.positionMs)
}
