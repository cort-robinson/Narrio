package app.narrio.domain

import kotlinx.serialization.Serializable

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
    val weight: Int get() = text.split(Regex("\\s+")).size.coerceAtLeast(1)
}

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
)

@Serializable
data class TextAnchor(val passageId: String, val positionMs: Long)

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
    fun passages(document: BookText, chapterId: String): List<TextPassage> =
        if (chapterId == WHOLE_BOOK) document.chapters.flatMap { it.passages }
        else document.chapters.firstOrNull { it.id == chapterId }?.passages.orEmpty()

    fun timeline(document: BookText, binding: TextBinding, durationMs: Long): FollowTimeline? {
        if (binding.documentId != document.id) return null
        val lines = passages(document, binding.chapterId)
        if (lines.isEmpty()) return null
        if (document.timedSourceId.isNotBlank()) {
            if (document.timedSourceId != binding.sourceId || document.timedPartId != binding.partId) return null
            return FollowTimeline(lines, lines.map { it.startMs ?: return null }, lines.map { it.endMs ?: return null }, true)
        }
        if (durationMs <= 0) return null
        val weights = lines.runningFold(0L) { total, line -> total + line.weight }
        val anchors = binding.anchors.mapNotNull { anchor ->
            lines.indexOfFirst { it.id == anchor.passageId }.takeIf { it >= 0 }?.let { weights[it] to anchor.positionMs }
        }.sortedBy { it.first }
        if (anchors.any { it.second < 0 || it.second >= durationMs } || anchors.zipWithNext().any { (a, b) -> a.second >= b.second }) return null
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

    fun addAnchor(document: BookText, binding: TextBinding, passageId: String, positionMs: Long, durationMs: Long): TextBinding {
        require(document.timedSourceId.isBlank()) { "Supplied timestamps cannot be adjusted with estimated timing." }
        val lines = passages(document, binding.chapterId)
        require(lines.any { it.id == passageId }) { "Choose a line in this chapter." }
        require(positionMs >= 0 && durationMs > positionMs) { "Wait for the audio length, then match a line before the end of this part." }
        val updated = binding.copy(anchors = binding.anchors.filterNot { it.passageId == passageId } + TextAnchor(passageId, positionMs))
        require(timeline(document, updated, durationMs) != null) { "Timing matches must follow the order of the text. Reset timing to start again." }
        return updated
    }
}
