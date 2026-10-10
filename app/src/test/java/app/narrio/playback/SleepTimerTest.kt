package app.narrio.playback

import app.narrio.domain.*
import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    private val chapters = listOf(Chapter("One", 0), Chapter("Two", 600_000), Chapter("Three", 1_200_000))

    @Test fun endOfChapterStopsAtTheNextChapterOrTheEndOfTheFile() {
        assertEquals(PartPlace(0, 600_000), sleepStop(SleepMode.END_OF_CHAPTER, 0, 120_000, chapters))
        assertEquals(PartPlace(0, PART_END), sleepStop(SleepMode.END_OF_CHAPTER, 0, 1_300_000, chapters))
        // Without chapters (yet), the chapter is the whole part.
        assertEquals(PartPlace(2, PART_END), sleepStop(SleepMode.END_OF_CHAPTER, 2, 5_000, emptyList()))
        assertEquals(PartPlace(2, PART_END), sleepStop(SleepMode.END_OF_PART, 2, 5_000, chapters))
        assertNull(sleepStop(SleepMode.MINUTES, 0, 0, chapters))
    }

    @Test fun aChapterTimerAimedBeforeChaptersLoadedNarrowsToTheChapterItWasSetIn() {
        // Seeked into part 2 at 1:00 before its chapters were read: the part's end for now.
        val early = boundaryTimer(SleepMode.END_OF_CHAPTER, PartPlace(2, 60_000), emptyList())
        assertEquals(PartPlace(2, PART_END), early.stop)
        assertEquals(PartPlace(2, 600_000), withChapters(early, 2, chapters).stop)
        // Another part's chapters never aim it.
        assertEquals(early, withChapters(early, 1, chapters))
        // Already aimed, or a part timer: unchanged.
        val aimed = boundaryTimer(SleepMode.END_OF_CHAPTER, PartPlace(2, 60_000), chapters)
        assertEquals(aimed, withChapters(aimed, 2, listOf(Chapter("Other", 0), Chapter("Later", 900_000))))
        val part = boundaryTimer(SleepMode.END_OF_PART, PartPlace(2, 60_000), emptyList())
        assertEquals(part, withChapters(part, 2, chapters))
    }

    @Test fun pausedTimersRestoreTheVolumeAndOnlyMinuteTimersKeepTheirClock() {
        val chapter = SleepTimer(SleepMode.END_OF_CHAPTER, stop = PartPlace(0, 600_000))
        // Paused mid-fade: full volume, and nothing to poll until playback changes.
        assertEquals(1f, sleepVolume(chapter, playing = false, remainingMs = 2_000), 0f)
        assertNull(sleepWaitMs(chapter, playing = false, remainingMs = 2_000))
        assertEquals(.25f, sleepVolume(chapter, playing = true, remainingMs = SLEEP_FADE_MS / 2), .0001f)
        assertEquals(100L, sleepWaitMs(chapter, playing = true, remainingMs = 2_000))
        // A minute timer's clock runs while paused: it wakes when it expires.
        val minutes = SleepTimer(SleepMode.MINUTES, 15, untilMs = 100_000)
        assertEquals(40_000L, sleepWaitMs(minutes, playing = false, remainingMs = 40_000))
        assertEquals(10L, sleepWaitMs(minutes, playing = false, remainingMs = -5))
        assertEquals(1f, sleepVolume(SleepTimer(), playing = true, remainingMs = 0), 0f)
    }

    @Test fun remainingTimeFollowsSpeedAndPausesOncePlaybackCarriesPastTheStop() {
        val chapter = SleepTimer(SleepMode.END_OF_CHAPTER, stop = PartPlace(1, 600_000))
        assertEquals(480_000L, sleepRemainingMs(chapter, 1, 120_000, 3_000_000, 1f, 0))
        assertEquals(240_000L, sleepRemainingMs(chapter, 1, 120_000, 3_000_000, 2f, 0))
        // Playback reached the boundary, or rolled into the next part.
        assertTrue(sleepRemainingMs(chapter, 1, 600_050, 3_000_000, 1f, 0)!! <= 0)
        assertEquals(0L, sleepRemainingMs(chapter, 2, 0, 3_000_000, 1f, 0))
        assertNull(sleepRemainingMs(chapter, 0, 0, 3_000_000, 1f, 0))
        val part = SleepTimer(SleepMode.END_OF_PART, stop = PartPlace(1, PART_END))
        assertEquals(60_000L, sleepRemainingMs(part, 1, 2_940_000, 3_000_000, 1f, 0))
        // A part whose length hasn't loaded can't be counted down; the part change still pauses.
        assertNull(sleepRemainingMs(part, 1, 2_940_000, 0, 1f, 0))
        // Minute timers count the clock, at any speed, playing or not.
        assertEquals(90_000L, sleepRemainingMs(SleepTimer(SleepMode.MINUTES, 15, untilMs = 100_000), 0, 0, 0, 2f, 10_000))
        assertNull(sleepRemainingMs(SleepTimer(), 0, 0, 0, 1f, 0))
    }

    @Test fun aPartEndTimerPausesOnThePartChangeSoThePlaceIsTheNextPartsStart() {
        val part = SleepTimer(SleepMode.END_OF_PART, stop = PartPlace(1, PART_END))
        assertFalse(sleepPausesNow(part, 1, 0))
        assertFalse(sleepPausesNow(part, 1, -40))
        assertTrue(sleepPausesNow(part, 2, 0))
        val chapter = SleepTimer(SleepMode.END_OF_CHAPTER, stop = PartPlace(1, 600_000))
        assertTrue(sleepPausesNow(chapter, 1, 0))
        assertFalse(sleepPausesNow(chapter, 1, 50))
        assertFalse(sleepPausesNow(chapter, 1, null))
        assertTrue(sleepPausesNow(SleepTimer(SleepMode.MINUTES, 15, 0), 0, -1))
    }

    @Test fun plusFifteenAddsToWhenTheTimerWouldHavePaused() {
        val minutes = extendSleep(SleepTimer(SleepMode.MINUTES, 30, untilMs = 1_000_000), 400_000, 600_000)
        assertEquals(SleepTimer(SleepMode.MINUTES, 0, 1_000_000 + SLEEP_EXTENSION_MS), minutes)
        // A chapter timer becomes a minute timer ending 15 minutes after the chapter would have.
        assertEquals(10_000 + 120_000 + SLEEP_EXTENSION_MS, extendSleep(SleepTimer(SleepMode.END_OF_CHAPTER, stop = PartPlace(0, 1)), 120_000, 10_000).untilMs)
        // Mid-fade, or with an unknown end, it counts from now.
        assertEquals(10_000 + SLEEP_EXTENSION_MS, extendSleep(SleepTimer(SleepMode.END_OF_PART, stop = PartPlace(0, PART_END)), null, 10_000).untilMs)
        assertEquals(10_000 + SLEEP_EXTENSION_MS, extendSleep(SleepTimer(SleepMode.MINUTES, 15, untilMs = 9_000), -1_000, 10_000).untilMs)
    }

    @Test fun narrationFadesOnlyOverTheLastSecondsAndEndsSilent() {
        assertEquals(1f, sleepFadeVolume(null), 0f)
        assertEquals(1f, sleepFadeVolume(60_000), 0f)
        assertEquals(1f, sleepFadeVolume(SLEEP_FADE_MS), 0f)
        assertEquals(.25f, sleepFadeVolume(SLEEP_FADE_MS / 2), .0001f)
        assertEquals(0f, sleepFadeVolume(0), 0f)
        val ramp = (SLEEP_FADE_MS downTo 0 step 500).map { sleepFadeVolume(it) }
        assertEquals(ramp.sortedDescending(), ramp)
        // Far off, the service checks once a second at most; while fading, every tenth of a second.
        assertEquals(1_000L, sleepCheckDelayMs(10 * 60_000))
        assertEquals(500L, sleepCheckDelayMs(SLEEP_FADE_MS + 500))
        assertEquals(100L, sleepCheckDelayMs(5_000))
        assertEquals(40L, sleepCheckDelayMs(40))
        // Waiting at a part's end for the part change: no busy loop if the listener paused right there.
        assertEquals(100L, sleepCheckDelayMs(-20))
    }

    @Test fun aDeliberateShakeIsHeardOnceAndTurningOverIsNot() {
        val shake = ShakeDetector()
        fun feed(g: Float, at: Long) = shake.sample(0f, 0f, g * 9.80665f, at)
        // One jolt, however long, is a single peak.
        assertFalse((0L..200L step 20).map { feed(3f, it) }.any { it })
        assertFalse(feed(1f, 220))
        // Peaks spread over seconds (turning over, setting the phone down) never add up.
        assertFalse(feed(3f, 2_000)); assertFalse(feed(1f, 2_050)); assertFalse(feed(3f, 4_000)); assertFalse(feed(1f, 4_050))
        // Three strokes in about a second: one shake.
        assertFalse(feed(3f, 6_000)); assertFalse(feed(1f, 6_100)); assertFalse(feed(3f, 6_300)); assertFalse(feed(1f, 6_400))
        assertTrue(feed(3f, 6_600))
        // Carrying on shaking doesn't add another 15 minutes straight away.
        assertFalse(feed(1f, 6_700)); assertFalse(feed(3f, 6_900)); assertFalse(feed(1f, 7_000)); assertFalse(feed(3f, 7_200)); assertFalse(feed(1f, 7_300)); assertFalse(feed(3f, 7_500))
        // Gentle handling stays under the threshold.
        assertFalse((10_000L..11_000L step 20).map { feed(if (it % 40 == 0L) 1.8f else 1f, it) }.any { it })
    }
}
