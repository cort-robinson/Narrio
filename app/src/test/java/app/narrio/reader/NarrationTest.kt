package app.narrio.reader

import app.narrio.domain.*
import app.narrio.playback.SyncPhase
import org.junit.Assert.*
import org.junit.Test

class NarrationTest {
    private val first = TextPassage("text@0", "The morning light fell softly.", "text", 0)
    private val second = TextPassage("text@31", "Mary opened the small wooden gate.", "text", 31)
    private val third = TextPassage("text@66", "A robin waited above her head.", "text", 66)
    private val doc = BookText("edition", "Book", "Author", "TXT", "", listOf(TextChapter("c", "Chapter", listOf(first, second, third))))
    private val source = AudioSource("recording", "", "MP3", listOf(AudioPart("part", "part.mp3", "Book", 30_000)))
    private fun at(offset: Int, confidence: MappingConfidence) = MappedText(ContentCursor(doc.id, "text", offset), confidence)

    @Test fun narratedSentenceSpansItsPassageAndTheWordOnlyWhenExact() {
        val place = Narration.place(doc, at(31 + 5, MappingConfidence.EXACT), words = true)!!
        assertEquals(second.id, place.passageId)
        assertEquals(31, place.sentence.start.offset)
        assertEquals(31 + second.text.length, place.sentence.end.offset)
        // "opened" starts at index 5 of the passage.
        assertEquals(36, place.word!!.start.offset)
        assertEquals(42, place.word!!.end.offset)
        assertNull("estimated narration never claims a word", Narration.place(doc, at(36, MappingConfidence.ESTIMATED), words = true)!!.word)
        assertNull("word marks are a setting", Narration.place(doc, at(36, MappingConfidence.EXACT), words = false)!!.word)
    }

    @Test fun supplyTimingTracksAndOtherEditionsNeverShowAWordOrPlace() {
        val timed = doc.copy(timedSourceId = source.id, timedPartId = "part")
        assertNull(Narration.place(timed, at(36, MappingConfidence.EXACT), words = true)!!.word)
        assertNull(Narration.place(doc, MappedText(ContentCursor("other", "text", 36), MappingConfidence.EXACT), words = true))
        assertNull(Narration.place(doc, at(36, MappingConfidence.UNMAPPED), words = true))
        assertNull(Narration.place(doc, null, words = true))
    }

    @Test fun tapsPlayFromTheirSentenceStart() {
        assertEquals(66, Narration.sentenceStart(doc, ContentCursor(doc.id, "text", 80))!!.offset)
        // A tap between sentences plays the next one.
        assertEquals(31, Narration.sentenceStart(doc, ContentCursor(doc.id, "text", 30))!!.offset)
        assertNull(Narration.sentenceStart(doc, ContentCursor(doc.id, "elsewhere", 3)))
    }

    @Test fun wordsSkipWhitespace() {
        assertEquals(0..2, Narration.wordAt("The light", 0))
        assertEquals(4..8, Narration.wordAt("The light", 3))
        assertNull(Narration.wordAt("The light", 20))
    }

    @Test fun theMediaClockMapsThePlayingPartOnly() {
        val anchors = TextBinding(doc.id, source.id, "part", WHOLE_BOOK, listOf(TextAnchor(first.id, 0, auto = true), TextAnchor(third.id, 20_000, auto = true)))
        val mapped = Narration.mapped(doc, source, 0, 30_000, listOf(anchors), 10_000)!!
        assertEquals(MappingConfidence.EXACT, mapped.confidence)
        assertEquals(second.id, Narration.place(doc, mapped, true)!!.passageId)
        assertEquals(MappingConfidence.ESTIMATED, Narration.mapped(doc, source, 0, 30_000, emptyList(), 10_000)!!.confidence)
        assertNull("an unknown part maps nothing", Narration.mapped(doc, source, 3, 30_000, emptyList(), 10_000))
        val ambiguous = source.copy(parts = listOf(AudioPart("a", "a.mp3", "Intro", 30_000), AudioPart("b", "b.mp3", "Other", 30_000)))
        assertNull("an ambiguous multipart layout waits for a chapter", Narration.mapped(doc, ambiguous, 1, 30_000, emptyList(), 10_000))
    }

