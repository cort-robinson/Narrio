package app.narrio

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.*
import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingStorageMigrationTest {
    @Test fun epubImportUsesOpfMetadataBeforeCatalogEnrichment() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val http = OkHttpClient()
        val storage = FollowAlongStore(context, db.library(), http, TorBoxDelivery(http, { null }))
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            mapOf(
                "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>",
                "book.opf" to "<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>OPF QA Book</dc:title><dc:creator>QA Writer</dc:creator><dc:language>en</dc:language></metadata><manifest><item id='c' href='chapter.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='c'/></spine></package>",
                "chapter.xhtml" to "<html><body><p>The imported chapter is available offline.</p></body></html>",
            ).forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
        }
        var id = ""
        try {
            val importer = LocalEbookImporter(context, db.library(), storage) { book ->
                assertEquals("OPF QA Book", book.title)
                assertEquals("QA Writer", book.author)
                assertEquals("en", book.language)
                assertTrue(book.sources.isEmpty())
                book.copy(description = "Catalog description", metadataSource = "Controlled catalog")
            }
            val book = importer.import(output.toByteArray(), "EPUB", "Filename title")
            id = book.id
            assertEquals("Catalog description", book.description)
            val edition = storage.editions(id).single()
            assertEquals("EPUB", edition.format)
            assertEquals("OPF QA Book", edition.title)
            assertArrayEquals(output.toByteArray(), storage.original(id, edition.id)!!.readBytes())
        } finally { if (id.isNotBlank()) storage.remove(id); db.close() }
    }

    @Test fun versionFourPreservesAttachmentsAnchorsPreparationBookmarksAndListening() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "reading-migration-qa.db"
        context.deleteDatabase(name)
        val schema = NarrioJson.parseToJsonElement(instrumentation.context.assets.open("app.narrio.data.LibraryDatabase/4.json").bufferedReader().use { it.readText() }).jsonObject["database"]!!.jsonObject
        val source = AudioSource("mp3", "Parts", "MP3", listOf(AudioPart("part", "1.mp3", "Chapter", durationMs = 200000)))
        val book = Audiobook("legacy-reading", "Book", "Author", sources = listOf(source))
        val bytes = "Chapter One\n\nAn existing edition with saved anchors.".toByteArray()
        val document = BookTextParser.parse(bytes, "TXT", book.title, book.author)
        val folder = File(context.filesDir, "follow-along/${BookTextParser.fingerprint(book.id.toByteArray())}").apply { mkdirs() }
        File(folder, "${document.id}.txt").writeBytes(bytes)
        File(folder, "${document.id}.json").writeText(NarrioJson.encodeToString(document))
        val binding = TextBinding(document.id, source.id, "part", document.chapters.first().id, listOf(TextAnchor(document.chapters.first().passages.first().id, 10000, 3, true)))
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            schema.objects("entities").forEach { table ->
                old.execSQL(table.text("createSql").replace("\${TABLE_NAME}", table.text("tableName")))
                table.objects("indices").forEach { old.execSQL(it.text("createSql").replace("\${TABLE_NAME}", table.text("tableName"))) }
            }
            old.execSQL("INSERT INTO shelf VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(book.id, NarrioJson.encodeToString(book), NarrioJson.encodeToString(source), "part", 123456, 1, 2, "preparing", 99, "M4B"))
            old.execSQL("INSERT INTO bookmarks VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(1, book.id, source.id, "part", 10000, "Moment", 1))
            old.execSQL("INSERT INTO positions VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>(book.id, source.id, NarrioJson.encodeToString(source), "part", 123456, 2))
            old.execSQL("INSERT INTO book_text VALUES (?, ?)", arrayOf(book.id, document.id))
            old.execSQL("INSERT INTO text_bindings VALUES (?, ?, ?, ?)", arrayOf(book.id, source.id, "part", NarrioJson.encodeToString(binding)))
            old.version = 4
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryMigration4To5).build()
        try {
            val dao = db.library()
            val saved = dao.find(book.id)!!
            assertEquals("preparing", saved.state)
            assertEquals(99L, saved.preparationId)
            assertEquals("M4B", saved.pendingFormat)
            assertEquals(123456L, saved.positionMs)
            assertEquals(123456L, dao.position(book.id, source.id)!!.positionMs)
            assertEquals(document.id, dao.editions(book.id).single().editionId)
            assertEquals("TXT", dao.editions(book.id).single().format)
            assertEquals(setOf(BookFormat.AUDIO, BookFormat.EBOOK), dao.observeShelfState(audio = true, ebook = true).first().single().formats)
            assertArrayEquals(bytes, File(folder, "${document.id}.txt").readBytes())
            assertEquals(document.id, dao.bookText(book.id)!!.documentId)
            assertEquals(binding, dao.observeTextBindings(book.id).first().single().binding())
            val migrated = RoomSharedPositionStore(dao).current(book.id)!!
            assertEquals(PositionOrigin.LISTENING, migrated.origin)
            assertEquals(AudioCursor("mp3", "part", 123456), migrated.audio)
            assertEquals(1L, migrated.sequence)
            assertEquals(1, dao.bookmarks(book.id).first().size)
            assertNull(dao.bookmarks(book.id).first().single().contentCursor())
            dao.putEdition(EbookEditionEntry(book.id, "second", format = "TXT"))
            dao.activateEdition(book.id, "second")
            assertTrue(dao.observeTextBindings(book.id).first().isEmpty())
            dao.activateEdition(book.id, document.id)
            assertEquals(binding, dao.observeTextBindings(book.id).first().single().binding())
            dao.removeEdition(book.id, "second")
            assertEquals(binding, dao.observeTextBindings(book.id).first().single().binding())
            dao.removeText(book.id)
            assertTrue(dao.observeTextBindings(book.id).first().isEmpty())
            assertEquals(migrated, RoomSharedPositionStore(dao).current(book.id))
            assertEquals(1, dao.bookmarks(book.id).first().size)
        } finally { db.close(); context.deleteDatabase(name); folder.deleteRecursively() }
    }

    @Test fun concurrentWritersOnlyCommitOneSequenceAndEbookImportKeepsAllEditions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val dao = db.library()
        val files = FollowAlongStore(context, dao, OkHttpClient(), TorBoxDelivery(OkHttpClient(), { null }))
        var id = ""
        try {
            val importer = LocalEbookImporter(context, dao, files) { throw IllegalStateException("offline") }
            val first = importer.import("First edition text.".toByteArray(), "TXT", "Imported QA book")
            id = first.id
            importer.import("Second edition text.".toByteArray(), "TXT", "Imported QA book")
            assertTrue(dao.find(id)!!.book().sources.isEmpty())
            val editions = files.editions(id)
            assertEquals(2, editions.size)
            assertEquals(1, editions.count { it.active })
            assertNotNull(files.original(id, editions.first().id))
            val firstEdition = editions.first().id
            files.activateEdition(id, firstEdition)
            assertEquals(firstEdition, files.editions(id).single { it.active }.id)
            val row = dao.observeShelfState(ebook = true).first().single()
            assertEquals(setOf(BookFormat.EBOOK), row.formats)
            assertTrue(dao.observeShelfState(audio = true).first().isEmpty())
            val stores = List(8) { RoomSharedPositionStore(dao) }
            val update = PositionUpdate(id, PositionOrigin.READING, text = ContentCursor(firstEdition, "text", 0, progression = 0.5), basedOnSequence = 0)
            val results = coroutineScope { stores.map { async(Dispatchers.Default) { it.commit(update) } }.awaitAll() }
            assertEquals(1, results.count { it != null })
            assertEquals(1L, stores.first().current(id)!!.sequence)
            assertEquals(0.5, dao.observeShelfState().first().single().progression!!, 0.0)
            val cursor = ContentCursor(firstEdition, "text", 0)
            dao.bookmark(BookmarkEntry(bookId = id, sourceId = "", partId = "", positionMs = 0, label = "Read here", editionId = firstEdition,
                resource = cursor.resource, offset = cursor.offset, normalizationVersion = cursor.normalizationVersion, progression = cursor.progression, locatorJson = cursor.locatorJson))
            assertEquals(cursor, dao.bookmarks(id).first().single().contentCursor())
            dao.putAnnotation(AnnotationEntry("qa-note", id, firstEdition, NarrioJson.encodeToString(cursor), NarrioJson.encodeToString(cursor.copy(offset = 5)), "copper", "Note", "First", 1, 2))
            dao.putAlignmentJob(AlignmentJobEntry(id, firstEdition, "recording", "source", "part", textFingerprint = firstEdition, audioFingerprint = "audio", progress = 0.25))
            assertEquals("Note", dao.annotations(id, firstEdition).first().single().note)
            assertEquals(0.25, dao.alignmentJob(id, firstEdition, "recording", "source", "part")!!.progress, 0.0)
            files.removeEdition(id, firstEdition)
            assertNull(files.original(id, firstEdition))
            assertTrue(dao.annotations(id, firstEdition).first().isEmpty())
            assertTrue(dao.alignmentJobs(id, firstEdition).first().isEmpty())
            assertEquals(1, files.editions(id).size)
            assertEquals(1, dao.bookmarks(id).first().size)
            dao.remove(id)
            assertNull(stores.first().current(id))
        } finally { if (id.isNotBlank()) files.remove(id); db.close() }
    }
}
