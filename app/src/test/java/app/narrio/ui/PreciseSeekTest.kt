package app.narrio.ui

import org.junit.Assert.*
import org.junit.Test

class PreciseSeekTest {
    @Test fun slidingHigherOnlyEverSlowsSeeking() {
        assertEquals(0, PreciseSeek.band(0f))
        assertEquals(PreciseSeek.bands - 1, PreciseSeek.band(1_000f))
        // A ten-hour part on a 300 dp bar: two minutes per dp when following the finger.
        val full = 36_000_000f / 300
        val rates = (0 until PreciseSeek.bands).map { PreciseSeek.msPerDp(it, full) }
        assertEquals(full, rates.first())
        assertEquals(rates.sortedDescending(), rates)
        // Fine seeking reaches about a second per finger-width (10 dp), however long the part.
        assertTrue(rates.last() <= 100f)
        // A short part never seeks faster than the finger in any band.
        assertTrue((0 until PreciseSeek.bands).all { PreciseSeek.msPerDp(it, 200f) <= 200f })
    }

    @Test fun rulerMarksStayReadableAndLabelsLandOnMarks() {
        assertEquals(1_000L, PreciseSeek.step(100f, 8f))
        val step = PreciseSeek.step(6_000f, 8f)
        assertTrue(step / 6_000f >= 8f)
        val major = PreciseSeek.major(step, 6_000f, 72f)!!
        assertEquals(0L, major % step)
        assertNull(PreciseSeek.finer(1_000L))
        assertEquals(5_000L, PreciseSeek.finer(10_000L))
    }

    @Test fun deltaReadsAsDirectionFromTheStart() {
        assertEquals("+1:05", PreciseSeek.delta(65_000))
        assertEquals("−0:12", PreciseSeek.delta(-12_000))
    }
}
