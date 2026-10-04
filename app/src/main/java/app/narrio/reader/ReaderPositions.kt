package app.narrio.reader

/**
 * What the page reports as its first visible character: the parser offset of the enclosing (or next) block and
 * the raw `textContent` index inside it. A null [blockOffset] means the page shows no indexed text.
 */
data class PagePosition(val blockOffset: Int?, val rawOffset: Int)

/**
 * A Readium text-quote target inside one block: [selector] finds the block, and [highlight] with its [before] and
 * [after] context is that block's exact `textContent`, so the navigator lands on the same character.
 */
data class TextQuote(val selector: String, val before: String, val highlight: String, val after: String)

/** Maps between page positions, `(resource, offset)` content offsets, and text quotes for one resource. */
object CursorMapping {
    private const val CONTEXT = 32
    private const val QUOTE = 24

    /** The content offset of [position]; positions outside indexed text fall on the resource start. */
    fun offset(index: ResourceTextIndex, position: PagePosition): Int {
        val block = position.blockOffset?.let(index::block) ?: return 0
        return block.offset + block.textIndex(position.rawOffset)
    }

    /** A quote that restores [offset]: it starts at the first visible character at or after the offset. */
    fun quote(index: ResourceTextIndex, offset: Int): TextQuote? {
        val block = index.blockAt(offset) ?: return null
        var start = block.rawIndex(offset - block.offset)
        while (start < block.raw.length && block.raw[start].isWhitespace()) start++
        if (start >= block.raw.length) start = (block.raw.length - 1).coerceAtLeast(0)
        val end = (start + QUOTE).coerceAtMost(block.raw.length)
        return quote(block, start, end)
    }

    /**
     * Quotes covering `[start, end)` in content offsets, one per block the range touches, for decorations. Ranges
     * in other resources or outside indexed text produce nothing.
     */
    fun rangeQuotes(index: ResourceTextIndex, start: Int, end: Int): List<TextQuote> {
        if (end <= start) return emptyList()
        return index.blocks.filter { it.offset < end && it.end > start }.mapNotNull { block ->
            val from = block.rawIndex(start - block.offset)
            var to = block.rawIndex(end - block.offset)
            while (to > from && block.raw[to - 1].isWhitespace()) to--
            if (to <= from) null else quote(block, from, to)
        }
    }

    private fun quote(block: IndexedBlock, start: Int, end: Int) = TextQuote(
        selector = block.selector,
        before = block.raw.substring((start - CONTEXT).coerceAtLeast(0), start),
        highlight = block.raw.substring(start, end),
        after = block.raw.substring(end, (end + CONTEXT).coerceAtMost(block.raw.length)),
    )
}

/**
 * Where a content offset sits in the whole edition. [resources] is the reading order by parser resource name,
 * [lengths] the offset-space length of each; a plain-text edition has one resource, `"text"`.
 */
class EditionLayout(val resources: List<String>, private val lengths: Map<String, Int>) {
    private val starts: Map<String, Long> = buildMap {
        var total = 0L
        for (resource in resources.distinct()) { put(resource, total); total += lengths[resource] ?: 0 }
    }
    val total: Long = resources.distinct().sumOf { (lengths[it] ?: 0).toLong() }

    fun length(resource: String): Int = lengths[resource] ?: 0

    /** Characters from the start of the edition. */
    fun position(resource: String, offset: Int): Long? = starts[resource]?.plus(offset.coerceAtLeast(0))

    fun progression(resource: String, offset: Int): Double =
        if (total <= 0) 0.0 else ((position(resource, offset) ?: 0L).toDouble() / total).coerceIn(0.0, 1.0)

    /** Characters between two places; negative when [to] is earlier. */
    fun distance(fromResource: String, fromOffset: Int, toResource: String, toOffset: Int): Long? {
        val from = position(fromResource, fromOffset) ?: return null
        val to = position(toResource, toOffset) ?: return null
        return to - from
    }
}

/**
 * Reading speed measured on this phone, in characters per minute. Each forward page turn after a plausible dwell is
 * a sample; skims and long pauses are ignored. Until [measured], [charsPerMinute] is a typical adult pace.
 */
data class ReadingPace(val charsPerMinute: Double = DEFAULT_CPM, val samples: Int = 0) {
    val measured: Boolean get() = samples >= MEASURED_SAMPLES

    fun sample(characters: Long, elapsedMs: Long): ReadingPace {
        if (characters < 80 || elapsedMs < 1_500 || elapsedMs > 10 * 60_000) return this
        val rate = characters * 60_000.0 / elapsedMs
        if (rate < 150 || rate > 6_000) return this
        // Early samples settle quickly; later ones adjust gently.
        val weight = if (samples < MEASURED_SAMPLES) 1.0 / (samples + 1) else 0.15
        return ReadingPace(charsPerMinute * (1 - weight) + rate * weight, samples + 1)
    }

    fun minutesFor(characters: Long): Int = kotlin.math.ceil(characters.coerceAtLeast(0) / charsPerMinute).toInt()

    companion object {
        /** About 240 words a minute of English prose. */
        const val DEFAULT_CPM = 1_300.0
        const val MEASURED_SAMPLES = 5
    }
}

/**
 * Decides when reading moves the shared position. Opening or restoring never commits; a reader's own navigation
 * commits after about [dwellMs] on the new page or at the second page turn. While audio plays, audio owns the
 * position, so nothing commits and activity starts over when it stops.
 */
class ReadingActivity<C : Any>(
    private val dwellMs: Long = app.narrio.domain.SyncThresholds.READING_DWELL_MS,
    private val pageTurns: Int = app.narrio.domain.SyncThresholds.READING_PAGE_TURNS,
) {
    private var pending: C? = null
    private var movedAt = 0L
    private var turns = 0
    private var committed: C? = null
    private var audioPlaying = false

    /** The place the book opened or was restored at; it is never committed by itself. */
    fun restored(cursor: C) { committed = cursor; pending = null; turns = 0 }

    /** A move the reader made (page turn, scroll, chapter or link jump). Returns a cursor to commit now, if any. */
    fun moved(cursor: C, now: Long): C? {
        if (audioPlaying) return null
        if (cursor == committed && pending == null) return null
        pending = cursor; movedAt = now; turns++
        return if (turns >= pageTurns) commit() else null
    }

    /** Call when the dwell may have elapsed. Returns a cursor to commit now, if any. */
    fun tick(now: Long): C? = if (!audioPlaying && pending != null && now - movedAt >= dwellMs) commit() else null

    /** Milliseconds until the pending dwell completes, or null when nothing is pending. */
    fun dwellRemaining(now: Long): Long? = pending?.let { (dwellMs - (now - movedAt)).coerceAtLeast(0) }

    fun audio(playing: Boolean) {
        audioPlaying = playing
        if (playing) { pending = null; turns = 0 }
    }

    private fun commit(): C? {
        val cursor = pending ?: return null
        pending = null; turns = 0
        if (cursor == committed) return null
        committed = cursor
        return cursor
    }
}
