package app.narrio.reader

import androidx.annotation.ColorInt
import app.narrio.data.AnnotationEntry
import app.narrio.data.NarrioJson
import app.narrio.domain.ContentCursor
import kotlinx.serialization.encodeToString

/**
 * Highlight colours. [key] is what's stored, so an export names the colour rather than a theme's pixel value; each
 * has its own Night and Day tint, which the navigator draws at its highlight opacity over the page.
 */
enum class HighlightColor(val key: String, val label: String, @ColorInt val day: Int, @ColorInt val night: Int) {
    YELLOW("yellow", "Yellow", 0xFFF2BE22.toInt(), 0xFFFFD54F.toInt()),
    GREEN("green", "Green", 0xFF62AA60.toInt(), 0xFF9CD69A.toInt()),
    BLUE("blue", "Blue", 0xFF5494D6.toInt(), 0xFF8EC1F2.toInt()),
    PINK("pink", "Pink", 0xFFDE6E96.toInt(), 0xFFF4A3C0.toInt());

    @ColorInt fun tint(dark: Boolean): Int = if (dark) night else day

    companion object {
        /** Unknown stored values fall back to yellow rather than hiding the highlight. */
        fun of(key: String): HighlightColor = entries.firstOrNull { it.key == key } ?: YELLOW
    }
}

/** A highlight, optionally with a note, over an exact content range of one edition. */
data class Highlight(
    val id: String,
    val bookId: String,
    val range: CursorRange,
    val color: HighlightColor,
    val note: String = "",
    val text: String = "",
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    val editionId: String get() = range.start.editionId

    /** The stored row: cursors as JSON at the parser's normalization version, the colour by name, plain-text note. */
    fun entry(): AnnotationEntry = AnnotationEntry(id, bookId, editionId, NarrioJson.encodeToString(range.start.durable()),
        NarrioJson.encodeToString(range.end.durable()), color.key, note.trim(), text, createdAtMs, updatedAtMs)

    companion object {
        fun of(entry: AnnotationEntry): Highlight? = runCatching {
            Highlight(entry.id, entry.bookId, CursorRange(entry.start(), entry.end()), HighlightColor.of(entry.color), entry.note, entry.selectedText, entry.createdAtMs, entry.updatedAtMs)
        }.getOrNull()
    }
}

/** Identity only: progression and locator caches don't belong in an annotation's range. */
private fun ContentCursor.durable() = copy(progression = 0.0, locatorJson = "")

/** The decoration group for highlights; narration and search draw in their own groups. */
const val HIGHLIGHT_GROUP = "narrio-highlights"
const val SEARCH_GROUP = "narrio-search"

/** Highlights as decorations; a note adds an underline in the same colour. */
fun highlightDecorations(highlights: List<Highlight>, dark: Boolean): List<TextDecoration> = highlights.map {
    TextDecoration(it.id, it.range, TextDecoration.Style.HIGHLIGHT, it.color.tint(dark), active = it.note.isNotBlank())
}

/** The parser text of `[start, end)` in [blocks] (offset, text), with blocks joined by line breaks. */
fun rangeText(blocks: List<Pair<Int, String>>, start: Int, end: Int): String =
    blocks.filter { (offset, text) -> offset < end && offset + text.length >= start }.mapNotNull { (offset, text) ->
        val from = (start - offset).coerceIn(0, text.length)
        val to = (end - offset).coerceIn(0, text.length)
        text.substring(from, to).trim().takeIf { it.isNotEmpty() }
    }.joinToString("\n")

/** The edition text of [range], in the same normalized form search and alignment use. Empty across resources it can't read. */
suspend fun ReaderBook.text(range: CursorRange): String {
    if (range.start.resource != range.end.resource) return ""
    val link = linkFor(range.start.resource, range.start.offset) ?: return ""
    val index = index(link) ?: return ""
    return rangeText(index.blocks.map { it.offset to it.text }, range.start.offset, range.end.offset)
}

/** About [length] characters of text from [cursor], cut at a word, for bookmark rows. */
suspend fun ReaderBook.snippet(cursor: ContentCursor, length: Int = 120): String {
    val link = linkFor(cursor.resource, cursor.offset) ?: return ""
    val index = index(link) ?: return ""
    val text = rangeText(index.blocks.map { it.offset to it.text }, cursor.offset, cursor.offset + length * 2).replace('\n', ' ')
    if (text.length <= length) return text
    val cut = text.lastIndexOf(' ', length).takeIf { it > length / 2 } ?: length
    return text.substring(0, cut).trimEnd(',', ';', ':', ' ') + "…"
}
