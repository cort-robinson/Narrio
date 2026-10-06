package app.narrio.domain

import kotlinx.serialization.Serializable

/** A owns the Room implementation. Version/fingerprints invalidate progress when media or normalization changes. */
@Serializable
data class AlignmentKey(val bookId: String, val editionId: String, val sourceId: String, val partId: String,
    val normalizationVersion: Int, val layoutFingerprint: String, val algorithmVersion: Int = 1)

@Serializable
data class AlignmentProgress(val key: AlignmentKey, val nextWindowMs: Long = 0, val attempted: Int = 0,
    val matched: Int = 0, val firstAttemptMs: Long = -1, val lastAttemptMs: Long = -1, val complete: Boolean = false)

interface AlignmentJobRepository {
    suspend fun progress(key: AlignmentKey): AlignmentProgress
    suspend fun save(progress: AlignmentProgress)
}

object PairingEvidence {
    /** Failed downloads/decodes and missing/unsupported models are not evidence of a mismatched edition. */
    fun status(progress: List<AlignmentProgress>): PairingStatus {
        val attempted = progress.sumOf { it.attempted }
        val matched = progress.sumOf { it.matched }
        if (attempted == 0) return PairingStatus.UNCHECKED
        if (matched > 0) return if (attempted >= 3 && matched.toDouble() / attempted >= .8) PairingStatus.MATCHES else PairingStatus.PARTIAL
        val spread = progress.any { it.lastAttemptMs - it.firstAttemptMs >= 120_000 } || progress.count { it.attempted > 0 } >= 2
        return if (attempted >= 5 && spread) PairingStatus.MISMATCH else PairingStatus.UNCHECKED
    }

    /** Count coverage only when accepted narration anchors actually intersect this attempted window. */
    fun covered(binding: TextBinding, startMs: Long, lengthMs: Long = 20_000): Boolean =
        binding.anchors.any { it.auto && it.positionMs in startMs until startMs + lengthMs }
}

object AlignmentPolicy {
    const val WINDOW_MS = 20_000L
    const val SPACING_MS = 30_000L
    const val WINDOWS_PER_RUN = 6
    fun allowed(downloaded: Boolean, unmetered: Boolean, charging: Boolean): Boolean = downloaded || (unmetered && charging)
    fun next(progress: AlignmentProgress, durationMs: Long): Long? =
        progress.nextWindowMs.takeIf { !progress.complete && durationMs > 0 && it < durationMs }
    fun advance(progress: AlignmentProgress, startMs: Long, durationMs: Long, matched: Boolean): AlignmentProgress =
        progress.copy(nextWindowMs = startMs + SPACING_MS, attempted = progress.attempted + 1,
            matched = progress.matched + if (matched) 1 else 0,
            firstAttemptMs = progress.firstAttemptMs.takeIf { it >= 0 } ?: startMs, lastAttemptMs = startMs,
            complete = startMs + SPACING_MS >= durationMs)
}
