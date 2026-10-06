package app.narrio.playback

import app.narrio.domain.*
import app.narrio.data.alignmentKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class WindowOutcome { MATCHED, NO_MATCH, UNAVAILABLE, UNSUPPORTED }
data class RecognizedWindow(val outcome: WindowOutcome, val anchors: List<TextAnchor> = emptyList())
fun interface NarrationWindowRecognizer {
    suspend fun window(target: SyncTarget, startMs: Long): RecognizedWindow
}

data class ReadingSyncState(
    val bookId: String = "", val editionId: String = "", val sourceId: String = "",
    val pairing: PairingStatus = PairingStatus.UNCHECKED,
    val audioJump: SyncJump<AudioCursor>? = null,
    val textJump: SyncJump<ContentCursor>? = null,
    val confidence: MappingConfidence = MappingConfidence.UNMAPPED,
    val correcting: Boolean = false,
)

/** C's UI boundary: D consumes jumps/confidence; E consumes mapped positions; no snackbar policy in UI. */
class ReadingSync(
    private val positions: SharedPositionStore,
    private val mapper: PositionMapper,
    private val repository: PositionMappingRepository,
    private val jobs: AlignmentJobRepository,
) {
    private val mutableState = MutableStateFlow(ReadingSyncState())
    val state = mutableState.asStateFlow()

    suspend fun pairing(bookId: String, snapshot: MappingSnapshot): PairingStatus {
        val progress = snapshot.source.parts.map { jobs.progress(alignmentKey(bookId, snapshot, it.id)) }
        val status = PairingEvidence.status(progress)
        // Foreground recognized anchors are positive evidence even before background attempts exist.
        val recognized = snapshot.bindings.any { it.documentId == snapshot.document.id && it.sourceId == snapshot.source.id && it.anchors.any { a -> a.auto } }
        return if (recognized && status in setOf(PairingStatus.UNCHECKED, PairingStatus.MISMATCH)) PairingStatus.PARTIAL else status
    }

    suspend fun refreshPairing(bookId: String, snapshot: MappingSnapshot) {
        val pairing = pairing(bookId, snapshot)
        mutableState.update { state ->
            if (state.bookId != bookId || state.editionId != snapshot.document.id || state.sourceId != snapshot.source.id) state
            else state.copy(pairing = pairing,
                audioJump = state.audioJump.takeUnless { pairing == PairingStatus.MISMATCH },
                textJump = state.textJump.takeUnless { pairing == PairingStatus.MISMATCH })
        }
    }

    suspend fun listeningStart(bookId: String, source: AudioSource, previous: AudioCursor?): SyncJump<AudioCursor> {
        val shared = positions.current(bookId)
        val snapshot = repository.snapshot(bookId, source.id)
        val pairing = snapshot?.let { pairing(bookId, it) } ?: PairingStatus.UNCHECKED
        val mapped = shared?.text?.let { mapper.audioFor(bookId, it, source.id) }
        val jump = SyncDecisions.listening(shared, source, mapped, previous, pairing)
        mutableState.value = ReadingSyncState(bookId, snapshot?.document?.id.orEmpty(), source.id, pairing, audioJump = jump, confidence = jump.confidence)
        return jump
    }

    suspend fun readingStart(bookId: String, editionId: String, previous: ContentCursor?, pagesMoved: Int?): SyncJump<ContentCursor> {
        val shared = positions.current(bookId)
        val audio = shared?.audio
        val snapshot = audio?.let { repository.snapshot(bookId, it.sourceId) }
        val pairing = snapshot?.let { pairing(bookId, it) } ?: PairingStatus.UNCHECKED
        val mapped = audio?.let { mapper.textFor(bookId, it) }
        val jump = SyncDecisions.reading(shared, editionId, mapped, previous, pagesMoved, pairing)
        mutableState.value = ReadingSyncState(bookId, editionId, audio?.sourceId.orEmpty(), pairing, textJump = jump, confidence = jump.confidence)
        return jump
    }

    suspend fun listeningCommit(bookId: String, audio: AudioCursor, basedOnSequence: Long): SharedPosition? {
        val snapshot = repository.snapshot(bookId, audio.sourceId)
        val pairing = snapshot?.let { pairing(bookId, it) } ?: PairingStatus.UNCHECKED
        val mapped = if (pairing == PairingStatus.MISMATCH) null else mapper.textFor(bookId, audio)
        val committed = positions.commit(PositionUpdate(bookId, PositionOrigin.LISTENING, mapped?.text, audio,
            mapped?.confidence ?: MappingConfidence.UNMAPPED, MappingConfidence.EXACT, basedOnSequence))
        if (committed != null) mutableState.value = mutableState.value.copy(bookId = bookId, editionId = snapshot?.document?.id.orEmpty(),
            sourceId = audio.sourceId, pairing = pairing, confidence = mapped?.confidence ?: MappingConfidence.UNMAPPED)
        return committed
    }

    /** One installed-model window. Only a bracketed target can become EXACT; extrapolation stays estimated. */
    suspend fun correct(target: SyncTarget, wanted: ContentCursor, recognizer: NarrationWindowRecognizer,
        latest: suspend () -> SyncTarget?, save: suspend (String, TextBinding) -> TextBinding): MappedAudio? {
        if (wanted.editionId != target.document.id || wanted.normalizationVersion != target.document.normalizationVersion) return null
        mutableState.value = mutableState.value.copy(correcting = true, confidence = MappingConfidence.ESTIMATED)
        try {
            val start = (target.positionMs - AlignmentPolicy.WINDOW_MS / 2).coerceIn(0, (target.durationMs - AlignmentPolicy.WINDOW_MS).coerceAtLeast(0))
            val result = recognizer.window(target, start)
            if (result.outcome != WindowOutcome.MATCHED) return null
            val current = latest() ?: return null
            if (current.book.id != target.book.id || current.document.id != wanted.editionId || current.source.id != target.source.id || current.part.id != target.part.id) return null
            val base = current.binding?.takeIf { it.documentId == wanted.editionId && it.sourceId == target.source.id && it.partId == target.part.id }
                ?: TextBinding(target.document.id, target.source.id, target.part.id, WHOLE_BOOK)
            val candidate = FollowAlongTiming.mergeAuto(target.document, base, result.anchors, target.durationMs)
            val merged = save(target.book.id, candidate)
            if (merged.documentId != wanted.editionId || merged.sourceId != target.source.id || merged.partId != target.part.id) return null
            val snapshot = MappingSnapshot(target.document, target.source.copy(parts = target.source.parts.map {
                if (it.id == target.part.id) it.copy(durationMs = target.durationMs) else it
            }), listOf(merged))
            val mapped = MappingEngine.audioFor(snapshot, wanted) ?: return null
            if (mapped.audio.partId != target.part.id) return null
            // Reversing the candidate must be inside a locally confirmed narration range.
            if (MappingEngine.textFor(snapshot, mapped.audio)?.confidence != MappingConfidence.EXACT) return null
            val corrected = mapped.copy(confidence = MappingConfidence.EXACT)
            mutableState.value = mutableState.value.copy(confidence = MappingConfidence.EXACT)
            return corrected
        } finally {
            mutableState.update { state ->
                if (state.bookId.isBlank() || (state.bookId == target.book.id && state.sourceId == target.source.id)) state.copy(correcting = false) else state
            }
        }
    }

    fun clearJump() { mutableState.value = mutableState.value.copy(audioJump = null, textJump = null) }

    fun sentenceSeek(bookId: String, text: ContentCursor, mapped: MappedAudio, pairing: PairingStatus) {
        mutableState.value = ReadingSyncState(bookId, text.editionId, mapped.audio.sourceId, pairing, confidence = mapped.confidence)
    }
}
