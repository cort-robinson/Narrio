package app.narrio.domain

import kotlinx.serialization.Serializable

/** An external search destination, not a discovered or downloadable ebook edition. */
data class EbookSearchLink(val name: String, val url: String)

/** A discovery reference, never a temporary delivery URL or a claim of edition equivalence. */
@Serializable
data class BookTextSource(
    val id: String,
    val title: String,
    val author: String = "",
    val format: String,
    val provider: String,
    val url: String = "",
    val torrentId: Long? = null,
    val fileId: Long? = null,
    val attribution: String = "",
    val language: String = "",
    // A TorBox-cached release is added to the account only when its text is fetched.
    val torrentHash: String = "",
    val magnetUri: String = "",
    val fileName: String = "",
)

@Serializable
data class TextPassage(
    val id: String,
    val text: String,
    // Resource-relative offsets survive a future change in pagination or font size.
    val resource: String,
    val offset: Int,
    val startMs: Long? = null,
    val endMs: Long? = null,
) {
    val weight: Int get() = text.split(WORD_BREAK).size.coerceAtLeast(1)
}

private val WORD_BREAK = Regex("\\s+")

@Serializable
data class TextChapter(val id: String, val title: String, val passages: List<TextPassage>)

@Serializable
data class BookText(
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    val attribution: String,
    val chapters: List<TextChapter>,
    // A standalone VTT track is valid only for the audio part it was attached to.
    val timedSourceId: String = "",
    val timedPartId: String = "",
    val normalizationVersion: Int = 1,
    val language: String = "",
)

/** [word] counts whitespace-separated words into the passage; [auto] anchors come from recognized narration. */
@Serializable
data class TextAnchor(val passageId: String, val positionMs: Long, val word: Int = 0, val auto: Boolean = false)

@Serializable
data class TextBinding(
    val documentId: String,
    val sourceId: String,
    val partId: String,
    val chapterId: String,
    val anchors: List<TextAnchor> = emptyList(),
)

fun textFileFormat(name: String): String? = name.substringAfterLast('.', "").uppercase().takeIf { it in setOf("EPUB", "TXT", "VTT") }

const val WHOLE_BOOK = "@whole-book"

/** No guessed mapping across different recordings, formats, or multipart layouts. */
fun defaultTextBinding(document: BookText, source: AudioSource, partIndex: Int): TextBinding? {
    val part = source.parts.getOrNull(partIndex) ?: return null
    if (document.timedSourceId.isNotBlank()) return if (document.timedSourceId == source.id && document.timedPartId == part.id)
        TextBinding(document.id, source.id, part.id, document.chapters.first().id) else null
    val chapter = when {
        source.parts.size == 1 -> WHOLE_BOOK
        document.chapters.size == source.parts.size -> document.chapters[partIndex].id
        else -> {
            val words = part.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            document.chapters.filter { it.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() == words }.singleOrNull()?.id
        }
    } ?: return null
    return TextBinding(document.id, source.id, part.id, chapter)
}

class FollowTimeline(
    val passages: List<TextPassage>,
    val starts: List<Long>,
    val ends: List<Long>,
    val timed: Boolean,
    // Anchored to recognized narration rather than spread by word count.
    val aligned: Boolean = false,
) {
    /** Gaps, introductions, and the end of a timed track do not highlight an unrelated line. */
    fun activeIndex(positionMs: Long): Int? {
        var low = 0
        var high = starts.lastIndex
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= positionMs) { found = middle; low = middle + 1 } else high = middle - 1
        }
        return found.takeIf { it >= 0 && positionMs < ends[it] }
    }
}

object FollowAlongTiming {
    /** About 155 spoken words per minute; used only until narration supplies its own pace. */
    private const val DEFAULT_WORDS_PER_MS = 2.6 / 1000
    private const val MIN_WORDS_PER_MS = 1.2 / 1000
    private const val MAX_WORDS_PER_MS = 5.0 / 1000
    /** Recognized anchors further apart than this leave the passages between them estimated. */
    private const val SYNC_GAP_MS = 180_000L

