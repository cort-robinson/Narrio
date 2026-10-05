package app.narrio.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
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
    @Query("UPDATE shelf SET bookJson = :json, hasAudio = CASE WHEN sourceJson != '' THEN 1 ELSE :audio END WHERE bookId = :id") suspend fun metadata(id: String, json: String, audio: Boolean)
    @Query("UPDATE shelf SET sourceJson = :source, hasAudio = 1, partId = :part, positionMs = :position, playedAt = :time, state = CASE WHEN state IN ('preparing', 'ready') THEN state ELSE 'listening' END WHERE bookId = :id")
    suspend fun shelfProgress(id: String, source: String, part: String, position: Long, time: Long)
    @Query("SELECT * FROM positions WHERE bookId = :book AND sourceId = :source") suspend fun position(book: String, source: String): SourcePosition?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPosition(position: SourcePosition)
    @Transaction suspend fun progress(id: String, source: String, part: String, position: Long, time: Long) {
        shelfProgress(id, source, part, position, time)
        val sourceId = NarrioJson.decodeFromString<AudioSource>(source).id
        putPosition(SourcePosition(id, sourceId, source, part, position, time))
    }
    @Query("UPDATE shelf SET state = 'preparing', preparationId = :torrent, pendingFormat = :format WHERE bookId = :id") suspend fun preparing(id: String, torrent: Long, format: String)
    @Query("UPDATE shelf SET state = 'listening', preparationId = 0, pendingFormat = '' WHERE bookId = :id") suspend fun finishPreparation(id: String)
    @Query("UPDATE shelf SET state = :state WHERE bookId = :id") suspend fun state(id: String, state: String)
    @Query("DELETE FROM shelf WHERE bookId = :id") suspend fun deleteShelf(id: String)
    @Query("DELETE FROM bookmarks WHERE bookId = :id") suspend fun deleteBookmarks(id: String)
    @Query("DELETE FROM positions WHERE bookId = :id") suspend fun deletePositions(id: String)
    @Query("SELECT * FROM bookmarks WHERE bookId = :id ORDER BY createdAt DESC") fun bookmarks(id: String): Flow<List<BookmarkEntry>>
    @Insert suspend fun bookmark(entry: BookmarkEntry)
    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteBookmark(id: Long)
    @Query("SELECT * FROM book_text WHERE bookId = :id") fun observeBookText(id: String): Flow<BookTextEntry?>
    @Query("SELECT * FROM book_text WHERE bookId = :id") suspend fun bookText(id: String): BookTextEntry?
    @Query("SELECT t.* FROM text_bindings t JOIN book_text b ON t.bookId = b.bookId AND t.editionId = b.documentId WHERE t.bookId = :id") fun observeTextBindings(id: String): Flow<List<TextBindingEntry>>
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
        val json = NarrioJson.encodeToString(book)
        insert(ShelfEntry(book.id, json)); metadata(book.id, json, book.sources.any { it.parts.isNotEmpty() })
    }
    @Transaction suspend fun updateBookDetails(book: Audiobook) {
        val saved = find(book.id) ?: return
        val updated = saved.book().withMetadataFrom(book)
        metadata(book.id, NarrioJson.encodeToString(updated), updated.sources.any { it.parts.isNotEmpty() })
    }
    @Transaction suspend fun remove(id: String) { deleteBookmarks(id); deletePositions(id); deleteShelf(id) }

    @Query("SELECT * FROM ebook_editions WHERE bookId = :book ORDER BY addedAtMs, editionId") suspend fun editions(book: String): List<EbookEditionEntry>
    @Query("SELECT * FROM ebook_editions WHERE bookId = :book ORDER BY addedAtMs, editionId") fun observeEditions(book: String): Flow<List<EbookEditionEntry>>
    @Query("SELECT * FROM ebook_editions WHERE bookId = :book AND editionId = :edition") suspend fun edition(book: String, edition: String): EbookEditionEntry?
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
    @Transaction suspend fun commitPosition(update: PositionUpdate, time: Long): SharedPosition? {
        val position = nextSharedPosition(sharedPosition(update.bookId)?.position(), update, time) ?: return null
        if (find(update.bookId) == null) return null
        val json = NarrioJson.encodeToString(position)
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

@Database(entities = [ShelfEntry::class, BookmarkEntry::class, SourcePosition::class, BookTextEntry::class, TextBindingEntry::class, EbookEditionEntry::class, SharedPositionEntry::class, AnnotationEntry::class, AlignmentJobEntry::class], version = 5, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() { abstract fun library(): LibraryDao }

val LibraryMigration3To4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS book_text (bookId TEXT NOT NULL, documentId TEXT NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES shelf(bookId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS text_bindings (bookId TEXT NOT NULL, sourceId TEXT NOT NULL, partId TEXT NOT NULL, bindingJson TEXT NOT NULL, PRIMARY KEY(bookId, sourceId, partId), FOREIGN KEY(bookId) REFERENCES book_text(bookId) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}
