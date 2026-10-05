package app.narrio.reader

import app.narrio.data.EpubTextBlocks
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag

/** Attribute carrying a block's offset in the `BookTextParser` space of its resource. */
const val OFFSET_ATTRIBUTE = "data-narrio-o"

/**
 * One readable block as served to the reader. [raw] is its DOM `textContent`. [text] is the block text in the
 * coordinates `BookTextParser` gives passages: jsoup text with whitespace runs collapsed, starting at [offset].
 * The block occupies [span] offsets (its uncollapsed text length), which differs only for preformatted text.
 */
class IndexedBlock(val offset: Int, val text: String, val span: Int, val raw: String, private val rawToText: IntArray) {
    val selector: String get() = "[$OFFSET_ATTRIBUTE=\"$offset\"]"
    val end: Int get() = offset + span

    companion object {
        private val javaWhitespace = " \t\n\u000B\u000C\r"

        /** Applies the parser's in-block whitespace collapse (`\s+` to one space) on top of jsoup text. */
        internal fun of(offset: Int, normalized: TextNormalizer.Result): IndexedBlock {
            val text = normalized.text
            val collapse = IntArray(text.length + 1)
            val out = StringBuilder(text.length)
            var lastWasSpace = false
            for (i in text.indices) {
                collapse[i] = out.length
                if (text[i] in javaWhitespace) { if (!lastWasSpace) out.append(' '); lastWasSpace = true }
                else { out.append(text[i]); lastWasSpace = false }
            }
            collapse[text.length] = out.length
            return IndexedBlock(offset, out.toString(), text.length, normalized.raw, IntArray(normalized.rawToText.size) { collapse[normalized.rawToText[it]] })
        }
    }

    /** Normalized index of the character at raw position [rawOffset] (or of the next one when it was collapsed). */
    fun textIndex(rawOffset: Int): Int = rawToText[rawOffset.coerceIn(0, raw.length)]

