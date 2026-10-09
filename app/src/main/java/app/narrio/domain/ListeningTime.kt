package app.narrio.domain

import kotlin.math.ceil

/** Where the listener is in the whole recording. Only built when every part's length is known; never a guess. */
data class BookTime(val positionMs: Long, val totalMs: Long) {
    val remainingMs: Long get() = (totalMs - positionMs).coerceAtLeast(0)
    val fraction: Float get() = if (totalMs > 0) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
}

/**
 * Whole-book place from the parts' lengths. [currentDurationMs] is the playing part's measured length, which
 * stands in for a part the source never described. Null when any part's length is still unknown.
 */
fun bookTime(parts: List<AudioPart>, partIndex: Int, positionMs: Long, currentDurationMs: Long = 0): BookTime? {
    if (parts.isEmpty() || partIndex !in parts.indices) return null
    val lengths = parts.mapIndexed { i, part -> if (i == partIndex && currentDurationMs > 0) currentDurationMs else part.durationMs }
    if (lengths.any { it <= 0 }) return null
    val total = lengths.sum()
    return BookTime((lengths.take(partIndex).sum() + positionMs.coerceIn(0, lengths[partIndex])).coerceAtMost(total), total)
}

/** The chapter playing at [positionMs]: the last one that has started, or -1 before the first. */
fun chapterIndexAt(chapters: List<Chapter>, positionMs: Long): Int = chapters.indexOfLast { it.startMs <= positionMs }

/**
 * Where the chapter playing at [positionMs] ends: the next chapter's start, or null when it runs to the end of the
 * part. A chapter ending within [graceMs] counts as finished, so a timer set just before a boundary (or resumed right
 * after one paused there) waits for the following chapter rather than stopping at once.
 */
fun chapterEndMs(chapters: List<Chapter>, positionMs: Long, graceMs: Long = CHAPTER_GRACE_MS): Long? =
    chapters.asSequence().map { it.startMs }.sorted().firstOrNull { it > positionMs + graceMs }

/** A place in a recording: part [partIndex] at [positionMs]. */
data class PartPlace(val partIndex: Int, val positionMs: Long)

/**
 * Previous/next chapter. Within a part's chapters it moves between chapter starts; past either end, and in parts
 * without chapters, it moves to a neighbouring part. Like a CD player, "previous" first returns to the start of the
 * current chapter or part once it has played for [restartMs]. Null when there is nowhere to go.
 */
fun chapterStep(chapters: List<Chapter>, partIndex: Int, partCount: Int, positionMs: Long, forward: Boolean, restartMs: Long = RESTART_MS): PartPlace? {
    val starts = chapters.map { it.startMs }.distinct().sorted()
    val current = starts.indexOfLast { it <= positionMs }
    if (forward) {
        starts.getOrNull(current + 1)?.let { return PartPlace(partIndex, it) }
        return if (partIndex + 1 < partCount) PartPlace(partIndex + 1, 0) else null
    }
    val start = starts.getOrNull(current) ?: 0L
    if (positionMs - start >= restartMs) return PartPlace(partIndex, start)
    if (current > 0) return PartPlace(partIndex, starts[current - 1])
    return if (partIndex > 0) PartPlace(partIndex - 1, 0) else if (positionMs > 0) PartPlace(partIndex, 0) else null
}

/** "9 h 41 m", "41 m", or "1 m" for the last seconds; rounds up so a timer never reads 0 while time remains. */
fun timeLeftLabel(ms: Long): String {
    val minutes = ceil(ms.coerceAtLeast(0) / 60_000.0).toLong()
    return when {
        minutes >= 60 && minutes % 60 == 0L -> "${minutes / 60} h"
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} m"
        else -> "$minutes m"
    }
}

/** Screen readers hear "9 hours 41 minutes" rather than "9 H 41 M". */
fun spokenTimeLeft(ms: Long): String {
    val minutes = ceil(ms.coerceAtLeast(0) / 60_000.0).toLong()
    val hours = minutes / 60; val rest = minutes % 60
    fun unit(n: Long, name: String) = "$n $name${if (n == 1L) "" else "s"}"
    return listOfNotNull(unit(hours, "hour").takeIf { hours > 0 }, unit(rest, "minute").takeIf { rest > 0 || hours == 0L }).joinToString(" ")
}

/** Listening time at [speed]: an hour of audio at 2× takes half an hour. */
fun atSpeed(ms: Long, speed: Float): Long = if (speed > 0f) (ms / speed.toDouble()).toLong() else ms

const val CHAPTER_GRACE_MS = 3_000L
const val RESTART_MS = 3_000L
