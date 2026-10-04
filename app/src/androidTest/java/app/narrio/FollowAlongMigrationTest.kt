package app.narrio

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.*
import app.narrio.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowAlongMigrationTest {
    @Test fun versionThreeUpgradePreservesListeningHistoryAndCascadesOnlyBookText() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "follow-migration-qa.db"
        context.deleteDatabase(name)
        val oldSchema = NarrioJson.parseToJsonElement(instrumentation.context.assets.open("app.narrio.data.LibraryDatabase/3.json").bufferedReader().use { it.readText() }).jsonObject["database"]!!.jsonObject
        val source = AudioSource("legacy-mp3", "Parts", "MP3", listOf(AudioPart("legacy-part", "1.mp3", "Chapter I")))
        val book = Audiobook("legacy-book", "Saved Book", "Saved Author", sources = listOf(source))
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            oldSchema.objects("entities").forEach { table ->
                old.execSQL(table.text("createSql").replace("\${TABLE_NAME}", table.text("tableName")))
                table.objects("indices").forEach { old.execSQL(it.text("createSql").replace("\${TABLE_NAME}", table.text("tableName"))) }
            }
            old.execSQL("INSERT INTO shelf VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(book.id, NarrioJson.encodeToString(book), NarrioJson.encodeToString(source), "legacy-part", 123456, 1, 2, "listening", 0, "M4B"))
            old.execSQL("INSERT INTO bookmarks VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(1, book.id, source.id, "legacy-part", 123456, "Saved moment", 1))
            old.execSQL("INSERT INTO positions VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>(book.id, source.id, NarrioJson.encodeToString(source), "legacy-part", 123456, 1))
            old.version = 3
        }
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryMigration3To4, LibraryMigration4To5).build()
        try {
            val dao = database.library()
            assertEquals(123456L, dao.find(book.id)!!.positionMs)
            assertEquals("M4B", dao.find(book.id)!!.pendingFormat)
            assertEquals(1, dao.bookmarks(book.id).first().size)
            assertEquals(123456L, dao.position(book.id, source.id)!!.positionMs)
            dao.attachText(book, "document")
            dao.putTextBinding(TextBindingEntry(book.id, source.id, "legacy-part", NarrioJson.encodeToString(TextBinding("document", source.id, "legacy-part", "chapter"))))
            dao.removeText(book.id)
            assertTrue(dao.observeTextBindings(book.id).first().isEmpty())
            assertEquals(123456L, dao.find(book.id)!!.positionMs)
            assertEquals(1, dao.bookmarks(book.id).first().size)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
