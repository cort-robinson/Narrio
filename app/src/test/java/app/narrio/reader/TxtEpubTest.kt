package app.narrio.reader

import app.narrio.data.BookTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtEpubTest {
    private val text = buildString {
        append("﻿The Project Gutenberg eBook of A Test\r\n\r\n*** START OF THE PROJECT GUTENBERG EBOOK A TEST ***\r\n\r\n")
        append("A preface before any chapter, with  doubled   spaces\r\nand a wrapped line.\r\n\r\n")
        append("CHAPTER I. The Start\r\n\r\n")
        repeat(40) { append("   Indented paragraph $it has <markup> & \"quotes\", a non breaking space, and\ttabs.\r\nIt wraps onto a second line.\r\n\r\n") }
        append("  CHAPTER II  \r\n\r\nThe last paragraph.\r\n")
        append("*** END OF THE PROJECT GUTENBERG EBOOK A TEST ***\r\nLicense text.\r\n")
    }.toByteArray()

    @Test fun everyPassageKeepsItsTextOffsetInTheConvertedEpub() {
        val book = BookTextParser.parse(text, "TXT", "A Test")
        val files = unzip(TxtEpub.convert(text, "A Test", "An Author"))
        val chapters = files.keys.filter { TxtEpub.fileStart(it) != null }.sortedBy { TxtEpub.fileStart(it) }
        assertTrue(chapters.size >= 3)
        val indexes = chapters.map { ReaderDocuments.prepareChapter(files.getValue(it), TxtEpub.RESOURCE, true, EditionKind.TXT) }
        indexes.forEach { assertWellFormedXml(it.bytes) }
        val blocks = indexes.flatMap { it.index!!.blocks }
        for (passage in book.chapters.flatMap { it.passages }) {
            assertEquals("text", passage.resource)
            val block = blocks.last { it.offset <= passage.offset }
            assertTrue(passage.id, block.text.substring(passage.offset - block.offset).startsWith(passage.text))
        }
        // Files are named by the offset of their first block, so a cursor finds its file without opening the book.
        chapters.zip(indexes).forEach { (name, prepared) -> assertEquals(TxtEpub.fileStart(name), prepared.index!!.blocks.first().offset) }
    }

    @Test fun boilerplateIsLeftOutAndChaptersFormTheContents() {
        val files = unzip(TxtEpub.convert(text, "A Test", "An Author"))
        val all = files.values.joinToString { it.toString(Charsets.UTF_8) }
        assertTrue(!all.contains("PROJECT GUTENBERG") && !all.contains("License text."))
        val nav = files.getValue("nav.xhtml").toString(Charsets.UTF_8)
        assertTrue(nav, nav.contains(">A Test</a>") && nav.contains(">CHAPTER I. The Start</a>") && nav.contains(">CHAPTER II</a>"))
        assertTrue(files.getValue("text/t" + "%010d".format(TxtEpub.fileStart(files.keys.first { TxtEpub.fileStart(it) != null })!!) + ".xhtml").isNotEmpty())
    }

    @Test fun conversionIsDeterministic() {
        assertTrue(TxtEpub.convert(text, "A Test", "An Author").contentEquals(TxtEpub.convert(text, "A Test", "An Author")))
        assertNull(TxtEpub.fileStart("OEBPS/chapter1.xhtml"))
    }
}