    @Test fun aPlaceBeyondTheAnchorsMapsToAudioAndBackToItsOwnSentence() {
        // Uneven sentences: word pace and character pace disagree, as in real prose.
        val texts = (0 until 40).map { (if (it < 20) "a " else "considerably ").repeat(4 + it % 9).trim() + "." }
        val offsets = texts.runningFold(0) { at, text -> at + text.length + 1 }
        val lines = texts.mapIndexed { index, text -> TextPassage("text@${offsets[index]}", text, "text", offsets[index]) }
        val book = doc.copy(chapters = listOf(TextChapter("c", "Chapter", lines)))
        val long = source.copy(parts = listOf(source.parts.single().copy(durationMs = 600_000)))
        val anchors = TextBinding(book.id, long.id, "part", WHOLE_BOOK, (0 until 20 step 3).map { TextAnchor(lines[it].id, it * 4_000L, auto = true) })
        val snapshot = MappingSnapshot(book, long, listOf(anchors))
        for (index in listOf(25, 32, 39)) {
            val audio = MappingEngine.audioFor(snapshot, ContentCursor(book.id, "text", lines[index].offset))!!
            assertEquals(MappingConfidence.ESTIMATED, audio.confidence)
            val back = Narration.mapped(book, long, 0, 600_000, listOf(anchors), audio.audio.positionMs)
            assertEquals("passage $index", lines[index].id, Narration.place(book, back, false)!!.passageId)
        }
    }

    @Test fun audioLeadsFromListeningOrWhilePlayingAndOnlyFarMovesOfferUndo() {
        assertTrue(ReadAlongDecisions.audioLeads(fromListening = true, playing = false))
        assertTrue(ReadAlongDecisions.audioLeads(fromListening = false, playing = true))
        assertFalse(ReadAlongDecisions.audioLeads(fromListening = false, playing = false))
        val here = ContentCursor(doc.id, "chapter1.xhtml", 400)
        assertFalse(ReadAlongDecisions.offersUndo(here, here.copy(offset = 1_200)))
        assertTrue(ReadAlongDecisions.offersUndo(here, here.copy(offset = 4_000)))
        assertTrue(ReadAlongDecisions.offersUndo(here, here.copy(resource = "chapter2.xhtml", offset = 0)))
        assertFalse("nothing to undo without an earlier place", ReadAlongDecisions.offersUndo(null, here))
    }

    @Test fun statusNeverPresentsEstimatesAsConfident() {
        val exact = Narration.place(doc, at(36, MappingConfidence.EXACT), false)
        val estimated = Narration.place(doc, at(36, MappingConfidence.ESTIMATED), false)
        assertEquals(ReadAlongStatus("Synced with narration"), ReadAlongStatuses.of(PairingStatus.MATCHES, true, exact, SyncPhase.IDLE, false))
        assertTrue(ReadAlongStatuses.of(PairingStatus.PARTIAL, true, estimated, SyncPhase.IDLE, false).estimated)
        assertTrue(ReadAlongStatuses.of(PairingStatus.PARTIAL, true, estimated, SyncPhase.IDLE, correcting = true).estimated)
        assertFalse(ReadAlongStatuses.of(PairingStatus.MISMATCH, true, exact, SyncPhase.IDLE, false).highlight)
        assertFalse(ReadAlongStatuses.of(PairingStatus.MATCHES, false, exact, SyncPhase.IDLE, false).highlight)
        assertEquals("Finding this part in the book…", ReadAlongStatuses.of(PairingStatus.UNCHECKED, true, null, SyncPhase.LISTENING, false).text)
    }
}
