package app.narrio.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/** A's store can implement this to check playback ownership at its transaction's commit boundary. */
interface GuardedSharedPositionStore : SharedPositionStore {
    suspend fun commitWhen(update: PositionUpdate, allowed: () -> Boolean): SharedPosition?
}

/** D renders [offerUndo] and the confidence; E consumes the mapped text independently of browsing. */
data class SyncJump<T>(val destination: T?, val confidence: MappingConfidence = MappingConfidence.UNMAPPED,
    val offerUndo: Boolean = false, val previous: T? = null, val sequence: Long = 0, val from: PositionOrigin? = null)

object SyncDecisions {
    fun listening(shared: SharedPosition?, source: AudioSource, mapped: MappedAudio?, previous: AudioCursor?, pairing: PairingStatus): SyncJump<AudioCursor> {
        if (shared == null || pairing == PairingStatus.MISMATCH) return SyncJump(null)
        val same = shared.audio?.takeIf { it.sourceId == source.id && source.parts.any { part -> part.id == it.partId } }
        val target = if (shared.origin == PositionOrigin.READING) mapped else same?.let { MappedAudio(it, shared.audioConfidence) } ?: mapped
        val audio = target?.audio ?: return SyncJump(null)
        val distance = previous?.let { distance(source, it, audio) }
        return SyncJump(audio, target.confidence, previous != null && (distance == null || distance > SyncThresholds.UNDO_AUDIO_MS), previous, shared.sequence, shared.origin)
    }

    fun reading(shared: SharedPosition?, editionId: String, mapped: MappedText?, previous: ContentCursor?, pagesMoved: Int?, pairing: PairingStatus): SyncJump<ContentCursor> {
        if (shared == null || pairing == PairingStatus.MISMATCH) return SyncJump(null)
        val target = if (shared.origin == PositionOrigin.READING) shared.text?.takeIf { it.editionId == editionId }?.let { MappedText(it, shared.textConfidence) }
            else mapped
        val text = target?.text?.takeIf { it.editionId == editionId } ?: return SyncJump(null)
        val moved = previous != null && (previous.editionId != text.editionId || previous.resource != text.resource || previous.offset != text.offset)
        return SyncJump(text, target.confidence, moved && (pagesMoved == null || abs(pagesMoved) > SyncThresholds.UNDO_TEXT_PAGES), previous, shared.sequence, shared.origin)
    }

    private fun distance(source: AudioSource, a: AudioCursor, b: AudioCursor): Long? {
        if (a.sourceId != source.id || b.sourceId != source.id) return null
        fun clock(cursor: AudioCursor): Long? {
            val index = source.parts.indexOfFirst { it.id == cursor.partId }.takeIf { it >= 0 } ?: return null
            val before = source.parts.take(index)
            if (before.any { it.durationMs <= 0 }) return null
            return before.sumOf { it.durationMs } + cursor.positionMs
        }
        return abs((clock(a) ?: return null) - (clock(b) ?: return null))
    }
}

/** Wrap A's store with this gate; B must commit through the same instance exposed by AppGraph. */
class AudioOwnedPositionStore(private val delegate: SharedPositionStore) : SharedPositionStore {
    private val mutex = Mutex()
    @Volatile var playingBookId: String? = null
    override fun observe(bookId: String): Flow<SharedPosition?> = delegate.observe(bookId)
    override suspend fun current(bookId: String) = delegate.current(bookId)
    override suspend fun commit(update: PositionUpdate): SharedPosition? = mutex.withLock {
        val allowed = { update.origin != PositionOrigin.READING || playingBookId != update.bookId }
        if (!allowed()) null
        else if (delegate is GuardedSharedPositionStore) delegate.commitWhen(update, allowed)
        else delegate.commit(update)
    }
}

/** Uses elapsed playback activity, never media position deltas (seeks/speed must not count as activity). */
class ListeningActivityGate {
    private var key: Pair<String, String>? = null
    private var tick: Long? = null
    private var elapsed = 0L
    fun reset() { key = null; tick = null; elapsed = 0 }
    fun sample(bookId: String, sourceId: String, playing: Boolean, nowMs: Long): Boolean {
        val next = bookId to sourceId
        if (key != next) { reset(); key = next }
        if (playing) tick?.let { elapsed += (nowMs - it).coerceIn(0, 5_000) }
        tick = if (playing) nowMs else null
        if (elapsed < SyncThresholds.LISTENING_ACTIVITY_MS) return false
        elapsed = 0
        return true
    }
}
