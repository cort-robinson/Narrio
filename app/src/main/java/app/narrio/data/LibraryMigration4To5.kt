package app.narrio.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.narrio.domain.*
import kotlinx.serialization.encodeToString
import java.io.File

val LibraryMigration4To5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE shelf ADD COLUMN hasAudio INTEGER NOT NULL DEFAULT 0")
        for ((name, type) in listOf("editionId" to "TEXT", "resource" to "TEXT", "offset" to "INTEGER", "normalizationVersion" to "INTEGER", "progression" to "REAL", "locatorJson" to "TEXT")) {
            db.execSQL("ALTER TABLE bookmarks ADD COLUMN $name $type")
        }
        db.execSQL("CREATE TABLE ebook_editions (bookId TEXT NOT NULL, editionId TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, format TEXT NOT NULL, language TEXT NOT NULL, attribution TEXT NOT NULL, provider TEXT NOT NULL, addedAtMs INTEGER NOT NULL, PRIMARY KEY(bookId, editionId), FOREIGN KEY(bookId) REFERENCES shelf(bookId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        // Originals/normalized JSON stay at their original private paths. Metadata is hydrated on first access.
        db.execSQL("INSERT INTO ebook_editions SELECT b.bookId, b.documentId, '', '', '', '', '', '', s.savedAt FROM book_text b JOIN shelf s ON s.bookId = b.bookId")
        val filesRoot = db.path?.let { File(File(it).parentFile?.parentFile, "files/follow-along") }
        db.query("SELECT bookId, editionId FROM ebook_editions").use { rows ->
            while (rows.moveToNext()) {
                val book = rows.getString(0)
                val edition = rows.getString(1)
                // A damaged or missing document must not prevent migrating the listening library.
                if (filesRoot != null && edition.matches(Regex("[a-f0-9]{64}"))) {
                    val text = runCatching { EditionFileStorage(filesRoot).load(book, edition) }.getOrNull()
                    if (text != null) db.execSQL("UPDATE ebook_editions SET title = ?, author = ?, format = ?, language = ?, attribution = ? WHERE bookId = ? AND editionId = ?",
                        arrayOf(text.title, text.author, text.format, text.language, text.attribution, book, edition))
                }
            }
        }
        db.execSQL("CREATE TABLE text_bindings_v5 (bookId TEXT NOT NULL, sourceId TEXT NOT NULL, partId TEXT NOT NULL, bindingJson TEXT NOT NULL, editionId TEXT NOT NULL, PRIMARY KEY(bookId, editionId, sourceId, partId), FOREIGN KEY(bookId, editionId) REFERENCES ebook_editions(bookId, editionId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("INSERT INTO text_bindings_v5 SELECT t.bookId, t.sourceId, t.partId, t.bindingJson, b.documentId FROM text_bindings t JOIN book_text b ON b.bookId = t.bookId")
        db.execSQL("DROP TABLE text_bindings")
        db.execSQL("ALTER TABLE text_bindings_v5 RENAME TO text_bindings")
        db.execSQL("CREATE TABLE book_text_v5 (bookId TEXT NOT NULL, documentId TEXT NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES shelf(bookId) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(bookId, documentId) REFERENCES ebook_editions(bookId, editionId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("INSERT INTO book_text_v5 SELECT * FROM book_text")
        db.execSQL("DROP TABLE book_text")
        db.execSQL("ALTER TABLE book_text_v5 RENAME TO book_text")
        db.execSQL("CREATE INDEX index_book_text_bookId_documentId ON book_text(bookId, documentId)")
        db.execSQL("CREATE TABLE shared_positions (bookId TEXT NOT NULL, positionJson TEXT NOT NULL, sequence INTEGER NOT NULL, PRIMARY KEY(bookId), FOREIGN KEY(bookId) REFERENCES shelf(bookId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE annotations (id TEXT NOT NULL, bookId TEXT NOT NULL, editionId TEXT NOT NULL, startCursorJson TEXT NOT NULL, endCursorJson TEXT NOT NULL, color TEXT NOT NULL, note TEXT NOT NULL, selectedText TEXT NOT NULL, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(bookId, editionId) REFERENCES ebook_editions(bookId, editionId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_annotations_bookId_editionId ON annotations(bookId, editionId)")
        db.execSQL("CREATE TABLE alignment_jobs (bookId TEXT NOT NULL, editionId TEXT NOT NULL, recordingId TEXT NOT NULL, sourceId TEXT NOT NULL, partId TEXT NOT NULL, status TEXT NOT NULL, progress REAL NOT NULL, textFingerprint TEXT NOT NULL, audioFingerprint TEXT NOT NULL, normalizationVersion INTEGER NOT NULL, alignmentVersion INTEGER NOT NULL, resumePositionMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, PRIMARY KEY(bookId, editionId, recordingId, sourceId, partId), FOREIGN KEY(bookId, editionId) REFERENCES ebook_editions(bookId, editionId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.query("SELECT bookId, bookJson, sourceJson, partId, positionMs, playedAt FROM shelf").use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0)
                val book = runCatching { NarrioJson.decodeFromString<Audiobook>(rows.getString(1)) }.getOrNull()
                val source = runCatching { NarrioJson.decodeFromString<AudioSource>(rows.getString(2)) }.getOrNull()
                if (book?.sources?.any { it.parts.isNotEmpty() } == true || source != null)
                    db.execSQL("UPDATE shelf SET hasAudio = 1 WHERE bookId = ?", arrayOf(id))
                if (source != null && rows.getString(3).isNotBlank()) {
                    val position = SharedPosition(id, PositionOrigin.LISTENING,
                        audio = AudioCursor(source.id, rows.getString(3), rows.getLong(4)), audioConfidence = MappingConfidence.EXACT,
                        sequence = 1, updatedAtMs = rows.getLong(5))
                    db.execSQL("INSERT INTO shared_positions VALUES (?, ?, ?)", arrayOf(id, NarrioJson.encodeToString(position), 1L))
                }
            }
        }
    }
}