    fun passages(document: BookText, chapterId: String): List<TextPassage> =
        if (chapterId == WHOLE_BOOK) document.chapters.flatMap { it.passages }
        else document.chapters.firstOrNull { it.id == chapterId }?.passages.orEmpty()

    private fun weights(lines: List<TextPassage>) = lines.runningFold(0L) { total, line -> total + line.weight }

    /** The anchor's word position in the chapter, or null when its passage is no longer present. */
    private fun point(lines: List<TextPassage>, weights: List<Long>, anchor: TextAnchor): Long? =
        lines.indexOfFirst { it.id == anchor.passageId }.takeIf { it >= 0 }?.let { weights[it] + anchor.word.coerceIn(0, lines[it].weight - 1) }

    fun timeline(document: BookText, binding: TextBinding, durationMs: Long): FollowTimeline? {
        if (binding.documentId != document.id) return null
        val lines = passages(document, binding.chapterId)
        if (lines.isEmpty()) return null
        if (document.timedSourceId.isNotBlank()) {
            if (document.timedSourceId != binding.sourceId || document.timedPartId != binding.partId) return null
            return FollowTimeline(lines, lines.map { it.startMs ?: return null }, lines.map { it.endMs ?: return null }, true)
        }
        if (durationMs <= 0) return null
        val weights = weights(lines)
        val anchors = binding.anchors.mapNotNull { anchor -> point(lines, weights, anchor)?.let { it to anchor.positionMs } }.sortedBy { it.first }
        if (anchors.any { it.second < 0 || it.second >= durationMs } || anchors.zipWithNext().any { (a, b) -> a.second >= b.second }) return null
        if (binding.anchors.any { it.auto }) return aligned(lines, weights, anchors, durationMs)
        val points = buildList {
            if (anchors.firstOrNull()?.first != 0L) add(0L to 0L)
            addAll(anchors)
            add(weights.last() to durationMs)
        }
        fun time(weight: Long): Long {
            if (weight < points.first().first) return 0
            val right = points.indexOfFirst { it.first > weight }.takeIf { it >= 0 } ?: return durationMs
            val a = points[(right - 1).coerceAtLeast(0)]
            val b = points[right]
            return a.second + ((weight - a.first).toDouble() / (b.first - a.first).coerceAtLeast(1) * (b.second - a.second)).toLong()
        }
        val starts = weights.dropLast(1).map(::time)
        return FollowTimeline(lines, starts, starts.drop(1) + durationMs, false)
    }

    /**
     * Narration anchors replace the assumption that the text fills the whole audio part. Text before the
     * first or after the last anchor continues at the narrator's measured pace, so a part covering only a
     * slice of the book highlights only that slice; other passages fall outside this part's clock.
     */
    private fun aligned(lines: List<TextPassage>, weights: List<Long>, anchors: List<Pair<Long, Long>>, durationMs: Long): FollowTimeline {
        val first = anchors.first()
        val last = anchors.last()
        val pace = if (last.second - first.second >= 20_000)
            ((last.first - first.first).toDouble() / (last.second - first.second)).coerceIn(MIN_WORDS_PER_MS, MAX_WORDS_PER_MS)
        else DEFAULT_WORDS_PER_MS
        fun time(weight: Long): Long {
            val right = anchors.indexOfFirst { it.first > weight }
            return when {
                right == 0 -> first.second - ((first.first - weight) / pace).toLong()
                right < 0 -> last.second + ((weight - last.first) / pace).toLong()
                else -> {
                    val a = anchors[right - 1]
                    val b = anchors[right]
                    a.second + ((weight - a.first).toDouble() / (b.first - a.first) * (b.second - a.second)).toLong()
                }
            }
        }
        val starts = weights.dropLast(1).map(::time)
        val end = time(weights.last()).coerceAtLeast(starts.last() + 1)
        return FollowTimeline(lines, starts, starts.drop(1) + minOf(end, durationMs.coerceAtLeast(starts.last() + 1)), false, aligned = true)
    }

