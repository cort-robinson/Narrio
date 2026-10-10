package app.narrio.ui

import app.narrio.data.*
import app.narrio.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Searching a book again keeps the listener's own recording rather than the automatic best match. */
class SourceChoiceTest {
    private val book = Audiobook("catalog:phm", "Project Hail Mary", "Andy Weir", provider = "catalog")
    private fun release(id: String, hash: String = "") = Audiobook(id, "Project Hail Mary", "Andy Weir", provider = if (hash.isBlank()) "archive" else "knaben", torrentHash = hash, cacheState = "cached")
    private val best = release("best")
    private val picked = release("picked", hash = "a".repeat(40))
    private fun snapshot(vararg found: Audiobook, complete: Boolean = true) =
        StreamedSourceSearch(book, listOf(SourceGroup("archive", "Archive", SourceGroupStatus.DONE, found.toList())), BestMatch(found.first(), emptyList(), "archive"), complete)
    private val chosen = SourceSearchState(book = book).withSnapshot(snapshot(best, picked)).copy(chosenId = picked.id)

    @Test fun aNewSearchKeepsThePickUntilItsResultsArrive() {
        val restarted = SourceSearchState(book = book, loading = true, searched = true).keepingChoiceOf(chosen)
        assertEquals(picked.id, restarted.chosenId)
        // Answers without the pick yet don't drop it; when it returns (here under a new id, same torrent) it's chosen again.
        val partial = restarted.withSnapshot(snapshot(best, complete = false))
        assertEquals(best.id, partial.choice?.id)
        val returned = partial.withSnapshot(snapshot(best, picked.copy(id = "picked-again")))
        assertEquals("picked-again", returned.choice?.id)
    }

    @Test fun reusedResultsKeepThePickToo() {
        val cached = SourceSearchState(book = book).withSnapshot(snapshot(best, picked))
        assertEquals(best.id, cached.choice?.id)
        assertEquals(picked.id, cached.keepingChoiceOf(chosen).choice?.id)
    }

    @Test fun theBestMatchLeadsOnlyWhenThePickIsGoneOrNoneWasMade() {
        val gone = SourceSearchState(book = book, loading = true, searched = true).keepingChoiceOf(chosen).withSnapshot(snapshot(best))
        assertEquals(best.id, gone.choice?.id)
        val automatic = SourceSearchState(book = book).withSnapshot(snapshot(best, picked))
        assertNull(SourceSearchState(book = book).keepingChoiceOf(automatic).chosenId)
        val otherBook = SourceSearchState(book = book.copy(id = "catalog:other")).keepingChoiceOf(chosen)
        assertNull(otherBook.chosenId)
    }
}
