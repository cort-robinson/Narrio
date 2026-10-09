package app.narrio.ui

import app.narrio.data.ShelfEntry
import app.narrio.domain.*
import app.narrio.reader.EditionLayout
import org.junit.Assert.*
import org.junit.Test

class ShelfOrganizingTest {
    private val parts = AudioSource("s", "Parts", "MP3", List(4) { AudioPart("p$it", "p$it.mp3", "Part ${it + 1}", durationMs = 600_000) })
    private val edition = EbookEdition("e", "b", "Book", "Author", "EPUB", active = true)
    private fun entry(id: String, savedAt: Long = 0, playedAt: Long = 0, finishedAt: Long = 0) =
        ShelfEntry(id, "{}", savedAt = savedAt, playedAt = playedAt, finishedAt = finishedAt)
    private fun listening(id: String, at: Long, part: String = "p1") = BookFormats(id, audio = true, audioSource = parts,
        position = SharedPosition(id, PositionOrigin.LISTENING, audio = AudioCursor("s", part, 0), updatedAtMs = at))
    private fun reading(id: String, at: Long, progression: Double = .5) = BookFormats(id, editions = listOf(edition),
        position = SharedPosition(id, PositionOrigin.READING, text = ContentCursor("e", "ch", 0, progression = progression), updatedAtMs = at))

    @Test fun continueListsInProgressBooksNewestPlaceFirstUpToFive() {
        val shelf = (1..7).map { entry("b$it") } + entry("unstarted")
        val formats = (1..7).associate { "b$it" to listening("b$it", at = it * 10L) } + ("unstarted" to BookFormats("unstarted", audio = true))
        assertEquals(listOf("b7", "b6", "b5", "b4", "b3"), continueItems(shelf, formats, playingBookId = null).map { it.entry.bookId })
    }

    @Test fun continueSkipsFinishedBooksAndPlacesInModesNoLongerHere() {
        val shelf = listOf(entry("done", finishedAt = 5), entry("ebook-gone"), entry("going"))
        val formats = mapOf("done" to listening("done", 30), "going" to listening("going", 10),
            // The place was read, but the edition has since been removed.
            "ebook-gone" to reading("ebook-gone", 20).copy(editions = emptyList(), audio = true))
        assertEquals(listOf("going"), continueItems(shelf, formats, null).map { it.entry.bookId })
    }

    @Test fun thePlayingBookLeavesContinueUnlessReadingIsItsThread() {
        val shelf = listOf(entry("playing"), entry("read-along"), entry("other"))
        val formats = mapOf("playing" to listening("playing", 30), "read-along" to reading("read-along", 20).copy(audio = true), "other" to listening("other", 10))
        assertEquals(listOf("read-along", "other"), continueItems(shelf, formats, "playing").map { it.entry.bookId })
        assertEquals(listOf("playing", "read-along", "other"), continueItems(shelf, formats, "read-along").map { it.entry.bookId })
    }

    @Test fun continueHeadingNamesASharedMode() {
        val listen = ContinueItem(entry("a"), PlaceSummary(PositionOrigin.LISTENING, "1:00 in", null))
        val read = ContinueItem(entry("b"), PlaceSummary(PositionOrigin.READING, "40%", .4f))
        assertEquals("Continue listening", continueHeading(listOf(listen, listen)))
        assertEquals("Continue reading", continueHeading(listOf(read)))
        assertEquals("Continue", continueHeading(listOf(listen, read)))
    }

    private fun item(id: String, title: String, author: String = "Author", savedAt: Long = 0, playedAt: Long = 0, formats: BookFormats = BookFormats(id)) =
        ShelfItem(entry(id, savedAt = savedAt, playedAt = playedAt), Audiobook(id, title, author), formats)

    @Test fun recentActivityCountsReadingMovesAsWellAsListening() {
        val listened = item("listened", "A", playedAt = 200, savedAt = 1)
        val read = item("read", "B", savedAt = 2, formats = reading("read", at = 300))
        val saved = item("saved", "C", savedAt = 250)
        assertEquals(listOf("read", "saved", "listened"), sortShelf(listOf(listened, read, saved), ShelfSort.RECENT).map { it.entry.bookId })
    }

    @Test fun titleIgnoresLeadingArticlesCaseAndAccents() {
        val shelf = listOf(item("1", "The Hobbit"), item("2", "an Ember in the Ashes"), item("3", "Émile"), item("4", "A Wizard of Earthsea"), item("5", "dune"))
        assertEquals(listOf("5", "2", "3", "1", "4"), sortShelf(shelf, ShelfSort.TITLE).map { it.entry.bookId })
    }

