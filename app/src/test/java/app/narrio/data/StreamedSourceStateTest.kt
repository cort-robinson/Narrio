package app.narrio.data

import app.narrio.domain.*
import app.narrio.ui.SourceSearchState
import org.junit.Assert.*
import org.junit.Test

class StreamedSourceStateTest {
    private val book = Audiobook("book", "Project Hail Mary", "Andy Weir", provider = "catalog")
    private fun release(id: String) = book.copy(id = id, provider = "archive")
    private fun snapshot(best: Audiobook, other: Audiobook? = null, complete: Boolean = false) = StreamedSourceSearch(book,
        listOf(SourceGroup("archive", "Archive", SourceGroupStatus.DONE, listOfNotNull(best, other))),
        BestMatch(best, listOf(BestMatchReason.READY_TO_STREAM), "archive"), complete)

    @Test fun firstFastMatchIsAutomaticAndChosenVersionSurvivesBetterSnapshots() {
        val fast = release("fast")
        val better = release("better")
        val initial = SourceSearchState(book = book).withSnapshot(snapshot(fast))
        assertEquals(fast, initial.choice); assertTrue(initial.loading); assertTrue(initial.searched)
        val chosen = initial.copy(chosenId = fast.id).withSnapshot(snapshot(better, fast, complete = true))
        assertEquals(fast, chosen.choice); assertEquals(better, chosen.recordings.first()); assertFalse(chosen.loading)
        assertEquals(listOf(better, fast), chosen.results)
    }

    @Test fun duplicateMovesToHigherPriorityGroupWithoutLosingExplicitPick() {
        val picked = release("account").copy(torrentHash = "a".repeat(40))
        val indexed = picked.copy(id = "index")
        val next = SourceSearchState(book, listOf(picked), chosenId = picked.id).withSnapshot(snapshot(indexed, release("better")))
        assertEquals(indexed.id, next.chosenId); assertEquals(indexed, next.choice)
    }

    @Test fun possibleMatchesNeedExplicitChoiceAndFailuresKeepFastResults() {
        val possible = release("possible")
        val partial = StreamedSourceSearch(book, listOf(SourceGroup("p", "p", SourceGroupStatus.FAILED, possible = listOf(possible), message = "timed out")), complete = true)
        val state = SourceSearchState().withSnapshot(partial)
        assertNull(state.choice); assertEquals(possible, state.copy(chosenId = possible.id).choice)
        assertEquals("timed out", state.error); assertEquals(partial, state.streamed)
    }
}
