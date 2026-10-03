package app.narrio.data

import app.narrio.domain.*
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Parses inert text only. EPUB scripts, styles, images, and remote resources never execute. */
object BookTextParser {
    const val MAX_FILE_BYTES = 20 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 48 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024
    private const val MAX_PASSAGES = 60_000

    fun readBounded(input: InputStream, limit: Int = MAX_FILE_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > limit) throw ProviderException("This book text is too large. Choose a smaller EPUB or text file (up to 20 MB).")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun parse(bytes: ByteArray, format: String, title: String, author: String = "", attribution: String = "Imported from your device"): BookText {
        if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) throw ProviderException("Choose a nonempty EPUB, UTF-8 text, or WebVTT file up to 20 MB.")
        val document = when (format.uppercase()) {
            "EPUB" -> epub(bytes, title, author, attribution)
            "TXT" -> plain(bytes, title, author, attribution)
            "VTT" -> vtt(bytes, title, author, attribution)
            else -> throw ProviderException("This file type isn't supported. Choose an EPUB, UTF-8 .txt, or .vtt file.")
        }
        val count = document.chapters.sumOf { it.passages.size }
        if (count == 0) throw ProviderException("This file contains no readable book text. Choose another edition.")
        if (count > MAX_PASSAGES) throw ProviderException("This book contains too many passages for follow along. Choose a smaller file.")
        return document
    }

    private fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: java.nio.charset.CharacterCodingException) { throw ProviderException("This text isn't UTF-8. Export it as UTF-8 text or choose an EPUB.") }

    private val heading = Regex("^(?:chapter|part|book)[ \\t]+(?:[\\p{L}]+|[0-9]+)(?:[.: \\t–—-].*)?$", RegexOption.IGNORE_CASE)

    private fun plain(bytes: ByteArray, title: String, author: String, attribution: String): BookText {
        var text = utf8(bytes).replace("\r\n", "\n").replace('\r', '\n')
        if (text.indexOf('\u0000') >= 0 || Regex("(?is)^\\s*(?:<!doctype\\s+html|<html\\b)").containsMatchIn(text))
            throw ProviderException("This isn't a plain-text book. Choose a UTF-8 .txt file or an EPUB.")
        val start = Regex("(?im)^\\*\\*\\* START OF (?:THE|THIS) PROJECT GUTENBERG.*?\\*\\*\\*[^\\n]*\\n").find(text)
        if (start != null) text = text.substring(start.range.last + 1)
        val end = Regex("(?im)^\\*\\*\\* END OF (?:THE|THIS) PROJECT GUTENBERG.*").find(text)
        if (end != null) text = text.substring(0, end.range.first)
        val chapters = mutableListOf<TextChapter>()
        var chapterTitle = title
        var chapterStart = 0
        val lines = mutableListOf<TextPassage>()
        fun finish() {
            if (lines.isNotEmpty()) chapters += TextChapter("text@$chapterStart", chapterTitle, lines.toList())
            lines.clear()
        }
        val paragraph = StringBuilder()
        var paragraphOffset = 0
        var offset = 0
        fun finishParagraph() {
            if (paragraph.isNotEmpty()) lines += splitPassages(paragraph.toString(), "text", paragraphOffset)
            paragraph.clear()
        }
        for (raw in text.split('\n')) {
            val value = raw.trim()
            when {
                heading.matches(value) -> { finishParagraph(); finish(); chapterTitle = value; chapterStart = offset }
                value.isBlank() -> finishParagraph()
                else -> {
                    if (paragraph.isEmpty()) paragraphOffset = offset
                    else paragraph.append('\n')
                    paragraph.append(raw)
                }
            }
            offset += raw.length + 1
        }
        finishParagraph()
        finish()
        return BookText(fingerprint(bytes), title, author, "TXT", attribution, chapters)
    }

    private fun epub(bytes: ByteArray, fallbackTitle: String, fallbackAuthor: String, attribution: String): BookText {
        val files = mutableMapOf<String, ByteArray>()
        var expanded = 0L
        var entries = 0
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > 2048) throw ProviderException("This EPUB has too many files. Choose a simpler edition.")
                val path = safePath(entry.name)
                if (!names.add(path)) throw ProviderException("This EPUB has duplicate file paths. Choose another edition.")
                val keep = path.substringAfterLast('.').lowercase() in setOf("xml", "opf", "xhtml", "html", "htm")
                val output = if (keep) ByteArrayOutputStream() else null
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expanded += count
                    if (expanded > MAX_EXPANDED_BYTES || (output?.size()?.toLong() ?: 0) + count > MAX_TEXT_BYTES)
                        throw ProviderException("This EPUB expands beyond the follow-along size limit. Choose a smaller edition.")
                    output?.write(buffer, 0, count)
                }
                if (output != null) files[path] = output.toByteArray()
            }
        }
        fun xml(path: String) = files[path]?.let { Jsoup.parse(ByteArrayInputStream(it), null, "", Parser.xmlParser()) }
            ?: throw ProviderException("This EPUB is missing a required book file. Choose another edition.")
        files["META-INF/encryption.xml"]?.let { data ->
            val encryption = Jsoup.parse(data.toString(Charsets.UTF_8), "", Parser.xmlParser())
            if (encryption.getAllElements().filter { it.tagName().substringAfter(':') == "EncryptionMethod" }.any { it.attr("Algorithm") !in setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC") })
                throw ProviderException("Encrypted or DRM-protected ebooks aren't supported. Choose a DRM-free EPUB or text file.")
        }
        val packagePath = xml("META-INF/container.xml").getElementsByTag("rootfile").firstOrNull()?.attr("full-path")?.let(::safePath)
            ?: throw ProviderException("This isn't a readable EPUB. Choose another file.")
        val opf = xml(packagePath)
        val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()?.ifBlank { fallbackTitle } ?: fallbackTitle
        val author = opf.getElementsByTag("dc:creator").joinToString(", ") { it.text() }.ifBlank { fallbackAuthor }
        val manifest = opf.getElementsByTag("item").associateBy { it.attr("id") }
        val chapters = mutableListOf<TextChapter>()
        var totalPassages = 0
        for (ref in opf.getElementsByTag("itemref").filter { it.attr("linear") != "no" }) {
            val item = manifest[ref.attr("idref")] ?: throw ProviderException("This EPUB has an incomplete reading order.")
            if (item.attr("properties").split(' ').contains("nav")) continue
            if (item.attr("media-type") !in setOf("application/xhtml+xml", "text/html")) continue
            val path = resolvePath(packagePath, item.attr("href"))
            val html = files[path]?.let { Jsoup.parse(ByteArrayInputStream(it), null, "") }
                ?: throw ProviderException("This EPUB is missing a chapter. Choose another edition.")
            html.select("script,style,nav,svg,#pg-header,#pg-footer,#pg-start-separator,#pg-end-separator,.pg-boilerplate").remove()
            if (html.body().attr("epub:type").split(' ').any { it in setOf("toc", "cover", "titlepage", "copyright-page") }) continue
            val blocks = html.body().select("h1,h2,h3,h4,p,li,blockquote,pre,td,th").filter { node ->
                node.parents().none { it.tagName() in setOf("p", "li", "blockquote", "pre", "td", "th") }
            }
            var chapterTitle = html.selectFirst("h1,h2,h3")?.text()?.take(200)?.ifBlank { "Chapter ${chapters.size + 1}" } ?: "Chapter ${chapters.size + 1}"
            var offset = 0
            var section = 0
            var hasBody = false
            val lines = mutableListOf<TextPassage>()
            fun finish() {
                if (lines.isNotEmpty()) chapters += TextChapter("$path#$section", chapterTitle, lines.toList())
                lines.clear(); section++; hasBody = false
            }
            for (node in blocks) {
                val value = node.text().trim()
                if (value.isBlank()) continue
                val isHeading = node.tagName() in setOf("h1", "h2") || heading.matches(value)
                if (isHeading && hasBody) { finish(); chapterTitle = value.take(200) }
                lines += splitPassages(value, path, offset)
                offset += value.length + 1
                if (!node.tagName().startsWith("h")) hasBody = true
                if (++totalPassages > MAX_PASSAGES) throw ProviderException("This EPUB contains too many text blocks.")
            }
            if (blocks.isEmpty()) lines += splitPassages(html.body().text(), path, 0)
            finish()
        }
        return BookText(fingerprint(bytes), title, author, "EPUB", attribution, chapters)
    }

    private fun safePath(path: String): String {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.contains(':') || path.split('/').any { it == ".." })
            throw ProviderException("This EPUB contains an unsafe file path. Choose another edition.")
        return path.removePrefix("./").trimEnd('/')
    }

    private fun resolvePath(packagePath: String, href: String): String {
        val uri = runCatching { URI(packagePath).resolve(href).normalize() }.getOrElse { throw ProviderException("This EPUB has an unreadable chapter link.") }
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null) throw ProviderException("This EPUB links to external chapters. Choose an EPUB with its text included.")
        return safePath(URLDecoder.decode(uri.rawPath.replace("+", "%2B"), "UTF-8"))
    }

    private fun vtt(bytes: ByteArray, title: String, author: String, attribution: String): BookText {
        val text = utf8(bytes).replace("\r\n", "\n").replace('\r', '\n')
        if (!Regex("^WEBVTT(?:[ \\t].*)?(?:\\n|$)").containsMatchIn(text)) throw ProviderException("This isn't a WebVTT timing track. Choose a .vtt file with timestamps.")
        val lines = mutableListOf<TextPassage>()
        var previousEnd = 0L
        for (block in text.split(Regex("\\n[ \\t]*\\n"))) {
            val rows = block.lines()
            if (rows.first().startsWith("NOTE") || rows.first() in setOf("STYLE", "REGION")) continue
            val index = rows.indexOfFirst { "-->" in it }
            if (index < 0) continue
            val timing = rows[index].trim().split(Regex("\\s+"))
            if (timing.size < 3 || timing[1] != "-->") throw ProviderException("This timing track contains an invalid cue.")
            val start = timestamp(timing[0])
            val end = timestamp(timing[2])
            if (start < previousEnd || end <= start) throw ProviderException("Use a timing track with ordered, non-overlapping cues.")
            val html = Jsoup.parse(rows.drop(index + 1).joinToString(" "))
            html.select("script,style").remove()
            val value = html.text().trim()
            if (value.isNotBlank()) lines += TextPassage("vtt@${lines.size}", value, "vtt", lines.size, start, end)
            previousEnd = end
        }
        return BookText(fingerprint(bytes), title, author, "VTT", attribution, listOf(TextChapter("vtt", "Timed text", lines)))
    }

    private fun timestamp(value: String): Long {
        val match = Regex("(?:(\\d{2,}):)?([0-5]\\d):([0-5]\\d)\\.(\\d{3})").matchEntire(value)
            ?: throw ProviderException("This timing track has an invalid timestamp.")
        return ((match.groupValues[1].toLongOrNull() ?: 0) * 3600 + match.groupValues[2].toLong() * 60 + match.groupValues[3].toLong()) * 1000 + match.groupValues[4].toLong()
    }

    private fun splitPassages(value: String, resource: String, baseOffset: Int): List<TextPassage> {
        val text = value.replace(Regex("\\s+"), " ").trim()
        val output = mutableListOf<TextPassage>()
        var offset = 0
        while (offset < text.length) {
            val remaining = text.substring(offset)
            val sentence = Regex("[.!?][\"'”’)]*(?:\\s+|$)").find(remaining)
            var length = sentence?.range?.last?.plus(1) ?: remaining.length
            if (length > 320) length = remaining.take(320).lastIndexOf(' ').takeIf { it > 0 } ?: 320
            val line = remaining.take(length).trim()
            if (line.isNotBlank()) output += TextPassage("$resource@${baseOffset + offset}", line, resource, baseOffset + offset)
            offset += length
            while (offset < text.length && text[offset].isWhitespace()) offset++
        }
        return output
    }
}
