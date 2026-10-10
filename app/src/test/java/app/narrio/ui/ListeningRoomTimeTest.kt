package app.narrio.ui

import app.narrio.domain.*
import app.narrio.playback.*
import org.junit.Assert.*
import org.junit.Test

class ListeningRoomTimeTest {
    private val m4b = AudioSource("m4b", "Whole book", "M4B", listOf(AudioPart("m", "book.m4b", "Book")))
    private val mp3 = AudioSource("mp3", "Parts", "MP3", List(3) { AudioPart("p$it", "p$it.mp3", "Part ${it + 1}", durationMs = 3_600_000) })
    private val chapters = List(40) { Chapter("Chapter ${it + 1}", it * 900_000L) }

    private fun labels(state: ListeningState) = sleepChoices(state).map { it.label }

    @Test fun sleepOffersTheEndOfAChapterWhenChaptersAreKnownAndTheEndOfAPartOnlyWithSeveralParts() {
        val single = ListeningState(source = m4b, durationMs = 36_000_000)
        assertFalse("At the end of this audio part" in labels(single))
        assertFalse("At the end of this chapter" in labels(single))
        val chaptered = single.copy(chapters = chapters)
        assertTrue("At the end of this chapter" in labels(chaptered))
        assertFalse("At the end of this audio part" in labels(chaptered))
        val parts = ListeningState(source = mp3, durationMs = 3_600_000)
        assertTrue("At the end of this audio part" in labels(parts))
        assertFalse("At the end of this chapter" in labels(parts))
        assertEquals("Turn timer off", labels(parts).last())
    }

    @Test fun theChosenOptionIsSelectedAndTheStatusSaysWhenItPauses() {
        val now = 1_000_000L
        val thirty = ListeningState(source = m4b, sleep = SleepTimer(SleepMode.MINUTES, 30, now + 12 * 60_000 - 5_000))
        assertEquals(listOf("In 30 minutes"), sleepChoices(thirty).filter { it.selected(thirty.sleep) }.map { it.label })
        assertEquals("Pauses in about 12 minutes.", sleepStatus(thirty, now))
        // After +15 no preset describes the timer, so none is selected, and timer off isn't either.
        val extended = thirty.copy(sleep = thirty.sleep.copy(minutes = 0))
        assertTrue(sleepChoices(extended).none { it.selected(extended.sleep) })
        val chapter = ListeningState(source = m4b, durationMs = 36_000_000, positionMs = 1_000_000, chapters = chapters, speed = 2f,
            sleep = SleepTimer(SleepMode.END_OF_CHAPTER, stop = PartPlace(0, 1_800_000)))
        assertEquals(listOf("At the end of this chapter"), sleepChoices(chapter).filter { it.selected(chapter.sleep) }.map { it.label })
        assertEquals("Pauses at the end of this chapter, in about 7 minutes.", sleepStatus(chapter, now))
        assertEquals("End of chapter", sleepLabel(chapter))
        assertEquals(listOf("Turn timer off"), sleepChoices(ListeningState(source = m4b)).filter { it.selected(SleepTimer()) }.map { it.label })
    }

    @Test fun timeLeftCoversTheWholeBookWithTheChapterAndTheTimeAtTheChosenSpeed() {
        val state = ListeningState(source = m4b, positionMs = 36_000_000 - 34_860_000, durationMs = 36_000_000, chapters = chapters)
        assertEquals("Ch 2 · 9 h 41 m left in book", timeLeft(state)!!.text)
        val faster = timeLeft(state.copy(speed = 1.25f))!!
        assertEquals("Ch 2 · 9 h 41 m left in book (7 h 45 m at 1.25×)", faster.text)
        assertEquals("Chapter 2, 9 hours 41 minutes left in book, 7 hours 45 minutes at 1.25 times speed", faster.spoken)
    }

    @Test fun previousFromAPartsStartUsesThatEarlierPartsOwnChapters() {
        val start = ListeningState(source = mp3, partIndex = 1, positionMs = 1_000, chapters = listOf(Chapter("B1", 0)))
        assertEquals(PartPlace(0, 0), start.step(forward = false))
        val known = start.copy(partChapters = mapOf("p0" to listOf(Chapter("A1", 0), Chapter("A2", 1_800_000)), "p1" to start.chapters))
        assertEquals(PartPlace(0, 1_800_000), known.step(forward = false))
        assertEquals(PartPlace(2, 0), known.step(forward = true))
    }

    @Test fun timeLeftFallsBackToThePartRatherThanGuessingTheBook() {
        val parts = ListeningState(source = mp3, partIndex = 1, positionMs = 1_200_000, durationMs = 3_600_000)
        assertEquals("1 h 40 m left in book", timeLeft(parts)!!.text)
        val unknown = parts.copy(source = mp3.copy(parts = mp3.parts.mapIndexed { i, p -> if (i == 2) p.copy(durationMs = 0) else p }))
        assertEquals("40 m left in this part (20 m at 2×)", timeLeft(unknown.copy(speed = 2f))!!.text)
        assertNull(timeLeft(unknown.copy(durationMs = 0)))
        // Whole-book progress drives the mini-player's line only when it's known.
        assertEquals(4.8f / 10.8f, parts.progress, .0001f)
        assertEquals(1f / 3, unknown.progress, .0001f)
    }
}
