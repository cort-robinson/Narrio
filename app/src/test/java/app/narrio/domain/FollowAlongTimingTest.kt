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

    private fun assertNear(expected: Long, actual: Long) = assertTrue("expected $expected, was $actual", kotlin.math.abs(expected - actual) <= 1)

    @Test fun recognizedAnchorsPlaceOnlyThisPartsSliceOfTheBook() {
        // Ten 26-word passages (10 s each at 2.6 words/s); this 60 s part narrates from passage 4, 10 s in.
        val words = List(26) { "word" }.joinToString(" ")
        val book = document.copy(chapters = listOf(TextChapter("all", "All", (0..9).map { TextPassage("line-$it", words, "book.xhtml", it * 200) })))
        val part = TextBinding(book.id, source.id, "whole", WHOLE_BOOK)
        val synced = FollowAlongTiming.mergeAuto(book, part, listOf(TextAnchor("line-4", 10_000), TextAnchor("line-6", 30_000)), 60_000)
        val timeline = FollowAlongTiming.timeline(book, synced, 60_000)!!
        assertTrue(timeline.aligned)
        assertNear(10_000, timeline.starts[4])
        assertNear(20_000, timeline.starts[5])
        assertEquals(4, timeline.activeIndex(12_000))
        // The narrator's measured pace continues beyond the anchors.
        assertNear(40_000, timeline.starts[7])
        // Earlier and later passages belong to other parts and fall outside this part's clock.
        assertTrue(timeline.starts[0] < 0)
        assertTrue(timeline.starts[9] >= 59_999)
        assertTrue(FollowAlongTiming.synced(synced, 20_000))
        assertFalse(FollowAlongTiming.synced(binding, 20_000))
    }

    @Test fun recognizedAnchorsThatContradictTheTextOrderOrAManualMatchAreDropped() {
        val manual = FollowAlongTiming.addAnchor(document, binding, "line-2", 40_000, 80_000)
        val merged = FollowAlongTiming.mergeAuto(document, manual, listOf(
            TextAnchor("line-0", 5_000), TextAnchor("line-3", 30_000), TextAnchor("line-3", 60_000),
        ), 80_000)
        // line-3 at 30 s precedes the listener's line-2 at 40 s, so it's discarded.
        assertEquals(listOf("line-0" to 5_000L, "line-2" to 40_000L, "line-3" to 60_000L), merged.anchors.map { it.passageId to it.positionMs })
        assertFalse(merged.anchors.single { it.passageId == "line-2" }.auto)
        // A later manual match overrides a recognized anchor that disagrees with it.
        val corrected = FollowAlongTiming.addAnchor(document, merged, "line-1", 3_000, 80_000)
        assertEquals(listOf("line-1", "line-2", "line-3"), corrected.anchors.map { it.passageId })
        // Contradictory manual matches still require a reset.
        assertTrue(runCatching { FollowAlongTiming.addAnchor(document, merged, "line-1", 50_000, 80_000) }.isFailure)
        assertNotNull(FollowAlongTiming.timeline(document, corrected, 80_000))
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
