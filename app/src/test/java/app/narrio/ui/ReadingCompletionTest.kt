package app.narrio.ui

import app.narrio.domain.ContentCursor
import app.narrio.reader.EditionLayout
import app.narrio.reader.VisibleRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingCompletionTest {
    private val book = EditionLayout(listOf("ch1", "ch2"), mapOf("ch1" to 1000, "ch2" to 1000))
    private fun page(resource: String, offset: Int, end: Int?) = VisibleRange(ContentCursor("e", resource, offset), end?.let { ContentCursor("e", resource, it) })
    private val middle = page("ch1", 400, 700)
    private val penultimate = page("ch2", 600, 900)
    private val last = page("ch2", 900, null)
    private val verdicts = mutableListOf<Boolean>()
    private fun TestScope.completion(layout: EditionLayout? = book) = MutableStateFlow(layout).let { it to ReadingCompletion(it, this, dwellMs = 3_000) { done -> verdicts += done } }

    @Test fun committingTheLastPageFinishesAndAnyOtherReopens() = runTest {
        val (_, completion) = completion()
        completion.committed(last); advanceUntilIdle()
        completion.committed(middle); advanceUntilIdle()
        completion.committed(null); advanceUntilIdle()
        assertEquals(listOf(true, false), verdicts)
    }

    @Test fun theQualifyingPageDecidesEvenWhenTheReaderHasMovedOnBeforeTheCommitLands() = runTest {
        val (_, completion) = completion()
        // The penultimate page qualified and its write is still saving when the reader reaches the last page.
        completion.showing(penultimate)
        completion.showing(last)
        completion.committed(penultimate)
        advanceUntilIdle()
        // The last page hasn't dwelt or been turned past, so the book isn't finished yet.
        assertEquals(listOf(false), verdicts)
    }

    @Test fun aCommitMadeBeforeTheLayoutIsMeasuredIsJudgedOnceItArrives() = runTest {
        val (layout, completion) = completion(layout = null)
        completion.committed(last); advanceUntilIdle()
        assertTrue(verdicts.isEmpty())
        layout.value = book; advanceUntilIdle()
        assertEquals(listOf(true), verdicts)
    }

    @Test fun aWaitingVerdictIsDroppedWhenTheReaderMovesOnOrCommitsAgain() = runTest {
        val (layout, completion) = completion(layout = null)
        completion.committed(last)
        completion.showing(middle)
        advanceUntilIdle()
        completion.committed(penultimate)
        layout.value = book; advanceUntilIdle()
        assertEquals(listOf(false), verdicts)
    }

    @Test fun anOpenedLastPageWithoutReadingActivityChangesNothing() = runTest {
        val (_, completion) = completion()
        completion.showing(last); advanceTimeBy(60_000); advanceUntilIdle()
        assertTrue(verdicts.isEmpty())
    }

    @Test fun aBookThatFitsOnOnePageFinishesAfterTheDwell() = runTest {
        val short = EditionLayout(listOf("text"), mapOf("text" to 240))
        val (_, completion) = completion(short)
        val whole = page("text", 0, null)
        completion.showing(whole); advanceTimeBy(2_000)
        assertTrue(verdicts.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(true), verdicts)
        // Its single page committed by an explicit action finishes it too.
        completion.committed(whole); advanceUntilIdle()
        assertEquals(listOf(true, true), verdicts)
    }

    @Test fun leavingAOnePageBookBeforeTheDwellLeavesItUnfinished() = runTest {
        val short = EditionLayout(listOf("cover", "text"), mapOf("cover" to 0, "text" to 240))
        val (_, completion) = completion(short)
        val whole = page("cover", 0, null).copy(end = ContentCursor("e", "text", 240))
        assertTrue(showsWholeEdition(short, page("text", 0, null)))
        completion.showing(whole); advanceTimeBy(1_000)
        completion.showing(null); advanceUntilIdle()
        assertTrue(verdicts.isEmpty())
    }
}
