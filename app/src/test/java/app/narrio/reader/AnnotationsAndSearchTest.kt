package app.narrio.reader

import app.narrio.data.NarrioJson
import app.narrio.domain.ContentCursor
import org.junit.Assert.*
import org.junit.Test

class AnnotationsAndSearchTest {
    private fun cursor(offset: Int, resource: String = "OEBPS/text/chapter1.xhtml") = ContentCursor("edition", resource, offset)

    @Test fun matchingIgnoresCaseAccentsAndTypographicQuotesWithoutMovingOffsets() {
        val text = "“Don’t,” said MARTHA. The café was closed; the Cafe opened later."
        assertEquals("every character folds to exactly one", text.length, TextSearch.fold(text).length)
        assertEquals(listOf(text.indexOf("Don’t")), TextSearch.find(text, TextSearch.normalizeQuery("don't")))
        assertEquals(listOf(text.indexOf("café"), text.indexOf("Cafe")), TextSearch.find(text, TextSearch.normalizeQuery("CAFE")))
        assertEquals(listOf(text.indexOf("MARTHA")), TextSearch.find(text, TextSearch.normalizeQuery("  martha ")))
        assertEquals("matches don't overlap", listOf(0, 2), TextSearch.find("aaaa", "aa"))
        assertEquals(listOf(0), TextSearch.find("aaaa", "aa", limit = 1))
        assertTrue(TextSearch.find("short", "").isEmpty())
    }

    @Test fun snippetsCutAtWordsAndMarkTrimmedContext() {
        val text = "one two three four five six seven eight nine ten eleven twelve thirteen"
        val start = text.indexOf("seven")
        val (before, match, after) = TextSearch.snippet(text, start, start + 5, context = 12)
        assertEquals("seven", match)
        assertTrue(before, before.startsWith("…") && before.endsWith("six "))
        assertFalse("no partial word before", before.removePrefix("…").startsWith("ve"))
        assertTrue(after, after.endsWith("…"))
        val whole = TextSearch.snippet("tiny match here", 5, 10)
        assertEquals("tiny ", whole.first); assertEquals(" here", whole.third)
    }

    /** A hit's offsets must select the same characters the reader indexes and decorates. */
    @Test fun searchHitsOverTheServedIndexAreExactContentRanges() {
        val files = unzip(ReaderFixtures.sampleEpub())
        val resource = "OEBPS/text/chapter1.xhtml"
        val index = ReaderDocuments.prepareChapter(files.getValue(resource), resource, xhtml = true, EditionKind.EPUB).index!!
        val needle = TextSearch.normalizeQuery("Misselthwaite Manor")
        val hits = index.blocks.flatMap { block -> TextSearch.find(block.text, needle).map { block.offset + it } }
        assertTrue(hits.isNotEmpty())
        val blocks = index.blocks.map { it.offset to it.text }
        for (start in hits) assertEquals("Misselthwaite Manor", rangeText(blocks, start, start + needle.length))
        // The same range resolves to a decoration quote whose highlighted text is the match itself.
        val quote = CursorMapping.rangeQuotes(index, hits.first(), hits.first() + needle.length).single()
        assertEquals("Misselthwaite Manor", quote.highlight)
    }

    @Test fun rangeTextJoinsBlocksAndTrimsTheirEdges() {
        val blocks = listOf(0 to "First paragraph here.", 22 to "Second paragraph follows.")
        assertEquals("paragraph here.\nSecond", rangeText(blocks, 6, 28))
        assertEquals("", rangeText(blocks, 100, 120))
    }

    @Test fun highlightsStoreAnExportReadyRowAndReadBack() {
        val start = cursor(10).copy(progression = .4, locatorJson = "{\"cache\":true}")
        val highlight = Highlight("h1", "book", CursorRange(start, cursor(24)), HighlightColor.GREEN, "  A note  ", "the passage", 1L, 2L)
        val entry = highlight.entry()
        assertEquals("green", entry.color)
        assertEquals("A note", entry.note)
        assertEquals("edition", entry.editionId)
        val stored = NarrioJson.decodeFromString<ContentCursor>(entry.startCursorJson)
        assertEquals("caches never become identity", cursor(10), stored)
        val back = Highlight.of(entry)!!
        assertEquals(HighlightColor.GREEN, back.color)
        assertEquals(CursorRange(cursor(10), cursor(24)), back.range)
        assertEquals(HighlightColor.YELLOW, HighlightColor.of("ultraviolet"))
    }

    @Test fun notesAddAnUnderlineAndColoursFollowTheMode() {
        val plain = Highlight("a", "book", CursorRange(cursor(0), cursor(4)), HighlightColor.BLUE, createdAtMs = 0, updatedAtMs = 0)
        val noted = plain.copy(id = "b", note = "why")
        val (day, night) = highlightDecorations(listOf(plain, noted), dark = false) to highlightDecorations(listOf(plain, noted), dark = true)
        assertEquals(listOf(false, true), day.map { it.active })
        assertEquals(HighlightColor.BLUE.day, day[0].tint)
        assertEquals(HighlightColor.BLUE.night, night[0].tint)
        assertNotEquals(HIGHLIGHT_GROUP, SEARCH_GROUP)
    }
}