    @Test fun authorThenTitleWithUnknownAuthorsLast() {
        val shelf = listOf(item("1", "Persuasion", "Jane Austen"), item("2", "Emma", "jane austen"), item("3", "Untitled", ""), item("4", "Dracula", "Bram Stoker"))
        assertEquals(listOf("4", "2", "1", "3"), sortShelf(shelf, ShelfSort.AUTHOR).map { it.entry.bookId })
    }

    @Test fun progressPutsFurthestAlongFirstAndUnknownPlacesLast() {
        val shelf = listOf(item("none", "A", playedAt = 999), item("half", "B", formats = reading("half", 1, .5)),
            item("most", "C", formats = reading("most", 1, .9)), item("start", "D", formats = reading("start", 1, .01)))
        assertEquals(listOf("most", "half", "start", "none"), sortShelf(shelf, ShelfSort.PROGRESS).map { it.entry.bookId })
    }

    @Test fun dateAddedIsNewestFirstAndAnUnknownSortFallsBackToRecent() {
        val shelf = listOf(item("old", "A", savedAt = 1, playedAt = 50), item("new", "B", savedAt = 9))
        assertEquals(listOf("new", "old"), sortShelf(shelf, ShelfSort.ADDED).map { it.entry.bookId })
        assertEquals(ShelfSort.RECENT, ShelfSort.from("REMOVED_OPTION"))
        assertEquals(ShelfSort.AUTHOR, ShelfSort.from("AUTHOR"))
    }

    @Test fun shelfSearchNeedsEveryWordInTitleOrAuthor() {
        val book = Audiobook("b", "Wuthering Heights", "Emily Brontë")
        assertTrue(matchesShelfQuery(book, ""))
        assertTrue(matchesShelfQuery(book, "  heights "))
        assertTrue(matchesShelfQuery(book, "bronte wuth"))
        assertFalse(matchesShelfQuery(book, "bronte jane"))
    }

    @Test fun listeningFinishesOnlyWhenTheLastPartPlaysThrough() {
        assertTrue(listeningFinished(partIndex = 3, partCount = 4, ended = true))
        assertFalse(listeningFinished(partIndex = 3, partCount = 4, ended = false))
        assertFalse(listeningFinished(partIndex = 2, partCount = 4, ended = true))
        assertFalse(listeningFinished(partIndex = 0, partCount = 0, ended = true))
    }

    @Test fun readingFinishesWhenThePageShowsTheEditionsEnd() {
        val layout = EditionLayout(listOf("ch1", "ch2", "notes"), mapOf("ch1" to 1000, "ch2" to 1000, "notes" to 0))
        fun at(resource: String, offset: Int) = ContentCursor("e", resource, offset)
        // The last page of the final chapter, which runs to its end.
        assertTrue(readingFinished(layout, at("ch2", 900), null))
        // An empty trailing resource is also past the end.
        assertTrue(readingFinished(layout, at("notes", 0), null))
        // The end of an earlier chapter, or a page with more text after it, isn't the end of the book.
        assertFalse(readingFinished(layout, at("ch1", 900), null))
        assertFalse(readingFinished(layout, at("ch2", 800), at("ch2", 990)))
        assertFalse(readingFinished(EditionLayout(emptyList(), emptyMap()), at("ch1", 0), null))
    }

    @Test fun recentSearchesDedupeIgnoringCaseNewestFirstAndKeepSix() {
        var recent = emptyList<String>()
        listOf("dune", "Hobbit", "  ", "Emma", "DUNE ", "a", "b", "c", "d").forEach { recent = RecentSearches.add(recent, it) }
        assertEquals(listOf("d", "c", "b", "a", "DUNE", "Emma"), recent)
        assertEquals(listOf("d", "c", "a", "DUNE", "Emma"), RecentSearches.remove(recent, "B"))
        assertEquals(listOf("project hail mary"), RecentSearches.add(emptyList(), "  project   hail mary "))
    }

    @Test fun recentSearchesSurviveStorageAndRepairOldOrDamagedValues() {
        val recent = listOf("dune", "Emma", "hobbit")
        assertEquals(recent, RecentSearches.decode(RecentSearches.encode(recent)))
        assertEquals(emptyList<String>(), RecentSearches.decode("not json"))
        assertEquals(emptyList<String>(), RecentSearches.decode(null))
        // Replaying keeps the newer spelling of a duplicate and the six newest.
        assertEquals(listOf("a", "B", "c", "d", "e", "f"), RecentSearches.decode(RecentSearches.encode(listOf("a", "B", "b", "c", "d", "e", "f", "g", "h"))))
    }
}
