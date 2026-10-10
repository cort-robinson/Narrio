package app.narrio.ui

import org.junit.Assert.*
import org.junit.Test

class TorBoxNudgeTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    @Test fun showsOnlyWhileDisconnected() {
        assertTrue(TorBoxNudge.visible(connected = false, dismissedAtMs = 0, nowMs = now))
        assertFalse(TorBoxNudge.visible(connected = true, dismissedAtMs = 0, nowMs = now))
    }

    @Test fun notNowHidesItForThirtyDaysThenItReturns() {
        assertFalse(TorBoxNudge.visible(false, now, now))
        assertFalse(TorBoxNudge.visible(false, now - 30 * day + 1, now))
        assertTrue(TorBoxNudge.visible(false, now - 30 * day, now))
        // Connecting later still hides it, whenever it was dismissed.
        assertFalse(TorBoxNudge.visible(true, now - 90 * day, now))
    }

    @Test fun aClockSetBackBeforeTheDismissalShowsItAgain() {
        assertTrue(TorBoxNudge.visible(false, now + day, now))
    }
}
