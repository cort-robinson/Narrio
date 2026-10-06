package app.narrio.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.text.Normalizer

/** One match: its exact content range and a snippet of the surrounding text for the results list. */
data class SearchHit(val range: CursorRange, val before: String, val match: String, val after: String)

/**
 * The reader's search. [query] is what was searched, [hits] arrive chapter by chapter while [searching], and
 * [current] is the match the reader last jumped to (-1 before any). [truncated] means more than [BookSearch.LIMIT]
 * matched; the list stops there.
 */
data class SearchState(
    val query: String = "",
    val searching: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val truncated: Boolean = false,
    val current: Int = -1,
    val scanned: Int = 0,
    val total: Int = 0,
) {
    val active: Boolean get() = query.isNotBlank()
    val currentHit: SearchHit? get() = hits.getOrNull(current)
}

/**
 * Matching that keeps the parser's offsets: every character folds to exactly one character, so a match index in
 * the folded text is the same index in the block text, and a hit becomes an exact [CursorRange].
 */
object TextSearch {
    /** Case, accents, and typographic quotes and dashes are ignored. */
    fun fold(c: Char): Char {
        val base = if (c.code < 128) c else Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
        return when (base) {
            '‘', '’', '‛', '′' -> '\''
            '“', '”', '‟', '″' -> '"'
            '‐', '‑', '‒', '–', '—' -> '-'
            ' ' -> ' '
            else -> base.lowercaseChar()
        }
    }

    fun fold(text: String): String = buildString(text.length) { text.forEach { append(fold(it)) } }

    /** The query as searched: trimmed, whitespace runs collapsed (block text is already collapsed), then folded. */
    fun normalizeQuery(query: String): String = fold(query.trim().replace(Regex("\\s+"), " "))

    /** Start indexes of non-overlapping matches of a normalized [needle] in [text]. */
    fun find(text: String, needle: String, limit: Int = Int.MAX_VALUE): List<Int> {
        if (needle.isEmpty() || text.length < needle.length) return emptyList()
        val folded = fold(text)
        val found = mutableListOf<Int>()
        var from = 0
        while (found.size < limit) {
            val at = folded.indexOf(needle, from)
            if (at < 0) break
            found += at
            from = at + needle.length
        }
        return found
    }

    /** About [context] characters either side of `[start, end)`, cut at word boundaries and marked with "…". */
    fun snippet(text: String, start: Int, end: Int, context: Int = 48): Triple<String, String, String> {
        var from = (start - context).coerceAtLeast(0)
        if (from > 0) text.indexOf(' ', from).takeIf { it in from until start }?.let { from = it + 1 }
        var to = (end + context).coerceAtMost(text.length)
        if (to < text.length) text.lastIndexOf(' ', to).takeIf { it > end }?.let { to = it }
        return Triple((if (from > 0) "…" else "") + text.substring(from, start), text.substring(start, end),
            text.substring(end, to) + if (to < text.length) "…" else "")
    }
}

/**
 * Full-text search over the same sanitized, indexed text the navigator shows. Results are parser cursor ranges,
 * so a hit decorates and restores exactly like a highlight or narration range; Readium's string search would
 * report its own text extraction and need mapping back.
 */
class BookSearch(private val book: ReaderBook, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null
    /** Block offsets and text per reading-order resource, read once; a typical novel is a few MB of text. */
    private val texts = HashMap<String, List<Pair<Int, String>>>()

    /** Searches for [query]; a blank or one-character query clears the results. */
    fun search(query: String) {
        val needle = TextSearch.normalizeQuery(query)
        if (needle.length < MIN_QUERY) { job?.cancel(); _state.value = SearchState(query = query); return }
        if (needle == TextSearch.normalizeQuery(_state.value.query) && (_state.value.searching || _state.value.hits.isNotEmpty() || _state.value.total > 0)) return
        job?.cancel()
        val links = book.readingOrder
        _state.value = SearchState(query = query, searching = true, total = links.size)
        job = scope.launch {
            val hits = mutableListOf<SearchHit>()
            var truncated = false
            for ((done, link) in links.withIndex()) {
                val blocks = blocks(link) ?: emptyList()
                val resource = book.resourceName(link)
                withContext(Dispatchers.Default) {
                    for ((offset, text) in blocks) {
                        for (at in TextSearch.find(text, needle, LIMIT + 1 - hits.size)) {
                            val (before, match, after) = TextSearch.snippet(text, at, at + needle.length)
                            hits += SearchHit(CursorRange(book.cursor(resource, offset + at), book.cursor(resource, offset + at + needle.length)), before, match, after)
                        }
                        if (hits.size > LIMIT) { truncated = true; break }
                    }
                }
                _state.update { it.copy(hits = hits.take(LIMIT), scanned = done + 1, truncated = truncated) }
                if (truncated) break
                yield()
            }
            _state.update { it.copy(searching = false, scanned = links.size) }
        }
    }

    /** Marks [index] as the match on screen. */
    fun select(index: Int) { _state.update { if (index in it.hits.indices) it.copy(current = index) else it } }

    fun clear() { job?.cancel(); _state.value = SearchState() }

    private suspend fun blocks(link: org.readium.r2.shared.publication.Link): List<Pair<Int, String>>? {
        val path = link.url().path ?: return null
        texts[path]?.let { return it }
        val index = book.index(link) ?: return null
        return index.blocks.map { it.offset to it.text }.also { texts[path] = it }
    }

    companion object {
        const val LIMIT = 500
        const val MIN_QUERY = 2
    }
}
