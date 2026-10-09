package app.narrio

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.*
import app.narrio.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfFinishedMigrationTest {
    @Test fun versionFiveBooksStartUnfinishedAndKeepTheirPlace() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "shelf-finished-migration-qa.db"
        context.deleteDatabase(name)
        val schema = NarrioJson.parseToJsonElement(instrumentation.context.assets.open("app.narrio.data.LibraryDatabase/5.json").bufferedReader().use { it.readText() }).jsonObject["database"]!!.jsonObject
        val source = AudioSource("mp3", "Parts", "MP3", listOf(AudioPart("part", "1.mp3", "Chapter", durationMs = 200000)))
        val book = Audiobook("v5-shelf", "Book", "Author", sources = listOf(source))
        val position = SharedPosition(book.id, PositionOrigin.LISTENING, audio = AudioCursor(source.id, "part", 123456), audioConfidence = MappingConfidence.EXACT, sequence = 3, updatedAtMs = 7)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            schema.objects("entities").forEach { table ->
                old.execSQL(table.text("createSql").replace("\${TABLE_NAME}", table.text("tableName")))
                table.objects("indices").forEach { old.execSQL(it.text("createSql").replace("\${TABLE_NAME}", table.text("tableName"))) }
            }
            old.execSQL("INSERT INTO shelf VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(book.id, NarrioJson.encodeToString(book), NarrioJson.encodeToString(source), "part", 123456, 1, 2, "listening", 0, "", 1))
            old.execSQL("INSERT INTO shared_positions VALUES (?, ?, ?)", arrayOf<Any>(book.id, NarrioJson.encodeToString(position), 3))
            old.execSQL("INSERT INTO bookmarks (bookId, sourceId, partId, positionMs, label, createdAt) VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>(book.id, source.id, "part", 10000, "Moment", 1))
            old.version = 5
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryMigration5To6).build()
        try {
            val dao = db.library()
            val saved = dao.find(book.id)!!
            assertEquals(0L, saved.finishedAt)
            assertEquals(123456L, saved.positionMs)
            assertTrue(saved.hasAudio)
            assertEquals(position, RoomSharedPositionStore(dao).current(book.id))
            assertEquals(1, dao.bookmarks(book.id).first().size)

            // Finishing keeps the first finish time; not finished clears it; progress saves never touch it.
            dao.finished(book.id, time = 100)
            dao.finished(book.id, time = 200)
            assertEquals(100L, dao.find(book.id)!!.finishedAt)
            dao.progress(book.id, NarrioJson.encodeToString(source), "part", 150000, 300)
            assertEquals(100L, dao.find(book.id)!!.finishedAt)
            dao.save(book.copy(description = "Refreshed"))
            assertEquals(100L, dao.observeShelf().first().single().finishedAt)
            dao.unfinished(book.id)
            assertEquals(0L, dao.find(book.id)!!.finishedAt)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
