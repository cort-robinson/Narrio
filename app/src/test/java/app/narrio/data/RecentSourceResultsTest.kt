package app.narrio.data

import org.junit.Assert.*
import org.junit.Test

class RecentSourceResultsTest {
    @Test fun tenMinuteBookCacheExpiresAndForceBypassesIt() {
        var now = 0L
        val cache = RecentSourceResults<String> { now }
        cache.put("book|connected", "results")
        now = 599_999
        assertEquals("results", cache.get("book|connected"))
        assertNull(cache.get("book|disconnected")); assertNull(cache.get("other|connected"))
        assertNull(cache.get("book|connected", force = true))
        now = 600_000; assertNull(cache.get("book|connected"))
    }

    @Test fun changingProviderSettingsInvalidatesAllBooksAndClockRollbackDoesNotExtendCache() {
        var now = 10L
        val cache = RecentSourceResults<String> { now }
        cache.put("one", "old"); cache.put("two", "old"); cache.clear()
        assertNull(cache.get("one")); assertNull(cache.get("two"))
        cache.put("one", "new"); now = 9; assertNull(cache.get("one"))
    }
}
