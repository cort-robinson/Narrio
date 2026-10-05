package app.narrio.reader

import app.narrio.data.BookTextParser
import app.narrio.domain.TextPassage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextIndexTest {
    /** Every follow-along passage must land on its own text in the served chapter: the cursor space is shared. */
    private fun assertPassagesMap(epub: ByteArray) {
        val files = unzip(epub)
        val book = BookTextParser.parse(epub, "EPUB", "Fixture")
        val passages = book.chapters.flatMap { it.passages }.groupBy(TextPassage::resource)
        assertTrue(passages.isNotEmpty())
        for ((resource, lines) in passages) {
            val index = ReaderDocuments.prepareChapter(files.getValue(resource), resource, xhtml = true, EditionKind.EPUB).index!!
            for (line in lines) {
                val block = index.blockAt(line.offset)!!
                assertTrue("${line.id} is inside its block", line.offset in block.offset..block.end)
                assertTrue("${line.id}: '${block.text.substring(line.offset - block.offset).take(40)}' vs '${line.text.take(40)}'",
                    block.text.substring(line.offset - block.offset).startsWith(line.text))
            }
        }
    }

    @Test fun sampleBookPassagesMapExactly() = assertPassagesMap(ReaderFixtures.sampleEpub())

    @Test fun markupTheHtmlParserRestructuresStillMapsExactly() {
        val chapter = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>T</title><style>p { color: black }</style></head>
<body>
<h1>Chapter One<br/>The   Beginning</h1>
<p>An <a id="anchor"/>anchor, a <span epub:type="pagebreak" id="p4" title="4"/>page break, and&#160;&#160;spaces.</p>
<p>Soft&#173;hyphen, zero&#8203;width, emoji 😀 and <em>em<strong>phasis</strong></em>.</p>
<div/><p>After an empty div.</p>
<blockquote><p>Quoted</p><p>twice</p></blockquote>
<ul><li><p>Item</p><p>parts</p></li><li>Second</li></ul>
<pre>  keep   these
  spaces</pre>
<p>Inline <svg xmlns="http://www.w3.org/2000/svg" width="4" height="4"><text>hidden</text></svg>svg and <style>.x{}</style>style.</p>
<p><!-- a comment -->Comment<![CDATA[ cdata ]]> end.</p>
<table><tr><td>Cell <b>one</b></td><th>Head</th></tr></table>
<div>Loose text in a div<p>then a paragraph</p></div>
</body></html>"""
        assertPassagesMap(ReaderFixtures.epub("Tricky", listOf("tricky.xhtml" to chapter)))
    }

    @Test fun everyRawPositionMapsBackToTheSameCharacter() {
        val files = unzip(ReaderFixtures.sampleEpub(6))
        val index = ReaderDocuments.prepareChapter(files.getValue("OEBPS/text/chapter1.xhtml"), "OEBPS/text/chapter1.xhtml", true, EditionKind.EPUB).index!!
        for (block in index.blocks) for (raw in block.raw.indices) {
            if (block.raw[raw].isWhitespace()) continue
            val offset = CursorMapping.offset(index, PagePosition(block.offset, raw))
            val quote = CursorMapping.quote(index, offset)!!
            assertEquals(block.selector, quote.selector)
            // The quote starts at exactly that character, so restoring lands where the page began.
            assertEquals(block.raw.substring(raw).take(quote.highlight.length), quote.highlight)
            assertEquals(block.raw.substring(0, raw).takeLast(quote.before.length), quote.before)
        }
    }

    @Test fun inlinePageBreaksAndTocTargetsResolveToTheirCharacter() {
        val files = unzip(ReaderFixtures.sampleEpub())
        val index = ReaderDocuments.prepareChapter(files.getValue("OEBPS/text/chapter1.xhtml"), "OEBPS/text/chapter1.xhtml", true, EditionKind.EPUB).index!!
        val heading = index.anchor("c1")
        assertEquals(0, heading)
        val page = index.anchor("page1")!!
        val block = index.blockAt(page)!!
        val paragraph = ReaderFixtures.sampleParagraphs[2].split(' ')
        assertTrue(block.text.substring(page - block.offset).startsWith(paragraph.drop(paragraph.size / 2).joinToString(" ")))
    }

    @Test fun rangesSplitAcrossBlocksForDecorations() {
        val files = unzip(ReaderFixtures.sampleEpub(4))
        val index = ReaderDocuments.prepareChapter(files.getValue("OEBPS/text/chapter2.xhtml"), "OEBPS/text/chapter2.xhtml", true, EditionKind.EPUB).index!!
        val first = index.blocks[1]
        val second = index.blocks[2]
        val quotes = CursorMapping.rangeQuotes(index, first.end - 10, second.offset + 12)
        assertEquals(listOf(first.selector, second.selector), quotes.map { it.selector })
        assertEquals(first.text.takeLast(10), quotes[0].highlight)
        assertEquals(second.text.take(12), quotes[1].highlight)
    }

    @Test fun servedSampleChaptersAreWellFormedAndKeepFootnotesAsPopups() {
        val files = unzip(ReaderFixtures.sampleEpub())
        val third = ReaderDocuments.prepareChapter(files.getValue("OEBPS/text/chapter3.xhtml"), "OEBPS/text/chapter3.xhtml", true, EditionKind.EPUB)
        assertWellFormedXml(third.bytes)
        val html = third.bytes.toString(Charsets.UTF_8)
        // The Gutenberg-style reference opens the note's paragraph; the note's own back-link stays a plain link.
        assertTrue(html, html.contains("""id="FNanchor_1_1" href="#narrio-note-Footnote_1_1" class="fnanchor" epub:type="noteref""""))
        assertTrue(html.contains("""<p id="narrio-note-Footnote_1_1" """))
        assertTrue(html.contains("""<a id="Footnote_1_1" href="#FNanchor_1_1">"""))
        assertNotNull(third.index)
    }
}
