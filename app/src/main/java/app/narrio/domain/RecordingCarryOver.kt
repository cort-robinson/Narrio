package app.narrio.domain

/**
 * Where to start a different recording of the same book so the listener lands near their place. Recordings differ in
 * pacing, front matter, and part layout, so this is always approximate ("≈"); books with a synced ebook map exactly
 * through the reading position instead.
 */
data class CarryOver(
    val partId: String,
    val positionMs: Long,
    /** The whole-book time the listener had reached in the old recording. */
    val fromElapsedMs: Long,
    /** The whole-book time this places them at in the new recording. */
    val toElapsedMs: Long,
    /** True when scaled by the two recordings' lengths; false when the same elapsed time was used. */
    val proportional: Boolean,
)

object RecordingCarryOver {
    /** Within this of either end, starting from the beginning (or near the end) says the same thing. */
    private const val EDGE_MS = 60_000L

    /** Whole-book time at [partId]/[positionMs]; null when an earlier part's length is unknown. */
    fun elapsed(parts: List<AudioPart>, partId: String, positionMs: Long): Long? {
        val index = parts.indexOfFirst { it.id == partId }
        if (index < 0) return null
        val before = parts.take(index)
        if (before.any { it.durationMs <= 0 }) return null
        return before.sumOf { it.durationMs } + positionMs.coerceAtLeast(0).let { position ->
            parts[index].durationMs.takeIf { it > 0 }?.let { position.coerceAtMost(it) } ?: position
        }
    }

    /** Sum of part lengths; null unless every part's length is known. */
    fun total(parts: List<AudioPart>): Long? = parts.takeIf { it.isNotEmpty() && it.all { part -> part.durationMs > 0 } }?.sumOf { it.durationMs }

    /**
     * Parts whose length isn't measured yet get a share of [totalMs] by file size, as audio of one recording usually
     * has a steady bitrate. Unchanged unless every unknown part has a size and some length is left to share.
     */
    fun estimated(parts: List<AudioPart>, totalMs: Long): List<AudioPart> {
        val unknown = parts.filter { it.durationMs <= 0 }
        if (unknown.isEmpty() || totalMs <= 0 || unknown.any { it.sizeBytes <= 0 }) return parts
        val left = totalMs - parts.sumOf { it.durationMs.coerceAtLeast(0) }
        val bytes = unknown.sumOf { it.sizeBytes }
        if (left <= 0 || bytes <= 0) return parts
        return parts.map { if (it.durationMs > 0) it else it.copy(durationMs = (left.toDouble() * it.sizeBytes / bytes).toLong().coerceAtLeast(1)) }
    }

    /**
     * Maps the old recording's whole-book time onto [newParts]: proportionally by total length when both lengths are
     * known, otherwise the same elapsed time, capped to the new length when that's known. [bookDurationMs] (the
     * catalog's length) lets unmeasured parts be estimated by file size. Null when there's no useful place to offer:
     * the listener was at the very start, or the new layout can't be located.
     */
    fun map(oldParts: List<AudioPart>, oldPartId: String, oldPositionMs: Long, newParts: List<AudioPart>, bookDurationMs: Long = 0): CarryOver? {
        if (newParts.isEmpty()) return null
        val old = total(oldParts)?.let { oldParts } ?: estimated(oldParts, bookDurationMs)
        val from = elapsed(old, oldPartId, oldPositionMs) ?: return null
        if (from < EDGE_MS) return null
        val oldTotal = total(old)
        val measured = total(newParts)
        val parts = if (measured != null) newParts else estimated(newParts, oldTotal ?: bookDurationMs)
        val newTotal = total(parts)
        val proportional = oldTotal != null && measured != null && oldTotal > 0
        var target = if (proportional) (from.toDouble() * measured!! / oldTotal!!).toLong() else from
        if (newTotal != null) target = target.coerceAtMost((newTotal - 1_000).coerceAtLeast(0))
        var remaining = target
        for ((index, part) in parts.withIndex()) {
            val last = index == parts.lastIndex
            if (part.durationMs <= 0) {
                // An unknown length can only hold the place when nothing after it has to be reached.
                return if (last) CarryOver(part.id, remaining, from, target, proportional) else null
            }
            if (remaining < part.durationMs || last) {
                return CarryOver(part.id, remaining.coerceAtMost((part.durationMs - 1_000).coerceAtLeast(0)), from, target, proportional)
            }
            remaining -= part.durationMs
        }
        return null
    }
}
