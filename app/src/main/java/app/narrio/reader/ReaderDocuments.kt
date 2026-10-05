package app.narrio.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream

/** Where an edition's offsets come from: the EPUB parser's block rules, or offsets written into a plain-text EPUB. */
enum class EditionKind { EPUB, TXT }

/**
 * Prepares each publication resource before the reader serves it: sanitizes HTML, SVG and CSS, marks likely
 * footnote references, and for chapters, records the text index that maps reader positions to content cursors.
 */
object ReaderDocuments {
    class Prepared(val bytes: ByteArray, val index: ResourceTextIndex?)

    /** The font family name of the Narrio pairing: Manrope text with Newsreader headings. */
    const val NARRIO_PAIRING = "NarrioPairing"
    private const val PAIRING_CSS = ":root[style*=\"readium-font-on\"][style*=\"$NARRIO_PAIRING\"] h1, :root[style*=\"readium-font-on\"][style*=\"$NARRIO_PAIRING\"] h2, " +
        ":root[style*=\"readium-font-on\"][style*=\"$NARRIO_PAIRING\"] h3, :root[style*=\"readium-font-on\"][style*=\"$NARRIO_PAIRING\"] h4, " +
        ":root[style*=\"readium-font-on\"][style*=\"$NARRIO_PAIRING\"] h5, :root[style*=\"readium-font-on\"][style*=\"$NARRIO_PAIRING\"] h6 " +
        "{ font-family: Newsreader, serif !important; letter-spacing: -0.01em; }"
    private const val EPUB_NAMESPACE = "http://www.idpf.org/2007/ops"
    private const val XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml"

    /** [resource] is the parser's resource name for this chapter; [xhtml] selects XML parsing and serialization. */
    fun prepareChapter(bytes: ByteArray, resource: String, xhtml: Boolean, kind: EditionKind): Prepared {
        val display = if (xhtml) Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())
            else Jsoup.parse(ByteArrayInputStream(bytes), null, "")
        val root = display.children().firstOrNull { it.normalName().substringAfter(':') == "html" }
        EpubSanitizer.sanitize(display)
        val index = if (root == null) null else {
            // Readium writes its own style attribute onto <html>; a second one would make the XHTML invalid.
            root.removeAttr("style")
            if (xhtml && !root.hasAttr("xmlns")) root.attr("xmlns", XHTML_NAMESPACE)
            markFootnotes(display)
            // Readium adds its default stylesheet only to unstyled chapters; don't change which chapters those are.
            val head = display.getElementsByTag("head").firstOrNull()
            if (head != null && !head.html().contains(NARRIO_PAIRING) && (display.select("link[rel~=(?i)stylesheet], style").isNotEmpty() || display.select("[style]").any { it !== root }))
                head.appendElement("style").attr("type", "text/css").appendText(PAIRING_CSS)
            if (display.getAllElements().any { element -> element.attributes().any { it.key.startsWith("epub:") } } && !root.hasAttr("xmlns:epub"))
                root.attr("xmlns:epub", EPUB_NAMESPACE)
            when (kind) {
                EditionKind.EPUB -> ResourceIndexer.annotateEpub(resource, Jsoup.parse(ByteArrayInputStream(bytes), null, ""), display)
                EditionKind.TXT -> ResourceIndexer.indexMarked(resource, display)
            }
        }
        return Prepared(serialize(display, xhtml), index)
    }

    fun prepareSvg(bytes: ByteArray): ByteArray {
        val document = Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())
        EpubSanitizer.sanitize(document)
        return serialize(document, xml = true)
    }

    fun prepareCss(bytes: ByteArray): ByteArray = EpubSanitizer.sanitizeCss(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")).toByteArray(Charsets.UTF_8)

    private fun serialize(document: Document, xml: Boolean): ByteArray {
        document.outputSettings().prettyPrint(false).charset(Charsets.UTF_8).escapeMode(Entities.EscapeMode.xhtml)
        if (xml) document.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
        // Whitespace left between removed prolog nodes would otherwise accumulate before the root element.
        document.childNodes().filter { it is TextNode && it.isBlank }.forEach { it.remove() }
        val body = document.outerHtml()
        return (if (xml) "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n$body" else body).toByteArray(Charsets.UTF_8)
    }

    private val noteLabel = Regex("^[\\[(]?(?:\\d{1,4}|[*†‡§¶#]{1,3}|[a-z]|[ivxlc]{1,6})[\\])]?\\.?$", RegexOption.IGNORE_CASE)
    private val noteClass = Regex("noteref|fnanchor|footnote|endnote|fn-?ref|note-?ref", RegexOption.IGNORE_CASE)

    /**
     * Readium shows a footnote pop-up for `epub:type="noteref"` links. Many EPUB 2 books, including Project
     * Gutenberg's, mark notes only with classes or bracketed numbers; mark those too. A note's own back-link opens
     * its paragraph and is left alone. When a note target is only its label, point the link at the label's paragraph
     * so the pop-up shows the note text.
     */
    private fun markFootnotes(document: Document) {
        for (link in document.select("a[href]")) {
            val href = link.attr("href")
            if (!href.contains('#') || EpubSanitizer.isRemote(href)) continue
            val types = link.attr("epub:type").split(Regex("\\s+"))
            val label = link.text().trim()
            val looksLikeNote = "noteref" in types || noteClass.containsMatchIn(link.className()) ||
                (noteLabel.matches(label) && (link.parents().any { it.normalName() == "sup" } || link.selectFirst("sup") != null || label.startsWith("[") || label.startsWith("(")))
            if (!looksLikeNote || startsItsBlock(link)) continue
            if ("noteref" !in types) link.attr("epub:type", (types.filter { it.isNotBlank() } + "noteref").joinToString(" "))
            if (!href.startsWith("#")) continue
            val target = document.getElementById(href.removePrefix("#")) ?: continue
            if (target.text().trim().length > 8) continue
            val note = target.parents().firstOrNull { it.normalName() in setOf("p", "li", "dd", "aside", "div") && it.text().length in 1..4000 } ?: continue
            if (note.id().isBlank()) note.id("narrio-note-${target.id()}")
            link.attr("href", "#${note.id()}")
        }
    }

    private fun startsItsBlock(link: Element): Boolean {
        val block = link.parents().firstOrNull { it.normalName() in setOf("p", "li", "dd", "dt", "div", "aside", "td") } ?: return false
        val before = StringBuilder()
        for (node in block.textNodesBefore(link)) before.append(node.wholeText)
        return before.isBlank()
    }

    private fun Element.textNodesBefore(stop: Element): List<TextNode> {
        val nodes = mutableListOf<TextNode>()
        var reached = false
        org.jsoup.select.NodeTraversor.traverse({ node, _ ->
            if (node === stop) reached = true
            if (!reached && node is TextNode) nodes += node
        }, this)
        return nodes
    }
}
