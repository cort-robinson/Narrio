package app.narrio.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import app.narrio.domain.*
import kotlinx.serialization.encodeToString

@Entity(tableName = "shelf")
data class ShelfEntry(
    @PrimaryKey val bookId: String,
    val bookJson: String,
    val sourceJson: String = "",
    val partId: String = "",
    val positionMs: Long = 0,
    val savedAt: Long = System.currentTimeMillis(),
    val playedAt: Long = 0,
    val state: String = "saved",
    val preparationId: Long = 0,
    val pendingFormat: String = "",
    @ColumnInfo(defaultValue = "0") val hasAudio: Boolean = false,
    /** When the listener finished the book, by hand or by reaching its end; 0 while unfinished. */
    @ColumnInfo(defaultValue = "0") val finishedAt: Long = 0,
) {
    fun book(): Audiobook = NarrioJson.decodeFromString(bookJson)
    fun source(): AudioSource? = sourceJson.takeIf { it.isNotBlank() }?.let { NarrioJson.decodeFromString(it) }
}

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val sourceId: String,
    val partId: String,
    val positionMs: Long,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
    val editionId: String? = null,
    val resource: String? = null,
    val offset: Int? = null,
    val normalizationVersion: Int? = null,
    val progression: Double? = null,
    val locatorJson: String? = null,
)

@Entity(tableName = "positions", primaryKeys = ["bookId", "sourceId"])
data class SourcePosition(val bookId: String, val sourceId: String, val sourceJson: String, val partId: String, val positionMs: Long, val updatedAt: Long)

@Entity(tableName = "book_text", indices = [Index(value = ["bookId", "documentId"])], foreignKeys = [ForeignKey(entity = ShelfEntry::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = EbookEditionEntry::class, parentColumns = ["bookId", "editionId"], childColumns = ["bookId", "documentId"], onDelete = ForeignKey.CASCADE)])
data class BookTextEntry(@PrimaryKey val bookId: String, val documentId: String)

