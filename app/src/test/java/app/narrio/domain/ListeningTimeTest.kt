package app.narrio.domain

import org.junit.Assert.*
import org.junit.Test

class ListeningTimeTest {
    private fun parts(vararg lengths: Long) = lengths.mapIndexed { i, ms -> AudioPart("p$i", "p$i.mp3", "Part ${i + 1}", durationMs = ms) }
    private val chapters = listOf(Chapter("Opening", 0), Chapter("One", 60_000), Chapter("Two", 600_000), Chapter("Three", 1_200_000))

    @Test fun wholeBookTimeNeedsEveryPartsLengthButTakesThePlayingPartsMeasuredLength() {
        val time = bookTime(parts(600_000, 600_000, 600_000), 1, 300_000)!!
        assertEquals(900_000, time.positionMs)
        assertEquals(900_000, time.remainingMs)
        assertEquals(.5f, time.fraction, .0001f)
        // An unheard part with no length means no whole-book figure at all.
        assertNull(bookTime(parts(600_000, 0, 600_000), 0, 1_000))
        // The playing part's measured length fills its own gap.
        assertEquals(1_800_000, bookTime(parts(600_000, 0, 600_000), 1, 0, currentDurationMs = 600_000)!!.totalMs)
        assertNull(bookTime(emptyList(), 0, 0))
    }

    @Test fun chapterEndIsTheNextStartAndAChapterAboutToEndCountsAsFinished() {
        assertEquals(1, chapterIndexAt(chapters, 300_000))
        assertEquals(-1, chapterIndexAt(listOf(Chapter("Late", 5_000)), 1_000))
        assertEquals(600_000L, chapterEndMs(chapters, 300_000))
        // Two seconds before chapter Two starts: stop at the end of Two, not in two seconds.
        assertEquals(1_200_000L, chapterEndMs(chapters, 598_000))
        // The last chapter runs to the end of the part.
        assertNull(chapterEndMs(chapters, 1_500_000))
    }

    @Test fun previousRestartsAChapterOnceItHasPlayedThenStepsBack() {
        assertEquals(PartPlace(0, 60_000), chapterStep(chapters, 0, 1, 300_000, forward = false))
        assertEquals(PartPlace(0, 0), chapterStep(chapters, 0, 1, 61_000, forward = false))
        assertEquals(PartPlace(0, 600_000), chapterStep(chapters, 0, 1, 300_000, forward = true))
        // Nothing past the last chapter of the last part, nor before the very start.
        assertNull(chapterStep(chapters, 0, 1, 1_300_000, forward = true))
        assertNull(chapterStep(chapters, 0, 1, 0, forward = false))
    }

    @Test fun chaptersRunIntoNeighbouringPartsAndPartsWithoutChaptersStepByPart() {
        assertEquals(PartPlace(3, 0), chapterStep(chapters, 2, 5, 1_300_000, forward = true))
        assertEquals(PartPlace(1, 0), chapterStep(chapters, 2, 5, 1_000, forward = false))
        assertEquals(PartPlace(3, 0), chapterStep(emptyList(), 2, 5, 40_000, forward = true))
        assertEquals(PartPlace(2, 0), chapterStep(emptyList(), 2, 5, 40_000, forward = false))
        assertEquals(PartPlace(1, 0), chapterStep(emptyList(), 2, 5, 1_000, forward = false))
        assertNull(chapterStep(emptyList(), 4, 5, 40_000, forward = true))
    }

    @Test fun timeLeftReadsInHoursAndMinutesRoundingUp() {
        assertEquals("9 h 41 m", timeLeftLabel(34_860_000))
        assertEquals("2 h", timeLeftLabel(7_200_000))
        assertEquals("41 m", timeLeftLabel(2_460_000))
        assertEquals("1 m", timeLeftLabel(20_000))
        assertEquals("0 m", timeLeftLabel(0))
        assertEquals("9 hours 41 minutes", spokenTimeLeft(34_860_000))
        assertEquals("1 hour", spokenTimeLeft(3_600_000))
        assertEquals("1 minute", spokenTimeLeft(30_000))
        assertEquals(27_888_000, atSpeed(34_860_000, 1.25f))
    }
}
