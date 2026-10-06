package app.narrio.playback

import app.narrio.data.BookmarkEntry
import app.narrio.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BookmarkPlacesTest {
    private val text = ContentCursor("edition", "OEBPS/ch1.xhtml", 120, progression = .2, locatorJson = "{}")
    private val audio = AudioCursor("recording", "part-2", 61_000)

    @Test fun readingBookmarksKeepTheListeningTimeOnlyWhenNarrationConfirmsIt() {
        val exact = newBookmark("book", "label", text = text, mappedAudio = MappedAudio(audio, MappingConfidence.EXACT))
        assertEquals(audio, exact.audioCursor())
        val estimated = newBookmark("book", "label", text = text, mappedAudio = MappedAudio(audio, MappingConfidence.ESTIMATED))
        assertNull("an estimate is mapped again later rather than frozen", estimated.audioCursor())
        assertEquals("", estimated.sourceId)
        assertEquals(text.offset, estimated.offset)
    }

    @Test fun listeningBookmarksKeepTheReadingPlaceOnlyWhenExact() {
        val exact = newBookmark("book", "Part 2", audio = audio, mappedText = MappedText(text, MappingConfidence.EXACT))
        assertEquals(text.offset, exact.offset)
        val estimated = newBookmark("book", "Part 2", audio = audio, mappedText = MappedText(text, MappingConfidence.ESTIMATED))
        assertNull(estimated.editionId)
        assertEquals(audio, estimated.audioCursor())
    }

    @Test fun missingPlacesAreMappedWithTheirConfidence() = runBlocking {
        val readOnly = newBookmark("book", "label", text = text)
        val mapped = resolveBookmark(readOnly, { MappedAudio(audio, MappingConfidence.ESTIMATED) }, { error("not needed") })
        assertEquals(MappingConfidence.EXACT, mapped.textConfidence)
        assertEquals(audio, mapped.audio)
        assertEquals("≈ shows on the mapped side", MappingConfidence.ESTIMATED, mapped.audioConfidence)

        // A pre-ebook audio bookmark has no reading columns at all.
        val legacy = BookmarkEntry(id = 7, bookId = "book", sourceId = "recording", partId = "part-2", positionMs = 61_000, label = "Part 2")
        val reading = resolveBookmark(legacy, { error("not needed") }, { MappedText(text, MappingConfidence.EXACT) })
        assertEquals(text.offset, reading.text?.offset)
        assertEquals(MappingConfidence.EXACT, reading.audioConfidence)

        val unmapped = resolveBookmark(legacy, { null }, { MappedText(text, MappingConfidence.UNMAPPED) })
        assertNull(unmapped.text)
        assertEquals(MappingConfidence.UNMAPPED, unmapped.textConfidence)
    }

    @Test fun eachModeListsBookmarksInItsOwnOrder() {
        fun place(id: Long, offset: Int?, part: String?, ms: Long) = BookmarkPlace(
            BookmarkEntry(id = id, bookId = "book", sourceId = "", partId = "", positionMs = 0, label = "", createdAt = id),
            offset?.let { ContentCursor("edition", "r", it, progression = it / 1000.0) }, MappingConfidence.EXACT,
            part?.let { AudioCursor("recording", it, ms) }, MappingConfidence.EXACT)
        val marks = listOf(place(1, 500, "b", 10), place(2, 100, null, 0), place(3, null, "a", 90), place(4, 300, "a", 20))
        assertEquals(listOf(2L, 4L, 1L, 3L), marks.inReadingOrder { it.offset.toLong() }.map { it.id })
        val parts = listOf(AudioPart("a", "Part 1", ""), AudioPart("b", "Part 2", ""))
        assertEquals(listOf(4L, 3L, 1L, 2L), marks.inListeningOrder(parts).map { it.id })
    }
}
