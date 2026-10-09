package app.narrio.ui

import app.narrio.data.NarrioJson
import app.narrio.data.ShelfEntry
import app.narrio.domain.*
import app.narrio.reader.EditionLayout
import java.text.Normalizer
import kotlinx.serialization.encodeToString

/** Shelf orderings. Recent activity is the default; the choice is kept on this phone. */
enum class ShelfSort(val label: String) {
    RECENT("Recent activity"), TITLE("Title"), AUTHOR("Author"), PROGRESS("Progress"), ADDED("Date added");

    companion object {
        const val PREFERENCE = "shelfSort"
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: RECENT
    }
}

/** One shelf book with everything ordering, searching, and the Finished section read. */
data class ShelfItem(val entry: ShelfEntry, val book: Audiobook, val formats: BookFormats) {
    val finished: Boolean get() = entry.finishedAt > 0
    /** The latest of saving, listening, and moving the shared place in either mode. */
    val activityAt: Long get() = maxOf(entry.savedAt, entry.playedAt, formats.position?.updatedAtMs ?: 0)
    val progress: Float? get() = placeSummary(formats)?.fraction
}

/**
 * Orders the shelf. Ties fall back to recent activity so the order never jumps between recompositions. Progress puts
 * the furthest-along first and books without a known place last.
 */
fun sortShelf(items: List<ShelfItem>, sort: ShelfSort): List<ShelfItem> {
    val recent = compareByDescending<ShelfItem> { it.activityAt }.thenBy { it.entry.bookId }
    val order = when (sort) {
        ShelfSort.RECENT -> recent
        ShelfSort.TITLE -> compareBy<ShelfItem> { titleKey(it.book.title) }.then(recent)
        ShelfSort.AUTHOR -> compareBy<ShelfItem> { folded(it.book.author).ifBlank { "￿" } }.thenBy { titleKey(it.book.title) }.then(recent)
        ShelfSort.PROGRESS -> compareBy<ShelfItem> { it.progress == null }.thenByDescending { it.progress ?: 0f }.then(recent)
        ShelfSort.ADDED -> compareByDescending<ShelfItem> { it.entry.savedAt }.then(recent)
    }
    return items.sortedWith(order)
}

/** "The Hobbit" files under H, as on a bookshop shelf. */
internal fun titleKey(title: String): String = folded(title).replace(Regex("^(the|a|an)\\s+"), "")

/** Lower case without accents or surrounding space, so "Brontë" is found by "bronte". */
private fun folded(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

/** Every word of [query] appears in the title or author, ignoring case and accents. A blank query matches all. */
fun matchesShelfQuery(book: Audiobook, query: String): Boolean {
    val haystack = folded("${book.title} ${book.author}")
    return folded(query).split(Regex("\\s+")).filter { it.isNotEmpty() }.all { it in haystack }
}

/** A book on Discover's Continue row: its shelf entry and the place to resume. */
data class ContinueItem(val entry: ShelfEntry, val place: PlaceSummary)

/**
 * Books in progress, most recently moved first: unfinished, with a place in a mode that's still here. A book the
 * mini-player already holds stays off the row while it would resume listening; reading it remains a separate thread.
 */
fun continueItems(shelf: List<ShelfEntry>, formats: Map<String, BookFormats>, playingBookId: String?, limit: Int = 5): List<ContinueItem> = shelf
    .asSequence()
    .filter { it.finishedAt <= 0 }
    .mapNotNull { entry ->
        val book = formats[entry.bookId] ?: return@mapNotNull null
        val place = placeSummary(book)?.takeIf { book.available(it.mode) } ?: return@mapNotNull null
        if (place.mode == PositionOrigin.LISTENING && entry.bookId == playingBookId) null
        else Triple(ContinueItem(entry, place), book.position!!.updatedAtMs, entry.bookId)
    }
    .sortedWith(compareByDescending<Triple<ContinueItem, Long, String>> { it.second }.thenBy { it.third })
    .take(limit).map { it.first }.toList()

/** The heading names the mode when every book on the row resumes the same way. */
fun continueHeading(items: List<ContinueItem>): String = when {
    items.isNotEmpty() && items.all { it.place.mode == PositionOrigin.READING } -> "Continue reading"
    items.isNotEmpty() && items.all { it.place.mode == PositionOrigin.LISTENING } -> "Continue listening"
    else -> "Continue"
}

/** Listening finishes when the player plays through the final part; stopping early or in an earlier part doesn't count. */
fun listeningFinished(partIndex: Int, partCount: Int, ended: Boolean): Boolean = ended && partCount > 0 && partIndex == partCount - 1

/**
 * Reading finishes when a settled page shows the end of the edition: the page after it would start past the last
 * character. [end] is the first character after the page, null when the page runs to its resource's end.
 */
fun readingFinished(layout: EditionLayout, first: ContentCursor, end: ContentCursor?): Boolean {
    if (layout.total <= 0) return false
    val after = if (end != null) layout.position(end.resource, end.offset) else layout.position(first.resource, layout.length(first.resource))
    return after != null && after >= layout.total
}

/** Recent Discover searches, newest first. Kept only on this phone. */
object RecentSearches {
    const val LIMIT = 6
    const val PREFERENCE = "recentSearches"

    /** Adds [query] at the front, replacing any earlier spelling of it regardless of case. Blank queries are ignored. */
    fun add(recent: List<String>, query: String, limit: Int = LIMIT): List<String> {
        val trimmed = query.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return recent
        return (listOf(trimmed) + recent.filterNot { it.equals(trimmed, ignoreCase = true) }).take(limit)
    }

    fun remove(recent: List<String>, query: String): List<String> = recent.filterNot { it.equals(query, ignoreCase = true) }

    /** Stored newest first; replaying oldest to newest keeps that order while repairing duplicates or an old limit. */
    fun decode(json: String?): List<String> = json?.let { runCatching { NarrioJson.decodeFromString<List<String>>(it) }.getOrNull() }
        .orEmpty().asReversed().fold(emptyList()) { recent, query -> add(recent, query) }

    fun encode(recent: List<String>): String = NarrioJson.encodeToString(recent)
}
