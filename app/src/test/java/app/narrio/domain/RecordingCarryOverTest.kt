package app.narrio.domain

import org.junit.Assert.*
import org.junit.Test

class RecordingCarryOverTest {
    private fun parts(prefix: String, vararg minutes: Long, sizes: List<Long> = emptyList()) = minutes.mapIndexed { i, m ->
        AudioPart("$prefix$i", "$prefix$i.mp3", "Part ${i + 1}", durationMs = m * 60_000, sizeBytes = sizes.getOrElse(i) { 0 })
    }
    private val hour = 3_600_000L

    @Test fun bothLengthsKnownScalesTheWholeBookPlace() {
        // Old: 10 h in ten 1 h parts; listener is 30 min into part 5 (4.5 h, 45%). New: one 12 h M4B.
        val old = parts("mp3-", *LongArray(10) { 60 })
        val new = parts("m4b-", 720)
        val carry = RecordingCarryOver.map(old, "mp3-4", 30 * 60_000, new)!!
        assertEquals("m4b-0", carry.partId)
        assertEquals((4.5 * hour).toLong(), carry.fromElapsedMs)
        assertEquals((0.45 * 12 * hour).toLong(), carry.positionMs)
        assertTrue(carry.proportional)
    }

    @Test fun unknownNewLengthUsesTheSameElapsedTimeInOneFile() {
        val carry = RecordingCarryOver.map(parts("a", 60, 60), "a1", 15 * 60_000, listOf(AudioPart("m4b", "book.m4b", "Book")))!!
        assertEquals(75 * 60_000L, carry.positionMs)
        assertFalse(carry.proportional)
    }

    @Test fun sameElapsedTimeIsCappedToTheNewLength() {
        // The old length is unknown (its last part was never measured), so the time can't be scaled; the new book is shorter.
        val old = parts("a", 300, 0)
        val carry = RecordingCarryOver.map(old, "a1", 0, parts("b", 120, 100))!!
        assertEquals("b1", carry.partId)
        assertEquals(220 * 60_000L - 1_000, carry.toElapsedMs)
        assertEquals(100 * 60_000L - 1_000, carry.positionMs)
    }

    @Test fun unmeasuredPartsAreEstimatedFromFileSizes() {
        // 4 h old book; new layout has four unmeasured files sized 1:1:1:1, so 2 h 30 min lands 30 min into the third.
        val old = parts("a", 240)
        val new = parts("b", 0, 0, 0, 0, sizes = listOf(100, 100, 100, 100))
        val carry = RecordingCarryOver.map(old, "a0", 150 * 60_000, new)!!
        assertEquals("b2", carry.partId)
        assertEquals(30 * 60_000L, carry.positionMs)
        // Without sizes or a known total the layout can't be located, so no place is offered.
        assertNull(RecordingCarryOver.map(old, "a0", 150 * 60_000, parts("c", 0, 0)))
        // The catalog length lets an unmeasured old layout be estimated too.
        val unmeasuredOld = parts("d", 0, 0, sizes = listOf(1, 3))
        assertEquals(60 * 60_000L + 30 * 60_000L, RecordingCarryOver.map(unmeasuredOld, "d1", 30 * 60_000, parts("e", 600), bookDurationMs = 4 * hour)!!.fromElapsedMs)
    }

    @Test fun noOfferNearTheStartOrWhenAnEarlierPartIsUnmeasured() {
        assertNull(RecordingCarryOver.map(parts("a", 60), "a0", 30_000, parts("b", 60)))
        assertNull(RecordingCarryOver.map(parts("a", 0, 60), "a1", 10 * 60_000, parts("b", 120)))
        assertNull(RecordingCarryOver.map(parts("a", 60), "missing", 10 * 60_000, parts("b", 120)))
        assertNull(RecordingCarryOver.map(parts("a", 60), "a0", 10 * 60_000, emptyList()))
    }
}
