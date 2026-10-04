package app.narrio.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test

class FakeSharedPositions : SharedPositionStore {
    private val state = MutableStateFlow<SharedPosition?>(null)
    override fun observe(bookId: String): Flow<SharedPosition?> = state
    override suspend fun current(bookId: String) = state.value
    override suspend fun commit(update: PositionUpdate): SharedPosition? {
        if (update.basedOnSequence != (state.value?.sequence ?: 0L)) return null
        return SharedPosition(update.bookId, update.origin, update.text, update.audio, update.textConfidence, update.audioConfidence,
            update.basedOnSequence + 1).also { state.value = it }
    }
}

class SyncDecisionsTest {
    private val source = AudioSource("audio", "", "MP3", listOf(AudioPart("one", "", "", 120_000), AudioPart("two", "", "", 120_000)))
    private val cursor = ContentCursor("edition", "chapter", 20)
    private val audio = AudioCursor("audio", "one", 60_000)
    private fun shared(origin: PositionOrigin = PositionOrigin.READING) = SharedPosition("book", origin, cursor, audio, MappingConfidence.EXACT, MappingConfidence.EXACT, 7)
    @Test fun exactThresholdIsSilentAndLargerJumpOffersUndo() {
        val mapped = MappedAudio(audio, MappingConfidence.ESTIMATED)
        val silent = SyncDecisions.listening(shared(), source, mapped, audio.copy(positionMs = 30_000), PairingStatus.MATCHES)
        assertFalse(silent.offerUndo)
        val jump = SyncDecisions.listening(shared(), source, mapped, audio.copy(positionMs = 29_999), PairingStatus.MATCHES)
        assertTrue(jump.offerUndo); assertEquals(7, jump.sequence); assertEquals(MappingConfidence.ESTIMATED, jump.confidence)
    }
    @Test fun multipartDistanceUsesDurations() {
        val mapped = MappedAudio(AudioCursor("audio", "two", 5_000), MappingConfidence.ESTIMATED)
        assertFalse(SyncDecisions.listening(shared(), source, mapped, audio.copy(positionMs = 115_000), PairingStatus.PARTIAL).offerUndo)
        assertTrue(SyncDecisions.listening(shared(), source, mapped, audio.copy(positionMs = 10_000), PairingStatus.PARTIAL).offerUndo)
    }
    @Test fun switchingRecordingMapsSharedTextInsteadOfBorrowingAudioClock() {
        val other = source.copy(id = "new")
        val mapped = MappedAudio(AudioCursor("new", "two", 8_000), MappingConfidence.ESTIMATED)
        assertEquals(mapped.audio, SyncDecisions.listening(shared(PositionOrigin.LISTENING), other, mapped, null, PairingStatus.PARTIAL).destination)
        assertNull(SyncDecisions.listening(shared(PositionOrigin.LISTENING), other, null, null, PairingStatus.UNCHECKED).destination)
    }
    @Test fun listeningOriginPreservesItsSameRecordingClock() {
        assertEquals(audio, SyncDecisions.listening(shared(PositionOrigin.LISTENING), source,
            MappedAudio(audio.copy(positionMs = 1), MappingConfidence.ESTIMATED), null, PairingStatus.MATCHES).destination)
    }
    @Test fun mismatchedPairsDisableModeAndRecordingSync() {
        assertNull(SyncDecisions.listening(shared(), source, MappedAudio(audio, MappingConfidence.EXACT), null, PairingStatus.MISMATCH).destination)
        assertNull(SyncDecisions.reading(shared(), "edition", MappedText(cursor, MappingConfidence.EXACT), null, 0, PairingStatus.MISMATCH).destination)
    }
    @Test fun readingJumpNeedsMoreThanOnePageAndActiveEdition() {
        val previous = cursor.copy(offset = 1)
        assertFalse(SyncDecisions.reading(shared(), "edition", null, previous, 1, PairingStatus.MATCHES).offerUndo)
        assertTrue(SyncDecisions.reading(shared(), "edition", null, previous, 2, PairingStatus.MATCHES).offerUndo)
        assertNull(SyncDecisions.reading(shared(), "different", null, previous, 2, PairingStatus.MATCHES).destination)
    }
    @Test fun staleWriterCannotOverwriteNewerActionAndAudioOwnsItsBook() = runTest {
        val delegate = FakeSharedPositions()
        val store = AudioOwnedPositionStore(delegate)
        val reading = PositionUpdate("book", PositionOrigin.READING, text = cursor, basedOnSequence = 0)
        store.playingBookId = "book"
        assertNull(store.commit(reading))
        val first = store.commit(reading.copy(origin = PositionOrigin.LISTENING, audio = audio))!!
        assertEquals(1, first.sequence)
        store.playingBookId = null
        assertNull(store.commit(reading))
        assertEquals(2, store.commit(reading.copy(basedOnSequence = 1))!!.sequence)
    }
    @Test fun playbackActivityIgnoresPauseAndResetsOnRecordingSwitch() {
        val gate = ListeningActivityGate()
        assertFalse(gate.sample("book", "one", true, 0))
        assertFalse(gate.sample("book", "one", true, 5_000))
        assertFalse(gate.sample("book", "one", false, 6_000))
        assertFalse(gate.sample("book", "one", true, 100_000))
        assertTrue(gate.sample("book", "one", true, 105_000))
        assertFalse(gate.sample("book", "one", true, 110_000))
        assertFalse(gate.sample("book", "two", true, 115_000))
        assertFalse(gate.sample("book", "two", true, 120_000))
        assertTrue(gate.sample("book", "two", true, 125_000))
    }
    @Test fun queuedReadingCommitRechecksOwnershipAtStoreBoundary() = runTest {
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backing = FakeSharedPositions()
        val delegate = object : GuardedSharedPositionStore {
            override fun observe(bookId: String) = backing.observe(bookId)
            override suspend fun current(bookId: String) = backing.current(bookId)
            override suspend fun commit(update: PositionUpdate) = backing.commit(update)
            override suspend fun commitWhen(update: PositionUpdate, allowed: () -> Boolean): SharedPosition? {
                ready.complete(Unit); release.await()
                return if (allowed()) backing.commit(update) else null
            }
        }
        val store = AudioOwnedPositionStore(delegate)
        val pending = async { store.commit(PositionUpdate("book", PositionOrigin.READING, text = cursor, basedOnSequence = 0)) }
        ready.await()
        store.playingBookId = "book"
        release.complete(Unit)
        assertNull(pending.await()); assertNull(store.current("book"))
    }
}
