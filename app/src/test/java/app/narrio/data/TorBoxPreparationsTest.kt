package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class TorBoxPreparationsTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val start = 1_760_000_000_000L
    private val book = Audiobook("book", "The Secret Garden", "Frances Hodgson Burnett", provider = "knaben", torrentHash = "a".repeat(40),
        sources = listOf(AudioSource("indexed:m4b", "Whole-book audio", "M4B", listOf(AudioPart("m", "garden.m4b", "Whole book")))))
    private val prepared = listOf(AudioSource("torbox:7:m4b", "Whole-book audio", "M4B", listOf(AudioPart("torbox:7:1", "garden.m4b", "Whole book", torrentId = 7, fileId = 1)), "torbox", 7))

    private class FakeShelf(vararg entries: ShelfEntry) : PreparationShelf {
        val rows = entries.associateBy { it.bookId }.toMutableMap()
        override suspend fun find(id: String) = rows[id]
        override suspend fun preparing() = rows.values.filter { it.state == "preparing" }
        override suspend fun state(id: String, state: String) { rows[id] = rows.getValue(id).copy(state = state) }
        override suspend fun save(book: Audiobook) { rows[book.id] = rows.getValue(book.id).copy(bookJson = NarrioJson.encodeToString(Audiobook.serializer(), book)) }
    }
    private class FakeHistory : PreparationHistory {
        val watches = mutableMapOf<String, PreparationWatch>()
        override fun get(id: String) = watches[id] ?: PreparationWatch()
        override fun put(id: String, watch: PreparationWatch) { watches[id] = watch }
        override fun remove(id: String) { watches.remove(id) }
    }
    private fun entry(state: String = "preparing", book: Audiobook = this.book) =
        ShelfEntry(book.id, NarrioJson.encodeToString(Audiobook.serializer(), book), state = state, preparationId = 7, pendingFormat = "M4B")
    private fun waiting(progress: Float = .4f) = Preparation(7, false, progress, "Preparing in TorBox")
    private fun ready() = Preparation(7, true, 1f, "Ready to listen")

    @Test fun checksComeQuicklyAtFirstThenEveryHalfHourAndBackOffWhenTorBoxIsUnreachable() {
        assertEquals(listOf(2L, 5, 10, 15, 30, 30), (0..5).map { PreparationPolicy.delayMinutes(it, 0) })
        assertEquals(30L, PreparationPolicy.delayMinutes(400, 0))
        assertEquals(listOf(30L, 60, 120, 240, 240), (1..5).map { PreparationPolicy.delayMinutes(3, it) })
        assertNull("Gives up after six unreachable rounds in a row", PreparationPolicy.delayMinutes(3, PreparationPolicy.MAX_ERROR_RUNS))
    }

    @Test fun aRemovedReleaseFailsOnlyWhenItStaysMissing() {
        var watch = PreparationWatch()
        val missing = Preparation(7, false, 0f, "Waiting for TorBox", missing = true)
        fun at(prep: Preparation, minutes: Long) = PreparationPolicy.judge(prep, watch, start + minutes * minute).also { watch = it.second }.first
        assertFalse(at(missing, 0).failed)
        assertFalse(at(missing, 1).failed)
        assertFalse("Three quick misses within minutes aren't enough", at(missing, 2).failed)
        assertFalse("A listing that returns resets the count", at(waiting(), 3).failed)
        assertFalse(at(missing, 4).failed); assertFalse(at(missing, 9).failed)
        val failed = at(missing, 15)
        assertTrue(failed.failed)
        assertEquals(PreparationPolicy.FAILED_STATE, failed.state)
        assertEquals("This release is no longer in your TorBox account.", watch.problem)
    }

    @Test fun aQueuedRequestAndAReleaseWithoutPeersGetADay() {
        val queued = Preparation(0, false, 0f, "Waiting for TorBox", missing = true)
        var watch = PreparationWatch()
        repeat(10) { watch = PreparationPolicy.judge(queued, watch, start + it * hour).second }
        assertEquals("", watch.problem)
        assertTrue(PreparationPolicy.judge(queued, watch, start + 24 * hour).first.failed)

        val stalled = waiting(.2f).copy(stalled = true)
        var stall = PreparationPolicy.judge(stalled, PreparationWatch(), start).second
        stall = PreparationPolicy.judge(waiting(.3f), stall, start + 20 * hour).second
        assertFalse("Peers returning restarts the wait", PreparationPolicy.judge(stalled, stall, start + 30 * hour).first.failed)
        stall = PreparationPolicy.judge(stalled, stall, start + 30 * hour).second
        assertTrue(PreparationPolicy.judge(stalled, stall, start + 54 * hour).first.failed)
    }

    @Test fun readyBooksSaveTheirTorBoxAudioAndAreReportedOnce() = runTest {
        val shelf = FakeShelf(entry()); val history = FakeHistory()
        history.put(book.id, PreparationWatch(missingChecks = 1, missingSinceMs = 5))
        val tracker = TorBoxPreparations(shelf, history, { _, _ -> ready() }, { _, id -> assertEquals(7L, id); prepared })
        val reported = mutableListOf<PreparationChange>()
        tracker.announce = { reported += it }

        val first = tracker.check(book, shelf.rows.getValue(book.id))
        assertTrue(first.preparation.ready)
        assertEquals(listOf("M4B"), first.book!!.cachedFormats)
        val saved = shelf.rows.getValue(book.id)
        assertEquals("ready", saved.state)
        assertEquals(prepared, saved.book().sources.filter { it.delivery == "torbox" })
        assertTrue(history.watches.isEmpty())

        val again = tracker.check(book, saved)
        assertNull("A book already ready isn't announced again", again.change)
        assertEquals(listOf(true), reported.map { it.ready })
        assertEquals("The Secret Garden", reported.single().book.title)
    }

    @Test fun theBookPageAndBackgroundChecksNeverAnnounceTwice() = runTest {
        val shelf = FakeShelf(entry())
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val tracker = TorBoxPreparations(shelf, FakeHistory(), { _, _ -> calls++; gate.await(); ready() }, { _, _ -> prepared })
        var announced = 0
        tracker.announce = { announced++ }
        val page = async { tracker.check(book, shelf.rows.getValue(book.id)) }
        val background = async { tracker.check(book, shelf.rows.getValue(book.id)) }
        gate.complete(Unit)
        assertEquals(1, listOfNotNull(page.await().change, background.await().change).size)
        assertEquals(1, announced); assertEquals(2, calls)
    }

    @Test fun failuresAreAnnouncedBeforeTheShelfChangesAndCanRecover() = runTest {
        val shelf = FakeShelf(entry()); val history = FakeHistory()
        var status = Preparation(7, false, .1f, PreparationPolicy.FAILED_STATE, problem = "TorBox couldn't fetch this release.")
        val tracker = TorBoxPreparations(shelf, history, { _, _ -> status }, { _, _ -> error("not ready") })
        val seenStates = mutableListOf<String>()
        tracker.announce = { seenStates += shelf.rows.getValue(book.id).state }

        val failed = tracker.check(book, shelf.rows.getValue(book.id))
        assertFalse(failed.change!!.ready)
        assertEquals(listOf("preparing"), seenStates)
        assertEquals("failed", shelf.rows.getValue(book.id).state)
        assertEquals("TorBox couldn't fetch this release.", tracker.placeholder(shelf.rows.getValue(book.id))!!.problem)
        assertNull("Still failed isn't news", tracker.check(book, shelf.rows.getValue(book.id)).change)

        status = waiting()
        tracker.check(book, shelf.rows.getValue(book.id))
        assertEquals("Restarted in TorBox, it's getting ready again", "preparing", shelf.rows.getValue(book.id).state)
    }

    @Test fun backgroundRoundsStopWhenNothingIsLeftOrTheKeyIsRejected() = runTest {
        val other = book.copy(id = "other", title = "Persuasion")
        val shelf = FakeShelf(entry(), entry(book = other))
        var refresh: (Audiobook) -> Preparation = { waiting() }
        val tracker = TorBoxPreparations(shelf, FakeHistory(), { b, _ -> refresh(b) }, { _, _ -> prepared })

        assertNull("Disconnected TorBox isn't contacted", tracker.backgroundCheck(false, PreparationRound()))
        assertEquals(PreparationRound(1, 0), tracker.backgroundCheck(true, PreparationRound()))

        refresh = { throw ProviderException("TorBox is unavailable (503). Retry when your connection returns.") }
        assertEquals(PreparationRound(5, 3), tracker.backgroundCheck(true, PreparationRound(4, 2)))
        assertNull(tracker.backgroundCheck(true, PreparationRound(9, PreparationPolicy.MAX_ERROR_RUNS - 1)))

        refresh = { if (it.id == other.id) throw ProviderException("Slow down") else waiting() }
        assertEquals("One answer is enough to keep the normal pace", PreparationRound(5, 0), tracker.backgroundCheck(true, PreparationRound(4, 2)))

        refresh = { throw ProviderAuthorizationException("TorBox could not authorize this request.") }
        assertNull(tracker.backgroundCheck(true, PreparationRound(1, 0)))

        refresh = { ready() }
        assertNull("Everything ready ends the checks", tracker.backgroundCheck(true, PreparationRound(1, 0)))
        assertEquals(setOf("ready"), shelf.rows.values.map { it.state }.toSet())
    }

    @Test fun checksFollowTheBooksGettingReady() {
        assertEquals(PreparationSchedule.KEEP, PreparationSchedule.plan(null, setOf("a:7")))
        assertEquals(PreparationSchedule.CANCEL, PreparationSchedule.plan(null, emptySet()))
        assertEquals(PreparationSchedule.RESTART, PreparationSchedule.plan(setOf("a:7"), setOf("a:7", "b:9")))
        assertEquals("A new torrent for the same book starts quickly again", PreparationSchedule.RESTART, PreparationSchedule.plan(setOf("a:7"), setOf("a:8")))
        assertEquals(PreparationSchedule.NONE, PreparationSchedule.plan(setOf("a:7", "b:9"), setOf("a:7")))
        assertEquals(PreparationSchedule.CANCEL, PreparationSchedule.plan(setOf("a:7"), emptySet()))
        assertEquals(PreparationSchedule.NONE, PreparationSchedule.plan(emptySet(), emptySet()))
    }

    @Test fun torBoxStatesSayWhetherAReleaseCanStillGetReady() = runTest {
        val server = MockWebServer(); server.start()
        try {
            fun list(state: String, extra: String = "") = MockResponse().setBody("""{"success":true,"data":[{"id":7,"hash":"${"a".repeat(40)}","download_state":"$state","progress":0.2,"download_finished":false,"download_present":false$extra}]}""")
            val client = TorBoxDelivery(OkHttpClient(), { "test-secret" }, server.url("/").toString())
            server.enqueue(list("error"))
            client.refresh(book, 7).let { assertTrue(it.failed); assertEquals(PreparationPolicy.FAILED_STATE, it.state) }
            server.enqueue(list("stalled (no seeds)", ""","seeds":0"""))
            client.refresh(book, 7).let { assertTrue(it.stalled); assertFalse(it.failed); assertEquals("Waiting for available peers", it.state) }
            server.enqueue(MockResponse().setBody("""{"success":true,"data":[]}"""))
            assertTrue(client.refresh(book, 7).missing)
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { client.refresh(book, 7) }.exceptionOrNull() is ProviderAuthorizationException)
        } finally { server.shutdown() }
    }
}
