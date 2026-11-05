package app.narrio.data

import app.narrio.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookTextParserTest {
    private fun epub(extra: Map<String, String> = emptyMap(), href: String = "chapter.xhtml"): ByteArray {
        val files = linkedMapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
            "OPS/book.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>A Book</dc:title><dc:creator>An Author</dc:creator></metadata><manifest><item id="two" href="later.xhtml" media-type="application/xhtml+xml"/><item id="one" href="$href" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="nav"/><itemref idref="one"/><itemref idref="two"/></spine></package>""",
            "OPS/chapter.xhtml" to """<html><body><h2>Chapter I</h2><p>A quiet &amp; green garden. She opened the gate.</p><blockquote><p>The birds were singing.</p></blockquote><script>private code</script></body></html>""",
            "OPS/later.xhtml" to """<html><body><h2>Chapter II</h2><p>Later, they returned.</p></body></html>""",
            "OPS/nav.xhtml" to """<html><body><nav>Not narrated</nav></body></html>""",
        ).apply { putAll(extra) }
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            files.forEach { (path, content) -> zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry() }
        } }.toByteArray()
    }

    @Test fun epubUsesSpineOrderDecodesEntitiesAndPreservesStableTextLocators() {
        val bytes = epub()
        val document = BookTextParser.parse(bytes, "EPUB", "Fallback")
        assertEquals("A Book", document.title)
        assertEquals("An Author", document.author)
        assertEquals(listOf("Chapter I", "Chapter II"), document.chapters.map { it.title })
        val lines = document.chapters.flatMap { it.passages }
        assertTrue(lines.any { it.text == "A quiet & green garden." })
        assertEquals(1, lines.count { it.text == "The birds were singing." })
        assertFalse(lines.any { it.text.contains("private code") || it.text.contains("Not narrated") })
        assertEquals(lines.size, lines.map { it.id }.toSet().size)
        assertTrue(lines.first().resource == "OPS/chapter.xhtml")
        assertEquals(document, BookTextParser.parse(bytes, "EPUB", "Fallback"))
    }

    @Test fun epubRejectsTraversalAndDrmWithoutResolvingRemoteResources() {
        assertTrue(runCatching { BookTextParser.parse(epub(href = "../../outside.xhtml"), "EPUB", "Title") }.exceptionOrNull() is ProviderException)
        assertTrue(runCatching { BookTextParser.parse(epub(href = "https://example.com/book.xhtml"), "EPUB", "Title") }.exceptionOrNull() is ProviderException)
        val encrypted = epub(mapOf("META-INF/encryption.xml" to """<encryption><EncryptionMethod Algorithm="http://example.com/drm"/></encryption>"""))
        assertTrue(runCatching { BookTextParser.parse(encrypted, "EPUB", "Title") }.exceptionOrNull()?.message.orEmpty().contains("DRM"))
    }

    /** A ZIP whose entries are stored uncompressed with trailing data descriptors, which [java.util.zip.ZipInputStream] rejects. */
    private fun storedWithDescriptors(files: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        fun u16(value: Int) { output.write(value and 0xFF); output.write((value shr 8) and 0xFF) }
        fun u32(value: Long) { u16((value and 0xFFFF).toInt()); u16(((value shr 16) and 0xFFFF).toInt()) }
        class Entry(val name: ByteArray, val data: ByteArray, val crc: Long, val offset: Long)
        val entries = files.map { (path, content) ->
            val name = path.toByteArray(); val data = content.toByteArray()
            val entry = Entry(name, data, java.util.zip.CRC32().apply { update(data) }.value, output.size().toLong())
            u32(0x04034b50); u16(20); u16(0x08); u16(0); u16(0); u16(0); u32(0); u32(0); u32(0); u16(name.size); u16(0)
            output.write(name); output.write(data)
            u32(0x08074b50); u32(entry.crc); u32(data.size.toLong()); u32(data.size.toLong())
            entry
        }
        val directoryStart = output.size().toLong()
        for (entry in entries) {
            u32(0x02014b50); u16(20); u16(20); u16(0x08); u16(0); u16(0); u16(0); u32(entry.crc); u32(entry.data.size.toLong()); u32(entry.data.size.toLong())
            u16(entry.name.size); u16(0); u16(0); u16(0); u16(0); u32(0); u32(entry.offset); output.write(entry.name)
        }
        val directorySize = output.size() - directoryStart
        u32(0x06054b50); u16(0); u16(0); u16(entries.size); u16(entries.size); u32(directorySize); u32(directoryStart); u16(0)
        return output.toByteArray()
    }

    @Test fun epubsThatStreamingZipReadersRejectAreStillDetectedAndRead() {
        val files = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""",
            "book.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>Stored</dc:title></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>""",
            "one.xhtml" to "<html><body><p>A stored chapter paragraph.</p></body></html>",
        )
        val stored = storedWithDescriptors(files)
        assertTrue(runCatching { java.util.zip.ZipInputStream(stored.inputStream()).use { while (it.nextEntry != null) Unit } }.isFailure)
        assertEquals("EPUB", BookTextParser.detect(stored))
        assertEquals("Stored", BookTextParser.parse(stored, "EPUB", "Fallback").title)

        // Bytes before the archive, as a self-extracting or wrapped download has, shift every recorded offset.
        val prefixed = "junk before the archive".toByteArray() + epub()
        assertEquals("EPUB", BookTextParser.detect(prefixed))
        assertEquals("A Book", BookTextParser.parse(prefixed, "EPUB", "Fallback").title)

        assertNull(BookTextParser.detect("%PDF-1.7 not an ebook".toByteArray()))
        assertEquals("a PDF", BookTextParser.describe("%PDF-1.7 not an ebook".toByteArray()))
        assertEquals("a Kindle (MOBI/AZW) file", BookTextParser.describe(ByteArray(60) + "BOOKMOBI".toByteArray()))
        assertNull(BookTextParser.describe("plain words".toByteArray()))
    }

    @Test fun plainTextRemovesGutenbergBoilerplateAndRecognizesSingleNewlineHeadings() {
        val text = """License header
*** START OF THE PROJECT GUTENBERG EBOOK A BOOK ***
CHAPTER I

The garden was quiet.
She walked outside.

CHAPTER II
Another morning began.

*** END OF THE PROJECT GUTENBERG EBOOK A BOOK ***
License footer"""
        val document = BookTextParser.parse(text.toByteArray(), "TXT", "A Book")
        assertEquals(listOf("CHAPTER I", "CHAPTER II"), document.chapters.map { it.title })
        val all = document.chapters.flatMap { it.passages }.joinToString(" ") { it.text }
        assertTrue(all.contains("Another morning began."))
        assertFalse(all.contains("License") || all.contains("GUTENBERG"))
    }

    @Test fun invalidTextAndOversizedInputLeaveNoPartialDocument() {
        assertTrue(runCatching { BookTextParser.parse(byteArrayOf(0xFF.toByte()), "TXT", "Title") }.exceptionOrNull() is ProviderException)
        assertTrue(runCatching { BookTextParser.parse("<html>Server error</html>".toByteArray(), "TXT", "Title") }.exceptionOrNull() is ProviderException)
        assertTrue(runCatching { BookTextParser.readBounded(ByteArray(5).inputStream(), 4) }.exceptionOrNull() is ProviderException)
    }

    @Test fun webVttRetainsCueGapsAndRejectsOverlappingOrMalformedTiming() {
        val valid = "WEBVTT\n\nopening\n00:02.000 --> 00:05.000\n<v Reader>Hello <b>garden</b>.</v>\n\n00:07.000 --> 00:09.000\nA second line.\n"
        val document = BookTextParser.parse(valid.toByteArray(), "VTT", "A Book").copy(timedSourceId = "source", timedPartId = "part")
        val binding = TextBinding(document.id, "source", "part", "vtt")
        val timeline = FollowAlongTiming.timeline(document, binding, 10_000)!!
        assertEquals("Hello garden.", timeline.passages.first().text)
        assertNull(timeline.activeIndex(1000))
        assertEquals(0, timeline.activeIndex(2000))
        assertNull(timeline.activeIndex(5000))
        assertEquals(1, timeline.activeIndex(8000))
        assertNull(timeline.activeIndex(9000))
        assertTrue(runCatching { BookTextParser.parse(valid.replace("00:07.000", "00:04.000").toByteArray(), "VTT", "Title") }.exceptionOrNull() is ProviderException)
        assertTrue(runCatching { BookTextParser.parse(valid.replace("00:02.000", "bad-time").toByteArray(), "VTT", "Title") }.exceptionOrNull() is ProviderException)
    }
}
