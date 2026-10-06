package app.narrio.reader

import app.narrio.data.BookTextParser
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Converts a plain-text book into a minimal EPUB so the same reader shows it. Headings and paragraphs come from
 * [BookTextParser.plainBlocks], and every block carries its offset in the parser's `"text"` space, so a reader
 * position in this EPUB is a [app.narrio.domain.ContentCursor] with resource `"text"` and the same offset as the
 * follow-along passages. Paragraph text is the parser's whitespace-collapsed text, character for character.
 *
 * Chapter files are named by the offset of their first block (`text/t0000012345.xhtml`) and split at paragraph
 * boundaries when long, so the reader can find the file for any offset without opening the book.
 */
object TxtEpub {
    const val RESOURCE = "text"
    private const val MAX_FILE_CHARS = 60_000
    private val fileName = Regex("(?:^|/)t(\\d{10})\\.xhtml$")

    /** The offset a chapter file starts at, or null when [href] isn't one of this converter's chapter files. */
    fun fileStart(href: String): Int? = fileName.find(href)?.groupValues?.get(1)?.toIntOrNull()

    class Chapter(val title: String, val href: String, val start: Int)

    fun convert(bytes: ByteArray, title: String, author: String, language: String = ""): ByteArray {
        val blocks = BookTextParser.plainBlocks(bytes)
        if (blocks.none { !it.heading }) throw app.narrio.data.ProviderException("This file contains no readable book text. Choose another edition.")
        val files = mutableListOf<Pair<String, List<BookTextParser.PlainBlock>>>()
        val chapters = mutableListOf<Chapter>()
        var current = mutableListOf<BookTextParser.PlainBlock>()
        var size = 0
        fun flush() {
            if (current.isEmpty()) return
            files += "text/t%010d.xhtml".format(current.first().offset) to current
            current = mutableListOf(); size = 0
        }
        for (block in blocks) {
            if (block.heading || size > MAX_FILE_CHARS) flush()
            if (current.isEmpty()) {
                val href = "text/t%010d.xhtml".format(block.offset)
                if (block.heading) chapters += Chapter(block.text, href, block.offset)
                else if (chapters.isEmpty()) chapters += Chapter(title, href, block.offset)
            }
            current += block
            size += block.text.length
        }
        flush()
        val id = "urn:narrio:text:" + BookTextParser.fingerprint(bytes)
        val lang = language.ifBlank { "und" }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.stored("mimetype", "application/epub+zip".toByteArray())
            zip.text("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val manifest = files.mapIndexed { index, (href, _) -> """<item id="c$index" href="$href" media-type="application/xhtml+xml"/>""" }.joinToString("\n")
            val spine = files.indices.joinToString("\n") { """<itemref idref="c$it"/>""" }
            zip.text("content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" xml:lang="${escape(lang)}"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">${escape(id)}</dc:identifier><dc:title>${escape(title)}</dc:title><dc:creator>${escape(author)}</dc:creator><dc:language>${escape(lang)}</dc:language>
<meta property="dcterms:modified">2000-01-01T00:00:00Z</meta></metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="css" href="text.css" media-type="text/css"/>
$manifest</manifest><spine>$spine</spine></package>""")
            zip.text("nav.xhtml", page(title, lang, """<nav epub:type="toc" id="toc"><h1>${escape(title)}</h1><ol>${chapters.joinToString("") { """<li><a href="${it.href}">${escape(it.title)}</a></li>""" }}</ol></nav>""", css = ""))
            zip.text("text.css", "p { margin: 0; text-indent: 1.4em; }\nh2 + p, p:first-child { text-indent: 0; }\nh2 { margin: 1.5em 0 1em; font-weight: normal; }\n")
            for ((href, content) in files) {
                val body = content.joinToString("\n") { block ->
                    val tag = if (block.heading) "h2" else "p"
                    """<$tag $OFFSET_ATTRIBUTE="${block.offset}">${escape(block.text)}</$tag>"""
                }
                zip.text(href, page(content.firstOrNull { it.heading }?.text ?: title, lang, body, css = """<link rel="stylesheet" type="text/css" href="../text.css"/>"""))
            }
        }
        return output.toByteArray()
    }

    private fun page(title: String, language: String, body: String, css: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${escape(language)}" lang="${escape(language)}"><head><meta charset="UTF-8"/><title>${escape(title)}</title>$css</head>
<body>
$body
</body></html>"""

    /** Escapes markup and replaces characters XML can't carry one-for-one, so offsets stay character-exact. */
    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 16)
        for (ch in value) when {
            ch == '&' -> out.append("&amp;")
            ch == '<' -> out.append("&lt;")
            ch == '>' -> out.append("&gt;")
            ch == '"' -> out.append("&quot;")
            ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> out.append('�')
            ch == '￾' || ch == '￿' -> out.append('�')
            else -> out.append(ch)
        }
        return out.toString()
    }

    private fun ZipOutputStream.stored(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = bytes.size.toLong(); time = 0
            crc = CRC32().apply { update(bytes) }.value
        }
        putNextEntry(entry); write(bytes); closeEntry()
    }

    private fun ZipOutputStream.text(name: String, value: String) {
        putNextEntry(ZipEntry(name).apply { time = 0 }); write(value.toByteArray(Charsets.UTF_8)); closeEntry()
    }
}
