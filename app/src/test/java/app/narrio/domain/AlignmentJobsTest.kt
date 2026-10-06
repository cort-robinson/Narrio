package app.narrio.domain

import org.junit.Assert.*
import org.junit.Test

class AlignmentJobsTest {
    private val key = AlignmentKey("book", "edition", "recording", "part", 1, "layout")
    @Test fun pairingRequiresAttemptedWindowsAndDistributedMismatchEvidence() {
        assertEquals(PairingStatus.UNCHECKED, PairingEvidence.status(emptyList()))
        assertEquals(PairingStatus.UNCHECKED, PairingEvidence.status(listOf(AlignmentProgress(key, attempted = 4))))
        assertEquals(PairingStatus.MISMATCH, PairingEvidence.status(listOf(AlignmentProgress(key, attempted = 5, firstAttemptMs = 0, lastAttemptMs = 120_000))))
        assertEquals(PairingStatus.PARTIAL, PairingEvidence.status(listOf(AlignmentProgress(key, attempted = 5, matched = 1))))
        assertEquals(PairingStatus.MATCHES, PairingEvidence.status(listOf(AlignmentProgress(key, attempted = 5, matched = 4))))
    }
    @Test fun manualMatchesDoNotCountAsRecognizedCoverage() {
        val binding = TextBinding("edition", "recording", "part", WHOLE_BOOK, listOf(TextAnchor("p", 10_000)))
        assertFalse(PairingEvidence.covered(binding, 0))
        assertTrue(PairingEvidence.covered(binding.copy(anchors = listOf(TextAnchor("p", 10_000, auto = true))), 0))
        assertFalse(PairingEvidence.covered(binding.copy(anchors = listOf(TextAnchor("p", 60_000, auto = true))), 0))
    }
    @Test fun batchesResumeFromNextWindowAndFinishAtDuration() {
        var progress = AlignmentProgress(key)
        assertEquals(0L, AlignmentPolicy.next(progress, 65_000))
        progress = AlignmentPolicy.advance(progress, 0, 65_000, true)
        assertEquals(30_000L, AlignmentPolicy.next(progress, 65_000))
        progress = AlignmentPolicy.advance(progress, 30_000, 65_000, false)
        progress = AlignmentPolicy.advance(progress, 60_000, 65_000, true)
        assertTrue(progress.complete); assertNull(AlignmentPolicy.next(progress, 65_000))
        assertEquals(3, progress.attempted); assertEquals(2, progress.matched)
    }
    @Test fun localAudioOrUnmeteredChargingOnly() {
        assertTrue(AlignmentPolicy.allowed(true, false, false))
        assertTrue(AlignmentPolicy.allowed(false, true, true))
        assertFalse(AlignmentPolicy.allowed(false, true, false))
        assertFalse(AlignmentPolicy.allowed(false, false, true))
    }
}
