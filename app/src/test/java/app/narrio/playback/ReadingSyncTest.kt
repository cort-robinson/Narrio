package app.narrio.playback

import app.narrio.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReadingSyncTest {
    private val line = TextPassage("p", "one two three four five six seven eight nine ten", "chapter", 0)
    private val doc = BookText("edition", "Book", "Author", "TXT", "", listOf(TextChapter("c", "Chapter", listOf(line))))
    private val source = AudioSource("recording", "", "MP3", listOf(AudioPart("part", "", "", 120_000)))
    private val book = Audiobook("book", "Book", "Author", sources = listOf(source))
    private val target = SyncTarget(book, source, source.parts.single(), 50_000, 120_000, doc, null)
    private val wanted = ContentCursor(doc.id, "chapter", 14) // Start of "four".
    private fun engine(positions: SharedPositionStore = FakeSharedPositions()) = ReadingSync(positions,
        NarrationPositionMapper(object : PositionMappingRepository {
            override suspend fun snapshot(bookId: String, sourceId: String) = MappingSnapshot(doc, source, emptyList())
        }), object : PositionMappingRepository {
            override suspend fun snapshot(bookId: String, sourceId: String) = MappingSnapshot(doc, source, emptyList())
        }, object : AlignmentJobRepository {
            override suspend fun progress(key: AlignmentKey) = AlignmentProgress(key)
            override suspend fun save(progress: AlignmentProgress) = Unit
        })
    private val anchors = listOf(TextAnchor("p", 40_000, word = 0, auto = true), TextAnchor("p", 60_000, word = 9, auto = true))

    @Test fun oneRecognizedWindowCorrectsOnlyBracketedTextAndMarksExact() = runTest {
        val sync = engine()
        var calls = 0
        var saved: TextBinding? = null
        val corrected = sync.correct(target, wanted, NarrationWindowRecognizer { _, start ->
            calls++; assertEquals(40_000, start); RecognizedWindow(WindowOutcome.MATCHED, anchors)
        }, { target }) { _, binding -> saved = binding; binding }
        assertEquals(1, calls); assertNotNull(saved)
        assertEquals(MappingConfidence.EXACT, corrected!!.confidence)
        assertTrue(corrected.audio.positionMs in 40_001..59_999)
        assertFalse(sync.state.value.correcting)
    }
    @Test fun unavailableUnsupportedAndUnmatchedWindowsStayEstimated() = runTest {
        for (outcome in listOf(WindowOutcome.UNAVAILABLE, WindowOutcome.UNSUPPORTED, WindowOutcome.NO_MATCH)) {
            val sync = engine()
            val corrected = sync.correct(target, wanted, NarrationWindowRecognizer { _, _ -> RecognizedWindow(outcome) }, { target }) { _, _ -> error("No match must not save anchors") }
            assertNull(corrected); assertEquals(MappingConfidence.ESTIMATED, sync.state.value.confidence)
            assertFalse(sync.state.value.correcting)
        }
    }
    @Test fun lateRecognitionCannotSeekChangedRecordingOrEdition() = runTest {
        val sync = engine()
        val corrected = sync.correct(target, wanted, NarrationWindowRecognizer { _, _ -> RecognizedWindow(WindowOutcome.MATCHED, anchors) },
            { target.copy(source = source.copy(id = "different")) }) { _, _ -> error("Obsolete results must not save") }
        assertNull(corrected)
    }
    @Test fun extrapolatedTextCannotBecomeExactAfterRecognition() = runTest {
        val sync = engine()
        val corrected = sync.correct(target, wanted.copy(offset = 47), NarrationWindowRecognizer { _, _ -> RecognizedWindow(WindowOutcome.MATCHED, anchors) }, { target }) { _, binding -> binding }
        assertNull(corrected)
    }
    @Test fun droppedAnchorsAfterConcurrentManualMatchCannotConfirmCorrection() = runTest {
        val sync = engine()
        val corrected = sync.correct(target, wanted, NarrationWindowRecognizer { _, _ -> RecognizedWindow(WindowOutcome.MATCHED, anchors) },
            { target }) { _, binding -> binding.copy(anchors = emptyList()) }
        assertNull(corrected)
        assertEquals(MappingConfidence.ESTIMATED, sync.state.value.confidence)
    }
    @Test fun listeningCommitRejectsStaleSequenceInsteadOfRetryingOverNewerReading() = runTest {
        val positions = FakeSharedPositions()
        positions.commit(PositionUpdate(book.id, PositionOrigin.READING, text = wanted, basedOnSequence = 0))
        val sync = engine(positions)
        assertNull(sync.listeningCommit(book.id, AudioCursor(source.id, "part", 5000), 0))
        assertEquals(PositionOrigin.READING, positions.current(book.id)!!.origin)
        assertNotNull(sync.listeningCommit(book.id, AudioCursor(source.id, "part", 5000), 1))
        assertEquals(PositionOrigin.LISTENING, positions.current(book.id)!!.origin)
    }
}
