package app.narrio.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingActivityTest {
    @Test fun openingAndWaitingNeverCommits() {
        val activity = ReadingActivity<String>()
        activity.restored("opened")
        assertNull(activity.tick(60_000))
        assertNull(activity.dwellRemaining(60_000))
    }

    @Test fun aPageReadForThreeSecondsCommits() {
        val activity = ReadingActivity<String>()
        activity.restored("a")
        assertNull(activity.moved("b", 1_000))
        assertNull(activity.tick(3_999))
        assertEquals(1_000L, activity.dwellRemaining(3_000))
        assertEquals("b", activity.tick(4_000))
        assertNull("one commit per place", activity.tick(9_000))
    }

    @Test fun theSecondQuickPageTurnCommits() {
        val activity = ReadingActivity<String>()
        activity.restored("a")
        assertNull(activity.moved("b", 0))
        assertEquals("c", activity.moved("c", 400))
        assertNull(activity.moved("d", 800))
        assertEquals("e", activity.moved("e", 1_000))
    }

    @Test fun returningToTheCommittedPageIsNotActivity() {
        val activity = ReadingActivity<String>()
        activity.restored("a")
        assertNull(activity.moved("a", 0))
        assertNull(activity.tick(10_000))
    }

    @Test fun whileAudioPlaysReadingNeverMovesThePosition() {
        val activity = ReadingActivity<String>()
        activity.restored("a")
        activity.moved("b", 0)
        activity.audio(playing = true)
        assertNull(activity.tick(5_000))
        assertNull(activity.moved("c", 6_000))
        assertNull(activity.moved("d", 6_100))
        activity.audio(playing = false)
        assertNull("activity starts over after audio stops", activity.moved("e", 7_000))
        assertEquals("e", activity.tick(10_000))
    }

    @Test fun paceIgnoresSkimsAndPausesAndSettlesOnMeasuredSpeed() {
        var pace = ReadingPace()
        assertFalse(pace.measured)
        pace = pace.sample(2_000, 500).sample(2_000, 20 * 60_000).sample(10, 5_000)
        assertEquals(0, pace.samples)
        repeat(6) { pace = pace.sample(1_000, 60_000) }
        assertTrue(pace.measured)
        assertEquals(1_000.0, pace.charsPerMinute, 1.0)
        assertEquals(3, pace.minutesFor(2_500))
    }

    @Test fun editionLayoutMeasuresAcrossResources() {
        val layout = EditionLayout(listOf("a.xhtml", "b.xhtml", "c.xhtml"), mapOf("a.xhtml" to 100, "b.xhtml" to 50, "c.xhtml" to 50))
        assertEquals(200L, layout.total)
        assertEquals(70L, layout.distance("a.xhtml", 80, "b.xhtml", 50))
        assertEquals(0.75, layout.progression("c.xhtml", 0), 1e-9)
        assertNull(layout.position("missing", 0))
    }

    @Test fun settingsRoundTripAndToleratesDamage() {
        val settings = ReaderSettings(font = ReaderFont.OPEN_DYSLEXIC, fontScale = 1.234, scroll = true)
        val decoded = ReaderSettingsCodec.decode(ReaderSettingsCodec.encode(settings))
        assertEquals(ReaderFont.OPEN_DYSLEXIC, decoded.font)
        assertEquals(1.25, decoded.fontScale, 1e-9)
        assertEquals(ReaderSettings(), ReaderSettingsCodec.decode("{broken"))
        assertEquals(ReaderSettings(), ReaderSettingsCodec.decode("""{"font":"COMIC_SANS"}"""))
        assertEquals(ReaderSettings.MAX_SCALE, ReaderSettings(fontScale = 9.0).normalized().fontScale, 1e-9)
        assertFalse(ReaderSettings().withAdvanced { copy(justify = false) }.publisherStyles)
    }
}