@Entity(tableName = "text_bindings", primaryKeys = ["bookId", "editionId", "sourceId", "partId"], foreignKeys = [ForeignKey(entity = EbookEditionEntry::class, parentColumns = ["bookId", "editionId"], childColumns = ["bookId", "editionId"], onDelete = ForeignKey.CASCADE)])
data class TextBindingEntry(val bookId: String, val sourceId: String, val partId: String, val bindingJson: String,
    val editionId: String = NarrioJson.decodeFromString<TextBinding>(bindingJson).documentId) {
    fun binding(): TextBinding = NarrioJson.decodeFromString(bindingJson)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM shelf ORDER BY playedAt DESC, savedAt DESC") fun observeShelf(): Flow<List<ShelfEntry>>
    @Query("SELECT * FROM shelf WHERE bookId = :id") suspend fun find(id: String): ShelfEntry?
    @Query("SELECT * FROM shelf WHERE playedAt > 0 AND sourceJson != '' ORDER BY playedAt DESC LIMIT 1") suspend fun lastPlayed(): ShelfEntry?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(entry: ShelfEntry)
    @Upsert suspend fun putShelf(entry: ShelfEntry)
    @Query("UPDATE shelf SET bookJson = :json, hasAudio = CASE WHEN sourceJson != '' THEN 1 ELSE :audio END WHERE bookId = :id") suspend fun metadata(id: String, json: String, audio: Boolean)
    @Query("UPDATE shelf SET sourceJson = :json WHERE bookId = :book") suspend fun sourceDetails(book: String, json: String)
    @Query("UPDATE shelf SET sourceJson = :source, hasAudio = 1, partId = :part, positionMs = :position, playedAt = :time, state = CASE WHEN state IN ('preparing', 'ready') THEN state ELSE 'listening' END WHERE bookId = :id")
    suspend fun shelfProgress(id: String, source: String, part: String, position: Long, time: Long)
    @Query("SELECT * FROM positions WHERE bookId = :book AND sourceId = :source") suspend fun position(book: String, source: String): SourcePosition?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPosition(position: SourcePosition)
    @Query("SELECT * FROM positions WHERE bookId = :book") fun observePositions(book: String): Flow<List<SourcePosition>>
    @Transaction suspend fun progress(id: String, source: String, part: String, position: Long, time: Long) {
        val incoming = NarrioJson.decodeFromString<AudioSource>(source)
        val previous = find(id)?.book()?.sources?.firstOrNull { it.id == incoming.id }
        val json = NarrioJson.encodeToString(incoming.withKnownDurations(previous))
        shelfProgress(id, json, part, position, time)
        putPosition(SourcePosition(id, incoming.id, json, part, position, time))
    }
    @Query("UPDATE shelf SET state = 'preparing', preparationId = :torrent, pendingFormat = :format WHERE bookId = :id") suspend fun preparing(id: String, torrent: Long, format: String)
    @Query("UPDATE shelf SET state = 'listening', preparationId = 0, pendingFormat = '' WHERE bookId = :id") suspend fun finishPreparation(id: String)
    @Query("UPDATE shelf SET state = :state WHERE bookId = :id") suspend fun state(id: String, state: String)
    /** Marks a book finished, keeping the first finish time when it already is. */
    @Query("UPDATE shelf SET finishedAt = :time WHERE bookId = :id AND finishedAt = 0") suspend fun finished(id: String, time: Long = System.currentTimeMillis())
    /** Reopens a finished book; an unfinished one is left untouched, so listening and reading don't wake shelf observers. */
    @Query("UPDATE shelf SET finishedAt = 0 WHERE bookId = :id AND finishedAt > 0") suspend fun unfinished(id: String)
    @Query("DELETE FROM shelf WHERE bookId = :id") suspend fun deleteShelf(id: String)
    @Query("DELETE FROM bookmarks WHERE bookId = :id") suspend fun deleteBookmarks(id: String)
    @Query("DELETE FROM positions WHERE bookId = :id") suspend fun deletePositions(id: String)
    @Query("SELECT * FROM bookmarks WHERE bookId = :id ORDER BY createdAt DESC") fun bookmarks(id: String): Flow<List<BookmarkEntry>>
    @Insert suspend fun bookmark(entry: BookmarkEntry)
    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteBookmark(id: Long)
    @Query("SELECT * FROM book_text WHERE bookId = :id") fun observeBookText(id: String): Flow<BookTextEntry?>
    @Query("SELECT * FROM book_text WHERE bookId = :id") suspend fun bookText(id: String): BookTextEntry?
    @Query("SELECT t.* FROM text_bindings t JOIN book_text b ON t.bookId = b.bookId AND t.editionId = b.documentId WHERE t.bookId = :id") fun observeTextBindings(id: String): Flow<List<TextBindingEntry>>
    @Query("SELECT * FROM text_bindings WHERE bookId = :book AND editionId = :edition")
    suspend fun editionBindings(book: String, edition: String): List<TextBindingEntry>
    @Transaction suspend fun bindActive(book: String, binding: TextBinding) {
        require(bookText(book)?.documentId == binding.documentId) { "The active ebook changed during narration sync." }
        putTextBinding(TextBindingEntry(book, binding.sourceId, binding.partId, NarrioJson.encodeToString(binding)))
    }
    @Transaction suspend fun mergeNarration(book: String, document: BookText, fallback: TextBinding,
        anchors: List<TextAnchor>, durationMs: Long): TextBinding {
        require(bookText(book)?.documentId == document.id) { "The active ebook changed during narration sync." }
        val latest = editionBindings(book, document.id).map { it.binding() }
            .firstOrNull { it.sourceId == fallback.sourceId && it.partId == fallback.partId } ?: fallback
        val merged = FollowAlongTiming.mergeAuto(document, latest, anchors, durationMs)
        val auto = merged.anchors.filter { it.auto }
        val bounded = if (auto.size <= 2048) merged else merged.copy(anchors = (merged.anchors.filterNot { it.auto } +
            (0 until 2048).map { auto[it * auto.lastIndex / 2047] }).sortedBy { it.positionMs })
        bindActive(book, bounded)
        return bounded
    }
    @Upsert suspend fun putBookText(entry: BookTextEntry)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTextBinding(entry: TextBindingEntry)
    @Query("DELETE FROM text_bindings WHERE bookId = :id") suspend fun deleteTextBindings(id: String)
    @Query("DELETE FROM text_bindings WHERE bookId = :id AND editionId = (SELECT documentId FROM book_text WHERE bookId = :id) AND sourceId = :source AND partId = :part") suspend fun deleteTextBinding(id: String, source: String, part: String)
    @Query("DELETE FROM book_text WHERE bookId = :id") suspend fun deleteBookText(id: String)
    @Transaction suspend fun attachText(book: Audiobook, documentId: String) {
        save(book)
        if (edition(book.id, documentId) == null) putEdition(EbookEditionEntry(book.id, documentId, title = book.title, author = book.author))
        putBookText(BookTextEntry(book.id, documentId))
    }
    @Transaction suspend fun save(book: Audiobook) {
        val previous = find(book.id)?.book()
        val sources = book.sources.map { it.withKnownDurations(previous?.sources?.firstOrNull { old -> old.id == it.id }) }
        val merged = book.copy(sources = (sources + previous?.sources.orEmpty()).distinctBy { it.id })
        val json = NarrioJson.encodeToString(merged)
        insert(ShelfEntry(book.id, json)); metadata(book.id, json, merged.sources.any { it.parts.isNotEmpty() })
    }
    @Transaction suspend fun updateBookDetails(book: Audiobook) {
        val saved = find(book.id) ?: return
        val updated = saved.book().withMetadataFrom(book)
        metadata(book.id, NarrioJson.encodeToString(updated), updated.sources.any { it.parts.isNotEmpty() })
    }
    @Transaction suspend fun recordingDuration(bookId: String, sourceId: String, partId: String, durationMs: Long) {
        if (durationMs <= 0) return
        val entry = find(bookId) ?: return
        fun AudioSource.measured() = if (id != sourceId) this else copy(parts = parts.map {
            if (it.id == partId) it.copy(durationMs = durationMs) else it
        })
        val book = entry.book()
        val measured = book.copy(sources = book.sources.map { it.measured() })
        if (measured != book) metadata(bookId, NarrioJson.encodeToString(measured), measured.sources.any { it.parts.isNotEmpty() })
        entry.source()?.takeIf { it.id == sourceId }?.let { source ->
            val updated = source.measured()
            if (updated != source) sourceDetails(bookId, NarrioJson.encodeToString(updated))
        }
        position(bookId, sourceId)?.let { history ->
            val source = NarrioJson.decodeFromString<AudioSource>(history.sourceJson)
            val updated = source.measured()
            if (updated != source) putPosition(history.copy(sourceJson = NarrioJson.encodeToString(updated)))
        }
    }
    @Transaction suspend fun remove(id: String) { deleteBookmarks(id); deletePositions(id); deleteShelf(id) }

    /** An explicit recording choice associates old recording-scoped data with its parent book. */
    @Transaction suspend fun adoptRecording(oldId: String, book: Audiobook) {
        if (oldId == book.id) return
        val old = find(oldId) ?: return
        val target = find(book.id)
        save(book.copy(sources = (book.sources + old.book().sources).distinctBy { it.id }))
        if (target == null || old.playedAt > target.playedAt) {
            val saved = find(book.id)!!
            putShelf(old.copy(bookId = book.id, bookJson = saved.bookJson, hasAudio = saved.hasAudio))
        }
        val active = bookText(book.id)
        val oldActive = bookText(oldId)
        for (edition in editions(oldId)) {
            if (edition(book.id, edition.editionId) == null) putEdition(edition.copy(bookId = book.id))
            for (binding in editionBindings(oldId, edition.editionId)) {
                if (editionBindings(book.id, edition.editionId).none { it.sourceId == binding.sourceId && it.partId == binding.partId })
                    putTextBinding(binding.copy(bookId = book.id))
            }
            annotations(oldId, edition.editionId).first().forEach { putAnnotation(it.copy(bookId = book.id)) }
            alignmentJobs(oldId, edition.editionId).first().forEach { putAlignmentJob(it.copy(bookId = book.id)) }
        }
        if (active == null && oldActive != null) activateEdition(book.id, oldActive.documentId)
        observePositions(oldId).first().forEach { history ->
            if ((position(book.id, history.sourceId)?.updatedAt ?: -1) < history.updatedAt) putPosition(history.copy(bookId = book.id))
        }
        bookmarks(oldId).first().forEach { mark -> deleteBookmark(mark.id); bookmark(mark.copy(bookId = book.id)) }
        val current = sharedPosition(book.id)?.position()
        sharedPosition(oldId)?.position()?.let { shared ->
            if (current == null) {
                val moved = shared.copy(bookId = book.id)
                insertSharedPosition(SharedPositionEntry(book.id, NarrioJson.encodeToString(moved), moved.sequence))
            } else if (shared.updatedAtMs > current.updatedAtMs) {
                val moved = shared.copy(bookId = book.id, sequence = maxOf(current.sequence, shared.sequence) + 1)
                compareAndSetPosition(book.id, current.sequence, moved.sequence, NarrioJson.encodeToString(moved))
            }
        }
        deletePositions(oldId)
        deleteShelf(oldId)
    }

    @Query("SELECT * FROM ebook_editions WHERE bookId = :book ORDER BY addedAtMs, editionId") suspend fun editions(book: String): List<EbookEditionEntry>
    @Query("SELECT * FROM ebook_editions WHERE bookId = :book ORDER BY addedAtMs, editionId") fun observeEditions(book: String): Flow<List<EbookEditionEntry>>
    @Query("SELECT * FROM ebook_editions WHERE bookId = :book AND editionId = :edition") suspend fun edition(book: String, edition: String): EbookEditionEntry?
    @Query("SELECT s.* FROM shelf s JOIN ebook_editions e ON s.bookId = e.bookId WHERE e.editionId = :edition ORDER BY s.savedAt LIMIT 1")
    suspend fun bookWithEdition(edition: String): ShelfEntry?
    @Upsert suspend fun putEdition(entry: EbookEditionEntry)
    @Transaction suspend fun attachEdition(book: Audiobook, entry: EbookEditionEntry) {
        save(book)
        putEdition(entry.copy(addedAtMs = edition(book.id, entry.editionId)?.addedAtMs ?: entry.addedAtMs))
        activateEdition(book.id, entry.editionId)
    }
    @Query("DELETE FROM ebook_editions WHERE bookId = :book AND editionId = :edition") suspend fun deleteEdition(book: String, edition: String)
    @Transaction suspend fun activateEdition(book: String, edition: String) {
        require(edition(book, edition) != null) { "This edition is no longer in the library." }
        putBookText(BookTextEntry(book, edition))
    }
    @Transaction suspend fun removeEdition(book: String, edition: String) {
        if (bookText(book)?.documentId == edition) {
            deleteBookText(book)
            editions(book).firstOrNull { it.editionId != edition }?.let { putBookText(BookTextEntry(book, it.editionId)) }
        }
        deleteEdition(book, edition)
    }
    @Transaction suspend fun removeText(book: String) {
        deleteBookText(book)
        editions(book).forEach { deleteEdition(book, it.editionId) }
    }
    @Query("SELECT * FROM shared_positions WHERE bookId = :book") suspend fun sharedPosition(book: String): SharedPositionEntry?
    @Query("SELECT * FROM shared_positions WHERE bookId = :book") fun observeSharedPosition(book: String): Flow<SharedPositionEntry?>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSharedPosition(entry: SharedPositionEntry): Long
    @Query("UPDATE shared_positions SET positionJson = :json, sequence = :next WHERE bookId = :book AND sequence = :expected")
    suspend fun compareAndSetPosition(book: String, expected: Long, next: Long, json: String): Int
    @Transaction suspend fun commitPosition(update: PositionUpdate, time: Long, allowed: () -> Boolean = { true }): SharedPosition? {
        val position = nextSharedPosition(sharedPosition(update.bookId)?.position(), update, time) ?: return null
        if (find(update.bookId) == null) return null
        val json = NarrioJson.encodeToString(position)
        // This runs after Room's transaction queue and reads, immediately before the guarded write.
        if (!allowed()) return null
        val saved = if (position.sequence == 1L) insertSharedPosition(SharedPositionEntry(update.bookId, json, 1)) != -1L
            else compareAndSetPosition(update.bookId, update.basedOnSequence, position.sequence, json) == 1
        return position.takeIf { saved }
    }
    @Query("SELECT * FROM annotations WHERE bookId = :book AND editionId = :edition ORDER BY createdAtMs, id") fun annotations(book: String, edition: String): Flow<List<AnnotationEntry>>
    @Upsert suspend fun putAnnotation(entry: AnnotationEntry)
    @Query("DELETE FROM annotations WHERE id = :id") suspend fun deleteAnnotation(id: String)
    @Query("SELECT * FROM alignment_jobs WHERE bookId = :book AND editionId = :edition") fun alignmentJobs(book: String, edition: String): Flow<List<AlignmentJobEntry>>
    @Query("SELECT * FROM alignment_jobs WHERE bookId = :book AND editionId = :edition AND recordingId = :recording AND sourceId = :source AND partId = :part")
    suspend fun alignmentJob(book: String, edition: String, recording: String, source: String, part: String): AlignmentJobEntry?
    @Upsert suspend fun putAlignmentJob(entry: AlignmentJobEntry)
    @Query("SELECT s.*, EXISTS(SELECT 1 FROM ebook_editions e WHERE e.bookId = s.bookId AND e.format IN ('EPUB', 'TXT')) AS hasEbook, p.positionJson AS sharedPositionJson FROM shelf s LEFT JOIN shared_positions p ON p.bookId = s.bookId WHERE (:audio = 0 OR s.hasAudio = 1) AND (:ebook = 0 OR EXISTS(SELECT 1 FROM ebook_editions e WHERE e.bookId = s.bookId AND e.format IN ('EPUB', 'TXT'))) ORDER BY s.playedAt DESC, s.savedAt DESC")
    fun observeShelfState(audio: Boolean = false, ebook: Boolean = false): Flow<List<LibraryShelfEntry>>
}

@Database(entities = [ShelfEntry::class, BookmarkEntry::class, SourcePosition::class, BookTextEntry::class, TextBindingEntry::class, EbookEditionEntry::class, SharedPositionEntry::class, AnnotationEntry::class, AlignmentJobEntry::class], version = 6, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() { abstract fun library(): LibraryDao }

/** Adds the Finished state; every existing book starts unfinished. */
val LibraryMigration5To6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE shelf ADD COLUMN finishedAt INTEGER NOT NULL DEFAULT 0") }
}

val LibraryMigration3To4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS book_text (bookId TEXT NOT NULL, documentId TEXT NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES shelf(bookId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS text_bindings (bookId TEXT NOT NULL, sourceId TEXT NOT NULL, partId TEXT NOT NULL, bindingJson TEXT NOT NULL, PRIMARY KEY(bookId, sourceId, partId), FOREIGN KEY(bookId) REFERENCES book_text(bookId) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}
