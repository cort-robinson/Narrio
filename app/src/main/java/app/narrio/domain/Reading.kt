package app.narrio.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/*
 * Shared contract for ebook reading and reading/listening sync. See docs/EREADER.md.
 * `Audiobook` remains the book-level metadata record and its id the book identity; a book's formats come
 * from the recordings in `sources` and the ebook editions attached to it, so an ebook-only book has no sources.
 */

enum class BookFormat { AUDIO, EBOOK }

/** How well narration sync has confirmed that an edition's text follows a recording. */
@Serializable
enum class PairingStatus { UNCHECKED, MATCHES, PARTIAL, MISMATCH }

/** One ebook edition attached to a book. [id] is the `BookText.id` content fingerprint of its original bytes. */
@Serializable
data class EbookEdition(
    val id: String,
    val bookId: String,
    val title: String,
    val author: String,
    val format: String,
    val language: String = "",
    val attribution: String = "",
    val provider: String = "",
    val addedAtMs: Long = 0,
    val active: Boolean = false,
)

/**
 * A durable location in an ebook edition. [resource] and [offset] use the same space as `TextPassage.resource`
 * and `TextPassage.offset` produced by `BookTextParser` at [normalizationVersion]; that pair is the identity used
 * for alignment and sync. [progression] (0..1 across the edition) and [locatorJson] (a Readium Locator, for exact
 * restoration in the reader) are views and caches only, never the authority. For TXT, [locatorJson] refers
 * to the deterministic internal EPUB generated from the original bytes.
 */
@Serializable
data class ContentCursor(
    val editionId: String,
    val resource: String,
    val offset: Int,
    val normalizationVersion: Int = 1,
    val progression: Double = 0.0,
    val locatorJson: String = "",
)

@Serializable
data class AudioCursor(val sourceId: String, val partId: String, val positionMs: Long)

@Serializable
enum class PositionOrigin { READING, LISTENING }

/** EXACT: confirmed by narration anchors or supplied timing. ESTIMATED: shown with "≈" until corrected. */
@Serializable
enum class MappingConfidence { EXACT, ESTIMATED, UNMAPPED }

/**
 * One shared position per book. [origin] is the mode whose activity last moved it; the other side is its mapped
 * counterpart, if any. [sequence] increases with every committed update so an older service or reader save can't
 * overwrite a newer action.
 */
@Serializable
data class SharedPosition(
    val bookId: String,
    val origin: PositionOrigin,
    val text: ContentCursor? = null,
    val audio: AudioCursor? = null,
    val textConfidence: MappingConfidence = MappingConfidence.UNMAPPED,
    val audioConfidence: MappingConfidence = MappingConfidence.UNMAPPED,
    val sequence: Long = 0,
    val updatedAtMs: Long = 0,
)

/** [basedOnSequence] is the sequence the writer last observed; a stale write is rejected rather than merged. */
data class PositionUpdate(
    val bookId: String,
    val origin: PositionOrigin,
    val text: ContentCursor? = null,
    val audio: AudioCursor? = null,
    val textConfidence: MappingConfidence = MappingConfidence.UNMAPPED,
    val audioConfidence: MappingConfidence = MappingConfidence.UNMAPPED,
    val basedOnSequence: Long,
)

interface SharedPositionStore {
    fun observe(bookId: String): Flow<SharedPosition?>
    suspend fun current(bookId: String): SharedPosition?
    /** Returns the committed position, or null when [PositionUpdate.basedOnSequence] is stale. */
    suspend fun commit(update: PositionUpdate): SharedPosition?
}

data class MappedAudio(val audio: AudioCursor, val confidence: MappingConfidence)
data class MappedText(val text: ContentCursor, val confidence: MappingConfidence)

/** Converts between text and audio for one book's active edition and a chosen recording. */
interface PositionMapper {
    suspend fun audioFor(bookId: String, cursor: ContentCursor, sourceId: String): MappedAudio?
    suspend fun textFor(bookId: String, audio: AudioCursor): MappedText?
}

/** Original edition bytes in private storage, for the reader. */
interface EditionFiles {
    suspend fun editions(bookId: String): List<EbookEdition>
    suspend fun original(bookId: String, editionId: String): java.io.File?
}

/** Thresholds agreed for activity and jump confirmation; tunable in code, not user settings. */
object SyncThresholds {
    const val READING_DWELL_MS = 3_000L
    const val READING_PAGE_TURNS = 2
    const val LISTENING_ACTIVITY_MS = 10_000L
    /** Jumps smaller than this sync silently; larger ones offer Undo. */
    const val UNDO_AUDIO_MS = 30_000L
    const val UNDO_TEXT_PAGES = 1
}
