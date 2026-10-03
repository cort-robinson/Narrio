package app.narrio.domain

import org.junit.Assert.*
import org.junit.Test

class FollowAlongTimingTest {
    private val lines = (0..3).map { TextPassage("line-$it", "Two words", "chapter.xhtml", it * 10) }
    private val document = BookText("doc", "Book", "Author", "EPUB", "Device", listOf(TextChapter("chapter-1", "Chapter I", lines)))
    private val source = AudioSource("m4b", "Whole book", "M4B", listOf(AudioPart("whole", "Book.m4b", "Whole book")))
    private val binding = TextBinding(document.id, source.id, "whole", WHOLE_BOOK)

    @Test fun estimatedPositionUsesTheAudioClockAndNotWallTimeOrPlaybackSpeed() {
        val timeline = FollowAlongTiming.timeline(document, binding, 80_000)!!
        assertFalse(timeline.timed)
        assertEquals(listOf(0L, 20_000L, 40_000L, 60_000L), timeline.starts)
        assertEquals(2, timeline.activeIndex(40_000))
        assertEquals(0, timeline.activeIndex(0))
        assertNull(timeline.activeIndex(80_000))
        assertNull(FollowAlongTiming.timeline(document, binding, 0))
    }

    @Test fun introAndMultipleManualAnchorsInterpolateWithoutInventingPreciseTiming() {
        val first = FollowAlongTiming.addAnchor(document, binding, "line-0", 10_000, 90_000)
        val second = FollowAlongTiming.addAnchor(document, first, "line-2", 60_000, 90_000)
        val timeline = FollowAlongTiming.timeline(document, second, 90_000)!!
        assertEquals(listOf(10_000L, 35_000L, 60_000L, 75_000L), timeline.starts)
        assertNull(timeline.activeIndex(9000))
        assertEquals(2, timeline.activeIndex(65_000))
        assertTrue(runCatching { FollowAlongTiming.addAnchor(document, second, "line-1", 65_000, 90_000) }.isFailure)
        assertTrue(runCatching { FollowAlongTiming.addAnchor(document, binding, "line-2", 90_000, 90_000) }.isFailure)
    }

    @Test fun differentLayoutsAndDocumentsRequireTheirOwnBindings() {
        val parts = source.copy(id = "mp3", parts = listOf(AudioPart("one", "1.mp3", "Opening"), AudioPart("two", "2.mp3", "Main text")))
        assertNull(defaultTextBinding(document, parts, 1))
        assertNull(FollowAlongTiming.timeline(document.copy(id = "replacement"), binding, 80_000))
        val timed = document.copy(timedSourceId = source.id, timedPartId = "whole")
        assertNull(defaultTextBinding(timed, parts, 0))
        assertNull(FollowAlongTiming.timeline(timed, binding.copy(sourceId = "mp3"), 80_000))
        assertNull(defaultTextBinding(timed, source.copy(parts = listOf(AudioPart("other", "Other.m4b", "Whole"))), 0))
    }
}
