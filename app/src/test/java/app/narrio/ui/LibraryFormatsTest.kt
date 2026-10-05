package app.narrio.ui

import app.narrio.domain.*
import org.junit.Assert.*
import org.junit.Test

class LibraryFormatsTest {
    private val edition = EbookEdition("e1", "b", "Book", "Author", "EPUB", active = true)
    private val parts = AudioSource("s", "Parts", "MP3", List(12) { AudioPart("p$it", "p$it.mp3", "Part ${it + 1}", durationMs = 600_000) })
    private fun listening(partId: String = "p2", positionMs: Long = 0, text: ContentCursor? = null, textConfidence: MappingConfidence = MappingConfidence.UNMAPPED) =
        SharedPosition("b", PositionOrigin.LISTENING, text = text, audio = AudioCursor("s", partId, positionMs), textConfidence = textConfidence)
    private fun reading(progression: Double, audio: AudioCursor? = null, audioConfidence: MappingConfidence = MappingConfidence.UNMAPPED) =
        SharedPosition("b", PositionOrigin.READING, text = ContentCursor("e1", "ch12.xhtml", 40, progression = progression), audio = audio, audioConfidence = audioConfidence)

    @Test fun audiobooksAndEbooksIncludePairsWhileBothNarrowsToThem() {
        val audio = BookFormats("a", audio = true)
        val ebook = BookFormats("e", editions = listOf(edition))
        val both = BookFormats("b", audio = true, editions = listOf(edition))
        val neither = BookFormats("n")
        val shelf = listOf(audio, ebook, both, neither)
        assertEquals(listOf("a", "e", "b", "n"), shelf.filter(ShelfFilter.ALL::matches).map { it.bookId })
        assertEquals(listOf("a", "b"), shelf.filter(ShelfFilter.AUDIOBOOKS::matches).map { it.bookId })
        assertEquals(listOf("e", "b"), shelf.filter(ShelfFilter.EBOOKS::matches).map { it.bookId })
        assertEquals(listOf("b"), shelf.filter(ShelfFilter.BOTH::matches).map { it.bookId })
    }

    @Test fun readingPlaceShowsChapterAndPercent() {
        val formats = BookFormats("b", editions = listOf(edition), position = reading(.437), textChapter = 12)
        assertEquals("Ch 12 · 43%", placeSummary(formats)!!.label)
        assertEquals("Chapter 12, 43%", spokenPlace("Ch 12 · 43%"))
        assertEquals("43%", placeSummary(formats.copy(textChapter = null))!!.label)
    }

    @Test fun startedBooksNeverReadZeroAndUnfinishedNeverReadHundred() {
        val formats = BookFormats("b", editions = listOf(edition), textChapter = 1)
        assertEquals("Ch 1 · 0%", placeSummary(formats.copy(position = reading(0.0)))!!.label)
        assertEquals("Ch 1 · 1%", placeSummary(formats.copy(position = reading(.002)))!!.label)
        assertEquals("Ch 1 · 99%", placeSummary(formats.copy(position = reading(.9996)))!!.label)
        assertEquals("Ch 1 · 100%", placeSummary(formats.copy(position = reading(1.0)))!!.label)
    }

    @Test fun listeningPlaceUsesPartsAndWholeBookProgressOnlyWhenEveryLengthIsKnown() {
        val formats = BookFormats("b", audio = true, position = listening("p2", 300_000), audioSource = parts)
        val known = placeSummary(formats)!!
        assertEquals("Part 3 of 12 · 20%", known.label)
        assertEquals(.208f, known.fraction!!, .001f)
        val unknown = parts.copy(parts = parts.parts.mapIndexed { i, part -> if (i == 5) part.copy(durationMs = 0) else part })
        val partial = placeSummary(formats.copy(audioSource = unknown))!!
        assertEquals("Part 3 of 12 · 5:00 in", partial.label)
        assertNull(partial.fraction)
        val single = AudioSource("one", "Whole", "M4B", listOf(AudioPart("m", "b.m4b", "Book")))
        assertEquals("1:02:03 in", placeSummary(BookFormats("b", audio = true, position = listening("m", 3_723_000), audioSource = single))!!.label)
    }

    @Test fun counterpartCarriesItsEstimateAndIsHiddenWhenUnmappedOrMismatched() {
        val cursor = ContentCursor("e1", "ch12.xhtml", 40, progression = .43)
        val both = BookFormats("b", audio = true, editions = listOf(edition), textChapter = 12, audioSource = parts,
            position = listening("p2", 300_000, cursor, MappingConfidence.ESTIMATED))
        val other = counterpartPlace(both)!!
        assertEquals(PositionOrigin.READING, other.mode)
        assertEquals("≈ Ch 12 · 43%", approximate(other.label, other.confidence))
        assertEquals("about Chapter 12, 43%, estimated", spokenApproximate(other.label, other.confidence))
        assertEquals("Ch 12 · 43%", approximate(other.label, MappingConfidence.EXACT))
        assertNull(counterpartPlace(both.copy(position = listening("p2", 300_000, cursor, MappingConfidence.UNMAPPED))))
        assertNull(counterpartPlace(both.copy(pairing = PairingStatus.MISMATCH)))
        // An ebook-only book has no listening counterpart even if a stale audio cursor remains.
        assertNull(counterpartPlace(BookFormats("b", editions = listOf(edition), position = reading(.5, AudioCursor("s", "p1", 0), MappingConfidence.EXACT))))
    }

    @Test fun continueReopensTheLastModeOnlyWhileThatFormatIsStillHere() {
        val both = BookFormats("b", audio = true, editions = listOf(edition), position = reading(.4))
        assertEquals(PositionOrigin.READING, both.lastMode)
        assertEquals(PositionOrigin.READING, both.leadingMode)
        // The ebook was removed: listening leads, and the reading place no longer claims Continue.
        val audioOnly = both.copy(editions = emptyList())
        assertNull(audioOnly.lastMode)
        assertEquals(PositionOrigin.LISTENING, audioOnly.leadingMode)
        assertEquals(PositionOrigin.READING, BookFormats("e", editions = listOf(edition)).leadingMode)
        assertNull(BookFormats("n").leadingMode)
    }

    @Test fun activeEditionPrefersTheMarkedOne() {
        val other = edition.copy(id = "e2", active = false)
        assertEquals("e1", BookFormats("b", editions = listOf(other, edition)).activeEdition!!.id)
        assertEquals("e2", BookFormats("b", editions = listOf(other)).activeEdition!!.id)
    }

    @Test fun pairingIsDescribedOnlyForABookWithBothFormats() {
        assertNull(pairingCopy(BookFormats("b", editions = listOf(edition), pairing = PairingStatus.MATCHES)))
        val both = BookFormats("b", audio = true, editions = listOf(edition))
        assertEquals(PairingStatus.entries.size, PairingStatus.entries.map { pairingCopy(both.copy(pairing = it))!!.title }.distinct().size)
    }

    @Test fun unsupportedEbookFilesNameTheReason() {
        assertTrue(unsupportedEbook("Dune.azw3").startsWith("Kindle files"))
        assertTrue(unsupportedEbook("scan.PDF").startsWith("PDF reading"))
        assertEquals("Choose an EPUB or a UTF-8 .txt ebook.", unsupportedEbook("notes.docx"))
    }
}
