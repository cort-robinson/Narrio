package app.narrio.data

import androidx.room.*
import app.narrio.domain.*
import kotlinx.coroutines.flow.map

internal fun BookText.editionEntry(book: String, provider: String, added: Long = System.currentTimeMillis()) =
    EbookEditionEntry(book, id, title, author, format, language, attribution, provider, added)

/** The active edition is the single book_text pointer; edition rows never replace each other. */
@Entity(tableName = "ebook_editions", primaryKeys = ["bookId", "editionId"], foreignKeys = [ForeignKey(entity = ShelfEntry::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class EbookEditionEntry(
    val bookId: String, val editionId: String, val title: String = "", val author: String = "",
    val format: String = "", val language: String = "", val attribution: String = "",
    val provider: String = "", val addedAtMs: Long = 0,
) {
    fun edition(active: Boolean) = EbookEdition(editionId, bookId, title, author, format, language, attribution, provider, addedAtMs, active)
}

@Entity(tableName = "shared_positions", foreignKeys = [ForeignKey(entity = ShelfEntry::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class SharedPositionEntry(@PrimaryKey val bookId: String, val positionJson: String, val sequence: Long) {
    fun position(): SharedPosition = NarrioJson.decodeFromString(positionJson)
}

@Entity(tableName = "annotations", indices = [Index(value = ["bookId", "editionId"])], foreignKeys = [ForeignKey(entity = EbookEditionEntry::class, parentColumns = ["bookId", "editionId"], childColumns = ["bookId", "editionId"], onDelete = ForeignKey.CASCADE)])
data class AnnotationEntry(
    @PrimaryKey val id: String,
    val bookId: String, val editionId: String,
    val startCursorJson: String, val endCursorJson: String,
    val color: String, val note: String = "", val selectedText: String = "",
    val createdAtMs: Long, val updatedAtMs: Long,
) {
    init {
        require(start().editionId == editionId && end().editionId == editionId)
    }
    fun start(): ContentCursor = NarrioJson.decodeFromString(startCursorJson)
    fun end(): ContentCursor = NarrioJson.decodeFromString(endCursorJson)
}

@Entity(tableName = "alignment_jobs", primaryKeys = ["bookId", "editionId", "recordingId", "sourceId", "partId"], foreignKeys = [ForeignKey(entity = EbookEditionEntry::class, parentColumns = ["bookId", "editionId"], childColumns = ["bookId", "editionId"], onDelete = ForeignKey.CASCADE)])
data class AlignmentJobEntry(
    val bookId: String, val editionId: String, val recordingId: String, val sourceId: String, val partId: String,
    val status: String = "queued", val progress: Double = 0.0,
    val textFingerprint: String, val audioFingerprint: String, val normalizationVersion: Int = 1,
    val alignmentVersion: Int = 1, val resumePositionMs: Long = 0, val updatedAtMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val attempted: Int = 0,
    @ColumnInfo(defaultValue = "0") val matched: Int = 0,
    @ColumnInfo(defaultValue = "-1") val firstAttemptMs: Long = -1,
    @ColumnInfo(defaultValue = "-1") val lastAttemptMs: Long = -1,
)

data class LibraryShelfEntry(
    @Embedded val entry: ShelfEntry,
    val hasEbook: Boolean,
    val sharedPositionJson: String?,
) {
    val formats: Set<BookFormat> get() = buildSet {
        if (entry.hasAudio || entry.sourceJson.isNotBlank()) add(BookFormat.AUDIO)
        if (hasEbook) add(BookFormat.EBOOK)
    }
    val sharedPosition: SharedPosition? get() = sharedPositionJson?.let { NarrioJson.decodeFromString(it) }
    val lastMode: PositionOrigin? get() = sharedPosition?.origin
    /** Unknown durations stay unknown instead of inventing a percentage. */
    val progression: Double? get() {
        val position = sharedPosition ?: return null
        if (position.origin == PositionOrigin.READING) return position.text?.progression
        val audio = position.audio ?: return null
        val source = entry.book().sources.firstOrNull { it.id == audio.sourceId } ?: entry.source()?.takeIf { it.id == audio.sourceId } ?: return null
        val index = source.parts.indexOfFirst { it.id == audio.partId }
        if (index < 0 || source.parts.any { it.durationMs <= 0 }) return null
        return ((source.parts.take(index).sumOf { it.durationMs } + audio.positionMs).toDouble() / source.parts.sumOf { it.durationMs }).coerceIn(0.0, 1.0)
    }
}

/** Pure conflict rule shared by the Room transaction and JVM tests. Absence has sequence zero. */
internal fun nextSharedPosition(current: SharedPosition?, update: PositionUpdate, time: Long): SharedPosition? {
    if (update.basedOnSequence != (current?.sequence ?: 0L)) return null
    require(update.basedOnSequence >= 0 && update.basedOnSequence < Long.MAX_VALUE)
    require(current == null || current.bookId == update.bookId)
    require(update.text == null || (update.text.offset >= 0 && update.text.progression in 0.0..1.0))
    require(update.audio == null || update.audio.positionMs >= 0)
    return SharedPosition(update.bookId, update.origin, update.text, update.audio, update.textConfidence,
        update.audioConfidence, update.basedOnSequence + 1, time)
}

class RoomSharedPositionStore(private val library: LibraryDao, private val now: () -> Long = System::currentTimeMillis) : GuardedSharedPositionStore {
    override fun observe(bookId: String) = library.observeSharedPosition(bookId).map { it?.position() }
    override suspend fun current(bookId: String) = library.sharedPosition(bookId)?.position()
    override suspend fun commit(update: PositionUpdate) = library.commitPosition(update, now())
    override suspend fun commitWhen(update: PositionUpdate, allowed: () -> Boolean) = library.commitPosition(update, now(), allowed)
}

fun BookmarkEntry.contentCursor(): ContentCursor? {
    return ContentCursor(editionId ?: return null, resource ?: return null, offset ?: return null,
        normalizationVersion ?: 1, progression ?: 0.0, locatorJson.orEmpty())
}
