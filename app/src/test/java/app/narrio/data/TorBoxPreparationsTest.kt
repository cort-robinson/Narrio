package app.narrio.data

import app.narrio.data.PreparationStates.FAILED
import app.narrio.data.PreparationStates.PAUSED
import app.narrio.data.PreparationStates.PREPARING
import app.narrio.data.PreparationStates.READY
import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class TorBoxPreparationsTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val start = 1_760_000_000_000L
    private val book = Audiobook("book", "The Secret Garden", "Frances Hodgson Burnett", "Ashleighjane", provider = "knaben", torrentHash = "a".repeat(40),
        recordingId = "knaben:garden", sources = listOf(AudioSource("indexed:m4b", "Whole-book audio", "M4B", listOf(AudioPart("m", "garden.m4b", "Whole book")))))
    private val other = book.copy(id = "other", title = "Persuasion", recordingId = "knaben:persuasion", torrentHash = "b".repeat(40))
    private fun prepared(torrent: Long = 7) = listOf(AudioSource("torbox:$torrent:m4b", "Whole-book audio", "M4B",
        listOf(AudioPart("torbox:$torrent:1", "garden.m4b", "Whole book", torrentId = torrent, fileId = 1)), "torbox", torrent))
    private fun waiting(torrent: Long = 7, progress: Float = .4f) = Preparation(torrent, false, progress, "Preparing in TorBox")
    private fun ready(torrent: Long = 7) = Preparation(torrent, true, 1f, "Ready to listen")

    /** Every call suspends, as Room and preferences do; [commitGate] holds a commit just before it validates the row. */
    private class FakeShelf : PreparationShelf {
        val rows = mutableMapOf<String, ShelfEntry>()
        var commitGate: CompletableDeferred<Unit>? = null
        var committing = false
        override suspend fun find(id: String): ShelfEntry? { yield(); return rows[id] }
        override suspend fun tracked(): List<ShelfEntry> { yield(); return rows.values.filter { it.state in PreparationStates.TRACKED } }
        override suspend fun begin(book: Audiobook, torrentId: Long, format: String) {
            yield()
            val row = rows[book.id] ?: ShelfEntry(book.id, NarrioJson.encodeToString(Audiobook.serializer(), book))
            rows[book.id] = row.copy(state = PREPARING, preparationId = torrentId, pendingFormat = format)
        }
        override suspend fun commit(id: String, key: PreparationKey, from: String, state: String, torrentId: Long, ready: Audiobook?): Boolean {
            yield(); committing = true; commitGate?.await(); committing = false
            // From here on nothing suspends: the check and write are one step, like the Room transaction.
            val row = rows[id] ?: return false
            if (row.state != from || row.preparationKey != key) return false
            val saved = row.book()
            val json = if (ready != null && saved.torrentHash == ready.torrentHash)
                NarrioJson.encodeToString(Audiobook.serializer(), saved.copy(cacheState = "cached", sources = (ready.sources + saved.sources).distinctBy { it.id })) else row.bookJson
            rows[id] = row.copy(state = state, preparationId = torrentId, bookJson = json)
            return true
        }
        override suspend fun finish(id: String, key: PreparationKey): Boolean {
            yield(); val row = rows[id] ?: return false
            if (row.preparationKey != key) return false
            rows[id] = row.copy(state = "listening", preparationId = 0, pendingFormat = ""); return true
        }
    }
    private class FakeRecords : PreparationRecords {
        val records = mutableMapOf<String, PreparationRecord>()
        override suspend fun get(bookId: String): PreparationRecord? { yield(); return records[bookId] }
        override suspend fun all(): List<PreparationRecord> { yield(); return records.values.toList() }
        override suspend fun put(record: PreparationRecord) { yield(); records[record.bookId] = record }
        override suspend fun remove(bookId: String) { yield(); records.remove(bookId) }
    }
    private inner class Fixture {
        val shelf = FakeShelf(); val records = FakeRecords()
        var status: (Audiobook, Long) -> Preparation = { _, id -> waiting(id) }
        var listing: suspend () -> Unit = {}
        var listings = 0
        var now = start
        private var generations = 0
        val announced = mutableListOf<PreparationChange>()
        val tracker = TorBoxPreparations(shelf, records, {
            listings++; listing()
            object : PreparationAccount {
                override fun preparation(book: Audiobook, torrentId: Long) = status(book, torrentId)
                override fun sources(book: Audiobook, torrentId: Long) = prepared(torrentId)
            }
        }, { now }, { "generation-${++generations}" }).apply { announce = { announced += it } }
        fun row(id: String = book.id) = shelf.rows[id]
    }

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
        assertEquals("This release is no longer in your TorBox account.", failed.problem)
    }

    @Test fun aQueuedRequestAndAReleaseWithoutPeersGetADay() {
        val queued = Preparation(0, false, 0f, "Waiting for TorBox", missing = true)
        var watch = PreparationWatch()
        repeat(10) { watch = PreparationPolicy.judge(queued, watch, start + it * hour).second }
        assertFalse(PreparationPolicy.judge(queued, watch, start + 23 * hour).first.failed)
        assertTrue(PreparationPolicy.judge(queued, watch, start + 24 * hour).first.failed)

        val stalled = waiting(progress = .2f).copy(stalled = true)
        var stall = PreparationPolicy.judge(stalled, PreparationWatch(), start).second
        stall = PreparationPolicy.judge(waiting(progress = .3f), stall, start + 20 * hour).second
        assertFalse("Peers returning restarts the wait", PreparationPolicy.judge(stalled, stall, start + 30 * hour).first.failed)
        stall = PreparationPolicy.judge(stalled, stall, start + 30 * hour).second
        assertTrue(PreparationPolicy.judge(stalled, stall, start + 54 * hour).first.failed)
    }

    @Test fun readyAudioIsSavedThenAnnouncedOnce() = runTest {
        val f = Fixture()
        val generation = f.tracker.begin(book, waiting(), "M4B").generation
        f.status = { _, id -> ready(id) }
        val first = f.tracker.check(book.id, book)!!
        assertEquals(READY, f.row()!!.state)
        assertEquals(prepared(), f.row()!!.book().sources.filter { it.delivery == "torbox" })
        assertEquals(listOf("M4B"), first.book!!.cachedFormats)
        assertEquals(generation, first.change!!.generation)
        assertTrue(f.records.records.getValue(book.id).announced)
        assertNull("A book already ready isn't announced again", f.tracker.check(book.id, book)!!.change)
        assertEquals(1, f.announced.size)
    }

    @Test fun aPlayedRecordingKeepsItsOwnDetailsWhileAnotherGetsReady() = runTest {
        val f = Fixture()
        val played = book.copy(narrator = "Karen Savage", recordingId = "archive:garden", torrentHash = "c".repeat(40), sources = emptyList())
        f.shelf.rows[book.id] = ShelfEntry(book.id, NarrioJson.encodeToString(Audiobook.serializer(), played), state = "listening", sourceJson = "x")
        f.tracker.begin(book, waiting(), "M4B")
        assertEquals("Karen Savage", f.row()!!.book().narrator)
        f.status = { _, id -> ready(id) }
        val update = f.tracker.check(book.id, book)!!
        assertEquals(played, f.row()!!.book())
        assertEquals(READY, f.row()!!.state)
        assertEquals("Ashleighjane", update.book!!.narrator)
        assertEquals(prepared(), f.tracker.current(book.id)!!.ready!!.sources)
    }

    @Test fun aBookRemovedDuringACheckStaysRemoved() = runTest {
        val f = Fixture()
        f.tracker.begin(book, waiting(), "M4B")
        f.status = { _, id -> ready(id) }
        val listing = CompletableDeferred<Unit>()
        f.listing = { listing.await() }
        val check = async { f.tracker.check(book.id, book) }
        testScheduler.advanceUntilIdle()
        f.shelf.rows.remove(book.id)
        listing.complete(Unit)
        assertNull(check.await())
        assertNull(f.row()); assertTrue(f.announced.isEmpty())

        // Removed while the result was being saved.
        f.listing = {}
        f.tracker.begin(book, waiting(), "M4B")
        f.shelf.commitGate = CompletableDeferred()
        val saving = async { f.tracker.check(book.id, book) }
        testScheduler.advanceUntilIdle()
        assertTrue(f.shelf.committing)
        f.shelf.rows.remove(book.id)
        f.shelf.commitGate!!.complete(Unit)
        assertNull(saving.await()!!.change)
        assertNull(f.row()); assertTrue(f.announced.isEmpty())
        assertFalse(book.id in f.records.records)
    }

    @Test fun aNewerPreparationIsNeverOverwrittenByAnOlderCheck() = runTest {
        val f = Fixture()
        f.tracker.begin(book, waiting(7), "M4B")
        f.status = { _, id -> if (id == 7L) ready(7) else waiting(id) }
        val listing = CompletableDeferred<Unit>()
        f.listing = { listing.await() }
        val check = async { f.tracker.check(book.id, book) }
        testScheduler.advanceUntilIdle()
        f.tracker.begin(book, waiting(9), "MP3")
        listing.complete(Unit)
        check.await()
        assertEquals(PREPARING, f.row()!!.state)
        assertEquals(PreparationKey(9, "MP3"), f.row()!!.preparationKey)
        assertTrue("Torrent 7 being ready means nothing now", f.announced.isEmpty())

        // A row another writer changes while a result is being saved keeps the newer state.
        f.listing = {}
        f.status = { _, id -> ready(id) }
        f.shelf.commitGate = CompletableDeferred()
        val saving = async { f.tracker.check(book.id, book) }
        testScheduler.advanceUntilIdle()
        assertTrue(f.shelf.committing)
        f.shelf.rows[book.id] = f.row()!!.copy(preparationId = 11, pendingFormat = "M4B")
        f.shelf.commitGate!!.complete(Unit)
        assertNull(saving.await()!!.change)
        assertEquals(PREPARING, f.row()!!.state)
        assertEquals(PreparationKey(11, "M4B"), f.row()!!.preparationKey)
        assertTrue(f.announced.isEmpty())
    }

    @Test fun cancellingACheckCantSeparateSavingFromAnnouncing() = runTest {
        val f = Fixture()
        f.tracker.begin(book, waiting(), "M4B")
        f.status = { _, id -> ready(id) }
        f.shelf.commitGate = CompletableDeferred()
        val worker = launch { f.tracker.checkAll(true, PreparationRound()) }
        testScheduler.advanceUntilIdle()
        assertTrue(f.shelf.committing)
        // WorkManager cancels the round as soon as nothing is preparing any more.
        worker.cancel()
        f.shelf.commitGate!!.complete(Unit)
        worker.join()
        assertEquals(READY, f.row()!!.state)
        assertEquals(1, f.announced.size)
        assertTrue(f.records.records.getValue(book.id).announced)
    }

    @Test fun anOutcomeSavedBeforeTheProcessStoppedIsAnnouncedOnceLater() = runTest {
        val f = Fixture()
        f.tracker.begin(book, waiting(), "M4B")
        f.shelf.rows[book.id] = f.row()!!.copy(state = READY)
        f.records.records[book.id] = f.records.records.getValue(book.id).copy(outcome = READY, readySources = prepared(), announced = false)
        f.tracker.deliverPending(); f.tracker.deliverPending()
        assertEquals(listOf(true), f.announced.map { it.ready })

        // A newer preparation replaced it before it could be announced: dropped.
        f.records.records[book.id] = f.records.records.getValue(book.id).copy(torrentId = 3, announced = false)
        f.tracker.deliverPending()
        assertEquals(1, f.announced.size)
    }

    @Test fun startingAgainResetsHistoryButAskingForTheSameItemKeepsIt() = runTest {
        val f = Fixture()
        val first = f.tracker.begin(book, waiting(), "M4B")
        assertEquals(first.generation, f.tracker.begin(book, waiting(), "M4B").generation)
        f.status = { _, id -> Preparation(id, false, 0f, "Waiting for TorBox", missing = true) }
        repeat(3) { f.tracker.check(book.id, book); f.now += 6 * minute }
        assertEquals(FAILED, f.row()!!.state)
        assertFalse(f.announced.single().ready)

        // The same release asked for again after failing is a new preparation with no earlier misses.
        val again = f.tracker.begin(book, waiting(), "M4B")
        assertNotEquals(first.generation, again.generation)
        assertEquals(PreparationWatch(), again.watch)
        assertEquals(PREPARING, f.row()!!.state)
        assertFalse(f.tracker.check(book.id, book)!!.preparation.failed)
    }

    @Test fun onlyThePreparedRecordingCompletesItsPreparation() = runTest {
        val f = Fixture()
        f.tracker.begin(book, waiting(), "M4B")
        f.status = { _, id -> ready(id) }
        f.tracker.check(book.id, book)
        val otherRecording = AudioSource("archive:m4b", "Whole-book audio", "M4B", listOf(AudioPart("a", "garden.m4b", "Whole book")))
        assertFalse(f.tracker.played(book.id, otherRecording))
        assertEquals(READY, f.row()!!.state)
        assertTrue(f.tracker.played(book.id, prepared().single()))
        assertEquals("listening", f.row()!!.state)
        assertNull(f.tracker.current(book.id))
    }

    @Test fun aSilencedNotificationNeverHoldsUpTheShelf() = runTest {
        val f = Fixture()
        f.tracker.announce = { throw SecurityException("Notifications aren't allowed") }
        f.tracker.begin(book, waiting(), "M4B")
        f.status = { _, id -> ready(id) }
        assertNotNull(f.tracker.check(book.id, book)!!.change)
        assertEquals(READY, f.row()!!.state)
        assertTrue(f.records.records.getValue(book.id).announced)
    }

    @Test fun backgroundRoundsListTheAccountOnceAndStopWithAReasonOnTheShelf() = runTest {
        val f = Fixture()
        f.tracker.begin(book, waiting(7), "M4B"); f.tracker.begin(other, waiting(8), "M4B")
        assertEquals(PreparationRound(1, 0), f.tracker.checkAll(true, PreparationRound()))
        assertEquals("One listing for both books", 1, f.listings)

        // A book whose status can't be read is paused alone; the other keeps its normal pace.
        f.status = { b, id -> if (b.id == other.id) error("unreadable") else waiting(id) }
        repeat(PreparationPolicy.MAX_BOOK_ERRORS - 1) { assertEquals(PreparationRound(2, 0), f.tracker.checkAll(true, PreparationRound(1, 0))) }
        assertEquals(PREPARING, f.row(other.id)!!.state)
        f.tracker.checkAll(true, PreparationRound(1, 0))
        assertEquals(PAUSED, f.row(other.id)!!.state)
        assertEquals(PREPARING, f.row()!!.state)
        assertEquals(PreparationPolicy.UNREADABLE, f.tracker.placeholder(f.row(other.id)!!)!!.problem)

        // TorBox unreachable: rounds back off, then stop and say so.
        f.listing = { throw ProviderException("TorBox is unavailable (503).") }
        assertEquals(PreparationRound(5, 3), f.tracker.checkAll(true, PreparationRound(4, 2)))
        assertNull(f.tracker.checkAll(true, PreparationRound(9, PreparationPolicy.MAX_ERROR_RUNS - 1)))
        assertEquals(PAUSED, f.row()!!.state)
        assertTrue(f.tracker.placeholder(f.row()!!)!!.paused)

        // Reconnecting resumes everything paused, with a fresh error count.
        f.tracker.resumeAll()
        assertEquals(setOf(PREPARING), f.shelf.rows.values.map { it.state }.toSet())
        assertEquals(0, f.records.records.getValue(other.id).watch.errors)

        f.listing = { throw ProviderAuthorizationException("TorBox could not authorize this request.") }
        assertNull(f.tracker.checkAll(true, PreparationRound(1, 0)))
        assertEquals(PreparationPolicy.KEY_REJECTED, f.tracker.placeholder(f.row()!!)!!.problem)
        f.tracker.resumeAll()

        f.listing = {}
        val before = f.listings
        assertNull("Disconnected TorBox isn't contacted", f.tracker.checkAll(false, PreparationRound()))
        assertEquals(before, f.listings)
        assertEquals(PreparationPolicy.DISCONNECTED, f.tracker.placeholder(f.row()!!)!!.problem)
        f.tracker.resumeAll()

        f.status = { _, id -> ready(id) }
        assertNull("Everything ready ends the checks", f.tracker.checkAll(true, PreparationRound(1, 0)))
        assertEquals(setOf(READY), f.shelf.rows.values.map { it.state }.toSet())
        assertEquals(2, f.announced.count { it.ready })
    }

    @Test fun aRoundWorkManagerKeptRestartingStopsLikeAnUnreachableOne() = runTest {
        val f = Fixture()
        f.tracker.begin(book, waiting(), "M4B")
        assertNull(f.tracker.checkAll(true, PreparationRound(3, PreparationPolicy.MAX_ERROR_RUNS)))
        assertEquals(0, f.listings)
        assertEquals(PAUSED, f.row()!!.state)
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
            server.enqueue(list("downloading"))
            val account = client.account()
            assertFalse(account.preparation(book, 7).missing)
            assertTrue("A queued request is found by its hash", !account.preparation(book, 0).missing)
            assertEquals("Several books, one request", 4, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { client.refresh(book, 7) }.exceptionOrNull() is ProviderAuthorizationException)
        } finally { server.shutdown() }
    }
}