    /** True when recognized anchors lie close on both sides of the position rather than extrapolated pace. */
    fun synced(binding: TextBinding, positionMs: Long): Boolean {
        val times = binding.anchors.filter { it.auto }.map { it.positionMs }
        val before = times.filter { it <= positionMs }.maxOrNull()
        val after = times.filter { it >= positionMs }.minOrNull()
        return when {
            before != null && after != null -> after - before <= SYNC_GAP_MS
            // Just outside the recognized range, the measured pace is still close.
            else -> (before ?: after)?.let { kotlin.math.abs(positionMs - it) <= 30_000 } == true
        }
    }

    fun addAnchor(document: BookText, binding: TextBinding, passageId: String, positionMs: Long, durationMs: Long): TextBinding {
        require(document.timedSourceId.isBlank()) { "Supplied timestamps cannot be adjusted with estimated timing." }
        val lines = passages(document, binding.chapterId)
        require(lines.any { it.id == passageId }) { "Choose a line in this chapter." }
        require(positionMs >= 0 && durationMs > positionMs) { "Wait for the audio length, then match a line before the end of this part." }
        val updated = binding.copy(anchors = binding.anchors.filterNot { it.passageId == passageId && !it.auto } + TextAnchor(passageId, positionMs))
        // A listener's match outranks recognized anchors that disagree with it.
        val resolved = if (binding.anchors.any { it.auto }) consistent(document, updated, durationMs) else updated
        require(resolved.anchors.count { !it.auto } == updated.anchors.count { !it.auto } && timeline(document, resolved, durationMs) != null) {
            "Timing matches must follow the order of the text. Reset timing to start again."
        }
        return resolved
    }

    /** Adds recognized anchors, dropping any that contradict manual matches, the text order, or a plausible pace. */
    fun mergeAuto(document: BookText, binding: TextBinding, found: List<TextAnchor>, durationMs: Long): TextBinding =
        consistent(document, binding.copy(anchors = binding.anchors + found.map { it.copy(auto = true) }), durationMs)

    private class Point(val anchor: TextAnchor, val words: Long) { val ms get() = anchor.positionMs }

    private fun consistent(document: BookText, binding: TextBinding, durationMs: Long): TextBinding {
        val lines = passages(document, binding.chapterId)
        val weights = weights(lines)
        val points = binding.anchors.filter { it.positionMs in 0 until durationMs }
            .mapNotNull { anchor -> point(lines, weights, anchor)?.let { Point(anchor, it) } }
            .sortedWith(compareBy({ it.ms }, { it.words }))
        fun compatible(a: Point, b: Point): Boolean {
            val words = b.words - a.words
            val ms = b.ms - a.ms
            if (words <= 0 || ms <= 0) return false
            if (!a.anchor.auto && !b.anchor.auto) return true
            // Pauses and music can slow the apparent pace, but nobody narrates far faster than this.
            return words <= 15 + ms * MAX_WORDS_PER_MS * 1.5
        }
        // Longest chain increasing in both text and time; manual anchors carry overriding weight.
        val score = IntArray(points.size)
        val previous = IntArray(points.size) { -1 }
        for (k in points.indices) {
            val own = if (points[k].anchor.auto) 1 else 10_000
            score[k] = own
            for (j in 0 until k) if (score[j] + own > score[k] && compatible(points[j], points[k])) { score[k] = score[j] + own; previous[k] = j }
        }
        val chain = mutableListOf<TextAnchor>()
        var at = score.indices.maxByOrNull { score[it] } ?: -1
        while (at >= 0) { chain += points[at].anchor; at = previous[at] }
        // Recognized anchors closer than a second add no precision.
        val kept = mutableListOf<TextAnchor>()
        for (anchor in chain.asReversed()) {
            val last = kept.lastOrNull()
            if (last == null || !anchor.auto || !last.auto || anchor.positionMs - last.positionMs >= 1000) kept += anchor
        }
        return binding.copy(anchors = kept)
    }
}