    /** The first raw position whose normalized index is [textIndex]. */
    fun rawIndex(textIndex: Int): Int {
        val target = textIndex.coerceIn(0, text.length)
        var low = 0
        var high = raw.length
        while (low < high) {
            val middle = (low + high) ushr 1
            if (rawToText[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }
}

/**
 * The text index of one served resource: its blocks in document order and the offset space they belong to.
 * [resource] is the `TextPassage.resource` name (the EPUB entry path, or "text" for plain text editions).
 */
class ResourceTextIndex(val resource: String, val blocks: List<IndexedBlock>, val anchors: Map<String, Int> = emptyMap()) {
    /** Length of the resource in offset space: each block plus one separator. */
    val length: Int get() = blocks.lastOrNull()?.let { it.end + 1 } ?: 0

    fun block(offset: Int): IndexedBlock? = blocks.firstOrNull { it.offset == offset }

    /** The offset of the element with [id]: its position inside a block, or the next block when it sits between them. */
    fun anchor(id: String): Int? = anchors[id]

    /** The block containing [offset], or the nearest one before it; the first block for earlier offsets. */
    fun blockAt(offset: Int): IndexedBlock? {
        var found: IndexedBlock? = null
        for (block in blocks) { if (block.offset <= offset) found = block else break }
        return found ?: blocks.firstOrNull()
    }
}

/**
 * Reproduces jsoup 1.21.2 `Element.text()` (the normalization `BookTextParser` uses) over an XML-parsed chapter,
 * while recording where each raw `textContent` character lands. Tag semantics follow the HTML tag set, so the served
 * XHTML normalizes exactly as the HTML parse that produced the stored offsets. Elements `BookTextParser` removes
 * before reading text (scripts, styles, navigation, SVG, Gutenberg boilerplate) contribute raw characters only.
 */
internal object TextNormalizer {
    private val htmlTags = HashMap<String, Tag>()
    private fun tag(element: Element): Tag = htmlTags.getOrPut(element.normalName()) {
        when (element.normalName()) {
            "svg", "math" -> Tag.valueOf("div")
            else -> Tag.valueOf(element.normalName())
        }
    }
    private fun isBlock(element: Element) = tag(element).isBlock
    private fun isInline(element: Element) = tag(element).isInline
    private fun preservesWhitespace(node: Node?): Boolean {
        var element = node as? Element ?: return false
        var depth = 0
        while (true) {
            if (tag(element).preserveWhitespace()) return true
            element = element.parent() ?: return false
            if (++depth >= 6) return false
        }
    }

    fun removedByParser(element: Element): Boolean = element.normalName() in setOf("script", "style", "nav", "svg") ||
        element.id() in setOf("pg-header", "pg-footer", "pg-start-separator", "pg-end-separator") || element.hasClass("pg-boilerplate")

    private fun nextKeptSibling(node: Node): Node? {
        var next = node.nextSibling()
        while (next is Element && removedByParser(next)) next = next.nextSibling()
        return next
    }

    class Result(val text: String, val raw: String, val rawToText: IntArray)

    fun normalize(root: Element): Result {
        val accum = StringBuilder()
        val raw = StringBuilder()
        // rawToText[i]: accum length before raw character i is consumed, so collapsed characters share an index.
        val mapping = ArrayList<Int>()
        fun lastIsWhite() = accum.isNotEmpty() && accum[accum.length - 1] == ' '
        fun rawOnly(value: String) { repeat(value.length) { mapping += accum.length }; raw.append(value) }
        fun walk(node: Node, removed: Boolean) {
            when (node) {
                is TextNode -> {
                    val value = node.wholeText
                    if (removed || node.parent().let { it is Element && it.normalName() in setOf("style", "script") }) { rawOnly(value); return }
                    // jsoup appends CDATA (and preformatted text) verbatim.
                    if (node is CDataNode || preservesWhitespace(node.parentNode())) {
                        for (ch in value) { mapping += accum.length; accum.append(ch) }
                        raw.append(value)
                        return
                    }
                    var lastWasWhite = false
                    var reachedNonWhite = false
                    val stripLeading = lastIsWhite()
                    var index = 0
                    while (index < value.length) {
                        val code = value.codePointAt(index)
                        val width = Character.charCount(code)
                        repeat(width) { mapping += accum.length }
                        if (code == ' '.code || code == '\t'.code || code == '\n'.code || code == '\u000C'.code || code == '\r'.code || code == 160) {
                            if (!((stripLeading && !reachedNonWhite) || lastWasWhite)) { accum.append(' '); lastWasWhite = true }
                        } else if (code != 8203 && code != 173) {
                            accum.appendCodePoint(code); lastWasWhite = false; reachedNonWhite = true
                        }
                        index += width
                    }
                    raw.append(value)
                }
                is DataNode -> rawOnly(node.wholeData)
                is Comment -> Unit
                is Element -> {
                    val skip = removed || (node !== root && removedByParser(node))
                    if (!skip && accum.isNotEmpty() && (isBlock(node) || node.normalName() == "br") && !lastIsWhite()) accum.append(' ')
                    node.childNodes().forEach { walk(it, skip) }
                    if (!skip && node !== root) {
                        val next = nextKeptSibling(node)
                        if (!isInline(node) && (next is TextNode || next is Element && isInline(next)) && !lastIsWhite()) accum.append(' ')
                    }
                }
                else -> Unit
            }
        }
        walk(root, false)
        mapping += accum.length
        // text() trims, then BookTextParser trims again; shift indices past the trimmed lead.
        val full = accum.toString()
        val lead = full.indexOfFirst { it > ' ' }.let { if (it < 0) full.length else it }
        val text = full.trim()
        val rawToText = IntArray(mapping.size) { (mapping[it] - lead).coerceIn(0, text.length) }
        return Result(text, raw.toString(), rawToText)
    }
}

/**
 * Builds a resource's text index and marks its blocks with their parser offsets. [parsed] is the HTML parse of
 * the original bytes (what `BookTextParser` reads); [display] is the XML document that will be served. Blocks are
 * matched in order by normalized text, so markup the HTML parser restructures (self-closing anchors, for example)
 * still maps exactly; a block without a match keeps no offset and maps to its neighbours.
 */
internal object ResourceIndexer {
    fun annotateEpub(resource: String, parsed: Document, display: Document): ResourceTextIndex {
        val expected = EpubTextBlocks.extract(parsed)
        if (expected.size == 1 && expected[0].element.normalName() == "body") {
            display.body().attr(OFFSET_ATTRIBUTE, "0")
            val blocks = listOfNotNull(indexed(display.body(), 0))
            return ResourceTextIndex(resource, blocks, anchors(display, blocks))
        }
        val candidates = display.body().select(EpubTextBlocks.CANDIDATES).filter { node ->
            node.parents().none { it.normalName() in EpubTextBlocks.CONTAINERS } && node.parents().none(TextNormalizer::removedByParser)
        }.map { it to TextNormalizer.normalize(it) }.filter { it.second.text.isNotBlank() }
        val blocks = mutableListOf<IndexedBlock>()
        var next = 0
        for (block in expected) {
            val match = (next until candidates.size).firstOrNull { candidates[it].second.text == block.text } ?: continue
            val (element, normalized) = candidates[match]
            element.attr(OFFSET_ATTRIBUTE, block.offset.toString())
            blocks += IndexedBlock.of(block.offset, normalized)
            next = match + 1
        }
        return ResourceTextIndex(resource, blocks, anchors(display, blocks))
    }

    /** Plain-text editions carry offsets already; their block text is the parser's passage text, character for character. */
    fun indexMarked(resource: String, display: Document): ResourceTextIndex {
        val blocks = display.select("[$OFFSET_ATTRIBUTE]").mapNotNull { element ->
            val offset = element.attr(OFFSET_ATTRIBUTE).toIntOrNull() ?: return@mapNotNull null
            val raw = element.wholeText()
            IndexedBlock(offset, raw, raw.length, raw, IntArray(raw.length + 1) { it })
        }
        return ResourceTextIndex(resource, blocks, anchors(display, blocks))
    }

    /**
     * Maps every element id to an offset. An id inside a block (an inline page break, a footnote anchor) lands on the
     * character where it starts; an id outside blocks lands on the next block, or the end of the resource.
     */
    private fun anchors(display: Document, blocks: List<IndexedBlock>): Map<String, Int> {
        val byOffset = blocks.associateBy { it.offset }
        val anchors = HashMap<String, Int>()
        val pending = mutableListOf<String>()
        var current: IndexedBlock? = null
        var currentRoot: Element? = null
        var raw = 0
        org.jsoup.select.NodeTraversor.traverse(object : org.jsoup.select.NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is Element) {
                    val block = node.attr(OFFSET_ATTRIBUTE).toIntOrNull()?.let(byOffset::get)
                    if (block != null && currentRoot == null) {
                        current = block; currentRoot = node; raw = 0
                        pending.forEach { anchors.putIfAbsent(it, block.offset) }; pending.clear()
                    }
                    val id = node.id()
                    if (id.isNotEmpty()) {
                        val inside = current
                        if (inside != null) anchors.putIfAbsent(id, inside.offset + inside.textIndex(raw)) else pending += id
                    }
                } else if (node is TextNode && currentRoot != null) raw += node.wholeText.length
                else if (node is DataNode && currentRoot != null) raw += node.wholeData.length
            }
            override fun tail(node: Node, depth: Int) {
                if (node === currentRoot) { currentRoot = null; current = null }
            }
        }, display)
        val end = blocks.lastOrNull()?.let { it.end + 1 } ?: 0
        pending.forEach { anchors.putIfAbsent(it, end) }
        return anchors
    }

    private fun indexed(element: Element, offset: Int): IndexedBlock? =
        TextNormalizer.normalize(element).takeIf { it.text.isNotBlank() }?.let { IndexedBlock.of(offset, it) }
}
