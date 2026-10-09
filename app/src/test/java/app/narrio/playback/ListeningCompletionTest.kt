package app.narrio.playback

import org.junit.Assert.*
import org.junit.Test

class ListeningCompletionTest {
    private val completion = ListeningCompletion().apply { loaded("book", "source", index = 0, positionMs = 0) }
    private fun ended(index: Int = 2, endMs: Long = 600_000, book: String = "book", source: String = "source") =
        completion.ended(book, source, index, partCount = 3, endMs = endMs)

    @Test fun playingIntoAndThroughTheLastPartFinishes() {
        completion.anchor(1, 0); completion.anchor(2, 0)
        assertTrue(ended())
    }

    @Test fun seekingOrSkippingToTheEndDoesNotFinish() {
        completion.anchor(2, 600_000)
        assertFalse(ended())
        // A skip that lands a moment before the end, then plays it out, still isn't listening to the end.
        completion.anchor(2, 597_000)
        assertFalse(ended())
        // Seeking back and listening for a while is.
        completion.anchor(2, 590_000)
        assertTrue(ended())
    }

    @Test fun anEndInAnEarlierPartOrAnotherTimelineIsNotTheBooksEnd() {
        completion.anchor(1, 0)
        assertFalse(completion.ended("book", "source", index = 1, partCount = 3, endMs = 600_000))
        // The anchor belongs to another part than the one that ended.
        assertFalse(ended())
        completion.anchor(2, 0)
        assertFalse(ended(book = "other"))
        assertFalse(ended(source = "other-layout"))
    }

    @Test fun anErrorCountsNothingUntilListeningResumes() {
        completion.anchor(2, 0)
        completion.failed()
        assertFalse(ended())
        completion.anchor(2, 300_000)
        assertTrue(ended())
        completion.cleared()
        assertFalse(ended())
    }

    @Test fun aShortFinalPartPlayedFromItsStartFinishes() {
        completion.anchor(2, 0)
        assertTrue(ended(endMs = 3_000))
        assertFalse(ended(endMs = 0))
    }

    @Test fun aRestoredPlaceNearTheEndDoesNotFinishOnItsOwn() {
        completion.loaded("book", "source", index = 2, positionMs = 599_000)
        assertFalse(ended())
    }

    @Test fun playingAwayFromTheEndReopensAFinishedBook() {
        assertTrue(completion.awayFromEnd(index = 0, partCount = 3, positionMs = 0, durationMs = 600_000))
        assertTrue(completion.awayFromEnd(index = 2, partCount = 3, positionMs = 500_000, durationMs = 600_000))
        assertFalse(completion.awayFromEnd(index = 2, partCount = 3, positionMs = 598_000, durationMs = 600_000))
        // An unknown duration can't be near the end.
        assertTrue(completion.awayFromEnd(index = 2, partCount = 3, positionMs = 598_000, durationMs = -1))
    }
}
