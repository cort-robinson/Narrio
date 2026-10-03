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
)

@Entity(tableName = "positions", primaryKeys = ["bookId", "sourceId"])
data class SourcePosition(val bookId: String, val sourceId: String, val sourceJson: String, val partId: String, val positionMs: Long, val updatedAt: Long)

@Entity(tableName = "book_text", foreignKeys = [ForeignKey(entity = ShelfEntry::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class BookTextEntry(@PrimaryKey val bookId: String, val documentId: String)

@Entity(tableName = "text_bindings", primaryKeys = ["bookId", "sourceId", "partId"], foreignKeys = [ForeignKey(entity = BookTextEntry::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class TextBindingEntry(val bookId: String, val sourceId: String, val partId: String, val bindingJson: String) {
    fun binding(): TextBinding = NarrioJson.decodeFromString(bindingJson)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM shelf ORDER BY playedAt DESC, savedAt DESC") fun observeShelf(): Flow<List<ShelfEntry>>
    @Query("SELECT * FROM shelf WHERE bookId = :id") suspend fun find(id: String): ShelfEntry?
    @Query("SELECT * FROM shelf WHERE playedAt > 0 AND sourceJson != '' ORDER BY playedAt DESC LIMIT 1") suspend fun lastPlayed(): ShelfEntry?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(entry: ShelfEntry)
    @Query("UPDATE shelf SET bookJson = :json WHERE bookId = :id") suspend fun metadata(id: String, json: String)
    @Query("UPDATE shelf SET sourceJson = :source, partId = :part, positionMs = :position, playedAt = :time, state = CASE WHEN state IN ('preparing', 'ready') THEN state ELSE 'listening' END WHERE bookId = :id")
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
    @Query("SELECT * FROM text_bindings WHERE bookId = :id") fun observeTextBindings(id: String): Flow<List<TextBindingEntry>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBookText(entry: BookTextEntry)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTextBinding(entry: TextBindingEntry)
    @Query("DELETE FROM text_bindings WHERE bookId = :id") suspend fun deleteTextBindings(id: String)
    @Query("DELETE FROM text_bindings WHERE bookId = :id AND sourceId = :source AND partId = :part") suspend fun deleteTextBinding(id: String, source: String, part: String)
    @Query("DELETE FROM book_text WHERE bookId = :id") suspend fun deleteBookText(id: String)
    @Transaction suspend fun attachText(book: Audiobook, documentId: String) {
        save(book); deleteTextBindings(book.id); putBookText(BookTextEntry(book.id, documentId))
    }
    @Transaction suspend fun save(book: Audiobook) {
        val json = NarrioJson.encodeToString(book)
        insert(ShelfEntry(book.id, json)); metadata(book.id, json)
    }
    @Transaction suspend fun updateBookDetails(book: Audiobook) {
        val saved = find(book.id) ?: return
        metadata(book.id, NarrioJson.encodeToString(saved.book().withMetadataFrom(book)))
    }
    @Transaction suspend fun remove(id: String) { deleteBookmarks(id); deletePositions(id); deleteShelf(id) }
}

@Database(entities = [ShelfEntry::class, BookmarkEntry::class, SourcePosition::class, BookTextEntry::class, TextBindingEntry::class], version = 4, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() { abstract fun library(): LibraryDao }

val LibraryMigration3To4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS book_text (bookId TEXT NOT NULL, documentId TEXT NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES shelf(bookId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS text_bindings (bookId TEXT NOT NULL, sourceId TEXT NOT NULL, partId TEXT NOT NULL, bindingJson TEXT NOT NULL, PRIMARY KEY(bookId, sourceId, partId), FOREIGN KEY(bookId) REFERENCES book_text(bookId) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}
